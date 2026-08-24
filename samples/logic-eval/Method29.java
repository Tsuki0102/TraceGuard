public class Method29 {
    public void processPaymentFlow(Payment p) {
        p.setStatus("PENDING");
        boolean ok = pay(p);
        if (ok) {
            p.setStatus("SUCCESS");
            p.setPaidAt(System.currentTimeMillis());
        } else {
            p.setStatus("FAILED");
        }
    }
    private boolean pay(Payment p) { return true; }
    static class Payment { void setStatus(String s) {} void setPaidAt(long t) {} }
}
