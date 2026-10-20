# db/ · 数据库脚本说明与部署执行清单

> **位置**：`toolbox-backend/docs/db/`（`backend/sql/` → `src/main/resources/db/` → 现 `docs/db/`，2026-09-29 起文件名同步规范化）。
> **为什么有这份文件**：2026-09-28 联调时因「老库缺 `tb_tool_grant` 表」导致 `GET /api/tools`
> 与 4 个授权接口**全部 500**。根因是 —— **脚本只由人工执行**，而 `schema.sql` 用的是
> `CREATE TABLE IF NOT EXISTS`，**已存在的库不会被补建新表**。特此把执行顺序与两种场景写死于此。

---

## 一、脚本清单

| 脚本 | 作用 | 是否必需 | 幂等手段 |
| --- | --- | --- | --- |
| **`schema.sql`** | **全量**建表：当前**全部 9 张表**（含 M-17 的 `tb_tool_grant`）+ 列默认值 | ✅ **必需**（新库有它就够） | `CREATE TABLE IF NOT EXISTS` |
| `02-seed-base.sql` | 种子数据：6 个基础标签 + 默认设置 `maxFileSize`（**刻意不种 `storageRoot`**） | ⚠️ **可选 / 与初始化重叠** | `INSERT IGNORE` |
| `03-align-schema.sql` | **老库**列默认值 / `ON UPDATE` 对齐 + 零日期清理 | 🕘 **仅老库**（新库跑 `schema.sql` 已含） | `ALTER` 幂等 |
| `04-add-tool-grant.sql` | **老库**增量：补 M-17 的 `tb_tool_grant` 表 | 🕘 **仅老库**（`schema.sql` 已含该表） | `CREATE TABLE IF NOT EXISTS` |
| `05-add-storage-backend.sql` | **老库**增量：补 M-22 的 `tb_tool_version.storage_backend` **列**（文件落点） | 🕘 **仅老库**（`schema.sql` 已含该列） | `information_schema` 探测 + 动态 SQL |
| `06-tool-version-delete.sql` | 工具名称唯一性改「按上传人」+（历史遗留）版本软删除列 | 🕘 **仅老库** | 探测 + 动态 SQL |
| `07-revert-version-soft-delete.sql` | 回退版本软删除：去 `deleted` 列、恢复版本唯一键（M-24） | 🕘 跑过 `06` 的库必做 | 探测 + 动态 SQL |
| `08-tool-type-and-link.sql` | **老库**增量：补 M-25 的 `tb_tool.tool_type` / `link_url` + 索引 | 🕘 **仅老库**（`schema.sql` 已含） | `information_schema` 探测 + 动态 SQL |
| `09-grant-tool-assign.sql` | **数据**：给既有全局管理员（持 `sys:setting`）补发权限点 `tool:assign` | ⚠️ **升到 v1.23 必做**（否则管理员改部门 403） | `NOT EXISTS` 判重 |

**关于命名与编号**：原 `01-init-tables.sql` 已按标准命名为 **`schema.sql`**（全量、幂等）；
`02` / `03` / `04` **保留原编号文件名** —— 它们是被历史交付报告与运维记录直接引用的**增量脚本**，
改名会让那些记录对不上。

> ℹ️ **`02-seed-base.sql` 为什么标"可选"**：`POST /api/system/init`（系统初始化接口）**自己就会**
> 写 6 个默认标签（`SystemService.DEFAULT_TAGS`）与默认设置（`settingService.initDefaults`）。
> 正常部署（空库 → 跑 `schema.sql` → 启动后端 → 前端"系统初始化"页建管理员）**不需要**执行 `02`。
> 它的保留价值 = 运维手工修复字典 / 不走初始化接口时的数据铺底。

**表总数恒为 9**（与代码里的 `@TableName` 实体一一对应）：
`tb_dept` · `tb_user` · `tb_user_permission` · `tb_tag` · `tb_tool` · `tb_tool_version` · `tb_tool_tag` · `tb_setting` · `tb_tool_grant`

> B5（下载稳健性）/ B7（Excel 导入导出）/ B8（单账号单登录）/ B9 / B10 / P11 / P13 **均未引入新表、未改列** ——
> 所以表数仍是 9，不会再冒出第 10 张。
>
> **M-22（阿里云 OSS 对接，契约 v1.19）只给 `tb_tool_version` 加了 1 列 `storage_backend`，未加表** —— 表数仍为 **9**。

---

## 二、场景 A：全新部署（空库）

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS toolbox DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p toolbox < docs/db/schema.sql
# 可选：仅当你不走「系统初始化」页、想先用 SQL 铺字典时
mysql -u root -p toolbox < docs/db/02-seed-base.sql
```

再启动后端 → 走前端「系统初始化」页（`POST /api/system/init`）创建管理员。
> 管理员口令是 BCrypt 摘要，**SQL 里写不了明文**，必须走该接口。

---

## 三、场景 B：老库升级（库已存在 —— 可能缺表 / 缺列）

**按编号从小到大**执行（全部幂等，可重复执行；**DDL 即时生效，跑完无需重启后端**）：

```bash
mysql -u root -p toolbox < docs/db/02-seed-base.sql            # 可选：基础标签字典 + 默认设置
mysql -u root -p toolbox < docs/db/03-align-schema.sql         # 建议：列默认值 / ON UPDATE 对齐（库由旧版脚本建时必跑）
mysql -u root -p toolbox < docs/db/04-add-tool-grant.sql       # 必做（M-17）：补 tb_tool_grant 表
mysql -u root -p toolbox < docs/db/05-add-storage-backend.sql  # 必做（M-22）：补 tb_tool_version.storage_backend 列
mysql -u root -p toolbox < docs/db/06-tool-version-delete.sql  # M-24：名称唯一性改「按上传人」
mysql -u root -p toolbox < docs/db/07-revert-version-soft-delete.sql  # M-24 回退：去 deleted 列、恢复版本唯一键
mysql -u root -p toolbox < docs/db/08-tool-type-and-link.sql   # 必做（M-25）：补 tb_tool.tool_type / link_url
mysql -u root -p toolbox < docs/db/09-grant-tool-assign.sql    # 必做（M-25）：给既有管理员补发 tool:assign（跑完需重新登录）
```

⚠️ **症状特征**：**登录与列表正常，但上传 / 下载 / 可见范围等接口 500** —— 基本就是缺了某一期脚本。
**交付时 `docs/db/` 必须整个目录带上服务器**（只带 `schema.sql` 会导致老库永远升不上去）。

⚠️ **为什么必须手工执行**：本目录脚本**只由人工执行** —— 后端代码零引用，
`POST /api/system/init` 也**不会重建 schema**。`schema.sql` 是 `CREATE TABLE IF NOT EXISTS`，
**对已存在的库不会补建新表**。

**漏执行的症状**：相关接口直接 **500**，响应体 `{"code":1000,"message":"系统异常"}`，
后端日志为 `Table 'toolbox.xxx' doesn't exist`。

---

## 四、部署后校验

```sql
-- 期望：9
SELECT COUNT(*) AS 表数 FROM information_schema.tables WHERE table_schema = 'toolbox';

-- 期望：≥6（基础标签字典）
SELECT COUNT(*) AS 标签数 FROM tb_tag;

-- 期望：每张表的 create_time 都有默认值（老库未对齐时才需要看这个）
SELECT table_name, column_name, column_default FROM information_schema.columns
 WHERE table_schema = 'toolbox' AND column_name = 'create_time';

-- 期望：1 行（storage_backend | local）。**0 行 = 未跑 05-add-storage-backend.sql**（老库升级必查）
SELECT column_name, column_default FROM information_schema.columns
 WHERE table_schema = 'toolbox' AND table_name = 'tb_tool_version' AND column_name = 'storage_backend';

-- 期望：2 行（tool_type | FILE 默认、link_url）。**0 行 = 未跑 08-tool-type-and-link.sql**
SELECT column_name, column_default FROM information_schema.columns
 WHERE table_schema = 'toolbox' AND table_name = 'tb_tool'
   AND column_name IN ('tool_type', 'link_url');

-- 期望：每个全局管理员各 1 行。**0 行 = 未跑 09-grant-tool-assign.sql**（症状：调整归属部门 403）
SELECT u.id, u.username FROM tb_user u
  JOIN tb_user_permission p ON p.user_id = u.id
 WHERE p.permission_code = 'tool:assign';
```

---

## 五、配套配置（与表无关，但部署易漏）

| 项 | 说明 |
| --- | --- |
| **`--spring.profiles.active=dev`** | **启动参数，必带**。配置已拆 `application.yml`（基础）+ `application-dev.yml` + `application-prod.yml`，而**基础文件不含数据源** —— 不带 profile 会直接启动失败 |
| `MYSQL_PASSWORD` | 数据源口令环境变量（`application-dev.yml` 的 `${MYSQL_PASSWORD}`）—— **不设则后端启动失败** |
| `JWT_SECRET` | 令牌签名密钥（`application-dev.yml`），**生产必须覆盖**，否则用的是代码里的开发默认值 |
| `TOOLBOX_STORAGE_ROOT` | 文件存储根路径；也可由管理员在设置页填写（二选一，不可都空） |
| ⚠️ 若改用 prod profile | 变量名不同：`SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` / `TOOLBOX_JWT_SECRET`（详见《部署配置说明》§6.5） |

---

## 六、⚠️ 维护规则（新增表 / 列时照做）

1. **同时改两处**：`schema.sql`（全量，供新库）+ **新增一个 `NN-xxx.sql` 增量脚本**（供老库）。
   `schema.sql` 用 `IF NOT EXISTS`，**对已存在的库不会补建** —— 只改它等于老库永远升不上去。
2. **增量脚本只增不改不删**：新表用 `CREATE TABLE IF NOT EXISTS`；加列用
   `ALTER TABLE ... ADD COLUMN`（先判断存在性或保证可重复执行）。
3. **表总数变化时**，更新本文件 §一 的表清单，并同步 `docs/数据模型.md`。
4. **交付含 schema 变更的阶段**，必须把「增量脚本路径 + 执行命令」写进该阶段交付报告。

### ⚠️ 不要把脚本挪回 `src/main/resources/`（会改变运行行为）

- 本目录 `docs/db/` **不在 classpath 上**，所以这些脚本**永远不会被 Spring Boot 自动执行** —— 这正是我们
  要的（项目既定约定：脚本**只由人工执行**）。**别把脚本挪回 `src/main/resources/`。**
- 一旦把 `schema.sql` 挪到 `src/main/resources/schema.sql`（类路径根），**Spring 会加载它**；
  若再显式设 `spring.sql.init.mode=always`，则每次启动都会执行。
  （另注：Spring Boot 默认 `spring.sql.init.mode=embedded`，对 **MySQL** 这类非内嵌库默认**不执行**，
  所以即便放在根目录也不会自动跑 —— 但那已偏离"人工执行"的约定，**不要依赖**这一点。）
- 测试库结构**不共用**本目录：`src/test/resources/schema-h2.sql`（H2，`MODE=MySQL`）由
  `application-test.yml` 的 `spring.sql.init.schema-locations` 显式加载。改动表结构时**两边都要同步**。

---

## 七、历史文档衔接

本目录 2026-09-29 前位于 `backend/sql/`，且 `01-init-tables.sql` 现名为 `schema.sql`。
以下**历史归档文档**中的路径 / 文件名仍是迁移前的写法，**未回改（归档不改）**：

- `docs/report/B1|B2|B3|B4|B6-交付报告.md`、`docs/plan/B4|B6-*.md`（执行命令示例）
- 其余交付报告未引用脚本路径

**照抄它们里的命令会失败** —— 请以本文件 §二 / §三 的命令为准。
