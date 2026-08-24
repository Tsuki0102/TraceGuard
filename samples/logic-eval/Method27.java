public class Method27 {
    public List<String> queryWithConnection(String sql) throws SQLException {
        Connection conn = null;
        try {
            conn = getConnection();
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(sql);
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
            rs.close();
            st.close();
            return rows;
        } finally {
            if (conn != null) conn.close();
        }
    }
    private Connection getConnection() { return null; }
}
