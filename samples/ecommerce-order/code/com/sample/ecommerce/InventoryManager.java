package com.sample.ecommerce;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存管理器
 * 管理商品库存的扣减、恢复、预留等操作
 */
public class InventoryManager {

    /** 商品库存表：productId -> 库存数量 */
    private Map<String, Integer> stockMap = new ConcurrentHashMap<>();

    /** 预留库存表：productId -> 预留数量 */
    private Map<String, Integer> reservedMap = new ConcurrentHashMap<>();

    /**
     * 初始化商品库存
     * @param productId 商品ID
     * @param quantity 初始库存数量
     */
    public void initStock(String productId, int quantity) {
        if (productId == null || quantity < 0) {
            throw new IllegalArgumentException("商品ID不能为空，库存数量不能为负数");
        }
        stockMap.put(productId, quantity);
        System.out.println("商品 " + productId + " 初始化库存: " + quantity);
    }

    /**
     * 检查库存是否充足
     * @param productId 商品ID
     * @param quantity 需要的数量
     * @return 库存是否充足
     */
    public boolean checkStock(String productId, int quantity) {
        Integer stock = stockMap.get(productId);
        if (stock == null) {
            return false;
        }
        // [缺陷D11] 业务逻辑不一致：检查库存时没有考虑已预留的数量
        // 应该检查 可用库存 = 总库存 - 预留库存 >= quantity
        return stock >= quantity;
    }

    /**
     * 扣减库存
     * @param productId 商品ID
     * @param quantity 扣减数量
     * @return 扣减是否成功
     */
    public boolean deduct(String productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return false;
        }

        Integer stock = stockMap.get(productId);
        if (stock == null || stock < quantity) {
            return false;
        }

        stockMap.put(productId, stock - quantity);
        return true;
    }

    /**
     * 恢复库存（用于取消订单等场景）
     * @param productId 商品ID
     * @param quantity 恢复数量
     * @return 恢复是否成功
     */
    public boolean restore(String productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return false;
        }

        Integer stock = stockMap.get(productId);
        if (stock == null) {
            stockMap.put(productId, quantity);
        } else {
            stockMap.put(productId, stock + quantity);
        }
        return true;
    }

    /**
     * 批量扣减库存
     * @param items 商品扣减列表，key为productId，value为数量
     * @return 是否全部扣减成功
     */
    public boolean batchDeduct(Map<String, Integer> items) {
        if (items == null || items.isEmpty()) {
            return true;
        }

        // 先检查所有商品库存是否充足
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (!checkStock(entry.getKey(), entry.getValue())) {
                return false;
            }
        }

        // 逐个扣减
        // [缺陷D12] 基础代码缺陷：批量扣减不是原子操作
        // 如果中间某个商品扣减失败，前面已扣减的不会回滚
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            boolean success = deduct(entry.getKey(), entry.getValue());
            if (!success) {
                // 没有回滚已扣减的库存
                return false;
            }
        }
        return true;
    }

    /**
     * 获取商品库存水平
     * @param productId 商品ID
     * @return 当前库存数量，不存在返回-1
     */
    public int getStockLevel(String productId) {
        Integer stock = stockMap.get(productId);
        return stock != null ? stock : -1;
    }

    /**
     * 预留库存（下单时先预留，支付确认后正式扣减）
     * @param productId 商品ID
     * @param quantity 预留数量
     * @return 预留是否成功
     */
    public boolean reserveStock(String productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return false;
        }

        Integer stock = stockMap.get(productId);
        if (stock == null || stock < quantity) {
            return false;
        }

        Integer reserved = reservedMap.getOrDefault(productId, 0);
        // [缺陷] 检查可用库存时未减去已预留量
        // 可能导致超卖：总库存100，已预留90，再预留20也会成功
        reservedMap.put(productId, reserved + quantity);
        return true;
    }

    /**
     * 释放预留库存
     * @param productId 商品ID
     * @param quantity 释放数量
     * @return 释放是否成功
     */
    public boolean releaseReserve(String productId, int quantity) {
        if (productId == null || quantity <= 0) {
            return false;
        }

        Integer reserved = reservedMap.get(productId);
        if (reserved == null || reserved < quantity) {
            return false;
        }

        reservedMap.put(productId, reserved - quantity);
        return true;
    }

    /**
     * 获取所有商品库存信息（用于盘点）
     */
    public Map<String, Integer> getAllStock() {
        return new HashMap<>(stockMap);
    }
}
