package com.sample.exam;

/**
 * GradingService - 评分服务 (Grading and review logic)
 * 负责客观题（单选/多选/判断）评分与总分计算、明细与复核。
 */
public class GradingService {

    /**
     * 1. 单选题评分 (Grade single choice, REQ-005)
     *
     * 【缺陷 DEF-010 / DEF-012】直接调用 studentAnswer.equals(question.getAnswer())，
     * 当 studentAnswer 为 null 时会抛出 NullPointerException。
     */
    public int gradeSingleChoice(String studentAnswer, String correctAnswer) {
        if (studentAnswer.equals(correctAnswer)) {
            return 1;
        }
        return 0;
    }

    /**
     * 2. 多选题评分 (Grade multiple choice, REQ-005)
     * 需全部选对才得分。
     */
    public int gradeMultipleChoice(String studentAnswer, String correctAnswer) {
        if (studentAnswer == null || correctAnswer == null) {
            return 0;
        }
        String[] studentParts = studentAnswer.split(",");
        String[] correctParts = correctAnswer.split(",");
        if (studentParts.length != correctParts.length) {
            return 0;
        }
        for (String s : studentParts) {
            boolean found = false;
            for (String c : correctParts) {
                if (s.trim().equals(c.trim())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return 0;
            }
        }
        return 1;
    }

    /**
     * 3. 判断题评分 (Grade true/false, REQ-005)
     *
     * 【缺陷 DEF-010 / DEF-012】与 gradeSingleChoice 相同，studentAnswer 为 null 时
     * 调用 equals 抛出 NullPointerException。
     */
    public int gradeTrueFalse(String studentAnswer, String correctAnswer) {
        if (studentAnswer.equals(correctAnswer)) {
            return 1;
        }
        return 0;
    }

    /**
     * 4. 简答题评分 (Grade essay, REQ-006) - 人工评分占位
     */
    public int gradeEssay(String studentAnswer, String referenceAnswer) {
        if (studentAnswer == null || studentAnswer.trim().isEmpty()) {
            return 0;
        }
        // 简化：非空即给一半分
        return 1;
    }

    /**
     * 5. 计算总分 (Calculate total score)
     */
    public double calculateTotalScore(java.util.Map<String, Integer> scores) {
        double total = 0.0;
        if (scores == null) {
            return total;
        }
        for (int s : scores.values()) {
            total += s;
        }
        return total;
    }

    /**
     * 6. 获取分数明细 (Get score breakdown)
     */
    public String getScoreBreakdown(java.util.Map<String, Integer> scores) {
        if (scores == null || scores.isEmpty()) {
            return "无成绩明细";
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : scores.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 7. 复核成绩 (Review grade, REQ-009)
     */
    public int reviewGrade(int originalScore, int adjustedScore, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("复核必须有明确理由");
        }
        return adjustedScore;
    }

    /**
     * 8. 申诉成绩 (Appeal grade, REQ-009)
     */
    public String appealGrade(String studentId, String examId, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("申诉必须有明确理由");
        }
        return "申诉已提交: " + studentId + " / " + examId;
    }

    /**
     * 处理成绩申诉 (Process appeal, REQ-009)
     * 与 appealGrade 语义一致，供一致性标注调用。
     */
    public String processAppeal(String studentId, String examId, String reason) {
        return appealGrade(studentId, examId, reason);
    }
}
