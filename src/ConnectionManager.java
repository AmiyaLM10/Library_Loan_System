import java.sql.*;

/**
 * ConnectionManager
 * Handles Derby embedded DB initialization, schema creation,
 * seed data population, and connection lifecycle.
 */
public class ConnectionManager {

    private static final String DB_URL = "jdbc:derby:librarydb;create=true";
    private static Connection   connection = null;

    static {
        try {
            // AutoloadedDriver works on Derby 10.11+ with JDK 9+
            Class.forName("org.apache.derby.jdbc.AutoloadedDriver");
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
            } catch (ClassNotFoundException ex) {
                System.err.println("[ConnectionManager] Derby driver not found: " + ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Returns the singleton Connection, creating it (and the schema) on first call.
     */
    public static Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(DB_URL);
            connection.setAutoCommit(true); // default; callers disable as needed
            System.out.println("[ConnectionManager] Connected to Derby: " + DB_URL);
            initializeSchema();
            seedData();
        }
        return connection;
    }

    /**
     * Cleanly shuts down the embedded Derby engine and releases file locks.
     */
    public static void shutdown() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[ConnectionManager] Warning closing connection: " + e.getMessage());
        }
        try {
            DriverManager.getConnection("jdbc:derby:librarydb;shutdown=true");
        } catch (SQLException e) {
            // Derby always throws SQLState 08006 / XJ015 on normal shutdown
            if ("08006".equals(e.getSQLState()) || "XJ015".equals(e.getSQLState())) {
                System.out.println("[ConnectionManager] Derby shut down cleanly.");
            } else {
                System.err.println("[ConnectionManager] Unexpected shutdown error: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------
    // Schema
    // ---------------------------------------------------------------

    private static void initializeSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {

            // --- Members ---
            if (!tableExists("MEMBERS")) {
                st.execute(
                    "CREATE TABLE Members (" +
                    "  MemberID   INT          NOT NULL GENERATED ALWAYS AS IDENTITY," +
                    "  Name       VARCHAR(100) NOT NULL," +
                    "  Email      VARCHAR(150) NOT NULL UNIQUE," +
                    "  Phone      VARCHAR(20)," +
                    "  ActiveLoans INT         NOT NULL DEFAULT 0," +
                    "  JoinDate   DATE         NOT NULL DEFAULT CURRENT_DATE," +
                    "  CONSTRAINT PK_Members PRIMARY KEY (MemberID)," +
                    "  CONSTRAINT CHK_ActiveLoans CHECK (ActiveLoans >= 0)" +
                    ")"
                );
                System.out.println("[Schema] Table Members created.");
            }

            // --- Books ---
            if (!tableExists("BOOKS")) {
                st.execute(
                    "CREATE TABLE Books (" +
                    "  BookID     INT          NOT NULL GENERATED ALWAYS AS IDENTITY," +
                    "  ISBN       VARCHAR(20)  NOT NULL UNIQUE," +
                    "  Title      VARCHAR(200) NOT NULL," +
                    "  Author     VARCHAR(100) NOT NULL," +
                    "  Genre      VARCHAR(50)," +
                    "  Available  SMALLINT     NOT NULL DEFAULT 1," +
                    "  CONSTRAINT PK_Books PRIMARY KEY (BookID)," +
                    "  CONSTRAINT CHK_Available CHECK (Available IN (0,1))" +
                    ")"
                );
                System.out.println("[Schema] Table Books created.");
            }

            // --- Loans ---
            if (!tableExists("LOANS")) {
                st.execute(
                    "CREATE TABLE Loans (" +
                    "  LoanID     INT  NOT NULL GENERATED ALWAYS AS IDENTITY," +
                    "  BookID     INT  NOT NULL," +
                    "  MemberID   INT  NOT NULL," +
                    "  LoanDate   DATE NOT NULL DEFAULT CURRENT_DATE," +
                    "  DueDate    DATE NOT NULL," +
                    "  ReturnDate DATE," +
                    "  CONSTRAINT PK_Loans    PRIMARY KEY (LoanID)," +
                    "  CONSTRAINT FK_LoanBook FOREIGN KEY (BookID)   REFERENCES Books(BookID)," +
                    "  CONSTRAINT FK_LoanMem  FOREIGN KEY (MemberID) REFERENCES Members(MemberID)" +
                    ")"
                );
                System.out.println("[Schema] Table Loans created.");
            }

            // --- Indexes ---
            createIndexIfAbsent(st, "IDX_Books_ISBN",       "Books",   "ISBN");
            createIndexIfAbsent(st, "IDX_Loans_MemberID",   "Loans",   "MemberID");
            createIndexIfAbsent(st, "IDX_Loans_ReturnDate", "Loans",   "ReturnDate");
            createIndexIfAbsent(st, "IDX_Loans_BookID",     "Loans",   "BookID");
        }
    }

    private static void seedData() throws SQLException {
        // Only seed if tables are empty
        try (Statement st = connection.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM Members");
            rs.next();
            if (rs.getInt(1) > 0) return; // already seeded
        }

        String insertMember =
            "INSERT INTO Members (Name, Email, Phone) VALUES (?, ?, ?)";
        String insertBook =
            "INSERT INTO Books (ISBN, Title, Author, Genre) VALUES (?, ?, ?, ?)";

        try (PreparedStatement psM = connection.prepareStatement(insertMember);
             PreparedStatement psB = connection.prepareStatement(insertBook)) {

            // Seed Members
            Object[][] members = {
                {"Alice Sharma",   "alice@lib.org",   "9000000001"},
                {"Bob Das",        "bob@lib.org",     "9000000002"},
                {"Chitra Nair",    "chitra@lib.org",  "9000000003"},
                {"Deepak Mohanty", "deepak@lib.org",  "9000000004"},
                {"Eva Patro",      "eva@lib.org",     "9000000005"}
            };
            for (Object[] m : members) {
                psM.setString(1, (String) m[0]);
                psM.setString(2, (String) m[1]);
                psM.setString(3, (String) m[2]);
                psM.executeUpdate();
            }

            // Seed Books
            Object[][] books = {
                {"978-0132350884", "Clean Code",                  "Robert C. Martin", "Technology"},
                {"978-0201633610", "Design Patterns",             "Gang of Four",     "Technology"},
                {"978-0596517748", "JavaScript: The Good Parts",  "Douglas Crockford","Technology"},
                {"978-0143127741", "The Pragmatic Programmer",    "Hunt & Thomas",    "Technology"},
                {"978-0062316097", "Sapiens",                     "Yuval Noah Harari","History"   },
                {"978-0143110354", "The Selfish Gene",            "Richard Dawkins",  "Science"   },
                {"978-0385333481", "Foundation",                  "Isaac Asimov",     "Sci-Fi"    },
                {"978-0307474278", "The Road",                    "Cormac McCarthy",  "Fiction"   },
                {"978-0618640157", "The Lord of the Rings",       "J.R.R. Tolkien",   "Fantasy"   },
                {"978-0679720201", "Crime and Punishment",        "Dostoevsky",       "Classic"   }
            };
            for (Object[] b : books) {
                psB.setString(1, (String) b[0]);
                psB.setString(2, (String) b[1]);
                psB.setString(3, (String) b[2]);
                psB.setString(4, (String) b[3]);
                psB.executeUpdate();
            }
        }
        System.out.println("[Seed] Baseline data inserted (5 members, 10 books).");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static boolean tableExists(String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, "APP", tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static void createIndexIfAbsent(Statement st, String idxName,
                                            String table, String col) {
        try {
            st.execute("CREATE INDEX " + idxName + " ON " + table + "(" + col + ")");
            System.out.println("[Schema] Index " + idxName + " created.");
        } catch (SQLException e) {
            if (!"X0Y32".equals(e.getSQLState())) { // X0Y32 = index already exists
                System.err.println("[Schema] Index warning (" + idxName + "): " + e.getMessage());
            }
        }
    }
}
