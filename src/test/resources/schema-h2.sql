-- ============================================================
-- 测试库结构（H2 / MODE=MySQL）
-- 与 sql/01-init-tables.sql 语义等价：字段、唯一键、索引一致；
-- 仅去掉 MySQL 专有语法（COMMENT / ENGINE / CHARSET）与 ON UPDATE 子句。
-- 每次测试启动重建，保证用例互不污染。
-- ============================================================

DROP TABLE IF EXISTS tb_dept;
DROP TABLE IF EXISTS tb_user;
DROP TABLE IF EXISTS tb_user_permission;
DROP TABLE IF EXISTS tb_tag;
DROP TABLE IF EXISTS tb_setting;
DROP TABLE IF EXISTS tb_tool;
DROP TABLE IF EXISTS tb_tool_version;
DROP TABLE IF EXISTS tb_tool_tag;
DROP TABLE IF EXISTS tb_tool_grant;

CREATE TABLE tb_dept (
  id          bigint       NOT NULL AUTO_INCREMENT,
  name        varchar(50)  NOT NULL,
  remark      varchar(200) NULL,
  status      tinyint      NOT NULL DEFAULT 1,
  parent_id   bigint       NULL,
  create_time datetime     NOT NULL,
  update_time datetime     NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_dept_name UNIQUE (name)
);

CREATE TABLE tb_user (
  id          bigint       NOT NULL AUTO_INCREMENT,
  username    varchar(32)  NOT NULL,
  password    varchar(100) NOT NULL,
  real_name   varchar(50)  NOT NULL,
  dept_id     bigint       NOT NULL,
  status      tinyint      NOT NULL DEFAULT 1,
  create_time datetime     NOT NULL,
  update_time datetime     NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_username UNIQUE (username)
);
CREATE INDEX idx_user_dept ON tb_user (dept_id);

CREATE TABLE tb_user_permission (
  id              bigint      NOT NULL AUTO_INCREMENT,
  user_id         bigint      NOT NULL,
  permission_code varchar(64) NOT NULL,
  create_time     datetime    NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_user_perm UNIQUE (user_id, permission_code)
);
CREATE INDEX idx_user_perm_user ON tb_user_permission (user_id);

CREATE TABLE tb_tag (
  id          bigint      NOT NULL AUTO_INCREMENT,
  name        varchar(20) NOT NULL,
  create_time datetime    NOT NULL,
  update_time datetime    NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_tag_name UNIQUE (name)
);

CREATE TABLE tb_setting (
  id            bigint        NOT NULL AUTO_INCREMENT,
  setting_key   varchar(64)   NOT NULL,
  setting_value varchar(1024) NOT NULL,
  update_time   datetime      NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_setting_key UNIQUE (setting_key)
);

-- 工具表（B3）：与 sql/01-init-tables.sql 的 tb_tool 对齐
CREATE TABLE tb_tool (
  id                bigint       NOT NULL AUTO_INCREMENT,
  name              varchar(100) NOT NULL,
  intro             varchar(200) NOT NULL,
  readme            text         NULL,
  file_ext          varchar(20)  NOT NULL,
  platform          varchar(50)  NULL,
  dependency        varchar(500) NULL,
  scope             varchar(16)  NOT NULL,
  dept_id           bigint       NOT NULL,
  uploader_id       bigint       NOT NULL,
  latest_version_id bigint       NOT NULL,
  latest_version    varchar(20)  NOT NULL,
  file_size         bigint       NOT NULL,
  download_count    int          NOT NULL DEFAULT 0,
  deleted           tinyint      NOT NULL DEFAULT 0,
  create_time       datetime     NOT NULL,
  update_time       datetime     NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_tool_name UNIQUE (name)
);

CREATE TABLE tb_tool_version (
  id             bigint       NOT NULL AUTO_INCREMENT,
  tool_id        bigint       NOT NULL,
  version        varchar(20)  NOT NULL,
  file_key       varchar(64)  NOT NULL,
  file_name      varchar(255) NOT NULL,
  file_ext       varchar(20)  NOT NULL,
  file_size      bigint       NOT NULL,
  uploader_id    bigint       NOT NULL,
  download_count int          NOT NULL DEFAULT 0,
  create_time    datetime     NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_tool_version UNIQUE (tool_id, version),
  CONSTRAINT uk_file_key UNIQUE (file_key)
);

CREATE TABLE tb_tool_tag (
  id          bigint   NOT NULL AUTO_INCREMENT,
  tool_id     bigint   NOT NULL,
  tag_id      bigint   NOT NULL,
  create_time datetime NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_tool_tag UNIQUE (tool_id, tag_id)
);

-- 工具定向授权表（v1.1，M-17）：与 sql/01-init-tables.sql 的 tb_tool_grant 对齐
CREATE TABLE tb_tool_grant (
  id          bigint       NOT NULL AUTO_INCREMENT,
  tool_id     bigint       NOT NULL,
  user_id     bigint       NOT NULL,
  grant_by    bigint       NOT NULL,
  create_time datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime     NULL,
  PRIMARY KEY (id),
  CONSTRAINT uk_tool_user UNIQUE (tool_id, user_id)
);
CREATE INDEX idx_grant_user ON tb_tool_grant (user_id);
CREATE INDEX idx_grant_tool ON tb_tool_grant (tool_id);
