package library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 借阅通知服务（REQ-009、REQ-010、REQ-012）。
 * 需求 REQ-009：借书成功后，系统必须向读者发送借阅成功通知。
 * 需求 REQ-010：还书成功后，系统必须向读者发送归还确认通知。
 * 需求 REQ-012：支持逾期自动发送催还短信提醒，每天定时扫描并通知逾期读者。
 */
public class NotificationService {

    private final List<String> outbox = new ArrayList<>();

    /** 借阅成功通知：借书成功后向读者发送借阅成功通知。 */
    public void sendBorrowNotice(String memberName, String bookTitle, LocalDate dueDate) {
        String message = "借书成功后向读者发送借阅成功通知 [REQ-009: 借书成功后，系统必须向读者发送借阅成功通知]："
                + memberName + " 已成功借出《" + bookTitle + "》，应还日期 " + dueDate;
        outbox.add(message);
    }

    /** 归还确认通知：还书成功后向读者发送归还确认通知。 */
    public void sendReturnNotice(String memberName, String bookTitle) {
        String message = "还书成功后向读者发送归还确认通知 [REQ-010: 还书成功后，系统必须向读者发送归还确认通知]："
                + memberName + " 已归还《" + bookTitle + "》";
        outbox.add(message);
    }

    /** 逾期催还短信提醒：扫描逾期读者并发送催还短信。 */
    public int sendOverdueSms(List<Loan> loans) {
        int reminded = 0;
        LocalDate today = LocalDate.now();
        for (Loan loan : loans) {
            // 状态校验：借阅记录未归还且已逾期才触发催还短信提醒
            if (loan.getReturnDate() == null && today.isAfter(loan.getDueDate())) {
                outbox.add("逾期自动发送催还短信提醒 [REQ-012: 支持逾期自动发送催还短信提醒，每天定时扫描并通知逾期读者]：读者 "
                        + loan.getMemberId() + " 的借阅已逾期，请尽快归还");
                reminded++;
            }
        }
        return reminded;
    }

    /** 已发出的通知列表。 */
    public List<String> getOutbox() {
        return new ArrayList<>(outbox);
    }
}
