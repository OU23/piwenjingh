package com.example.toolbox.storage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 迁移任务状态（M-22 / 契约 §9.9）。
 *
 * <p><b>可替换性</b>：任务状态机与计数独立于"怎么执行" —— 同步与异步执行器共用本类，
 * 故将来把同步换成异步**不触碰本类、不触碰接口与前端**。
 *
 * <p>状态存**进程内存**（选型禁 Redis）；重启丢失任务记录属可接受 —— 迁移**可重入**，重新发起即可。
 */
public class MigrationTask {

    /** 失败明细保留上限（超出只计数不记录，避免长任务把内存撑爆） */
    private static final int MAX_FAILURES = 100;

    private final String taskId;
    private final String operator;
    private final LocalDateTime startedAt = LocalDateTime.now();

    private volatile MigrationStatus status = MigrationStatus.PENDING;
    private volatile Integer total;
    private volatile LocalDateTime finishedAt;

    private final AtomicInteger migrated = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

    public MigrationTask(String taskId, String operator) {
        this.taskId = taskId;
        this.operator = operator;
    }

    public void markRunning() {
        this.status = MigrationStatus.RUNNING;
    }

    public void markCompleted() {
        this.status = MigrationStatus.COMPLETED;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = MigrationStatus.FAILED;
        this.finishedAt = LocalDateTime.now();
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public void incMigrated() {
        migrated.incrementAndGet();
    }

    public void incFailed() {
        failed.incrementAndGet();
    }

    public void incSkipped() {
        skipped.incrementAndGet();
    }

    public void addFailure(String versionId, String reason) {
        if (failures.size() < MAX_FAILURES) {
            failures.add(new Failure(versionId, reason));
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public String getOperator() {
        return operator;
    }

    public MigrationStatus getStatus() {
        return status;
    }

    public Integer getTotal() {
        return total;
    }

    public int getMigrated() {
        return migrated.get();
    }

    public int getFailed() {
        return failed.get();
    }

    public int getSkipped() {
        return skipped.get();
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    /** 失败明细只读快照 */
    public List<Failure> failuresSnapshot() {
        synchronized (failures) {
            return new ArrayList<>(failures);
        }
    }

    /** 单条失败：版本 ID + 原因 */
    public record Failure(String versionId, String reason) {
    }
}
