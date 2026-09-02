package attendance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考勤打卡服务（REQ-001、REQ-002、REQ-005、REQ-006）。
 * 需求 REQ-001：员工打卡时必须校验员工号存在于员工花名册中，员工号不存在时打卡失败并记录异常。
 * 需求 REQ-002：每日首次打卡记为上班打卡，同一员工当日第二次打卡记为下班打卡，超过两次的打卡被拒绝。
 * 需求 REQ-005：加班时长按小时为单位统计，每次加班登记必须记录加班日期与时长小时数。
 * 需求 REQ-006：月度出勤统计需汇总指定月份内每位员工的出勤天数、迟到次数与加班总时长。
 */
public class AttendanceService {

    private final Set<String> roster = new HashSet<>();
    private final List<String> anomalies = new ArrayList<>();
    private final Map<String, List<AttendanceRecord>> dayRecords = new HashMap<>();
    private final Map<String, List<OvertimeLog>> overtimeLogs = new HashMap<>();

    /** 加班登记记录（REQ-005）：加班日期与时长小时数。 */
    public static class OvertimeLog {
        public final LocalDate date;
        public final double hours;

        public OvertimeLog(LocalDate date, double hours) {
            this.date = date;
            this.hours = hours;
        }
    }

    /** 员工入职登记进花名册。 */
    public void enroll(String employeeId) {
        roster.add(employeeId);
    }

    /**
     * 员工打卡：非空校验员工号，状态校验员工号必须存在于员工花名册中，
     * 员工号不存在时打卡失败并记录异常；每日首次打卡记为上班打卡，
     * 同一员工当日第二次打卡记为下班打卡，超过两次的打卡被拒绝。
     */
    public boolean punch(String employeeId, LocalDate date, LocalTime time) {
        // 非空校验：员工号不能为空
        if (employeeId == null || employeeId.isEmpty()) {
            return false;
        }
        // 状态校验：员工号必须存在于员工花名册中，不存在时打卡失败并记录异常
        if (!roster.contains(employeeId)) {
            anomalies.add("异常打卡 [REQ-001: 员工打卡时必须校验员工号存在于员工花名册中，员工号不存在时打卡失败并记录异常]：员工号 "
                    + employeeId + " 不在花名册中");
            return false;
        }
        List<AttendanceRecord> records = dayRecords.computeIfAbsent(employeeId, k -> new ArrayList<>());
        AttendanceRecord today = null;
        for (AttendanceRecord r : records) {
            if (r.getDate().equals(date)) {
                today = r;
                break;
            }
        }
        if (today == null) {
            // 每日首次打卡记为上班打卡
            AttendanceRecord record = new AttendanceRecord(employeeId, date);
            record.setPunchIn(time);
            records.add(record);
            return true;
        }
        if (!today.hasPunchOut()) {
            // 当日第二次打卡记为下班打卡
            today.setPunchOut(time);
            return true;
        }
        // 超过两次的打卡被拒绝
        return false;
    }

    /**
     * 加班登记：数值阈值校验时长小时数必须大于0，记录加班日期与时长小时数。
     */
    public boolean logOvertime(String employeeId, LocalDate date, double hours) {
        // 数值阈值校验：加班时长必须大于0
        if (hours <= 0) {
            return false;
        }
        overtimeLogs.computeIfAbsent(employeeId, k -> new ArrayList<>()).add(new OvertimeLog(date, hours));
        return true;
    }

    /**
     * 月度出勤统计：汇总指定月份内每位员工的出勤天数、迟到次数与加班总时长。
     */
    public MonthlySummary monthlySummary(String employeeId, int year, int month) {
        int attendDays = 0;
        int lateTimes = 0;
        double overtimeTotal = 0;
        List<AttendanceRecord> records = dayRecords.getOrDefault(employeeId, new ArrayList<>());
        for (AttendanceRecord r : records) {
            if (r.getDate().getYear() == year && r.getDate().getMonthValue() == month && r.getPunchIn() != null) {
                attendDays++;
                if (r.isLate()) {
                    lateTimes++;
                }
            }
        }
        for (OvertimeLog log : overtimeLogs.getOrDefault(employeeId, new ArrayList<>())) {
            if (log.date.getYear() == year && log.date.getMonthValue() == month) {
                overtimeTotal += log.hours;
            }
        }
        return new MonthlySummary(employeeId, year, month, attendDays, lateTimes, overtimeTotal);
    }

    /** 员工花名册。 */
    public Set<String> getRoster() {
        return new HashSet<>(roster);
    }

    /** 异常打卡记录列表。 */
    public List<String> getAnomalies() {
        return new ArrayList<>(anomalies);
    }
}
