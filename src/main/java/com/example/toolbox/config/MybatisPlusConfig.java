package com.example.toolbox.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 * 注册分页拦截器：使 selectPage 正确执行 count 查询并回填 Page.total，同时追加物理分页 LIMIT。
 * 依据 backend.md 分页规则；对方后端曾因缺此配置导致 total 恒为 0（联调问题 P-01），此处必须存在。
 */
@Configuration
public class MybatisPlusConfig {

    /** 单页条数上限，防止一次性拉取全表（与业务层 pageSize 兜底一致） */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 页码越界时回到第 1 页，而不是抛异常
        pagination.setOverflow(true);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
