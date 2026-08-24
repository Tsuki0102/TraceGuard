public class Method10 {
    public int countActiveUsers(List<User> users) {
        int count = 0;
        for (User u : users) {
            if ("ACTIVE".equals(u.getStatus())) {
                count++;
            }
        }
        return count;
    }
    static class User { String status; String getStatus(){return status;} }
}
