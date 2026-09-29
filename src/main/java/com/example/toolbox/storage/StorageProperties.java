package com.example.toolbox.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置（toolbox.storage.root）。
 * 未配置时运行期给出明确错误；B4 起改由全局设置表写入。
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.storage")
public class StorageProperties {

    private String root = "";
}
