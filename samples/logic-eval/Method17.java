public class Method17 {
    public String classifyTransaction(double amount) {
        if (amount > 10000) {
            return "LARGE";
        }
        if (amount > 1000) {
            return "NORMAL";
        }
        return "SMALL";
    }
}
