package com.traceguard.util;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AST级控制流图（CFG）构建器
 * 精确建模：条件分支(true/false)、循环回边、break/continue跳转、switch多路分支、try/catch异常边
 * 说明：Soot字节码级CFG需Java 11+，此处基于JavaParser AST实现同等语义的语句级CFG
 */
@Component
public class CfgBuilderUtil {

    /** CFG节点 */
    public static class CfgNode {
        final int id;
        final String type;   // start/end/if/loop/loop_exit/switch/stmt/catch/merge/break/continue/return/throw
        final String label;
        final int line;
        int loopOwner = -1;  // 所属循环节点ID（break/continue跳转归属）

        CfgNode(int id, String type, String label, int line) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.line = line;
        }
    }

    /** CFG边 */
    public static class CfgEdge {
        final int from;
        final int to;
        final String label;  // true/false/back/loop_exit/break/continue/catch/seq

        CfgEdge(int from, int to, String label) {
            this.from = from;
            this.to = to;
            this.label = label;
        }
    }

    private final List<CfgNode> nodes = new ArrayList<>();
    private final List<CfgEdge> edges = new ArrayList<>();
    private final Set<String> edgeKeys = new HashSet<>();
    private int nextId = 0;
    private int currentLoopId = -1;

    /** 构建方法CFG，返回JSON字符串 {nodes:[{id,type,label,line}],edges:[{from,to,label}]} */
    public static String build(MethodDeclaration method) {
        CfgBuilderUtil builder = new CfgBuilderUtil();
        return builder.doBuild(method);
    }

    private String doBuild(MethodDeclaration method) {
        int start = addNode("start", "START", 0);
        int end = addNode("end", "END", 0);
        int entry = start;
        if (method.getBody().isPresent()) {
            int exit = walkBlock(method.getBody().get(), start);
            if (exit >= 0) {
                addEdge(exit, end, "seq");
            }
        } else {
            addEdge(entry, end, "seq");
        }
        return toJson();
    }

    /**
     * 遍历语句块，返回该块的出口节点ID（-1表示流程已终结于return/break等）
     */
    private int walkBlock(BlockStmt block, int entryId) {
        return walkBlock(block, entryId, "seq");
    }

    private int walkBlock(BlockStmt block, int entryId, String entryLabel) {
        if (block.getStatements().isEmpty()) return entryId; // 空块直通，不产生节点
        boolean first = true;
        int current = entryId;
        for (Statement stmt : block.getStatements()) {
            if (current < 0) break; // 前序语句已终结流程，后续不可达
            current = first ? walkStatement(stmt, current, entryLabel)
                            : walkStatement(stmt, current);
            first = false;
        }
        return current;
    }

    /**
     * 处理单条语句，返回后继入口节点ID（-1表示流程终结）
     */
    private int walkStatement(Statement stmt, int entryId) {
        return walkStatement(stmt, entryId, "seq");
    }

    private int walkStatement(Statement stmt, int entryId, String entryLabel) {
        int line = stmt.getBegin().map(p -> p.line).orElse(0);

        if (stmt instanceof IfStmt) {
            return walkIf((IfStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof WhileStmt) {
            return walkWhile((WhileStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof ForStmt) {
            return walkFor((ForStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof DoStmt) {
            return walkDoWhile((DoStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof ForEachStmt) {
            return walkForeach((ForEachStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof SwitchStmt) {
            return walkSwitch((SwitchStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof TryStmt) {
            return walkTry((TryStmt) stmt, entryId, entryLabel, line);
        }
        if (stmt instanceof BlockStmt) {
            return walkBlock((BlockStmt) stmt, entryId, entryLabel);
        }
        if (stmt instanceof ReturnStmt) {
            int n = addNode("return", "return", line);
            addEdge(entryId, n, entryLabel);
            return -1;
        }
        if (stmt instanceof BreakStmt) {
            int n = addNode("break", "break", line);
            node(n).loopOwner = currentLoopId;
            addEdge(entryId, n, entryLabel);
            return -1; // 由所属循环的linkBreaks连接跳转边
        }
        if (stmt instanceof ContinueStmt) {
            int n = addNode("continue", "continue", line);
            node(n).loopOwner = currentLoopId;
            addEdge(entryId, n, entryLabel);
            return -1;
        }
        if (stmt instanceof ThrowStmt) {
            int n = addNode("throw", "throw", line);
            addEdge(entryId, n, entryLabel);
            return -1;
        }
        // 普通语句（表达式/声明等）
        String label = stmt.toString().replaceAll("\\s+", " ").trim();
        if (label.length() > 30) label = label.substring(0, 30) + "...";
        int n = addNode("stmt", label, line);
        addEdge(entryId, n, entryLabel);
        return n;
    }

    /** if语句：条件节点 + true/false双分支 */
    private int walkIf(IfStmt ifStmt, int entryId, String entryLabel, int line) {
        String cond = ifStmt.getCondition().toString();
        if (cond.length() > 25) cond = cond.substring(0, 25) + "...";
        int condNode = addNode("if", "if(" + cond + ")", line);
        addEdge(entryId, condNode, entryLabel);

        int thenExit = walkBlockOrStmt(ifStmt.getThenStmt(), condNode, "true");
        // 合并点：then有出口则then出口后继为merge，否则直接取else入口
        int elseEntry = condNode;
        if (ifStmt.getElseStmt().isPresent()) {
            Statement elseStmt = ifStmt.getElseStmt().get();
            if (elseStmt instanceof IfStmt) {
                // else-if链：递归作为独立if处理，入边标签保持false（两参重载会丢失为seq）
                return mergeExits(thenExit, walkStatement(elseStmt, elseEntry, "false"));
            }
            int elseExit = walkBlockOrStmt(elseStmt, condNode, "false");
            return mergeExits(thenExit, elseExit);
        }
        // 无else：false直接落到后继
        if (thenExit >= 0) {
            int merge = addNode("merge", "merge", line);
            addEdge(thenExit, merge, "seq");
            addEdge(condNode, merge, "false");
            return merge;
        }
        return condNode; // then分支终结（return），false边为唯一后继
    }

    /** while循环：条件节点、回边、loop_exit虚拟出口（break跳出口、循环正常出口） */
    private int walkWhile(WhileStmt stmt, int entryId, String entryLabel, int line) {
        String cond = stmt.getCondition().toString();
        if (cond.length() > 25) cond = cond.substring(0, 25) + "...";
        int condNode = addNode("loop", "while(" + cond + ")", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, condNode, entryLabel);
        addEdge(condNode, exitNode, "false");
        int bodyExit = walkLoopBody(stmt.getBody(), condNode, "true", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "back");
        }
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    private int walkFor(ForStmt stmt, int entryId, String entryLabel, int line) {
        int condNode = addNode("loop", "for(...)", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, condNode, entryLabel);
        addEdge(condNode, exitNode, "false");
        int bodyExit = walkLoopBody(stmt.getBody(), condNode, "true", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "back");
        }
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    private int walkDoWhile(DoStmt stmt, int entryId, String entryLabel, int line) {
        int bodyEntry = addNode("loop", "do{...}", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, bodyEntry, entryLabel);
        String cond = stmt.getCondition().toString();
        if (cond.length() > 25) cond = cond.substring(0, 25) + "...";
        int condNode = addNode("loop", "while(" + cond + ")", line);
        int bodyExit = walkLoopBody(stmt.getBody(), bodyEntry, "seq", condNode);
        if (bodyExit >= 0) addEdge(bodyExit, condNode, "seq");
        addEdge(condNode, bodyEntry, "back");
        addEdge(condNode, exitNode, "false");
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    private int walkForeach(ForEachStmt stmt, int entryId, String entryLabel, int line) {
        int condNode = addNode("loop", "for(..:..)", line);
        int exitNode = addNode("loop_exit", "loop_exit", line);
        addEdge(entryId, condNode, entryLabel);
        addEdge(condNode, exitNode, "false");
        int bodyExit = walkLoopBody(stmt.getBody(), condNode, "true", condNode);
        if (bodyExit >= 0) {
            addEdge(bodyExit, condNode, "back");
        }
        linkBreaks(condNode, exitNode);
        return exitNode;
    }

    /** 遍历循环体，设置currentLoopId标记break/continue归属 */
    private int walkLoopBody(Statement body, int entryId, String edgeLabel, int loopNodeId) {
        int prev = currentLoopId;
        currentLoopId = loopNodeId;
        try {
            return walkBlockOrStmt(body, entryId, edgeLabel);
        } finally {
            currentLoopId = prev;
        }
    }

    /** switch多路分支 */
    private int walkSwitch(SwitchStmt stmt, int entryId, String entryLabel, int line) {
        String sel = stmt.getSelector().toString();
        if (sel.length() > 20) sel = sel.substring(0, 20) + "...";
        int selNode = addNode("switch", "switch(" + sel + ")", line);
        addEdge(entryId, selNode, entryLabel);
        List<Integer> exits = new ArrayList<>();
        for (SwitchEntry entry : stmt.getEntries()) {
            String caseLabel = entry.getLabels().isEmpty() ? "default"
                    : entry.getLabels().stream().map(Object::toString).collect(Collectors.joining(","));
            int caseNode = addNode("stmt", "case " + caseLabel, line);
            addEdge(selNode, caseNode, caseLabel);
            int caseExit = walkStatements(entry.getStatements(), caseNode);
            if (caseExit >= 0) exits.add(caseExit);
        }
        if (exits.isEmpty()) return selNode;
        int merge = addNode("merge", "merge", line);
        for (int e : exits) addEdge(e, merge, "seq");
        addEdge(selNode, merge, "default"); // 无匹配case直落
        return merge;
    }

    /** try/catch异常边建模：finally前用merge节点承接try/catch全部出口 */
    private int walkTry(TryStmt stmt, int entryId, String entryLabel, int line) {
        int tryNode = addNode("stmt", "try{...}", line);
        addEdge(entryId, tryNode, entryLabel);
        int tryExit = walkBlock(stmt.getTryBlock(), tryNode);
        List<Integer> exits = new ArrayList<>();
        if (tryExit >= 0) exits.add(tryExit);
        for (CatchClause cc : stmt.getCatchClauses()) {
            int catchNode = addNode("catch", "catch(" + cc.getParameter().getTypeAsString() + ")", line);
            addEdge(tryNode, catchNode, "catch");
            int catchExit = walkBlock(cc.getBody(), catchNode);
            if (catchExit >= 0) exits.add(catchExit);
        }
        if (stmt.getFinallyBlock().isPresent()) {
            int finEntry;
            if (exits.isEmpty()) {
                // try/catch全部路径终结（return/throw）：finally仅挂在try节点上保证图连通
                finEntry = tryNode;
            } else if (exits.size() == 1) {
                finEntry = exits.get(0);
            } else {
                // 多出口必须先汇合到merge再进finally，避免只连第一个出口
                int merge = addNode("merge", "merge", line);
                for (int e : exits) addEdge(e, merge, "seq");
                finEntry = merge;
            }
            return walkBlock(stmt.getFinallyBlock().get(), finEntry);
        }
        if (exits.isEmpty()) return -1;
        if (exits.size() == 1) return exits.get(0);
        int merge = addNode("merge", "merge", line);
        for (int e : exits) addEdge(e, merge, "seq");
        return merge;
    }

    /** 块或单条语句统一入口：不插入伪节点，分支标签直接落到首语句 */
    private int walkBlockOrStmt(Statement stmt, int entryId, String edgeLabel) {
        if (stmt instanceof BlockStmt) {
            return walkBlock((BlockStmt) stmt, entryId, edgeLabel);
        }
        return walkStatement(stmt, entryId, edgeLabel);
    }

    private int walkStatements(NodeList<Statement> stmts, int entryId) {
        int current = entryId;
        for (Statement s : stmts) {
            if (current < 0) break;
            current = walkStatement(s, current);
        }
        return current;
    }

    /** 合并两个出口：都有效建merge节点，只有一个返回那个，都终结返回-1 */
    private int mergeExits(int exitA, int exitB) {
        if (exitA >= 0 && exitB >= 0) {
            int merge = addNode("merge", "merge", 0);
            addEdge(exitA, merge, "seq");
            addEdge(exitB, merge, "seq");
            return merge;
        }
        return Math.max(exitA, exitB);
    }

    /** 将属于该循环的break连到loop_exit出口节点、continue连回循环条件节点 */
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

    private CfgNode node(int id) {
        return nodes.get(id);
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
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(n.id)
              .append(",\"type\":\"").append(n.type)
              .append("\",\"label\":\"").append(escape(n.label))
              .append("\",\"line\":").append(n.line).append("}");
        }
        sb.append("],\"edges\":[");
        for (int i = 0; i < edges.size(); i++) {
            CfgEdge e = edges.get(i);
            if (i > 0) sb.append(",");
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
}
