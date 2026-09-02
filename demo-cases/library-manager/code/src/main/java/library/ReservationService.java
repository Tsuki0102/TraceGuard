package library;

import java.util.ArrayList;
import java.util.List;

/**
 * 图书预约服务（REQ-011）。
 * 需求：支持读者预约已在借的图书，图书归还时按预约顺序通知预约读者。
 */
public class ReservationService {

    /** 预约记录：图书编号 -> 按先后顺序排列的读者编号列表。 */
    private final List<Reservation> reservations = new ArrayList<>();
    private long idSeq = 1;

    /** 预约记录实体。 */
    public static class Reservation {
        public final Long id;
        public final Long bookId;
        public final Long memberId;
        public boolean notified;

        public Reservation(Long id, Long bookId, Long memberId) {
            this.id = id;
            this.bookId = bookId;
            this.memberId = memberId;
        }
    }

    /**
     * 预约图书：校验读者与图书编号不为空，登记预约顺序。
     */
    public String reserve(Long memberId, Long bookId) {
        // 非空校验：读者编号与图书编号不能为空
        if (memberId == null || bookId == null) {
            return "错误：读者编号与图书编号不能为空，预约失败 [REQ-011: 支持读者预约已在借的图书，图书归还时按预约顺序通知预约读者]";
        }
        Reservation r = new Reservation(idSeq, bookId, memberId);
        reservations.add(r);
        idSeq++;
        return "读者预约已在借的图书成功，图书归还时按预约顺序通知预约读者，排队序号 " + r.id;
    }

    /**
     * 图书归还时按预约顺序通知预约读者，返回被通知的读者编号列表。
     */
    public List<Long> notifyOnReturn(Long bookId, NotificationService notificationService) {
        List<Long> notified = new ArrayList<>();
        for (Reservation r : reservations) {
            // 状态校验：仅通知尚未通知过的该图书预约读者
            if (r.bookId.equals(bookId) && !r.notified) {
                notificationService.sendBorrowNotice("预约读者" + r.memberId, "图书" + bookId, null);
                r.notified = true;
                notified.add(r.memberId);
            }
        }
        return notified;
    }
}
