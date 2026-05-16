# Analysis: Transaction Behavior & JDBC Performance Findings

---

## 1. How Transaction Boundaries Preserve Data Integrity

### The Multi-Step Loan Problem

Issuing a book involves four coordinated writes:

1. Mark the book `Available = 0`
2. Insert a `Loans` row
3. Increment `Members.ActiveLoans`

Without explicit transactions, a crash between steps 1 and 2 would leave the book permanently
marked unavailable with no loan record. The database would be **silently inconsistent** — no
error, no audit trail, just phantom data.

### Explicit `commit()` / `rollback()`

By disabling `autoCommit` before step 1 and committing only after step 3, all three writes
become **atomic**: they either all succeed or all disappear. Derby holds row-level locks across
the steps, preventing other sessions from seeing the half-written state (isolation).

### Savepoints for Partial Rollback

The implementation places two savepoints:

```
updateBookAvailability()     ← write 1
  SP_BEFORE_LOAN             ← savepoint
    insertLoanRecord()       ← write 2
      SP_BEFORE_MEMBER_UPD   ← savepoint
        updateMemberLoans()  ← write 3
```

If write 3 fails (e.g., a CHECK constraint rejects a negative `ActiveLoans` value), rolling back
to `SP_BEFORE_LOAN` undoes writes 2 and 3 **but also undoes write 1**, restoring the book to
Available. This is safer than rolling back only to `SP_BEFORE_MEMBER_UPD`, which would leave an
orphaned loan record.

### Constraint Violation Demo

The `demonstrateRollback()` method inserts two members with the same email in a single
transaction. Derby's `UNIQUE` constraint fires a `SQLException` (SQLState `23505`) on the second
insert. The catch block calls `rollback()`, and the **first** insert — already in Derby's log
buffer but not committed — is also discarded. Running `SELECT COUNT(*) FROM Members` immediately
after confirms zero net change, proving durability-at-rollback (the "D" in ACID for aborted
transactions means: aborted work leaves no trace).

---

## 2. Why Certain JDBC Patterns Outperform Others

### Batch vs Individual Inserts

Derby's embedded engine must flush the transaction log to disk on every `commit()`. With
individual inserts (one commit per row), 10 000 rows require 10 000 log flushes. Batch inserts
group all rows into a single flush, reducing I/O by ~99%. Observed speedups are typically
**10–40×** for large record counts, bounded by JVM overhead at small counts.

### Indexed vs Full-Table Scan

Derby stores B-tree indexes as separate disk pages. A `WHERE BenchID = ?` predicate on a
primary key navigates the B-tree in O(log n) page reads — typically 2–3 I/Os regardless of
table size. A full-table scan reads every page: O(n) I/Os. For 10 000 rows the indexed lookup
completes in < 1 ms; the scan may take 20–100 ms, a difference that compounds at scale.

### `PreparedStatement` vs `Statement`

Each call to `Statement.executeUpdate(sql)` causes Derby to parse, validate, and compile the
SQL from scratch. `PreparedStatement` parses once at construction; subsequent `execute()` calls
reuse the compiled plan. At 1 000 inserts the compilation overhead accumulates into a
measurable gap (typically 15–30% slower with plain `Statement`). `PreparedStatement` also
prevents SQL injection — a correctness benefit that comes at zero extra cost.

### Transaction Granularity (Per-op vs Batched Commit)

Each `commit()` in Derby triggers a **synchronous write** to the on-disk transaction log
(`derby.log`). At 100 per-operation commits, the application waits 100 times for the OS to
confirm the fsync. A single commit after 100 batched inserts performs one fsync, reducing wall
time by 5–20× in benchmarks. The trade-off is durability window: if the process dies mid-batch,
all uncommitted work is lost.

---

## 3. Trade-offs: Safety vs Raw Speed

| Strategy | Safety | Speed | Recommendation |
|---|---|---|---|
| Per-row commit | Highest (1 row max lost on crash) | Slowest | Use only for critical audit trails |
| Batched commit (N rows) | Medium (N rows max lost) | Fast | Default for bulk import |
| Individual inserts | Safe, no batch complexity | Slow | Use for single-record interactive ops |
| Batch inserts | Same durability as commit granularity | Fast | Use for seeding / reporting |
| `Statement` (string concat) | SQL injection risk | Slightly slower | **Never use** in production |
| `PreparedStatement` | Safe, parameterized | Slightly faster | Always use |
| No explicit transaction | Inconsistent on crash | Fastest (no locking) | Only for read-only workloads |

### Recommended Production Profile

- **Interactive user operations** (loan, return, register): explicit transaction with savepoints,
  `PreparedStatement`, per-operation commit — correctness outweighs throughput here.
- **Bulk imports / batch jobs**: `addBatch()` + single commit per 500–1 000 rows, explicit
  transaction, `PreparedStatement` — throughput matters, bounded loss is acceptable.
- **Reporting queries**: `setReadOnly(true)`, no transaction needed, index-aware queries with
  `EXPLAIN`/runtime statistics to verify scan types.

---

## 4. Derby-Specific Observations

- **Buffer cache warm-up**: Derby keeps recently accessed pages in memory. The first run of any
  benchmark is 2–5× slower than subsequent runs due to cold page cache. The 300 ms warm-up loop
  in `PerformanceEvaluator` mitigates this.
- **Embedded vs network mode**: Embedded mode eliminates TCP serialization overhead, making it
  significantly faster for single-JVM applications. Network mode (`jdbc:derby://localhost:1527/`)
  is needed for multi-process access but adds ~0.5–2 ms per round-trip.
- **Log file growth**: Repeated insert/delete benchmarks grow `librarydb/log/` rapidly. In
  production, configure `derby.storage.logArchiveMode` and schedule checkpoints.
- **Runtime statistics**: Enabling `CALL SYSCS_UTIL.SYSCS_SET_RUNTIMESTATISTICS(1)` and
  inspecting `derby.log` confirms whether queries use index scans (`Index Scan`) or heap scans
  (`Table Scan`), validating that indexes created at schema initialization are actually used.
