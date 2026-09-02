package inventory;

/**
 * 商品出库服务。
 * @author inventory-demo
 */
public class ProductOutboundService {

    private final InventoryStore store;

    public ProductOutboundService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 商品出库。
     * 需求 REQ-002: 商品出库时必须校验商品存在且库存充足，库存不足时拒绝出库并返回错误信息。
     * 需求 REQ-009: 商品出库时必须校验商品状态为在售状态，已下架商品拒绝出库。
     * @param productId 商品编号，商品必须存在
     * @param quantity 出库数量，库存必须充足
     * @return 出库结果，拒绝出库时返回错误信息
     */
    public String outbound(Long productId, int quantity) {
        Product product = store.find(productId);
        // 校验商品存在，商品不存在时拒绝出库
        if (product == null) {
            return "错误：商品不存在，拒绝出库";
        }
        // 状态校验：商品状态必须为在售状态，已下架商品拒绝出库
        if (!Product.STATUS_ON_SALE.equals(product.getStatus())) {
            return "错误：商品已下架，商品状态不是在售状态，拒绝出库";
        }
        // 库存校验：库存必须充足，库存不足时拒绝出库
        if (product.getStock() < quantity) {
            return "错误：库存不足，拒绝出库";
        }
        product.setStock(product.getStock() - quantity);
        store.logOutbound(product.getId(), quantity);
        return "商品出库成功：" + product.getName() + "，出库数量 " + quantity;
    }
}
