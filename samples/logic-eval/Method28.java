public class Method28 {
    public boolean advanceOrderState(Order o) {
        String s = o.getStatus();
        if ("PENDING_PAYMENT".equals(s)) {
            o.setStatus("PAID");
            return true;
        }
        if ("PAID".equals(s)) {
            o.setStatus("SHIPPED");
            return true;
        }
        return false;
    }
    static class Order { String status; String getStatus(){return status;} void setStatus(String s){status=s;} }
}
