public class Method30 {
    public void handleRefundFlow(Refund r) {
        if (!r.isRefundable()) {
            throw new IllegalStateException("订单不可退款");
        }
        r.setStatus("PENDING_REVIEW");
        if (r.approve()) {
            r.setStatus("REFUNDED");
        }
    }
    static class Refund { boolean isRefundable(){return true;} boolean approve(){return true;} void setStatus(String s){} }
}
