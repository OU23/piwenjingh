# 后端通过 STS 访问 OSS

> ⚠️ **2026-10-16 范围扩展**：本文最初只描述「核实（`exists` / `size`）改用 STS」。
> 此后**全部对象操作都已改用 STS** —— 内容读取（`open`）、**下载签名（`presignedUrl`）**、
> 写入（`save`）、删除（`delete`）**同样不再使用长期 AK**；长期 AK 现在**只**用于向 STS 换取临时凭证。
> 下文「对象内容读取、下载签名、写入、删除仍沿用原有长期凭据客户端」的表述**已过时**（见该节更新块）。

后端的 `OssStorageService.exists` 和 `size` 改用 STS 临时凭据访问 OSS。
因此上传完成登记、提交工具/版本时的存在性检查，以及其他调用这两个方法的地方都会使用 STS。
接口和前端无需调整，文件归属、大小比较、审计及错误码保持原有逻辑。

## 使用现有用户和角色

继续使用现有的 `OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET` 调用 STS，
角色仍为 `OSS_STS_ROLE_ARN`，不需要新建 RAM 用户或角色。

现有角色必须允许**读取与删除**目标对象。如果角色原来只有上传权限，在角色权限策略中增加下面的语句
（替换 `<bucket>`，并保留原上传权限）：

```json
{
  "Effect": "Allow",
  "Action": ["oss:GetObject", "oss:DeleteObject"],
  "Resource": ["acs:oss:*:*:<bucket>/tools/*"]
}
```

> `oss:GetObject` 供**内容读取 / 下载签名 / 存在性与大小核实**；`oss:DeleteObject` 供 **OSS 落点文件彻底删除**。
> **只补 `GetObject` 会让下载能用、但删除仍 403。**

HeadObject 的权限是 `oss:GetObject`，无需添加名为 `oss:HeadObject` 的权限。
参考：https://help.aliyun.com/zh/oss/developer-reference/headobject

后端核实凭据的会话策略进一步限制为单个对象的 `oss:GetObject`，仅保留在后端。
浏览器上传凭据仍使用原有的部门前缀和仅上传策略，不会获得读取权限。

## 生命周期和改造范围

STS 签发器使用 Guava 在进程内缓存临时凭据，最多保留 1024 条，按会话身份和完整会话策略区分。
容量淘汰和同一缓存键的并发更新交给 Guava，业务代码仅判断 STS 的实际到期时间。
这保留了既有的身份与权限：上传会话仍包含用户 ID，策略仍限定部门；核实会话仍只读单个对象。
上传和核实不会共用同一份凭据。命中有效缓存时，不调用阿里云 AssumeRole。

距离到期不足 2 分钟（含边界）时，下次请求重新申请凭据；并发请求避免重复签发。
申请失败或返回的到期时间无效、剩余寿命不足时返回 2006，不缓存错误，也不回退到旧凭据或长期 AK。
缓存不写磁盘或日志，多实例分别缓存，重启后首次使用重新申请。

存在性和大小检查携带临时 AccessKeyId、AccessKeySecret、SecurityToken 建立 OSS 客户端，
检查后关闭客户端。同一对象的存在性、大小和后续提交检查可以复用缓存中的核实凭据。
参考阿里云关于缓存时长应小于 STS 有效期的建议：
https://help.aliyun.com/zh/well-architected/solution-251

> ⚠️ **已更新（2026-10-16）**：原「对象内容读取、下载签名、写入、删除仍沿用原有长期凭据客户端」的说法**已失效** ——
> 现在**全部改为 STS 临时凭据客户端**，各操作使用**按用途最小授予**的后端凭证：
> 读 → `oss:GetObject`、写 → `oss:PutObject`、删 → `oss:DeleteObject`，`Resource` 均**精确到单个对象**。
> ⇒ **角色权限策略必须同时包含这三个 Action**（会话策略 ∩ 角色权限）；缺任一项，对应操作报 403 `bucket acl`。

如果长期 AccessKey 连 STS AssumeRole 也无法调用，**一切 OSS 操作**都会返回 2006。
它适用于现有身份能够申请 STS、所扮演角色具备**读 / 写 / 删**对象权限的情况。

## 部署后验证

1. 更新角色权限（**需含 `oss:GetObject` 与 `oss:DeleteObject`** —— 仅上传动作不够），重新构建并部署后端。
2. 上传一个新文件，确认 `/api/upload/direct/complete` 成功，出现 `UPLOAD_DIRECT_COMPLETE` 成功审计。
3. 填写工具信息并提交，确认文件存在性检查和版本保存成功。
4. **下载**该文件：签发 URL 里 `OSSAccessKeyId` 应以 **`STS.` 开头且带 `security-token`** —— 这是"真走 STS"的判据（仍是 `LTAI...` 就说明没接上 STS）。
5. **删除**该工具并清空回收站，确认 OSS 控制台里对象消失。
6. 若出现 2006 / 403，区分 `[STS] AssumeRole 失败`（凭据或角色信任问题）与 `[OSS] ... bucket acl`
   （角色权限不足；报错里 `AuthPrincipalType=AssumedRoleUser` 表示 STS 身份已生效、卡在角色策略）。

单元测试使用模拟 STS/OSS，验证单对象只读策略、SecurityToken 传递、客户端释放、
无长期凭据回退、缓存隔离/刷新/并发/淘汰、STS/OSS 故障隔离及原有上传/提交行为；
不代表真实云端权限已验证。
