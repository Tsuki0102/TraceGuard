public class Method02 {
    public boolean deleteUser(Long userId, UserDao dao) {
        boolean deleted = dao.delete(userId);
        if (deleted) {
            roleDao.deleteByUserId(userId);
        }
        return deleted;
    }
    static class UserDao { boolean delete(Long id) { return true; } }
    static class RoleDao { void deleteByUserId(Long id) {} }
    static RoleDao roleDao = new RoleDao();
}
