package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.DataChangeLog;
import com.traceguard.mapper.DataChangeLogMapper;
import com.traceguard.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 数据变更日志服务（2.8 整改，FR-PLAT-004）：记录关键业务实体字段级变更前后 diff。
 */
@Service
public class DataChangeLogService {

    private static final int MAX_VALUE_LEN = 500;

    @Autowired
    private DataChangeLogMapper mapper;

    /**
     * 对比新旧实体的字段值，逐差异字段写入变更日志。
     * fields 为需要跟踪的字段名→取值函数结果（已转为字符串）；oldMap/newMap 分别为旧/新值映射。
     */
    public void recordFieldChanges(String entityType, String entityId, Long projectId,
                                    Map<String, String> oldMap, Map<String, String> newMap,
                                    List<String> fields) {
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getUsername();
        List<DataChangeLog> logs = new ArrayList<>();
        for (String field : fields) {
            String oldVal = oldMap.get(field);
            String newVal = newMap.get(field);
            if (Objects.equals(oldVal, newVal)) continue;
            DataChangeLog log = new DataChangeLog();
            log.setProjectId(projectId);
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setOperation("update");
            log.setFieldName(field);
            log.setOldValue(truncate(oldVal));
            log.setNewValue(truncate(newVal));
            log.setOperatorId(operatorId);
            log.setOperatorName(operatorName);
            log.setChangeTime(LocalDateTime.now());
            logs.add(log);
        }
        if (!logs.isEmpty()) {
            logs.forEach(mapper::insert);
        }
    }

    /** 记录创建动作（整体快照） */
    public void recordCreate(String entityType, String entityId, Long projectId, Map<String, String> newMap) {
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getUsername();
        DataChangeLog log = new DataChangeLog();
        log.setProjectId(projectId);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setOperation("create");
        log.setFieldName("*");
        log.setOldValue(null);
        log.setNewValue(truncate(String.valueOf(newMap)));
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setChangeTime(LocalDateTime.now());
        mapper.insert(log);
    }

    /** 记录删除动作 */
    public void recordDelete(String entityType, String entityId, Long projectId) {
        Long operatorId = UserContext.getUserId();
        String operatorName = UserContext.getUsername();
        DataChangeLog log = new DataChangeLog();
        log.setProjectId(projectId);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setOperation("delete");
        log.setFieldName("*");
        log.setOldValue(null);
        log.setNewValue(null);
        log.setOperatorId(operatorId);
        log.setOperatorName(operatorName);
        log.setChangeTime(LocalDateTime.now());
        mapper.insert(log);
    }

    /** 分页查询某实体的变更历史 */
    public Page<DataChangeLog> pageByEntity(String entityType, String entityId, int pageNum, int pageSize) {
        LambdaQueryWrapper<DataChangeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataChangeLog::getEntityType, entityType).eq(DataChangeLog::getEntityId, entityId);
        wrapper.orderByDesc(DataChangeLog::getChangeTime);
        return mapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /** 分页查询某项目的全部变更历史 */
    public Page<DataChangeLog> pageByProject(Long projectId, int pageNum, int pageSize) {
        LambdaQueryWrapper<DataChangeLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataChangeLog::getProjectId, projectId);
        wrapper.orderByDesc(DataChangeLog::getChangeTime);
        return mapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_VALUE_LEN ? s.substring(0, MAX_VALUE_LEN) : s;
    }
}
