public class Method06 {
    public Long createOrder(Order order, OrderDao dao) {
        order.setCreatedAt(System.currentTimeMillis());
        order.setStatus("PENDING_PAYMENT");
        return dao.insert(order);
    }
    static class Order { void setCreatedAt(long t) {} void setStatus(String s) {} }
    static class OrderDao { Long insert(Order o) { return 1L; } }
}
