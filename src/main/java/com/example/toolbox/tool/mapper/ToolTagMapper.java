package com.example.toolbox.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tool.entity.ToolTagDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具标签关联 Mapper。
 */
@Mapper
public interface ToolTagMapper extends BaseMapper<ToolTagDO> {
}
