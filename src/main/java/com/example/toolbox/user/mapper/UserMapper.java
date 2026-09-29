package com.example.toolbox.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.user.entity.UserDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户数据访问。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDO> {
}
