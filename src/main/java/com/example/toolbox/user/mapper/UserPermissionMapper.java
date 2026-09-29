package com.example.toolbox.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.user.entity.UserPermissionDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户权限点数据访问。
 */
@Mapper
public interface UserPermissionMapper extends BaseMapper<UserPermissionDO> {
}
