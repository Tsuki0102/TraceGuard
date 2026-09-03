package com.sample.library;

/**
 * 逾期罚款与信用计算器
 */
public class FineCalculator {

    /** REQ-L006: 逾期罚款 0.5 元/天 */
    public static final double FINE_PER_DAY = 0.5;
    /** REQ-L007: 单次罚款封顶 50 元 */
    public static final double FINE_CAP = 50.0;
    /** REQ-L011: 欠款冻结阈值 10 元 */
    public static final double DEBT_FREEZE_THRESHOLD = 10.0;
    /** REQ-L012: 逾期未还冻结阈值 15 天 */
    public static final long OVERDUE_FREEZE_DAYS = 30;

    /**
     * REQ-L006/REQ-L007: 逾期罚款——0.5元/天，封顶50元
     * 困难负例：需求将费率与封顶写在两段文字中，实现以常量+封顶逻辑表达，语义一致、措辞迥异
     */
    public double computeFine(long overdueDays) {
        if (overdueDays <= 0) {
            return 0.0;
        }
        return Math.min(overdueDays * FINE_PER_DAY, FINE_CAP);
    }

    /**
     * REQ-L011: 欠款超过 10 元冻结借阅资格
     */
    public boolean isCreditBlockedByDebt(Book.Reader reader) {
        return reader != null && reader.getDebt() > DEBT_FREEZE_THRESHOLD;
    }

    /**
     * REQ-L012: 逾期未还超过 15 天冻结借阅资格
     * 【缺陷 D-L07】实现误写为 30 天（需求为 15 天），数值阈值错配
     */
    public boolean isCreditBlockedByOverdue(long overdueDays) {
        return overdueDays > OVERDUE_FREEZE_DAYS;
    }
}
