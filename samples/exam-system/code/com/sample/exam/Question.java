package com.sample.exam;

/**
 * Question - 题目实体类 (Question entity)
 * 支持单选/多选/判断/简答四种类型，包含题干、选项、答案、难度与分值。
 */
public class Question {

    /** 题目类型 (Question types) */
    public static final String TYPE_SINGLE = "SINGLE_CHOICE";
    public static final String TYPE_MULTI = "MULTI_CHOICE";
    public static final String TYPE_TRUE_FALSE = "TRUE_FALSE";
    public static final String TYPE_ESSAY = "ESSAY";

    /** 难度等级 (Difficulty levels) */
    public static final String DIFFICULTY_EASY = "EASY";
    public static final String DIFFICULTY_MEDIUM = "MEDIUM";
    public static final String DIFFICULTY_HARD = "HARD";

    /** 题目ID */
    private String id;

    /** 题干内容 (stem) */
    private String content;

    /** 题目类型 */
    private String type;

    /** 难度等级 */
    private String difficulty;

    /** 选项 (客观题使用, options) */
    private String[] options;

    /** 正确答案 (correct answer) */
    private String answer;

    /** 分值 (score) */
    private int score;

    public Question() {
    }

    public Question(String id, String content, String type, String difficulty, int score) {
        this.id = id;
        this.content = content;
        this.type = type;
        this.difficulty = difficulty;
        this.score = score;
    }

    // ========== Getters and Setters ==========

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String[] getOptions() {
        return options;
    }

    public void setOptions(String[] options) {
        this.options = options;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    /**
     * 是否为客观题 (Is objective question: 单选/多选/判断)
     */
    public boolean isObjective() {
        return TYPE_SINGLE.equals(type) || TYPE_MULTI.equals(type) || TYPE_TRUE_FALSE.equals(type);
    }

    @Override
    public String toString() {
        return String.format("题目[%s] 类型:%s 难度:%s 分值:%d", id, type, difficulty, score);
    }
}
