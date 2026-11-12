# 手工部署手册（Windows 到 Debian 12）

本文描述从本地 Windows 工作站把系统部署到已备案域名的 Linux 服务器的完整流程，覆盖测试数据清理、发布包构建、服务器初始化、持久数据上传、Nginx 与证书、首次激活、验收与回滚。GitHub Actions 路径见 [`cicd.md`](cicd.md)。

## 1. 适用前提

| 项目 | 取值 |
| :--- | :--- |
| 域名 | `game.himematsu.cn` |
| 服务器 | `81.71.64.37`，Debian 12 |
| 运行用户 | `bgm`（systemd 服务），`deploy`（发布） |
| 部署根目录 | `/opt/bgm-guess` |
| 音频来源 | OSS 对象 `bgm/event-offline.mp3` 与 `bgm/event-online-cbr192.mp3` |

域名 `A` 记录指向服务器公网 IP，备案与 HTTPS 证书就绪。服务器安全组放行 `22`、`80`、`443`，`8080` 不对外开放。

Windows 工作站使用 Git for Windows 自带的 OpenSSH 客户端。下文的 `ssh` 与 `scp` 指：

```powershell
& "C:\Program Files\Git\usr\bin\ssh.exe" -V
& "C:\Program Files\Git\usr\bin\scp.exe" -V
```

首次连接前把服务器指纹写入 `known_hosts`：

```powershell
& "C:\Program Files\Git\usr\bin\ssh.exe" root@81.71.64.37 "echo ok"
```

## 2. 清理本地测试数据

活动数据只包含题库、两份编排与榜单。榜单是运行期产物，部署前先在本地归档并清空，避免测试成绩进入服务器。

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = "work\cleanup-backup-$stamp"
New-Item -ItemType Directory -Force $backup | Out-Null
Move-Item backend\data\leaderboards.json, backend\data\online_leaderboard.json, backend\data\leaderboard_summary.txt $backup -ErrorAction SilentlyContinue
Move-Item backend\data-backup\leaderboards.json, backend\data-backup\online_leaderboard.json, backend\data-backup\leaderboard_summary.txt $backup -ErrorAction SilentlyContinue
```

保留的文件：

| 文件 | 用途 |
| :--- | :--- |
| `backend/data/timeline.json` | 线下四轮编排与 40 题时刻 |
| `backend/data/timeline-online.json` | 线上 40 题编排 |
| `backend/data/questions.json` | 题目文本与选项 |
| `backend/data/event-offline.mp3` | 本地联调副本，不参与部署 |

榜单文件由服务在首次写入时重新创建，空文件不需要上传。

## 3. 构建发布包

前端构建注入正式域名：

```powershell
cd frontend
$env:VITE_PUBLIC_BASE_URL = 'https://game.himematsu.cn'
npm run build
cd ..
```

后端使用 Java 17 构建：

```powershell
cd backend
mvn -s maven-settings.xml -B -ntp clean package
cd ..
```

组装发布包。目录结构与 `deploy.yml` 的产物一致：

```powershell
$release = (Get-Date -Format 'yyyyMMddHHmmss') + '-local'
$stage = "work\release-$release"
New-Item -ItemType Directory -Force "$stage\dist", "$stage\deploy" | Out-Null
Copy-Item frontend\dist\* "$stage\dist" -Recurse -Force
Copy-Item backend\target\bgm-guess-backend-0.0.1-SNAPSHOT.jar "$stage\app.jar"
Copy-Item deploy\activate-release.sh "$stage\deploy\"
tar -czf "work\release-$release.tar.gz" -C $stage .
Get-FileHash "work\release-$release.tar.gz" -Algorithm SHA256
```

发布包只包含 `app.jar`、`dist/` 与 `deploy/activate-release.sh`。题库、编排与音频不进入发布包。

## 4. 服务器检查

以 root 登录服务器后执行：

```bash
cat /etc/os-release | head -n 2
java -version
systemctl is-active nginx
ss -lntp | grep -E ':(80|443|8080)\b' || true
getent hosts game.himematsu.cn
```

Java 17 缺失时安装：

```bash
apt-get update
apt-get install -y openjdk-17-jre-headless curl ca-certificates
```

`getent hosts` 没有返回公网 IP 时，先修 DNS 再继续。

## 5. 服务器初始化

本地把部署脚本传到服务器：

```powershell
& "C:\Program Files\Git\usr\bin\scp.exe" -r deploy root@81.71.64.37:/tmp/
```

服务器执行一次性初始化。脚本创建 `bgm`、`deploy` 用户，建立 `/opt/bgm-guess` 目录树，安装 systemd 单元与 sudoers 规则：

```bash
REPO_DIR=/tmp INSTALL_SUDOERS=1 bash /tmp/deploy/bootstrap-server.sh
```

初始化完成后检查：

```bash
id bgm
id deploy
ls -ld /opt/bgm-guess /opt/bgm-guess/shared/data /opt/bgm-guess/releases
systemctl is-enabled bgm-guess
```

## 6. 上传持久数据

```powershell
& "C:\Program Files\Git\usr\bin\scp.exe" `
  backend\data\timeline.json `
  backend\data\timeline-online.json `
  backend\data\questions.json `
  root@81.71.64.37:/opt/bgm-guess/shared/data/
```

服务器设置属主与权限：

```bash
chown -R bgm:bgm /opt/bgm-guess/shared/data
chmod 750 /opt/bgm-guess/shared/data
chmod 640 /opt/bgm-guess/shared/data/*.json
ls -l /opt/bgm-guess/shared/data
```

该目录不存放音频文件，`audioFile` 只保留文件名。

## 7. 配置运行参数

编辑 `/etc/bgm-guess/bgm-guess.env`，替换主持人密码：

```text
APP_HOST_PASSWORD=<主持人密码>
APP_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
APP_AUDIO_URL_ONLINE=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3
APP_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline.json
APP_ONLINE_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline-online.json
APP_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/leaderboards.json
APP_ONLINE_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/online_leaderboard.json
APP_NICKNAME_CLAIMS_FILE=/opt/bgm-guess/shared/data/nickname_claims.json
APP_LEADERBOARD_TXT_FILE=/opt/bgm-guess/shared/data/leaderboard_summary.txt
APP_FRONTEND_ORIGIN=https://game.himematsu.cn
```

权限保持 `root:bgm 640`：

```bash
chown root:bgm /etc/bgm-guess/bgm-guess.env
chmod 640 /etc/bgm-guess/bgm-guess.env
```

## 8. Nginx 与证书

主站配置可能包含把 `game.himematsu.cn` 重定向到 `himematsu.cn` 的 server block，该重定向会拦截子域：

```bash
grep -rn "game.himematsu.cn" /etc/nginx/ || true
```

存在时编辑对应文件删除该 server block，再执行 `nginx -t`。

签发证书前先建立仅监听 80 端口的站点：

```bash
mkdir -p /var/www/certbot
cat >/etc/nginx/sites-available/game.himematsu.cn.conf <<'EOF'
server {
    listen 80;
    listen [::]:80;
    server_name game.himematsu.cn;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}
EOF
ln -sfn /etc/nginx/sites-available/game.himematsu.cn.conf /etc/nginx/sites-enabled/game.himematsu.cn.conf
nginx -t
systemctl reload nginx
```

签发证书：

```bash
apt-get install -y certbot python3-certbot-nginx
certbot certonly --webroot -w /var/www/certbot -d game.himematsu.cn
ls /etc/letsencrypt/live/game.himematsu.cn/
```

证书就绪后把站点配置替换为 HTTPS 版本，内容取 `deploy/nginx-game.conf.example`：

```bash
cp /tmp/deploy/nginx-game.conf.example /etc/nginx/sites-available/game.himematsu.cn.conf
nginx -t
systemctl reload nginx
```

该配置对 `/assets/` 使用一年期 immutable 缓存，对入口与 SPA 路由使用 `Cache-Control: no-cache`。发布后刷新页面即可加载新哈希资源。

自动续期由 `certbot.timer` 负责：

```bash
systemctl list-timers | grep certbot
```

## 9. 上传并激活首个版本

```powershell
& "C:\Program Files\Git\usr\bin\scp.exe" work\release-<release>.tar.gz root@81.71.64.37:/opt/bgm-guess/releases/
```

服务器解压并激活：

```bash
cd /opt/bgm-guess/releases
mkdir -p <release>
tar -xzf release-<release>.tar.gz -C <release>
rm release-<release>.tar.gz
chown -R deploy:deploy <release>
sudo -u deploy bash /opt/bgm-guess/bin/activate-release.sh <release>
```

激活脚本切换 `current` 软链接、重启 `bgm-guess` 并轮询 `/api/health`。健康检查失败时自动回到上一版本。

服务状态与日志：

```bash
systemctl status bgm-guess --no-pager
journalctl -u bgm-guess -n 100 --no-pager
curl -fsS http://127.0.0.1:8080/api/health
```

## 10. 验收

本地执行：

```powershell
Resolve-DnsName game.himematsu.cn
curl.exe -I https://game.himematsu.cn/
curl.exe -sS https://game.himematsu.cn/api/health
curl.exe -sS -o NUL -w "%{http_code} -> %{redirect_url}`n" https://game.himematsu.cn/api/events/jisong/audio
curl.exe -sS -o NUL -w "%{http_code} -> %{redirect_url}`n" "https://game.himematsu.cn/api/events/jisong/audio?mode=online"
curl.exe -sS https://game.himematsu.cn/api/events/jisong/online/questions
```

浏览器验收：

- `https://game.himematsu.cn/` 显示参与者入口。
- `https://game.himematsu.cn/host` 刷新返回页面而不是 404。
- 主持人登录后 `<audio>` 的 `src` 为 `/api/events/jisong/audio`，该请求返回 302 到 OSS 线下对象，点击开始后声音与进度条同时前进。
- 手机通过二维码打开线上页面，音频可播放、暂停与拖动。
- 主持人页面二维码指向 `https://game.himematsu.cn`。

## 11. 回滚与数据重置

回滚到历史版本：

```bash
sudo -u deploy bash /opt/bgm-guess/bin/activate-release.sh <旧 release-id>
```

清空活动数据后重新开始：

```bash
systemctl stop bgm-guess
rm -f /opt/bgm-guess/shared/data/leaderboards.json \
      /opt/bgm-guess/shared/data/online_leaderboard.json \
      /opt/bgm-guess/shared/data/leaderboard_summary.txt
systemctl start bgm-guess
curl -fsS http://127.0.0.1:8080/api/health
```

修改题库或编排后重启 `bgm-guess` 生效。后端活动状态保存在内存，重启会清空进行中的活动。

## 12. 常见故障

| 现象 | 检查 |
| :--- | :--- |
| 浏览器返回 Nginx 默认页或跳转主站 | `grep -rn "game.himematsu.cn" /etc/nginx/`，删除旧重定向 server block |
| HTTPS 证书名称不匹配 | `certbot certonly --webroot -w /var/www/certbot -d game.himematsu.cn` 重新签发 |
| `/api/health` 返回 502 | `systemctl status bgm-guess`、`journalctl -u bgm-guess -n 100` |
| 主持人页面无声音 | 浏览器开发者工具查看 `<audio>` 的 `src` 与重定向目标；确认 OSS 对象 `HEAD` 为 `200` |
| 刷新 `/host` 出现 404 | Nginx 缺少 `try_files $uri $uri/ /index.html` |
| 服务启动报音频文件不可读 | 服务端未放置本地音频且 `APP_AUDIO_URL`、`APP_AUDIO_URL_ONLINE` 未注入 |
