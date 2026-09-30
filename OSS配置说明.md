# OSS（阿里云对象存储）对接配置说明

> 适用阶段：B13（M-22 / F-17）代码层已交付。本文只讲**部署侧如何真正启用 OSS**，不重复代码实现细节。  
> 读者：负责部署 / 运维的人（含本人）。属内部运维文档，勿外发。

---

## 0. 一句话原则（先读，防错）

1. **凭据与开关全部走服务器环境变量**，绝不进代码 / 仓库 / 前端 / 数据库。
2. **“配了 OSS” ≠ “新上传走 OSS”**：前者是 OSS 凭据，后者是 `TOOLBOX_STORAGE_TYPE` 这个全局开关。两者独立。
3. 环境变量是**启动期绑定**，改完必须 `daemon-reload` + `restart`，不重启不生效。

---

## 1. 涉及的配置项（全部环境变量）

| 变量                      | 作用                                                    | 默认值     | 启用 OSS 时是否必填                   |
| ----------------------- | ----------------------------------------------------- | ------- | ------------------------------ |
| `OSS_ENDPOINT`          | OSS **公网** endpoint（例：`oss-cn-hangzhou.aliyuncs.com`） | 空       | **必填**（且不能用 `-internal`）       |
| `OSS_BUCKET`            | Bucket 名称                                             | 空       | **必填**                         |
| `OSS_ACCESS_KEY_ID`     | AccessKey ID                                          | 空       | **必填**                         |
| `OSS_ACCESS_KEY_SECRET` | AccessKey Secret                                      | 空       | **必填**                         |
| `OSS_URL_TTL_SECONDS`   | 下载签名 URL 有效期（秒）                                       | `3600`  | 可选                             |
| `TOOLBOX_STORAGE_TYPE`  | 新上传写到哪：`local`（默认）/ `oss`                             | `local` | 想让**新上传走 OSS** 时**必填 = `oss`** |

> 对应后端配置：`application.yml` 的 `storage.type` 与 `storage.oss.*`（均 `${ENV:默认值}` 绑定）。

---

## 2. 开关在哪（回答“是不是在部署服务器上”）

是。**全部在部署服务器的 systemd 单元里**，不在代码 / 前端 / 设置页。

- 单元文件：`/etc/systemd/system/tool-box.service`
- 现有 `Environment=` 已有：`MYSQL_PASSWORD` / `JWT_SECRET` / `TOOLBOX_STORAGE_ROOT`
- 启用 OSS 需 **追加** 上面 6 个（至少前 4 个 OSS 凭据 + `TOOLBOX_STORAGE_TYPE=oss`）

改完必须重载并重启：

```bash
sudo systemctl daemon-reload
sudo systemctl restart tool-box
```



---

## 3. 完整启用步骤

1. **阿里云控制台**：开通 OSS → 新建 Bucket（权限选**私有**即可，下载走短时效签名 URL，不需要公共读）→ 记录 **公网** endpoint、Bucket 名、AccessKey ID / Secret。
2. **加环境变量**：把 4 个 `OSS_*` + `TOOLBOX_STORAGE_TYPE=oss` 写进 systemd 单元的 `Environment=`。
3. **重载 + 重启**（见 §2）。
4. **校验 DB 列**：确认 `tb_tool_version` 有 `storage_backend` 列。
   - 用全量 `docs/db/schema.sql` 建的新库已含该列；
   - 若是 B13 之前建的老库，需补跑增量：`mysql -u root -p toolbox < docs/db/05-add-storage-backend.sql`（幂等，可重复跑）。
5. **迁移存量**：调用 `POST /api/tools/migrate-to-oss`（或前端设置页「开始迁移」）。
   - 可重复执行，已迁移的自动跳过；单文件失败不影响其他；**成功即删本地副本**，真正释放磁盘。
6. **验证**：上传一个新文件 → 查该版本 `storage_backend = 'oss'`；下载应走签名直链（浏览器直接访问、不再经后端代理）。老文件迁移后同样如此。

---

## 4. 常见误区（防错，重点看）

- ❌ **“我配了 OSS 凭据 + 跑了迁移，新上传应该自动走 OSS”**  
  → 错。新上传写哪只由 `TOOLBOX_STORAGE_TYPE` 决定，默认 `local`。必须把开关设为 `oss` 并**重启**才生效。迁移只是搬老文件，不切新上传开关。
- ❌ **“endpoint 填内网 / 随便填就行”**  
  → 错。OSS 落点的下载是**浏览器直连签名 URL**，必须**公网** endpoint；用了 `-internal` 内网地址浏览器访问不通。
- ❌ **“迁移完了把存储根路径删了省事”**  
  → 错。本地存储后端仍常驻：万一有 `local` 落点文件（迁移漏网）或以后切回 `local`，路径错位即 404。OSS 时代它进入“**休眠但保留**”状态，不要删。
- ⚠️ **“改了环境变量不重启应该生效吧”**  
  → 不生效。环境变量是启动期绑定，必须 `restart tool-box`。

---

## 5. 回退

- 把 `TOOLBOX_STORAGE_TYPE` 改回 `local` 并重启 → **新上传**回到本地路径。
- 已迁到 OSS 的文件落点仍是 `oss`，其下载继续走 OSS（无需迁回，也不受影响）。
- 若要彻底把 OSS 上的文件迁回本地，需另写反向迁移任务，本期（B13）**未实现**。

---

## 6. 与其他文档的关系

- 部署总流程 / systemd 单元写法：见 `tool-box-bak/docs/部署配置说明.md`（本文是其 OSS 专项补充，建议互相交叉引用）。
- 数据库增量脚本：见 `toolbox-backend/docs/db/`（`05-add-storage-backend.sql` 等）。
- 代码层设计 / 接口定义：见 `tool-box-bak/docs/` 下 B13 的方案 / 计划 / 验收 / 交付报告。
