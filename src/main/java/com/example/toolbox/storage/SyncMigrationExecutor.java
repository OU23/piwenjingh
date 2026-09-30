package com.example.toolbox.storage;

import com.example.toolbox.tool.entity.ToolVersionDO;
import com.example.toolbox.tool.mapper.ToolVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 同步迁移执行器（M-22，本期实现）。
 *
 * <p>逐条把 {@code storage_backend='local'} 的文件搬到 OSS。**失败隔离**：单条失败只记入
 * {@code failures} 并保持本地落点，**不中断整体**、**不回滚**已完成项（S-08）。
 *
 * <p><b>单条顺序（重要，保证"落点指向的文件始终存在"）</b>：
 * <ol>
 *   <li>本地读 → OSS 写（**事务外**，原则 8：禁止事务内文件 IO）；</li>
 *   <li>改落点为 oss（单条 UPDATE 天然原子 + 条件更新防并发）；</li>
 *   <li>**成功后再删本地副本** —— 若先删本地再改字段，崩溃在中间会出现"落点=local 但本地已无"→ 下载 404；</li>
 * </ol>
 * 崩溃在第 3 步之前最多残留一个本地文件（不影响下载），无数据丢失风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncMigrationExecutor implements MigrationExecutor {

    private final ToolVersionMapper toolVersionMapper;
    private final LocalStorageService localStorageService;
    private final OssStorageService ossStorageService;

    @Override
    public void execute(MigrationTask task, MigrationParams params) {
        task.markRunning();
        int limit = (params == null || params.limit() == null || params.limit() <= 0)
                ? Integer.MAX_VALUE : params.limit();

        List<ToolVersionDO> candidates = toolVersionMapper.selectLocalForMigration(limit);
        task.setTotal(candidates.size());

        for (ToolVersionDO version : candidates) {
            try {
                if (StorageBackend.of(version.getStorageBackend()) != StorageBackend.LOCAL) {
                    task.incSkipped();  // 并发下已被其他任务迁移
                    continue;
                }
                migrateOne(version);
                task.incMigrated();
            } catch (Exception e) {
                task.incFailed();
                task.addFailure(String.valueOf(version.getId()), e.getMessage());
                log.warn("[MIGRATE] 单条失败 versionId={} err={}", version.getId(), e.getMessage());
            }
        }
        task.markCompleted();
    }

    /** 单个版本迁移（事务外文件 IO + 单条原子 UPDATE） */
    private void migrateOne(ToolVersionDO version) throws Exception {
        String fileKey = version.getFileKey();
        long size = version.getFileSize() == null ? -1L : version.getFileSize();

        // 1) 本地读 → OSS 写（已知长度，供对象存储设置 Content-Length）
        try (InputStream in = localStorageService.open(fileKey)) {
            ossStorageService.save(fileKey, in, size);
        }

        // 2) 先改落点（条件更新，防并发重复）
        toolVersionMapper.markMigrated(version.getId());

        // 3) 再删本地副本（用户 2026-09-30 拍板：迁移成功即删本地，真正释放磁盘）
        localStorageService.delete(fileKey);
    }
}
