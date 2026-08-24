public class Method15 {
    public String validateUserRegistration(String username, String password, String email) {
        if (username == null || username.isEmpty()) {
            return "USERNAME_EMPTY";
        }
        if (password == null || password.length() < 6) {
            return "PASSWORD_TOO_SHORT";
        }
        if (email == null || !email.contains("@")) {
            return "EMAIL_INVALID";
        }
        return "OK";
    }
}
