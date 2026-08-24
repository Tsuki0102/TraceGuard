public class Method04 {
    public int updateUser(User user, UserMapper mapper) {
        return mapper.update(user.getName(), user.getEmail(), user.getId());
    }
    static class User { String name; String email; Long id; String getName(){return name;} String getEmail(){return email;} Long getId(){return id;} }
    static class UserMapper { int update(String n, String e, Long id) { return 1; } }
}
