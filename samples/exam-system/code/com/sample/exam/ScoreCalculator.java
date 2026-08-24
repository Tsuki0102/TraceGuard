package com.sample.exam;

import java.util.List;
import java.util.Map;

/**
 * ScoreCalculator - 成绩计算器 (Score calculator)
 * 负责根据各题得分汇总考试总分，并按维度计算统计指标（平均分/最高/最低/及格率）。
 */
public class ScoreCalculator {

    /**
     * 1. 计算考试总分 (Grade exam, REQ-005)
     * 汇总一份答卷中各题得分得到总分。
     */
    public double gradeExam(Map<String, Integer> questionScores) {
        double total = 0.0;
        if (questionScores == null) {
            return total;
        }
        for (int s : questionScores.values()) {
            total += s;
        }
        return total;
    }

    /**
     * 2. 计算统计指标 (Calculate statistics, REQ-007)
     * 基于全部学生总分计算平均分、最高分、最低分与及格率。
     *
     * @param scores        学生成绩列表 (exam total scores)
     * @param passingScore  及格分数线 (passing score)
     */
    public Map<String, Double> calculateStatistics(List<Double> scores, double passingScore) {
        Map<String, Double> stats = new java.util.LinkedHashMap<>();
        if (scores == null || scores.isEmpty()) {
            stats.put("average", 0.0);
            stats.put("highest", 0.0);
            stats.put("lowest", 0.0);
            stats.put("passRate", 0.0);
            return stats;
        }
        double sum = 0.0;
        double highest = Double.MIN_VALUE;
        double lowest = Double.MAX_VALUE;
        int passCount = 0;
        for (double s : scores) {
            sum += s;
            if (s > highest) {
                highest = s;
            }
            if (s < lowest) {
                lowest = s;
            }
            // 及格判定：分数达到及格线
            if (s >= passingScore) {
                passCount++;
            }
        }
        int total = scores.size();
        stats.put("average", sum / total);
        stats.put("highest", highest);
        stats.put("lowest", lowest);
        stats.put("passRate", (double) passCount / total);
        return stats;
    }
}
