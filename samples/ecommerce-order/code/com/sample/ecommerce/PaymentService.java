package com.sample.ecommerce;

import java.util.*;

/**
 * 支付服务 - 处理订单支付与退款
 */
public class PaymentService {

    // 支付记录存储
    private final Map<String, Map<String, Object>> paymentRecords = new HashMap<>();
    // 退款记录
    private final Set<String> refundedOrders = new HashSet<>();

    /**
     * REQ-002: 处理支付
     * 只有PENDING状态订单可支付，支付成功后状态变更为PAID
     */
    public boolean processPayment(String orderId, double amount, Map<String, Object> order) {
        String status = (String) order.get("status");

        // REQ-011: 支付状态校验
        if (!"PENDING".equals(status)) {
            throw new RuntimeException("订单状态不允许支付: " + status);
        }

        double expectedAmount = (double) order.get("totalAmount");
        if (Math.abs(amount - expectedAmount) > 0.01) {
            throw new RuntimeException("支付金额不匹配: 期望 " + expectedAmount + ", 实际 " + amount);
        }

        // 模拟支付处理
        boolean paymentSuccess = doPayment(orderId, amount);

        if (paymentSuccess) {
            // 记录支付信息
            Map<String, Object> record = new HashMap<>();
            record.put("orderId", orderId);
            record.put("amount", amount);
            record.put("payTime", System.currentTimeMillis());
            record.put("channel", "ONLINE");
            paymentRecords.put(orderId, record);

            // 更新订单状态和支付金额
            order.put("status", "PAID");
            order.put("paidAmount", amount);

            // 优惠券核销：如果订单关联了优惠券，标记为已使用
            String couponId = (String) order.get("couponId");
            if (couponId != null && !couponId.isEmpty()) {
                markCouponUsed(couponId);
            }
        }

        return paymentSuccess;
    }

    /**
     * REQ-006/REQ-012: 处理退款
     * 退款金额不超过原支付金额，同一订单不可重复退款
     */
    public boolean processRefund(String orderId, double refundAmount) {
        // REQ-012: 幂等性检查
        if (refundedOrders.contains(orderId)) {
            throw new RuntimeException("订单已退款，不可重复操作: " + orderId);
        }

        Map<String, Object> payment = paymentRecords.get(orderId);
        if (payment == null) {
            throw new RuntimeException("无支付记录，无法退款: " + orderId);
        }

        double paidAmount = (double) payment.get("amount");
        // REQ-012: 退款金额校验
        if (refundAmount > paidAmount) {
            throw new RuntimeException("退款金额超过支付金额");
        }

        // 执行退款
        boolean success = doRefund(orderId, refundAmount);
        if (success) {
            refundedOrders.add(orderId);
            payment.put("refundAmount", refundAmount);
            payment.put("refundTime", System.currentTimeMillis());
        }
        return success;
    }

    /**
     * 查询支付记录
     */
    public Map<String, Object> getPaymentRecord(String orderId) {
        return paymentRecords.get(orderId);
    }

    /**
     * 检查订单是否已退款
     */
    public boolean isRefunded(String orderId) {
        return refundedOrders.contains(orderId);
    }

    /**
     * 模拟实际支付操作
     */
    private boolean doPayment(String orderId, double amount) {
        // 模拟支付网关调用，假设支付总是成功
        try {
            Thread.sleep(10); // 模拟网络延迟
        } catch (InterruptedException e) {
            // 忽略中断
        }
        return true;
    }

    /**
     * 模拟实际退款操作
     */
    private boolean doRefund(String orderId, double amount) {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            // 忽略中断
        }
        return true;
    }

    /**
     * 标记优惠券已使用
     */
    private void markCouponUsed(String couponId) {
        System.out.println("优惠券 " + couponId + " 已核销");
    }
}
