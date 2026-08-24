package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.traceguard.entity.DataChangeLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataChangeLogMapper extends BaseMapper<DataChangeLog> {
}
