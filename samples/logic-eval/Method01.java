public class Method01 {
    public Long saveUser(String username, String password, UserMapper userMapper) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        String encrypted = password != null && !password.isEmpty() ? encrypt(password) : null;
        return userMapper.insert(username, encrypted);
    }
    private String encrypt(String p) { return "enc:" + p; }
    static class UserMapper { Long insert(String n, String p) { return 1L; } }
}
