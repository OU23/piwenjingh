package com.example.toolbox.storage;

/**
 * 迁移参数（M-22 / 契约 §9.9）。
 *
 * @param limit 单次迁移条数上限；null / &le;0 表示不限
 */
public record MigrationParams(Integer limit) {
}
