# OSS 相关文件清单（自查用 · 2026-10-15）

> 用途：逐一核对"我们的 OSS 配置与实现"到底由哪些文件承载。
> 原则：**凭据只在环境变量里**，代码/配置文件里不出现明文密钥。
> 路径前缀均为 `new-tool-box/toolbox-backend/`。

---

## 一、配置类（决定"读哪些环境变量"）

| ☐ | 文件 | 作用 | 重点看 |
| --- | --- | --- | --- |
| ☐ | `src/main/java/com/example/toolbox/storage/OssProperties.java` | OSS 凭据 + STS 段 + 签名 URL 有效期 | 字段与 yml 的 `${ENV}` 一一对应；`configured()`（四项凭据齐备）/ `stsConfigured()`（role-arn 非空） |
| ☐ | `src/main/java/com/example/toolbox/storage/StorageProperties.java` | **写目标开关** `toolbox.storage.type` + 存储根/分片目录 | `writeBackend()` 是"新上传写哪里"的**唯一判据** |

## 二、配置文件（值从哪来）

| ☐ | 文件 | 作用 | 重点看 |
| --- | --- | --- | --- |
| ☐ | `src/main/resources/application.yml` | 基础配置 | **§39–59**：`toolbox.storage.type`、`toolbox.oss.*`（9 个环境变量全在此绑定） |
| ☐ | `src/main/resources/application-dev.yml` | 本地 profile | 是否覆盖 oss 段；`chunk-size` 冗余项 |
| ☐ | `src/main/resources/application-prod.yml` | 生产 profile | datasource / 存储路径的**变量名与 dev 不同**（`SPRING_DATASOURCE_*`） |
| ☐ | `src/test/resources/application-test.yml` | 测试配置 | 确认测试**不依赖真实 OSS** |

**环境变量（OSS 相关 9 项）**：
`TOOLBOX_STORAGE_TYPE`（local/oss）、`OSS_ENDPOINT`、`OSS_BUCKET`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、
`OSS_URL_TTL_SECONDS`、`OSS_STS_ROLE_ARN`、`OSS_STS_DURATION_SECONDS`、`OSS_STS_ENDPOINT`

## 三、存储实现 —— ★【长期 AK 的唯一直接使用者】

| ☐ | 文件 | 作用 | 重点看 |
| --- | --- | --- | --- |
| ☐ | `src/main/java/com/example/toolbox/storage/OssStorageService.java` | **后端直连 OSS 的全部动作** | ★ 第 186–192 行 `client()`：用 `getAccessKeyId/Secret` 建 client。方法：`save`(putObject) / `openStream`(getObject) / `exists` / `size`(getObjectMetadata) / `delete` / `presignedUrl` |
| ☐ | `src/main/java/com/example/toolbox/storage/StorageService.java` | 存储抽象接口 | 只有 `presignedUrl`（读），**无上传类接口** |
| ☐ | `src/main/java/com/example/toolbox/storage/LocalStorageService.java` | 本地磁盘实现 | 对照看 OSS 实现的行为一致性 |
| ☐ | `src/main/java/com/example/toolbox/storage/RoutedStorageService.java` | `@Primary` 路由 | 按 **每个文件的实际落点** 分流（读/删） |
| ☐ | `src/main/java/com/example/toolbox/storage/StorageBackend.java` | 枚举 `LOCAL` / `OSS` | 与 DB 字段 `storage_backend` 取值一致 |
| ☐ | `.../storage/StorageBackendLocator.java`（接口）<br>+ `.../tool/service/ToolVersionStorageBackendLocator.java`（实现） | 落点查询 | 按 `file_key` 查 `tb_tool_version.storage_backend`；**存储层只依赖接口**（不反向依赖业务表） |
| ☐ | `.../storage/StorageRootProvider.java`（接口）<br>+ `.../setting/service/SettingStorageRootProvider.java`（实现） | 存储根优先级 | ① 设置表 `storageRoot` → ② `TOOLBOX_STORAGE_ROOT` / `toolbox.storage.root` |

## 四、直传链路 —— ★【STS 的使用者】

| ☐ | 文件 | 作用 | 重点看 |
| --- | --- | --- | --- |
| ☐ | `src/main/java/com/example/toolbox/upload/service/OssStsCredentialIssuer.java` | 调 `AssumeRole` 签发临时凭证 | ★ 长期 AK **只在这里**用于 STS；内联会话策略限 `tools/<deptId>/*` + 6 个上传动作（**不给 Get/Delete**）；`type=oss` 且 role-arn 空 → 启动失败 |
| ☐ | `src/main/java/com/example/toolbox/upload/service/StsCredential.java` | 临时凭证载体 | `toString()` 是否打码（**禁入日志**） |
| ☐ | `src/main/java/com/example/toolbox/upload/service/UploadDirectService.java` | 模式判定 / 签发 / 收尾 | `mode()`（按全局开关分流）；`complete()` 的 **HEAD 复核 → 用的是长期 AK** |
| ☐ | `src/main/java/com/example/toolbox/upload/controller/UploadDirectController.java` | 3 个端点 | `GET /api/upload/mode`、`POST /api/upload/direct/credentials`、`POST /api/upload/direct/complete` |
| ☐ | `.../upload/vo/UploadModeVO.java` · `UploadCredentialVO.java` | 出参 | 前端据此分流 / 建 `ali-oss` 客户端 |
| ☐ | `.../upload/dto/UploadDirectCredentialDTO.java` · `UploadDirectCompleteDTO.java` | 入参 | 与契约 §8.6 / §8.7 对齐 |
| ☐ | `.../upload/controller/UploadController.java` + `service/UploadService.java` | **老分片链路（仅 local 模式）** | `check` / `chunk` / `merge` / 取消 |

## 五、迁移 与 下载分流

| ☐ | 文件 | 作用 |
| --- | --- | --- |
| ☐ | `src/main/java/com/example/toolbox/tool/controller/StorageMigrationController.java` | `POST /api/tools/migrate-to-oss` + `GET .../{taskId}` |
| ☐ | `.../storage/StorageMigrationService.java` | 任务编排（顺序：读本地 → 写 OSS → 改落点 → 删本地副本） |
| ☐ | `.../storage/MigrationExecutor.java` · `SyncMigrationExecutor.java` | 执行器接口 + 本期同步实现 |
| ☐ | `.../storage/MigrationTask.java` · `MigrationTaskStore.java` · `MigrationParams.java` · `MigrationStatus.java` · `MigrationTaskVO.java` | 任务模型 / 进程内存储 / 参数 / 状态 / 出参 |
| ☐ | `src/main/java/com/example/toolbox/tool/service/DownloadUrlService.java` | 下载分流编排：本地落点发 `?token=` 地址、OSS 落点发签名直链；签发前做 `exists()` 探测 |

## 六、依赖 / 数据库 / 部署 / 运维脚本

| ☐ | 位置 | 内容 |
| --- | --- | --- |
| ☐ | `pom.xml` | `aliyun-sdk-oss` 3.17.4（+ JAXB 三件套）、`aliyun-java-sdk-sts` 3.1.3、**显式** `aliyun-java-sdk-core` 4.7.3 |
| ☐ | `docs/db/05-add-storage-backend.sql` | 给 `tb_tool_version` 加 `storage_backend`（老库升级必跑） |
| ☐ | `docs/db/`（整目录） | `schema.sql` + `02`~`05` 增量；**交付时必须整目录带上** |
| ☐ | `OSS配置说明.md` | 平台侧 + 服务器侧配置手册、§9 排错速查、§10 探针用法 |
| ☐ | `OSS-RAM配置说明.md` | 给管理员的配置指引（策略 A/B、附 E 当前状态、附 F 自助桶策略） |
| ☐ | `OSS-与sts-oss-demo差异说明.md` | 与官方 demo 的路径差异说明（含"两个身份"证据） |
| ☐ | `oss-probe.py` | 零依赖独立探针（⓪ 身份自检 / ① STS / ② 长期 AK 桶权限 / ③ 角色桶权限 / `--set-policy-for` 自助写桶策略） |
| ☐ | `deploy/`（Dockerfile / docker-compose / README） | 生产 `SPRING_PROFILES_ACTIVE=prod`；老库升级清单 |

## 七、测试（与 OSS 相关）

| ☐ | 文件 |
| --- | --- |
| ☐ | `src/test/java/com/example/toolbox/storage/OssStorageServiceTest.java` |
| ☐ | `src/test/java/com/example/toolbox/storage/StorageRoutingTest.java` · `StorageMigrationServiceTest.java` |
| ☐ | `src/test/java/com/example/toolbox/upload/UploadModeTest.java` · `UploadDirectCredentialTest.java` · `UploadDirectCompleteTest.java` · `UploadDirectEndpointTest.java` |
| ☐ | `src/test/java/com/example/toolbox/tool/DownloadUrlEndpointTest.java` · `DownloadUrlPlanTest.java` · `DownloadUrlTestBase.java` |
| ☐ | `src/test/java/com/example/toolbox/setting/SettingApiTest.java` · `SettingEffectiveTest.java`（`chunkSize`） |

---

## 八、一句话记住：**两个身份**

| 身份 | 代码里的位置 | 用途 | 实测 |
| --- | --- | --- | --- |
| **长期 AK**（RAM 用户） | `OssStorageService.client()`（直连 OSS）+ `OssStsCredentialIssuer`（仅调 STS） | 下载签名 / HEAD 复核 / 迁移写入 / 删除 + 换临时凭证 | ❌ **对桶无任何权限** |
| **STS 临时凭证**（角色） | 前端 `ali-oss`（`src/api/directUpload.ts`） | 浏览器直传 | ✅ 有权限 |

> 排查口诀：**报"桶里对象"相关的 403 → 看长期 AK 的授权；报 CORS / 直传失败 → 看角色与 CORS 配置。**

## 九、自查时顺带确认的两个已知点

1. ⚠️ `application.yml` 的 `toolbox.upload.chunk-size: 5242880`（及 `application-dev.yml` 同名项）—— 分片大小的**唯一来源已是设置表 `chunkSize`**，该配置项**疑无代码读取**（待确认后清理）。
2. ⚠️ `application-prod.yml` 把存储路径写死为 `/data/files`、`/data/chunks`；宿主机部署需用 `TOOLBOX_STORAGE_ROOT` / `TOOLBOX_CHUNK_DIR` 覆盖。
