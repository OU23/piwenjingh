package com.example.toolbox.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 迁移任务视图（契约 §9.9 / v1.19，M-22）。
 *
 * <p>放在 {@code storage} 包（而非 {@code tool.vo}）：迁移属存储域能力，避免存储层反向依赖业务包。
 */
@Data
@Builder
public class MigrationTaskVO {

    private String taskId;

    /** PENDING / RUNNING / COMPLETED / FAILED */
    private String status;

    /** 待迁移总数（未知为 null） */
    private Integer total;

    private int migrated;
    private int failed;
    private int skipped;

    /** 失败明细（最多 100 条） */
    private List<FailureVO> failures;

    /** yyyy-MM-dd HH:mm:ss */
    private String startedAt;

    /** yyyy-MM-dd HH:mm:ss；未结束为 null */
    private String finishedAt;

    @Data
    @AllArgsConstructor
    public static class FailureVO {
        private String versionId;
        private String reason;
    }
}
