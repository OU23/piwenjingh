-- ============================================================
-- tool-box · 增量迁移 04：新增「工具定向授权」表 tb_tool_grant
--   （M-17 跨部门工具授权 / 契约 v1.7 / 数据模型 v1.1 §3.9）
--
-- 【为什么需要这个脚本】
-- `01-init-tables.sql` 用的建表语句是 `CREATE TABLE IF NOT EXISTS`，**且该脚本只由人工执行**
-- （后端代码里没有任何地方引用它，系统初始化接口也不会重建 schema）。
-- 因此 —— **已初始化的老库不会自动补建新表**。
--
-- 本阶段新增了第 9 张表 `tb_tool_grant`。老库（8 表）若不执行本脚本，
-- 所有经过「可见范围判定 / 授权查询」的接口会直接 500：
--     Table 'toolbox.tb_tool_grant' doesn't exist
--
-- 实测影响面（2026-09-28 联调复现）:
--   GET  /api/tools                      → 500（列表带 EXISTS 子查询，必走此表）
--   GET/PUT /api/users/{id}/tool-grants  → 500
--   GET/PUT /api/tools/{id}/grants       → 500
--
-- 【执行】
--   mysql -u root -p toolbox < 04-add-tool-grant.sql
--
-- 【幂等】可重复执行；只新建这一张表，不改动其余 8 张表与任何数据。
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_tool_grant` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_id`     bigint       NOT NULL COMMENT '被授权的工具 ID',
  `user_id`     bigint       NOT NULL COMMENT '被授权的用户 ID',
  `grant_by`    bigint       NOT NULL COMMENT '最近一次授权的操作人（管理员）用户 ID',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `update_time` datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最近修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_user` (`tool_id`, `user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_tool_id` (`tool_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具定向授权（跨部门可见/下载例外）';

-- ---------- 校验（应返回 1）----------
-- SELECT COUNT(*) AS 表已存在 FROM information_schema.tables
--   WHERE table_schema = 'toolbox' AND table_name = 'tb_tool_grant';
