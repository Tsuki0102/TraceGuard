package inventory;

/**
 * 商品修改与删除服务。
 * @author inventory-demo
 */
public class ProductManageService {

    private final InventoryStore store;

    public ProductManageService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 修改商品价格。
     * 需求 REQ-003: 修改商品信息时必须校验商品存在且价格大于0，否则修改失败并返回错误信息。
     * @param productId 商品编号，商品必须存在
     * @param newPrice 新价格，价格必须大于0
     * @return 修改结果，修改失败时返回错误信息
     */
    public String changePrice(Long productId, double newPrice) {
        Product product = store.find(productId);
        // 校验商品存在，商品不存在时修改失败
        if (product == null) {
            return "错误：商品不存在，修改失败";
        }
        // 校验价格必须大于0
        if (newPrice <= 0) {
            return "错误：价格必须大于0，修改失败";
        }
        return "商品信息修改成功：" + product.getName() + " 价格调整为 " + newPrice;
    }

    /**
     * 删除商品。
     * 需求 REQ-010: 删除商品前必须校验商品库存为0，库存不为0时拒绝删除并返回错误信息。
     * @param productId 商品编号，商品必须存在
     * @return 删除结果，拒绝删除时返回错误信息
     */
    public String deleteProduct(Long productId) {
        Product product = store.find(productId);
        // 校验商品存在，商品不存在时删除失败
        if (product == null) {
            return "错误：商品不存在，删除失败";
        }
        // 校验商品库存为0，库存不为0时拒绝删除
        if (product.getStock() != 0) {
            return "错误：商品库存不为0，拒绝删除";
        }
        return "商品删除成功：" + product.getName();
    }
}
