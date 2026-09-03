package com.traceguard.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2-5：增量解析文件 diff 计划（纯函数，可独立单测）。
 *
 * 输入两侧"相对工程路径 -> 内容 sha256"：
 * <ul>
 *   <li>old：上次解析落库的 filePath→contentHash（取自 code_unit 行）；</li>
 *   <li>cur：本次扫描到的 .java 文件全文哈希（{@link JavaCodeParserUtil#scanContentHashes}）。</li>
 * </ul>
 * 产出：变更/新增文件（需重解析）、已移除文件（需清理存量行）、未变更文件（整体复用）。
 */
public final class IncrementalDiff {

    private IncrementalDiff() {}

    /** diff 结果计划 */
    public static final class Plan {
        /** 变更/新增：old 缺失或哈希不一致，需重解析 */
        public final List<String> changed = new ArrayList<>();
        /** 已从磁盘移除：需删除其存量 code_unit 行 */
        public final List<String> removed = new ArrayList<>();

        public boolean isEmpty() {
            return changed.isEmpty() && removed.isEmpty();
        }

        public int unchangedCount(int totalFiles) {
            return Math.max(0, totalFiles - changed.size());
        }
    }

    /**
     * 计算增量计划。
     *
     * @param oldHashByFile 上次解析的文件哈希（filePath -> contentHash）
     * @param curHashByFile 本次扫描的文件哈希（filePath -> contentHash）
     */
    public static Plan plan(Map<String, String> oldHashByFile, Map<String, String> curHashByFile) {
        Plan plan = new Plan();
        if (oldHashByFile == null) {
            oldHashByFile = new LinkedHashMap<>();
        }
        if (curHashByFile == null) {
            curHashByFile = new LinkedHashMap<>();
        }
        for (Map.Entry<String, String> e : curHashByFile.entrySet()) {
            String old = oldHashByFile.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) {
                plan.changed.add(e.getKey());
            }
        }
        for (String fp : oldHashByFile.keySet()) {
            if (!curHashByFile.containsKey(fp)) {
                plan.removed.add(fp);
            }
        }
        return plan;
    }
}
