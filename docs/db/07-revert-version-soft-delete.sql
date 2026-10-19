-- 07 回退「版本软删除」：历史版本改回物理删除（2026-10-19 需求调整）
--
-- 背景：
--   06 曾给 tb_tool_version 加 `deleted` 列、并移除 UNIQUE(tool_id, version)，用于"版本进回收站"。
--   需求调整为：**历史版本删除 = 物理删除、不可恢复**；只有"删整个工具"才进回收站。
--   因此本脚本把版本表恢复为原结构（无 deleted、有唯一键），并清掉 06 遗留的软删行。
--
-- ⚠️ 执行顺序：01 → 02 → 03 → 04 → 05 → 06 → 07（全部幂等，可重复执行）
-- ⚠️ 本脚本会 **DELETE** 掉 `deleted = 1` 的版本记录（新口径下它们本就不该存在）。
--    这些版本对应的物理文件不会由 SQL 删除：
--      - 本地落点：文件残留在 storageRoot 下，需人工清理（或交由孤儿清理）；
--      - OSS 落点：本系统为"逻辑删除"，对象本就保留，无需处理。
-- ⚠️ 若线上已出现同一 (tool_id, version) 的重复行（软删副本），先删软删行再恢复唯一键，
--    否则 ADD UNIQUE 会因重复值失败。

-- ① 清掉 06 遗留的软删版本（先删数据，才能恢复唯一键）
SET @has_deleted_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tb_tool_version'
    AND COLUMN_NAME = 'deleted'
);
SET @sql := IF(@has_deleted_col > 0,
  'DELETE FROM tb_tool_version WHERE deleted = 1',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② 删除 deleted 列（若存在）
SET @sql := IF(@has_deleted_col > 0,
  'ALTER TABLE tb_tool_version DROP COLUMN deleted',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ 恢复版本唯一键 (tool_id, version)（若不存在）
SET @has_uk := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tb_tool_version'
    AND INDEX_NAME = 'uk_tool_version'
);
SET @sql := IF(@has_uk = 0,
  'ALTER TABLE tb_tool_version ADD UNIQUE KEY uk_tool_version (tool_id, version)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ④ 清理 06 给 deleted 建的索引（若仍存在；列已删则索引通常已被连带删除）
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'tb_tool_version'
    AND INDEX_NAME = 'idx_tool_version_deleted'
);
SET @sql := IF(@has_idx > 0,
  'ALTER TABLE tb_tool_version DROP INDEX idx_tool_version_deleted',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验（执行后可手动跑）：
-- SHOW CREATE TABLE tb_tool_version;
--   期望：无 deleted 列；有 UNIQUE KEY uk_tool_version (tool_id, version)；有 uk_file_key
