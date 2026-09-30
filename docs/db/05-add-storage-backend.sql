-- ============================================================
-- tool-box · 增量迁移 05：tb_tool_version 新增「存储后端落点」字段 storage_backend
--   （M-22 阿里云 OSS 对接 / 契约 v1.19 / 数据模型 v1.2 §3.6 · §5.5）
--
-- 【为什么需要这个脚本】
-- `schema.sql` 用的是 `CREATE TABLE IF NOT EXISTS` —— **表已存在时它不做任何改动**，
-- 因此**已初始化的老库不会自动补上新列**。本阶段给 `tb_tool_version` 增加了
-- `storage_backend`，老库若不执行本脚本，读写版本记录 / 下载分流的接口会直接 500：
--     Unknown column 'storage_backend' in 'field list'
--
-- 【为什么需要这个字段】
-- 全局开关（`toolbox.storage.type`）只决定**新上传文件**落本地还是 OSS；**存量文件**在迁移
-- 完成前仍在本地 → 存在"本地 / OSS 混合态"。**下载必须按每个文件的实际落点分流**
--   （storage_backend='oss'   → 签发短时效签名 URL，浏览器直连 OSS；
--     storage_backend='local' → 仍由后端代理返回），
-- 故落点必须**逐文件**记录（不能用全局开关推断）。
--
-- 【语义】
--   storage_backend = 'local'（默认）→ 文件在本机磁盘；**存量数据全部为该值**。
--   storage_backend = 'oss'          → 文件在阿里云 OSS。
--   ⚠️ 默认值即"本地"，与升级前行为**完全一致**，**无需任何数据回填**。
--
-- 【执行】
--   mysql -u root -p toolbox < 05-add-storage-backend.sql
--
-- 【幂等】可重复执行：列已存在则跳过。
--   MySQL 8 **不支持** `ADD COLUMN IF NOT EXISTS`，故用 information_schema 探测 + 动态 SQL。
-- ============================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'tb_tool_version'
    AND COLUMN_NAME  = 'storage_backend'
);

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `tb_tool_version`
     ADD COLUMN `storage_backend` varchar(8) NOT NULL DEFAULT ''local''
     COMMENT ''文件落点：local / oss（M-22）'' AFTER `file_size`',
  'DO 0'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 校验（应返回 1 行：storage_backend | local）----------
-- SELECT COLUMN_NAME, COLUMN_DEFAULT, COLUMN_COMMENT
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME   = 'tb_tool_version'
--   AND COLUMN_NAME  = 'storage_backend';

-- ---------- 校验（应返回 0：存量全部为 local，无脏值）----------
-- SELECT COUNT(*) AS 非local行数 FROM tb_tool_version WHERE storage_backend <> 'local';
