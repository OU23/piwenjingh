# OSS 授权配置样板（三份 JSON · 逐级路径）

> ⚠️ **2026-10-16 起架构变更**：后端**全部对象操作**（读 / 写 / 删 / 下载签名）都改用 **STS 临时凭证**，
> **不再用长期 AK 直连桶**。角色权限策略至少需含 `oss:PutObject` + **`oss:GetObject`**（见 2️⃣）——
> 少了 `GetObject` 会出现"上传正常、**下载** 403 `bucket acl`"。
> `oss:DeleteObject` **建议一并给上**：**当前 OSS 落点为「逻辑删除」**（不删对象）暂不依赖它，日后恢复物理删除即需。
> 详见 [OSS-STS核实说明](OSS-STS核实说明.md)。浏览器仍通过会话策略限制为仅上传。

> 用途：**一次把三份策略配全**，让「后端访问 OSS（走 STS）」与「浏览器直传」都能跑通。
> 这份同时也是**交给对方（管理员）的标准样板** —— 在他们账号上照配一遍即可。
> 占位符统一说明：`<账号ID>` / `<角色名>` / `<子账号登录名>` / `<bucket>`。

---

## 0. 先纠正一个常见错误（很重要）

**「角色的信任策略」里的 `Principal` 填的是"谁被允许扮演这个角色"，必须填【RAM 用户】，不是角色自身。**

| 写法 | 含义 | 对不对 |
| --- | --- | --- |
| `"RAM": ["acs:ram::<账号ID>:role/<角色名>"]` | 让**这个角色**扮演自己（自引用） | ❌ 无意义 |
| `"RAM": ["acs:ram::<账号ID>:user/<子账号登录名>"]` | 只允许**那个子账号**扮演 | ✅ 正解 |
| `"RAM": ["acs:ram::<账号ID>:root"]` | 账号下**所有** RAM 身份都能扮演（控制台默认值） | ⚠️ 能用但范围过宽 |

另外两个格式点：`RAM` 建议用**数组**；三份 JSON 的 `Version` 一律是字符串 `"1"`。

---

## 1️⃣ 角色的「信任策略」—— 在哪配

`RAM 控制台 → 角色 → 点角色名 → 「信任策略」标签页 → 编辑`
（也可在创建角色时选"信任主体类型 = 云账号"，建好后**默认是 `:root`，回来把它改窄**）

```json
{
  "Version": "1",
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "RAM": ["acs:ram::<账号ID>:user/<子账号登录名>"]
      }
    }
  ]
}
```

> **`<子账号登录名>` 怎么拿（最不容易错）**：`RAM → 用户 → 点那个子账号 → 「基本信息」→ 复制 ARN`，
> 形如 `acs:ram::1046881306537720:user/xxx` —— 把 `user/` **后面那段**填进去即可。

> 🔸 **两处 ARN 别混**（最常被问的一点）：
>
> | 位置 | 填什么 | 长什么样 |
> | --- | --- | --- |
> | 角色的 **ARN**（角色基本信息页） | **角色自己**的身份证 | `acs:ram::<账号ID>:role/<角色名>` |
> | 信任策略的 **Principal** | **被允许扮演它的 RAM 用户** | `acs:ram::<账号ID>:user/<登录名>` |
>
> 角色的 ARN 用在：应用环境变量 `OSS_STS_ROLE_ARN`、给"扮演者"授权时的 `Resource`
> —— **它不该出现在自己的信任策略里**（那样等于"自己允许自己"）。
> 角色是"**可被扮演的身份**"，RAM 用户才是"**去扮演它的那个人**"；按官方要求，**只有 RAM 用户 / RAM 角色能调 `AssumeRole`**（主账号不行）。

> 🔸 **想先跑通、不纠结收窄**：把这个 Principal **保持控制台默认的 `acs:ram::<账号ID>:root`** 也**完全能用** ——
> 差别只是"账号内任何有 `sts:AssumeRole` 的身份都能扮演它"。**收窄到指定用户是加分项，不是必需项。**

---

## 2️⃣ 角色的「权限策略」—— 在哪配

`RAM 控制台 → 角色 → 点角色名 → 「权限管理」标签页 → 新增授权 → 创建权限策略 → 脚本编辑`
（起名如 `toolbox-oss-uploader-policy` → 授权范围选**整个云账号** → 确定）

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
      "Resource": ["acs:oss:*:*:<bucket>/tools/*"]
    }
  ]
}
```

> **前 6 个 = 上传**（浏览器直传用）；**末尾 2 个** = **后端**「读 / 删」用 ——
> `oss:GetObject`：内容读取、下载签名、存在性与大小核实（HeadObject 的权限就是 `oss:GetObject`）—— **必须**；
> `oss:DeleteObject`：OSS 落点文件物理删除 —— **建议给上**（当前为逻辑删除，暂不依赖；恢复物理删除即需）。
> ⚠️ **2026-10-16 前这两个是刻意不配的**（当时后端用长期 AK 直连，角色只管浏览器上传）；
> **现在后端也走 STS**：缺 `GetObject` 会让**下载**报 `AccessDenied ... bucket acl`
> （报错里 `AuthPrincipalType=AssumedRoleUser` 就表示 STS 身份已生效、卡在角色权限）。
> 资源仍只到 `tools/*`；**浏览器拿到的临时凭证另有会话策略限制（仅 6 个上传动作），读不到、删不掉**。
> ⚠️ 角色「基本信息」里的**最大会话时间**保持默认 3600 即可（应用默认也用 3600）。

---

## 3️⃣ 子账号（RAM 用户）的「权限策略」—— 在哪配

`RAM 控制台 → 用户 → 点该子账号 → 「权限管理」标签页 → 新增授权 → 创建权限策略 → 脚本编辑`
（起名如 `toolbox-user-policy` → 确定 → 授权给该用户）

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
      "Resource": ["acs:oss:*:*:<bucket>/*"]
    },
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "acs:ram::<账号ID>:role/<角色名>"
    }
  ]
}
```

> **这一段是"一把 AK 跑全程"的关键**：
> ① 第一条 = **长期 AK 自身的对象级权限**（保留作探针/兜底排查用；**2026-10-16 后后端不再直连**，
>    真正的读/写/删权限落在**角色**上，见 2️⃣）；
> ② 第二条 = 允许它**扮演上面的角色**（后端与浏览器都靠它换临时凭证）。
> ⚠️ 少了 ② → 报 `You are not authorized to do this action. You should be authorized by RAM.`

---

## 4. 本次自测环境的实际值（对照用）

| 项 | 值 |
| --- | --- |
| 账号 ID | `1046881306537720` |
| Bucket | `toolbox-test`（华北2 → `oss-cn-beijing.aliyuncs.com`） |
| 角色名 | 你截图里用的是 `toolbox-test`（**与桶名撞了，不影响功能**，但排查时容易看混；若还没建完建议改成 `oss-uploader-role`） |
| 子账号登录名 | `______`（从「用户 → 该用户 → 基本信息 → ARN」复制） |

---

## 5. 配完后的验收（3 步）

1. **换子账号 AK** 并设角色 ARN，重跑探针：

```powershell
$env:OSS_ACCESS_KEY_ID="<子账号AK>"
$env:OSS_ACCESS_KEY_SECRET="<子账号Secret>"
$env:OSS_STS_ROLE_ARN="acs:ram::<账号ID>:role/<角色名>"
python oss-probe.py
```

期望：`[AK] AssumeRole ✅`、`[AK] Put/Get/Delete ✅`、`[STS] Put ✅ / Get ✅`
（③ 的 `Delete`：按本样板（已含 `oss:DeleteObject`）= ✅；未给 = ❌ ——
 ⚠️ 2026-10-16 起 OSS 落点为**逻辑删除**，`Delete` 为 ❌ **不影响功能**，但建议补上，日后恢复物理删除即需）

2. **Bucket CORS（直传必需，且最容易漏）**

   路径：`OSS 控制台 → Bucket 列表 → 点该桶 → 左侧「数据安全 → 跨域设置」→ 创建规则`

   | 项 | 值 |
   | --- | --- |
   | 来源 | `http://localhost:5173`（可再加 `http://127.0.0.1:5173`） |
   | 允许 Methods | **PUT / POST / GET / HEAD / DELETE 全勾** |
   | 允许 Headers | `*` |
   | **暴露 Headers** | **`ETag`**（★ 分片上传靠它串片，漏了必失败） |
   | 缓存时间 | `600` |

   然后加 `TOOLBOX_STORAGE_TYPE=oss` → 重启后端。
   > ⚠️ CORS 管的是"**哪个网页能直连这个桶**"（Origin = 协议 + 域名 + **端口**，端口不能省），**与"IP 白名单/放行 IP"完全无关** —— 按 IP 限制是另一处功能（「访问控制 → 防盗链 / IP 黑白名单」），本项目不需要。

3. 上传文件 → **F12 → Network** 应看到请求**直接打向 OSS 域名**；该版本落点 `storage_backend='oss'`。

> 🔎 **没配 CORS 的典型报错**（一眼识别）：
> `XHR error (req "error"), PUT https://<bucket>.<endpoint>/... -1 (connected: false, keepalive socket: false) headers: {}`
> —— `connected: false` = 浏览器在**连接阶段就被拦**，九成是 **CORS 没配、或来源里没含当前页面的 Origin**。
