public class Method03 {
    public User findUserById(Long userId, UserRepository repo) {
        return repo.findById(userId);
    }
    static class User {}
    static class UserRepository { User findById(Long id) { return new User(); } }
}
