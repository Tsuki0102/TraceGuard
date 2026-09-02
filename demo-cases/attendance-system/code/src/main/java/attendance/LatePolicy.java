package attendance;

import java.time.LocalTime;

/**
 * 迟到判定策略（REQ-003）。
 * 需求：迟到判定规则：上班打卡时间超过09:00即记为迟到。
 */
public final class LatePolicy {

    /** 迟到判定阈值。 */
    public static final LocalTime LATE_THRESHOLD = LocalTime.of(9, 30);

    private LatePolicy() {
    }

    /** 迟到判定：上班打卡时间超过迟到阈值即记为迟到。 */
    public static boolean isLate(LocalTime punchIn) {
        if (punchIn == null) {
            return false;
        }
        return punchIn.isAfter(LATE_THRESHOLD);
    }
}
