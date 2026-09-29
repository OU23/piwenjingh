package com.example.toolbox.tag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.tag.entity.TagDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 标签数据访问。
 */
@Mapper
public interface TagMapper extends BaseMapper<TagDO> {
}
