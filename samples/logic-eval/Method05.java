public class Method05 {
    public List<Order> listAllOrders(OrderDao dao) {
        return dao.findAll();
    }
    static class Order {}
    static class OrderDao { List<Order> findAll() { return java.util.Collections.emptyList(); } }
}
