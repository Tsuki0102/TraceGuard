package com.traceguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * W5：LLM 调用用量日志（用量看板数据源）。
 * 由 LlmCallExecutor 在每次真实调用后写入，失败不阻断主流程。
 */
@Data
@TableName("tg_llm_call_log")
public class LlmCallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 调用场景（阶段标识：requirement/alloy/code/consistency/chat/explain） */
    private String scene;
    /** 模型标识 */
    private String model;
    /** 是否成功 1/0 */
    private Integer success;
    /** 耗时（毫秒） */
    private Integer latencyMs;
    /** 失败原因 */
    private String errorMsg;
    private LocalDateTime createTime;
}
