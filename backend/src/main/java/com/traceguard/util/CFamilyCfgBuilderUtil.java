package com.traceguard.util;

import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T11 多语言扩展：C/C++ 函数级 CFG 构建器（tree-sitter AST）。
 * 输出与 {@link CfgBuilderUtil}（Java 版）/ PythonCfgBuilderUtil 完全相同的 JSON schema：
 * {@code {"nodes":[{id,type,label,line}],"edges":[{from,to,label}]}}
 * 节点 type：start/end/if/loop/loop_exit/switch/stmt/catch/merge/break/continue/return/throw/goto；
 * 边 label：true/false/back/loop_exit/catch/seq 及 case/default 分支标签。
 * 与 Python 版差异：C 系无 elif_clause（else-if 直接嵌套 if_statement）、
 * do-while 先体后条件、switch 的 case_statement 自含语句序列、goto 建模为终结节点。
 */
public final class CFamilyCfgBuilderUtil {

    private final List<CfgNode> nodes = new ArrayList<>();
    private final List<CfgEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private int nextId = 0;
    private int currentLoopId = -1;
    private final byte[] sourceBytes;

    private CFamilyCfgBuilderUtil(byte[] sourceBytes) {
        this.sourceBytes = sourceBytes;
    }

    private static class CfgNode {
        final int id;
        final String type;
        final String label;
        final int line;
        int loopOwner = -1;

        CfgNode(int id, String type, String label, int line) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.line = line;
        }
    }

    private static class CfgEdge {
        final int from;
        final int to;
        final String label;

        CfgEdge(int from, int to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    /** 构建 C/C++ 函数 CFG，返回与 Java/Python 版同构的 JSON 字符串 */
    public static String build(TSNode funcNode, byte[] sourceBytes) {
        CFamilyCfgBuilderUtil builder = new CFamilyCfgBuilderUtil(sourceBytes);
        return builder.doBuild(funcNode);
    }

    private String doBuild(TSNode funcNode) {
        int start = addNode("start", "START", 0);
        int end = addNode("end", "END", 0);
        TSNode body = funcNode.getChildByFieldName("body");
        int exit = start;
        if (!body.isNull()) {
            exit = walkSuite(body, start, "seq");
        }
        if (exit >= 0) {
            addEdge(exit, end, "seq");
        }
        return toJson();
    }

    /** 遍历语句块（跳过注释），返回出口节点 ID（-1 表示流程已终结） */
    private int walkSuite(TSNode suite, int entryId, String entryLabel) {
        if (suite.isNull()) {
            return entryId;
        }
        if (!"compound_statement".equals(suite.getType()) && !"translation_unit".equals(suite.getType())) {
            return walkStatement(suite, entryId, entryLabel);
        }
        int current = entryId;
        boolean first = true;
        for (int i = 0; i < suite.getNamedChildCount(); i++) {
            TSNode child = suite.getNamedChild(i);
            String type = child.getType();
            if ("comment".equals(type) || type.startsWith("preproc")) {
                continue; // 注释/预处理指令不产生 CFG 节点
            }
            if (current < 0) {
                break;
            }
            current = walkStatement(child, current, first ? entryLabel : "seq");
            first = false;
        }
        return current;
    }

    private int walkStatement(TSNode stmt, int entryId, String entryLabel) {
        int line = stmt.getStartPoint().getRow() + 1;
        switch (stmt.getType()) {
            case "if_statement":
                return walkIf(stmt, entryId, entryLabel, line);
            case "while_statement":
                return walkWhile(stmt, entryId, entryLabel, line);
            case "for_statement":
                return walkLoop(stmt, entryId, entryLabel, line, "for(...)");
            case "do_statement":
                return walkDoWhile(stmt, entryId, entryLabel, line);
            case "switch_statement":
                return walkSwitch(stmt, entryId, entryLabel, line);
            case "try_statement":
                return walkTry(stmt, entryId, entryLabel, line);
            case "return_statement":
                addNodeEdgeTerminal("return", "return", line, entryId, entryLabel);
                return -1;
            case "throw_statement":
                addNodeEdgeTerminal("throw", "throw", line, entryId, entryLabel);
                return -1;
            case "goto_statement":
                addNodeEdgeTerminal("goto", trunc(normalize(nodeText(stmt)), 20), line, entryId, entryLabel);
                return -1;
            case "break_statement": {
                int n = addNode("break", "break", line);
                nodes.get(n).loopOwner = currentLoopId;
                addEdge(entryId, n, entryLabel);
                return -1;
            }
            case "continue_statement": {
                int n = addNode("continue", "continue", line);
                nodes.get(n).loopOwner = currentLoopId;
                addEdge(entryId, n, entryLabel);
                return -1;
            }
            case "case_statement":
                // 单独出现在块内的 case（极少）：按普通语句处理
                return walkDefault(stmt, entryId, entryLabel, line);
            default:
                return walkDefault(stmt, entryId, entryLabel, line);
        }
    }

    private int walkDefault(TSNode stmt, int entryId, String entryLabel, int line) {
        String label = trunc(normalize(nodeText(stmt)), 30);
        int n = addNode("stmt", label, line);
        addEdge(entryId, n, entryLabel);
        return n;
    }

    private void addNodeEdgeTerminal(String type, String label, int line, int entryId, String entryLabel) {
        int n = addNode(type, label, line);
        addEdge(entryId, n, entryLabel);
    }

    /** if/else-if 建模：条件节点 + true 分支 + false 分支（C 的 else-if 是直接嵌套 if_statement） */
    private int walkIf(TSNode ifNode, int entryId, String entryLabel, int line) {
        TSNode cond = ifNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 25);
        int condNode = addNode("if", "if(" + condText + ")", line);
        addEdge(entryId, condNode, entryLabel);

        int thenExit = walkSuite(ifNode.getChildByFieldName("consequence"), condNode, "true");
        TSNode alternative = ifNode.getChildByFieldName("alternative");
        if (alternative.isNull()) {
            if (thenExit >= 0) {
                int merge = addNode("merge", "merge", line);
                addEdge(thenExit, merge, "seq");
                addEdge(condNode, merge, "false");
                return merge;
            }
            return condNode;
        }
        int elseExit;
        if ("if_statement".equals(alternative.getType())) {
            // else-if 链：作为独立 if 处理，入边保持 false
            elseExit = walkIf(alternative, condNode, "false", alternative.getStartPoint().getRow() + 1);
        } else {
            elseExit = walkSuite(alternative, condNode, "false");
        }
        return mergeExits(thenExit, elseExit);
    }

    private int walkWhile(TSNode loopNode, int entryId, String entryLabel, int line) {
        TSNode cond = loopNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 25);
        int condNode = addNode("loop", "while(" + condText + ")", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, condNode, entryLabel);
        addEdge(condNode, exitNode, "false");
        int bodyExit = walkLoopBody(loopNode.getChildByFieldName("body"), condNode, "true", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "back");
        }
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    private int walkLoop(TSNode loopNode, int entryId, String entryLabel, int line, String label) {
        int condNode = addNode("loop", label, line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, condNode, entryLabel);
        addEdge(condNode, exitNode, "false");
        int bodyExit = walkLoopBody(loopNode.getChildByFieldName("body"), condNode, "true", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "back");
        }
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    /** C do-while：先执行循环体，后判条件 */
    private int walkDoWhile(TSNode loopNode, int entryId, String entryLabel, int line) {
        int bodyEntry = addNode("loop", "do{...}", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, bodyEntry, entryLabel);
        TSNode cond = loopNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 25);
        int condNode = addNode("loop", "while(" + condText + ")", loopNode.getEndPoint().getRow() + 1);
        int bodyExit = walkLoopBody(loopNode.getChildByFieldName("body"), bodyEntry, "seq", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "seq");
        }
        addEdge(condNode, bodyEntry, "back");
        addEdge(condNode, exitNode, "false");
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    /** switch 多路分支：case_statement 自含 value+语句序列 */
    private int walkSwitch(TSNode switchNode, int entryId, String entryLabel, int line) {
        TSNode cond = switchNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 20);
        int selNode = addNode("switch", "switch(" + condText + ")", line);
        addEdge(entryId, selNode, entryLabel);
        List<Integer> exits = new ArrayList<>();
        boolean hasDefault = false;
        TSNode body = switchNode.getChildByFieldName("body");
        if (!body.isNull()) {
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                TSNode child = body.getNamedChild(i);
                if (!"case_statement".equals(child.getType())) {
                    continue;
                }
                TSNode value = child.getChildByFieldName("value");
                String caseLabel = value.isNull() ? (hasDefault ? "case ..." : "default") : trunc(normalize(nodeText(value)), 15);
                if (value.isNull()) {
                    hasDefault = true;
                }
                int caseNode = addNode("stmt", "case " + caseLabel, child.getStartPoint().getRow() + 1);
                addEdge(selNode, caseNode, value.isNull() ? "default" : "case");
                // case_statement 内除 value 外的语句平铺执行
                int caseExit = walkCaseBody(child, value, caseNode);
                if (caseExit >= 0) {
                    exits.add(caseExit);
                }
            }
        }
        if (exits.isEmpty()) {
            return selNode;
        }
        int merge = addNode("merge", "merge", line);
        for (int e : exits) {
            addEdge(e, merge, "seq");
        }
        if (!hasDefault) {
            addEdge(selNode, merge, "default"); // 无匹配 case 直落
        }
        return merge;
    }

    /** case_statement 内部：跳过 value 字段节点与注释，平铺语句 */
    private int walkCaseBody(TSNode caseNode, TSNode value, int entryId) {
        int current = entryId;
        boolean first = true;
        for (int i = 0; i < caseNode.getNamedChildCount(); i++) {
            TSNode child = caseNode.getNamedChild(i);
            String type = child.getType();
            if ("comment".equals(type) || type.startsWith("preproc")) {
                continue;
            }
            if (!value.isNull() && TSNode.eq(child, value)) {
                continue;
            }
            if (current < 0) {
                break;
            }
            current = walkStatement(child, current, first ? "seq" : "seq");
            first = false;
        }
        return current;
    }

    /** C++ try/catch 异常边建模（对齐 Java/Python 版 walkTry） */
    private int walkTry(TSNode tryNode, int entryId, String entryLabel, int line) {
        int tryBodyNode = addNode("stmt", "try{...}", line);
        addEdge(entryId, tryBodyNode, entryLabel);
        int tryExit = walkSuite(tryNode.getChildByFieldName("body"), tryBodyNode, "seq");
        List<Integer> exits = new ArrayList<>();
        if (tryExit >= 0) {
            exits.add(tryExit);
        }
        for (int i = 0; i < tryNode.getNamedChildCount(); i++) {
            TSNode child = tryNode.getNamedChild(i);
            if ("catch_clause".equals(child.getType())) {
                TSNode params = child.getChildByFieldName("parameters");
                String exType = params.isNull() ? "..." : trunc(normalize(nodeText(params)), 20);
                int catchNode = addNode("catch", "catch(" + exType + ")", child.getStartPoint().getRow() + 1);
                addEdge(tryBodyNode, catchNode, "catch");
                int catchExit = walkSuite(child.getChildByFieldName("body"), catchNode, "seq");
                if (catchExit >= 0) {
                    exits.add(catchExit);
                }
            }
        }
        if (exits.isEmpty()) {
            return -1;
        }
        if (exits.size() == 1) {
            return exits.get(0);
        }
        int merge = addNode("merge", "merge", line);
        for (int e : exits) {
            addEdge(e, merge, "seq");
        }
        return merge;
    }

    private int walkLoopBody(TSNode body, int entryId, String edgeLabel, int loopNodeId) {
        int prev = currentLoopId;
        currentLoopId = loopNodeId;
        try {
            return walkSuite(body, entryId, edgeLabel);
        } finally {
            currentLoopId = prev;
        }
    }

    private int mergeExits(int exitA, int exitB) {
        if (exitA >= 0 && exitB >= 0) {
            int merge = addNode("merge", "merge", 0);
            addEdge(exitA, merge, "seq");
            addEdge(exitB, merge, "seq");
            return merge;
        }
        return Math.max(exitA, exitB);
    }

    private void linkBreaks(int loopNode, int exitNode) {
        for (CfgNode n : nodes) {
            if ("break".equals(n.type) && n.loopOwner == loopNode) {
                addEdge(n.id, exitNode, "loop_exit");
            } else if ("continue".equals(n.type) && n.loopOwner == loopNode) {
                addEdge(n.id, loopNode, "back");
            }
        }
    }

    private int addNode(String type, String label, int line) {
        nodes.add(new CfgNode(nextId, type, label, line));
        return nextId++;
    }

    private void addEdge(int from, int to, String label) {
        String key = from + "->" + to + ":" + label;
        if (edgeKeys.add(key)) {
            edges.add(new CfgEdge(from, to, label));
        }
    }

    private String toJson() {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            CfgNode n = nodes.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(n.id)
                    .append(",\"type\":\"").append(n.type)
                    .append("\",\"label\":\"").append(escape(n.label))
                    .append("\",\"line\":").append(n.line).append("}");
        }
        sb.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            CfgEdge e = edges.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"from\":").append(e.from)
                    .append(",\"to\":").append(e.to)
                    .append(",\"label\":\"").append(escape(e.label)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 空白规范化（含 \r\n -> 空格）：裸控制字符会使 JSON 字符串非法 */
    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String trunc(String s, int max) {
        if (s.length() > max) {
            return s.substring(0, max) + "...";
        }
        return s;
    }

    /** 按 UTF-8 字节偏移取节点源码文本（与 tree-sitter getStartByte/getEndByte 口径一致） */
    private String nodeText(TSNode node) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end <= start || end > sourceBytes.length) {
            return "";
        }
        return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
    }
}
