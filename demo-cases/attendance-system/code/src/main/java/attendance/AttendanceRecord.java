package attendance;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 单日考勤记录（REQ-002、REQ-003）。
 * 需求 REQ-002：每日首次打卡记为上班打卡，同一员工当日第二次打卡记为下班打卡。
 * 需求 REQ-003：迟到判定规则：上班打卡时间超过09:00即记为迟到。
 */
public class AttendanceRecord {

    private final String employeeId;
    private final LocalDate date;
    private LocalTime punchIn;
    private LocalTime punchOut;

    public AttendanceRecord(String employeeId, LocalDate date) {
        this.employeeId = employeeId;
        this.date = date;
    }

    public String getEmployeeId() { return employeeId; }
    public LocalDate getDate() { return date; }
    public LocalTime getPunchIn() { return punchIn; }
    public LocalTime getPunchOut() { return punchOut; }

    /** 上班打卡：记录首次打卡时间，并按迟到判定规则标记是否迟到。 */
    public void setPunchIn(LocalTime punchIn) {
        this.punchIn = punchIn;
        if (LatePolicy.isLate(punchIn)) {
            markLate();
        }
    }

    /** 下班打卡：记录第二次打卡时间。 */
    public void setPunchOut(LocalTime punchOut) {
        this.punchOut = punchOut;
    }

    /** 迟到状态标记。 */
    public boolean late;

    private void markLate() {
        this.late = true;
    }

    /** 是否迟到。 */
    public boolean isLate() { return late; }

    /** 下班打卡是否已完成。 */
    public boolean hasPunchOut() { return punchOut != null; }
}
