public class Method14 {
    public Map<String, List<Order>> groupOrdersByStatus(List<Order> orders) {
        Map<String, List<Order>> groups = new HashMap<>();
        for (Order o : orders) {
            groups.computeIfAbsent(o.getStatus(), k -> new ArrayList<>()).add(o);
        }
        return groups;
    }
    static class Order { String status; String getStatus(){return status;} }
}
