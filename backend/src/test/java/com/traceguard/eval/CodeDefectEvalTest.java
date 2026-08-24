package com.traceguard.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceguard.entity.CodeDefect;
import com.traceguard.util.JavaCodeParserUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码基础缺陷检测量化评测（4.7 整改 / FUN-05）。
 *
 * 复用 samples/ecommerce-order 与 samples/exam-system 的 defects.json 作为 ground-truth，
 * 对「基础代码缺陷」（type=基础代码缺陷：空指针/空catch/数组越界/资源未释放/SQL注入等）
 * 做小样本量化：TP/FP/FN 匹配（按 文件+行号 ±2 行），计算检出准确率/漏检率/误报率，
 * 结果写入 docs/03-报告/评测报告-综合.md 的 code-defect-detection 章节。
 *
 * 匹配口径：
 *   - 正例（ground-truth）＝ defects.json 中 type=基础代码缺陷 的条目；
 *   - TP ＝ 检测缺陷命中 ground-truth（文件同名且行号相差 ≤2）；
 *   - FP ＝ 检测缺陷未命中任何 ground-truth；
 *   - FN ＝ ground-truth 未被任何检测命中；
 *   - 准确率＝TP/(TP+FP)，漏检率＝FN/(TP+FN)，误报率＝FP/(TP+FP)。
 */
@DisplayName("代码基础缺陷检测量化评测（ground-truth 小样本，FUN-05）")
class CodeDefectEvalTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private final JavaCodeParserUtil parserUtil = new JavaCodeParserUtil();

    /** 行号匹配容差 */
    private static final int LINE_TOLERANCE = 2;

    private static class GtDefect {
        String id;
        String file;      // 简单文件名
        int line;
        String subtype;
        boolean matched;
    }

    private List<File> collectJavaFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return out;
        for (File c : children) {
            if (c.isDirectory()) out.addAll(collectJavaFiles(c));
            else if (c.getName().endsWith(".java")) out.add(c);
        }
        return out;
    }

    @Test
    @DisplayName("样例工程基础缺陷 ground-truth 量化（准确率/漏检率/误报率）")
    void evalGroundTruth() throws Exception {
        Path root;
        try {
            root = resolveSamplesRoot();
        } catch (IllegalStateException e) {
            // TST-04：samples 缺失时显式 skip（而非静默 return 造成"永远通过的空测试"）
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "samples 目录缺失，跳过基础缺陷量化评测（TST-04 显式 skip）");
            return;
        }
        String[] sources = {"ecommerce-order", "exam-system"};
        List<GtDefect> allGt = new ArrayList<>();
        List<String> gtNotes = new ArrayList<>();
        for (String source : sources) {
            List<GtDefect> gt = loadGroundTruth(root.resolve(source).resolve("defects.json"), source);
            allGt.addAll(gt);
            gtNotes.add(source + "=" + gt.size());
        }

        long tp = 0, fp = 0;
        List<CodeDefect> allDetected = new ArrayList<>();
        for (String source : sources) {
            Path codeDir = root.resolve(source).resolve("code");
            if (!Files.isDirectory(codeDir)) continue;
            List<File> javaFiles = collectJavaFiles(codeDir.toFile());
            for (File f : javaFiles) {
                try {
                    allDetected.addAll(parserUtil.detectBasicDefects(f, codeDir.toString(), 1L, 1L));
                } catch (Exception e) {
                    System.out.println("[CodeDefectEval] 解析失败: " + f.getName() + " - " + e.getMessage());
                }
            }
        }

        // 匹配：检测缺陷 → ground-truth（文件同名 + 行号容差），同一 gt 只计一次 TP
        for (CodeDefect d : allDetected) {
            String fname = d.getFilePath() == null ? "" : new File(d.getFilePath()).getName();
            GtDefect hit = null;
            for (GtDefect g : allGt) {
                if (!g.matched && g.file.equals(fname)
                        && d.getLineNumber() != null && Math.abs(g.line - d.getLineNumber()) <= LINE_TOLERANCE) {
                    hit = g;
                    break;
                }
            }
            if (hit != null) {
                hit.matched = true;
                tp++;
            } else {
                fp++;
            }
        }
        long fn = allGt.stream().filter(g -> !g.matched).count();

        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double miss = tp + fn == 0 ? 0 : (double) fn / (tp + fn);
        double fpr = tp + fp == 0 ? 0 : (double) fp / (tp + fp);

        // 类型分布
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (CodeDefect d : allDetected) dist.merge(d.getDefectType(), 1, Integer::sum);

        StringBuilder sb = new StringBuilder();
        sb.append(EvalReportWriter.envSnapshot("基础代码缺陷 ground-truth 小样本", "N/A", "关闭"))
          .append("\n| 指标 | 值 | 目标（SRS FR-CODE-004） | 判定 |\n|---|---|---|---|\n")
          .append("| 检出准确率（精确率） | ").append(EvalReportWriter.pct(precision))
          .append(" | >= 80% | ").append(precision >= 0.80 ? "达标" : "未达标").append(" |\n")
          .append("| 漏检率 | ").append(EvalReportWriter.pct(miss))
          .append(" | <= 15% | ").append(miss <= 0.15 ? "达标" : "未达标").append(" |\n")
          .append("| 误报率 | ").append(EvalReportWriter.pct(fpr))
          .append(" | <= 10% | ").append(fpr <= 0.10 ? "达标" : "未达标").append(" |\n")
          .append("| ground-truth 基础缺陷数 | ").append(allGt.size()).append(" | 小样本（每类 10 正+10 负为理想） | ")
          .append(allGt.size() >= 8 ? "达标" : "偏少").append(" |\n")
          .append("| 检出缺陷总数 | ").append(allDetected.size()).append(" | - | 含未命中（FP）|")
          .append("\n\n混淆计数：TP=").append(tp).append("，FP=").append(fp).append("，FN=").append(fn).append("\n\n");

        sb.append("### 检出缺陷类型分布\n\n| 缺陷类型 | 数量 |\n|---|---|\n");
        dist.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));

        sb.append("\n### ground-truth 逐条命中情况\n\n| ID | 文件 | 行号 | 标注子类型 | 命中 | 检测类型 |\n|---|---|---|---|---|---|\n");
        for (GtDefect g : allGt) {
            String detType = "-";
            for (CodeDefect d : allDetected) {
                String fname = d.getFilePath() == null ? "" : new File(d.getFilePath()).getName();
                if (g.file.equals(fname) && d.getLineNumber() != null && Math.abs(g.line - d.getLineNumber()) <= LINE_TOLERANCE) {
                    detType = d.getDefectType();
                    break;
                }
            }
            sb.append("| ").append(g.id).append(" | ").append(g.file).append(" | ").append(g.line)
              .append(" | ").append(g.subtype).append(" | ").append(g.matched ? "TP" : "FN")
              .append(" | ").append(detType).append(" |\n");
        }

        sb.append("\n### 口径与局限（FUN-05）\n\n");
        sb.append("- **ground-truth 来源**：samples/ecommerce-order/defects.json（4 条基础缺陷）与 samples/exam-system/defects.json（4 条），"
          + "共 ").append(allGt.size()).append(" 条，覆盖 空指针/空catch/数组越界/比较器传递性。\n");
        sb.append("- **匹配口径**：文件同名 + 行号相差 ≤2 行视为命中；FP 为检出但未命中任何 ground-truth 的缺陷。\n");
        sb.append("- **局限**：小样本（").append(String.join("，", gtNotes))
          .append("），未达每类 10 正+10 负的理想规模；负例（无缺陷方法）未纳入，误报率分母为检出缺陷数，"
          + "与一致性四指标口径（基于标注对）不同，仅供量化追踪。\n");
        sb.append("- **结论**：基础缺陷检测的精确率/漏检/误报以小样本量化留档，行号定位率由既有断言（≥80%）保障；"
          + "完整准确率评测需扩充 ground-truth 后重跑。\n");

        EvalReportWriter.writeSection("code-defect-detection", "三、基础代码缺陷检测量化（FUN-05）", sb.toString());

        System.out.println("[CodeDefectEval] ground-truth=" + allGt.size() + "，TP=" + tp + " FP=" + fp + " FN=" + fn
                + "，准确率=" + EvalReportWriter.pct(precision) + "，漏检率=" + EvalReportWriter.pct(miss)
                + "，误报率=" + EvalReportWriter.pct(fpr));

        assertThat(allGt).isNotEmpty();
        assertThat(tp + fp + fn).isGreaterThan(0);
        assertThat(precision >= 0 && precision <= 1 && miss >= 0 && miss <= 1 && fpr >= 0 && fpr <= 1)
                .as("各指标应为合法概率值").isTrue();
    }

    /** 从 defects.json 提取 type=基础代码缺陷 的条目 */
    private List<GtDefect> loadGroundTruth(Path file, String source) throws Exception {
        List<GtDefect> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        JsonNode root = OM.readTree(Files.readString(file));
        // 兼容两种结构：ecommerce-order 根为数组；exam-system 为 { "defects": [...] }
        JsonNode defects = root.isArray() ? root : root.path("defects");
        if (!defects.isArray()) return out;
        for (JsonNode d : defects) {
            if (!"基础代码缺陷".equals(d.path("type").asText())) continue;
            GtDefect g = new GtDefect();
            g.id = d.path("id").asText();
            // 兼容两种标注格式：ecommerce-order 直接 file/line；exam-system 用 location.file/location.lines
            String locFile = d.path("location").path("file").asText("");
            String lines = d.path("location").path("lines").asText("");
            if (locFile.isEmpty()) locFile = d.path("file").asText("");
            if (lines.isEmpty() && d.path("line").isNumber()) lines = String.valueOf(d.path("line").asInt());
            g.file = new File(locFile).getName();
            g.line = firstLineNumber(lines);
            String subtype = d.path("subtype").asText("");
            g.subtype = subtype.isEmpty() ? d.path("title").asText("") : subtype;
            if (g.line > 0 && !g.file.isEmpty()) out.add(g);
        }
        return out;
    }

    private int firstLineNumber(String lines) {
        if (lines == null || lines.isEmpty()) return 0;
        // 支持 "30-31"、"163-164, 167-168" 取第一个数字
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(lines);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private Path resolveSamplesRoot() {
        String userDir = System.getProperty("user.dir", ".");
        String[] candidates = {
                Paths.get(userDir, "samples").toString(),
                Paths.get(userDir, "..", "samples").toString(),
                Paths.get(userDir, "..", "..", "samples").toString(),
        };
        for (String c : candidates) {
            if (Files.isDirectory(Paths.get(c, "ecommerce-order"))) {
                return Paths.get(c);
            }
        }
        throw new IllegalStateException("未找到 samples 目录（候选: " + String.join(", ", candidates) + "）");
    }
}
