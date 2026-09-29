package com.example.toolbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传与分片配置（toolbox.upload.*）。
 * 分片临时目录、单文件上限、分片大小均为可配置项，禁止裸数字散落逻辑（principles.md 原则 9）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.upload")
public class UploadProperties {

    private String chunkDir = System.getProperty("java.io.tmpdir") + "/toolbox-chunks";
    private long maxFileSize = 10L * 1024 * 1024 * 1024;
    private long chunkSize = 5L * 1024 * 1024;
}
