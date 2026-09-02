package inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存数据存储。
 * @author inventory-demo
 */
public class InventoryStore {

    private static final int LOW_STOCK_THRESHOLD = 10;

    private final Map<Long, Product> products = new LinkedHashMap<>();
    private final List<String> outboundRecords = new ArrayList<>();
    private long idSeq = 1;

    /** 生成商品编号。 */
    public long nextId() {
        return idSeq++;
    }

    /** 保存商品。 */
    public void save(Product product) {
        products.put(product.getId(), product);
    }

    /** 按编号查询商品，商品不存在时返回 null。 */
    public Product find(Long productId) {
        return products.get(productId);
    }

    /** 全部商品列表。 */
    public java.util.List<Product> allProducts() {
        return new java.util.ArrayList<>(products.values());
    }

    /** 记录出库记录。 */
    public void logOutbound(Long productId, int quantity) {
        outboundRecords.add("出库记录：商品" + productId + " 数量" + quantity);
    }

    /** 全部出库记录。 */
    public List<String> allOutboundRecords() {
        return new ArrayList<>(outboundRecords);
    }

    /**
     * 库存预警。
     * 需求 REQ-004: 商品库存数量低于10时，系统必须将该商品标记为低库存状态。
     * @return 低库存商品列表
     */
    public List<Product> lowStockProducts() {
        List<Product> lowList = new ArrayList<>();
        for (Product product : products.values()) {
            // 库存数量低于10时标记为低库存状态
            if (product.isLowStock()) {
                lowList.add(product);
            }
        }
        return lowList;
    }

    /** 低库存阈值。 */
    public int lowStockThreshold() {
        return LOW_STOCK_THRESHOLD;
    }
}
