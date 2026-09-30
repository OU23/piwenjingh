package com.example.toolbox.storage;

/**
 * 迁移任务状态（契约 §9.9 / v1.19，M-22）。
 *
 * <p>本期为**同步执行**（任务很快走到 {@link #COMPLETED}）；将来改异步时，
 * 会经历 {@link #PENDING} → {@link #RUNNING} → {@link #COMPLETED}，由前端轮询 —— **接口不变**。
 */
public enum MigrationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
