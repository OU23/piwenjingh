-- 08 tb_tool 增加「工具类型」与「链接地址」（M-25 工具归属调整 + 链接工具，契约 v1.23）
--
-- 变更：
--   ① tb_tool.tool_type  varchar(8) NOT NULL DEFAULT 'FILE'   FILE 文件工具 / LINK 链接工具
--   ② tb_tool.link_url   varchar(512) NULL                    仅 LINK 有值（http/https）
--   ③ idx_tool_type 索引（列表按类型筛选）
--
-- 执行：mysql -u root -p toolbox < docs/db/08-tool-type-and-link.sql
-- 幂等：全部「先探测、再执行」，可重复运行。
-- 存量零回填：tool_type 默认 FILE，老数据全部视为文件工具。

-- ① tool_type 列
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool' AND COLUMN_NAME = 'tool_type'
);
SET @sql := IF(@has_col = 0,
  "ALTER TABLE tb_tool ADD COLUMN tool_type varchar(8) NOT NULL DEFAULT 'FILE' COMMENT '工具类型：FILE 文件工具 / LINK 链接工具（M-25）' AFTER scope",
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ② link_url 列
SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool' AND COLUMN_NAME = 'link_url'
);
SET @sql := IF(@has_col = 0,
  "ALTER TABLE tb_tool ADD COLUMN link_url varchar(512) NULL COMMENT '链接工具的目标地址（仅 tool_type=LINK 时有值；M-25）' AFTER tool_type",
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ③ 类型筛选索引
SET @has_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_tool' AND INDEX_NAME = 'idx_tool_type'
);
SET @sql := IF(@has_idx = 0,
  'CREATE INDEX idx_tool_type ON tb_tool (tool_type)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验：
-- SHOW CREATE TABLE tb_tool;
--   期望：有 tool_type（默认 FILE）、link_url、idx_tool_type；
-- SELECT tool_type, COUNT(*) FROM tb_tool GROUP BY tool_type;   -- 存量应全为 FILE
