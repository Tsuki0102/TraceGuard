package com.traceguard.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * FUN-04b：类级判定证据扫描器。
 *
 * 在原始源码文本层提取每个类的「字段/常量定义」清单（方法体之外的首层声明行），
 * 供缺陷模式检测做跨方法的量化常量核对（如需求要求 60 秒幂等窗口而常量定义为 30）。
 *
 * 与 AST 解析解耦：正则逐行扫描，任何可读的 .java 文本均可，不依赖编译产物；
 * 扫描失败返回空证据（相关信号静默失效），不影响主链路。
 */
public final class ClassEvidenceScanner {

    /** 常量/字段声明行：4 空格缩进的 private/public/protected 声明，允许带初始化 */
    private static final Pattern FIELD_LINE = Pattern.compile(
            "^\\s{4}(private|public|protected)\\s+[\\w.<>\\[\\], ]+?\\s+\\w+(\\s*=\\s*[^;]+)?;");
    private static final Pattern METHOD_LINE = Pattern.compile(
            "^\\s{4}(public|protected|private)\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\(");

    private ClassEvidenceScanner() {
    }

    /**
     * 扫描工程目录下全部 .java 文件。
     *
     * @return 简单类名 -> 字段/常量声明行列表（含 "private static final int X = 30;" 形式）
     */
    public static Map<String, List<String>> scanConstants(String codeDir) {
        Map<String, List<String>> out = new HashMap<>();
        if (codeDir == null || codeDir.isEmpty()) {
            return out;
        }
        Path root = Paths.get(codeDir);
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> collectFile(p, out));
        } catch (IOException e) {
            // 扫描失败降级为无证据：调用方按空 map 处理
        }
        return out;
    }

    /** 类名同时收集方法签名；constants=true 收集字段行，false 收集签名行 */
    private static void collectFile(Path file, Map<String, List<String>> out) {
        try {
            String cls = file.getFileName().toString().replaceFirst("\\.java$", "");
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> fields = out.computeIfAbsent(cls, k -> new ArrayList<>());
            String simpleCls = cls;
            for (String raw : lines) {
                String s = raw.trim();
                if (s.isEmpty() || s.startsWith("@") || s.startsWith("//")
                        || s.startsWith("*") || s.startsWith("/*")) {
                    continue;
                }
                if (FIELD_LINE.matcher(raw).matches()) {
                    if ("Logger".equals(loggerWord(s)) || s.contains("Logger ")) {
                        continue; // 日志器声明对量化核对是噪声
                    }
                    if (fields.size() < 15 && !fields.contains(s)) {
                        fields.add(s);
                    }
                    continue;
                }
                java.util.regex.Matcher m = METHOD_LINE.matcher(raw);
                if (m.find() && !isControlKeyword(m.group(2)) && !m.group(2).equals(simpleCls)) {
                    int brace = s.indexOf('{');
                    String sig = brace > 0 ? s.substring(0, brace).trim() : s;
                    if (sig.endsWith(")") && !fields.contains(sig) && fields.size() < 40) {
                        fields.add(sig); // 方法签名复用同一列表存储（检测器仅关心含 "=" 的常量与名称引用）
                    }
                }
            }
        } catch (IOException ignored) {
            // 单文件读取失败忽略
        }
    }

    private static boolean isControlKeyword(String name) {
        switch (name) {
            case "if": case "for": case "while": case "switch": case "catch":
            case "return": case "new": case "try": case "else": case "do":
                return true;
            default:
                return false;
        }
    }

    private static String loggerWord(String s) {
        int i = s.indexOf("Logger");
        return i >= 0 ? "Logger" : "";
    }

    /** 从证据行中解析常量定义（形如 name=value 的 static final 行），返回 名称->数值 */
    public static Map<String, Integer> parseNumericConstants(List<String> evidenceLines) {
        Map<String, Integer> out = new HashMap<>();
        if (evidenceLines == null) {
            return out;
        }
        for (String line : evidenceLines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("static") || !lower.contains("final")) {
                continue;
            }
            java.util.regex.Matcher nm = Pattern.compile("(\\w+)\\s*=\\s*(-?\\d+)").matcher(line);
            if (nm.find()) {
                out.put(nm.group(1), Integer.parseInt(nm.group(2)));
            }
        }
        return out;
    }
}
