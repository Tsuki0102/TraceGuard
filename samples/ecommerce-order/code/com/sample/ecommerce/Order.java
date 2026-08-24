package com.sample.ecommerce;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单实体类
 * 包含订单的基本信息和商品明细
 */
public class Order {

    /** 订单状态常量 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 订单ID */
    private String id;

    /** 客户ID */
    private String customerId;

    /** 订单金额 */
    private double amount;

    /** 订单状态 */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 订单商品明细列表 */
    private List<OrderItem> items;

    /** 订单备注 */
    private String remark;

    /** 收货地址 */
    private String shippingAddress;

    public Order() {
        this.items = new ArrayList<>();
        this.status = STATUS_PENDING;
        this.createTime = LocalDateTime.now();
    }

    public Order(String id, String customerId, double amount) {
        this();
        this.id = id;
        this.customerId = customerId;
        this.amount = amount;
    }

    // ========== Getters and Setters ==========

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void setItems(List<OrderItem> items) {
        this.items = items;
    }

    public void addItem(OrderItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    /**
     * 获取订单摘要信息
     */
    public String getSummary() {
        return String.format("订单[%s] 客户:%s 金额:%.2f 状态:%s 商品数:%d",
                id, customerId, amount, status, items != null ? items.size() : 0);
    }

    @Override
    public String toString() {
        return getSummary();
    }

    /**
     * 订单商品明细内部类
     */
    public static class OrderItem {
        /** 商品ID */
        private String productId;
        /** 商品名称 */
        private String productName;
        /** 数量 */
        private int quantity;
        /** 单价 */
        private double price;

        public OrderItem() {}

        public OrderItem(String productId, String productName, int quantity, double price) {
            this.productId = productId;
            this.productName = productName;
            this.quantity = quantity;
            this.price = price;
        }

        public String getProductId() {
            return productId;
        }

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }

        /**
         * 计算商品小计金额
         */
        public double getSubtotal() {
            return quantity * price;
        }

        @Override
        public String toString() {
            return String.format("%s(%s) x%d @%.2f", productName, productId, quantity, price);
        }
    }
}
