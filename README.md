# Library Loan Management System
### End-to-End JDBC Application · Apache Derby · Transaction Management & Performance Evaluation

---

## Project Structure

```
LibraryLoanSystem/
├── src/
│   ├── ConnectionManager.java     # Derby init, schema, seed data, shutdown
│   ├── TransactionService.java    # Explicit TX: commit / rollback / savepoints
│   ├── BusinessLogic.java         # CRUD queries, display formatting
│   ├── PerformanceEvaluator.java  # 4-suite benchmark framework
│   └── MainApp.java               # CLI entry point
├── lib/
│   └── derby.jar                  # Apache Derby embedded engine (place here)
├── README.md
└── analysis.md
```

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java JDK | 11 or later |
| Apache Derby | 10.16+ (embedded) |

### Download Derby

```bash
# Download from Apache mirrors
wget https://downloads.apache.org/db/derby/db-derby-10.16.1.1/db-derby-10.16.1.1-bin.tar.gz
tar -xzf db-derby-10.16.1.1-bin.tar.gz
cp db-derby-10.16.1.1-bin/lib/derby.jar lib/
```

Or use Maven (see below).

---

## Build & Run

### Option A – Manual javac

```bash
# 1. Create output directory
mkdir -p out

# 2. Compile all sources
javac -cp "lib/derby.jar" -d out src/*.java

# 3. Run
java -cp "out:lib/derby.jar" MainApp
# Windows:
java -cp "out;lib/derby.jar" MainApp
```

### Option B – Maven (pom.xml)

Create `pom.xml` in the project root:

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.library</groupId>
  <artifactId>library-loan-system</artifactId>
  <version>1.0</version>

  <dependencies>
    <dependency>
      <groupId>org.apache.derby</groupId>
      <artifactId>derby</artifactId>
      <version>10.16.1.1</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <configuration>
          <archive>
            <manifest>
              <mainClass>MainApp</mainClass>
            </manifest>
          </archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

```bash
mvn compile
mvn exec:java -Dexec.mainClass="MainApp"
```

---

## Database

- **Mode**: Embedded Derby (`jdbc:derby:librarydb;create=true`)
- **Location**: `librarydb/` directory created in the working directory on first run.
- **Shutdown**: Automatically triggered on exit via `jdbc:derby:librarydb;shutdown=true`
- **Schema**: Three normalized tables — `Members`, `Books`, `Loans` — with FK constraints and indexes.
- **Seed data**: 5 members + 10 books inserted automatically on first boot.

---

## Sample CLI Session

```
╔══════════════════════════════════════════════╗
║    Library Loan Management System (Derby)    ║
╚══════════════════════════════════════════════╝
[ConnectionManager] Connected to Derby: jdbc:derby:librarydb;create=true
[Schema] Table Members created.
[Schema] Table Books created.
[Schema] Table Loans created.
[Seed] Baseline data inserted (5 members, 10 books).

══════════════════ MAIN MENU ══════════════════
 1.  Register a Member
 2.  Add a Book
 3.  Process Loan (issue book)
 4.  Return Book
...
 10. Run Performance Benchmarks
 11. Demo: ACID Rollback on Constraint Violation
 0.  Exit
Choice: 3

--- Process Loan ---
[Book listing appears here]
Enter Book ID   : 1
[Member listing appears here]
Enter Member ID : 1
[TX] Book 1 marked unavailable.
[TX] Loan record inserted.
[TX] Member 1 active loan count incremented.
[TX] processLoan COMMITTED for book=1 member=1
✔ Loan processed successfully.

Choice: 11

--- ACID Rollback Demonstration ---
Using test email: test_rollback_1716800000000@demo.org
[TX-DEMO] Inserting first member ...
[TX-DEMO] Attempting duplicate email insert (should fail) ...
[TX-DEMO] Caught expected violation: The statement was aborted because it would have caused a duplicate key value ...
[TX-DEMO] SQLState=23505 – rolling back entire transaction.
[TX-DEMO] Rollback complete. Data integrity preserved.

Choice: 10
[!] Performance benchmarks may take 1–3 minutes.
Proceed? (y/n): y
[Benchmark output with table and CSV]

Choice: 0
Goodbye!
[ConnectionManager] Derby shut down cleanly.
```

---

## Key Design Decisions

| Concern | Decision |
|---|---|
| Transaction control | `autoCommit=false` for all writes; explicit `commit()`/`rollback()` |
| Partial failure | Savepoints (`SP_BEFORE_LOAN`, `SP_BEFORE_MEMBER_UPDATE`) enable granular rollback |
| SQL injection | All queries use `PreparedStatement`; no string concatenation in production paths |
| Resource cleanup | `try-with-resources` for all `Connection`, `Statement`, `ResultSet` objects |
| Derby shutdown | Explicit `DriverManager.getConnection("...;shutdown=true")` on exit |

---

## Performance Benchmark Suites

| Suite | What is compared |
|---|---|
| 1 – Insert Strategy | Individual `executeUpdate()` vs `addBatch()/executeBatch()` at 1 000 & 10 000 rows |
| 2 – Query Strategy | Full-table scan vs PK indexed lookup |
| 3 – Statement Type | `Statement` (string concat) vs `PreparedStatement` |
| 4 – TX Granularity | Per-operation `commit()` vs single batched `commit()` for 100 ops |

Each test runs **5 times**; results show average ms and throughput (ops/sec).

---

## Dependencies

```
org.apache.derby:derby:10.16.1.1   (embedded engine, ~3 MB)
```
No other runtime dependencies.
