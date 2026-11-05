# 自动化构建与发布

本文定义本系统的 GitHub Actions 构建、服务器发布、回滚与一次性初始化流程。适用目标为 Linux 服务器上的 Nginx + Spring Boot + 静态前端单域名部署。

## 1. 范围

- 流水线构建前端静态资源与后端可执行 JAR，并发布到服务器。
- 活动题库、编排、音频与榜单属于服务器持久数据，不随代码发布覆盖。
- 线上入口与静态页面、`/api`、`/ws` 共用同一域名。
- 服务器使用 systemd 管理后端进程，Nginx 提供 HTTPS 入口与反向代理。

## 2. 运行拓扑

```text
参与者 / 主持人
        |
        | HTTPS
        v
   Nginx 443
   ├── /            -> /opt/bgm-guess/current/dist
   ├── /api/**      -> 127.0.0.1:8080
   └── /ws          -> 127.0.0.1:8080 (WebSocket Upgrade)
        |
        v
   systemd: bgm-guess
   └── java -jar /opt/bgm-guess/current/app.jar
        |
        v
   /opt/bgm-guess/shared/data
```

域名 `game.himematsu.cn` 的 `A` 记录指向服务器公网 IP。Nginx 必须为该子域签发覆盖 `game.himematsu.cn` 的证书，并保留独立的 `server_name` 配置。

## 3. 服务器目录

```text
/opt/bgm-guess/
├── releases/
│   └── <release-id>/
│       ├── app.jar
│       ├── dist/
│       └── deploy/activate-release.sh
├── current -> releases/<release-id>
├── shared/
│   └── data/
│       ├── timeline.json
│       ├── timeline-online.json
│       ├── questions.json
│       ├── leaderboards.json
│       ├── online_leaderboard.json
│       └── leaderboard_summary.txt
└── bin/
    └── activate-release.sh

/etc/bgm-guess/bgm-guess.env
/etc/systemd/system/bgm-guess.service
```

`releases/` 保存发布产物，`current` 指向当前版本，`shared/data` 保存跨版本持久数据。发布脚本只替换 `current` 指向的版本，不修改 `shared/data`。

## 4. 流水线

### 4.1 持续集成

`.github/workflows/ci.yml` 在拉取请求与手动触发时执行：

- Node.js 22 执行 `npm ci` 与 `npm run build`。
- Java 17 执行 `mvn -B -ntp verify`。

前端构建注入 `VITE_PUBLIC_BASE_URL` 与 `VITE_AUDIO_URL`，默认值分别为 `https://game.himematsu.cn` 与 `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3`。`VITE_AUDIO_URL` 指向线下母带，只影响主持人控制台；线上参与者由后端 `app.audio-url-online` 下发音频地址。

### 4.2 发布

`.github/workflows/deploy.yml` 通过手动触发执行：

1. 构建前端与后端。
2. 组装发布包，包含 `app.jar`、`dist/` 与 `deploy/activate-release.sh`。
3. 通过 SCP 上传到 `/opt/bgm-guess/releases/`。
4. 在服务器解压并执行 `activate-release.sh`。
5. 激活脚本切换 `current` 软链接、重启 `bgm-guess`、轮询 `/api/health`。
6. 健康检查失败时切回上一版本并再次重启。

发布包只包含代码产物。`shared/data` 中的题库与榜单由服务器侧维护，音频固定由 OSS 提供，服务器不保存音频文件。

服务器初始化与 GitHub Secrets 配置完成后，可为 `deploy.yml` 增加 `main` 分支推送触发。

### 4.3 回滚

手动触发 `deploy.yml` 并填写 `release_id` 时，流水线跳过构建，直接激活 `/opt/bgm-guess/releases/<release_id>`。该方式用于回滚到已上传的历史版本。

### 4.4 发布标识

发布标识格式为 `YYYYMMDDHHMMSS-<commit-short-sha>`，例如 `20261031103000-19f2aa4`。标识同时作为目录名与发布包名。

### 4.5 并发与保留

- 发布任务使用固定 concurrency group，同一时间只执行一个发布。
- 服务器保留最近 5 个版本，激活成功后清理更早的版本。

## 5. GitHub 配置

### 5.1 Secrets

| 名称 | 内容 |
| :--- | :--- |
| `SSH_HOST` | 服务器地址或公网 IP |
| `SSH_USER` | 发布用户，例如 `deploy` |
| `SSH_PORT` | SSH 端口，省略时为 `22` |
| `SSH_PRIVATE_KEY` | 发布用户的 Ed25519 私钥 |

### 5.2 Variables

| 名称 | 内容 |
| :--- | :--- |
| `PUBLIC_BASE_URL` | 参与者访问地址，例如 `https://game.himematsu.cn` |
| `AUDIO_URL` | 线下音频对象地址，例如 `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3` |

`PUBLIC_BASE_URL` 缺省时使用 `https://game.himematsu.cn`，`AUDIO_URL` 缺省时使用本文第 4.1 节的 OSS 地址。线上音频对象地址由服务器环境变量 `APP_AUDIO_URL_ONLINE` 提供，不经前端构建注入。

### 5.3 Production Environment

发布任务绑定 `production` environment。需要人工确认时，在仓库 Settings → Environments 中为 `production` 配置 required reviewers。

## 6. 一次性服务器初始化

以下步骤在服务器上以 root 或有 sudo 权限的账号执行一次。

### 6.1 安装运行依赖

Debian / Ubuntu 示例：

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx certbot python3-certbot-nginx curl tar rsync
```

服务器已有 JDK 17 时跳过 Java 安装。JDK 17 为最低版本，后端构建目标为 Java 17。

### 6.2 确认 DNS 与证书

`game.himematsu.cn` 的 `A` 记录指向服务器公网 IP。证书必须覆盖该子域：

```bash
sudo certbot certonly --nginx -d game.himematsu.cn
```

证书路径：

```text
/etc/letsencrypt/live/game.himematsu.cn/fullchain.pem
/etc/letsencrypt/live/game.himematsu.cn/privkey.pem
```

主站 `himematsu.cn` 的证书不覆盖 `game.himematsu.cn`。两个站点使用独立证书，或使用同时包含两个名称的证书。

### 6.3 准备仓库副本

服务器需要一个仓库副本用于执行初始化脚本：

```bash
sudo git clone https://github.com/HNU-himematsu/BGM-todokanai.git /srv/bgm-repo
cd /srv/bgm-repo
sudo bash deploy/bootstrap-server.sh
```

脚本执行内容：

- 创建 `bgm` 服务用户与 `deploy` 发布用户。
- 创建 `/opt/bgm-guess` 目录结构与 `/etc/bgm-guess`。
- 将 `/opt/bgm-guess` 设为 `root:deploy`、`775`，使 `deploy` 用户可切换 `current` 软链接。
- 安装 systemd 单元与激活脚本。
- 生成 `/etc/bgm-guess/bgm-guess.env` 模板。

需要同时安装 sudoers 规则时：

```bash
sudo INSTALL_SUDOERS=1 bash deploy/bootstrap-server.sh
```

### 6.4 放置活动数据

将以下文件放入 `/opt/bgm-guess/shared/data/`：

- `timeline.json`
- `timeline-online.json`
- `questions.json`
- 初始 `leaderboards.json`
- 初始 `online_leaderboard.json`

服务器侧不放置音频文件。音频对象位于 OSS，`timeline.json` 与 `timeline-online.json` 的 `audioFile` 只保留文件名。

目录所有者与权限：

```bash
sudo chown -R bgm:bgm /opt/bgm-guess/shared/data
sudo chmod 750 /opt/bgm-guess/shared/data
```

### 6.5 配置运行参数

编辑 `/etc/bgm-guess/bgm-guess.env`：

```text
APP_HOST_PASSWORD=<主持人密码>
APP_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
APP_AUDIO_URL_ONLINE=https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
APP_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline.json
APP_ONLINE_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline-online.json
APP_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/leaderboards.json
APP_ONLINE_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/online_leaderboard.json
APP_LEADERBOARD_TXT_FILE=/opt/bgm-guess/shared/data/leaderboard_summary.txt
APP_FRONTEND_ORIGIN=https://game.himematsu.cn
```

文件权限：

```bash
sudo chown root:bgm /etc/bgm-guess/bgm-guess.env
sudo chmod 640 /etc/bgm-guess/bgm-guess.env
```

### 6.6 配置 Nginx

参考 `deploy/nginx-game.conf.example` 创建独立站点配置：

```bash
sudo cp deploy/nginx-game.conf.example /etc/nginx/sites-available/game.himematsu.cn.conf
sudo ln -sfn /etc/nginx/sites-available/game.himematsu.cn.conf /etc/nginx/sites-enabled/game.himematsu.cn.conf
sudo nginx -t
sudo systemctl reload nginx
```

删除或修改将 `game.himematsu.cn` 重定向到主站 `himematsu.cn` 的旧 server block。该重定向会阻止子域加载本系统。

### 6.7 配置发布 SSH 密钥

在本地生成发布密钥：

```bash
ssh-keygen -t ed25519 -C "github-actions-bgm" -f bgm_deploy
```

将公钥写入服务器发布用户：

```bash
sudo -u deploy mkdir -p /home/deploy/.ssh
sudo -u deploy chmod 700 /home/deploy/.ssh
sudo -u deploy sh -c 'cat >> /home/deploy/.ssh/authorized_keys' < bgm_deploy.pub
sudo -u deploy chmod 600 /home/deploy/.ssh/authorized_keys
```

私钥内容写入 GitHub Secret `SSH_PRIVATE_KEY`。公钥文件不进入仓库。

## 7. 首次发布与验收

首次发布前确认：

- `game.himematsu.cn` 解析到服务器。
- Nginx 站点证书覆盖该子域。
- `shared/data` 中的题库与编排齐备，OSS 音频对象可访问。
- `bgm-guess.env` 中的主持人密码已替换默认值。
- GitHub Secrets 与 Variables 已配置。

触发方式：

- 在 Actions 中手动运行 `Deploy`。
- 服务器初始化与 Secrets 配置完成后，按需增加 `main` 分支推送触发。

验收项：

- `https://game.himematsu.cn/` 返回本系统页面。
- `https://game.himematsu.cn/api/health` 返回健康信息。
- `https://game.himematsu.cn/host` 刷新不出现 404。
- `https://game.himematsu.cn/offline?event=demo` 进入线下入口。
- `https://game.himematsu.cn/api/events/demo/online/questions` 的 `audioUrl` 为 OSS 地址。
- `https://game.himematsu.cn/api/events/demo/audio` 跳转到 OSS 地址。
- 手机可播放音频，Range 请求返回 `206`。
- `/ws` 完成 WebSocket Upgrade。
- 重启 `bgm-guess` 后历史榜单仍可读取。

访问 `https://game.himematsu.cn/` 返回 Nginx 默认页、跳转主站或证书名称不匹配时，子域 server block 或证书尚未生效。

## 8. 发布约束

- 后端活动状态保存在内存。发布重启会清空进行中的线上与线下活动，发布应安排在活动窗口之外。
- `backend/data` 中 `timeline.json` 与 `timeline-online.json` 进入版本库，`questions.json` 由服务器侧维护，音频由 OSS 提供，流水线不生成这些文件。
- 发布脚本不删除 `/opt/bgm-guess/shared/data`。清理旧版本时只处理 `/opt/bgm-guess/releases/`。
- 前端与后端必须使用同一 origin。`VITE_PUBLIC_BASE_URL` 指向正式域名，页面、API、音频与 WebSocket 共用该域名。
- 更换域名会形成新的浏览器存储空间，线上本地进度与主持人令牌不跨域名迁移。
- 构建前端使用 Node.js 22。Vite 7 要求 Node.js 20.19 或更高版本。
- 后端使用 Java 17 构建与运行。

## 9. 回滚操作

1. 在 Actions 中手动运行 `Deploy`。
2. 在 `release_id` 中填写 `/opt/bgm-guess/releases/` 下的目标目录名。
3. 流水线跳过构建，直接执行激活脚本。

服务器侧也可手工执行：

```bash
sudo -u deploy bash /opt/bgm-guess/bin/activate-release.sh <release-id>
```

激活脚本验证目标目录、切换软链接、重启服务并轮询健康接口。健康检查失败时自动回到切换前的版本。

## 10. 备份

活动前备份 `shared/data` 中的题库、编排与空榜单，并保留 OSS 音频对象的版本。活动期间每轮结束或线上锁定高峰后备份榜单文件。备份目录独立于发布目录，发布与回滚不覆盖备份。
