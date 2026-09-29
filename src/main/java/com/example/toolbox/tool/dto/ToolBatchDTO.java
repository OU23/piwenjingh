package com.example.toolbox.tool.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量操作入参（契约 §9.6 `ToolBatchDTO`）。
 *
 * <p>`ids` **必填且非空**；为空 / 非数组 → `1003`（非数组由全局异常处理映射，
 * 见 `GlobalExceptionHandler.handleUnreadableBody`）。
 *
 * <p>元素为字符串（对外 ID 统一字符串），由服务层经 `IdUtils` 解析 —— 单个 ID 格式非法
 * 会让整个请求 1003（不静默丢弃，否则用户以为删掉了）。
 */
@Data
public class ToolBatchDTO {

    /** 工具 ID 列表；契约建议单次 ≤ 100 */
    private List<String> ids;
}
