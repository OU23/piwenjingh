package com.example.toolbox.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tool.entity.ToolVersionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工具版本 Mapper。下载计数同工具表，走原子自增。
 */
@Mapper
public interface ToolVersionMapper extends BaseMapper<ToolVersionDO> {

    /** 该版本下载次数 +1（原子） */
    @Update("UPDATE tb_tool_version SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownloadCount(@Param("id") Long id);

    /**
     * M-22 迁移：取"落本地、且所属工具未软删"的版本（按 id 升序，可限量）。
     * 软删工具的版本**不迁移**（S-08 口径）。
     */
    @Select("SELECT v.* FROM tb_tool_version v JOIN tb_tool t ON t.id = v.tool_id "
            + "WHERE v.storage_backend = 'local' AND t.deleted = 0 "
            + "ORDER BY v.id LIMIT #{limit}")
    List<ToolVersionDO> selectLocalForMigration(@Param("limit") int limit);

    /**
     * M-22 迁移：把该版本落点改为 oss。
     * 单条 UPDATE 天然原子；条件 {@code storage_backend='local'} 防并发重复迁移。
     */
    @Update("UPDATE tb_tool_version SET storage_backend = 'oss' "
            + "WHERE id = #{id} AND storage_backend = 'local'")
    int markMigrated(@Param("id") Long id);
}
