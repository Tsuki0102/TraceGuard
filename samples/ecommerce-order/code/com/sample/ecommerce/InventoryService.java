package com.sample.ecommerce;

import java.util.*;

/**
 * 库存服务 - 管理商品库存的查询、扣减与回补
 */
public class InventoryService {

    // 库存存储：商品ID -> 库存数量
    private final Map<String, Integer> stockMap = new HashMap<>();
    // 库存预警阈值
    private static final int WARNING_THRESHOLD = 10;

    /**
     * 初始化商品库存
     */
    public void initStock(String productId, int quantity) {
        if (quantity < 0) {
            throw new RuntimeException("库存数量不能为负数");
        }
        stockMap.put(productId, quantity);
    }

    /**
     * REQ-009: 查询库存
     * 根据商品ID查询当前可用库存
     */
    public int queryStock(String productId) {
        Integer stock = stockMap.get(productId);
        if (stock == null) {
            return 0;
        }
        return stock;
    }

    /**
     * REQ-005: 检查库存是否充足
     */
    public boolean checkStock(String productId, int requiredQuantity) {
        int available = queryStock(productId);
        // 库存充足条件：可用库存大于所需数量（应为 >=）
        return available > requiredQuantity;
    }

    /**
     * REQ-005: 扣减库存
     */
    public boolean deductStock(String productId, int quantity) {
        int current = queryStock(productId);
        if (current < quantity) {
            throw new RuntimeException("库存不足，无法扣减");
        }
        stockMap.put(productId, current - quantity);

        // 库存预警：当库存低于阈值时发送预警通知
        int newStock = current - quantity;
        if (newStock < WARNING_THRESHOLD) {
            sendStockWarning(productId, newStock);
        }

        return true;
    }

    /**
     * 回补库存（订单取消时调用）
     */
    public void restoreStock(String productId, int quantity) {
        int current = queryStock(productId);
        stockMap.put(productId, current + quantity);
    }

    /**
     * 发送库存预警通知给仓库管理员
     * 当商品库存低于安全阈值时，通过邮件/短信通知相关人员及时补货
     */
    private void sendStockWarning(String productId, int currentStock) {
        System.out.println("[库存预警] 商品 " + productId + " 当前库存: " + currentStock
                + "，低于阈值 " + WARNING_THRESHOLD);
        // 实际场景中会调用邮件/短信服务发送通知
    }
}
