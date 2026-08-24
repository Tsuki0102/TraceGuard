package com.traceguard.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrphanFileCleanupTask 单元测试：登记路径解析（逗号分隔多路径）
 */
@DisplayName("孤儿文件清理任务单元测试")
class OrphanFileCleanupTaskTest {

    @Test
    @DisplayName("逗号分隔的多路径被逐个登记，防止登记文件被误判为孤儿")
    void commaSeparatedPathsAllRegistered() throws Exception {
        OrphanFileCleanupTask task = new OrphanFileCleanupTask();
        Set<String> referenced = new HashSet<>();
        File file1 = File.createTempFile("traceguard-ref1", ".txt");
        File file2 = File.createTempFile("traceguard-ref2", ".txt");
        try {
            Method addReferenced = OrphanFileCleanupTask.class
                    .getDeclaredMethod("addReferenced", Set.class, String.class);
            addReferenced.setAccessible(true);
            // 批量导入后数据库登记形态：逗号分隔（含空格）
            addReferenced.invoke(task, referenced,
                    file1.getAbsolutePath() + ", " + file2.getAbsolutePath());

            assertThat(referenced).contains(file1.getCanonicalPath(), file2.getCanonicalPath());
        } finally {
            file1.delete();
            file2.delete();
        }
    }
}
