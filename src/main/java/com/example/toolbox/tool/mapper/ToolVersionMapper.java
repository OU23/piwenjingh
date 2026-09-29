package com.example.toolbox.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tool.entity.ToolVersionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 工具版本 Mapper。下载计数同工具表，走原子自增。
 */
@Mapper
public interface ToolVersionMapper extends BaseMapper<ToolVersionDO> {

    /** 该版本下载次数 +1（原子） */
    @Update("UPDATE tb_tool_version SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownloadCount(@Param("id") Long id);
}
