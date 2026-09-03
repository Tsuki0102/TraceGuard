package com.sample.library;

import java.util.Date;

/**
 * 图书与借阅记录实体
 */
public class Book {
    private String isbn;
    private String title;
    private String author;
    private String category;
    private double price;
    private int stock;

    public Book(String isbn, String title, String author, String category, double price, int stock) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.category = category;
        this.price = price;
        this.stock = stock;
    }

    public String getIsbn() { return isbn; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    /** 借阅记录 */
    public static class BorrowRecord {
        public static final String STATUS_BORROWING = "BORROWING";
        public static final String STATUS_RETURNED = "RETURNED";

        private String recordId;
        private String readerId;
        private String isbn;
        private Date borrowDate;
        private Date dueDate;
        private Date returnDate;
        private String status;
        private int renewCount;

        public BorrowRecord(String recordId, String readerId, String isbn, Date borrowDate, Date dueDate) {
            this.recordId = recordId;
            this.readerId = readerId;
            this.isbn = isbn;
            this.borrowDate = borrowDate;
            this.dueDate = dueDate;
            this.status = STATUS_BORROWING;
            this.renewCount = 0;
        }

        public String getRecordId() { return recordId; }
        public String getReaderId() { return readerId; }
        public String getIsbn() { return isbn; }
        public Date getBorrowDate() { return borrowDate; }
        public Date getDueDate() { return dueDate; }
        public void setDueDate(Date dueDate) { this.dueDate = dueDate; }
        public Date getReturnDate() { return returnDate; }
        public void setReturnDate(Date returnDate) { this.returnDate = returnDate; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getRenewCount() { return renewCount; }
        public void setRenewCount(int renewCount) { this.renewCount = renewCount; }
    }

    /** 读者 */
    public static class Reader {
        private String readerId;
        private Date cardExpireDate;   // 读者证有效期
        private double debt;           // 欠款总额
        private int borrowingCount;    // 在借数量
        private boolean frozen;        // 借阅资格冻结

        public Reader(String readerId, Date cardExpireDate) {
            this.readerId = readerId;
            this.cardExpireDate = cardExpireDate;
        }

        public String getReaderId() { return readerId; }
        public Date getCardExpireDate() { return cardExpireDate; }
        public double getDebt() { return debt; }
        public void setDebt(double debt) { this.debt = debt; }
        public int getBorrowingCount() { return borrowingCount; }
        public void setBorrowingCount(int borrowingCount) { this.borrowingCount = borrowingCount; }
        public boolean isFrozen() { return frozen; }
        public void setFrozen(boolean frozen) { this.frozen = frozen; }
    }
}
