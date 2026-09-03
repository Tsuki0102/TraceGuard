package com.sample.library;

import java.util.Date;

/**
 * 借阅参数校验器
 */
public class BorrowValidator {

    /** REQ-L002: 每人最多同时借阅 5 本 */
    public static final int MAX_BORROW_QUOTA = 5;

    /**
     * REQ-L002: 校验读者借阅额度
     * 【缺陷 D-L01（困难负例）】实现误写为最多 3 本（需求为 5 本）；
     * 校验结构完整、语义高度对齐，仅数值错配
     */
    public boolean validateQuota(Book.Reader reader) {
        return reader.getBorrowingCount() < 3;
    }

    /**
     * REQ-L001: 校验读者证在有效期内
     * 【缺陷 D-L08】未实现有效期校验，过期读者证仍放行（约束缺失）
     */
    public boolean validateCardValidity(Book.Reader reader) {
        return reader != null;
    }

    /** REQ-L020: 新增图书校验——ISBN 不为空且价格大于 0 */
    public boolean validateBookInfo(String isbn, double price) {
        if (isbn == null || isbn.trim().isEmpty()) {
            return false;
        }
        return price > 0;
    }

    /** REQ-L003: 库存校验——库存大于 0 才可借 */
    public boolean validateStock(Book book) {
        return book != null && book.getStock() > 0;
    }
}
