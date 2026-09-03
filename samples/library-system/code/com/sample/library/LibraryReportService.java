package com.sample.library;

import com.sample.library.Book.BorrowRecord;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 借阅统计与预约状态服务
 */
public class LibraryReportService {

    private final List<BorrowRecord> records;
    private final Map<String, java.util.Deque<String>> reservations;

    public LibraryReportService(List<BorrowRecord> records,
                                Map<String, java.util.Deque<String>> reservations) {
        this.records = records;
        this.reservations = reservations;
    }

    /**
     * REQ-L023: 统计指定读者累计借阅次数（含已归还记录）
     */
    public int countLifetimeBorrows(String readerId) {
        if (readerId == null || readerId.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (BorrowRecord r : records) {
            if (readerId.equals(r.getReaderId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * REQ-L024: 判断某本图书当前是否存在有效预约
     */
    public boolean hasActiveReservation(String isbn) {
        if (isbn == null) {
            return false;
        }
        java.util.Deque<String> queue = reservations.get(isbn);
        return queue != null && !queue.isEmpty();
    }
}
