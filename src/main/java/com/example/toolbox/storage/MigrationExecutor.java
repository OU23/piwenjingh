package com.example.toolbox.storage;

/**
 * 迁移执行器（M-22）—— <b>可替换性的核心</b>。
 *
 * <p>本期实现：{@link SyncMigrationExecutor}（内联跑完，接口一次性返回 {@code COMPLETED}）。
 * <br>将来异步：新增 {@code AsyncMigrationExecutor}（线程池 / {@code @Async}），**只换这一个 Bean**
 * —— 编排（{@link StorageMigrationService}）、接口契约、前端结构**都不动**。
 */
public interface MigrationExecutor {

    /**
     * 执行迁移：逐条把 {@code storage_backend='local'} 的文件搬到 OSS。
     * 失败隔离（单条失败不影响其他），全程更新 {@code task} 状态与计数。
     */
    void execute(MigrationTask task, MigrationParams params);
}
