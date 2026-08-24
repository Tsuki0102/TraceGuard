package com.sample.exam;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Exam - 考试实体类 (Exam entity)
 * 表示一场考试的基本信息，包括标题、时长、及格分数、状态与题目列表。
 */
public class Exam {

    /** 考试状态常量 (Exam status constants) */
    public static final String STATUS_UNPUBLISHED = "UNPUBLISHED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ONGOING = "ONGOING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 考试唯一ID (Exam ID) */
    private String id;

    /** 考试标题 (title) */
    private String title;

    /** 考试时长，单位分钟 (duration in minutes) */
    private int duration;

    /** 及格分数线 (passing score) */
    private int passingScore;

    /** 考试状态 (status) */
    private String status;

    /** 创建时间 (create time) */
    private LocalDateTime createTime;

    /** 题目列表 (question list) */
    private List<Question> questions;

    /** 考试提醒时间(分钟)，提前多久提醒学生 (reminderMinutes - 提前 N 分钟提醒学生) */
    private int reminderMinutes;

    public Exam() {
        this.questions = new ArrayList<>();
        this.status = STATUS_UNPUBLISHED;
        this.createTime = LocalDateTime.now();
        this.reminderMinutes = 10;
    }

    public Exam(String id, String title, int duration, int passingScore) {
        this();
        this.id = id;
        this.title = title;
        this.duration = duration;
        this.passingScore = passingScore;
    }

    // ========== Getters and Setters ==========

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getPassingScore() {
        return passingScore;
    }

    public void setPassingScore(int passingScore) {
        this.passingScore = passingScore;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }

    public void addQuestion(Question question) {
        if (this.questions == null) {
            this.questions = new ArrayList<>();
        }
        this.questions.add(question);
    }

    public int getReminderMinutes() {
        return reminderMinutes;
    }

    public void setReminderMinutes(int reminderMinutes) {
        this.reminderMinutes = reminderMinutes;
    }

    /**
     * 获取考试摘要信息 (Get exam summary)
     */
    public String getSummary() {
        return String.format("考试[%s] 标题:%s 时长:%d分钟 及格分:%d 状态:%s 题数:%d",
                id, title, duration, passingScore, status,
                questions != null ? questions.size() : 0);
    }

    @Override
    public String toString() {
        return getSummary();
    }
}
