package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.traceguard.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * W2-06：按天统计登录次数（operation='登录'），用于使用趋势图。
     * username 为空表示全量（管理员）；否则仅统计该用户登录（数据隔离）。
     */
    @Select("<script>SELECT DATE_FORMAT(create_time,'%Y-%m-%d') AS day, COUNT(*) AS cnt "
            + "FROM tg_audit_log WHERE operation = '登录' AND create_time &gt;= #{start} "
            + "<if test='username != null and username != \"\"'> AND username = #{username}</if> "
            + "GROUP BY day ORDER BY day</script>")
    List<Map<String, Object>> dailyLoginStats(@Param("start") LocalDateTime start,
                                              @Param("username") String username);
}
