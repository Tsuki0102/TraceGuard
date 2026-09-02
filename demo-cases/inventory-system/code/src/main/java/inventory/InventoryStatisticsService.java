package inventory;

/**
 * 库存统计服务。
 * @author inventory-demo
 */
public class InventoryStatisticsService {

    private final InventoryStore store;

    public InventoryStatisticsService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 统计指定商品的库存总量与库存金额总额。
     * 需求 REQ-006: 支持统计指定商品的库存总量与库存金额总额。
     * @param productId 商品编号，商品必须存在
     * @return 统计结果，商品不存在时返回错误信息
     */
    public String statistics(Long productId) {
        Product product = store.find(productId);
        // 校验商品存在，商品不存在时统计失败
        if (product == null) {
            return "错误：商品不存在，统计失败";
        }
        // 库存总量与库存金额总额统计
        int stockTotal = product.getStock();
        double amountTotal = product.getStock() * product.getPrice();
        return "统计成功：" + product.getName() + " 库存总量 " + stockTotal + "，库存金额总额 " + amountTotal;
    }
}
