package com.traceguard.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * GAP-007：评测报告输出辅助（写入 docs/03-报告/评测报告-综合.md）。
 * 采用"按章节合并"策略：两个评测类（SpecConversionEvalTest / DefectDetectionEvalTest）
 * 各自生成章节，写入同一报告文件；重复运行同名章节会被覆盖，避免重复追加。
 */
public class EvalReportWriter {

    /** 报告路径（相对 backend 模块运行目录；Maven surefire 工作目录为 backend/） */
    public static final Path REPORT_PATH = Path.of("..", "docs", "03-报告", "评测报告-综合.md");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private EvalReportWriter() {}

    /**
     * 写入/合并一个章节。
     * @param sectionId   章节唯一标识（如 spec-conversion / defect-detection）
     * @param title       章节标题
     * @param markdownBody 章节正文（不含标题）
     */
    public static synchronized void writeSection(String sectionId, String title, String markdownBody) throws IOException {
        Path report = REPORT_PATH.toAbsolutePath().normalize();
        String section = "<!-- SECTION:" + sectionId + " -->";
        String rendered = "## " + title + "\n\n" + markdownBody + "\n";

        String existing = Files.exists(report) ? Files.readString(report, StandardCharsets.UTF_8) : "";
        StringBuilder sb = new StringBuilder();
        if (existing.isEmpty()) {
            sb.append("# TraceGuard 评测报告\n\n")
              .append("> 本报告由 GAP-007 评测类自动生成（`mvn test -Dtest=SpecConversionEvalTest,DefectDetectionEvalTest`）。\n")
              .append("> 生成时间：").append(LocalDateTime.now().format(TIME)).append("\n\n")
              .append("---\n\n");
        } else {
            sb.append(existing);
        }

        // 若已存在同名章节，替换；否则追加
        int start = sb.indexOf(section);
        if (start >= 0) {
            int sectionEnd = sb.indexOf("\n## ", start + section.length());
            int closeIdx = sb.indexOf(section + "~END", start);
            if (closeIdx > start) {
                sb.replace(start, closeIdx + (section + "~END").length(), "");
                // 重新定位追加位置（替换后原内容前移）
                appendSection(sb, section, rendered);
            } else {
                // 无结束标记：截到下一个章节前
                int next = sb.indexOf("<!-- SECTION:", start + section.length());
                int end = next > start ? next : sb.length();
                sb.replace(start, end, rendered + "\n" + section + "~END\n\n");
            }
        } else {
            sb.append("\n").append(section).append("\n").append(rendered).append(section).append("~END\n\n");
        }

        Files.createDirectories(report.getParent());
        Files.writeString(report, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void appendSection(StringBuilder sb, String section, String rendered) {
        sb.append("\n").append(section).append("\n").append(rendered).append(section).append("~END\n\n");
    }

    /** 生成环境与配置快照（markdown 表） */
    public static String envSnapshot(String mode, String model, String extra) {
        return "| 项 | 值 |\n|---|---|\n"
                + "| 评测模式 | " + mode + " |\n"
                + "| 模型 | " + model + " |\n"
                + "| Alloy 校验 | 真实 Alloy 语义求解（不可用时降级结构校验） |\n"
                + "| Embedding | " + extra + " |\n"
                + "| 数据集 | samples/dataset/ |\n"
                + "| 生成时间 | " + LocalDateTime.now().format(TIME) + " |\n";
    }

    /** 把 double 格式化为百分数（保留 1 位） */
    public static String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }
}
