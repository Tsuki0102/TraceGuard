package library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 借书服务（REQ-003、REQ-009）。
 * 需求 REQ-003：借书时必须校验读者存在、图书存在且馆藏数量大于0，
 * 同时读者在借数量不得超过5本，任一条件不满足则拒绝借出。
 * 需求 REQ-009：借书成功后，系统必须向读者发送借阅成功通知。
 */
public class BorrowService {

    private static final int MAX_BORROW_LIMIT = 5;

    private final BookCatalogService catalog;
    private final MemberRegistryService registry;
    private final List<Loan> loans = new ArrayList<>();
    private final NotificationService notificationService = new NotificationService();
    private long idSeq = 1;

    public BorrowService(BookCatalogService catalog, MemberRegistryService registry) {
        this.catalog = catalog;
        this.registry = registry;
    }

    /**
     * 借书：状态校验读者与图书必须存在，库存校验馆藏数量必须大于0，
     * 数值阈值校验在借数量不得超过5本上限，任一条件不满足则拒绝借出并抛出异常。
     */
    public Loan borrowBook(Long memberId, Long bookId) {
        Member member = registry.findMember(memberId);
        // 校验读者存在，读者不存在时拒绝借出并抛出异常
        if (member == null) {
            throw new IllegalArgumentException("错误：读者不存在，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0，同时读者在借数量不得超过5本，任一条件不满足则拒绝借出]");
        }
        Book book = catalog.findBook(bookId);
        // 校验图书存在，图书不存在时拒绝借出并抛出异常
        if (book == null) {
            throw new IllegalArgumentException("错误：图书不存在，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0，同时读者在借数量不得超过5本，任一条件不满足则拒绝借出]");
        }
        // 库存校验：馆藏数量（库存）必须大于0
        if (book.getCopies() <= 0) {
            throw new IllegalStateException("错误：馆藏数量不大于0，库存为0，拒绝借出 [REQ-003: 借书时必须校验读者存在、图书存在且馆藏数量大于0]");
        }
        // 数值阈值校验：在借数量不得超过5本
        long active = 0;
        for (Loan loan : loans) {
            if (loan.getMemberId().equals(memberId) && loan.getReturnDate() == null) {
                active++;
            }
        }
        if (active >= MAX_BORROW_LIMIT) {
            throw new IllegalStateException("错误：读者在借数量不得超过5本，已达上限，拒绝借出 [REQ-003]");
        }
        LocalDate today = LocalDate.now();
        Loan loan = new Loan(idSeq, memberId, bookId, today, today.plusDays(30));
        loans.add(loan);
        idSeq++;
        catalog.updateStock(bookId, book.getCopies() - 1);
        // REQ-009：借书成功后向读者发送借阅成功通知
        notificationService.sendBorrowNotice(member.getName(), book.getTitle(), loan.getDueDate());
        return loan;
    }

    /** 全部借阅记录。 */
    public List<Loan> allLoans() {
        return new ArrayList<>(loans);
    }

    /** 按编号查询借阅记录。 */
    public Loan findLoan(Long loanId) {
        for (Loan loan : loans) {
            if (loan.getId().equals(loanId)) {
                return loan;
            }
        }
        return null;
    }

    /** 通知服务访问器。 */
    public NotificationService getNotificationService() {
        return notificationService;
    }
}
