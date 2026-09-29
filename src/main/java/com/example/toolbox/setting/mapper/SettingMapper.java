package com.example.toolbox.setting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.setting.entity.SettingDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 全局设置数据访问。
 */
@Mapper
public interface SettingMapper extends BaseMapper<SettingDO> {
}
