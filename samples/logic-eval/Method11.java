public class Method11 {
    public double findMaxPrice(List<Double> prices) {
        double max = 0;
        for (double p : prices) {
            if (p > max) {
                max = p;
            }
        }
        return max;
    }
}
