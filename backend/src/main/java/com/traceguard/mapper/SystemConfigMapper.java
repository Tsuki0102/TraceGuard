package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.traceguard.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置表 Mapper（AUD-07）
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}
