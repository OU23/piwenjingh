# toolbox-backend Docker Compose 线上部署

本目录只部署后端服务。MySQL、Nginx 和 TLS 证书均由服务器既有运维流程维护，不会由 Compose 创建或修改。

## 1. 部署前准备

1. 将完整项目源码上传至服务器，并保留 `deploy/` 与 `src/` 的相对目录关系。
2. 安装 Docker Engine 和 Docker Compose v2。
3. 创建线上 MySQL 数据库与业务账号，并允许该服务器访问。
4. 编辑 `deploy/docker-compose.yml`，替换全部 `REPLACE_*` 配置值：数据库库名、账号、密码及不少于 32 个字符的 JWT 密钥。宿主机 MySQL 保持使用 `host.docker.internal`。
5. 确保部署用户可创建和写入 `/opt/toolbox/uploads`。该目录保存正式文件和上传分片，必须纳入备份。

数据库密码和 JWT 密钥会按当前部署要求直接保存在 Compose 文件中。请将该文件视作敏感配置，避免提交到公开仓库、日志、截图或工单。

## 2. 数据库人工初始化

应用启动不会自动创建数据库或执行 SQL。请在**项目根目录**手动执行：

```bash
# 新库：创建全部当前表结构
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/schema.sql

# 老库升级：按需执行
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/04-add-tool-grant.sql
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/03-align-schema.sql
```

新库创建首个管理员时，启动服务后调用既有 `POST /api/system/init` 接口；数据库 SQL 不会创建管理员明文密码。

## 3. 启动与日常运维

```bash
./deploy/scripts/build-up.sh
./deploy/scripts/status.sh
./deploy/scripts/logs.sh
./deploy/scripts/healthcheck.sh
```

源码更新后执行：

```bash
./deploy/scripts/restart.sh
```

停止服务但保留容器数据和宿主机上传文件：

```bash
./deploy/scripts/stop.sh
```

服务仅监听 `127.0.0.1:8080`，不能直接从公网访问。

## 4. 宿主机 Nginx

复制 `deploy/nginx/toolbox.conf.example` 到宿主机 Nginx 配置目录，替换：

- `REPLACE_DOMAIN` 为实际域名；
- 两个 `REPLACE_PATH` 为 TLS 证书目录。

配置包含 `/ws` WebSocket 转发、1GB 请求体限制和适用于上传与长连接的超时。安装后按宿主机既有流程执行 `nginx -t`，确认无误后 reload Nginx。

## 5. 备份与回滚

完整恢复需要同时备份线上 MySQL 和 `/opt/toolbox/uploads`。发布新版本前保留上一版源码或镜像；回滚应用时使用上一版源码再次运行 `./deploy/scripts/build-up.sh`。数据库结构变更不自动回滚，须由运维按实际 SQL 变更单独处理。
