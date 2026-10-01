package com.traceguard.util;

import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T11 多语言扩展：Python 函数级 CFG 构建器（tree-sitter AST）。
 * 输出与 {@link CfgBuilderUtil}（Java 版）完全相同的 JSON schema：
 * {@code {"nodes":[{id,type,label,line}],"edges":[{from,to,label}]}}
 * 节点 type：start/end/if/loop/loop_exit/stmt/catch/merge/break/continue/return/throw；
 * 边 label：true/false/back/loop_exit/catch/seq 及 except 分支标签。
 * 说明：解释型语言无字节码级 CFG，统一走 AST 级（对齐 Java 链路 Soot 缺失时的 AST 降级路径）。
 */
public final class PythonCfgBuilderUtil {

    private final List<CfgNode> nodes = new ArrayList<>();
    private final List<CfgEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private int nextId = 0;
    private int currentLoopId = -1;
    private final byte[] sourceBytes;

    private PythonCfgBuilderUtil(byte[] sourceBytes) {
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

    /** 构建 Python 函数 CFG，返回与 Java 版同构的 JSON 字符串 */
    public static String build(TSNode funcNode, byte[] sourceBytes) {
        PythonCfgBuilderUtil builder = new PythonCfgBuilderUtil(sourceBytes);
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

    /** 遍历 suite/block（跳过注释节点），返回出口节点 ID（-1 表示流程已终结） */
    private int walkSuite(TSNode suite, int entryId, String entryLabel) {
        if (suite.isNull()) {
            return entryId;
        }
        if (!"block".equals(suite.getType()) && !"module".equals(suite.getType())) {
            return walkStatement(suite, entryId, entryLabel);
        }
        int current = entryId;
        boolean first = true;
        for (int i = 0; i < suite.getNamedChildCount(); i++) {
            TSNode child = suite.getNamedChild(i);
            String type = child.getType();
            if ("comment".equals(type) || "pass_statement".equals(type) || "line_continuation".equals(type)) {
                continue; // 注释/pass 为空操作，不产生 CFG 节点
            }
            if (current < 0) {
                break; // 前序语句已终结流程，后续不可达
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
            case "try_statement":
                return walkTry(stmt, entryId, entryLabel, line);
            case "with_statement":
                return walkWith(stmt, entryId, entryLabel, line);
            case "return_statement":
                addNodeEdgeTerminal("return", "return", line, entryId, entryLabel);
                return -1;
            case "raise_statement":
                addNodeEdgeTerminal("throw", "raise", line, entryId, entryLabel);
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
            default:
                // 普通语句（表达式/赋值/断言/导入/嵌套定义等）；与 Java 版一致规范化空白（Windows \r 会使 JSON 非法）
                String label = normalize(nodeText(stmt));
                int n = addNode("stmt", label, line);
                addEdge(entryId, n, entryLabel);
                return n;
        }
    }

    private void addNodeEdgeTerminal(String type, String label, int line, int entryId, String entryLabel) {
        int n = addNode(type, label, line);
        addEdge(entryId, n, entryLabel);
    }

    /** if/elif 统一建模：条件节点 + true 分支 + false 分支（elif 递归、else 直接走） */
    private int walkIf(TSNode ifNode, int entryId, String entryLabel, int line) {
        TSNode cond = ifNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 25);
        int condNode = addNode("if", "if(" + condText + ")", line);
        addEdge(entryId, condNode, entryLabel);

        int thenExit = walkSuite(ifNode.getChildByFieldName("consequence"), condNode, "true");
        TSNode alternative = ifNode.getChildByFieldName("alternative");
        if (alternative.isNull()) {
            // 无 else：false 直接落到后继
            if (thenExit >= 0) {
                int merge = addNode("merge", "merge", line);
                addEdge(thenExit, merge, "seq");
                addEdge(condNode, merge, "false");
                return merge;
            }
            return condNode;
        }
        String altType = alternative.getType();
        if ("elif_clause".equals(altType)) {
            // elif 链：作为独立 if 处理，入边保持 false
            return mergeExits(thenExit, walkElif(alternative, condNode));
        }
        if ("else_clause".equals(altType)) {
            int elseExit = walkSuite(alternative.getChildByFieldName("body"), condNode, "false");
            return mergeExits(thenExit, elseExit);
        }
        return mergeExits(thenExit, condNode);
    }

    /** elif_clause 与 if_statement 字段结构一致（condition/consequence/alternative） */
    private int walkElif(TSNode elifNode, int entryId) {
        int line = elifNode.getStartPoint().getRow() + 1;
        return walkIf(elifNode, entryId, "false", line);
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

    /** try/except/finally 异常边建模（对齐 Java 版 walkTry） */
    private int walkTry(TSNode tryNode, int entryId, String entryLabel, int line) {
        int tryBodyNode = addNode("stmt", "try{...}", line);
        addEdge(entryId, tryBodyNode, entryLabel);
        int tryExit = walkSuite(tryNode.getChildByFieldName("body"), tryBodyNode, "seq");
        List<Integer> exits = new ArrayList<>();
        if (tryExit >= 0) {
            exits.add(tryExit);
        }
        // except_clause 为 try_statement 的命名子节点（非字段），else_clause 同理
        for (int i = 0; i < tryNode.getNamedChildCount(); i++) {
            TSNode child = tryNode.getNamedChild(i);
            if ("except_clause".equals(child.getType())) {
                String exType = exceptLabel(child);
                int catchNode = addNode("catch", "except(" + exType + ")", child.getStartPoint().getRow() + 1);
                addEdge(tryBodyNode, catchNode, "catch");
                int catchExit = walkSuite(child.getChildByFieldName("body"), catchNode, "seq");
                if (catchExit >= 0) {
                    exits.add(catchExit);
                }
            }
        }
        // else_clause（try 正常结束才执行）挂在主出口之后
        for (int i = 0; i < tryNode.getNamedChildCount(); i++) {
            TSNode child = tryNode.getNamedChild(i);
            if ("else_clause".equals(child.getType()) && !exits.isEmpty()) {
                int merged = mergeExitsList(exits);
                exits = merged >= 0 ? new ArrayList<>(List.of(merged)) : new ArrayList<>();
            }
        }
        TSNode finallyClause = null;
        for (int i = 0; i < tryNode.getNamedChildCount(); i++) {
            TSNode child = tryNode.getNamedChild(i);
            if ("finally_clause".equals(child.getType())) {
                finallyClause = child;
            }
        }
        if (finallyClause != null) {
            int finEntry;
            if (exits.isEmpty()) {
                finEntry = tryBodyNode;
            } else if (exits.size() == 1) {
                finEntry = exits.get(0);
            } else {
                int merge = addNode("merge", "merge", line);
                for (int e : exits) {
                    addEdge(e, merge, "seq");
                }
                finEntry = merge;
            }
            return walkSuite(finallyClause.getChildByFieldName("body"), finEntry, "seq");
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

    /** except 子句标签：取异常类型文本（裸 except 记 "*"） */
    private String exceptLabel(TSNode exceptClause) {
        for (int i = 0; i < exceptClause.getNamedChildCount(); i++) {
            TSNode child = exceptClause.getNamedChild(i);
            String t = child.getType();
            if ("block".equals(t) || "comment".equals(t)) {
                continue;
            }
            if ("identifier".equals(t) || "attribute".equals(t) || "dotted_name".equals(t)
                    || "subscript".equals(t) || "tuple".equals(t) || "string".equals(t)) {
                return trunc(normalize(nodeText(child)), 20);
            }
        }
        return "*";
    }

    /** with 语句：头语句节点 + body 块（资源管理语义节点，供 invariant/约束分析复用） */
    private int walkWith(TSNode withNode, int entryId, String entryLabel, int line) {
        int header = addNode("stmt", trunc(normalize(nodeText(withNode).split("\n")[0]), 30), line);
        addEdge(entryId, header, entryLabel);
        return walkSuite(withNode.getChildByFieldName("body"), header, "seq");
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

    private int mergeExitsList(List<Integer> exits) {
        if (exits.size() == 1) {
            return exits.get(0);
        }
        int merge = addNode("merge", "merge", 0);
        for (int e : exits) {
            addEdge(e, merge, "seq");
        }
        return merge;
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

    /** 空白规范化（含 \r\n -> 空格）：裸控制字符会使 JSON 字符串非法（对齐 Java 版 stmt.toString 行为） */
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
