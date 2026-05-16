import java.sql.*;

/**
 * TransactionService
 * Manages explicit transaction boundaries: commit, rollback, and savepoints.
 * All data-modifying operations must go through this layer.
 */
public class TransactionService {

    private final Connection conn;

    public TransactionService(Connection conn) {
        this.conn = conn;
    }

    // ---------------------------------------------------------------
    // Core: Process Loan  (multi-step, savepoint-aware transaction)
    // ---------------------------------------------------------------

    /**
     * Issues a book to a member.
     * Steps (all-or-nothing):
     *   1. Verify book exists and is available.
     *   2. Mark book as unavailable.
     *   3. SP1 – savepoint before loan insert.
     *   4. Insert loan record.
     *   5. SP2 – savepoint before member counter update.
     *   6. Increment member's ActiveLoans.
     *   7. Commit.
     *
     * If step 6 fails  → rollback to SP1  (loan record rolled back, book status rolled back).
     * If step 4 fails  → rollback to SP1  (book status rolled back).
     * Any other error  → full rollback.
     */
    public boolean processLoan(int bookId, int memberId) {
        Savepoint sp1 = null;
        Savepoint sp2 = null;

        try {
            conn.setAutoCommit(false);

            // Step 1 – Verify availability
            if (!isBookAvailable(bookId)) {
                System.out.println("[TX] Book " + bookId + " is not available.");
                conn.setAutoCommit(true);
                return false;
            }
            if (!memberExists(memberId)) {
                System.out.println("[TX] Member " + memberId + " not found.");
                conn.setAutoCommit(true);
                return false;
            }

            // Step 2 – Mark book unavailable
            updateBookAvailability(bookId, false);
            System.out.println("[TX] Book " + bookId + " marked unavailable.");

            // SP1 – before loan record
            sp1 = conn.setSavepoint("SP_BEFORE_LOAN");

            // Step 4 – Insert loan record
            insertLoanRecord(bookId, memberId);
            System.out.println("[TX] Loan record inserted.");

            // SP2 – before member counter
            sp2 = conn.setSavepoint("SP_BEFORE_MEMBER_UPDATE");

            // Step 6 – Increment member ActiveLoans
            updateMemberActiveLoans(memberId, +1);
            System.out.println("[TX] Member " + memberId + " active loan count incremented.");

            conn.commit();
            System.out.println("[TX] processLoan COMMITTED for book=" + bookId
                               + " member=" + memberId);
            conn.setAutoCommit(true);
            return true;

        } catch (SQLException e) {
            System.err.println("[TX] SQLException in processLoan: " + e.getMessage()
                               + " | SQLState=" + e.getSQLState());
            rollbackTo(sp1, "SP_BEFORE_LOAN");
            return false;
        } finally {
            resetAutoCommit();
        }
    }

    // ---------------------------------------------------------------
    // Return a Book
    // ---------------------------------------------------------------

    /**
     * Returns a book:
     *   1. Find the open loan record.
     *   2. Set ReturnDate = today.
     *   3. Mark book available.
     *   4. Decrement member's ActiveLoans.
     *   5. Commit.
     */
    public boolean returnBook(int loanId) {
        try {
            conn.setAutoCommit(false);

            int[] ids = getLoanDetails(loanId);
            if (ids == null) {
                System.out.println("[TX] Loan " + loanId + " not found or already returned.");
                conn.setAutoCommit(true);
                return false;
            }
            int bookId   = ids[0];
            int memberId = ids[1];

            setReturnDate(loanId);
            updateBookAvailability(bookId, true);
            updateMemberActiveLoans(memberId, -1);

            conn.commit();
            System.out.println("[TX] returnBook COMMITTED for loanId=" + loanId);
            conn.setAutoCommit(true);
            return true;

        } catch (SQLException e) {
            System.err.println("[TX] SQLException in returnBook: " + e.getMessage());
            rollbackFully();
            return false;
        } finally {
            resetAutoCommit();
        }
    }

    // ---------------------------------------------------------------
    // Register Member
    // ---------------------------------------------------------------

    public int registerMember(String name, String email, String phone) {
        String sql = "INSERT INTO Members (Name, Email, Phone) VALUES (?, ?, ?)";
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps =
                     conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, phone);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                int newId = keys.next() ? keys.getInt(1) : -1;
                conn.commit();
                conn.setAutoCommit(true);
                System.out.println("[TX] Member registered: ID=" + newId);
                return newId;
            }
        } catch (SQLException e) {
            System.err.println("[TX] registerMember failed: " + e.getMessage());
            rollbackFully();
            return -1;
        } finally {
            resetAutoCommit();
        }
    }

    // ---------------------------------------------------------------
    // Add Book
    // ---------------------------------------------------------------

    public int addBook(String isbn, String title, String author, String genre) {
        String sql = "INSERT INTO Books (ISBN, Title, Author, Genre) VALUES (?, ?, ?, ?)";
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps =
                     conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, isbn);
                ps.setString(2, title);
                ps.setString(3, author);
                ps.setString(4, genre);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                int newId = keys.next() ? keys.getInt(1) : -1;
                conn.commit();
                conn.setAutoCommit(true);
                System.out.println("[TX] Book added: ID=" + newId);
                return newId;
            }
        } catch (SQLException e) {
            System.err.println("[TX] addBook failed: " + e.getMessage());
            rollbackFully();
            return -1;
        } finally {
            resetAutoCommit();
        }
    }

    // ---------------------------------------------------------------
    // Demonstrate Constraint Violation (ACID illustration)
    // ---------------------------------------------------------------

    /**
     * Attempts to insert a duplicate email – should trigger a UNIQUE constraint,
     * causing a full rollback. Used in testing/demos.
     */
    public void demonstrateRollback(String duplicateEmail) {
        String sql = "INSERT INTO Members (Name, Email, Phone) VALUES (?, ?, ?)";
        try {
            conn.setAutoCommit(false);
            System.out.println("[TX-DEMO] Inserting first member ...");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "Test User A");
                ps.setString(2, duplicateEmail);
                ps.setString(3, "1234567890");
                ps.executeUpdate();
            }
            System.out.println("[TX-DEMO] Attempting duplicate email insert (should fail) ...");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "Test User B");
                ps.setString(2, duplicateEmail); // intentional duplicate
                ps.setString(3, "0987654321");
                ps.executeUpdate();   // <-- throws SQLException
            }
            conn.commit(); // never reached
        } catch (SQLException e) {
            System.out.println("[TX-DEMO] Caught expected violation: " + e.getMessage());
            System.out.println("[TX-DEMO] SQLState=" + e.getSQLState()
                               + " – rolling back entire transaction.");
            rollbackFully();
            System.out.println("[TX-DEMO] Rollback complete. Data integrity preserved.");
        } finally {
            resetAutoCommit();
        }
    }

    // ---------------------------------------------------------------
    // Private SQL helpers
    // ---------------------------------------------------------------

    private boolean isBookAvailable(int bookId) throws SQLException {
        String sql = "SELECT Available FROM Books WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bookId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt("Available") == 1;
        }
    }

    private boolean memberExists(int memberId) throws SQLException {
        String sql = "SELECT 1 FROM Members WHERE MemberID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, memberId);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    private void updateBookAvailability(int bookId, boolean available) throws SQLException {
        String sql = "UPDATE Books SET Available = ? WHERE BookID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, available ? 1 : 0);
            ps.setInt(2, bookId);
            ps.executeUpdate();
        }
    }

    private void insertLoanRecord(int bookId, int memberId) throws SQLException {
        String sql = "INSERT INTO Loans (BookID, MemberID, DueDate) " +
                     "VALUES (?, ?, {fn TIMESTAMPADD(SQL_TSI_DAY, 14, CURRENT_DATE)})";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bookId);
            ps.setInt(2, memberId);
            ps.executeUpdate();
        }
    }

    private void updateMemberActiveLoans(int memberId, int delta) throws SQLException {
        String sql = "UPDATE Members SET ActiveLoans = ActiveLoans + ? WHERE MemberID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, delta);
            ps.setInt(2, memberId);
            ps.executeUpdate();
        }
    }

    private int[] getLoanDetails(int loanId) throws SQLException {
        String sql = "SELECT BookID, MemberID FROM Loans " +
                     "WHERE LoanID = ? AND ReturnDate IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, loanId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            return new int[]{rs.getInt("BookID"), rs.getInt("MemberID")};
        }
    }

    private void setReturnDate(int loanId) throws SQLException {
        String sql = "UPDATE Loans SET ReturnDate = CURRENT_DATE WHERE LoanID = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, loanId);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------
    // Rollback helpers
    // ---------------------------------------------------------------

    private void rollbackTo(Savepoint sp, String label) {
        try {
            if (sp != null) {
                conn.rollback(sp);
                System.out.println("[TX] Rolled back to savepoint: " + label);
            } else {
                conn.rollback();
                System.out.println("[TX] Full rollback (no savepoint available).");
            }
        } catch (SQLException ex) {
            System.err.println("[TX] Rollback failed: " + ex.getMessage());
        }
    }

    private void rollbackFully() {
        try {
            conn.rollback();
            System.out.println("[TX] Full rollback executed.");
        } catch (SQLException ex) {
            System.err.println("[TX] Full rollback failed: " + ex.getMessage());
        }
    }

    private void resetAutoCommit() {
        try {
            if (!conn.getAutoCommit()) conn.setAutoCommit(true);
        } catch (SQLException e) {
            System.err.println("[TX] Could not reset autoCommit: " + e.getMessage());
        }
    }
}
