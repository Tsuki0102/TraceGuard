public class Method18 {
    public double determineDiscount(boolean member, double amount) {
        double finalAmount = amount;
        if (member) {
            finalAmount = amount * 0.8;
        } else if (amount >= 100) {
            finalAmount = amount - 10;
        }
        return finalAmount;
    }
}
