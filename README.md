# tool-box 后端（交付说明）

> 工具管理箱后端服务：文件存储 + 分片上传 + 部门隔离的工具检索与下载。  
> 技术栈：Java 17 · Spring Boot 3.2.5 · MyBatis-Plus 3.5.7 · MySQL 8 · Maven。  
> 本文件是**启动说明**；配置见 `src/main/resources/application.yml`（基础）+ `application-dev.yml` / `application-prod.yml`（profile），
> 部署与人工验收见文档仓库 `tool-box-bak/docs/`（如 `部署配置说明.md`、`交付/后端人工验收手册.md`）。

---

## 1. 依赖版本

| 组件    | 版本       | 说明                                                                          |
| ----- | -------- | --------------------------------------------------------------------------- |
| JDK   | **17**   | 编译与运行均为 17                                                                  |
| MySQL | **8.x**  | 需支持 `utf8mb4`；库名默认 `toolbox`（连接串带 `createDatabaseIfNotExist=true`，首次启动自动建库） |
| Maven | **3.9+** | 仅构建用；也可用 IDE 直接运行 `ToolboxApplication`                                      |

## 2. 环境变量（**启动前必须设置**）

| 变量                     | 必填       | 说明                                                                                                    |
| ---------------------- | -------- | ----------------------------------------------------------------------------------------------------- |
| `MYSQL_PASSWORD`       | **是**    | 数据库口令。**代码与配置文件里没有明文口令**，未设置则启动失败（这是刻意设计，避免口令进仓库）                                                     |
| `JWT_SECRET`           | **生产必填** | 令牌签名密钥，长度建议 ≥ 32 字符。未设置时使用仅供本地开发的默认值，**生产环境必须覆盖**                                                     |
| `TOOLBOX_STORAGE_ROOT` | 否        | 工具文件的存储根目录（绝对路径，**统一正斜杠**，如 `D:/toolbox/files`）。未配置时：优先用数据库里的全局设置；都没有则初始化接口会落到"工作目录下 `toolbox-files`" |
| `TOOLBOX_CHUNK_DIR`    | 否        | 分片临时目录，默认 `${java.io.tmpdir}/toolbox-chunks`。GB 级上传需保证该目录所在盘有足够空间                                     |

数据库连接信息（地址 / 账号）在 `src/main/resources/application.yml` 中，按需修改；**口令只走环境变量**。

## 3. 初始化数据库

> 脚本位于 `docs/db/`（**只由人工执行**，后端代码零引用、不会自动执行）。
> 完整执行清单 / 两种部署场景 / 校验 SQL 见 **`docs/db/README.md`**。

```bash
# ① 建表（全量 9 张表，幂等，可重复执行）—— 新库只需这一步
mysql -u root -p toolbox < docs/db/schema.sql

# ② 种子数据：基础标签字典 + 默认单文件上限（幂等）
#    可选：`POST /api/system/init` 也会写同名默认值，走初始化页时可不执行
mysql -u root -p toolbox < docs/db/02-seed-base.sql

# ③ 表结构对齐（幂等；**库由旧版脚本建时必须跑一次**）
mysql -u root -p toolbox < docs/db/03-align-schema.sql

# ④ 增量：补 tb_tool_grant 表（M-17；**老库必做** —— schema.sql 已含该表，对老库无效）
mysql -u root -p toolbox < docs/db/04-add-tool-grant.sql

# ⑤ 增量：补 tb_tool_version.storage_backend 列（M-22；**老库必做** —— 同上）
mysql -u root -p toolbox < docs/db/05-add-storage-backend.sql
```

⚠️ **老库升级 = 按编号顺序执行 ②~⑤**（全部幂等，DDL 即时生效、**跑完无需重启后端**）。
只跑 `schema.sql` **不会给已存在的表补列 / 补表** → 典型症状是**登录与列表正常，但上传 /
下载 / 可见范围等接口 500**（日志 `Unknown column 'xxx'` 或 `Table 'xxx' doesn't exist`）。
**交付时 `docs/db/` 必须整个目录带上服务器。**

> **③ 为什么需要**：`CREATE TABLE IF NOT EXISTS` **不会修改已存在的表**。若库是更早版本的
> 建表脚本建的，后补的 `DEFAULT CURRENT_TIMESTAMP` / `ON UPDATE` 不会生效 → 任何
> **不显式写 `create_time` 的 INSERT**（手工 SQL / 运维脚本）会写进 `0000-00-00 00:00:00`
> 零日期，后端把它映射成 `LocalDateTime` 时抛异常 → **该表相关接口直接 500**。
> 业务代码总是显式赋时间，正常链路无碍；③ 用于消除这个隐患。
> 执行后校验：`create_time` 有默认值的列应为 7 个，`tb_tag` 零日期应为 0 行（脚本末尾有校验语句）。

> 若跳过 ②，也可在服务起来后调用一次 `POST /api/system/init`（它会同时写入标签与默认设置）。  
> 两条路径写入的默认值一致，先后执行都不冲突。

## 4. 启动

```bash
# 方式一：命令行（必须指定 dev profile，否则只加载基础配置、无数据源 → 启动失败）
export MYSQL_PASSWORD=你的数据库口令
export JWT_SECRET=请换成长随机串
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 方式二：打包后运行
mvn clean package -DskipTests
java -jar target/toolbox-1.0.0.jar
```

服务默认端口 **8080**。

## 5. 首次初始化（建管理员）

服务起后，数据库还没有任何账号，先查状态再初始化：

```bash
# 查询是否需要初始化
curl http://localhost:8080/api/system/init-status

# 初始化：创建默认部门 + 首个全局管理员（含全部 8 个权限点）+ 基础标签 + 默认设置
curl -X POST http://localhost:8080/api/system/init \
  -H 'Content-Type: application/json' \
  -d '{"adminUsername":"admin","adminPassword":"请自行设置强口令","deptName":"默认部门"}'

# 登录拿令牌
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"刚才设置的口令"}'
```

> 初始化接口**幂等**：重复调用返回 `1007`（系统已初始化），不会重复写入。  
> 初始管理员持全部权限（含 `sys:setting`），可跨部门查看与管理。

## 6. 关键配置项

| 配置项                                    | 默认值                | 说明                                 |
| -------------------------------------- | ------------------ | ---------------------------------- |
| `toolbox.jwt.expiration-minutes`       | 720                | 令牌有效期（分钟）                          |
| `toolbox.storage.root`                 | 空                  | 存储根路径的**兜底值**；实际取值优先级见下            |
| `toolbox.upload.max-file-size`         | 10737418240（10 GB） | 单文件上限默认值（库中有全局设置时以设置表为准）           |
| `toolbox.upload.chunk-size`            | 5242880（5 MB）      | 分片大小参考值；**实际以客户端切分大小为准，后端不校验单片大小** |
| `toolbox.upload.chunk-retention-hours` | 24                 | 未合并分片的保留时长（小时），到期由定时任务清理           |
| `toolbox.upload.cleanup-interval-ms`   | 3600000            | 分片清理任务的扫描间隔（毫秒）                    |
| `toolbox.security.public-paths`       | 见 `PublicPathProperties` 默认值 | 认证过滤器（`JwtAuthFilter`）白名单：列表内的路径**完全公开、无需任何凭证**。默认值已含 `/ws`、登录、初始化等 |

**`toolbox.security.public-paths` 的硬性约束（易踩坑）**：  
该列表**必须包含 `/ws`**，否则会出现「账号被踢不弹窗 / 前端 WebSocket 连不上」。原因链：
1. WebSocket 握手是一个打到 `/ws` 的 HTTP 升级请求，它会先经过 `JwtAuthFilter`；
2. 握手无法携带 `Authorization` 头（令牌走 `?token=` 查询参数），其鉴权由 `AuthHandshakeInterceptor` 单独完成；
3. 若 `/ws` 不在 `public-paths`，过滤器会把这次升级请求当成「未登录」直接回 `401` → 握手失败 → 前端 WS 永远连不上 → 后端想推 `FORCE_LOGOUT` 时找不到会话 → 不弹窗。

> ⚠️ **显式枚举会顶掉默认值**：`PublicPathProperties` 的 Java 默认值本就含 `/ws`，但**仅当配置文件里完全没写 `toolbox.security.public-paths` 时才生效**。Spring Boot 对 list 型属性是「显式覆盖默认值、跨 profile 文件不合并」——只要任一文件手写这个列表，**就必须手动把 `/ws` 写进去**，否则会悄悄丢失（本仓库拆分后的 `application.yml` / `application-dev.yml` 曾因此漏掉 `/ws`，已补回）。

**存储根路径的取值优先级**（重要）：  
① 全局设置表 `storageRoot`（管理员在设置页填写，即时生效）→ ② 配置项 `toolbox.storage.root`。  
改了设置后**新文件立即落到新目录**；**旧文件不会自动迁移**，需人工搬移。  
路径统一使用**正斜杠**（`D:/toolbox/files`），后端对反斜杠会做容错归一化。

## 7. 常见问题

| 现象                                | 原因与处理                                                        |
| --------------------------------- | ------------------------------------------------------------ |
| 启动报数据库连接失败                        | 未设置 `MYSQL_PASSWORD`；或 MySQL 未启动、账号/库名不对                     |
| 保存设置报 `2003 存储路径不存在 / 不是目录 / 不可写` | 该路径必须**已存在且可写**（后端不自动建目录，避免把填错的路径变成"悄悄建了个错目录"）；先在服务器上建好目录再保存 |
| 上传报 `2003 存储根路径未配置`               | 既没在设置页填，也没配 `TOOLBOX_STORAGE_ROOT`。任选一种配置即可                  |
| 上传报 `2001 文件超过大小上限`               | 超出全局设置的 `maxFileSize`；管理员可在设置页调整（改后立刻对新上传生效）                 |
| 上传报 `2002 分片不完整`                  | 有分片没传成功；提示里会列出**缺失的分片序号**，前端据此续传补片                           |
| 上传大文件报"无法连接服务器"或 `1003 请求体超过服务端接收上限` | 客户端分片大小超过了 `spring.servlet.multipart.max-file-size`（本配置 **1GB**，正常不会触发）。若被调小过，请调到不小于分片大小（前端当前 5MB） |
| 某个列表接口突然 `500` | 先查该表时间列是否有**零日期**（`create_time LIKE '0000%'`）—— 库由旧版建表脚本创建时会踩到；跑 `docs/db/03-align-schema.sql` 修复 |
| 下载返回 `401`                        | 未登录或令牌过期；`?token=` 方式下载时令牌**一次性且 60 秒有效**，重复点击会失效            |
| 跨部门看不到工具                          | 属预期：普通用户默认只见"本部门 + 公共"；全局管理员（持 `sys:setting`）不受此限            |
| 账号被踢后不弹"下线通知" / 前端 WebSocket 连不上 | 多半是 `toolbox.security.public-paths` **漏了 `/ws`**：`JwtAuthFilter` 把 `/ws` 升级请求当未登录拦成 `401`，WS 握手失败、前端连不上，后端无法推送 `FORCE_LOGOUT`。修法：在 `public-paths` 补 `- /ws` 后重启（详见 §6 说明） |

## 8. 跑测试

```bash
mvn test          # 全量用例（使用 H2 内存库，不连真实库、不污染数据）
```
