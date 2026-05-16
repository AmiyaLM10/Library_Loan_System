import java.sql.*;
import java.util.*;

/**
 * PerformanceEvaluator
 * Benchmarks four JDBC access strategies with warm-up, multiple runs,
 * and a formatted console/CSV report.
 *
 * Test suites:
 *   1. Insert Strategy   – individual executeUpdate() vs addBatch()/executeBatch()
 *   2. Query Strategy    – full-table scan vs indexed lookup
 *   3. Statement Type    – Statement (string concat) vs PreparedStatement
 *   4. Transaction Gran. – per-operation commit vs batched commit (100 ops)
 */
public class PerformanceEvaluator {

    private static final int SMALL_N   = 1_000;
    private static final int LARGE_N   = 10_000;
    private static final int RUNS      = 5;
    private static final int WARMUP_MS = 300;

    private final Connection conn;

    // Stores results: [operationType, recordCount, avgMs, throughput, observation]
    private final List<String[]> results = new ArrayList<>();

    public PerformanceEvaluator(Connection conn) {
        this.conn = conn;
    }

    // ===================================================================
    // Public entry point
    // ===================================================================

    public void runAllBenchmarks() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║         JDBC PERFORMANCE EVALUATION SUITE            ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        try {
            conn.setAutoCommit(false);

            suite1_InsertStrategy();
            suite2_QueryStrategy();
            suite3_StatementType();
            suite4_TransactionGranularity();

            conn.rollback(); // Clean up all bench data
            conn.setAutoCommit(true);

        } catch (SQLException e) {
            System.err.println("[Bench] Fatal error: " + e.getMessage());
            try { conn.rollback(); conn.setAutoCommit(true); } catch (SQLException ignored) {}
        }

        printReport();
    }

    // ===================================================================
    // Suite 1 – Insert Strategy
    // ===================================================================

    private void suite1_InsertStrategy() throws SQLException {
        System.out.println("\n--- Suite 1: Insert Strategy ---");

        for (int n : new int[]{SMALL_N, LARGE_N}) {
            warmUp();

            // 1a Individual inserts
            long[] timesIndividual = new long[RUNS];
            for (int r = 0; r < RUNS; r++) {
                truncateBenchTable();
                long start = System.nanoTime();
                insertIndividual(n);
                conn.commit();
                timesIndividual[r] = (System.nanoTime() - start) / 1_000_000;
                truncateBenchTable(); conn.commit();
            }

            // 1b Batch inserts
            long[] timesBatch = new long[RUNS];
            for (int r = 0; r < RUNS; r++) {
                truncateBenchTable();
                long start = System.nanoTime();
                insertBatch(n);
                conn.commit();
                timesBatch[r] = (System.nanoTime() - start) / 1_000_000;
                truncateBenchTable(); conn.commit();
            }

            double avgInd   = mean(timesIndividual);
            double avgBatch = mean(timesBatch);
            double tpsInd   = throughput(n, avgInd);
            double tpsBatch = throughput(n, avgBatch);

            record("Individual INSERT",   n, avgInd,   tpsInd,
                   "Baseline; one round-trip per row");
            record("Batch INSERT",        n, avgBatch, tpsBatch,
                   String.format("%.1fx faster than individual", avgInd / avgBatch));

            System.out.printf("  [n=%,d] Individual=%.1f ms | Batch=%.1f ms%n",
                n, avgInd, avgBatch);
        }
    }

    // ===================================================================
    // Suite 2 – Query Strategy
    // ===================================================================

    private void suite2_QueryStrategy() throws SQLException {
        System.out.println("\n--- Suite 2: Query Strategy (Full Scan vs Indexed Lookup) ---");

        // Populate bench table for queries
        truncateBenchTable();
        insertBatch(LARGE_N);
        conn.commit();
        warmUp();

        // 2a Full-table scan (no index column filter)
        long[] timesScan = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            long start = System.nanoTime();
            fullTableScan();
            timesScan[r] = (System.nanoTime() - start) / 1_000_000;
        }

        // 2b Indexed lookup on BenchID (PK, always indexed)
        long[] timesIdx = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            long start = System.nanoTime();
            indexedLookup(LARGE_N / 2);   // pick a mid-range id
            timesIdx[r] = (System.nanoTime() - start) / 1_000_000;
        }

        double avgScan = mean(timesScan);
        double avgIdx  = mean(timesIdx);

        record("Full-Table Scan",   LARGE_N, avgScan, throughput(LARGE_N, avgScan),
               "Reads all rows; O(n)");
        record("Indexed Lookup",    1,       avgIdx,  throughput(1, avgIdx),
               "Direct B-tree seek; O(log n)");

        System.out.printf("  Full scan=%.1f ms | Indexed lookup=%.3f ms%n",
            avgScan, avgIdx);

        truncateBenchTable(); conn.commit();
    }

    // ===================================================================
    // Suite 3 – Statement vs PreparedStatement
    // ===================================================================

    private void suite3_StatementType() throws SQLException {
        System.out.println("\n--- Suite 3: Statement vs PreparedStatement ---");
        warmUp();

        int n = SMALL_N;

        // 3a Plain Statement (string concat, no cache)
        long[] timesStmt = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            truncateBenchTable();
            long start = System.nanoTime();
            insertWithStatement(n);
            conn.commit();
            timesStmt[r] = (System.nanoTime() - start) / 1_000_000;
            truncateBenchTable(); conn.commit();
        }

        // 3b PreparedStatement (pre-compiled, parameterized)
        long[] timesPS = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            truncateBenchTable();
            long start = System.nanoTime();
            insertWithPrepared(n);
            conn.commit();
            timesPS[r] = (System.nanoTime() - start) / 1_000_000;
            truncateBenchTable(); conn.commit();
        }

        double avgStmt = mean(timesStmt);
        double avgPS   = mean(timesPS);

        record("Statement (concat)",  n, avgStmt, throughput(n, avgStmt),
               "Re-parses SQL every call; SQL injection risk");
        record("PreparedStatement",   n, avgPS,   throughput(n, avgPS),
               "Pre-compiled; parameterized; safer & faster");

        System.out.printf("  Statement=%.1f ms | PreparedStatement=%.1f ms%n",
            avgStmt, avgPS);
    }

    // ===================================================================
    // Suite 4 – Transaction Granularity
    // ===================================================================

    private void suite4_TransactionGranularity() throws SQLException {
        System.out.println("\n--- Suite 4: Transaction Granularity ---");
        warmUp();

        int ops = 100;

        // 4a Per-operation commit
        long[] timesPerOp = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            truncateBenchTable(); conn.commit();
            long start = System.nanoTime();
            perOperationCommit(ops);
            timesPerOp[r] = (System.nanoTime() - start) / 1_000_000;
            truncateBenchTable(); conn.commit();
        }

        // 4b Single batched commit
        long[] timesBatch = new long[RUNS];
        for (int r = 0; r < RUNS; r++) {
            truncateBenchTable(); conn.commit();
            long start = System.nanoTime();
            batchedCommit(ops);
            conn.commit();
            timesBatch[r] = (System.nanoTime() - start) / 1_000_000;
            truncateBenchTable(); conn.commit();
        }

        double avgPerOp = mean(timesPerOp);
        double avgBatch = mean(timesBatch);

        record("Per-op Commit (100 ops)",    ops, avgPerOp,
               throughput(ops, avgPerOp),
               "100 fsync calls; high durability overhead");
        record("Batched Commit (100 ops)",   ops, avgBatch,
               throughput(ops, avgBatch),
               String.format("%.1fx faster; single fsync", avgPerOp / avgBatch));

        System.out.printf("  Per-op commit=%.1f ms | Batched commit=%.1f ms%n",
            avgPerOp, avgBatch);
    }

    // ===================================================================
    // Bench table helpers
    // ===================================================================

    private void ensureBenchTable() throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getTables(null, "APP", "BENCH_DATA", new String[]{"TABLE"})) {
            if (rs.next()) return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE Bench_Data (" +
                "  BenchID INT NOT NULL GENERATED ALWAYS AS IDENTITY," +
                "  Payload VARCHAR(100)," +
                "  NumVal  INT," +
                "  CONSTRAINT PK_Bench PRIMARY KEY (BenchID)" +
                ")"
            );
            conn.commit();
        }
    }

    private void truncateBenchTable() throws SQLException {
        ensureBenchTable();
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM Bench_Data");
        }
    }

    // ===================================================================
    // Insert implementations
    // ===================================================================

    private void insertIndividual(int n) throws SQLException {
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                ps.setString(1, "record_" + i);
                ps.setInt(2, i);
                ps.executeUpdate();
            }
        }
    }

    private void insertBatch(int n) throws SQLException {
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                ps.setString(1, "record_" + i);
                ps.setInt(2, i);
                ps.addBatch();
                if (i % 500 == 0) ps.executeBatch(); // flush every 500
            }
            ps.executeBatch();
        }
    }

    @SuppressWarnings("SqlResolve")
    private void insertWithStatement(int n) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (int i = 0; i < n; i++) {
                st.executeUpdate(
                    "INSERT INTO Bench_Data (Payload, NumVal) VALUES ('record_" + i
                    + "', " + i + ")"
                );
            }
        }
    }

    private void insertWithPrepared(int n) throws SQLException {
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < n; i++) {
                ps.setString(1, "record_" + i);
                ps.setInt(2, i);
                ps.executeUpdate();
            }
        }
    }

    // ===================================================================
    // Query implementations
    // ===================================================================

    private void fullTableScan() throws SQLException {
        // Deliberately avoid index: aggregate on non-indexed column
        String sql = "SELECT COUNT(*), SUM(NumVal) FROM Bench_Data WHERE NumVal >= 0";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next(); // consume result
        }
    }

    private void indexedLookup(int id) throws SQLException {
        // Primary key lookup – always uses index
        String sql = "SELECT Payload, NumVal FROM Bench_Data WHERE BenchID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    // ===================================================================
    // Transaction granularity implementations
    // ===================================================================

    private void perOperationCommit(int ops) throws SQLException {
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        for (int i = 0; i < ops; i++) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "txn_" + i);
                ps.setInt(2, i);
                ps.executeUpdate();
            }
            conn.commit(); // commit every single op
        }
    }

    private void batchedCommit(int ops) throws SQLException {
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ops; i++) {
                ps.setString(1, "txn_" + i);
                ps.setInt(2, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        // single commit at the end (called by caller)
    }

    // ===================================================================
    // Warm-up
    // ===================================================================

    private void warmUp() throws SQLException {
        long end = System.currentTimeMillis() + WARMUP_MS;
        String sql = "INSERT INTO Bench_Data (Payload, NumVal) VALUES (?, ?)";
        ensureBenchTable();
        while (System.currentTimeMillis() < end) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "warmup");
                ps.setInt(2, 0);
                ps.executeUpdate();
            }
        }
        truncateBenchTable();
        conn.commit();
    }

    // ===================================================================
    // Stats helpers
    // ===================================================================

    private double mean(long[] vals) {
        long sum = 0;
        for (long v : vals) sum += v;
        return (double) sum / vals.length;
    }

    private double throughput(int n, double avgMs) {
        return avgMs <= 0 ? 0 : (n / (avgMs / 1000.0));
    }

    private void record(String op, int n, double avgMs, double tps, String obs) {
        results.add(new String[]{
            op,
            String.valueOf(n),
            String.format("%.2f", avgMs),
            String.format("%.0f", tps),
            obs
        });
    }

    // ===================================================================
    // Report
    // ===================================================================

    private void printReport() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                          JDBC PERFORMANCE REPORT                                                ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-35s %-10s %-14s %-14s %-25s ║%n",
            "Operation", "Records", "Avg Time(ms)", "Throughput", "Observation");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════════════════╣");
        for (String[] r : results) {
            System.out.printf("║ %-35s %-10s %-14s %-14s %-25s ║%n",
                truncate(r[0], 35),
                r[1],
                r[2],
                r[3] + " ops/s",
                truncate(r[4], 25));
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════════════════╝");
        exportCsv();
    }

    private void exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Operation,Records,AvgTimeMs,ThroughputOpsPerSec,Observation\n");
        for (String[] r : results) {
            sb.append(String.join(",", r)).append("\n");
        }
        System.out.println("\n--- CSV export ---\n" + sb);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
