-- ============================================================
-- tool-box · 种子数据（幂等，可重复执行）
-- 对应 B4 计划 T3 / 验收 A8：空库按说明初始化后系统即可用、不空跑。
--
-- 执行顺序：先 schema.sql 建表，再本脚本。
-- 幂等手段：INSERT IGNORE + 表上的唯一键（uk_tag_name / uk_setting_key），
--           重复执行不会报错、不会产生重复行、不会覆盖管理员后续的修改。
--
-- 说明：本脚本只种"字典与默认值"这类**必需**数据；
--      管理员账号必须走 POST /api/system/init（口令需 BCrypt 摘要，SQL 里写不了明文口令）。
--      两条路径写入的默认值完全一致，先后执行互不冲突。
-- ============================================================

-- 基础标签字典（与后端 SystemService.DEFAULT_TAGS 保持完全一致，勿单方增删）
--
-- ⚠️ **必须显式写 create_time，不能依赖列的 DEFAULT CURRENT_TIMESTAMP**（2026-09-20 事故）：
--    线上的表可能是**更早版本**的建表脚本创建的（当时 create_time 是 NOT NULL 但**没有默认值**），
--    而 `CREATE TABLE IF NOT EXISTS` 不会去改已存在的表 → 默认值不会补上。
--    此时 `INSERT IGNORE` 会把"NOT NULL 列无值"的严格模式报错**降级成警告**，
--    于是写进 `0000-00-00 00:00:00`（零日期）→ 后端把 datetime 映射成 LocalDateTime 时抛异常
--    → 该表相关接口直接 500（本次即 `GET /api/tags` 500）。
--    显式给值后，无论线上表结构是哪一版都不会再出这个问题。
INSERT IGNORE INTO `tb_tag` (`name`, `create_time`) VALUES
  ('数据处理', NOW()),
  ('网络工具', NOW()),
  ('系统运维', NOW()),
  ('开发调试', NOW()),
  ('效率工具', NOW()),
  ('文档资料', NOW());

-- 默认全局设置
-- ⚠️ 刻意**不种 storageRoot**：它是一个平台相关的绝对路径（Linux `/data/...` / Windows `D:/...`），
--    种错会让"默认值"变成隐蔽陷阱 —— 文件被悄悄写到意料之外的目录。
--    存储根路径的正确来源是：① 配置项 `toolbox.storage.root`（部署期）；
--    ② 管理员在设置页填写（写入前后端会校验存在性与可写性，不满足 → 2003）；
--    ③ `POST /api/system/init` 会按"配置项 → 工作目录下 toolbox-files"落一个可用默认值。
-- 分片大小（M-23 / B14）：字节，默认 20 MB；管理员可在设置页改为 5 ~ 100 MB。
-- 不种也可 —— `SettingService.getChunkSize()` 缺失时回落同一默认值，本行只是让默认值"显式可见"。
INSERT IGNORE INTO `tb_setting` (`setting_key`, `setting_value`) VALUES
  ('maxFileSize', '10737418240'),
  ('chunkSize', '20971520');

-- 校验：下面两条应分别返回 6 行与 2 行
-- SELECT COUNT(*) AS tag_count FROM tb_tag;
-- SELECT * FROM tb_setting;
