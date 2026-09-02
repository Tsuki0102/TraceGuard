package attendance;

/** 月度出勤统计汇总（REQ-006）：出勤天数、迟到次数与加班总时长。 */
public class MonthlySummary {

    private final String employeeId;
    private final int year;
    private final int month;
    private final int attendDays;
    private final int lateTimes;
    private final double overtimeHours;

    public MonthlySummary(String employeeId, int year, int month,
                          int attendDays, int lateTimes, double overtimeHours) {
        this.employeeId = employeeId;
        this.year = year;
        this.month = month;
        this.attendDays = attendDays;
        this.lateTimes = lateTimes;
        this.overtimeHours = overtimeHours;
    }

    public String getEmployeeId() { return employeeId; }
    public int getYear() { return year; }
    public int getMonth() { return month; }
    public int getAttendDays() { return attendDays; }
    public int getLateTimes() { return lateTimes; }
    public double getOvertimeHours() { return overtimeHours; }

    /** 月度统计汇总行文本。 */
    public String toLine() {
        return employeeId + " " + year + "-" + month
                + " 出勤天数 " + attendDays + ", 迟到次数 " + lateTimes + ", 加班总时长 " + overtimeHours + " 小时";
    }
}
