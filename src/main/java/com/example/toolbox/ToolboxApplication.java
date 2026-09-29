package com.example.toolbox;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * tool-box 后端启动类。
 * 仅保留应用启动、组件扫描、Mapper 扫描与必要框架开关，不含业务配置（配置见 config 包）。
 * @EnableScheduling：分片超时清理（ChunkCleanupTask）依赖定时任务。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.example.toolbox.**.mapper")
public class ToolboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(ToolboxApplication.class, args);
    }
}
