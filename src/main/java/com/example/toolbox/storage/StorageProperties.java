package com.example.toolbox.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储配置（{@code toolbox.storage.*}）。
 *
 * <ul>
 *   <li>{@code root} —— 本地磁盘存储根路径；未配置时运行期给出明确错误；B4 起改由全局设置表写入。</li>
 *   <li>{@code type} —— <b>全局开关（M-22）</b>：决定<b>新上传文件</b>写到哪里（{@code local} / {@code oss}）。
 *       注意：它<b>只决定"写目标"</b>；读取 / 删除按<b>每个文件的实际落点</b>路由（见 {@link RoutedStorageService}）。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "toolbox.storage")
public class StorageProperties {

    /** 本地磁盘存储根路径（兜底值；实际取值优先级见 StorageRootProvider） */
    private String root = "";

    /** 新文件写入后端：local / oss（默认 local —— 未配置即本地，与升级前行为一致） */
    private String type = "local";

    /** 解析后的写入落点（未知值按 local） */
    public StorageBackend writeBackend() {
        return StorageBackend.of(type);
    }
}
