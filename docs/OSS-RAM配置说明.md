# OSS-RAM 配置说明

> ⚠️ **2026-10-16 架构变更（转发时请一并转达）**：后端**全部对象操作**（读 / 写 / 删 / 下载签名）已改为走
> **STS 临时凭据**（扮演角色），**不再用长期 AK 直连桶**。
> ⇒ **要配的核心权限落在【角色】上**：角色权限策略**至少需含 `oss:PutObject` + `oss:GetObject`**
> （缺 `GetObject` → **下载**报 403 `bucket acl`，上传仍正常）。
> `oss:DeleteObject` **建议一并给上**：当前 OSS 落点为**逻辑删除**（不删对象）暂不依赖它。
> 下文"长期 AK 需要桶权限"的相关部分，是改造前为"后端直连"申请权限时的记录（保留供追溯）。
> **按现架构，只需保证：① 长期 AK 能调 `sts:AssumeRole`；② 角色具备上述对象动作。**

> **这份文档是给"主账号管理员"看的**，可直接转发。全程在 **RAM 控制台**操作，约 5~10 分钟，**只需做一次**。  
> 对应本文档仓库中的 `OSS配置说明.md` §2.2 / §2.3

---

## 背景（30 秒）

我们有一个内部工具系统 **tool-box**，需要把文件存进 OSS 桶 **`toolbox-file-upload`**（华北2·北京）。

系统需要两样东西：

1. **一个专用 RAM 用户** —— 系统后端用它读写这个桶（上传历史文件、签发下载链接、删除文件）；
2. **一个上传专用 RAM 角色** —— 让**用户在浏览器里把文件直传进 OSS**（不经过我们的服务器，省带宽也更快）。

**注意**：系统**不会**拿到你们的主账号凭据，也**不会**要求桶设为公共读。

---

## 0. 先确认这些参数（下面的 JSON 已按它们写好，可直接用）

| 项             | 值（**2026-10-14 探针实测，以本表为准**）                |
| ------------- | --------------------------------------- |
| 主账号 ID        | `1064446027897230`                      |
| Bucket 名称     | `toolbox-file-upload`                   |
| 地域 / endpoint | 华北2（北京） / `oss-cn-beijing.aliyuncs.com` |
| 后端 RAM 用户     | **`app-server-sts-user`**（UID `204645391880405440`） |
| 上传角色名         | **`oss-uploader-role`**                 |

**这些值怎么来的（可复现）**：
- 主账号 ID / 用户 UID / 用户 ARN → `python oss-probe.py` 第 ⓪ 步 `GetCallerIdentity` **直接打印**；
- 角色名与 endpoint → 取自应用**实际运行的环境变量**（`OSS_STS_ROLE_ARN` / `OSS_ENDPOINT`）。

> ⚠️ **本文早前记录的 `1064446027897230`（主账号 ID）、`oss-uploader-role`（角色名）、
> `toolbox-backend`（用户登录名）与实测不符，均已按实测更正。**
> 且**当前状态不需要再新建任何用户或角色** —— 用户与角色都已存在，
> **只缺"给 `app-server-sts-user` 挂上桶权限"**：请看 **附 E**（管理员挂 RAM 策略）或 **附 F**（自助 Bucket 策略）。

---

## 0.5 先选做法：两种，选一种

|          | **方案 A · 推荐（正式）**                 | **方案 B · 最快（可先跑起来）**                        |
| -------- | --------------------------------- | ------------------------------------------- |
| 程序用哪把 AK | 新建**专用** RAM 用户 `toolbox-backend` | **复用它现有的那把 AK**（已有，无需新建）                    |
| 管理员要做    | 动作 **1 + 2 + 3**                  | 只需 **动作 2 + 3**（+ 确认现有用户有 `sts:AssumeRole`） |
| 角色信任策略主体 | `…:user/app-server-sts-user`          | `…:user/<它现在那个 RAM 用户的登录名称>`                |
| 优点       | 人机分离：程序凭据独立、可单独轮换，人员变动不影响服务       | 立刻可用，不用新建用户                                 |
| 缺点       | 需要多做一个动作                          | **"人用的凭据"与"程序用的凭据"是同一把**，长期不推荐              |

> **怎么选**：
>
> - 只想**尽快验证直传能不能跑通** → 走 **方案 B**（把动作 2 信任策略的主体改成现有 RAM 用户名即可）。
> - 准备**正式上线** → 走 **方案 A**，把程序凭据与人的账号分开。

**若走方案 B，还要补一件事**：确认现有那个 RAM 用户**具备 `sts:AssumeRole` 权限**。  
若不确定，请管理员给它加一条（或直接附加系统策略 `AliyunSTSAssumeRoleAccess`）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "acs:ram::1064446027897230:role/oss-uploader-role"
    }
  ]
}
```

---

## 动作 1／3：建「后端专用 RAM 用户」并授权（**走方案 B 可跳过本动作**）

**路径**：控制台搜「**访问控制 RAM**」→ 左侧「**身份管理 → 用户**」→ 右上「**创建用户**」

1. **登录名称**：`toolbox-backend`
2. **显示名称**：随意（如 `tool-box 后端`）
3. **访问方式**：**只勾「OpenAPI 调用访问」**  
   ⚠️ **不要**勾"控制台访问" —— 程序不需要登录网页，少一个风险面。
4. 点「确定」并完成安全验证。
5. **立即保存系统生成的 AccessKey ID 与 AccessKey Secret**  
   ⚠️ Secret **只显示这一次**，关闭后无法再查询 → **这就是要交付给我们的第 1、2 项**。

**接着给它授权**：

1. 回到用户列表 → 点 `toolbox-backend` 行的「**添加权限**」。
2. 在面板里选「**创建权限策略**」→ 选「**脚本编辑**」→ 粘贴下面 **策略 A**（已填好值）→ 策略名称填  
   `toolbox-backend-policy` → 确定。
3. 回到「添加权限」面板：**授权范围**选「**整个云账号**」→ 勾选刚建的 `toolbox-backend-policy` → 确定。

### 策略 A —— 后端用户的权限

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
      "Resource": ["acs:oss:*:*:toolbox-file-upload/*"]
    },
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "acs:ram::1064446027897230:role/oss-uploader-role"
    }
  ]
}
```

**每条为什么需要（请不要随手删）**：

| Action                                                                                                          | 用途                                                                    |
| --------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `oss:GetObject`                                                                                                 | ★ **下载**：官方规定「预签名 URL 谁签发，谁就必须持有 `GetObject`，第三方才能下载成功」；另用于上传完成后的对象校验 |
| `oss:PutObject`                                                                                                 | 把历史存量文件搬到 OSS                                                         |
| `oss:DeleteObject`                                                                                              | 删除文件、上传校验失败时回收对象                                                      |
| `oss:InitiateMultipartUpload` / `UploadPart` / `CompleteMultipartUpload` / `AbortMultipartUpload` / `ListParts` | 大文件分片上传的中断清理与断点续传                                                     |
| `sts:AssumeRole`                                                                                                | 允许该用户扮演下面的"上传角色"（**动作 2/3 依赖它**）                                      |

> ⚠️ **请不要给这个用户绑定 IP 白名单**（策略里的 `acs:SourceIp` 条件）。  
> 原因：**下载签名直链的"身份"就是这个用户**，一旦限定了 IP，**最终用户在自己电脑上点下载会返回 403**。

---

## 动作 2／3：建「上传专用 RAM 角色」

**路径**：RAM 控制台 → 左侧「**身份管理 → 角色**」→「**创建角色**」

1. **信任主体类型**：选「**云账号**」（控制台可能显示为「阿里云账号」；⚠️ **不是**"阿里云服务"）
2. **信任主体名称**：选「**当前云账号**」
3. 下一步 → **角色名称**填入 **`oss-uploader-role`** → 完成
4. 进入该角色 → 页签「**信任策略**」→ 编辑 → 替换为下面内容 → 保存

```json
{
  "Version": "1",
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Effect": "Allow",
      "Principal": {
        "RAM": ["acs:ram::1064446027897230:user/app-server-sts-user"]
      }
    }
  ]
}
```

> 💡 **上面 JSON 里的主体（`Principal`）要填「会来调用 `AssumeRole` 的那个身份」**：
> - 走**方案 A** → 填 `acs:ram::1064446027897230:user/app-server-sts-user`（上面已填好）；
> - 走**方案 B** → 改成 `acs:ram::1064446027897230:user/<现有 RAM 用户的登录名称>`。
>   「登录名称」= RAM 控制台「用户」列表里 **"登录名称"那一列**（**不含** `@` 后缀）；
>   **拿不准就把该用户 ARN 里 `user/` 后面那段直接抄进来**。
>
> 💡 这一步的意义：**只有被写进信任策略的那个身份能扮演该角色**。
> 其他 RAM 用户（哪怕知道角色 ARN）也拿不到临时凭证。

---

## 动作 3／3：给角色挂「只写」权限，并交付 ARN

1. 仍在角色详情页 → 页签「**权限策略**」→「**新增授权**」：  
   **授权范围**选「整个云账号」→「**创建权限策略**」→「**脚本编辑**」→ 粘贴 **策略 B** →  
   策略名称填 `oss-uploader-role-policy` → 确定 → 授权。
2. 回到角色「**基本信息**」页 → **复制角色 ARN**  
   （形如 `acs:ram::1064446027897230:role/oss-uploader-role`）  
   → **这就是要交付给我们的第 3 项**。

### 策略 B —— 角色权限（刻意"只能写、只写这个目录"）

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
      "Resource": ["acs:oss:*:*:toolbox-file-upload/tools/*"]
    }
  ]
}
```

**两点说明**：

- **前 6 个 = 上传；末尾 2 个**（`oss:GetObject` / `oss:DeleteObject`）= **后端**「读 / 删」用
  （内容读取、下载签名、存在性与大小核实要 `Get`；OSS 落点文件物理删除要 `Delete`）。
  ⚠️ **2026-10-16 起后端也走 STS 临时凭证**：`GetObject` **必须给**（否则**下载**报 403 `bucket acl`）；
  `DeleteObject` **建议给上**（当前为**逻辑删除**，暂不依赖；恢复物理删除即需）。
  浏览器拿到的临时凭证另有**独立会话策略**（仅 6 个上传动作），所以**读不到、也删不掉**任何文件。
- **资源只到 `tools/*`**  
  → 正是本系统存放文件的目录，桶里的其它数据碰不到。

> ℹ️ 角色的「**最大会话时间**」默认 **3600 秒**，我们默认就用 3600，**这一项不需要改**。



---

## 最后：请交付给我们（视 §0.5 所选的方案而定）

> **方案 A** → 下面三项都要；**方案 B** → **只需第 3 项（角色 ARN）**，AK 我们已有、无需再给。

| # | 内容                                                 | 从哪来                      |
| - | -------------------------------------------------- | ------------------------ |
| 1 | **AccessKey ID**                                   | 动作 1 第 5 步               |
| 2 | **AccessKey Secret**                               | 动作 1 第 5 步（只显示一次，请当时保存好） |
| 3 | **角色 ARN**（`acs:ram::…:role/oss-uploader-role`） | 动作 3 第 2 步               |

（Bucket 名称与 endpoint 我们已有，无需提供。）

> ⚠️ **走方案 B 时请额外确认**：现有那个 RAM 用户已具备 `sts:AssumeRole` 权限
> （即 §0.5 末尾那条策略）—— 否则系统拿不到临时凭证，直传仍不可用。

---

## 附 A：安全说明（供安全/审阅同事参考）

- 上述凭据**只用于我们服务器的启动环境变量**，**不会**写进代码仓库、数据库或任何前端页面；**浏览器里永远拿不到长期 AccessKey**。
- 浏览器直传使用的是**每次临时签发、默认 1 小时有效**的临时凭证（STS Token），并且被上面的「策略 B」**限死在 `tools/*` 的写操作**上：即使临时凭证泄露，也**读不到、删不掉**任何文件。
- 我们**不做任何"公共读"配置**：桶保持**私有**，且「阻止公共访问」**保持开启**（该开关只拦匿名访问，我们的**签名请求不受影响**）。
- 下载走**短时效签名 URL**（默认 1 小时），且 `GetObject` 权限只在后端用户身上；签名 URL 不会被写入日志。
- 将来如需回收：**禁用该 AccessKey** 或**删除该角色**即可，我们的系统随之失效，不留残留。

---

## 附 B：如果只能先做一部分

> **关键点**：让「新上传进 OSS」的**必要条件**是 **动作 2 + 3（建角色 + 授只写策略）**；
> **动作 1（建专用用户）只决定"用谁的凭据"，不影响能不能直传**。
> 所以哪怕只做动作 2+3（把信任策略主体写成现有 RAM 用户），**直传立刻就能用**。

| 已完成              | 效果                                                                    |
| ---------------- | --------------------------------------------------------------------- |
| 只做**动作 1**       | 系统的「下载 / 存量迁移到 OSS / 删除」可用（这部分我们目前用自己的凭据已跑通）；**但"新上传"仍会写到我们的服务器本地磁盘** |
| **动作 1 + 2 + 3** | **全部可用**：新上传由用户浏览器**直传 OSS**，不再经过我们的服务器                               |

---

## 附 C：常见疑问

- **为什么不用主账号的 AccessKey？**  
  官方明确：`AssumeRole`（换取临时凭证的接口）**不能使用阿里云账号（主账号）调用**，只能用 **RAM 用户 / RAM 角色**。  
  所以必须有一个 RAM 用户来做这件事 —— 这就是动作 1 存在的原因。
- **为什么要单独建一个用户，不用我们现有的账号？**  
  最小权限 + 隔离：这个用户只能碰这一个桶，离职/回收互不影响；也便于日后单独轮换密钥。
- **为什么建完角色还要授"只写"策略？**  
  角色本身只是"身份"，没有权限就是空的。策略 B 才是真正限定它能做什么（只能写、只写 `tools/*`）。

---

## 附 D：排障反馈模板（报错时可直接发给管理员）

> 当后端日志同时出现下面两类错误时，说明**我们正在用的那把 AccessKey 所属的 RAM 用户没有被授予权限**
> （最常见：用户建好了、**策略还没挂上去**，或策略挂给了别的用户）。把下面这段直接发给管理员即可。

```text
你好，OSS 直传的配置还差最后一步，麻烦帮忙确认一下。

【现象】我们后端日志里有两类报错：
1) 调 STS 换取临时凭证时：
   [STS] AssumeRole 失败 code=NoPermission
2) 访问桶时：
   You have no right to access this object because of bucket acl（AccessDenied）

【我们的判断】这两个错同时出现，说明我们现在配置的那把 AccessKey
所属的 RAM 用户还没有被授予权限（用户可能建好了，但策略还没挂上）。

【麻烦你确认/处理三件事】
1. 确认这把 AccessKey 属于哪个 RAM 用户 —— 请告知该用户的「登录名称」。
   （我们只有 AK 字符串，看不到它归属谁；如果新建了 toolbox-backend，请确认给我们的就是它名下的。）
   AK ID 前几位：LTAI______（请按实际填写）
2. 给这个用户挂上两条权限：
   a) 桶权限：自定义策略 toolbox-backend-policy（含 PutObject/GetObject/DeleteObject
      + 4 个 Multipart 动作 + ListParts，资源 acs:oss:*:*:toolbox-file-upload/*）
   b) 角色扮演权限：sts:AssumeRole，资源 acs:ram::666xxxx:role/oss-uploader-role
      （也可直接附加系统策略 AliyunSTSAssumeRoleAccess）
3. 确认角色 oss-uploader-role 的「信任策略」主体
   就是这个用户：acs:ram::666xxxx:user/<该用户登录名称>

【另外请顺便确认】
- 该用户没有被绑 IP 白名单（策略里没有 acs:SourceIp 条件）——
  否则我们访问会被拒绝。
- 如果有多个用户/多把 AK，请告诉我们该用哪一把。

改完我们这边不用改代码，重启后端即可生效。谢谢！
```

---

## 附 E：当前状态（2026-10-14 探针实测）—— 只差「桶策略」这一步

用 `oss-probe.py` 分别拿**长期 AK** 与**临时凭证**打了 STS + OSS 各一次，结果：

| 检查项 | 用谁的凭据 | 结果 |
| --- | --- | --- |
| STS `AssumeRole` | 长期 AK | ✅ 成功 |
| `PutObject` | **长期 AK** | ❌ **403 AccessDenied（bucket acl）** |
| `PutObject` | 临时凭证（角色） | ✅ 200 |
| `GetObject` | 临时凭证（角色） | ✅ 200 |
| `DeleteObject` | 临时凭证（角色） | ❌ 403（符合「角色不给删」的设计） |

**结论：角色的信任策略 + `sts:AssumeRole` 都已正确**（否则第一行不会成功、第三行也不会成功）。
**唯一缺口 = 长期 AK 所属 RAM 用户没有这个桶的权限** —— 即 §2.2「策略 A」的 OSS 那段。

### 影响面（为什么说"一个权限补齐就全好了"）

后端那把长期 AK 同时承担**四件事**，因此它没权限 ⇒ 这四件事一起挂：

| 受影响 | 表现 | 原因 |
| --- | --- | --- |
| **上传** | 分片能传进 OSS（走临时凭证 ✅），但最后**登记失败** | `complete` 要 HEAD 复核（用长期 AK） |
| **下载** | OSS 落点的文件下不了，返回业务码 `2006` | 签发链接前要 `exists()` 存在性探测（用长期 AK） |
| **存量迁移** | 迁移任务里每条都失败 | 后端 `putObject` 写 OSS（用长期 AK） |
| **删除** | OSS 落点的工具删不掉 | 后端删对象（用长期 AK） |

**不受影响的**：登录、列表、检索、工具详情（纯数据库）；**本地落点**文件的下载/删除（走本地磁盘）。

> 也就是说：**补上这一条权限，上面四件事会一起恢复**，不需要分别处理。
> 验证方式：重跑 `python oss-probe.py`，第 2 行 `[AK] PutObject` 变 ✅ 即代表后端侧权限已就位。


> ⚠️ 两种可能，请管理员一并确认：
> ① 该用户**没挂**桶策略（最常见）；
> ② 挂了，但策略里带 **IP 限制条件（`acs:SourceIp`）**，或账号级安全策略限制了来源 IP。
> 注意：`AssumeRole` 成功**不能**排除 ②——因为 IP 条件可能只写在 OSS 那几条语句上。
> 另：**下载签名 URL 的身份就是这个用户**，一旦限 IP，用户在浏览器点下载会 403，所以也不要加 IP 白名单。

> 📌 附带观察：角色能 `GetObject`（第 4 行通过），而我们的「策略 B」**刻意不含**该权限
> ——说明角色上实际挂了比策略 B 更多的策略。**不影响功能**（浏览器直传只写不读）；
> 若需严格最小权限，可让管理员核对角色下挂了哪些策略、去掉多余的读权限。

### 可直接转发的文本（只差这一步）

```text
你好，OSS 直传的权限还差最后一步，麻烦帮忙补一下。

【好消息】STS 角色那条路已经通了 —— 我们写了个探针脚本实测：
· 用长期 AccessKey 调 AssumeRole   ✅ 成功
· 用换来的临时凭证上传对象到 OSS    ✅ 成功
说明角色的信任策略、sts:AssumeRole 授权都没问题，浏览器直传已经能正常写入。

【还差一步】同一把长期 AccessKey 直接访问桶时被拒：
· PutObject → 403 AccessDenied：You have no right to access this object because of bucket acl.
（RequestId 例：6ACEBA174FBFB83832B9B164）

【原因】这个 AccessKey 所属的 RAM 用户还没有被授予该桶的权限
（sts:AssumeRole 给了，桶权限没给）。

【请处理】给该用户挂一条自定义策略（可命名 toolbox-backend-policy）：

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
      "Resource": ["acs:oss:*:*:toolbox-file-upload/*"]
    }
  ]
}

说明：GetObject 用于下载签名与上传后校验，请勿删；
若贵方已有含 sts:AssumeRole 的策略，保持原样即可，本策略只补 OSS 权限。

【另外请一并确认】
1. 该用户/该策略里没有 IP 限制条件（acs:SourceIp），账号级安全策略也没有限制来源 IP。
   （下载签名 URL 的身份就是这个用户，限 IP 会导致用户在浏览器下载时 403。）
2. 若账号下建了多个 RAM 用户/多把 AK，请确认我们手上这把属于哪一个。

改完我们重启后端即可生效，无需改代码。谢谢！
```

> 验证方式：改完后我们再跑一次 `python oss-probe.py`，第 2 行应变 ✅。

---

## 附 F：替代路径 —— 用「Bucket 策略」自助授权（**不需要 RAM 管理员**）

> 背景：RAM 策略（§2.2 策略 A）需要 `ram:CreatePolicy` / `ram:AttachPolicyToUser` 权限，**必须管理员做**。
> 而 **Bucket 策略是"资源型策略"，直接配在桶上，属于 OSS 权限**（`oss:PutBucketPolicy`），
> **不需要任何 RAM 管理权限**。这个桶是我们自己建的，所以这条路大概率能自己走通。

### F.1 两条路的区别（都有效，官方口径是"二者叠加"）

| | RAM 策略（策略 A） | **Bucket 策略（本附）** |
| --- | --- | --- |
| 挂在哪 | RAM 用户身上 | **桶上** |
| 需要什么权限 | `ram:*` 管理权限 | **`oss:PutBucketPolicy`（桶级）** |
| 谁来做 | 主账号管理员 | **你自己**（有该桶管理权限即可） |
| 能否授予 `sts:AssumeRole` | 能 | **不能**（桶策略只能授 OSS 动作） |

> 官方：OSS 评估请求时**综合 Bucket Policy 与 RAM Policy**，同一主体的多条策略**叠加**（拒绝优先）。
> 所以追加一条 Bucket 策略**不会破坏**现有授权，可以放心加。

### F.2 唯一需要的信息：那把 AK 的 **RAM 用户 UID**

用探针脚本第 ⓪ 步直接查（`GetCallerIdentity` 无额外授权要求，官方即用它做凭证自检）：

```powershell
python oss-probe.py
```

输出中的 **`UserId`**（形如 `20407046578681xxxx`）就是 Bucket 策略 `Principal` 要填的值。
**脚本还会在结论处把填好该 UID 的 JSON 直接打印出来 —— 复制粘贴即可。**

### F.3 配置路径

OSS 控制台 → **Bucket 列表** → 点 `toolbox-file-upload` → 左侧 **「Bucket 授权策略」**
（不同版本可能在「权限控制」或「数据安全」分组下）→ 切到 **「按语法策略添加」** → 粘贴 → 保存。

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
      "Principal": ["上面查到的 UserId"],
      "Resource": ["acs:oss:*:*:toolbox-file-upload/*"]
    }
  ]
}
```

### F.4 四个坑（不避开就会白试）

1. **别用图形策略的「子账号」下拉** —— 它需要 `ram:ListUsers` 权限，而我们这个身份正好没有（会报 NoPermission）。
   改用 **「按语法策略添加」**（直接写 JSON），或在图形策略里选 **「其他账号」手填 UID**。
2. **`Principal` 绝不能写 `*`** —— 那等于公开授权；而且本桶已开通「**阻止公共访问**」，
   匿名（`*`）授权会被**直接忽略**，配了也无效。
3. **不要加 IP 条件** —— 一是新规：配了 `acs:SourceIp` 就**必须同时配** `acs:SourceVpc`（否则保存报错）；
   二是**下载签名 URL 的身份就是这个用户**，限 IP 会让用户在浏览器点下载直接 403。
4. **没有 `oss:ListObjects` 不影响我们** —— 后端走 SDK/API 直连，不需要"在控制台里浏览桶"；
   `Resource` 写 `bucket/*`（对象级）就够。

### F.5 验证

保存后重跑 `python oss-probe.py`，第 2 行 `[AK] PutObject` 应从 ❌ 变 **✅**；
之后上传的 `complete` 复核、下载签名、存量迁移、删除应一并恢复。

> ✅ **无需重启后端**：改的是阿里云侧授权，OSS 每次请求实时判定；AK 也没变，不必重启。
> （只有**改环境变量**才需要重启。）

> 若保存时报 `AccessDenied` / `NoPermission` → 说明你这个身份**也没有** `oss:PutBucketPolicy`，
> 那就回到「附 A ~ 附 E」交给管理员（直接给 §2.2 **策略 A** 即可）。

### F.6 ★ 连控制台都不用登：用 AK 直接写桶策略（走 API）

> 如果**登不进控制台**（子账号登录要管理员给验证码/动态口令），这条路可以**完全绕开登录** ——
> 桶策略本质只是一次 API 调用（`PUT /?policy`），**有 AK 就能打**。

探针脚本已内置该能力：

```
python oss-probe.py --set-policy-for <RAM用户UID>           # 演练：只打印将要写入的策略，不写入
python oss-probe.py --set-policy-for <UID> --yes           # 真正写入
```

- **用哪把 AK**：就是当前环境变量里的 `OSS_ACCESS_KEY_ID`。要写得进去，它自己必须有
  **`oss:PutBucketPolicy`** —— 通常就是 **"当初建这个桶"的那把 AK**：把它设成
  `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` 再跑这条命令即可。
- 写不进去会明确报 `AccessDenied` / `NoPermission` ⇒ 说明这把也没有 ⇒ 两条路都断，只能交给管理员。
- 写完后**换回应用那把 AK** 再跑一次 `python oss-probe.py` 验证（第 2 行应从 ❌ 变 ✅）；
  **不需要重启后端**。

> ⚠️ 两点如实说明：
> ① 这条命令用 AK 直连 OSS，**不经过控制台**，所以不问验证码；但反过来，**任何拿到该 AK 的人都能改桶策略** ——
> 用完请把环境变量清掉，别留在共用机器上。
> ② 脚本的签名逻辑只在假凭据下验证过"**请求格式能被 OSS 识别**"（返回 `InvalidAccessKeyId` 而非签名/路径错误）；
> **签名是否完全正确，只能用真 AK 实跑才能确认** —— 若报 `SignatureDoesNotMatch`，把原文发我。

