package com.example.toolbox.ws;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * WebSocket 配置（toolbox.ws.*）。
 * 段路径 / 心跳间隔 / 无响应阈值均可配，便于联调期调整（方案 §5.5）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.ws")
public class WsProperties {

    /** 端点路径（不带 /api 前缀，避免与统一响应体 / 拦截器体系耦合） */
    private String path = "/ws";

    /** 心跳间隔（服务端 PING） */
    private Duration heartbeatInterval = Duration.ofSeconds(30);

    /** 无响应关闭阈值（连续 2 次心跳无 PONG 即关闭） */
    private Duration heartbeatTimeout = Duration.ofSeconds(60);
}
