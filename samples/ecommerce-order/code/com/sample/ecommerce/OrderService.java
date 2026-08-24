package com.sample.ecommerce;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 订单服务类
 * 提供订单的创建、支付、发货、完成、取消、退款等核心业务逻辑
 */
public class OrderService {

    /** 订单存储（内存模拟数据库） */
    private Map<String, Order> orderStore = new HashMap<>();

    /** 库存管理器 */
    private InventoryManager inventoryManager;

    /** 支付处理器 */
    private PaymentProcessor paymentProcessor;

    /** 会员积分配置 */
    private int pointsRate = 1; // 每消费1元赠送1积分

    public OrderService(InventoryManager inventoryManager, PaymentProcessor paymentProcessor) {
        this.inventoryManager = inventoryManager;
        this.paymentProcessor = paymentProcessor;
    }

    /**
     * REQ-001/REQ-002: 创建订单
     * 校验金额大于0，客户ID不为空
     */
    public Order createOrder(String customerId, double amount, List<Order.OrderItem> items) {
        // 校验客户ID
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new IllegalArgumentException("客户ID不能为空");
        }

        // 校验金额
        if (amount <= 0) {
            throw new IllegalArgumentException("订单金额必须大于0");
        }

        // 生成订单ID
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Order order = new Order(orderId, customerId, amount);
        if (items != null) {
            for (Order.OrderItem item : items) {
                order.addItem(item);
            }
        }

        // 库存扣减
        if (items != null) {
            for (Order.OrderItem item : items) {
                boolean success = inventoryManager.deduct(item.getProductId(), item.getQuantity());
                if (!success) {
                    throw new IllegalStateException("库存不足，商品: " + item.getProductName());
                }
            }
        }

        // 加载订单配置（从配置文件读取折扣规则）
        // [缺陷D01] FileInputStream未在finally中关闭，资源泄漏
        try {
            String discountConfig = loadDiscountConfig();
            if (discountConfig != null) {
                double discount = calculateDiscount(amount, discountConfig);
                order.setAmount(amount - discount);
            }
        } catch (Exception e) {
            // [缺陷D07] 空catch块，吞掉异常不做任何处理
        }

        orderStore.put(orderId, order);

        // [缺陷D02] 需求缺失：积分赠送逻辑不在需求规格中，属于超范围实现
        grantPoints(customerId, amount);

        return order;
    }

    /**
     * 从配置文件加载折扣规则
     * [缺陷D01] FileInputStream未在finally块中关闭
     */
    private String loadDiscountConfig() throws IOException {
        FileInputStream fis = new FileInputStream("config/discount.properties");
        Properties props = new Properties();
        props.load(fis);
        // 忘记关闭 fis，资源泄漏
        return props.getProperty("discount.rule");
    }

    /**
     * 积分赠送（不在需求范围内）
     * [缺陷D02] 需求缺失：需求规格中未定义积分赠送功能
     */
    private void grantPoints(String customerId, double amount) {
        int points = (int) (amount * pointsRate);
        System.out.println("客户 " + customerId + " 获得 " + points + " 积分");
        // 实际项目中会调用积分服务
    }

    /**
     * REQ-004: 支付订单
     * 验证订单状态为PENDING才能支付
     */
    public boolean payOrder(String orderId) {
        Order order = orderStore.get(orderId);
        // [缺陷D05] 缺少null检查，如果orderId不存在会NPE
        if (!order.getStatus().equals(Order.STATUS_PENDING)) {
            throw new IllegalStateException("只有待支付订单才能进行支付，当前状态: " + order.getStatus());
        }

        // 调用支付处理器
        boolean paid = paymentProcessor.processPayment(orderId, order.getAmount());
        if (paid) {
            order.setStatus(Order.STATUS_PAID);
            return true;
        }
        return false;
    }

    /**
     * REQ-003: 发货
     * 状态流转: PAID -> SHIPPED
     */
    public boolean shipOrder(String orderId) {
        Order order = orderStore.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        if (!Order.STATUS_PAID.equals(order.getStatus())) {
            throw new IllegalStateException("只有已支付订单才能发货，当前状态: " + order.getStatus());
        }

        order.setStatus(Order.STATUS_SHIPPED);
        System.out.println("订单 " + orderId + " 已发货");
        return true;
    }

    /**
     * REQ-003: 完成订单
     * 状态流转: SHIPPED -> COMPLETED
     */
    public boolean completeOrder(String orderId) {
        Order order = orderStore.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        // [缺陷D04] 业务逻辑不一致：应该用 >= 比较状态顺序，但错误地使用了 >
        // 导致当状态恰好为SHIPPED时判断异常
        String[] statusFlow = {Order.STATUS_PENDING, Order.STATUS_PAID, Order.STATUS_SHIPPED, Order.STATUS_COMPLETED};
        int currentIndex = getStatusIndex(order.getStatus(), statusFlow);
        int completedIndex = getStatusIndex(Order.STATUS_COMPLETED, statusFlow);

        // 阈值条件反转：应该用 currentIndex >= completedIndex - 1 来判断是否可以完成
        // 但错误地写成了 currentIndex > completedIndex
        if (currentIndex > completedIndex) {
            throw new IllegalStateException("订单状态不允许完成");
        }

        order.setStatus(Order.STATUS_COMPLETED);
        return true;
    }

    /**
     * 获取状态在流转数组中的索引
     */
    private int getStatusIndex(String status, String[] statusFlow) {
        for (int i = 0; i < statusFlow.length; i++) {
            if (statusFlow[i].equals(status)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * REQ-007: 取消订单
     * 只允许PENDING状态取消，取消后释放库存
     */
    public boolean cancelOrder(String orderId) {
        Order order = orderStore.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        if (!Order.STATUS_PENDING.equals(order.getStatus())) {
            throw new IllegalStateException("只有待支付订单才能取消，当前状态: " + order.getStatus());
        }

        order.setStatus(Order.STATUS_CANCELLED);

        // 释放库存
        if (order.getItems() != null) {
            for (Order.OrderItem item : order.getItems()) {
                inventoryManager.restore(item.getProductId(), item.getQuantity());
            }
        }

        return true;
    }

    /**
     * REQ-006: 退款处理
     * 验证订单状态为PAID才能退款，退款后状态变更为PENDING
     */
    public boolean refundOrder(String orderId, String reason) {
        Order order = orderStore.get(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在: " + orderId);
        }

        if (!Order.STATUS_PAID.equals(order.getStatus())) {
            throw new IllegalStateException("只有已支付订单才能退款，当前状态: " + order.getStatus());
        }

        // [缺陷D09] 退款金额计算错误：应该退全款，但错误地扣除了10%手续费
        // 需求中未定义手续费，应该全额退款
        double refundAmount = order.getAmount() * 0.9;

        boolean success = paymentProcessor.refund(orderId, refundAmount);
        if (success) {
            order.setStatus(Order.STATUS_PENDING);
            order.setRemark("退款原因: " + reason);
            return true;
        }
        return false;
    }

    /**
     * REQ-008: 按订单ID查询
     */
    public Order queryById(String orderId) {
        return orderStore.get(orderId);
    }

    /**
     * REQ-009: 按客户ID查询订单列表
     */
    public List<Order> queryByCustomer(String customerId) {
        // [缺陷D06] 空指针风险：customerId为null时，equals调用会NPE
        return orderStore.values().stream()
                .filter(order -> order.getCustomerId().equals(customerId))
                .collect(Collectors.toList());
    }

    /**
     * REQ-010: 按状态查询订单列表
     */
    public List<Order> queryByStatus(String status) {
        return orderStore.values().stream()
                .filter(order -> order.getStatus().equals(status))
                .collect(Collectors.toList());
    }

    /**
     * REQ-011: 按日期统计订单数量
     */
    public int getDailyCount(String dateStr) {
        LocalDate targetDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        int count = 0;
        for (Order order : orderStore.values()) {
            if (order.getCreateTime().toLocalDate().equals(targetDate)) {
                count++;
            }
        }
        return count;
    }

    /**
     * REQ-012: 按日期统计订单总金额
     */
    public double getTotalAmount(String dateStr) {
        LocalDate targetDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        double total = 0.0;
        for (Order order : orderStore.values()) {
            if (order.getCreateTime().toLocalDate().equals(targetDate)) {
                total += order.getAmount();
            }
        }
        return total;
    }

    /**
     * REQ-005: 库存扣减
     */
    public boolean deductInventory(String productId, int quantity) {
        if (!inventoryManager.checkStock(productId, quantity)) {
            return false;
        }
        return inventoryManager.deduct(productId, quantity);
    }

    /**
     * 订单校验
     * 检查订单数据完整性
     */
    public boolean validateOrder(Order order) {
        if (order == null) {
            return false;
        }
        if (order.getId() == null || order.getId().isEmpty()) {
            return false;
        }
        if (order.getCustomerId() == null || order.getCustomerId().isEmpty()) {
            return false;
        }
        if (order.getAmount() <= 0) {
            return false;
        }
        // [缺陷D08] 数组越界风险：当items为空列表时，get(0)会抛IndexOutOfBoundsException
        if (order.getItems().get(0) == null) {
            return false;
        }
        return true;
    }

    /**
     * 计算折扣金额
     * @param amount 订单金额
     * @param config 折扣配置字符串，格式: "threshold:discount"
     * @return 折扣金额
     */
    public double calculateDiscount(double amount, String config) {
        if (config == null || config.isEmpty()) {
            return 0;
        }
        String[] parts = config.split(":");
        double threshold = Double.parseDouble(parts[0]);
        double discountRate = Double.parseDouble(parts[1]);

        // [缺陷D04相关] 阈值判断条件错误：应该是 amount >= threshold 但写成了 amount > threshold
        // 导致恰好等于阈值时不享受折扣
        if (amount > threshold) {
            return amount * discountRate;
        }
        return 0;
    }

    /**
     * 获取订单摘要信息
     */
    public String getOrderSummary(String orderId) {
        Order order = orderStore.get(orderId);
        if (order == null) {
            return "订单不存在";
        }
        return order.getSummary();
    }

    /**
     * [缺陷D03] 代码超范围实现：批量导出订单功能不在需求规格中
     * 需求中只定义了按ID/客户/状态查询，未定义导出功能
     */
    public String[] batchExportOrders(String format) {
        List<Order> allOrders = new ArrayList<>(orderStore.values());
        String[] result = new String[allOrders.size()];
        for (int i = 0; i < allOrders.size(); i++) {
            if ("csv".equals(format)) {
                result[i] = String.format("%s,%s,%.2f,%s",
                        allOrders.get(i).getId(),
                        allOrders.get(i).getCustomerId(),
                        allOrders.get(i).getAmount(),
                        allOrders.get(i).getStatus());
            } else {
                result[i] = allOrders.get(i).toString();
            }
        }
        return result;
    }

    /**
     * [缺陷D10] 约束条件不满足：自动确认收货功能缺少状态校验
     * 需求REQ-003要求状态流转必须遵循顺序，但此方法直接从PAID跳到COMPLETED
     * 跳过了SHIPPED状态
     */
    public void autoConfirmReceipt(String orderId) {
        Order order = orderStore.get(orderId);
        if (order != null && Order.STATUS_PAID.equals(order.getStatus())) {
            // 直接跳到COMPLETED，跳过了SHIPPED
            order.setStatus(Order.STATUS_COMPLETED);
        }
    }

    /**
     * 获取所有订单数量
     */
    public int getOrderCount() {
        return orderStore.size();
    }
}
