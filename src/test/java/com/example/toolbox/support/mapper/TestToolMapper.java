package com.example.toolbox.support.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.toolbox.support.TestToolEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 测试专用 Mapper：唯一作用是让 MyBatis-Plus 在容器启动时初始化
 * {@link TestToolEntity} 的 TableInfo（lambda 缓存），使 ScopeChecker 的
 * `applyVisibility` 可在测试中解析方法引用。不执行任何真实 SQL。
 *
 * <p>包名含 `.mapper` 以命中启动类的 `@MapperScan("com.example.toolbox.**.mapper")`。
 */
@Mapper
public interface TestToolMapper extends BaseMapper<TestToolEntity> {
}
