package com.example.toolbox.setting.service;

import com.example.toolbox.storage.StorageProperties;
import com.example.toolbox.storage.StorageRootProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 存储根路径取值：**全局设置表优先，配置项兜底**（契约 §十 约束「storageRoot 只在**新文件落盘**时生效」）。
 *
 * <p>取"设置表优先"是必须的：管理员在设置页改路径后，**新上传必须落到新目录**（B4 验收 A3 / B1），
 * 而配置项是部署期的值、运行期改不了。配置项保留为**兜底**：空库、尚未初始化、
 * 或未走设置流程的部署（如 CI）仍能工作，不必先写库。
 *
 * <p><b>刻意不做缓存</b>：缓存会让"保存成功即生效"出现时间窗（改完立刻上传仍走旧路径），
 * 与契约「保存成功即生效」冲突。每次解析读一次设置表，代价可接受（写文件本身远重于一次点查）。
 *
 * <p>路径统一**正斜杠**（契约 §十 v1.5）：读时也做一次容错归一化，
 * 防止历史数据或直接写库的脚本留下 `D:\dir` 形式。
 */
@Component
@RequiredArgsConstructor
public class SettingStorageRootProvider implements StorageRootProvider {

    private final SettingService settingService;
    private final StorageProperties storageProperties;

    @Override
    public String currentRoot() {
        String fromDb = settingService.getStorageRoot();
        if (fromDb != null && !fromDb.isBlank()) {
            return fromDb;
        }
        String fromConfig = storageProperties.getRoot();
        return fromConfig == null ? null : fromConfig;
    }
}
