public class Method07 {
    public Product findProductByCode(String code, List<Product> products) {
        for (Product p : products) {
            if (p.getCode().equals(code)) {
                return p;
            }
        }
        return null;
    }
    static class Product { String code; String getCode(){return code;} }
}
