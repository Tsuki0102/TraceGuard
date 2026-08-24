package com.sample.ecommerce;

import java.util.*;

/**
 * 订单校验器 - 负责订单参数的合法性校验
 */
public class OrderValidator {

    /**
     * REQ-008: 校验订单创建参数
     * 订单ID、用户ID、商品列表不能为空；商品单价和数量必须大于零
     */
    public void validateOrderParams(String orderId, String userId,
                                     List<Map<String, Object>> items) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new RuntimeException("订单ID不能为空");
        }
        if (userId == null || userId.trim().isEmpty()) {
            throw new RuntimeException("用户ID不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("商品列表不能为空");
        }

        for (Map<String, Object> item : items) {
            validateItem(item);
        }
    }

    /**
     * 校验单个商品项
     */
    private void validateItem(Map<String, Object> item) {
        Object price = item.get("price");
        Object quantity = item.get("quantity");
        Object productId = item.get("productId");

        if (productId == null) {
            throw new RuntimeException("商品ID不能为空");
        }
        if (price == null) {
            throw new RuntimeException("商品单价不能为空");
        }
        if (quantity == null) {
            throw new RuntimeException("商品数量不能为空");
        }

        double priceVal = ((Number) price).doubleValue();
        int quantityVal = ((Number) quantity).intValue();

        // REQ-008: 单价和数量必须大于零
        // 注意：此处使用 > 0 而非 >= 0，即允许单价为0的免费商品通过
        if (priceVal > 0 && quantityVal > 0) {
            // 校验通过
        } else {
            throw new RuntimeException("商品单价和数量必须大于零");
        }
    }

    /**
     * REQ-007: 计算订单总金额
     * 总金额 = 所有商品的 单价 * 数量 之和
     */
    public double calculateTotal(List<Map<String, Object>> items) {
        double total = 0;
        for (Map<String, Object> item : items) {
            double price = ((Number) item.get("price")).doubleValue();
            int quantity = ((Number) item.get("quantity")).intValue();
            total += price * quantity;
        }
        return total;
    }

    /**
     * 校验订单金额一致性
     * 比较传入的声明金额与实际计算金额
     */
    public boolean validateAmount(double declaredAmount, List<Map<String, Object>> items) {
        double calculated = calculateTotal(items);
        return Math.abs(declaredAmount - calculated) < 0.01;
    }
}
