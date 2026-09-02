package library;

import java.time.LocalDate;

/** 借阅记录实体：一次借书的读者、图书、借还日期。 */
public class Loan {
    private final Long id;
    private final Long memberId;
    private final Long bookId;
    private final LocalDate borrowDate;
    private LocalDate dueDate;
    private LocalDate returnDate;

    public Loan(Long id, Long memberId, Long bookId, LocalDate borrowDate, LocalDate dueDate) {
        this.id = id;
        this.memberId = memberId;
        this.bookId = bookId;
        this.borrowDate = borrowDate;
        this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public Long getMemberId() { return memberId; }
    public Long getBookId() { return bookId; }
    public LocalDate getBorrowDate() { return borrowDate; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public LocalDate getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDate returnDate) { this.returnDate = returnDate; }

    /** 状态判断：借阅记录是否已归还。 */
    public boolean isReturned() { return returnDate != null; }

    /** 状态判断：借阅是否已逾期。 */
    public boolean isOverdue(LocalDate today) { return !isReturned() && today.isAfter(dueDate); }
}
