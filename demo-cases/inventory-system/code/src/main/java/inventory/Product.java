package inventory;

/** 商品实体。 */
public class Product {
    public static final String STATUS_ON_SALE = "在售";
    public static final String STATUS_OFF_SHELF = "已下架";

    private final Long id;
    private final String name;
    private double price;
    private int stock;
    private String status;

    public Product(Long id, String name, double price, int stock, String status) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public double getPrice() { return price; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    /** 是否低库存：库存数量低于10。 */
    public boolean isLowStock() { return stock < 10; }
}
