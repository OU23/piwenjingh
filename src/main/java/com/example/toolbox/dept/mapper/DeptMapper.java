package com.example.toolbox.dept.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.dept.entity.DeptDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门数据访问（数据访问层，只做数据访问）。
 */
@Mapper
public interface DeptMapper extends BaseMapper<DeptDO> {
}
