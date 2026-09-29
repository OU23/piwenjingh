package com.example.toolbox.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 极简 JSON 序列化工具，供过滤器/拦截器等脱离 Spring 消息转换器处手写响应体。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }
}
