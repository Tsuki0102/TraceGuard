package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.traceguard.entity.AnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnalysisTaskMapper extends BaseMapper<AnalysisTask> {

    /**
     * W2-06：按天统计分析任务（总数/完成数），用于使用趋势图。
     * projectIds 为空或 null 表示不限制（管理员全量）；否则仅统计可见项目（数据隔离）。
     */
    @Select("<script>SELECT DATE_FORMAT(create_time,'%Y-%m-%d') AS day, COUNT(*) AS total, "
            + "SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END) AS success "
            + "FROM tg_analysis_task WHERE create_time &gt;= #{start} "
            + "<if test='projectIds != null and projectIds.size() &gt; 0'> AND project_id IN "
            + "<foreach collection='projectIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>"
            + "</if> GROUP BY day ORDER BY day</script>")
    List<Map<String, Object>> dailyTaskStats(@Param("start") LocalDateTime start,
                                             @Param("projectIds") List<Long> projectIds);

    /**
     * W2-01：统计时间区间内创建的任务数（周同比趋势）。projectIds 语义同上。
     */
    @Select("<script>SELECT COUNT(*) FROM tg_analysis_task WHERE create_time &gt;= #{start} AND create_time &lt; #{end} "
            + "<if test='projectIds != null and projectIds.size() &gt; 0'> AND project_id IN "
            + "<foreach collection='projectIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>"
            + "</if></script>")
    Long countTasksBetween(@Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end,
                           @Param("projectIds") List<Long> projectIds);
}
