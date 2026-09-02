package library;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 逾期罚款服务（REQ-006）。
 * 需求：逾期罚款按每本每天0.5元计算，罚款金额必须大于等于0。
 */
public class FineService {

    private static final double FINE_PER_DAY = 0.5;

    /**
     * 逾期罚款计算：逾期天数 × 每天罚款0.5元。
     * 数值阈值校验：罚款金额必须大于等于0。
     */
    public double calculateFine(Loan loan) {
        if (loan == null) {
            return 0.0;
        }
        LocalDate end = loan.getReturnDate() != null ? loan.getReturnDate() : LocalDate.now();
        long overdueDays = ChronoUnit.DAYS.between(loan.getDueDate(), end);
        if (overdueDays <= 0) {
            return 0.0;
        }
        // REQ-006: 逾期罚款按每本每天0.5元计算，罚款金额必须大于等于0
        double fine = overdueDays * FINE_PER_DAY;
        if (fine < 0) {
            return 0.0;
        }
        return fine;
    }
}
