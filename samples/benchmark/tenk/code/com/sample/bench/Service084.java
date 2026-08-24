package com.sample.bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基准样例类 Service084：CRUD + 状态流转 + 聚合统计（模式化模板，仅供性能评测）
 * 对应需求条目：REQ-0333 ~ REQ-0336
 */
public class Service084 {

    public static final int MAX_AMOUNT = 1000000;
    public static final int MAX_BATCH = 100;
    private final Map<String, Entity084> store = new HashMap<>();

    /** 创建：校验名称非空且金额大于0 */
    public Entity084 create(String id, String name, double amount) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("amount out of range");
        }
        if (store.containsKey(id)) {
            throw new IllegalStateException("duplicate id");
        }
        Entity084 e = new Entity084(id, name, amount);
        store.put(id, e);
        return e;
    }

    /** 查询：按ID精确查询，未命中返回null */
    public Entity084 queryById(String id) {
        return store.get(id);
    }

    /** 更新并流转状态：PENDING -> PROCESSING */
    public boolean updateToProcessing(String id) {
        Entity084 e = store.get(id);
        if (e == null || !"PENDING".equals(e.getStatus())) {
            return false;
        }
        e.setStatus("PROCESSING");
        return true;
    }

    /** 完成：校验金额非负后流转到 COMPLETED */
    public boolean complete(String id) {
        Entity084 e = store.get(id);
        if (e == null || e.getAmount() < 0) {
            return false;
        }
        e.setStatus("COMPLETED");
        return true;
    }

    /** 聚合：统计金额总和（循环聚合） */
    public double sumAmounts() {
        double sum = 0;
        for (Entity084 e : store.values()) {
            sum += e.getAmount();
        }
        return sum;
    }

    /** 按状态过滤查询 */
    public List<Entity084> listByStatus(String status) {
        List<Entity084> result = new ArrayList<>();
        for (Entity084 e : store.values()) {
            if (status.equals(e.getStatus())) {
                result.add(e);
            }
        }
        return result;
    }

    /** 批量创建：单次不超过 MAX_BATCH 条 */
    public int batchCreate(List<Entity084> items) {
        if (items == null || items.size() > MAX_BATCH) {
            throw new IllegalArgumentException("batch size exceeded");
        }
        int created = 0;
        for (Entity084 item : items) {
            if (item != null && !store.containsKey(item.getId())) {
                store.put(item.getId(), item);
                created++;
            }
        }
        return created;
    }

    /** 统计各状态数量分布 */
    public Map<String, Integer> statusDistribution() {
        Map<String, Integer> dist = new HashMap<>();
        for (Entity084 e : store.values()) {
            dist.merge(e.getStatus(), 1, Integer::sum);
        }
        return dist;
    }

    /** 校验一组金额合计不超过上限（总量守恒） */
    public boolean validateTotalAmount(List<Double> amounts) {
        if (amounts == null) {
            return false;
        }
        double total = 0;
        for (Double a : amounts) {
            if (a == null || a < 0) {
                return false;
            }
            total += a;
        }
        return total <= MAX_AMOUNT;
    }

    /** 取消：仅 PENDING 状态可取消 */
    public boolean cancel(String id) {
        Entity084 e = store.get(id);
        if (e == null || !"PENDING".equals(e.getStatus())) {
            return false;
        }
        e.setStatus("CANCELLED");
        return true;
    }

    /** 内部实体：ID/名称/金额/状态 */
    public static class Entity084 {
        private final String id;
        private final String name;
        private final double amount;
        private String status = "PENDING";

        public Entity084(String id, String name, double amount) {
            this.id = id;
            this.name = name;
            this.amount = amount;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public double getAmount() { return amount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
