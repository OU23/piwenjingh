-- ============================================================
-- tool-box · 表结构对齐（幂等，可重复执行）
--
-- 【为什么需要这个脚本】
-- `schema.sql`（原 `01-init-tables.sql`）用的是 `CREATE TABLE IF NOT EXISTS`：**表已存在时它不会做任何改动**。
-- 因此如果库是**更早版本**的脚本建的，后来给列补的 `DEFAULT CURRENT_TIMESTAMP` /
-- `ON UPDATE CURRENT_TIMESTAMP` **不会自动生效**。
--
-- 2026-09-20 实测：本地库 8 张表的 `create_time` 全部是「NOT NULL 但无默认值」，
-- `update_time` 也都没有 `ON UPDATE`。后果是 —— 任何**不显式写 create_time 的 INSERT**
-- （手工 SQL、种子脚本、运维脚本）都会写进 `0000-00-00 00:00:00`（零日期）：
--   `INSERT IGNORE` 会把严格模式的报错降级成警告，于是零日期被静默写入；
--   后端把 datetime 映射成 `LocalDateTime` 时会抛异常 → **该表相关接口直接 500**。
--   （本次事故即 `GET /api/tags` 500，原因是基础标签的种子数据写进了零日期。）
--
-- 业务代码本身**总是显式赋值时间列**，所以这些接口在正常链路上是好的；
-- 本脚本是为了让库结构与脚本一致、消除"手工写入即踩雷"的隐患。
--
-- 【执行】
--   mysql -u root -p toolbox < 03-align-schema.sql
-- ============================================================

-- ---------- 1) create_time：补 DEFAULT CURRENT_TIMESTAMP ----------
ALTER TABLE `tb_dept`            MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `tb_user`            MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `tb_user_permission` MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `tb_tag`             MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `tb_tool`            MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
ALTER TABLE `tb_tool_version`    MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间';
ALTER TABLE `tb_tool_tag`        MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';

-- ---------- 2) update_time：补 ON UPDATE CURRENT_TIMESTAMP ----------
ALTER TABLE `tb_dept`   MODIFY COLUMN `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间';
ALTER TABLE `tb_user`   MODIFY COLUMN `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间';
ALTER TABLE `tb_tag`    MODIFY COLUMN `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间';
ALTER TABLE `tb_tool`   MODIFY COLUMN `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间';
ALTER TABLE `tb_setting` MODIFY COLUMN `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间';

-- ---------- 3) 清理任何残留的零日期（保险，正常应为 0 行）----------
UPDATE `tb_dept`            SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_user`            SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_user_permission` SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_tag`             SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_tool`            SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_tool_version`    SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';
UPDATE `tb_tool_tag`        SET `create_time` = NOW() WHERE `create_time` LIKE '0000%';

-- ---------- 4) 备选（默认注释，需要时再放开）----------
-- 线上 tb_tool 的 latest_version_id / latest_version / file_size 是「可空」，
-- 而脚本定义为 NOT NULL。线上更宽松 **不会导致报错**，只是约束弱一些。
-- 若要收紧，请先确认无 NULL 再执行：
--   SELECT COUNT(*) FROM tb_tool WHERE latest_version_id IS NULL OR latest_version IS NULL OR file_size IS NULL;
-- ALTER TABLE `tb_tool` MODIFY COLUMN `latest_version_id` bigint      NOT NULL COMMENT '最新版本 ID（新增版本同事务更新）';
-- ALTER TABLE `tb_tool` MODIFY COLUMN `latest_version`    varchar(20) NOT NULL COMMENT '最新版本号（冗余，供列表展示）';
-- ALTER TABLE `tb_tool` MODIFY COLUMN `file_size`         bigint      NOT NULL COMMENT '最新版本文件大小（字节，冗余）';

-- ---------- 校验（应分别返回 7 与 0）----------
-- SELECT COUNT(*) AS 有默认值的create_time FROM information_schema.columns
--   WHERE table_schema='toolbox' AND column_name='create_time' AND column_default IS NOT NULL;
-- SELECT COUNT(*) AS 残留零日期 FROM tb_tag WHERE create_time LIKE '0000%';
