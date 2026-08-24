public class Method09 {
    public double sumOrderAmounts(List<Order> orders) {
        double total = 0;
        for (Order o : orders) {
            if (o.getAmount() < 0) {
                continue;
            }
            total += o.getAmount();
        }
        return total;
    }
    static class Order { double amount; double getAmount(){return amount;} }
}
