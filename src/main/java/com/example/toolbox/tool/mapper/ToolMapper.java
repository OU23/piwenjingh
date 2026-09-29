package com.example.toolbox.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tool.entity.ToolDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 工具 Mapper。
 * 下载计数用原子自增（并发下不丢计数），避免"读-改-写"竞态。
 */
@Mapper
public interface ToolMapper extends BaseMapper<ToolDO> {

    /** 累计下载次数 +1（原子） */
    @Update("UPDATE tb_tool SET download_count = download_count + 1 WHERE id = #{id}")
    int incrementDownloadCount(@Param("id") Long id);
}
