# 旮旯原声之届不到的 BGM

高校社团百团大战现场的猜歌互动系统，覆盖扫码入场、现场统一放音、逐题锁卷揭晓、线上独立答题与三类榜单归档。

主持人用一台电脑打开控制台并播放成品母带，服务端按编排时间轴推进题目上屏、锁卷与揭晓，参与者用手机扫码作答，结算后生成线下轮次榜、线上积分榜与总榜。前端为 Vue 3 单页应用，后端为 Spring Boot 服务，音频托管在阿里云 OSS，生产环境由 Nginx 以同一域名提供静态页面、REST 接口与 WebSocket。

## 1. 系统能力

### 1.1 线下模式

- 主持人访问 `/host`，输入主持人密码进入控制台。
- 控制台按轮次播放含轮首播报的成品母带，每次开播以当前播放位置作为时间轴零点。
- 服务端按 `timeline.json` 派发 `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH` 动作，参与者页面与主持人控制台通过 STOMP 广播保持同步。
- 四轮各 10 题，共 40 题；每轮首题与轮首播报同时开始，题间播报在下一题上屏时播放。
- 中途加入的参与者自加入时刻起作答；锁卷时未提交的题目按参与时长补记耗时。
- 每轮结束写入该轮榜单快照，用于现场评奖；轮次榜与累计分数在结算前不对外公开。

### 1.2 线上模式

- 入口为 `/`，显式入口为 `/online?event=jisong`。
- 覆盖题库全部题目，不使用轮次；浏览器按音频与题目级标记自动推进，已答题目可顺序进入下一题。
- 进度保存在浏览器本地，刷新后继续作答；锁定后成绩进入线上榜与总榜。

### 1.3 榜单

- 三类榜单：线下每轮榜单、线上积分榜、总榜。
- 总榜为每位参与者线下各轮最佳成绩之和与线上锁定成绩的合并结果。
- 结算动作触发时，榜单写入 `leaderboards.json` 与 `leaderboard_summary.txt`，服务重启后仍可读取历史结果。

## 2. 技术栈

| 层 | 技术 |
| :--- | :--- |
| 前端 | Vue 3.5、Vite 7、Vue Router 4、Pinia 3、Ant Design Vue 4、STOMP.js、qrcode |
| 后端 | Spring Boot 3.5.5、Spring Web、Spring WebSocket（STOMP）、Jackson，Java 17 字节码 |
| 音频 | 阿里云 OSS 公共读对象，MP3，支持 Range 请求 |
| 运行环境 | Debian 13、OpenJDK 21、systemd、Nginx、certbot |
| 流水线 | GitHub Actions：`ci.yml` 校验，`deploy.yml` 发布与回滚 |

## 3. 目录结构

```text
backend/                 Spring Boot 服务
  data/                  编排文件、题库与本地音频目录
  src/main/java/         控制器、服务、时间轴调度、状态机与 DTO
  src/test/java/         后端测试
frontend/                Vue 3 单页应用
  src/views/             参与者入口、答题页与主持人控制台
  src/components/        线上与线下答题面板、进度与结算组件
  src/stores/            活动状态与线上进度
  .env.example           前端环境变量样例
  .env.production        生产域名与音频地址
deploy/                  服务器初始化、发布激活脚本与 Nginx/systemd 模板
docs/                    架构、业务、接口、数据契约与运维文档
tools/                   母带合成与移动端流程预览工具
```

## 4. 页面与入口

| 路由 | 用途 |
| :--- | :--- |
| `/` | 线上模式入口；带 `?mode=offline` 时进入线下模式 |
| `/online?event=jisong` | 显式线上入口 |
| `/offline?event=jisong` | 线下模式的路径形态入口，与 `?mode=offline` 等效 |
| `/play` | 参与者答题页，缺少身份或模式时回落到对应入口 |
| `/host` | 主持人控制台，需要主持人密码 |

活动标识固定为 `jisong`，入口未显式指定活动时使用该值。线下现场二维码地址为 `https://game.himematsu.cn/?mode=offline`，由主持人控制台按下当前公网地址生成。

## 5. 数据文件

| 文件 | 位置 | 作用 | 是否入库 |
| :--- | :--- | :--- | :--- |
| `timeline.json` | `backend/data/` | 线下四轮编排：轮次区间、题目音频片段与动作时间点 | 是 |
| `timeline-online.json` | `backend/data/` | 线上题集编排：全题库顺序与逐题标记 | 是 |
| `questions.json` | `backend/data/` | 40 道题目的题干、选项、正确答案、作品名、解析与出题人 | 是 |
| `leaderboards.json` | `backend/data/` | 线下轮次榜单快照 | 否，运行时生成 |
| `online_leaderboard.json` | `backend/data/` | 线上锁定成绩 | 否，运行时生成 |
| `leaderboard_summary.txt` | `backend/data/` | 结算文本摘要 | 否，运行时生成 |
| `event-offline.mp3` | `backend/data/` | 本地联调用的线下母带 | 否，音频不进入版本库 |

题目字段与编排字段的完整契约见 [`docs/data-and-config/timeline-spec.md`](docs/data-and-config/timeline-spec.md)，双母带形态见 [`docs/data-and-config/dual-master-audio.md`](docs/data-and-config/dual-master-audio.md)。

## 6. 配置项

### 6.1 前端环境变量

| 变量 | 缺省 | 说明 |
| :--- | :--- | :--- |
| `VITE_PUBLIC_BASE_URL` | 当前页面 origin | 二维码与加入地址使用的外网地址 |
| `VITE_AUDIO_URL` | 空 | 主持人控制台直连的外部音频地址；留空时请求 `/api/events/{eventId}/audio`，由后端优先输出编排目录中的本地音频，本地缺失时跳转 OSS |
| `VITE_API_BASE` | 空 | 前后端不同域时填写接口前缀 |
| `VITE_WS_BASE` | 空 | 前后端不同域时填写 WebSocket 前缀 |

`frontend/.env.production` 保存生产域名；`npm run build` 注入 `VITE_PUBLIC_BASE_URL`，`VITE_AUDIO_URL` 保持为空。

### 6.2 后端配置

| 配置项 | 缺省值 | 说明 |
| :--- | :--- | :--- |
| `server.port` | `8080` | 后端监听端口，只对 Nginx 暴露 |
| `app.frontend-origin` | `http://localhost:5173` | WebSocket 允许的跨域来源 |
| `app.host-password` | `123456` | 主持人控制台密码 |
| `app.audio-url` | OSS `bgm/event-offline-v2.mp3` | 线下外链地址；本地音频可读时优先本地流 |
| `app.audio-url-online` | OSS `bgm/event-online-v2.mp3` | 线上外链地址；本地音频可读时优先本地流 |
| `app.timeline-file` | `data/timeline.json` | 线下编排文件 |
| `app.online-timeline-file` | `data/timeline-online.json` | 线上编排文件 |
| `app.leaderboard-file` | `data/leaderboards.json` | 线下榜单文件 |
| `app.online-leaderboard-file` | `data/online_leaderboard.json` | 线上榜单文件 |
| `app.leaderboard-txt-file` | `data/leaderboard_summary.txt` | 结算文本摘要文件 |

编排同目录的音频文件可读时，后端以 Range 流输出本地文件；本地文件不可读时使用两个外链地址。该顺序同时用于本地联调与不含音频文件的服务端部署。

### 6.3 服务器环境变量

服务器通过 `/etc/bgm-guess/bgm-guess.env` 覆盖后端配置：

```text
APP_HOST_PASSWORD=123456
APP_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
APP_AUDIO_URL_ONLINE=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3
APP_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline.json
APP_ONLINE_TIMELINE_FILE=/opt/bgm-guess/shared/data/timeline-online.json
APP_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/leaderboards.json
APP_ONLINE_LEADERBOARD_FILE=/opt/bgm-guess/shared/data/online_leaderboard.json
APP_NICKNAME_CLAIMS_FILE=/opt/bgm-guess/shared/data/nickname_claims.json
APP_LEADERBOARD_TXT_FILE=/opt/bgm-guess/shared/data/leaderboard_summary.txt
APP_FRONTEND_ORIGIN=https://game.himematsu.cn
```

修改环境变量后执行 `systemctl restart bgm-guess` 生效。

## 7. 本地开发

前置条件：Node.js 20.19 及以上或 22.12 及以上、JDK 17 及以上、Maven。

### 7.1 启动后端

```bash
cd backend
mvn -s maven-settings.xml spring-boot:run
```

后端地址为 `http://localhost:8080`，健康检查为 `GET /api/health`。

### 7.2 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端地址为 `http://localhost:5173`，开发服务器把 `/api` 与 `/ws` 代理到后端。

### 7.3 测试与构建

```bash
cd backend
mvn -s maven-settings.xml test
mvn -s maven-settings.xml package -DskipTests

cd ../frontend
npm run build
```

后端产物为 `backend/target/bgm-guess-backend-0.0.1-SNAPSHOT.jar`，前端产物为 `frontend/dist`。

本地联调时二维码使用主持人页面当前地址；通过 `127.0.0.1` 打开的控制台只能被本机扫码，需要他人扫码时使用正式域名或局域网地址，并以 `VITE_PUBLIC_BASE_URL` 固定二维码地址。

## 8. 服务器部署

### 8.1 运行形态

```text
浏览器
  │ 443/TLS
  ▼
Nginx（game.himematsu.cn）
  ├── /        → /opt/bgm-guess/current/dist
  ├── /api/    → 127.0.0.1:8080
  └── /ws      → 127.0.0.1:8080（Upgrade）
                    │
                    ▼
              systemd: bgm-guess
                    │
                    └── /opt/bgm-guess/shared/data（编排、题库、榜单）
音频：后端优先流式输出本地母带；服务端没有本地音频时返回 302，由浏览器直接向阿里云 OSS 请求 MP3
```

### 8.2 目录布局

```text
/opt/bgm-guess/
  releases/           每个版本一个目录，内含 app.jar 与 dist/
  current -> releases/<release-id>
  shared/data/        timeline.json、timeline-online.json、questions.json 与运行时榜单
  bin/activate-release.sh
/etc/bgm-guess/bgm-guess.env
/etc/systemd/system/bgm-guess.service
/etc/nginx/sites-available/game.himematsu.cn.conf
```

发布流水线不修改 `shared/data`，编排、题库与榜单在该目录长期保存，跨版本复用。

### 8.3 初始化

以 root 执行：

```bash
bash deploy/bootstrap-server.sh
```

脚本创建 `bgm` 与 `deploy` 用户、目录结构、systemd 单元与 `bgm-guess.env` 初始文件。可选 `INSTALL_SUDOERS=1` 安装发布账号的重启授权。

初始化完成后：

1. 把 `timeline.json`、`timeline-online.json`、`questions.json` 放入 `/opt/bgm-guess/shared/data`，目录中不放音频。
2. 编辑 `/etc/bgm-guess/bgm-guess.env`，填写主持人密码、两个音频地址与三个数据文件路径。
3. 安装 `deploy/nginx-game.conf.example` 为子域站点。
4. 把发布公钥写入 `/home/deploy/.ssh/authorized_keys`。
5. 触发一次 `Deploy` 工作流或手工上传发布包。

### 8.4 发布与回滚

`deploy/activate-release.sh <release-id>` 完成以下动作：切换 `current` 软链、重启服务、轮询 `/api/health` 最多 60 秒、失败时回退到上一版本、保留最近 5 个版本。

手工发布：

```bash
scp -P 33 release-<release-id>.tar.gz deploy@<host>:/opt/bgm-guess/releases/
ssh -p 33 deploy@<host> "cd /opt/bgm-guess/releases && mkdir -p <release-id> && tar -xzf release-<release-id>.tar.gz -C <release-id> && bash <release-id>/deploy/activate-release.sh <release-id>"
```

回滚即激活历史版本目录：

```bash
bash /opt/bgm-guess/bin/activate-release.sh <previous-release-id>
```

### 8.5 GitHub Actions

- `ci.yml`：拉取请求与手动触发时构建前端、验证后端。
- `deploy.yml`：手动触发；`release_id` 留空时构建并发布当前提交，填写已有版本号时执行回滚。

发布所需的 Secrets 与 Variables：

| 类型 | 名称 | 示例 |
| :--- | :--- | :--- |
| Secret | `SSH_HOST` | `81.71.64.37` |
| Secret | `SSH_USER` | `deploy` |
| Secret | `SSH_PORT` | `33` |
| Secret | `SSH_PRIVATE_KEY` | OpenSSH 格式私钥 |
| Variable | `PUBLIC_BASE_URL` | `https://game.himematsu.cn` |
| Variable | `AUDIO_URL` | 保留的变量名，构建流程不读取 |

`main` 推送自动发布在服务器初始化与 Secrets 配置完成后启用。

## 9. 音频托管

| 用途 | 对象 | 规格 |
| :--- | :--- | :--- |
| 线下母带 | `bgm/event-offline-v2.mp3` | 58,304,095 字节，2429.278 秒，MP3 CBR 192 kbps |
| 线上母带 | `bgm/event-online-v2.mp3` | 53,869,130 字节，2244.491 秒，MP3 CBR 192 kbps |

两个对象于 2026-11-12 上传，均为公共读，`Content-Type` 为 `audio/mpeg`，支持 Range 请求，普通请求返回 `200`、Range 请求返回 `206`。两个对象均带 `Cache-Control: public, max-age=31536000, immutable`。生产环境的 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 指向 `bgm/event-offline-v2.mp3` 与 `bgm/event-online-v2.mp3`；音频对象与配套编排在同一发布步骤切换。

服务器不保存音频文件；`/api/events/{eventId}/audio` 返回线下对象跳转，`?mode=online` 返回线上对象跳转。本地开发运行时这两个接口输出编排目录中的本地母带。

## 10. 验收清单

- `https://game.himematsu.cn/`、`/host`、`/api/health` 返回 `200`。
- `https://game.himematsu.cn/?mode=offline` 进入线下入口，`/offline?event=jisong` 行为一致。
- 主持人登录密码为 `123456`，控制台二维码指向 `https://game.himematsu.cn/?mode=offline`。
- `/api/events/jisong/audio` 返回 `302` 到线下母带，`?mode=online` 返回 `302` 到线上母带。
- `/api/events/jisong/online/questions` 返回 40 题，`audioUrl` 为线上母带地址。
- 主持人控制台 `<audio>` 的 `src` 为线下母带地址，四轮区间为 0/562469、562469/1259149、1259149/1871684、1871684/2429278。
- `/ws` 完成 WebSocket Upgrade，参与者页面可收到状态广播。
- 手机端可播放、可拖动进度，锁卷与揭晓表现正常。
- 重启 `bgm-guess` 后榜单记录仍可读取。

## 11. 运维速查

```bash
systemctl status bgm-guess
systemctl restart bgm-guess
journalctl -u bgm-guess -n 200 --no-pager
curl -fsS http://127.0.0.1:8080/api/health
nginx -t && systemctl reload nginx
ls -l /opt/bgm-guess/shared/data
```

故障排查见 [`docs/ops-and-deployment/troubleshooting.md`](docs/ops-and-deployment/troubleshooting.md)，服务器现状见 [`docs/ops-and-deployment/deployment-status.md`](docs/ops-and-deployment/deployment-status.md)。

## 12. 文档索引

- 架构：[`overview.md`](docs/architecture/overview.md)、[`state-machine.md`](docs/architecture/state-machine.md)、[`data-flow.md`](docs/architecture/data-flow.md)
- 业务规则：[`event-rules.md`](docs/business/event-rules.md)、[`host-sop.md`](docs/business/host-sop.md)
- 后端：[`backend-architecture.md`](docs/backend/backend-architecture.md)、[`api-spec.md`](docs/backend/api-spec.md)
- 前端：[`frontend-architecture.md`](docs/frontend/frontend-architecture.md)、[`views-and-ui.md`](docs/frontend/views-and-ui.md)
- 数据与配置：[`timeline-spec.md`](docs/data-and-config/timeline-spec.md)、[`dual-master-audio.md`](docs/data-and-config/dual-master-audio.md)、[`announcer-voice.md`](docs/data-and-config/announcer-voice.md)
- 功能规格：[`dual-mode-development-spec.md`](docs/product/dual-mode-development-spec.md)
- 运维：[`cicd.md`](docs/ops-and-deployment/cicd.md)、[`manual-deploy-runbook.md`](docs/ops-and-deployment/manual-deploy-runbook.md)、[`oss-audio.md`](docs/ops-and-deployment/oss-audio.md)
