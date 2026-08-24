package com.traceguard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceguard.entity.Project;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    /** GAP-012：回收站分页查询（deleted=1，含归属过滤） */
    @Select("<script>SELECT * FROM tg_project WHERE deleted = 1" +
            "<if test='userId != null'> AND create_user_id = #{userId}</if>" +
            " ORDER BY update_time DESC</script>")
    IPage<Project> selectDeletedPage(Page<?> page, @Param("userId") Long userId);

    /** GAP-012：查询回收站中的单个项目（含逻辑删除行） */
    @Select("SELECT * FROM tg_project WHERE id = #{id} AND deleted = 1")
    Project selectDeletedById(@Param("id") Long id);

    /** GAP-012：恢复回收站项目（deleted=0） */
    @Update("UPDATE tg_project SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreDeleted(@Param("id") Long id);

    /** GAP-012：彻底删除回收站项目（物理删除） */
    @Delete("DELETE FROM tg_project WHERE id = #{id} AND deleted = 1")
    int purgeDeleted(@Param("id") Long id);

    /** GAP-012：定时清理回收站超期项目（物理删除，仅 MySQL） */
    @Delete("DELETE FROM tg_project WHERE deleted = 1 AND update_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int purgeExpiredDeleted(@Param("days") int days);
}
