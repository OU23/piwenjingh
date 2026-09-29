# 前端部署（CentOS 7）

本方案不在宿主机安装 Node.js。构建通过 Docker 的 Node 20 镜像完成，Nginx 直接服务构建结果。项目要求 Node 20（见 `package.json`），不是 Node 24。

## 前置条件

- 已安装 Git、Docker 和 Nginx，且 Docker 服务已启动。
- 后端 Docker 容器已将 `8080` 映射到宿主机 `127.0.0.1:8080`。
- 部署用户可写入 `/opt/toolbox/toolbox-frontend`。
- 安全组和系统防火墙允许入站 TCP 3004。

首次在服务器上执行：

```bash
git clone <仓库地址> /opt/toolbox/toolbox-frontend/source
cd /opt/toolbox/toolbox-frontend/source
chmod +x deploy/*.sh
# 手工将 deploy/nginx/toolbox-frontend.conf 放入 Nginx 配置目录，
# 然后执行 nginx -t 并重载 Nginx。
./deploy/deploy.sh
```

`deploy/nginx/toolbox-frontend.conf` 是 Nginx 配置模板，监听 `3004`。将它手工复制为服务器的站点配置（例如 `/etc/nginx/conf.d/toolbox-frontend.conf`），执行 `sudo nginx -t` 成功后，再执行 `sudo systemctl reload nginx`。

## 日常发布

```bash
cd /opt/toolbox/toolbox-frontend/source
./deploy/deploy.sh
```

脚本会拒绝带有未提交或未跟踪文件的源码目录，随后仅接受快进式 `git pull`。它用 Docker 中的 Node 20 执行 `npm ci` 和 `npm run build`，再发布到版本目录，并原子切换 `/opt/toolbox/toolbox-frontend/dist` 软链接。Nginx 始终访问该固定路径，无需在每次静态资源发布后重载。

脚本保留当前与前一个 release。若首次发布前该路径已经是普通目录而不是软链接，脚本会停止，以免覆盖现有内容；确认内容可迁移后，手工将旧目录移走，再重新发布。

## 回退

列出可用版本：

```bash
find /opt/toolbox/toolbox-frontend/releases -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r
```

将 `RELEASE_ID` 替换为目标版本，然后原子切换并重载 Nginx：

```bash
RELEASE_ID=<目标版本>
ln -s "/opt/toolbox/toolbox-frontend/releases/$RELEASE_ID/dist" /opt/toolbox/toolbox-frontend/.dist.rollback
mv -Tf /opt/toolbox/toolbox-frontend/.dist.rollback /opt/toolbox/toolbox-frontend/dist
```
