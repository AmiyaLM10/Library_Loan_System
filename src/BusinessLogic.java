import java.sql.*;

/**
 * BusinessLogic
 * Handles all read-only queries and display formatting.
 * Write operations delegate to TransactionService.
 */
public class BusinessLogic {

    private final Connection        conn;
    private final TransactionService txService;

    public BusinessLogic(Connection conn, TransactionService txService) {
        this.conn      = conn;
        this.txService = txService;
    }

    // ---------------------------------------------------------------
    // Member operations
    // ---------------------------------------------------------------

    public int registerMember(String name, String email, String phone) {
        return txService.registerMember(name, email, phone);
    }

    public void listMembers() throws SQLException {
        String sql = "SELECT MemberID, Name, Email, Phone, ActiveLoans, JoinDate " +
                     "FROM Members ORDER BY MemberID";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n" + line(80));
            System.out.printf("%-6s %-22s %-28s %-14s %-6s %s%n",
                "ID", "Name", "Email", "Phone", "Loans", "Joined");
            System.out.println(line(80));
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("%-6d %-22s %-28s %-14s %-6d %s%n",
                    rs.getInt("MemberID"),
                    rs.getString("Name"),
                    rs.getString("Email"),
                    rs.getString("Phone"),
                    rs.getInt("ActiveLoans"),
                    rs.getDate("JoinDate"));
            }
            if (!any) System.out.println("  (no members found)");
            System.out.println(line(80));
        }
    }

    // ---------------------------------------------------------------
    // Book operations
    // ---------------------------------------------------------------

    public int addBook(String isbn, String title, String author, String genre) {
        return txService.addBook(isbn, title, author, genre);
    }

    public void listBooks() throws SQLException {
        String sql = "SELECT BookID, ISBN, Title, Author, Genre, Available " +
                     "FROM Books ORDER BY BookID";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n" + line(100));
            System.out.printf("%-6s %-15s %-35s %-22s %-12s %s%n",
                "ID", "ISBN", "Title", "Author", "Genre", "Status");
            System.out.println(line(100));
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("%-6d %-15s %-35s %-22s %-12s %s%n",
                    rs.getInt("BookID"),
                    rs.getString("ISBN"),
                    truncate(rs.getString("Title"),  33),
                    truncate(rs.getString("Author"), 20),
                    rs.getString("Genre"),
                    rs.getInt("Available") == 1 ? "Available" : "On Loan");
            }
            if (!any) System.out.println("  (no books found)");
            System.out.println(line(100));
        }
    }

    // ---------------------------------------------------------------
    // Loan operations
    // ---------------------------------------------------------------

    public boolean processLoan(int bookId, int memberId) {
        return txService.processLoan(bookId, memberId);
    }

    public boolean returnBook(int loanId) {
        return txService.returnBook(loanId);
    }

    // ---------------------------------------------------------------
    // Queries
    // ---------------------------------------------------------------

    /** All active (not yet returned) loans, optionally filtered by member. */
    public void listActiveLoans(int memberIdFilter) throws SQLException {
        boolean filtered = memberIdFilter > 0;
        String sql =
            "SELECT L.LoanID, M.Name, B.Title, L.LoanDate, L.DueDate " +
            "FROM Loans L " +
            "JOIN Members M ON L.MemberID = M.MemberID " +
            "JOIN Books   B ON L.BookID   = B.BookID " +
            "WHERE L.ReturnDate IS NULL" +
            (filtered ? " AND L.MemberID = ?" : "") +
            " ORDER BY L.DueDate";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setInt(1, memberIdFilter);
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n" + line(90));
                System.out.printf("%-8s %-22s %-32s %-12s %-12s%n",
                    "LoanID", "Member", "Book Title", "Loan Date", "Due Date");
                System.out.println(line(90));
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("%-8d %-22s %-32s %-12s %-12s%n",
                        rs.getInt("LoanID"),
                        truncate(rs.getString("Name"),  20),
                        truncate(rs.getString("Title"), 30),
                        rs.getDate("LoanDate"),
                        rs.getDate("DueDate"));
                }
                if (!any) System.out.println("  (no active loans" +
                    (filtered ? " for member " + memberIdFilter : "") + ")");
                System.out.println(line(90));
            }
        }
    }

    /** Books whose DueDate has passed and have not been returned. */
    public void listOverdueBooks() throws SQLException {
        String sql =
            "SELECT L.LoanID, M.Name, B.Title, L.DueDate, " +
            "       DAYS(CURRENT_DATE) - DAYS(L.DueDate) AS DaysOverdue " +
            "FROM Loans L " +
            "JOIN Members M ON L.MemberID = M.MemberID " +
            "JOIN Books   B ON L.BookID   = B.BookID " +
            "WHERE L.ReturnDate IS NULL AND L.DueDate < CURRENT_DATE " +
            "ORDER BY DaysOverdue DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            System.out.println("\n=== OVERDUE BOOKS ===");
            System.out.println(line(80));
            System.out.printf("%-8s %-22s %-32s %-12s %-10s%n",
                "LoanID", "Member", "Book Title", "Due Date", "Days Over");
            System.out.println(line(80));
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.printf("%-8d %-22s %-32s %-12s %-10d%n",
                    rs.getInt("LoanID"),
                    truncate(rs.getString("Name"),  20),
                    truncate(rs.getString("Title"), 30),
                    rs.getDate("DueDate"),
                    rs.getInt("DaysOverdue"));
            }
            if (!any) System.out.println("  (no overdue books – great!)");
            System.out.println(line(80));
        }
    }

    /** Search books by title keyword (case-insensitive). */
    public void searchBooks(String keyword) throws SQLException {
        String sql =
            "SELECT BookID, Title, Author, Genre, Available " +
            "FROM Books WHERE UPPER(Title) LIKE UPPER(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            try (ResultSet rs = ps.executeQuery()) {
                System.out.println("\n=== Search: \"" + keyword + "\" ===");
                System.out.println(line(80));
                boolean any = false;
                while (rs.next()) {
                    any = true;
                    System.out.printf("[%d] %s by %s | %s | %s%n",
                        rs.getInt("BookID"),
                        rs.getString("Title"),
                        rs.getString("Author"),
                        rs.getString("Genre"),
                        rs.getInt("Available") == 1 ? "Available" : "On Loan");
                }
                if (!any) System.out.println("  (no matching books)");
                System.out.println(line(80));
            }
        }
    }

    // ---------------------------------------------------------------
    // ACID demo
    // ---------------------------------------------------------------

    public void demonstrateRollback(String duplicateEmail) {
        txService.demonstrateRollback(duplicateEmail);
    }

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    private static String line(int n) {
        return "-".repeat(n);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
