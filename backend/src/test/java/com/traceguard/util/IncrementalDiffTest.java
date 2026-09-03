package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-5：增量解析文件 diff 计划单测（无需 DB/文件系统，验证"改一文件只重解析该文件"的判定逻辑）。
 */
@DisplayName("IncrementalDiff 增量计划")
class IncrementalDiffTest {

    private static Map<String, String> map(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (String) kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("内容全部未变更 -> 计划为空（整体复用）")
    void unchangedAllEmpty() {
        Map<String, String> oldH = map("A.java", "h1", "B.java", "h2");
        Map<String, String> curH = map("A.java", "h1", "B.java", "h2");
        IncrementalDiff.Plan plan = IncrementalDiff.plan(oldH, curH);
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.changed).isEmpty();
        assertThat(plan.removed).isEmpty();
        assertThat(plan.unchangedCount(curH.size())).isEqualTo(2);
    }

    @Test
    @DisplayName("单个文件内容变更 -> 仅该文件进入 changed")
    void singleFileEditedOnlyItselfChanged() {
        Map<String, String> oldH = map("A.java", "h1", "B.java", "h2", "C.java", "h3");
        Map<String, String> curH = map("A.java", "h1", "B.java", "h2b", "C.java", "h3");
        IncrementalDiff.Plan plan = IncrementalDiff.plan(oldH, curH);
        assertThat(plan.isEmpty()).isFalse();
        assertThat(plan.changed).containsExactly("B.java");
        assertThat(plan.removed).isEmpty();
        // 两个未变更文件可复用
        assertThat(plan.unchangedCount(curH.size())).isEqualTo(2);
    }

    @Test
    @DisplayName("新增文件 -> changed；本地删除文件 -> removed")
    void addedAndRemovedFiles() {
        Map<String, String> oldH = map("A.java", "h1", "B.java", "h2");
        Map<String, String> curH = map("A.java", "h1", "C.java", "h9");
        IncrementalDiff.Plan plan = IncrementalDiff.plan(oldH, curH);
        assertThat(plan.changed).containsExactly("C.java"); // 新增即"变更"（需重解析）
        assertThat(plan.removed).containsExactly("B.java"); // 磁盘已删除
        assertThat(plan.unchangedCount(curH.size())).isEqualTo(1);
    }

    @Test
    @DisplayName("null 输入按空处理，不抛异常")
    void nullInputsTolerated() {
        IncrementalDiff.Plan plan = IncrementalDiff.plan(null, map("A.java", "h1"));
        assertThat(plan.changed).containsExactly("A.java");
        IncrementalDiff.Plan plan2 = IncrementalDiff.plan(map("A.java", "h1"), null);
        assertThat(plan2.removed).containsExactly("A.java");
    }
}
