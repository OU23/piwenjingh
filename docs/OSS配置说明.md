# OSS（阿里云对象存储）对接配置说明

> 适用阶段：**B13**（M-22：下载直链 + 存量迁移）与 **B14**（M-23：上传直传）。本文只讲**部署侧如何真正启用 OSS**，不重复代码实现细节。  
> 读者：负责部署 / 运维的人（含本人）。属**内部运维文档**，勿外发。

---

## 0. 一句话原则（先读，防错）

1. **凭据与开关全部走服务器环境变量**，绝不进代码 / 仓库 / 前端 / 数据库。
2. **“配了 OSS” ≠ “新上传走 OSS”**：前者是 OSS 凭据，后者是 `TOOLBOX_STORAGE_TYPE` 这个全局开关。两者独立。
3. **“新上传走 OSS” ≠ “上传直传”**：直传（B14）还要在平台建 **RAM 角色 + STS 授权 + Bucket CORS**，少一样都跑不起来。
4. 环境变量是**启动期绑定**，改完必须**重建容器**才生效——`docker compose restart` **不够**（见 §3）。
5. 两条上传链路并存：`type=oss` → **浏览器直传**（需 STS）；`type=local` → **经服务器中转**（不需要 STS）。

> **只做下载直链 / 存量迁移（B13）**：做完 §2.1 / §2.2 即可，§2.3 ~ §2.5 可跳过。  
> **要让上传也直传（B14）**：§2.1 ~ §2.6 全部要做。

---

## 1. 涉及的配置项（全部环境变量）

| 变量                          | 作用                                                        | 默认值          | 何时必填                            |
| --------------------------- | --------------------------------------------------------- | ------------ | ------------------------------- |
| `OSS_ENDPOINT`              | OSS **公网** endpoint。**两种填法**：纯域名 `oss-cn-beijing.aliyuncs.com`，或带协议 `https://oss-cn-beijing.aliyuncs.com`。⚠️ **不带协议时阿里云 SDK 默认用 HTTP** ⇒ 生成的签名直链是 `http://`（浏览器会再做一次 http→https 自动升级）；**推荐带 `https://`**，直链固定 https。**不要带路径、不能用 `-internal`** | 空            | 用 OSS 时**必填**（且不能用 `-internal`）  |
| `OSS_BUCKET`                | Bucket 名称                                                 | 空            | 用 OSS 时**必填**                   |
| `OSS_ACCESS_KEY_ID`         | 后端 RAM 用户的 AccessKey ID                                    | 空            | 用 OSS 时**必填**                   |
| `OSS_ACCESS_KEY_SECRET`     | 后端 RAM 用户的 AccessKey Secret                                | 空            | 用 OSS 时**必填**                   |
| `OSS_URL_TTL_SECONDS`       | 下载签名 URL 有效期（秒）                                            | `3600`       | 可选                              |
| **`OSS_STS_ROLE_ARN`**      | 上传专用 RAM 角色 ARN（B14）                                       | 空            | **`type=oss` 时必填**（否则**启动即失败**） |
| **`OSS_STS_DURATION_SECONDS`** | 临时凭证有效期（秒），区间 900 ~ 43200                                 | `3600`       | 可选（越界按 3600 处理）                 |
| **`OSS_STS_ENDPOINT`**      | **STS 服务地址**（⚠️ **不是** OSS 地址，别和 `OSS_ENDPOINT` 混）。默认 `sts.aliyuncs.com`（中心地址，全局可用）；也可用**与桶同地域**的区域地址，如 `sts.cn-beijing.aliyuncs.com`。**不带 `https://`** | `sts.aliyuncs.com` | 一般无需改（用区域地址也行）                           |
| `TOOLBOX_STORAGE_TYPE`      | 新上传写到哪：`local`（默认）/ `oss`                                  | `local`      | 想让**新上传走 OSS** 时**必填 = `oss`**  |

> 对应后端配置：`application.yml` 的 `toolbox.storage.*` 与 `toolbox.oss.*`（均 `${ENV:默认值}` 绑定）。

---

## 2. 平台侧配置（阿里云控制台，**按顺序做**）

前置：已登录阿里云控制台（右上角「控制台」）。

### 2.1 开通 OSS + 建 Bucket

1. 控制台搜索 **「对象存储 OSS」** → 进入 → 首次需点 **开通**（免费开通，按量计费）。
2. 左侧 **Bucket 列表 → 创建 Bucket**，按下表填：

**逐项填法（对照控制台「创建 Bucket」表单，含默认值）**：

| 字段 | 建议值 / 说明 |
| --- | --- |
| Bucket 名称 | **全局唯一**；仅小写字母 / 数字 / 短横线，3~63 位，首尾须为字母或数字（如 `toolbox-files-2026`） |
| 地域 | 选离服务器/用户近的（如华东1-杭州）。⚠️ **创建后不可改**，且决定 endpoint 与延迟 |
| 存储类型 | **标准存储**（要归档降冷以后用生命周期加，别在这里选） |
| **存储冗余类型** | **选「本地冗余存储」**（控制台默认是「同城冗余存储（推荐）」）★ 见下注 1 |
| 版本控制 | **关闭**（开了会让生命周期与计费复杂化） |
| **阻止公共访问** | **保持「已开通」**（默认即开）★ 见下注 2 |
| 读写权限 | **私有**（下载走短时效签名 URL，**不要**公共读） |
| 服务端加密 | 建议选 **「OSS 完全托管」**（AES-256，不加收费用）；**KMS** 方式需另开通 KMS 并按调用计费 |
| 实时日志查询 | 可开（免费提供最近 7 天访问日志查询，排查方便；日志含请求信息，注意留存策略） |
| 定时备份 | **不开**（走混合云备份 HBR，需额外开通与付费） |
| 所属资源组 | 可留默认；仅用于费用/权限归类，不影响功能 |

> **注 1 · 存储冗余类型（新增，涉及钱 + 不可逆）**
> - 控制台**默认选中「同城冗余存储（ZRS）」**；我上一轮只说"标准存储"，漏了这一项。
> - 差异：**本地冗余（LRS）**= 单可用区内多设备冗余，持久性 **99.999999999%（11 个 9）**、可用性 99.99%；**同城冗余（ZRS）**= 同城多可用区（≥3 AZ），持久性 **99.9999999999%（12 个 9）**、可用性 99.995%。
> - 价格（标准存储）：**LRS `0.12 元/GB/月`** vs **ZRS `0.15 元/GB/月`** → ZRS **贵 25%**。
> - 可逆性：**LRS → ZRS 可升级；ZRS → LRS 不支持**（单向）。所以"先选便宜的"没有后顾之忧。
> - **建议：内部工具仓库选「本地冗余存储（LRS）」** —— 11 个 9 的持久性对内网工具仓库绰绰有余，可用区级故障概率极低，直接省 25% 存储费；真要提高容灾，日后一键升 ZRS 即可。
> - 例外：若你的组织有**合规/容灾要求（要求多 AZ 容灾）**，则选 ZRS。

> **注 2 · 阻止公共访问（新增，注意它与我们**不冲突**）**
> - 控制台建桶时**默认「已开通」**，**保持开通即可**——这正是我们想要的（把桶锁死为私有）。
> - 它拦的是**匿名请求**：把桶设成公共读/公共写、或 Bucket Policy 里出现 `Principal: "*"` 之类的公开授权。开启后**忽略一切公共权限**，强制桶私有。
> - **为什么不影响我们**：OSS 鉴权分两条路 —— **签名请求**（URL 带 `Signature`，走签名校验 + RAM Policy）与**匿名请求**（走 Bucket Policy / ACL 判是否公开）。我们的**下载签名 URL 属于「签名请求」**，上传直传用的是 **STS 临时凭证签名**，**都不走匿名那条路**，因此不受影响。
> - ⚠️ 副作用：开启后**无法把桶改成「公共读」**（会提示先关闭该功能）。我们全程私有，无妨。
> - ⚠️ **联调排查点**：万一改动后下载报 `403 AccessDenied`，**第一个查它**——虽然按鉴权机制不该拦，但阿里云文档里确有"启用阻止公共访问则无法生成公网可访问签名地址"的表述（出现在 MaxCompute Object Table 场景，属另一产品的限制），留作排查首位。

> 另：控制台新旧版字段名略有差异（如「**基础设置 → 跨域设置**」vs「**数据安全 → 跨域设置**」），以页面实际路径为准；截图里未展开的项按上表处理即可。

3. 建好后在 **Bucket 概览**页记下：**Bucket 名** + **外网访问 endpoint**。

> ⚠️ **必须用外网 endpoint**（形如 `oss-cn-hangzhou.aliyuncs.com`），**不要** `-internal` 内网地址 —— OSS 落点的下载是**浏览器直连签名 URL**，内网地址浏览器访问不通。

### 2.2 建「后端」RAM 用户 + AccessKey（长期 AK）+ 策略 A

1. 控制台搜索 **「访问控制 RAM」** → 左侧 **身份管理 → 用户 → 创建用户**：
   - 登录名称（如 `toolbox-backend`）
   - 访问方式**只勾「OpenAPI 调用访问」**（我们不用它登录控制台，少一个风险面）
2. 创建后**立即保存 AccessKey ID / Secret** —— 三条硬事实：
   - **AccessKey Secret 只在创建那一刻显示一次**，之后控制台**不提供查询**（想再拿只能新建一个）；
   - **每个 RAM 用户最多 2 个 AccessKey**（已达上限就得先禁用/删除旧的）；
   - 这一步由**主账号管理员**在该用户下操作；**RAM 子账号看不到、也拿不到别人的 AK**。
3. 回到该用户 → **添加权限 → 创建权限策略 → 脚本编辑** → 粘贴下面的 **策略 A** → 起名（如 `toolbox-backend-policy`）→ 确定 → 把它授权给该用户。

**策略 A —— 后端 RAM 用户**（把 `<Bucket名>`、`<主账号ID>` 换成实际值）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:GetObject",
        "oss:DeleteObject",
        "oss:InitiateMultipartUpload",
        "oss:UploadPart",
        "oss:CompleteMultipartUpload",
        "oss:AbortMultipartUpload",
        "oss:ListParts"
      ],
      "Resource": ["acs:oss:*:*:<Bucket名>/*"]
    },
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "acs:ram::<主账号ID>:role/oss-uploader-role"
    }
  ]
}
```

**每一条为什么需要（别随手删）**：

| Action | 用途 |
| --- | --- |
| `oss:GetObject` | ★ **签名直链下载**：官方明确「预签名 URL 无需权限即可生成，但**只有具备 `oss:GetObject` 的签名者**，第三方才能通过该 URL 下载成功」；另用于 `complete` 的 HEAD 复核 |
| `oss:PutObject` | B13 存量迁移（后端中转上传） |
| `oss:DeleteObject` | `complete` 发现「对象大小与声明不符」时删对象回收空间；OSS 落点工具彻底删除 |
| 4 个 multipart + `ListParts` | 直传中断清理 / 断点续传（配合角色策略） |
| `sts:AssumeRole` | 允许后端 RAM 用户扮演上传角色（**只做 B13 可删这条**） |

> **主账号 ID 获取**（形如 `1234567890123456`）：控制台右上角头像 → 「账号信息」里可见；
> **若登录的是 RAM 用户（子账号）**，头像菜单里可能看不到，改用更可靠的办法 ——
> **RAM 控制台 → 用户 → 点你自己的登录名 → 「基本信息」里的 ARN**，形如
> `acs:ram::1234567890123456:user/你的登录名`，**中间那串数字就是主账号 ID**。
> 若暂时只做 B13：策略 A 保留 OSS 那段、删掉第二条即可。

> ### ⚠️ 若你登录的是 **RAM 用户（子账号）** —— 先读这段
>
> **技术步骤一个都不用改**：所有 RAM 资源（用户、角色）都归属**主账号**，所以 §2.3 信任策略里的
> `<主账号ID>` **仍填主账号的 ID，不是这个 RAM 用户的名字**。
>
> 但**"能不能做"取决于该 RAM 用户有没有 RAM 管理权限**，请先自查：
> 控制台搜「**访问控制 RAM**」→ 进入，看左侧「用户 / 角色」下的
> **「创建用户」「创建角色」按钮是否可用**；若点了报「没有权限 / Forbidden」，说明**没有** RAM 管理权限。
>
> | 你的情况 | 怎么办 |
> | --- | --- |
> | 有 RAM 管理权限（能建用户/角色） | 照本文 §2.2 / §2.3 自己做完即可 |
> | **没有** RAM 管理权限 | **必须请主账号管理员**（或让管理员给你授权）做 §2.2 / §2.3；做完把 **AccessKey ID/Secret + 角色 ARN** 交给你填环境变量。**别硬试**，会反复报权限错误 |
>
> **为什么必须有"建角色"的能力**：B14 的**上传直传**靠 STS 临时凭证，而临时凭证只能通过
> **扮演一个已存在的 RAM 角色**获得 —— **建角色是纯 RAM 操作**，OSS 权限再大也变不出来。
> 也就是说：
> - **没有 RAM 管理权限时，B13（下载直链 / 存量迁移）可以用你自己的 AK 先跑**（降级方案，见下），
>   但 **B14（上传直传）一定跑不起来**，必须等管理员建好角色。
>
> **⚠️ 降级方案（仅救 B13，且不推荐）**：实在拿不到 RAM 管理权限、又只想先跑通下载/迁移，
> 可**临时用你这个 RAM 用户自己的 AK** 作为 `OSS_ACCESS_KEY_ID/SECRET`（跳过"新建后端用户"）。
> 代价是**"人用的凭据"和"程序用的凭据"合并成一把**，一旦轮换/离职就会打断服务，属安全反模式；
> **等直传上线前一定要换成专用的后端 RAM 用户**。

### 2.3 建「上传专用」RAM 角色（B14 直传用）

1. 控制台 → **访问控制 RAM → 身份管理 → 角色 → 创建角色**
   - 信任主体类型：**云账号**（不是「阿里云服务」！）
   - 信任主体名称：**当前云账号**
   - 下一步 → 角色名称填 **`oss-uploader-role`** → 完成
2. 进入该角色详情 → **「信任策略」** 页签 → 编辑 → 替换为下面内容（把主体**收窄到那个后端 RAM 用户**，比默认的 root 更严）：

```json
{
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "RAM": ["acs:ram::<主账号ID>:user/<后端RAM用户名>"]
      }
    }
  ],
  "Version": "1"
}
```

3. 回到角色 → **「权限策略」→ 新增授权 → 自定义策略 → 脚本编辑** → 粘贴 **策略 B** → 起名（如 `oss-uploader-role-policy`）→ 授权。

**策略 B —— 角色权限（只给“写”）**：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject",
        "oss:InitiateMultipartUpload",
        "oss:UploadPart",
        "oss:CompleteMultipartUpload",
        "oss:AbortMultipartUpload",
        "oss:ListParts",
        "oss:GetObject",
        "oss:DeleteObject"
      ],
      "Resource": ["acs:oss:*:*:<Bucket名>/tools/*"]
    }
  ]
}
```

- **前 6 个 = 上传**（浏览器直传用）；**末尾 2 个**（`oss:GetObject` / `oss:DeleteObject`）= **后端**「读 / 删」用
  （内容读取、下载签名、存在性与大小核实要 `Get`；OSS 落点文件彻底删除要 `Delete`）。
  ⚠️ **2026-10-16 起后端也走 STS 临时凭证**，这两个**必须给** —— 只给上传会出现"**上传正常、下载/删除 403 `bucket acl`**"。
  （浏览器拿到的临时凭证另有**独立会话策略**限制为仅 6 个上传动作，所以**读不到、删不掉**任何文件。）
- ⚠️ 资源只到 **`tools/*`**（正是本系统对象键的前缀），别的目录碰不到。
- 角色详情「**基本信息**」里可调 **最大会话时间**：默认 **3600 秒**。若你想把 `OSS_STS_DURATION_SECONDS` 设成大于 3600（如 7200），**必须先把这里调到 ≥ 该值**（上限 43200），否则 `AssumeRole` 会失败。用默认 3600 则不用动。

4. 记下角色 ARN：**`acs:ram::<主账号ID>:role/oss-uploader-role`**（角色详情页可直接复制）。

### 2.4 Bucket CORS（⚠️ 最容易漏；不配**直传第一个请求就被拦**）

1. OSS 控制台 → 目标 Bucket → 左侧 **「数据安全 → 跨域设置」**（旧版路径为「权限管理 → 跨域设置」）→ **创建规则**。
2. 按下表填：

| 项 | 值 |
| --- | --- |
| 来源（Origin） | 每行一个**完整来源 = 协议 + 域名 + 端口**（不要带路径、不要带末尾 `/`）。例：服务器 `http://192.168.35.115`（nginx 走 80，浏览器会省略默认端口）；**本地开发 `http://localhost:5173`** ⚠️ **端口不能省**。多个环境一行一个；**测试期也可只写一行 `*`**（允许任意来源，上线前务必收窄） |
| 允许 Methods | 勾 **GET、PUT、POST、HEAD、DELETE**（分片直传用 PUT/POST，预检用 OPTIONS 由 OSS 自动处理） |
| 允许 Headers | `*` |
| **暴露 Headers** | **`ETag`**、`x-oss-request-id` ← **务必加 `ETag`** |
| 缓存时间 | `600`（**别留 0**：0 = 不缓存预检结果，浏览器每次上传前都要多发一轮 `OPTIONS` 预检，白慢一拍） |
| 返回 Vary: Origin | 可勾（我们没接 CDN，勾不勾差别不大；勾上更"正确"，多来源/多规则时能避免缓存串味） |

> **★ 关于「来源」里的 `*` —— 别把它当"IP 白名单"理解**
> 来源（Origin）管的是 **"哪个网站页面的 JS 被允许直连本 Bucket"**，取值形如 `http://站点域名:端口`，
> 它是**浏览器的跨域安全机制**，**与"哪些 IP 能访问 OSS"完全无关**。
> 填 `*` = **允许任意来源的网页直连本 Bucket**（页面也写了：每行最多一个通配符 `*`）。
> - ✅ **测试期填 `*` 完全可行**，能最快确认"直传链路通不通"；
> - ⚠️ **上线前务必收窄**为站点地址（如 `http://192.168.35.115`），否则任意网站都能拿用户浏览器当跳板直传/下载
>   （凭据本身有 STS 限权 + 桶是私有，风险有限，但没必要留这个口子）；
> - 真要按 **IP** 限制访问是**另一处功能**（「访问控制 → 防盗链 / IP 黑白名单」），**本项目不需要**。
>
> **⚠️ 最容易踩的一点：Origin 里带端口。** 浏览器发的 `Origin` 是 `协议://域名:端口`，
> 所以 **`http://192.168.34.21` 匹配不到 `http://192.168.34.21:5173`**。
> 用 `localhost` 访问本地前端时，Origin 是 **`http://localhost:5173`**（Vite 默认端口，见 `vite.config.ts`），
> **必须显式加进来源列表**，否则本地直传第一个请求就被 CORS 拦掉。
> 本地测试建议的来源行：`http://localhost:5173`、`http://127.0.0.1:5173`（用 IP 访问再加 `http://192.168.34.21:5173`）。

> ⚠️ **暴露 Headers 必须含 `ETag`**：OSS 分片上传要靠 `ETag` 串起每个分片，不暴露会直接报
> `Please set the etag of expose-headers in OSS` 而**上传失败**。这是本项目实测会踩的服务端配置坑，
> 也是"测试失败"时**第一顺位**要查的项（比"来源"更容易致错）。
> ⚠️ **规则保存后不会立刻生效**：控制台提示"设置成功后 **15 分钟内**生效"——刚配完马上测可能失败，**等一两分钟再试**。
> ⚠️ 多条规则**按自上而下顺序命中第一条**；有冲突就把多余规则删掉。
> 若站点将来加了 CDN 域名，CORS 要在 CDN 侧再配一次（OSS 侧只对直连 OSS 域名的请求生效）。

### 2.5 Bucket 生命周期：自动清「未完成分片」（省计费）

1. OSS 控制台 → 目标 Bucket → 左侧 **「数据管理 → 生命周期」**（旧版在「基础设置」下方）→ **创建规则**。
2. 规则内容：
   - 状态：**启用**
   - 作用范围：**按前缀**（留空 = 整个 Bucket；或填 `tools/`）
   - 动作：**只勾「清理碎片 / 未完成分片上传（AbortMultipartUpload）」→ 1 天**
   - ❌ **不要**勾「文件过期删除」「转低频/归档」！那会连**正式文件**一起删掉或降冷。
3. ⚠️ **生命周期规则不会立即生效**：创建后 OSS 需 **24 小时内加载**规则，之后在固定周期（通常次日 UTC 0 点、**北京时间 8 点后**）扫描执行。
   所以中断的上传**不会马上被清**，要等到第二天 —— 这不代表没配成功。

### 2.6 汇总：接下来要写进服务器环境变量的 7 个值

| 值 | 从哪里拿 |
| --- | --- |
| `OSS_ENDPOINT` | Bucket 概览页的「外网访问 endpoint」 |
| `OSS_BUCKET` | 你起的 Bucket 名 |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | §2.2 建的 RAM 用户 AccessKey |
| `OSS_STS_ROLE_ARN` | §2.3 角色详情页复制的 ARN |
| `OSS_STS_DURATION_SECONDS` | 不填即默认 3600（须 ≤ 角色的最大会话时间） |
| `OSS_STS_ENDPOINT` | 不填即默认 `sts.aliyuncs.com` |
| `TOOLBOX_STORAGE_TYPE` | 想直传就填 `oss` |

---

## 3. 开关在哪（回答“是不是在部署服务器上”）

是。**全部在部署服务器的 Docker Compose 配置里**，不在代码 / 前端 / 设置页。

- 配置文件：`toolbox-backend/deploy/docker-compose.yml` → `services.toolbox-backend.environment:`
- 现有 `environment:` 已有：`SPRING_PROFILES_ACTIVE=prod`、`SPRING_DATASOURCE_*`、`TOOLBOX_JWT_SECRET` 等
- 启用 OSS 需 **追加** §1 表里的 OSS 相关项（至少 4 个凭据 + `TOOLBOX_STORAGE_TYPE=oss`；要直传再加 `OSS_STS_ROLE_ARN`）

改完**必须重建容器**（`docker compose restart` **不生效**）：

```bash
cd <项目根>/toolbox-backend
./deploy/scripts/restart.sh     # = stop + build-up（--build + up -d）
```

> 📄 逐项怎么加、可照抄的完整 `environment:` 片段、验证与坑位：见 **`OSS-服务器部署配置说明.md`**。

---

## 4. 服务器侧完整启用步骤

1. **平台侧**：按 §2 完成 Bucket / RAM 用户 / 角色 / CORS / 生命周期。
2. **加环境变量**：把 §2.6 的项写进 `deploy/docker-compose.yml` 的 `environment:`（逐项操作见 `OSS-服务器部署配置说明.md`）。
3. **重建容器**（见 §3）。⚠️ 若 `TOOLBOX_STORAGE_TYPE=oss` 而 `OSS_STS_ROLE_ARN` 为空 → **后端启动即失败**（这是防"带病启动"的预期行为，不是 bug）。
4. **校验 DB 列**：确认 `tb_tool_version` 有 `storage_backend` 列。
   - 用全量 `docs/db/schema.sql` 建的新库已含该列；
   - 若是 B13 之前建的老库，需补跑增量：`mysql -u root -p toolbox < docs/db/05-add-storage-backend.sql`（幂等，可重复跑）。
5. **验证直传开关生效**（前端上传页会自动按模式分流）：
   - 浏览器打开 `GET /api/upload/mode`（带登录令牌），应返回 `"mode":"direct"` 与 `"partSize":20971520`；
   - 若返回 `"mode":"server"`，说明 `TOOLBOX_STORAGE_TYPE` 没生效（多半忘了重建容器）。
6. **迁移存量**（可选但推荐）：调用 `POST /api/tools/migrate-to-oss`（或前端设置页「开始迁移」）。
   - 可重复执行，已迁移的自动跳过；单文件失败不影响其他；**成功即删本地副本**，真正释放磁盘。
7. **验证**：上传一个新文件 → 查该版本 `storage_backend = 'oss'`；下载应走签名直链（浏览器直接访问、不再经后端代理）。老文件迁移后同样如此。

---

## 4.5 先在本地验证（可选，强烈推荐）

> **为什么值得**：本机代码最新、改配置成本为零。先在本地把"OSS 通不通"验掉，再动服务器 ——
> 避免"改了服务器配置 + 重启 + 才发现某处不对"。

### ① 先分清"能测哪一半"

代码里两道启动校验**都只在 `TOOLBOX_STORAGE_TYPE=oss` 时才触发**
（`OssStorageService.validateConfiguration` / `OssStsCredentialIssuer.validateConfiguration`），
所以 **`type` 保持 `local` 时，缺 OSS / 缺 STS 配置都不影响启动**。据此可拆成两半：

| 要验证的 | 需要的配置 | 现在能不能测 |
| --- | --- | --- |
| **B13**：存量迁移到 OSS + OSS 落点下载直链 | 只要 **4 个凭据**（`OSS_ENDPOINT` / `OSS_BUCKET` / `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET`）；`TOOLBOX_STORAGE_TYPE` **保持 `local`** | ✅ 拿到 AK 就能测 |
| **B14**：上传直传 | 还要 **`OSS_STS_ROLE_ARN`**（角色已建）**且** `TOOLBOX_STORAGE_TYPE=oss` | ❌ 必须先有角色 |

**①′ 无 RAM 管理权限时：能不能先借用「你自己账号的 AK」？（仅本机自测）**

- **取法**：控制台**右上角头像 → 「AccessKey 管理」→ 选「RAM 用户 AccessKey」→ 「开始使用子用户 AccessKey」**，
  跳到 RAM 控制台用户页 → 点**你自己**的登录名 → 「认证管理 → 用户 AccessKey → **创建 AccessKey**」。
- ⚠️ **能不能用取决于账号安全策略**：需"阿里云账号允许 RAM 用户自主管理 AccessKey"；
  否则同样报 `NoPermission` —— 那就只能等管理员（见 §8）。
- ⚠️ **只在本机自测**：人机共用同一把 AK 是安全反模式，**别把它配到服务器上长期跑**；测完可在控制台**禁用**该 AK。
- ✅ **换正式凭据不用改代码**：等管理员把专用 AK + 角色 ARN 给你，**只换环境变量的值**即可。
- ❌ 它**测不了上传直传**（那需要角色，见上表 B14）。

### ② ⚠️ 凭据放**环境变量**，别写进 `application-dev.yml`

`src/main/resources/application-*.yml` **是入库文件**（`.gitignore` 未忽略它们）——
把 AK/Secret 写进去 = **把密钥提交进 Git**。用环境变量（PowerShell 会话内即生效）：

```powershell
$env:OSS_ENDPOINT="oss-cn-beijing.aliyuncs.com"
$env:OSS_BUCKET="toolbox-file-upload"
$env:OSS_ACCESS_KEY_ID="<AK>"
$env:OSS_ACCESS_KEY_SECRET="<Secret>"
# ↓ 等角色就绪、要测直传时再加这两行（缺角色时加了会启动失败，见 §3）
# $env:TOOLBOX_STORAGE_TYPE="oss"
# $env:OSS_STS_ROLE_ARN="acs:ram::<主账号ID>:role/oss-uploader-role"

mvn spring-boot:run "-Dspring-boot.run.profiles=dev"   # ⚠️ PowerShell 下这个引号不能省，见 §5 末条
```

> 也可在 IDE（IDEA / VS Code）的运行配置里设环境变量，效果相同。
> 更省事的写法（**绕开 PowerShell 的引号坑**）：直接 `$env:SPRING_PROFILES_ACTIVE="dev"`，再 `mvn spring-boot:run`。
> 按项目约定：**服务由你本人启动**，AI 不代跑。

### ③ 看什么算通

| 步骤 | 预期 |
| --- | --- |
| 1. 上传文件（此时 `type=local`） | 走**原链路**（分片经服务器 → 落本地磁盘）；该版本 `storage_backend = 'local'` |
| 2. 设置页点「开始迁移」 | 本地文件搬到 OSS；**成功即删本地副本**；单文件失败不影响其他 |
| 3. 下载刚迁移的文件 | 走**签名直链**（浏览器直连 OSS 域名，不再经后端代理） |
| 4. 切 `type=oss` 后再上传（需角色） | **F12 → Network**：请求应直接打向 `oss-cn-beijing.aliyuncs.com`，**不经过** `localhost:5173/api/upload/chunk`；该版本 `storage_backend = 'oss'` |
| 5. `GET /api/upload/mode`（带登录令牌） | `type=oss` 时返回 `"mode":"direct"` 与 `"partSize":20971520`（20MB） |

> ⚠️ 两个前提：① **CORS 来源必须包含 `http://localhost:5173`**（Origin 含端口，见 §2.4），否则直传第一个请求就被浏览器拦；
> ② 第 2 步迁移**会真的删掉本地副本**——跑在本地测试数据上没问题，但别拿"还想要的文件"试。

---

## 4.6 对照实验：用「自己的桶 + 自己的 AK」验证失败与代码无关（2026-10-15）

> **适用场景**：对方（部署方 / 管理员）说"配置没问题、demo 都跑通了，是你们应用的问题"。
> **做法**：换一个**我们自己完全有权限**的桶与 AK，跑**同一套应用、同一套代码**。
> 变量只有一个 —— **凭据的权限** —— 因此能干净地证明问题出在**授权范围**，而不是应用代码。
> （旁证：2026-10-13 14:50 用「有权限的 AK」时，迁移 / 下载 / 删除都是通的；换成现在这把就全挂。）

**阶段 1 · 不建角色，先复验「之前 403 挂掉的那三件事」**

| 步骤 | 做什么 | 期望 |
| --- | --- | --- |
| 1 | 终端设 **4 个变量**（自己的 AK / Secret / endpoint / bucket）；**刻意不设** `TOOLBOX_STORAGE_TYPE` | 保持 `type=local` ⇒ 启动**不需要角色** |
| 2 | 先跑 `python oss-probe.py` | `[AK] Put / Get / Delete` **全部 ✅** ← 这就是与"那把没权限的 AK"的**关键差异** |
| 3 | 起后端 + 前端 → 设置页点「开始迁移」 | 迁移成功；DB 里 `storage_backend = 'oss'` |
| 4 | 下载**刚迁移的**文件 | **F12 → Network**：直链打向 `<bucket>.<endpoint>`（不再经 `localhost:5173/api/...`） |
| 5 | （可选）删除一个 OSS 落点的工具 | 删除成功 |

⇒ 第 3~5 步**正是之前全部 403 的三件事**。在有权限的 AK 下它们全部恢复 = **证明与我们的代码无关**。

**阶段 2 · 建角色，验证「上传直传」**

> ⚠️ **一条官方硬前提（会决定你用哪把 AK）**：
> 「**`AssumeRole` 接口不允许阿里云账号（主账号）调用，只能由 RAM 用户或 RAM 角色调用**」（阿里云 RAM 常见问题 / API 文档原文）。
> ⇒ 所以**直传必须用一个 RAM 用户的 AK**（不能沿用主账号 AK）；该 RAM 用户还需具备 `sts:AssumeRole` 权限。

| 步骤 | 做什么 |
| --- | --- |
| 6 | **用一个 RAM 用户（子账号）**，给它挂**两样**：① 桶权限（对象级 8 个动作，资源 `acs:oss:*:*:<bucket>/*` —— 主要供探针 / 兜底排查；2026-10-16 后后端不再直连，对象操作走下面的角色）；② **`sts:AssumeRole`**（资源指向下面那个角色 ARN）。记下它的 AK / Secret |
| 7 | RAM → 创建角色：信任主体类型选**云账号** → 建好后**把「信任策略」改窄为指定的那个 RAM 用户**：`{"RAM": ["acs:ram::<账号ID>:user/<子账号登录名>"]}` —— 控制台默认给的是账号根 `:root`（= 账号下所有身份都能扮演），**收窄这一步就是"更安全"的做法**，也是管理员建议的正解 |
| 8 | 给该角色挂策略：`acs:oss:*:*:<bucket>/tools/*` 上的 **6 个上传动作 + `oss:GetObject` + `oss:DeleteObject`**（`PutObject` / `InitiateMultipartUpload` / `UploadPart` / `CompleteMultipartUpload` / `AbortMultipartUpload` / `ListParts` / **`GetObject`** / **`DeleteObject`**）—— ⚠️ **2026-10-16 起后端也走 STS，`Get`/`Delete` 必须给**（旧版刻意不给，那是"后端用长期 AK 直连"时代的做法） |
| 9 | 环境变量**换成子账号 AK**，再加 `OSS_STS_ROLE_ARN=acs:ram::<账号ID>:role/<角色名>` + `TOOLBOX_STORAGE_TYPE=oss`；CORS 里加 `http://localhost:5173` → 重启 |
| 10 | 上传文件 → F12 应看到请求**直接打向 OSS 域名**；该版本落点 `storage_backend='oss'` |

> 换成子账号 AK 后**重跑一次探针**，期望：① `AssumeRole` ✅、② `Put / Get / Delete` ✅、③ 角色 **`Put` ✅ / `Get` ✅ / `Delete` ✅**
> （若 ③ 的 `Delete` 为 ❌ ⇒ 角色权限漏了 `oss:DeleteObject`，后端将删不掉 OSS 落点文件）。

**⚠️ 为什么信任主体要收窄到 RAM 用户（而不是账号根）**

| 写法 | 谁能扮演 |
| --- | --- |
| `acs:ram::<账号ID>:root` | 账号下**所有** RAM 身份（谁拿到 `sts:AssumeRole` 都能扮演） |
| `acs:ram::<账号ID>:user/<子账号>` | **只有该用户** |

**可以这样回复对方（结论模板）**：

> 我们用**自己的、权限完整的**桶与 AK 跑了同一套代码：迁移 / 下载 / 上传**全部成功**（附探针输出 + 截图）；
> 换成给我们的那把 AK，同一个动作**立刻 403**。两次唯一的差别是**凭据的权限** ——
> 所以缺的是"**给该 RAM 用户（或该桶）的 OSS 授权**"，不是应用代码。

> ⚠️ **主账号 AK 安全提醒**：主账号 AccessKey 等同于**超级管理员**，**仅限本地临时测试**；
> 测完请到控制台**禁用或删除**它；生产环境务必换成专用 RAM 用户。

---

## 5. 常见误区（防错，重点看）

- ❌ **“AK 已经有了，就够了吧”**
  → 不够。**直传要的是"角色"**：AK 只让后端能读写桶，而**临时凭证必须通过"扮演角色"（`AssumeRole`）换取**。角色没建 / 没配 → 上传报「对象存储暂不可用」（2006）。
- ❌ **“用哪把 AK 都一样”** ★ **最容易踩，务必核对**
  → **不一样。角色的「信任策略主体」必须正好是"这把 AK 所属的那个 RAM 用户"**。
  例：角色信任的是 `user/toolbox-backend`，你却把**自己账号**的 AK 配上去 → `AssumeRole` 失败，
  现象同样是上传报「对象存储暂不可用」，后端日志会给 `[STS] AssumeRole 失败 code=AccessDenied`。
- ❌ **“把 `sts.*.aliyuncs.com` 填到 `OSS_ENDPOINT` 里”**
  → 不行。`oss-*.aliyuncs.com` 是 **OSS 服务地址**、`sts*.aliyuncs.com` 是 **STS 服务地址**，
  分别对应 `OSS_ENDPOINT` 与 `OSS_STS_ENDPOINT` 两个变量。填错会导致**所有** OSS 操作失败。
- ❌ **“PowerShell 里 `mvn spring-boot:run -Dspring-boot.run.profiles=dev` 报 `Unknown lifecycle phase ".run.profiles=dev"`，是不是 OSS 配置坏了？”**
  → **不是。这是 PowerShell 把 `-D…` 参数拆成两段导致的命令行错误**，Maven 在解析参数阶段就退出了，
  **根本没进 Spring**（所以与任何环境变量无关）。
  修法任选一个：① `mvn spring-boot:run "-Dspring-boot.run.profiles=dev"`（**加引号**）；
  ② `$env:SPRING_PROFILES_ACTIVE="dev"` 后再 `mvn spring-boot:run`；③ 换 cmd 执行。
  > 反证：若是 OSS 配置问题，报的是 Java 异常（`存储后端已设为 oss（直传），但 STS 配置缺失…`），不是 Maven 的 `LifecyclePhaseNotFoundException`。

- ❌ **“我配了 OSS 凭据 + 跑了迁移，新上传应该自动走 OSS”**  
  → 错。新上传写哪只由 `TOOLBOX_STORAGE_TYPE` 决定，默认 `local`。必须把开关设为 `oss` 并**重启**才生效。迁移只是搬老文件，不切新上传开关。
- ❌ **“endpoint 填内网 / 随便填就行”**  
  → 错。OSS 落点的下载是**浏览器直连签名 URL**，必须**公网** endpoint；用了 `-internal` 内网地址浏览器访问不通。
- ❌ **“CORS 先不配，等报错再说”**  
  → 直传模式下**浏览器第一个请求就会被 CORS 拦掉**（上传根本发不出去）。
- ❌ **“CORS 配了就行，暴露 Headers 随便”**  
  → 必须含 **`ETag`**，否则分片上传报 `Please set the etag of expose-headers in OSS` 直接失败。
- ❌ **“角色最大会话时间不用管”**  
  → `OSS_STS_DURATION_SECONDS` **不能大于**角色的「最大会话时间」（默认 3600），否则 `AssumeRole` 失败、直传报「对象存储暂不可用」。
- ❌ **“迁移完了把存储根路径删了省事”**  
  → 错。本地存储后端仍常驻：万一有 `local` 落点文件（迁移漏网）或以后切回 `local`，路径错位即 404。OSS 时代它进入“**休眠但保留**”状态，不要删。
- ⚠️ **“改了环境变量重启一下就行吧”**  
  → `docker compose restart` **不生效**。环境变量在容器**创建时**注入，必须**重建**：`./deploy/scripts/restart.sh`。
- ⚠️ **“生命周期设了，中断的分片应该马上被清”**  
  → 不会。规则创建后需 **24 小时内加载**，之后按固定周期（北京时 8 点后）扫描。

---

## 6. 回退

- 把 `TOOLBOX_STORAGE_TYPE` 改回 `local` 并**重建容器** → **新上传**回到本地路径（原「检查 → 传分片 → 合并」链路，不依赖 STS）。
- 已迁到 OSS 的文件落点仍是 `oss`，其下载继续走 OSS（无需迁回，也不受影响）。
- 若要彻底把 OSS 上的文件迁回本地，需另写反向迁移任务，本期（B13 / B14）**未实现**。

---

## 7. 与其他文档的关系

- 部署总流程 / 容器编排：见 `toolbox-backend/deploy/README.md`（本文是其 OSS 专项补充）。
- **服务器部署 OSS 逐项操作（Docker）**：见 `OSS-服务器部署配置说明.md`（本文 §3 / §4 的展开版，含可照抄的 `environment:` 片段与坑位）。
- 数据库增量脚本：见 `toolbox-backend/docs/db/`（`05-add-storage-backend.sql` 等）。
- 代码层设计 / 接口定义 / 部署前置清单：见 `tool-box-bak/docs/` 下
  `OSS对接（需求与实现方案）.md`（B13）、`OSS直传（需求与实现方案）.md`（B14，§5.7 为平台侧清单原文）、
  `接口契约.md` v1.20、以及 B13 / B14 的 plan / acceptance / 交付报告。

---

## 8. 附录：交给主账号管理员的申请清单

> 📄 **可直接转发给管理员的版本已单独成文**：**`OSS-RAM配置说明.md`**（自包含、含逐步截图级操作与已填好值的 JSON）。
> 本节是"浓缩速查版"，内容与那份一致；**要发人就发那份**，别发本文件（本文件含我们自己的运维口径）。

> **什么时候用这一节**：你登录的是 **RAM 子账号**，且在「访问控制 RAM → 用户 → 创建用户」时报
> `NoPermission`（授权操作 `ram:ListUsers`）——
> **报 `ram:ListUsers` 就说明这个子账号没有 RAM 管理权限**（真有管理员权限不可能连"列用户"都不许）。
> 同理「创建角色」也会被拒。此时 **§2.2 与 §2.3 必须由主账号管理员执行**。
>
> ⚠️ **注意**：能"点进"创建用户页面 **不代表有权限** —— 控制台前端路由不做鉴权，真正校验在**点「确定」提交时**。
> 想确认的话，随便填个名字点一次「确定」即可：若仍报 `NoPermission`（这次的授权操作会是 `ram:CreateUser`），
> 就是确定没有权限，不必再试。

**已知参数**（直接抄给管理员）

| 项 | 值 |
| --- | --- |
| 主账号 ID | 见报错框「**所属账号**」那一串 16 位数字（本报错为 `1064446027897230`，请在页面上再核对一次——创建用户页里登录名称输入框右侧的后缀 `<账号ID>.onaliyun.com` 与它应当一致） |
| Bucket 名 | `toolbox-file-upload` |
| 地域 | 华北2（北京） → endpoint `oss-cn-beijing.aliyuncs.com` |
| 后端 RAM 用户名 | 建议 `toolbox-backend` |
| 上传角色名 | 建议 `oss-uploader-role` |

### 需要管理员做两件事

**① 建「后端专用 RAM 用户」**（照 §2.2）：只勾 **OpenAPI 调用访问**；
授上 **策略 A**（把 `<Bucket名>` = `toolbox-file-upload`、`<主账号ID>` = 上表值填好）。
👉 **交付物：AccessKey ID + AccessKey Secret**

**② 建「上传专用 RAM 角色」**（照 §2.3）：信任主体 = **云账号（当前账号）**，
信任策略收窄到 `toolbox-backend`，权限挂 **策略 B**（写 + 读 + 删，资源限 `tools/*`）。
👉 **交付物：角色 ARN**（`acs:ram::<主账号ID>:role/oss-uploader-role`）

> **给管理员的安全说明（可直接转发）**：
> 这两个凭据**只用于服务器环境变量**，不进代码库、不入数据库、不出现在前端；
> 角色权限限 **`<bucket>/tools/*`**，其中**读 / 删动作仅由后端使用**（浏览器拿到的临时凭证另有**独立会话策略**，只含 6 个上传动作），
> 即"就算浏览器凭证泄露，也读不到、删不掉任何文件"。
> ⚠️ **2026-10-16 起**：后端**全部对象操作**都改用 STS 临时凭证 ⇒ 角色的权限策略**必须含 `oss:GetObject` + `oss:DeleteObject`**，
> 否则会出现"上传正常、但下载 / 删除报 403 `bucket acl`"。

### 有两条路可选（管理员二选一）

| | 做法 | 适合 |
| --- | --- | --- |
| **A（推荐）** | 管理员**代做** ①②，把 **AK/Secret + 角色 ARN** 交给你 | 你不想再要 RAM 权限，最省心、权限最小暴露 |
| **B** | 管理员给你这个子账号**临时授予** `AliyunRAMFullAccess`，你做完 §2.2/§2.3 后**请管理员收回** | 你想自己点完，少一轮来回 |

### 你自己就能先做的（不必等管理员）

只要该子账号有 OSS 权限（**它能建 Bucket，说明有**），Bucket 级配置你自己就能配：
**§2.4 跨域设置（CORS，务必含暴露 `ETag`）** 与 **§2.5 生命周期（未完成分片 1 天）**。
先把这两项做掉，等管理员把 AK/角色 ARN 给你，就能直接进 §3 服务器侧。
⚠️ 但在拿到**角色 ARN**之前，**绝对不要把 `TOOLBOX_STORAGE_TYPE` 设成 `oss`** —— 后端会启动即失败。

---

## 9. 排错速查：后端日志 → 原因 → 找谁

> 直传/OSS 出问题**不会 500**（E-02 隔离为业务码 `2006`），所以**要去后端日志里找真因**。
> 前端只会看到「对象存储暂不可用」，`HTTP` 可能仍是 200。

| 日志关键字 | 含义 | 怎么办 |
| --- | --- | --- |
| `[STS] AssumeRole 失败 code=NoPermission` | **调用者没资格扮演该角色**：① 该 RAM 用户没被授 `sts:AssumeRole`（如缺 `AliyunSTSAssumeRoleAccess`）；② 或**角色的信任策略不包含这个用户** | 核对「用户权限」+「角色信任策略主体」**是否同一个用户** |
| `[STS] AssumeRole 失败 code=AccessDenied` | 同上（另一种表现），或该用户/角色被绑了 **IP 限制（`acs:SourceIp`）** | 同上；并检查有没有 IP 白名单条件 |
| `[STS] AssumeRole 失败 code=InvalidAccessKeyId` | `OSS_ACCESS_KEY_ID` 写错 / 该 AK 已被禁用 | 核对 AK，或重新生成 |
| `[STS] AssumeRole 失败 code=InvalidTimeStamp` / 时间相关 | 服务器系统时间偏差过大 | 校准服务器时间（NTP） |
| `[OSS] 探测失败：You have no right to access this object because of bucket acl` | **该 AK 没有这个桶的读权限**（`oss:GetObject`）。出现位置：下载签发时的存在性探测（`DownloadUrlService` → `exists()`）、直传完成时的 HEAD 复核 | 把**「策略 A」授给该 AK 所属用户** |
| `[OSS] 读取失败 / 删除失败：…AccessDenied` | 同上，缺对应动作权限 | 同上 |
| `[OSS] …：The bucket you access does not belong to you` | `OSS_BUCKET` 写错，或 AK 属于**另一个账号** | 核对桶名 / AK 所属账号 |
| `UnknownHostException` / 连接超时 | `OSS_ENDPOINT` 填错（如误填 `sts.*` 或 `-internal`） | 改成 `oss-cn-beijing.aliyuncs.com` |

### 9.1 「删了工具，但 OSS 里对象还在」—— 三步定位

> **先看设计口径**：彻底删除时，**删文件失败只记日志、不抛异常**（`ToolPurgeService.deleteFileQuietly`，
> 取舍是"**宁可留文件，不可留脏记录**"）⇒ "**记录没了、对象还在**"是**允许出现的中间态**，
> 靠**孤儿清理**兜底（⚠️ 孤儿自动对账**本期明确未实现**，现阶段只能人工删）。

| # | 查什么 | 结论 |
| --- | --- | --- |
| 1 | 后端日志搜 **`TOOL_PURGE`**，看 `target=tool:X **versions:N**` | `N>0` = 尝试删了 N 个文件；**`N=0` = 压根没尝试**（当时版本记录已不在） |
| 2 | 搜 **`TOOL_PURGE_FILE`**，有没有 `result=FAIL` | **有 FAIL** = 那次 OSS 删除失败被吞掉 ⇒ 对象成**孤儿**，需人工删（**不是代码 bug**） |
| 3 | OSS 控制台：你看到的是**「文件」**页签里的对象，还是**「碎片」（未完成分片）**？ | **碎片 `deleteObject` 管不到** —— 只能靠 **生命周期规则**清理；上传中断/失败留下的都是碎片 |

**一分钟判定是不是 bug**：上传一个小文件 → 记下它的 key → 软删 → 清空回收站，然后看
① 日志有没有 `TOOL_PURGE_FILE ... FAIL`？② 那个 **key** 在 OSS 里消失了吗？

- **没有 FAIL、key 也消失了** ⇒ 代码正常（之前的残留在历史/碎片里）；
- **没有 FAIL、key 却还在** ⇒ **那才是 bug** —— 把日志发我，立刻查。

> 顺带：**未完成分片的生命周期规则（1 天）** 就是为了自动清"碎片"，**新桶也要配**。

**一句话诊断法**：
- **只有 `AssumeRole` 报错** → 角色/信任策略/`sts:AssumeRole` 的问题；
- **只有 `[OSS]` 报错** → AK 缺桶权限（RAM 策略 A，或**自助的 Bucket 策略**，见 `OSS-RAM配置说明.md` 附 F）；
- **两个都报** → **这把 AK 所属用户几乎没有任何权限**（最常见：用户建好了、**策略还没挂上去**），或该用户被 IP 限制。

---

## 10. 独立探针脚本 `oss-probe.py`（不经过应用，直接验阿里云侧）

> 用途：**把"我们代码的问题"和"阿里云侧配置的问题"一刀切开**。
> 当上传/下载报「对象存储暂不可用」，但你不确定该怪谁时，先跑它 —— 它不依赖我们的应用、不依赖前端，只用标准库直连阿里云。

```bash
# 在 toolbox-backend 目录下；**同一个终端**里已设好 OSS_* 环境变量
python oss-probe.py
```

**它检查四件事**：

| 步骤 | 用谁的凭据 | 证明什么 |
| --- | --- | --- |
| ⓪ `GetCallerIdentity` | **长期 AK** | **这把 AK 到底是谁**（账号 ID / 用户 ARN / **RAM 用户 UID**）—— 桶策略的 `Principal` 就填这个 UID |
| ① `STS AssumeRole` | **长期 AK** | AK 是否有效、有没有 `sts:AssumeRole`、角色信任策略是否包含它 |
| ② `Put / Get / Delete` | **长期 AK** | 这把 AK **自身**对桶的对象级权限。⚠️ 2026-10-16 后后端**不再用它直连**，此步仅作排查/兜底 |
| ③ `Put / Get / Delete` | **STS 临时凭证** | **角色的权限** —— 后端（读 / 写 / 删）与浏览器直传**都**用它。**当前最该看的一步**：`Get` / `Delete` 为 ❌ ⇒ 角色缺 `oss:GetObject` / `oss:DeleteObject`，会直接导致下载/删除 403 |

**读什么、写什么**：
- 配置**全部从环境变量读**（`OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` / `OSS_BUCKET` / `OSS_ENDPOINT` / `OSS_STS_ROLE_ARN` / `OSS_STS_ENDPOINT`），**脚本内不存任何密钥**，输出里 AK 也是打码的；
- 会往桶里写一个几十字节的探针对象（默认前缀 `tools/_diagnose/`），**测完立即删除**；加 `--no-cleanup` 可保留以便排查。
- **还能自助配桶策略**：`--set-policy-for <RAM用户UID> [--yes]` —— 用 AK 直连 OSS API 写桶策略，
  **不需要登控制台、也不需要 RAM 管理员**（详见 `OSS-RAM配置说明.md` 附 F.6）。

**典型输出解读**：

| ① 结果 | ② 结果 | ③ 结果 | 结论 |
| --- | --- | --- | --- |
| ✅ | ❌ | ✅ | ★ **最常见**：后端 AK 缺桶权限；角色侧正常（所以浏览器能传上去）→ 补权限**二选一**：① RAM 策略（需管理员，§2.2 策略 A）；② **Bucket 策略（自助，不需要管理员）** —— 用 ⓪ 查到的 `UserId` 作 `Principal`（脚本会把 JSON 直接打印出来），详见 `OSS-RAM配置说明.md` **附 F** |
| ✅ | ✅ | ✅ | 阿里云侧全通 → 该去查我们的代码/配置了 |
| ❌ | — | — | AK 无效 / 缺 `sts:AssumeRole` / 角色信任策略不含它 |
| ✅ | ❌ | ❌ | 整个账号侧都没挂权限（用户建好了但策略没挂） |

> 退出码：`0` 全通过 / `1` 有失败 / `2` 配置缺失（缺环境变量时**不会发起任何网络请求**）。
> 需要本机有 Python 3（`python --version` 能出版本即可）。
