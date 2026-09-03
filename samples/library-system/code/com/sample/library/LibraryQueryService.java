package com.sample.library;

import com.sample.library.Book.BorrowRecord;

import java.util.*;

/**
 * 查询、统计与预约服务
 */
public class LibraryQueryService {

    private final Map<String, Book> bookStore;
    private final List<BorrowRecord> records;
    /** 有效预约表：isbn -> 预约读者ID队列 */
    private final Map<String, Deque<String>> reservations = new HashMap<>();

    public LibraryQueryService(Map<String, Book> bookStore, List<BorrowRecord> records) {
        this.bookStore = bookStore;
        this.records = records;
    }

    /** REQ-L015: 按读者证号查询全部在借图书（返回 ISBN 列表） */
    public List<String> queryBorrowingByReader(String readerId) {
        List<String> out = new ArrayList<>();
        for (BorrowRecord r : records) {
            if (r.getReaderId().equals(readerId) && BorrowRecord.STATUS_BORROWING.equals(r.getStatus())) {
                out.add(r.getIsbn());
            }
        }
        return out;
    }

    /**
     * REQ-L016: 查询全部逾期未还记录，按逾期天数从高到低排序
     * 【缺陷 D-L09（细节困难例）】实现误用升序排序（需求为从高到低）
     */
    public List<BorrowRecord> queryOverdue() {
        List<BorrowRecord> overdue = new ArrayList<>();
        Date now = new Date();
        for (BorrowRecord r : records) {
            if (BorrowRecord.STATUS_BORROWING.equals(r.getStatus()) && now.after(r.getDueDate())) {
                overdue.add(r);
            }
        }
        overdue.sort(Comparator.comparingLong(r -> r.getDueDate().getTime()));
        return overdue;
    }

    /** REQ-L017: 按图书分类统计当前借出数量 */
    public Map<String, Integer> countBorrowingByCategory() {
        Map<String, Integer> counter = new HashMap<>();
        for (BorrowRecord r : records) {
            if (!BorrowRecord.STATUS_BORROWING.equals(r.getStatus())) {
                continue;
            }
            Book b = bookStore.get(r.getIsbn());
            if (b != null) {
                counter.merge(b.getCategory(), 1, Integer::sum);
            }
        }
        return counter;
    }

    /** REQ-L018: 按 ISBN 精确搜索图书，找不到返回 null（英文需求，实现一致） */
    public Book searchByIsbn(String isbn) {
        return bookStore.get(isbn);
    }

    /**
     * REQ-L025: 按作者名搜索图书，返回该作者的全部图书列表
     */
    public List<Book> searchByAuthor(String author) {
        List<Book> out = new ArrayList<>();
        if (author == null || author.isEmpty()) {
            return out;
        }
        for (Book b : bookStore.values()) {
            if (author.equals(b.getAuthor())) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * REQ-L013: 预约图书——仅当无库存时可预约，重复预约拒绝
     */
    public void reserveBook(String readerId, Book book) {
        if (book.getStock() > 0) {
            throw new RuntimeException("有库存应直接借书，不可预约");
        }
        Deque<String> queue = reservations.computeIfAbsent(book.getIsbn(), k -> new ArrayDeque<>());
        if (queue.contains(readerId)) {
            throw new RuntimeException("重复预约");
        }
        queue.addLast(readerId);
    }

    /** REQ-L014: 取消预约释放名额 */
    public void cancelReservation(String readerId, Book book) {
        Deque<String> queue = reservations.get(book.getIsbn());
        if (queue != null) {
            queue.remove(readerId);
        }
    }
}
