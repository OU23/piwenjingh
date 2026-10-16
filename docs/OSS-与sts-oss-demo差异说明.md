# OSS 联调：我们的应用 vs `sts-oss-demo` 的差异说明

> 更新：后端存在性和大小核实已改为使用现有角色的 STS 临时凭据，
> 见 [OSS-STS核实说明](OSS-STS核实说明.md)。下文关于核实使用长期 AK 的描述为改造前的排查记录；
> 对象写入、内容读取、下载签名和删除仍使用长期 AK。

> 用途：管理员反馈「配置没问题、demo 已测通，问题可能在使用方」。
> 本文用**可核对的证据**说明：**demo 覆盖的路径与失败路径不是同一条**，因此 demo 通过 ≠ 我们的场景可用。
> 结论在最后一节，附**一个 5 分钟就能验证的做法**。

---

## 一、先说结论

- **demo 验证的是「浏览器拿 STS 临时凭证直传 OSS」这条路径** —— 这条路径**我们这边本来就是通的**（分片 `PUT 200 OK`、探针 ③ 全绿）。
- **我们失败的是另一条路径**：**后端用自己的长期 AccessKey 直连 OSS**（下载签名前探测、上传收尾 HEAD 复核、存量迁移写入、删除对象）。
- **`sts-oss-demo` 从未触碰这条路径** —— 它的后端**没有 OSS 客户端**，因此它对"长期 AK 能否读写桶"**零覆盖**。

---

## 二、证据（都可在 demo 仓库里直接核对）

### 证据 1：demo 后端不访问 OSS 对象

| 核对点 | 内容 |
| --- | --- |
| `pom.xml` 依赖 | 只有 `spring-boot-starter-web` + **`com.aliyun:aliyun-java-sdk-core:4.6.4`**；**没有 `aliyun-sdk-oss`** |
| `pom.xml` 注释 | 「Web 提供页面和接口，**阿里云 SDK 仅用于签发 STS**」 |
| `AliyunClientConfiguration.java` | 只构造一个 `DefaultAcsClient`，**仅用于 STS**；注释「Spring 默认单例，**仅用于 STS**」 |
| `DirectUploadController.java` | 后端只做两件事：`GET /api/config` 返回公开配置、`POST /api/sts` 签发临时凭证。**没有任何对 OSS 对象的读写** |
| `README.md` | 「文件直接从浏览器传至 OSS，**后端仅提供公开配置与临时凭证**」 |

### 证据 2：demo 的测试是**模拟**的，不碰真实 OSS

`TEST_RESULTS.md` 原文：

> - 页面与 SDK 在本机 8080 加载正常。
> - **自动化测试使用模拟接口与 OSS，不上传真实文件。**
> - **Java 联网上传 Demo 已移除**，保留页面及其自动化测试。

也就是说：8 项后端测试 + 4 项前端测试全部基于 mock；唯一"真联网上传"的那段代码已被移除。
**凭这些测试无法得出"长期 AK 对桶有权限"的结论** —— 它们连真实 OSS 都没访问。

### 证据 4：管理员真机上传成功，**仍然只覆盖「浏览器直传」这一条**

管理员展示了 demo 页面上**真实上传成功**的截图（`Object Key = sts-demo/browser/….zip`，带 ETag 与 RequestId）。
这**只说明 STS 直传可用**，其流程是：

```
浏览器 ──① 向 demo 后端要凭证 ──▶ demo 后端用【长期 AK】调 AssumeRole
浏览器 ──② 拿临时凭证直传 ─────▶ OSS（✅ 对象落地，ETag/RequestId 为证）
```

关键：**demo 后端在这里只做了一次 `AssumeRole`，从头到尾没有读写任何对象。**
「普通上传 / 分片上传 / 断点续传」三者**都发生在第 ② 步**，都使用**同一个身份（STS 临时凭证 = 角色）** ——
所以它们全通，**恰好印证"角色有权限"**，而不是"长期 AK 有权限"。

> 也就是说：这张截图与我们 `oss-probe.py` 的第 ③ 步（临时凭证 → `Put ✅ / Get ✅`）是同一个结论。
> 我们出问题的是**另一个身份**（长期 AK = RAM 用户本身），而那张图上**没有这个身份的任何动作**。

**两个身份，别混**：

| 身份 | 谁在用 | 实测 |
| --- | --- | --- |
| **角色**（STS 临时凭证） | 浏览器直传（demo 的 ①②、我们的直传） | ✅ 有权限（`Put`/`Get` 通过） |
| **RAM 用户**（长期 AK） | **我们的后端**（下载签名 / HEAD 复核 / 迁移 / 删除） | ❌ **无任何桶权限** |

### 证据 3：我们这边的实测（**不经过我们的应用代码**）

用独立探针 `oss-probe.py`（零依赖、只用标准库、**不加载我们的任何代码**）直连阿里云，结果：

```
[AK] STS AssumeRole                ✅ 成功          ← 长期 AK 有效，sts:AssumeRole 与角色信任策略都对
[AK] PutObject                     ❌ 403 AccessDenied  You have no right to access this object because of bucket acl
[STS] PutObject / GetObject        ✅ / ✅          ← 浏览器直传那条路完全正常
```

配套事实（`GetCallerIdentity` 实测）：这把长期 AK 属于 `app-server-sts-user`
（UID `204645391880405440`，账号 `1064446027897230`）。

**同一个 AK 能成功调 `AssumeRole`、却对桶报 403** ⇒ 凭据有效，**只是被授权不足** —— 与调用方代码无关。

---

## 三、为什么我们的后端**必须**访问 OSS 对象

这不是实现选择，而是功能要求：

| 功能 | 为什么必须由后端碰对象 | 官方依据 |
| --- | --- | --- |
| **下载（签名直链）** | 预签名 URL 由后端签发，**签名者本人必须持有 `oss:GetObject`**，否则用户下载 403 | 阿里云明文：「预签名 URL 无需权限即可生成，但**只有具备 `oss:GetObject` 的签名者**，第三方才能下载成功」 |
| **上传收尾** | `POST /api/upload/direct/complete` 要 **HEAD 复核**对象是否存在、大小是否与声明一致（防虚报） | 我们自己的防作弊设计（失去这一层就只剩前端校验，可被绕过） |
| **存量迁移** | 把服务器本地旧文件搬到 OSS，**只能后端写**（`putObject` / multipart） | B13 迁移功能 |
| **删除** | OSS 落点的工具彻底删除时，要由后端删对象 | 删除隔离设计（只删该文件实际所在的后端） |

---

## 四、需要一个最小授权（二选一，任选其一即可）

**方案 A｜给该用户挂 RAM 策略**（`OSS-RAM配置说明.md` 策略 A）：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "oss:PutObject", "oss:GetObject", "oss:DeleteObject",
        "oss:InitiateMultipartUpload", "oss:UploadPart",
        "oss:CompleteMultipartUpload", "oss:AbortMultipartUpload", "oss:ListParts"
      ],
      "Resource": ["acs:oss:*:*:toolbox-file-upload/*"]
    }
  ]
}
```

**方案 B｜在桶上加一条 Bucket 策略**（只作用于该桶，随时可撤）：
`Principal` 填上面那个 UID `204645391880405440`，Action / Resource 同上。

> 两者效果等价、互不冲突（OSS 会取并集）。
> 注意：**不要**使用 `Principal: "*"`（桶已开启「阻止公共访问」，匿名授权会被忽略）。

---

## 五、如果坚持「服务器不碰对象」这个模型

那需要**改我们的架构**（不是配置问题），代价如下，请一并评估：

1. 后端改为**扮演 `oss-uploader-role`** 来访问 OSS（缓存 STS 凭证 + 到期刷新）；
2. 角色的权限需要**再加 `oss:DeleteObject`** —— 目前探针实测角色是 `Put ✅ / Get ✅ / **Delete ❌**`，不加就删不掉 OSS 落点文件；
3. **下载签名 URL 的有效期会被压到 STS 时长以内**（现在默认 3600s，可调但受角色最大会话时间限制）；
4. 属**代码改造**，按我们的流程要走「方案 + 计划 + 验收」一整套，周期比补一条策略长得多。

**我们的建议：走方案 A / B（一条策略，5 分钟，零代码改动）。**

---

## 六、5 分钟即可验证（推荐做一次）

请管理员用**给我们的那把 AccessKey**（不是自己常用的那把）做任意一次**对象级**操作即可 —— 例如：

```
python oss-probe.py          # 在 toolbox-backend 目录下，复用我们已设的环境变量
```

或直接在有该 AK 的终端里跑：`[AK] PutObject` 一行。
- 若同样 403 ⇒ 与该 AK 的**授权范围**有关，与我们的代码无关；
- 若为 200 ⇒ 说明我们手上的这把 AK 不是预期那把，请提供正确的那把。

（探针脚本零依赖、不需要登录控制台、不会修改任何数据：只写一个几十字节的探针对象并立即删除。）
