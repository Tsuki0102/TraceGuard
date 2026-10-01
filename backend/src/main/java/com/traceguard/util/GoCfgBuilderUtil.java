package com.traceguard.util;

import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * T11 多语言扩展：Go 函数级 CFG 构建器（tree-sitter AST）。
 * 输出与 Java/Python/C 系完全相同的 JSON schema：
 * {@code {"nodes":[{id,type,label,line}],"edges":[{from,to,label}]}}
 * 节点 type：start/end/if/loop/loop_exit/switch/stmt/merge/break/continue/return；
 * Go 无 while/do/try（错误处理为值返回），for 为唯一循环关键字（三种形式统一建模），
 * goroutine（go）与 defer 按普通语句建模。
 */
public final class GoCfgBuilderUtil {

    private final List<CfgNode> nodes = new ArrayList<>();
    private final List<CfgEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private int nextId = 0;
    private int currentLoopId = -1;
    private final byte[] sourceBytes;

    private GoCfgBuilderUtil(byte[] sourceBytes) {
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

    /** 构建 Go 函数 CFG，返回与 Java/Python/C 系同构的 JSON 字符串 */
    public static String build(TSNode funcNode, byte[] sourceBytes) {
        GoCfgBuilderUtil builder = new GoCfgBuilderUtil(sourceBytes);
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

    /** 遍历块（跳过注释），返回出口节点 ID（-1 表示流程已终结） */
    private int walkSuite(TSNode suite, int entryId, String entryLabel) {
        if (suite.isNull()) {
            return entryId;
        }
        if (!"block".equals(suite.getType())) {
            return walkStatement(suite, entryId, entryLabel);
        }
        int current = entryId;
        boolean first = true;
        for (int i = 0; i < suite.getNamedChildCount(); i++) {
            TSNode child = suite.getNamedChild(i);
            if ("comment".equals(child.getType())) {
                continue;
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
            case "for_statement":
                return walkFor(stmt, entryId, entryLabel, line);
            case "switch_statement":
                return walkSwitch(stmt, entryId, entryLabel, line);
            case "return_statement":
                addNodeEdgeTerminal("return", "return", line, entryId, entryLabel);
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

    /** if/else-if 建模：Go 无 elseif 关键字，else 分支直接嵌套 if_statement */
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
            elseExit = walkIf(alternative, condNode, "false", alternative.getStartPoint().getRow() + 1);
        } else {
            elseExit = walkSuite(alternative, condNode, "false");
        }
        return mergeExits(thenExit, elseExit);
    }

    /** Go for 三种形式（for{} / for cond{} / for init;cond;post{}）统一为条件节点 + 回边 */
    private int walkFor(TSNode loopNode, int entryId, String entryLabel, int line) {
        TSNode cond = loopNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 25);
        int condNode = addNode("loop", "for(" + condText + ")", line);
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

    /** switch 多路分支：expression_case / default_case 自含语句序列 */
    private int walkSwitch(TSNode switchNode, int entryId, String entryLabel, int line) {
        TSNode cond = switchNode.getChildByFieldName("condition");
        String condText = cond.isNull() ? "..." : trunc(normalize(nodeText(cond)), 20);
        int selNode = addNode("switch", "switch(" + condText + ")", line);
        addEdge(entryId, selNode, entryLabel);
        List<Integer> exits = new ArrayList<>();
        boolean hasDefault = false;
        for (int i = 0; i < switchNode.getNamedChildCount(); i++) {
            TSNode child = switchNode.getNamedChild(i);
            String type = child.getType();
            if (!"expression_case".equals(type) && !"default_case".equals(type)) {
                continue;
            }
            TSNode value = child.getChildByFieldName("value");
            boolean isDefault = "default_case".equals(type) || value.isNull();
            String caseLabel = isDefault ? "default" : trunc(normalize(nodeText(value)), 15);
            if (isDefault) {
                hasDefault = true;
            }
            int caseNode = addNode("stmt", "case " + caseLabel, child.getStartPoint().getRow() + 1);
            addEdge(selNode, caseNode, isDefault ? "default" : "case");
            int caseExit = walkCaseBody(child, value, caseNode);
            if (caseExit >= 0) {
                exits.add(caseExit);
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
            addEdge(selNode, merge, "default");
        }
        return merge;
    }

    /** case 子节点内部：跳过 value 与注释，平铺语句 */
    private int walkCaseBody(TSNode caseNode, TSNode value, int entryId) {
        int current = entryId;
        for (int i = 0; i < caseNode.getNamedChildCount(); i++) {
            TSNode child = caseNode.getNamedChild(i);
            String type = child.getType();
            if ("comment".equals(type)) {
                continue;
            }
            if (!value.isNull() && TSNode.eq(child, value)) {
                continue;
            }
            if (current < 0) {
                break;
            }
            current = walkStatement(child, current, "seq");
        }
        return current;
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

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private String trunc(String s, int max) {
        if (s.length() > max) {
            return s.substring(0, max) + "...";
        }
        return s;
    }

    private String nodeText(TSNode node) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start < 0 || end <= start || end > sourceBytes.length) {
            return "";
        }
        return new String(sourceBytes, start, end - start, StandardCharsets.UTF_8);
    }
}
