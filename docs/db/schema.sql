-- ============================================================
-- tool-box 工具管理箱 · 全量建表脚本（新库只需本脚本）
-- 数据库：MySQL 8 / utf8mb4 / InnoDB
-- 约定：主键 bigint；时间 datetime；状态 tinyint；软删除 deleted；
--       所有表与字段含中文注释；脚本幂等（IF NOT EXISTS）。
-- 执行：在已创建的 `toolbox` 库内执行本脚本。
-- 文件本体不入库（只存存储键）；分片不建表（落磁盘临时目录）。
--
-- 【本脚本的地位】全量、幂等 —— 含当前**全部 9 张表**与列默认值，与代码实体一一对应。
--   - 全新库：只跑本脚本即可（种子数据与管理员由 `POST /api/system/init` 写入）。
--   - 老库升级：本脚本是 `IF NOT EXISTS`，**不会补建新表 / 新列** → 请跑 `03` / `04` / `05` 增量脚本。
--   ⚠️ 详细执行清单与场景见同目录 `README.md`。
--
-- ⚠️【不要改路径 / 改名】本文件名为 `schema.sql`，但位于 `resources/db/` 子目录 ——
--   Spring Boot 默认的 DataSource 初始化只扫 `classpath*:schema.sql`（**仅类路径根目录**，
--   不递归子目录），因此本文件**不会被自动执行**，脚本仍由**人工执行**（项目既定约定）。
--   一旦把它挪到 `src/main/resources/schema.sql`（类路径根），Spring 就会加载它。
-- ============================================================

CREATE TABLE IF NOT EXISTS `tb_dept` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        varchar(50)  NOT NULL COMMENT '部门名称，不可重复',
  `remark`      varchar(200) NULL     COMMENT '备注',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
  `parent_id`   bigint       NULL     COMMENT '上级部门 ID；当前为扁平结构，恒为 NULL',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

CREATE TABLE IF NOT EXISTS `tb_user` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`    varchar(32)  NOT NULL COMMENT '登录账号，全局唯一',
  `password`    varchar(100) NOT NULL COMMENT '密码摘要（BCrypt，禁止明文）',
  `real_name`   varchar(50)  NOT NULL COMMENT '姓名',
  `dept_id`     bigint       NOT NULL COMMENT '归属部门（一人一部门，不支持兼岗）',
  `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '状态：1 启用 / 0 停用',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  KEY `idx_user_dept` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户（账号）表';

CREATE TABLE IF NOT EXISTS `tb_user_permission` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     bigint       NOT NULL COMMENT '用户 ID',
  `permission_code` varchar(64) NOT NULL COMMENT '权限点编码（见契约 §13.1）',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_perm` (`user_id`, `permission_code`),
  KEY `idx_user_perm_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户权限点表（一人多条，全量覆盖）';

CREATE TABLE IF NOT EXISTS `tb_tag` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        varchar(20)  NOT NULL COMMENT '标签名称，不可重复',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标签字典表';

CREATE TABLE IF NOT EXISTS `tb_tool` (
  `id`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`              varchar(100) NOT NULL COMMENT '工具名称，全局唯一（不区分部门）',
  `intro`             varchar(200) NOT NULL COMMENT '简介',
  `readme`            text         NULL     COMMENT '使用说明（纯文本，保留换行）',
  `file_ext`          varchar(20)  NOT NULL COMMENT '文件格式（小写，如 py / zip / jar）',
  `platform`          varchar(50)  NULL     COMMENT '运行平台',
  `dependency`        varchar(500) NULL     COMMENT '依赖说明',
  `scope`             varchar(16)  NOT NULL COMMENT '可见范围：DEPT 本部门 / PUBLIC 全员',
  `dept_id`           bigint       NOT NULL COMMENT '归属部门（取上传人所属部门）',
  `uploader_id`       bigint       NOT NULL COMMENT '上传人',
  `latest_version_id` bigint       NOT NULL COMMENT '最新版本 ID（新增版本同事务更新）',
  `latest_version`    varchar(20)  NOT NULL COMMENT '最新版本号（冗余，供列表展示）',
  `file_size`         bigint       NOT NULL COMMENT '最新版本文件大小（字节，冗余）',
  `download_count`    int          NOT NULL DEFAULT 0 COMMENT '累计下载次数',
  `deleted`           tinyint      NOT NULL DEFAULT 0 COMMENT '软删除标记：0 正常 / 1 已删除',
  `create_time`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_name` (`name`),
  KEY `idx_tool_dept_scope_deleted` (`dept_id`, `scope`, `deleted`),
  KEY `idx_tool_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具表（元数据，不含文件）';

CREATE TABLE IF NOT EXISTS `tb_tool_version` (
  `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_id`       bigint       NOT NULL COMMENT '所属工具 ID',
  `version`       varchar(20)  NOT NULL COMMENT '版本号，同一工具下不可重复',
  `file_key`      varchar(64)  NOT NULL COMMENT '存储键（物理位置由存储层解析，禁止存物理路径）',
  `file_name`     varchar(255) NOT NULL COMMENT '原始文件名（含扩展名）',
  `file_ext`      varchar(20)  NOT NULL COMMENT '文件格式（小写）',
  `file_size`     bigint       NOT NULL COMMENT '文件大小（字节）',
  `storage_backend` varchar(8) NOT NULL DEFAULT 'local' COMMENT '文件落点：local 本机磁盘 / oss 阿里云 OSS（M-22）',
  `uploader_id`   bigint       NOT NULL COMMENT '上传人',
  `download_count` int         NOT NULL DEFAULT 0 COMMENT '该版本下载次数',
  `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_version` (`tool_id`, `version`),
  UNIQUE KEY `uk_file_key` (`file_key`),
  KEY `idx_tool_version_tool` (`tool_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具版本表（文件挂在这一层）';

CREATE TABLE IF NOT EXISTS `tb_tool_tag` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tool_id`     bigint       NOT NULL COMMENT '工具 ID',
  `tag_id`      bigint       NOT NULL COMMENT '标签 ID',
  `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tool_tag` (`tool_id`, `tag_id`),
  KEY `idx_tool_tag_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具标签关联表（删标签只删关联，不删工具）';

CREATE TABLE IF NOT EXISTS `tb_setting` (
  `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `setting_key` varchar(64)  NOT NULL COMMENT '配置键，唯一',
  `setting_value` varchar(1024) NOT NULL COMMENT '配置值（字符串存储，按业务解析）',
  `update_time` datetime     NULL     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局设置表（键值）';

-- 工具定向授权表（v1.1，M-17 跨部门工具授权）：见 数据模型.md §3.9
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
