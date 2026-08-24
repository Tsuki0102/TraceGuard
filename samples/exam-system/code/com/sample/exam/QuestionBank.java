package com.sample.exam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * QuestionBank - 题库管理 (Question bank management)
 * 支持题目的增删查、按难度/类型查询与批量导入导出。
 */
public class QuestionBank {

    /** 题库存储 (question bank store) */
    private final List<Question> questions = new ArrayList<>();

    /**
     * 1. 导入题目 (Import question, REQ-011)
     */
    public void importQuestions(List<Question> imported) {
        if (imported == null) {
            return;
        }
        questions.addAll(imported);
    }

    /**
     * 2. 导出题目 (Export questions, REQ-011)
     * 按类型和难度分类整理。
     */
    public List<Question> exportQuestions() {
        return new ArrayList<>(questions);
    }

    /**
     * 3. 添加单个题目 (Add single question, REQ-002)
     */
    public void addQuestion(Question question) {
        if (question == null) {
            throw new IllegalArgumentException("题目不能为空");
        }
        questions.add(question);
    }

    /**
     * 4. 移除题目 (Remove question, REQ-002)
     */
    public boolean removeQuestion(String questionId) {
        return questions.removeIf(q -> questionId.equals(q.getId()));
    }

    /**
     * 5. 按难度查询 (Query by difficulty, REQ-011)
     */
    public List<Question> queryByDifficulty(String difficulty) {
        List<Question> result = new ArrayList<>();
        for (Question q : questions) {
            if (difficulty.equals(q.getDifficulty())) {
                result.add(q);
            }
        }
        return result;
    }

    /**
     * 6. 按类型查询 (Query by type, REQ-011)
     */
    public List<Question> queryByType(String type) {
        List<Question> result = new ArrayList<>();
        for (Question q : questions) {
            if (type.equals(q.getType())) {
                result.add(q);
            }
        }
        return result;
    }

    /**
     * 7. 随机获取题目 (Get random questions)
     */
    public List<Question> getRandomQuestions(int count) {
        List<Question> pool = new ArrayList<>(questions);
        java.util.Collections.shuffle(pool);
        List<Question> result = new ArrayList<>();
        for (int i = 0; i < count && i < pool.size(); i++) {
            result.add(pool.get(i));
        }
        return result;
    }

    /**
     * 8. 批量导入 (Batch import, REQ-011)
     * 从文本逐行解析题目，需进行格式校验。
     *
     * 【缺陷 DEF-007】两层空的 catch 块吞掉所有异常，导入格式错误时静默忽略，
     * 既不记录日志也不通知调用方，导致数据丢失难以排查。
     */
    public int batchImport(String text) {
        int importedCount = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    String[] parts = line.split("\\|");
                    if (parts.length < 4) {
                        // 格式不完整，应记录并跳过
                        continue;
                    }
                    Question q = new Question(parts[0], parts[1], parts[2], parts[3],
                            Integer.parseInt(parts.length > 4 ? parts[4] : "0"));
                    questions.add(q);
                    importedCount++;
                } catch (Exception e) {
                    // 【缺陷 DEF-007】空的 catch 块，吞掉了所有异常
                }
            }
        } catch (IOException e) {
            // 【缺陷 DEF-007】空的 catch 块，吞掉了 IO 异常
        }
        return importedCount;
    }

    // ===== 查询辅助 =====

    public List<Question> getAll() {
        return new ArrayList<>(questions);
    }

    public int size() {
        return questions.size();
    }
}
