package com.sample.library;

import java.util.*;

/**
 * 图书管理服务（新增图书 / 库存维护）
 */
public class BookManageService {

    private final BorrowValidator validator = new BorrowValidator();
    private final Map<String, Book> bookStore;

    public BookManageService(Map<String, Book> bookStore) {
        this.bookStore = bookStore;
    }

    /** REQ-L020: 新增图书——校验 ISBN 非空且价格大于 0 */
    public void addBook(String isbn, String title, String author, String category, double price, int stock) {
        if (!validator.validateBookInfo(isbn, price)) {
            throw new RuntimeException("图书信息非法：ISBN 不能为空且价格必须大于 0");
        }
        bookStore.put(isbn, new Book(isbn, title, author, category, price, stock));
    }

    /** 代码超范围实现：向读者发送逾期提醒邮件（需求未要求该功能） */
    public void sendOverdueReminder(String readerId, String isbn) {
        System.out.println("[reminder] reader=" + readerId + " book=" + isbn);
    }

    /** 代码超范围实现：图书封面图片压缩缓存（需求未要求） */
    public String compressCoverImage(String isbn, byte[] rawImage) {
        return isbn + ":compressed:" + (rawImage == null ? 0 : rawImage.length);
    }
}
