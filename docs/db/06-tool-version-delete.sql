-- ============================================================
-- tool-box · 增量迁移 06：版本级软删除 + 名称唯一性改「按上传人」
--   （M-24 工具版本管理 / 契约 v1.21 / 数据模型 v1.4 §3.5 · §3.6）
--
-- 【为什么需要这个脚本】
-- `schema.sql` 用 `CREATE TABLE IF NOT EXISTS` —— **表已存在时它不做任何改动**，
-- 因此**已初始化的老库不会自动改结构**。本次有两处结构变更，老库不执行本脚本会出问题：
--   ① `tb_tool.uk_tool_name` 仍是 `UNIQUE (name)`（全局唯一）
--      ⇒ 不同上传人的同名工具**无法创建**（接口报 `Duplicate entry` 或 1005）。
--   ② `tb_tool_version` 没有 `deleted` 列
--      ⇒ 版本列表 / 回收站「版本」页签相关查询报 `Unknown column 'deleted'`（500）。
--
-- 【为什么改】
--   ① 名称唯一性：由「全局唯一」改为「**同一上传人内唯一**」（GitHub 模式，同名可并存）。
--   ② 版本级软删除：历史版本可删除且**可恢复** ⇒ 版本需要 `deleted` 标记；
--      同时**必须移除** `UNIQUE (tool_id, version)` —— 否则"删掉 v1.0 再重新上传 v1.0"
--      会撞上那条**已软删**的行。唯一性改由**应用层**按 `deleted = 0` 校验。
--
-- 【语义】
--   tb_tool_version.deleted = 0（默认）→ 正常版本；1 → 已删除（进回收站「版本」页签）。
--   ⚠️ 默认 0 与升级前行为**完全一致**，**无需任何数据回填**。
--
-- 【执行】
--   mysql -u root -p toolbox < 06-tool-version-delete.sql
--
-- 【幂等】可重复执行（全部"先探测、再执行"）。
--   MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS` / `DROP INDEX IF EXISTS`，
--   故统一用 information_schema 探测 + 动态 SQL。
--
-- 【顺序说明】
--   ① 先加新唯一键、再删旧唯一键 —— 避免中间出现"无唯一约束"的窗口；
--      老库原本 `name` 全局唯一 ⇒ `(name, uploader_id)` 必然唯一，加索引不会失败。
--   ② 先加 `deleted` 列、再建新索引。
-- ============================================================

-- ---------- ① tb_tool：新增唯一键 (name, uploader_id) ----------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool'
    AND INDEX_NAME   = 'uk_tool_name_uploader'
);

SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `tb_tool` ADD UNIQUE KEY `uk_tool_name_uploader` (`name`, `uploader_id`)',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- ② tb_tool：移除旧唯一键 uk_tool_name ----------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool'
    AND INDEX_NAME   = 'uk_tool_name'
);

SET @ddl := IF(@idx_exists > 0,
  'ALTER TABLE `tb_tool` DROP INDEX `uk_tool_name`',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- ③ tb_tool_version：新增 deleted 列 ----------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool_version'
    AND COLUMN_NAME  = 'deleted'
);

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `tb_tool_version`
     ADD COLUMN `deleted` tinyint NOT NULL DEFAULT 0
     COMMENT ''版本级软删除：0 正常 / 1 已删除（M-24）'' AFTER `create_time`',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- ④ tb_tool_version：新增索引 (deleted, create_time) ----------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool_version'
    AND INDEX_NAME   = 'idx_tool_version_deleted'
);

SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `tb_tool_version` ADD KEY `idx_tool_version_deleted` (`deleted`, `create_time`)',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- ⑤ tb_tool_version：移除 UNIQUE (tool_id, version) ----------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool_version'
    AND INDEX_NAME   = 'uk_tool_version'
);

SET @ddl := IF(@idx_exists > 0,
  'ALTER TABLE `tb_tool_version` DROP INDEX `uk_tool_version`',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 校验（应只剩 uk_tool_name_uploader，不再有 uk_tool_name）----------
-- SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
-- FROM information_schema.STATISTICS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool'
-- GROUP BY INDEX_NAME, NON_UNIQUE;

-- ---------- 校验（应有 deleted 列与 idx_tool_version_deleted，不应再有 uk_tool_version）----------
-- SELECT COLUMN_NAME, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool_version' AND COLUMN_NAME = 'deleted';

-- SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
-- FROM information_schema.STATISTICS
-- WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool_version'
-- GROUP BY INDEX_NAME, NON_UNIQUE;

-- ---------- 校验（存量数据应全部为未删除）----------
-- SELECT COUNT(*) AS 已删版本行数 FROM tb_tool_version WHERE deleted <> 0;
