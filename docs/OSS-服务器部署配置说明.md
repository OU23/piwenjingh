# OSS 服务器部署配置说明（Docker Compose）

> 适用：本仓库后端以 **Docker Compose** 部署。本文只讲**服务器上如何启用 OSS**。
> 平台侧（建桶 / RAM / 角色 / CORS / 生命周期）见 `OSS配置说明.md` §2；本地验证见其 §4.5。

---

## 0. 结论先行

- 本地用环境变量；**服务器也还是环境变量**，区别只在**写在哪**：
  - 本地 → 写在启动终端（`$env:OSS_...`）；
  - 服务器 → 写进 `deploy/docker-compose.yml` 的 `environment:` 段。
- ⚠️ **不是 systemd**。`OSS配置说明.md` §3 里写的 `/etc/systemd/system/tool-box.service` 是**旧部署方式**；本仓库现行部署在 `deploy/` 目录（Docker Compose）。

---

## 1. 加在哪

- 文件：`toolbox-backend/deploy/docker-compose.yml`
- 位置：`services.toolbox-backend.environment:`
- 该文件无 `.env`，变量**直接写在此段**。

---

## 2. 加哪些

| 变量 | 值 | 从哪拿 |
| --- | --- | --- |
| `TOOLBOX_STORAGE_TYPE` | `oss` | 想让**新上传走 OSS** 时必填；不填默认 `local` |
| `OSS_ENDPOINT` | 桶的**公网** endpoint，建议带 `https://`，**不能用 `-internal`** | 桶概览页「外网访问」 |
| `OSS_BUCKET` | 桶名 | 你建的桶 |
| `OSS_ACCESS_KEY_ID` | 后端 RAM 用户 AK ID | `OSS配置说明.md` §2.2 |
| `OSS_ACCESS_KEY_SECRET` | 后端 RAM 用户 AK Secret | `OSS配置说明.md` §2.2 |
| `OSS_STS_ROLE_ARN` | 上传专用角色 ARN，**`type=oss` 时必填**（缺则**启动即失败**） | `OSS配置说明.md` §2.3 |
| `OSS_URL_TTL_SECONDS` | 下载签名 URL 有效期（秒），默认 `3600` | 可选 |
| `OSS_STS_DURATION_SECONDS` | 临时凭证有效期（秒），默认 `3600` | 可选 |
| `OSS_STS_ENDPOINT` | STS 服务地址（**不是** OSS 地址），默认 `sts.aliyuncs.com` | 可选 |

> 变量名与 `application.yml` 的 `${...:}` 绑定**逐一对应**，`dev` / `prod` 两个 profile **同名通用**。
> ⚠️ OSS 凭据会**明文落在 compose 文件**里（与数据库密码同级）——按 `deploy/README.md` 口径，该文件视为**敏感配置**，勿外泄、勿提交公开仓库。

---

## 3. 完整片段（追加到现有 environment 段）

```yaml
    environment:
      # —— 现有（保持你已填好的值）——
      SPRING_PROFILES_ACTIVE: prod
      TZ: Asia/Shanghai
      SPRING_DATASOURCE_URL: "jdbc:mysql://host.docker.internal:3306/toolbox?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai"
      SPRING_DATASOURCE_USERNAME: "..."
      SPRING_DATASOURCE_PASSWORD: "..."
      TOOLBOX_JWT_SECRET: "..."
      # —— 新增：OSS ——
      TOOLBOX_STORAGE_TYPE: "oss"
      OSS_ENDPOINT: "https://oss-cn-beijing.aliyuncs.com"
      OSS_BUCKET: "your-bucket"
      OSS_ACCESS_KEY_ID: "LTAI..."
      OSS_ACCESS_KEY_SECRET: "..."
      OSS_STS_ROLE_ARN: "acs:ram::<主账号ID>:role/<角色名>"
```

> ⚠️ 不要用 `REPLACE_` 开头的占位值 —— `deploy/scripts/build-up.sh` 检测到会**直接拒绝部署**。
> 只需下载直链 / 存量迁移（B13）时，可先**不加** `TOOLBOX_STORAGE_TYPE`（保持 local）与 `OSS_STS_ROLE_ARN`，只填 4 个凭据。

---

## 4. 生效

```bash
cd <项目根>/toolbox-backend
./deploy/scripts/restart.sh        # = stop.sh + build-up.sh（--build + up -d），重建容器
```

> ⚠️ 仅执行 `docker compose restart` **不会**应用新 environment —— 环境变量在容器**创建时**注入，必须重建。

---

## 5. 验证

```bash
curl -s http://127.0.0.1:8080/api/upload/mode -H "Authorization: Bearer <登录令牌>"
```

- 期望返回 `"mode":"direct"`（及 `"partSize":...`）；
- 若返回 `"mode":"server"` ⇒ 环境变量没生效（多半没重建容器，见 §4）。

---

## 6. 平台侧三项前置（缺一不可）

1. **长期 AK 对桶有权限** —— 后端「删对象 / 下载签名 / complete 复核」都靠它；无权限 → 接口报 **2006**。
2. **STS 角色 + 授权** —— `TOOLBOX_STORAGE_TYPE=oss` 时 `OSS_STS_ROLE_ARN` 必填，缺则**后端启动即失败**。
3. **Bucket CORS** —— 放行浏览器访问服务器的**实际来源**（`http(s)://<域名或IP>[:端口]`），并**暴露 `ETag`**。

> 以上三步的详细做法见 `OSS配置说明.md` §2。
