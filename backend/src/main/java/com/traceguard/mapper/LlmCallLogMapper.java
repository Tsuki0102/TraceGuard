package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.traceguard.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;

/** W5：LLM 调用用量日志 Mapper */
@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}
