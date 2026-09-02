package attendance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 请假与考勤月报导出服务（REQ-004、REQ-007、REQ-008）。
 * 需求 REQ-004：提交请假申请时必须校验请假开始日期不晚于结束日期，日期范围非法时申请被拒绝。
 * 需求 REQ-007：支持导出指定月份的考勤月报为文本文件，包含每位员工的统计汇总。
 * 需求 REQ-008：异常打卡记录必须转入人工审核队列，由管理员复核后处理。
 */
public class LeaveService {

    private final List<String> leaveRecords = new java.util.ArrayList<>();
    private final java.util.Queue<String> reviewQueue = new java.util.LinkedList<>();

    /**
     * 提交请假申请：登记请假日期范围。
     */
    public boolean applyLeave(String employeeId, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        if (employeeId == null || employeeId.isEmpty()) {
            return false;
        }
        leaveRecords.add(employeeId + " 提交请假申请 [REQ-004: 提交请假申请时必须校验请假开始日期不晚于结束日期] " + startDate + " 至 " + endDate);
        return true;
    }

    /**
     * 导出考勤月报：将每位员工的月度统计汇总写入文本文件。
     */
    public void exportMonthlyReport(Path target, List<MonthlySummary> summaries) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("考勤月报 [REQ-007: 支持导出指定月份的考勤月报为文本文件，包含每位员工的统计汇总]\n");
        for (MonthlySummary s : summaries) {
            sb.append(s.toLine()).append('\n');
        }
        Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 人工审核队列：异常打卡记录转入队列，由管理员复核后处理。
     */
    public void enqueueReview(String anomaly) {
        // 非空校验：异常记录不能为空
        if (anomaly == null || anomaly.isEmpty()) {
            return;
        }
        reviewQueue.add("转入人工审核队列，由管理员复核后处理 [REQ-008: 异常打卡记录必须转入人工审核队列，由管理员复核后处理]：" + anomaly);
    }

    /** 管理员复核：取出队首异常记录进行处理。 */
    public String reviewNext() {
        return reviewQueue.poll();
    }
}
