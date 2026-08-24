package com.sample.exam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ExamService - 考试核心服务 (Core exam service)
 * 负责考试创建、组卷、答题、评分、统计、排名、防作弊与学习计划生成。
 */
public class ExamService {

    /** 考试存储 (exam store) */
    private final Map<String, Exam> examStore = new HashMap<>();

    /** 学生答案存储: examId -> studentId -> questionId -> answer */
    private final Map<String, Map<String, Map<String, String>>> answerStore = new HashMap<>();

    /** 考试成绩存储: examId -> studentId -> totalScore */
    private final Map<String, Map<String, Double>> scoreStore = new HashMap<>();

    /** 标签页切换次数记录: examId -> studentId -> switchCount */
    private final Map<String, Map<String, Integer>> tabSwitchStore = new HashMap<>();

    /** 学习计划存储 (study plan store, 超范围功能) */
    private final Map<String, String> studyPlanStore = new HashMap<>();

    /**
     * 1. 创建考试 (Create exam, REQ-001)
     * 创建后默认状态为 UNPUBLISHED。
     *
     * 【缺陷 DEF-006】未对 duration 做合法性校验（duration 可为 0 或负数），
     * 而 REQ-001 定义的考试时长隐含应为正值。
     */
    public Exam createExam(String id, String title, int duration, int passingScore) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("考试ID不能为空");
        }
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("考试标题不能为空");
        }
        // 【缺陷 DEF-006】缺少 duration > 0 校验：
        // public Exam createExam(String id, String title, int duration, int passingScore)
        Exam exam = new Exam(id, title, duration, passingScore);
        examStore.put(id, exam);
        return exam;
    }

    /**
     * 2. 添加题目到考试 (Add question to exam, REQ-002)
     */
    public void addQuestion(String examId, Question question) {
        Exam exam = examStore.get(examId);
        if (exam == null) {
            throw new IllegalArgumentException("考试不存在: " + examId);
        }
        exam.addQuestion(question);
    }

    /**
     * 3. 从考试中移除题目 (Remove question from exam)
     */
    public boolean removeQuestion(String examId, String questionId) {
        Exam exam = examStore.get(examId);
        if (exam == null || exam.getQuestions() == null) {
            return false;
        }
        return exam.getQuestions().removeIf(q -> questionId.equals(q.getId()));
    }

    /**
     * 4. 随机组卷 (Generate paper, REQ-003)
     * 按难度从题库随机抽取题目组成试卷。
     */
    public List<Question> generatePaper(String examId, Map<String, Integer> countByDifficulty) {
        Exam exam = examStore.get(examId);
        if (exam == null) {
            throw new IllegalArgumentException("考试不存在: " + examId);
        }
        List<Question> all = exam.getQuestions();
        if (all == null) {
            return new ArrayList<>();
        }
        List<Question> paper = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countByDifficulty.entrySet()) {
            String difficulty = entry.getKey();
            int need = entry.getValue();
            List<Question> pool = new ArrayList<>();
            for (Question q : all) {
                if (difficulty.equals(q.getDifficulty())) {
                    pool.add(q);
                }
            }
            Collections.shuffle(pool);
            for (int i = 0; i < need && i < pool.size(); i++) {
                paper.add(pool.get(i));
            }
        }
        return paper;
    }

    /**
     * 5. 学生开始考试 (Student starts exam, REQ-004)
     * 记录开始时间，初始化答案容器。
     */
    public void startExam(String examId, String studentId) {
        if (!examStore.containsKey(examId)) {
            throw new IllegalArgumentException("考试不存在: " + examId);
        }
        answerStore.computeIfAbsent(examId, k -> new HashMap<>())
                .computeIfAbsent(studentId, k -> new HashMap<>());
        tabSwitchStore.computeIfAbsent(examId, k -> new HashMap<>())
                .put(studentId, 0);
    }

    /**
     * 6. 提交试卷 (Submit exam, REQ-004)
     * 超时后自动提交；正常时间内可自由提交。
     *
     * 【缺陷 DEF-004】提交超时判断逻辑反转：
     * 当 elapsed < duration（正常时间内）时拒绝提交，超时后才允许提交，
     * 与 REQ-004「超时后自动提交、正常时间内可提交」相反。
     */
    public boolean submitExam(String examId, String studentId, long elapsedMinutes) {
        Exam exam = examStore.get(examId);
        if (exam == null) {
            return false;
        }
        // 【缺陷 DEF-004】逻辑反转：正常时间内拒绝提交
        if (elapsedMinutes < exam.getDuration()) {
            return false;
        }
        // 正常应是 elapsedMinutes >= duration 时才允许提交
        recordAnswerSnapshot(examId, studentId);
        return true;
    }

    /**
     * 保存答案 (Save answer)
     */
    private void recordAnswerSnapshot(String examId, String studentId) {
        // 提交时固化答案（简化实现）
    }

    /**
     * 学生作答 (Answer a question)
     */
    public void answer(String examId, String studentId, String questionId, String answer) {
        Map<String, Map<String, String>> examAnswers =
                answerStore.computeIfAbsent(examId, k -> new HashMap<>());
        Map<String, String> studentAnswers =
                examAnswers.computeIfAbsent(studentId, k -> new HashMap<>());
        studentAnswers.put(questionId, answer);
    }

    /**
     * 7. 客观题自动评分 (Auto grade, REQ-005)
     *
     * 【缺陷 DEF-005】从 answerStore 获取学生答案后未做 null 检查即传入评分方法，
     * 当学生未作答时 studentAnswer 为 null，后续 gradeSingleChoice/gradeTrueFalse
     * 调用 null.equals() 抛出 NullPointerException。
     */
    public Map<String, Integer> autoGrade(String examId, String studentId, List<Question> questions,
                                          GradingService gradingService) {
        Map<String, Map<String, String>> examAnswers = answerStore.get(examId);
        if (examAnswers == null) {
            return new HashMap<>();
        }
        Map<String, String> studentAnswers = examAnswers.get(studentId);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Question q : questions) {
            // 【缺陷 DEF-005】未对 studentAnswer 做 null 检查
            String studentAnswer = studentAnswers.get(q.getId());
            int score = 0;
            if (Question.TYPE_SINGLE.equals(q.getType())) {
                score = gradingService.gradeSingleChoice(studentAnswer, q.getAnswer());
            } else if (Question.TYPE_MULTI.equals(q.getType())) {
                score = gradingService.gradeMultipleChoice(studentAnswer, q.getAnswer());
            } else if (Question.TYPE_TRUE_FALSE.equals(q.getType())) {
                score = gradingService.gradeTrueFalse(studentAnswer, q.getAnswer());
            }
            result.put(q.getId(), score);
        }
        return result;
    }

    /**
     * 8. 主观题人工评分 (Manual grade, REQ-006)
     */
    public void manualGrade(String examId, String studentId, String questionId, int score, String comment) {
        Map<String, Double> examScores =
                scoreStore.computeIfAbsent(examId, k -> new HashMap<>());
        Double current = examScores.get(studentId);
        double base = current != null ? current : 0.0;
        examScores.put(studentId, base + score);
    }

    /**
     * 9. 计算排名 (Calculate ranking, REQ-007)
     *
     * 【缺陷 DEF-008】排序比较器在两个值相等时返回 1 而非 0，违反 Comparator 传递性约定，
     * 相同分数时可能导致排序不确定或抛 IllegalArgumentException。
     */
    public List<String> calculateRanking(String examId) {
        Map<String, Double> scores = scoreStore.get(examId);
        if (scores == null || scores.isEmpty()) {
            return new ArrayList<>();
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        // 【缺陷 DEF-008】相等时返回 1，违反传递性
        entries.sort((a, b) -> {
            if (a.getValue() <= b.getValue()) {
                return 1;
            }
            return -1;
        });
        List<String> ranking = new ArrayList<>();
        for (Map.Entry<String, Double> e : entries) {
            ranking.add(e.getKey());
        }
        return ranking;
    }

    /**
     * 10. 获取统计信息 (Get statistics, REQ-007)
     *
     * 【缺陷 DEF-003】及格率统计逻辑反转：用 s < passingScore 判断及格，
     * 实际上把不及格人数当作及格人数统计。
     */
    public Map<String, Object> getStatistics(String examId) {
        Map<String, Double> scores = scoreStore.get(examId);
        Exam exam = examStore.get(examId);
        Map<String, Object> stats = new LinkedHashMap<>();
        if (scores == null || scores.isEmpty() || exam == null) {
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
        for (double s : scores.values()) {
            sum += s;
            if (s > highest) {
                highest = s;
            }
            if (s < lowest) {
                lowest = s;
            }
            // 【缺陷 DEF-003】逻辑反转：应为 s >= passingScore
            if (s < exam.getPassingScore()) {
                passCount++;
            }
        }
        int total = scores.size();
        stats.put("average", sum / total);
        stats.put("highest", highest);
        stats.put("lowest", lowest);
        // 反向统计：passCount 实际是不及格人数
        stats.put("passRate", (double) passCount / total);
        return stats;
    }

    /**
     * 11. 导出考试结果 (Export results, REQ-010)
     */
    public String exportResults(String examId) {
        Exam exam = examStore.get(examId);
        if (exam == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("考试: ").append(exam.getTitle()).append("\n");
        Map<String, Double> scores = scoreStore.get(examId);
        if (scores != null) {
            for (Map.Entry<String, Double> e : scores.entrySet()) {
                sb.append("学生 ").append(e.getKey())
                        .append(" 总分: ").append(e.getValue()).append("\n");
            }
        }
        List<String> ranking = calculateRanking(examId);
        sb.append("排名: ").append(String.join(", ", ranking)).append("\n");
        return sb.toString();
    }

    /**
     * 12. 检测作弊行为 (Detect cheating, REQ-008)
     * 包括标签页切换检测与重复答案检测。
     *
     * 【缺陷 DEF-009】访问 answerList.get(answerList.size()) 越界，
     * 应为 answerList.get(answerList.size() - 1)。
     * 【缺陷 DEF-011】标签切换阈值 5 硬编码，未在 REQ-008 中定义。
     */
    public boolean detectCheating(String examId, String studentId, List<String> answers) {
        // 重复答案检测
        if (answers != null && answers.size() > 1) {
            // 【缺陷 DEF-009】数组越界：索引应为 size()-1
            String last = answers.get(answers.size());
            for (String a : answers) {
                if (a != null && a.equals(last)) {
                    return true;
                }
            }
        }
        // 标签切换检测
        Map<String, Integer> switches = tabSwitchStore.get(examId);
        if (switches != null) {
            Integer count = switches.get(studentId);
            // 【缺陷 DEF-011】阈值 5 未在需求中定义
            if (count != null && count > 5) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记录标签页切换 (Record tab switch, REQ-008 辅助)
     */
    public void recordTabSwitch(String examId, String studentId) {
        Map<String, Integer> switches = tabSwitchStore.computeIfAbsent(examId, k -> new HashMap<>());
        switches.put(studentId, switches.getOrDefault(studentId, 0) + 1);
    }

    /**
     * 13. 自动生成学习计划 (Generate study plan) —— 【超范围实现 DEF-002】
     * 根据学生成绩自动生成个性化学习计划。该功能不在 requirements.txt 任何需求条目中定义。
     */
    public String generateStudyPlan(String studentId, String examId) {
        Map<String, Double> scores = scoreStore.get(examId);
        if (scores == null || !scores.containsKey(studentId)) {
            return "暂无成绩数据，无法生成学习计划";
        }
        double score = scores.get(studentId);
        StringBuilder plan = new StringBuilder();
        plan.append("学生 ").append(studentId).append(" 学习计划:\n");
        if (score < 60) {
            plan.append("- 重点复习基础知识\n");
            plan.append("- 每日练习 10 道选择题\n");
        } else if (score < 80) {
            plan.append("- 巩固中等难度题型\n");
            plan.append("- 每周模拟测试一次\n");
        } else {
            plan.append("- 挑战高难度拓展题\n");
            plan.append("- 参与习题讲解\n");
        }
        String planText = plan.toString();
        studyPlanStore.put(studentId + ":" + examId, planText);
        return planText;
    }

    /**
     * 14. 成绩复核 (Review grade, REQ-009)
     */
    public double reviewGrade(String examId, String studentId, double adjustedScore) {
        Map<String, Double> scores = scoreStore.computeIfAbsent(examId, k -> new HashMap<>());
        scores.put(studentId, adjustedScore);
        return adjustedScore;
    }

    // ===== 查询辅助 =====

    public Exam getExam(String examId) {
        return examStore.get(examId);
    }

    public Map<String, Double> getScores(String examId) {
        return scoreStore.get(examId);
    }
}
