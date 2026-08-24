package com.sample.ecommerce;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付处理器
 * 处理订单的支付、退款、支付状态查询等操作
 */
public class PaymentProcessor {

    /** 支付状态常量 */
    public static final String PAYMENT_SUCCESS = "SUCCESS";
    public static final String PAYMENT_FAILED = "FAILED";
    public static final String PAYMENT_PENDING = "PENDING";
    public static final String PAYMENT_REFUNDED = "REFUNDED";

    /** 支付记录存储 */
    private Map<String, PaymentRecord> paymentRecords = new HashMap<>();

    /** 支付历史列表 */
    private List<String> paymentHistory = new ArrayList<>();

    /** 最大重试次数 */
    private static final int MAX_RETRY = 3;

    /**
     * 处理支付
     * @param orderId 订单ID
     * @param amount 支付金额
     * @return 支付是否成功
     */
    public boolean processPayment(String orderId, double amount) {
        if (orderId == null || amount <= 0) {
            return false;
        }

        // 模拟支付处理
        PaymentRecord record = new PaymentRecord();
        record.orderId = orderId;
        record.amount = amount;
        record.paymentTime = LocalDateTime.now();
        record.status = PAYMENT_SUCCESS; // 模拟支付成功
        record.retryCount = 0;

        paymentRecords.put(orderId, record);
        paymentHistory.add("支付: 订单" + orderId + " 金额" + amount + " 时间" + record.paymentTime);

        System.out.println("支付成功: 订单=" + orderId + ", 金额=" + amount);
        return true;
    }

    /**
     * 退款处理
     * @param orderId 订单ID
     * @param amount 退款金额
     * @return 退款是否成功
     */
    public boolean refund(String orderId, double amount) {
        PaymentRecord record = paymentRecords.get(orderId);
        // [缺陷] 缺少null检查，如果orderId对应的支付记录不存在会NPE
        if (!PAYMENT_SUCCESS.equals(record.status)) {
            throw new IllegalStateException("只有支付成功的订单才能退款");
        }

        record.status = PAYMENT_REFUNDED;
        record.refundAmount = amount;
        record.refundTime = LocalDateTime.now();

        paymentHistory.add("退款: 订单" + orderId + " 金额" + amount + " 时间" + record.refundTime);

        System.out.println("退款成功: 订单=" + orderId + ", 金额=" + amount);
        return true;
    }

    /**
     * 验证支付状态
     * @param orderId 订单ID
     * @return 支付是否有效
     */
    public boolean validatePayment(String orderId) {
        PaymentRecord record = paymentRecords.get(orderId);
        if (record == null) {
            return false;
        }
        return PAYMENT_SUCCESS.equals(record.status);
    }

    /**
     * 获取支付状态
     * @param orderId 订单ID
     * @return 支付状态字符串
     */
    public String getPaymentStatus(String orderId) {
        PaymentRecord record = paymentRecords.get(orderId);
        if (record == null) {
            return "NOT_FOUND";
        }
        return record.status;
    }

    /**
     * 重试支付
     * @param orderId 订单ID
     * @param amount 支付金额
     * @return 重试是否成功
     */
    public boolean retryPayment(String orderId, double amount) {
        PaymentRecord record = paymentRecords.get(orderId);
        if (record == null) {
            // 首次重试，创建记录
            record = new PaymentRecord();
            record.orderId = orderId;
            record.amount = amount;
            record.retryCount = 0;
            paymentRecords.put(orderId, record);
        }

        if (record.retryCount >= MAX_RETRY) {
            System.out.println("支付重试次数已达上限: " + MAX_RETRY);
            record.status = PAYMENT_FAILED;
            return false;
        }

        record.retryCount++;
        // 模拟重试支付（随机成功/失败）
        boolean success = record.retryCount >= 2; // 简化：第2次重试必定成功
        if (success) {
            record.status = PAYMENT_SUCCESS;
            record.paymentTime = LocalDateTime.now();
            paymentHistory.add("重试支付成功: 订单" + orderId + " 第" + record.retryCount + "次重试");
        } else {
            record.status = PAYMENT_FAILED;
            paymentHistory.add("重试支付失败: 订单" + orderId + " 第" + record.retryCount + "次重试");
        }

        return success;
    }

    /**
     * 取消支付
     * @param orderId 订单ID
     * @return 取消是否成功
     */
    public boolean cancelPayment(String orderId) {
        PaymentRecord record = paymentRecords.get(orderId);
        if (record == null) {
            return false;
        }

        if (PAYMENT_REFUNDED.equals(record.status)) {
            throw new IllegalStateException("已退款的支付不能取消");
        }

        record.status = PAYMENT_FAILED;
        paymentHistory.add("取消支付: 订单" + orderId);
        return true;
    }

    /**
     * 获取支付历史记录
     * @return 支付历史列表
     */
    public List<String> getPaymentHistory() {
        return new ArrayList<>(paymentHistory);
    }

    /**
     * 支付记录内部类
     */
    private static class PaymentRecord {
        String orderId;
        double amount;
        String status;
        LocalDateTime paymentTime;
        LocalDateTime refundTime;
        double refundAmount;
        int retryCount;

        @Override
        public String toString() {
            return String.format("支付记录[订单=%s, 金额=%.2f, 状态=%s, 重试次数=%d]",
                    orderId, amount, status, retryCount);
        }
    }
}
