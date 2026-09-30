package com.example.toolbox.storage;

import com.example.toolbox.common.exception.BusinessException;
import com.example.toolbox.common.result.Code;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;

/**
 * 存量迁移编排（M-22 / 契约 §9.9）。
 *
 * <p><b>可替换性</b>：本类只负责"建任务 → 交给执行器 → 回快照"，**不假设执行是同步还是异步**。
 * 本期执行器为 {@link SyncMigrationExecutor}（返回即 COMPLETED）；将来换成异步执行器时，
 * {@code start} 会立即返回 {@code RUNNING}，由 {@code status} 轮询 —— **接口与前端不变**。
 */
@Service
@RequiredArgsConstructor
public class StorageMigrationService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MigrationTaskStore taskStore;
    private final MigrationExecutor executor;

    /** 发起迁移任务（本期同步执行，返回时任务通常已 COMPLETED） */
    public MigrationTaskVO start(Integer limit, String operator) {
        MigrationTask task = taskStore.create(operator);
        executor.execute(task, new MigrationParams(limit));
        return toVo(task);
    }

    /** 查询任务进度 / 结果 */
    public MigrationTaskVO status(String taskId) {
        MigrationTask task = taskStore.get(taskId)
                .orElseThrow(() -> new BusinessException(Code.NOT_FOUND, "迁移任务不存在"));
        return toVo(task);
    }

    private MigrationTaskVO toVo(MigrationTask task) {
        return MigrationTaskVO.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus().name())
                .total(task.getTotal())
                .migrated(task.getMigrated())
                .failed(task.getFailed())
                .skipped(task.getSkipped())
                .failures(task.failuresSnapshot().stream()
                        .map(f -> new MigrationTaskVO.FailureVO(f.versionId(), f.reason()))
                        .toList())
                .startedAt(task.getStartedAt() == null ? null : TIME.format(task.getStartedAt()))
                .finishedAt(task.getFinishedAt() == null ? null : TIME.format(task.getFinishedAt()))
                .build();
    }
}
