package inventory;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品查询服务。
 * @author inventory-demo
 */
public class ProductQueryService {

    private final InventoryStore store;

    public ProductQueryService(InventoryStore store) {
        this.store = store;
    }

    /**
     * 按商品名称关键词查询商品列表。
     * 需求 REQ-005: 支持按商品名称关键词查询商品列表，查询关键词不能为空。
     * @param keyword 查询关键词，查询关键词不能为空
     * @return 匹配的商品列表
     */
    public List<Product> searchByKeyword(String keyword) {
        // 校验查询关键词不能为空
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>();
        }
        List<Product> result = new ArrayList<>();
        for (Product product : store.allProducts()) {
            if (product.getName() != null && product.getName().contains(keyword)) {
                result.add(product);
            }
        }
        return result;
    }
}
