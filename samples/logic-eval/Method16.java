public class Method16 {
    public boolean checkPermission(String role) {
        if ("ADMIN".equals(role) || "OPERATOR".equals(role)) {
            return true;
        }
        return false;
    }
}
