package com.traceguard.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentParserUtil {

    public String parseDocument(String filePath) throws Exception {
        String ext = getFileExtension(filePath).toLowerCase();
        switch (ext) {
            case "docx":
                return parseDocx(filePath);
            case "pdf":
                return parsePdf(filePath);
            case "md":
            case "markdown":
                return parseMarkdown(filePath);
            case "txt":
                return parseTxt(filePath);
            default:
                throw new IllegalArgumentException("不支持的文件格式: " + ext);
        }
    }

    /**
     * GAP-032：需求拆分（支持有编号和无编号兜底策略）
     * 1. 优先使用编号规则切分（FR/REQ-数字、数字 + 标点、中文数字 + 标点）
     * 2. 若无编号，则使用空行分隔 + 句号分句 + 关键词切分的兜底策略
     */
    /**
     * FR-REQ-001 规则2 内容清洗层（2.1 整改项）：
     * 分句/切分前剔除无业务含义的格式内容——页眉/页脚重复行、页码、目录条目、
     * 表格分隔线、HTML 标签残留、Markdown 标记残留，并将连续空行归一化。
     * 逐行清洗：行内清理（HTML/Markdown 残留）→ 整行噪声剔除（页码/表格线/目录/分隔线）→ 页眉重复行剔除。
     */
    public String cleanContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 第一步：统计去空白后的行频次，用于剔除重复出现的页眉/页脚短文本
        Map<String, Integer> lineFreq = new HashMap<>();
        String[] rawLines = content.split("\\r?\\n", -1);
        for (String raw : rawLines) {
            String key = raw.trim();
            if (!key.isEmpty()) {
                lineFreq.put(key, lineFreq.getOrDefault(key, 0) + 1);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : rawLines) {
            String line = cleanLine(raw);
            if (line == null || line.trim().isEmpty()) {
                continue; // 纯噪声/空行剔除
            }
            String trimmed = line.trim();
            if (isNoiseLine(trimmed)) {
                continue;
            }
            // HTML 块级结构残留：原行以标签开头，清理后为无正文特征的短文本 -> 整行剔除
            if (raw.trim().startsWith("<") && trimmed.length() <= 40 && !looksLikeContent(trimmed)) {
                continue;
            }
            // 页眉/页脚重复短文本：独立成行、出现 >= 3 次、长度 <= 24 且不含正文特征词
            if (lineFreq.getOrDefault(trimmed, 0) >= 3
                    && trimmed.length() <= 24 && !looksLikeContent(trimmed)) {
                continue;
            }
            sb.append(trimmed).append("\n");
        }
        // 空行归一化：允许段落间最多一个空行（保留原段落结构）
        String cleaned = sb.toString().replaceAll("\\n{2,}", "\n\n");
        return cleaned.endsWith("\n") ? cleaned.substring(0, cleaned.length() - 1) : cleaned;
    }

    /**
     * 行内清理：剔除 HTML 标签与 Markdown 标记残留（链接/图片保留可见文本）。
     */
    private String cleanLine(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw;
        // HTML 标签（含属性）
        line = line.replaceAll("<[^>]+>", " ");
        // Markdown 图片 ![alt](url) -> alt
        line = line.replaceAll("!\\[([^]]*)\\]\\([^)]*\\)", "$1");
        // Markdown 链接 [text](url) -> text
        line = line.replaceAll("\\[([^]]*)\\]\\([^)]*\\)", "$1");
        // Markdown 标题/引用/列表标记（保留内容文本）
        line = line.replaceAll("^\\s{0,3}(#{1,6}|>+)\\s*", "");
        // Markdown 加粗/斜体/删除线/行内代码/脚注标记
        line = line.replaceAll("[*_`~]{1,3}", "");
        line = line.replaceAll("\\^\\[[^]]*\\]", "");
        // 标签/标记剔除产生的连续空格压缩为单个（如 <b> 被替换后）
        line = line.replaceAll("\\s{2,}", " ");
        return line.trim();
    }

    /**
     * 整行噪声判定：页码、表格分隔线、纯分隔线、目录条目（点线+页码）。
     */
    private boolean isNoiseLine(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return true;
        }
        // 纯分隔线：---- / ==== / **** / ____ 等
        if (t.matches("^[-=*_~·\\s]{3,}$")) {
            return true;
        }
        // Markdown 表格分隔行：| :---: | --- | 等
        String compact = t.replaceAll("[|\\s]", "");
        if (compact.matches(":?-{3,}:?") && t.matches("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")) {
            return true;
        }
        // 纯表格线（单元格均为连字符/冒号/空格组成）
        if (t.replaceAll("[-|:：\\s]", "").isEmpty()
                && t.replaceAll("[^-]", "").length() >= 3) {
            return true;
        }
        // 页码：第 1 页 / 第 1 页 共 2 页 / Page 1 of 2 / - 1 - / 1/2
        if (t.matches("^第\\s*\\d+\\s*页(\\s*共\\s*\\d+\\s*页)?$")) {
            return true;
        }
        if (t.matches("(?i)^[-—–]?\\s*page\\s*\\d+(\\s*(of|/)\\s*\\d+)?\\s*[-—–]?$")) {
            return true;
        }
        // 页码形式：- 2 - / — 3 — / -- 4 --（至少一侧破折号 + 数字）
        if (t.matches("^[-—–]+\\s*\\d+\\s*[-—–]*$") || t.matches("^[-—–]*\\s*\\d+\\s*[-—–]+$")) {
            return true;
        }
        if (t.matches("^\\d+\\s*/\\s*\\d+$")) {
            return true;
        }
        // 目录条目：标题 + 点线/省略号 + 页码 结尾（如 "1.1 功能需求..... 3"、"一、概述 … 5"）
        if (t.matches(".*(\\.{3,}|…+)\\s*\\d+\\s*$")) {
            return true;
        }
        return false;
    }

    /**
     * 判定短文本是否像正文内容（避免把短需求/章节标题误当页眉剔除）：
     * 以编号/标题词开头、或含行为动词/句读、或为"约束：xxx"式结构行，视为正文。
     * 注意：不把"需求/功能/系统"等业务名词当作正文特征——这些词常出现在页眉（文档名）中。
     */
    private boolean looksLikeContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.matches("^([0-9]+[.、)）]|[一二三四五六七八九十]+[.、)）]|第[一二三四五六七八九十0-9]+[条章节]|(FR|REQ)-\\d+).*")) {
            return true;
        }
        // 行为动词/句读/权限主体出现即视为正文（页眉/页脚通常为纯名称、日期或页码）
        return text.matches(".*(支持|应当|必须|需要|提供|实现|管理员|[。；;！!？?])+.*");
    }

    public List<String> splitRequirements(String content) {
        List<String> requirements = new ArrayList<>();
        // FR-REQ-001 规则2：分句前先做内容清洗（页眉/页脚/页码/目录/表格线/HTML/Markdown 残留剔除）
        content = cleanContent(content);
        String[] lines = content.split("\\r?\\n");
        StringBuilder currentReq = new StringBuilder();
        
        boolean hasNumbered = false; // 标记是否存在编号需求
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                if (currentReq.length() > 0) {
                    requirements.add(currentReq.toString().trim());
                    currentReq = new StringBuilder();
                }
                continue;
            }
            // 检查是否为编号需求起点
            if (isRequirementStart(line)) {
                hasNumbered = true;
                if (currentReq.length() > 0) {
                    requirements.add(currentReq.toString().trim());
                    currentReq = new StringBuilder();
                }
            }
            currentReq.append(line).append("\n");
        }
        if (currentReq.length() > 0) {
            requirements.add(currentReq.toString().trim());
        }
        
        // GAP-032：若无编号需求，使用兜底策略重新拆分
        if (!hasNumbered && requirements.size() <= 1 && content.length() > 50) {
            return splitUnnumberedRequirements(content);
        }
        
        return requirements;
    }

    /**
     * GAP-032：无编号需求拆分兜底策略
     * 1. 空行分隔（段落级切分）
     * 2. 句号/分号分句（句子级切分）
     * 3. 关键词切分（"应当"、"必须"、"支持"等行为词作为分割点）
     */
    private List<String> splitUnnumberedRequirements(String content) {
        List<String> requirements = new ArrayList<>();
        
        // 步骤 1：按空行分割为段落
        String[] paragraphs = content.split("\\n\\s*\\n");
        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) continue;
            
            // 步骤 2：按句号/分号分割为句子
            String[] sentences = p.split("[。；;！!？?]\\s*");
            StringBuilder currentReq = new StringBuilder();
            
            for (String sentence : sentences) {
                String s = sentence.trim();
                if (s.isEmpty()) continue;
                
                // 步骤 3：关键词切分（行为动词作为新需求起点）
                if (currentReq.length() > 0 && isBehaviorKeywordStart(s)) {
                    requirements.add(currentReq.toString().trim());
                    currentReq = new StringBuilder();
                }
                currentReq.append(s).append("。");
            }
            
            if (currentReq.length() > 0) {
                String reqText = currentReq.toString().trim();
                // 过滤过短文本（少于 10 字符视为无效需求）
                if (reqText.length() >= 10) {
                    requirements.add(reqText);
                }
            }
        }
        
        // 若兜底策略仍未拆分出有效需求，则返回原文作为单条需求
        if (requirements.isEmpty() && content.trim().length() >= 10) {
            requirements.add(content.trim());
        }
        
        return requirements;
    }

    /**
     * 判断句子是否以行为关键词开头（用于无编号需求切分）
     */
    private boolean isBehaviorKeywordStart(String sentence) {
        String[] keywords = {
            "系统应当", "系统必须", "系统需要", "系统支持", "系统提供",
            "应当", "必须", "需要", "支持", "提供", "实现", "完成",
            "用户能够", "用户可以", "用户可以", "管理员可以",
            "支持", "允许", "禁止", "不得", "只能", "仅"
        };
        for (String keyword : keywords) {
            if (sentence.startsWith(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRequirementStart(String line) {
        return line.matches("^[0-9]+[.、)）]\\s*.+") ||
               line.matches("^[一二三四五六七八九十]+[.、)）]\\s*.+") ||
               line.matches("^第[一二三四五六七八九十0-9]+[条章节]\\s*.+") ||
               line.matches("^(FR|REQ)-\\d+.*") ||
               // 标题词后可跟正文（如"功能需求："），原交替写法仅首分支锚定行首
               line.matches("^(功能需求|非功能需求|约束|规则).*");
    }

    /**
     * FR-REQ-001 规则2：docx 表格表头判定。
     * 表头特征：首行所有单元格均为短文本（<=12 字符）、不含行为词、不含编号，
     * 且表格中存在至少一行含较长内容（>12 字符）的数据行时，判定首行为表头并跳过。
     */
    private boolean isHeaderRow(List<XWPFTableRow> rows) {
        if (rows.size() < 2) {
            return false;
        }
        XWPFTableRow header = rows.get(0);
        List<String> cells = new ArrayList<>();
        for (XWPFTableCell cell : header.getTableCells()) {
            String text = cell.getText();
            if (text != null && !text.trim().isEmpty()) {
                cells.add(text.trim());
            }
        }
        if (cells.isEmpty()) {
            return false;
        }
        boolean allShortAndPlain = true;
        for (String c : cells) {
            if (c.length() > 12
                    || c.matches("^([0-9]+[.、)）]|(FR|REQ)-\\d+).*")
                    || c.matches(".*(支持|应当|必须|需要|提供|实现|系统|用户|管理员).*")) {
                allShortAndPlain = false;
                break;
            }
        }
        if (!allShortAndPlain) {
            return false;
        }
        // 存在比表头更长单元格的数据行才认定为真实数据表（避免纯短列举表误跳过首行）
        int headerMaxLen = 0;
        for (String c : cells) {
            headerMaxLen = Math.max(headerMaxLen, c.length());
        }
        for (int i = 1; i < rows.size(); i++) {
            for (XWPFTableCell cell : rows.get(i).getTableCells()) {
                String text = cell.getText();
                if (text != null && text.trim().length() > headerMaxLen) {
                    return true;
                }
            }
        }
        return false;
    }

    private String parseDocx(String filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(fis)) {
            // 按文档体元素顺序遍历：getParagraphs不含表格，需单独处理（SRS等文档需求常以表格呈现）
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    String text = ((XWPFParagraph) element).getText();
                    if (text != null && !text.trim().isEmpty()) {
                        sb.append(text).append("\n");
                    }
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    List<XWPFTableRow> rows = table.getRows();
                    // FR-REQ-001 规则2：跳过表头行（首行全为短文本且不含行为词、后续存在较长数据行时判定为表头）
                    for (int rIdx = 0; rIdx < rows.size(); rIdx++) {
                        XWPFTableRow row = rows.get(rIdx);
                        if (rIdx == 0 && isHeaderRow(rows)) {
                            continue;
                        }
                        StringBuilder rowText = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            String cellText = cell.getText();
                            if (cellText != null && !cellText.trim().isEmpty()) {
                                if (rowText.length() > 0) rowText.append(" | ");
                                rowText.append(cellText.trim());
                            }
                        }
                        if (rowText.length() > 0) {
                            sb.append(rowText).append("\n");
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private String parsePdf(String filePath) throws Exception {
        try (PDDocument document = PDDocument.load(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private String parseMarkdown(String filePath) throws Exception {
        String content = parseTxt(filePath);
        Parser parser = Parser.builder().build();
        Node document = parser.parse(content);
        TextContentRenderer renderer = TextContentRenderer.builder().build();
        return renderer.render(document);
    }

    private String parseTxt(String filePath) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private String getFileExtension(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot == -1) {
            return "";
        }
        return filePath.substring(lastDot + 1);
    }
}
