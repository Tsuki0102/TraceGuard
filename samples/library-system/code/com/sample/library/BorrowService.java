package com.sample.library;

import com.sample.library.Book.BorrowRecord;
import com.sample.library.Book.Reader;

import java.util.*;

/**
 * 借阅核心服务：借书 / 还书 / 续借
 */
public class BorrowService {

    public static final long BORROW_PERIOD_DAYS = 30;
    public static final long RENEW_EXTEND_DAYS = 15;

    private final BorrowValidator validator = new BorrowValidator();
    private final FineCalculator fineCalculator = new FineCalculator();
    private final Map<String, Book> bookStore = new HashMap<>();
    private final List<BorrowRecord> records = new ArrayList<>();
    private int recordSeq = 5000;

    /**
     * REQ-L002/REQ-L003/REQ-L004/REQ-L005: 借书主流程
     * 【缺陷 D-L04】需求 REQ-L011 要求"欠款超10元冻结借阅资格，冻结期间拒绝借书"，
     * 本方法缺少冻结与欠款检查（约束缺失，检查逻辑归属借书入口职责）
     */
    public String borrowBook(Reader reader, Book book) {
        if (!validator.validateCardValidity(reader)) {
            throw new RuntimeException("读者证无效");
        }
        if (!validator.validateQuota(reader)) {
            throw new RuntimeException("超出借阅上限");
        }
        if (!validator.validateStock(book)) {
            throw new RuntimeException("图书无库存");
        }
        book.setStock(book.getStock() - 1);
        Date now = new Date();
        Date due = new Date(now.getTime() + BORROW_PERIOD_DAYS * 24 * 3600 * 1000);
        BorrowRecord record = new BorrowRecord("R-" + (++recordSeq), reader.getReaderId(), book.getIsbn(), now, due);
        records.add(record);
        reader.setBorrowingCount(reader.getBorrowingCount() + 1);
        return record.getRecordId();
    }

    /**
     * REQ-L006/REQ-L008: 还书——核对应还日期、逾期罚款计入欠款、增加库存并核销记录
     * 【缺陷 D-L05】实现遗漏了"核销借阅记录（状态置为已归还）"环节，且未将罚款计入读者欠款
     */
    public double returnBook(Reader reader, BorrowRecord record) {
        Date now = new Date();
        record.setReturnDate(now);
        long overdueDays = Math.max(0,
                (now.getTime() - record.getDueDate().getTime()) / (24 * 3600 * 1000));
        double fine = fineCalculator.computeFine(overdueDays);
        Book book = bookStore.get(record.getIsbn());
        if (book != null) {
            book.setStock(book.getStock() + 1);
        }
        reader.setBorrowingCount(Math.max(0, reader.getBorrowingCount() - 1));
        return fine;
    }

    /**
     * REQ-L009/REQ-L010: 续借——最多1次、延长15天、被预约图书不可续借
     * 【缺陷 D-L06】实现允许续借2次（需求为最多1次），且缺少"被预约图书不可续借"的预约检查
     */
    public void renewBook(Reader reader, BorrowRecord record, boolean hasActiveReservation) {
        if (record.getRenewCount() >= 2) {
            throw new RuntimeException("续借次数已达上限");
        }
        if (record.getStatus().equals(BorrowRecord.STATUS_RETURNED)) {
            throw new RuntimeException("已归还记录不可续借");
        }
        record.setRenewCount(record.getRenewCount() + 1);
        record.setDueDate(new Date(record.getDueDate().getTime() + RENEW_EXTEND_DAYS * 24 * 3600 * 1000));
    }

    /** REQ-L019: 挂失补办——原证失效，资格与欠款转移至新证 */
    public Reader reissueCard(Reader lost, String newReaderId) {
        Reader fresh = new Reader(newReaderId, lost.getCardExpireDate());
        fresh.setDebt(lost.getDebt());
        fresh.setBorrowingCount(lost.getBorrowingCount());
        fresh.setFrozen(lost.isFrozen());
        return fresh;
    }
}
