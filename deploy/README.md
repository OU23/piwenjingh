# toolbox-backend Docker Compose 线上部署

本目录只部署后端服务。MySQL、Nginx 和 TLS 证书均由服务器既有运维流程维护，不会由 Compose 创建或修改。

## 1. 部署前准备

1. 将完整项目源码上传至服务器，并保留 `deploy/` 与 `src/` 的相对目录关系。
2. 安装 JDK 17、Maven 3.9+、Docker Engine 和 Docker Compose v2；`build-up.sh` 会在宿主机使用 Maven 打包，因此可复用宿主机的 Maven 镜像与依赖缓存。
3. 创建线上 MySQL 数据库与业务账号，并允许该服务器访问。
4. 首次部署时复制 `deploy/.env.example` 为 `deploy/.env`（已有文件请直接编辑，勿覆盖），填写 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`。宿主机 MySQL 保持使用 `host.docker.internal`。在 `deploy/docker-compose.yml` 中配置不少于 32 个字符的 JWT 密钥。
5. 确保部署用户可创建和写入 `/opt/toolbox/uploads`。该目录保存正式文件和上传分片，必须纳入备份。

数据库连接从 `deploy/.env` 读取，三项均为必填，缺失时 Compose 会直接报错。密码建议使用单引号包裹，避免 `$` 等字符被插值。JWT 密钥仍保存在 Compose 文件中，请将这两个文件视作敏感配置，避免泄露到公开仓库、日志、截图或工单。

### OSS 与 STS 配置

Compose 的 `environment` 已包含应用支持的全部 OSS、STS 环境变量。若尚未创建 `deploy/.env`，在项目根目录执行：

```bash
cp deploy/.env.example deploy/.env
```

在 `deploy/.env` 中填写 `OSS_ENDPOINT`（公网地址）、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 和 `OSS_STS_ROLE_ARN`，填齐后将 `TOOLBOX_STORAGE_TYPE` 改为 `oss`。默认值为 `local`；启用 `oss` 后缺少必要配置会导致应用启动失败。

下载签名有效期 `OSS_URL_TTL_SECONDS`、STS 凭证有效期 `OSS_STS_DURATION_SECONDS` 默认均为 3600 秒；STS 有效期范围为 900～43200 秒。`OSS_STS_ENDPOINT` 默认是 `sts.aliyuncs.com`。临时凭证由应用运行时申请，无需写入 Compose。

部署脚本会读取 Compose 同目录下的 `.env`；该文件已被 Git 忽略，不要提交真实密钥。修改配置后执行 `./deploy/scripts/restart.sh` 重新创建容器使其生效。

## 2. 数据库人工初始化

应用启动不会自动创建数据库或执行 SQL。请在**项目根目录**手动执行。

### 2.1 新库：一次性建全表

```bash
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/schema.sql
```

### 2.2 老库升级：按编号顺序执行增量脚本

`schema.sql` 用的是 `CREATE TABLE IF NOT EXISTS` —— **表已存在时它不做任何改动**，
因此**已初始化的老库不会自动补列 / 补表**，必须手动执行下列增量脚本（**按编号从小到大**）：

```bash
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/02-seed-base.sql            # 基础种子数据（标签 / 默认设置）
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/03-align-schema.sql         # 时间列对齐（补 DEFAULT / ON UPDATE）
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/04-add-tool-grant.sql       # 新增 tb_tool_grant（M-17 定向授权）
mysql -h DB_HOST -P 3306 -u DB_USER -p DB_NAME < docs/db/05-add-storage-backend.sql  # tb_tool_version 加 storage_backend（M-22 OSS）
```

⚠️ 三条要点：

- **`docs/db/` 必须整个目录随部署包上传**，只带 `schema.sql` 会导致老库缺列 / 缺表。
  典型症状：**登录与列表正常，但上传 / 下载 / 可见范围等接口 500**，日志报
  `Unknown column 'xxx' in 'field list'` 或 `Table 'xxx' doesn't exist`。
- 上述脚本**全部幂等**，可重复执行；**DDL 即时生效，执行后无需重启后端**。
- **每次版本新增了 `NN-*.sql`，都要先在此处补一行**，再交付。

新库创建首个管理员时，启动服务后调用既有 `POST /api/system/init` 接口；数据库 SQL 不会创建管理员明文密码。

## 3. 启动与日常运维

```bash
./deploy/scripts/build-up.sh
./deploy/scripts/status.sh
./deploy/scripts/logs.sh
./deploy/scripts/healthcheck.sh
```

源码更新后执行（脚本会先在宿主机执行 `mvn clean package -DskipTests`，再构建仅包含 JRE 和 JAR 的运行镜像）：

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
