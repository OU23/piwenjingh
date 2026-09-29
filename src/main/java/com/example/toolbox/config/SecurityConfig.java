package com.example.toolbox.config;

import com.example.toolbox.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 安全相关 Bean 装配。
 * 认证过滤器以最高优先级注册，确保在进入业务前完成令牌校验。
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration() {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
