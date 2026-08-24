public class Method20 {
    public String pickShippingMethod(String address) {
        if (address.contains("本地")) {
            return "次日达";
        }
        return "标准快递";
    }
}
