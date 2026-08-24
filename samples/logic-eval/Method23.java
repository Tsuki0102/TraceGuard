public class Method23 {
    public void transferMoney(Account from, Account to, double amount) {
        if (from.getBalance() < amount) {
            throw new IllegalStateException("余额不足");
        }
        from.setBalance(from.getBalance() - amount);
        to.setBalance(to.getBalance() + amount);
    }
    static class Account { double balance; double getBalance(){return balance;} void setBalance(double b){balance=b;} }
}
