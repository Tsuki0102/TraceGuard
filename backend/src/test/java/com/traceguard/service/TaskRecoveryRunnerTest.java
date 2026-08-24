package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.traceguard.entity.AnalysisTask;
import com.traceguard.mapper.AnalysisTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务重启任务恢复器单元测试：pending/running/paused -> interrupted，其余状态不动
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务重启任务恢复器单元测试")
class TaskRecoveryRunnerTest {

    @Mock
    private AnalysisTaskMapper taskMapper;

    @InjectMocks
    private TaskRecoveryRunner runner;

    @SuppressWarnings("unchecked")
    private void mockSelect(AnalysisTask... tasks) {
        when(taskMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(tasks));
    }

    @Test
    @DisplayName("重启时运行中任务被标记为interrupted并写入提示与结束时间")
    void marksUnfinishedAsInterrupted() {
        AnalysisTask running = new AnalysisTask();
        running.setId(1L);
        running.setStatus("running");
        AnalysisTask paused = new AnalysisTask();
        paused.setId(2L);
        paused.setStatus("paused");
        mockSelect(running, paused);

        runner.run(null);

        ArgumentCaptor<AnalysisTask> captor = ArgumentCaptor.forClass(AnalysisTask.class);
        verify(taskMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        List<AnalysisTask> updated = captor.getAllValues();
        assertThat(updated).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo("interrupted");
            assertThat(t.getErrorMessage()).contains("服务重启");
            assertThat(t.getEndTime()).isNotNull();
        });
    }

    @Test
    @DisplayName("无未完成任务时不执行任何更新")
    void noUnfinishedTasksNoUpdate() {
        mockSelect();

        runner.run(null);

        verify(taskMapper, never()).updateById(any(AnalysisTask.class));
    }
}
