package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.entity.AnalysisTask;
import com.traceguard.mapper.AnalysisTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 服务重启任务恢复器（断点续跑跨重启）：
 * 服务重启后，上次仍在执行中（pending/running/paused）的分析任务已不可能继续运行，
 * 统一标记为 interrupted 并提示用户。用户点击「重新分析」即可续跑——
 * 已完成阶段（需求解析/规约生成/代码解析）因产物已入库将自动跳过。
 */
@Slf4j
@Component
public class TaskRecoveryRunner implements ApplicationRunner {

    @Autowired
    private AnalysisTaskMapper taskMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<AnalysisTask> unfinished = taskMapper.selectList(new LambdaQueryWrapper<AnalysisTask>()
                .in(AnalysisTask::getStatus, Arrays.asList("pending", "running", "paused")));
        for (AnalysisTask task : unfinished) {
            task.setStatus("interrupted");
            task.setEndTime(LocalDateTime.now());
            task.setErrorMessage("服务重启导致任务中断，可点击重新分析续跑（已完成阶段将自动跳过）");
            taskMapper.updateById(task);
        }
        if (!unfinished.isEmpty()) {
            log.warn("服务重启恢复：检测到{}个未完成任务，已标记为 interrupted 等待续跑", unfinished.size());
        }
    }
}
