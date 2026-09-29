package com.example.toolbox.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tool.entity.ToolGrantDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工具定向授权 Mapper（M-17）。
 *
 * <p>基础 CRUD 来自 {@link BaseMapper}（按 tool_id / user_id 的删、按唯一键查等）；
 * 列表查询、存在性计数与批量插入为本表专用，故在此补充。
 */
@Mapper
public interface ToolGrantMapper extends BaseMapper<ToolGrantDO> {

    /** 某用户被授权的工具 ID 列表（按 id 升序；含软删工具，由调用方按 tb_tool 记录是否存在判定有效性） */
    @Select("<script>SELECT tool_id FROM tb_tool_grant WHERE user_id = #{userId} ORDER BY tool_id ASC</script>")
    List<Long> selectToolIdsByUser(@Param("userId") Long userId);

    /** 某工具已授权的用户 ID 列表（按 id 升序） */
    @Select("<script>SELECT user_id FROM tb_tool_grant WHERE tool_id = #{toolId} ORDER BY user_id ASC</script>")
    List<Long> selectUserIdsByTool(@Param("toolId") Long toolId);

    /** 某「工具 + 用户」授权关系是否存在（定向授权可见范围判定用） */
    @Select("<script>SELECT COUNT(1) FROM tb_tool_grant WHERE tool_id = #{toolId} AND user_id = #{userId}</script>")
    int countByToolAndUser(@Param("toolId") Long toolId, @Param("userId") Long userId);

    /** 单事务批量插入（全量覆盖语义：先删后插） */
    @Insert("<script>INSERT INTO tb_tool_grant (tool_id, user_id, grant_by, create_time) VALUES " +
            "<foreach collection='list' item='g' separator=','>" +
            "(#{g.toolId}, #{g.userId}, #{g.grantBy}, #{g.createTime})" +
            "</foreach></script>")
    int batchInsert(@Param("list") List<ToolGrantDO> list);
}
