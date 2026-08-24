public class Method19 {
    public String validateOrderStatus(String status) {
        if ("PENDING_PAYMENT".equals(status)) {
            return "可取消";
        }
        if ("SHIPPED".equals(status)) {
            return "可签收";
        }
        return "非法操作";
    }
}
