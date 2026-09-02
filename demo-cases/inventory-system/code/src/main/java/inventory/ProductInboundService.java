package inventory;

/**
 * 商品入库服务。
 * @author inventory-demo
 */
public class ProductInboundService {

    private final InventoryStore store;

    public ProductInboundService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 商品入库。
     * 需求 REQ-001: 商品入库时必须校验商品名称不为空且库存数量大于0，否则入库失败并返回错误信息。
     * @param name 商品名称，商品名称不能为空
     * @param price 商品价格，必须大于0
     * @param stock 库存数量，库存数量必须大于0
     * @return 入库结果，入库失败时返回错误信息
     */
    public String inbound(String name, double price, int stock) {
        // 校验商品名称不为空
        if (name == null || name.isEmpty()) {
            return "错误：商品名称不能为空，入库失败";
        }
        // 校验库存数量必须大于0
        if (stock <= 0) {
            return "错误：库存数量必须大于0，入库失败";
        }
        Product product = new Product(store.nextId(), name, price, stock, Product.STATUS_ON_SALE);
        store.save(product);
        return "商品入库成功：" + name + "，库存数量 " + stock;
    }
}
