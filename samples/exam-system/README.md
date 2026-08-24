# Exam System Sample Project (在线考试系统)

## Overview

This is a **Chinese-English mixed** Java sample project representing an online examination system.
It serves as a test scenario for code analysis, requirement traceability, and defect detection tools.

The project is built using **JDK standard library only** — no Spring, no third-party dependencies.

## Project Structure

```
exam-system/
├── README.md               # This file
├── requirements.txt         # Requirements specification (REQ-001 ~ REQ-011)
├── defects.json             # Ground truth: injected defect catalog (12 defects)
└── code/
    └── com/sample/exam/
        ├── Exam.java            # Exam model entity
        ├── Question.java        # Question model entity
        ├── ExamService.java     # Core exam service (14 methods)
        ├── QuestionBank.java    # Question bank management (8 methods)
        ├── GradingService.java  # Grading and review logic (8 methods)
        └── ScoreCalculator.java # Score aggregation & statistics (2 methods)
```

## Scenario Description

This sample models an **online examination system** (在线考试系统) used in educational institutions.
The system covers the full exam lifecycle:

1. **Exam Creation** (创建考试) — Administrators create exams with title, duration, and passing score
2. **Question Management** (题目管理) — CRUD operations for single choice, multiple choice, true/false, and essay questions
3. **Paper Generation** (试卷生成) — Random selection from question bank by difficulty level
4. **Student Participation** (学生参加考试) — Start exam, answer questions, auto-submit on timeout
5. **Auto Grading** (自动评分) — Objective questions graded automatically
6. **Manual Grading** (人工阅卷) — Essay questions require instructor review
7. **Statistics & Ranking** (成绩统计与排名) — Average, highest, lowest scores, pass rate, ranking
8. **Anti-Cheating** (防作弊检测) — Tab switch detection, duplicate answer detection
9. **Result Review & Appeal** (成绩复核与申诉) — Students can appeal grades
10. **Export Results** (导出考试结果) — Generate text-based reports
11. **Question Bank I/O** (题库导入导出) — Batch import/export of questions

## Method Inventory

### ExamService.java (~12 methods)

| # | Method | Description |
|---|--------|-------------|
| 1 | `createExam` | 创建考试 |
| 2 | `addQuestion` | 添加题目到考试 |
| 3 | `removeQuestion` | 从考试中移除题目 |
| 4 | `generatePaper` | 随机组卷 |
| 5 | `startExam` | 学生开始考试 |
| 6 | `submitExam` | 提交试卷 |
| 7 | `autoGrade` | 客观题自动评分 |
| 8 | `manualGrade` | 主观题人工评分 |
| 9 | `calculateRanking` | 计算排名 |
| 10 | `getStatistics` | 获取统计信息 |
| 11 | `exportResults` | 导出考试结果 |
| 12 | `detectCheating` | 检测作弊行为 |
| 13 | `generateStudyPlan` | 自动生成学习计划 (超范围) |
| 14 | `recordTabSwitch` | 记录标签页切换 |

### QuestionBank.java (~8 methods)

| # | Method | Description |
|---|--------|-------------|
| 1 | `importQuestions` | 导入题目 |
| 2 | `exportQuestions` | 导出题目 |
| 3 | `addQuestion` | 添加单个题目 |
| 4 | `removeQuestion` | 移除题目 |
| 5 | `queryByDifficulty` | 按难度查询 |
| 6 | `queryByType` | 按类型查询 |
| 7 | `getRandomQuestions` | 随机获取题目 |
| 8 | `batchImport` | 批量导入 |

### GradingService.java (~8 methods)

| # | Method | Description |
|---|--------|-------------|
| 1 | `gradeSingleChoice` | 单选题评分 |
| 2 | `gradeMultipleChoice` | 多选题评分 |
| 3 | `gradeTrueFalse` | 判断题评分 |
| 4 | `gradeEssay` | 简答题评分 |
| 5 | `calculateTotalScore` | 计算总分 |
| 6 | `getScoreBreakdown` | 获取分数明细 |
| 7 | `reviewGrade` | 复核成绩 |
| 8 | `appealGrade` | 申诉成绩 |
| 8b | `processAppeal` | 处理成绩申诉（委托 appealGrade） |

### ScoreCalculator.java (2 methods)

| # | Method | Description |
|---|--------|-------------|
| 1 | `gradeExam` | 计算考试总分（汇总各题得分） |
| 2 | `calculateStatistics` | 计算统计指标（平均分/最高/最低/及格率） |

**Total: ~32 methods** across 4 service classes + 2 model classes.

## Defect Distribution

The project contains **12 intentionally injected defects** documented in `defects.json`:

| Defect Type | Count | Description |
|-------------|-------|-------------|
| 需求缺失 (Missing Requirement) | 2 | Code implements features not in requirements |
| 代码超范围实现 (Over-Implementation) | 1 | Code goes beyond requirement scope |
| 业务逻辑不一致 (Business Logic Inconsistency) | 2 | Logic contradicts requirement specifications |
| 约束条件不满足 (Constraint Violation) | 3 | Missing validations and null checks |
| 基础代码缺陷 (Basic Code Defect) | 4 | Empty catch, infinite loop risk, array bounds |

### Severity Distribution

| Severity | Count |
|----------|-------|
| HIGH | 6 |
| MEDIUM | 4 |
| LOW | 2 |

### File Distribution

| File | Defect Count |
|------|-------------|
| ExamService.java | 8 |
| GradingService.java | 2 |
| QuestionBank.java | 1 |
| Exam.java | 1 |

## Language Mixing Note (中英文混用说明)

This project intentionally mixes **Chinese and English** throughout:

- **Requirements (requirements.txt)**: ~60% Chinese, ~40% English entries
- **Code comments**: Mixed Chinese and English descriptions
- **Variable names**: English (standard Java convention)
- **String literals**: Primarily Chinese (user-facing messages)
- **Method documentation**: Bilingual (Chinese + English)

This reflects real-world codebases in Chinese software companies where
developers naturally mix languages in documentation, comments, and code.

## Dependencies

- **JDK Standard Library only** — no external dependencies
- Uses: `java.util.*` (Collections, HashMap, ArrayList, etc.)
- Compatible with JDK 8+

## How to Compile

```bash
cd code
javac com/sample/exam/*.java
```

## How to Run

This is a library/sample project without a main class.
It is designed for static analysis and code review testing,
not for runtime execution.
