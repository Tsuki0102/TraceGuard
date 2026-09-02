package library;

import java.time.LocalDate;

/**
 * 还书与续借服务（REQ-004、REQ-005）。
 * 需求 REQ-004：还书时必须校验借阅记录存在且尚未归还，归还成功后该书馆藏数量加1。
 * 需求 REQ-005：续借时必须校验借阅记录存在且未逾期，已逾期的借阅记录不允许续借。
 */
public class ReturnService {

    private final BorrowService borrowService;
    private final BookCatalogService catalog;
    private final NotificationService notificationService;

    public ReturnService(BorrowService borrowService, BookCatalogService catalog,
                         NotificationService notificationService) {
        this.borrowService = borrowService;
        this.catalog = catalog;
        this.notificationService = notificationService;
    }

    /**
     * 还书：校验借阅记录存在且尚未归还（状态校验），归还成功后馆藏数量加1，
     * 并向读者发送归还确认通知。
     */
    public boolean returnBook(Long loanId) {
        Loan loan = borrowService.findLoan(loanId);
        // 状态校验：借阅记录必须存在且尚未归还
        if (loan == null || loan.getReturnDate() != null) {
            return false;
        }
        loan.setReturnDate(LocalDate.now());
        Book book = catalog.findBook(loan.getBookId());
        if (book != null) {
            // 归还成功后该书馆藏数量加1
            catalog.updateStock(book.getId(), book.getCopies() + 1);
        }
        notificationService.sendReturnNotice("读者" + loan.getMemberId() + " [REQ-004: 还书时必须校验借阅记录存在且尚未归还，归还成功后该书馆藏数量加1]", "图书" + loan.getBookId());
        return true;
    }

    /**
     * 续借：校验借阅记录存在且未逾期；已逾期的借阅记录不允许续借。
     * 续借成功将应还日期顺延30天。
     */
    public boolean renewLoan(Long loanId) {
        Loan loan = borrowService.findLoan(loanId);
        // 状态校验：借阅记录必须存在且尚未归还
        if (loan == null || loan.getReturnDate() != null) {
            return false;
        }
        // 状态校验：已逾期的借阅记录不允许续借
        if (loan.isOverdue(LocalDate.now())) {
            throw new IllegalStateException("错误：已逾期的借阅记录不允许续借 [REQ-005: 续借时必须校验借阅记录存在且未逾期，已逾期的借阅记录不允许续借]");
        }
        loan.setDueDate(loan.getDueDate().plusDays(30));
        return true;
    }
}
