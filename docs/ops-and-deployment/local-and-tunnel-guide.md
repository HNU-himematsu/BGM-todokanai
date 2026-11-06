# 本地启动与现场内网穿透指南

## 1. 运行环境

| 组件 | 要求 |
| :--- | :--- |
| JDK | 17 或以上 |
| Maven | 3.8 或以上 |
| Node.js | 18 或以上 |
| npm | 9 或以上 |
| 浏览器 | Chrome / Edge 111+、Safari 16.2+、Firefox 113+ |
| 网络 | 手机可访问的公网 HTTPS 地址，或现场局域网地址 |

## 2. 素材准备

### 2.1 文件位置

```text
backend/data/
├── questions.json
├── timeline.json
├── timeline-online.json
├── event-offline.mp3
├── event-online.mp3
├── leaderboards.json
├── online_leaderboard.json
└── leaderboard_summary.txt
```

`questions.json`、两份编排与两份音频由运营方提供。榜单文件可由服务在首次写入时创建。`event-offline.mp3` 供主持人控制台使用，`event-online.mp3` 供线上参与者使用；两者内容不同，时长分别为 2780.110 秒与 2593.149 秒。

### 2.2 题库

- 全部题目使用唯一 `id`。
- 每题包含四个选项与 0 至 3 的正确选项下标。
- `workName`、`explanation` 与 `author` 完整，供线下揭晓使用。
- `type` 为 `WORK` 或 `ATMOSPHERE`。
- `difficulty` 为 `EASY`、`MEDIUM` 或 `HARD`。

### 2.3 编排

- `eventId` 与访问地址中的活动标识一致。
- 全部题库题目至少被一个 `LOAD` 引用。
- 每题包含 `LOAD`、`NEXT`、`LOCK` 与 `REVEAL`。
- 每轮包含 `FINISH`。
- 相邻题目音频片段首尾相接。
- 第一段从音频 0 毫秒开始，最后一段到音频总时长结束。
- 线上题目满足 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。

### 2.4 音频

- 文件名与各自编排的 `audioFile` 一致：线下为 `event-offline.mp3`，线上为 `event-online.mp3`。
- 编码可被手机与主持人浏览器解码。
- 音频接口支持 HTTP Range。
- 线上母带为纯 BGM，线下母带在每个轮次起点包含开幕播报段。
- 线上母带为 VBR，上线前按 [`../data-and-config/audio-seek-quality.md`](../data-and-config/audio-seek-quality.md) 复测逐题定位；线下母带为 CBR 192 kbps。
- 题目片段之间没有空白。

## 3. 本地开发启动

### 3.1 后端

```powershell
cd backend
mvn clean compile
mvn spring-boot:run
```

`spring-boot:run` 与 JAR 启动使用同一份 `application.yml`：线下地址取 `app.audio-url` 指向的 OSS 对象 `bgm/event-offline.mp3`，线上题集取 `app.audio-url-online` 指向的 OSS 纯 BGM 对象。`/api/events/{eventId}/audio` 在这些配置下返回 302 跳转，音频流量不经过后端。把 `app.audio-url` 置空时，后端改为从 `backend/data/event-offline.mp3` 以 Range 流输出线下母带。

健康检查：

```powershell
Invoke-RestMethod http://localhost:8080/api/health
```

预期：

```text
bgm-guess-backend is running
```

### 3.2 前端

```powershell
cd frontend
npm install
npm run dev
```

开发地址：

```text
http://localhost:5173/
```

Vite 把 `/api` 与 `/ws` 代理到 `http://localhost:8080`。

本地前端与生产构建都使用 `.env` 文件中的 OSS 地址：`.env.development.local` 的 `VITE_AUDIO_URL` 指向 `bgm/event-offline.mp3`，主持人控制台直接从 OSS 加载音频。把该值置空时，主持人控制台回落到 `/api/events/{eventId}/audio`。

### 3.3 本地入口

| 场景 | 地址 |
| :--- | :--- |
| 线上 | `http://localhost:5173/` |
| 显式线上 | `http://localhost:5173/?mode=online&event=jisong` |
| 线下 | `http://localhost:5173/?mode=offline` |
| 主持人 | `http://localhost:5173/host` |

手机与电脑位于同一局域网时，可把 `localhost` 替换为电脑局域网地址。跨设备访问前确认系统防火墙允许 Vite 端口。

## 4. 生产构建

### 4.1 前端构建

```powershell
cd frontend
npm install
npm run build
```

构建产物位于 `frontend/dist`。

### 4.2 后端运行

```powershell
cd backend
mvn clean package
java -jar target/bgm-guess-backend-0.0.1-SNAPSHOT.jar
```

后端默认监听 `8080`。

### 4.3 单域名要求

线上进度、`participantId` 与主持人令牌按浏览器 origin 隔离。现场入口应满足：

- 前端页面、`/api` 与 `/ws` 使用同一协议、域名与端口。
- HTTPS 页面连接 `wss://`。
- 活动期间不更换公网域名。
- 不把前端与 API 分配到不同 origin 后再依赖跨域共享 `localStorage`。

## 5. Nginx 部署

```nginx
server {
    listen 80;
    server_name bgm.example.test;

    root /var/www/bgm-guess/frontend/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location ~ ^/api/events/[^/]+/audio$ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 120s;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 120s;
    }

    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
```

检查项：

- `/` 返回前端 SPA。
- `/api/health` 返回后端健康信息。
- `/api/events/jisong/audio` 支持 Range，音频路由关闭代理缓冲并直接透传客户端区间请求。
- `/ws` 返回 WebSocket 升级响应。
- 刷新 `/host` 不出现 404。

## 6. Cloudflare Quick Tunnel

当现场电脑没有公网 IP 时，使用内网穿透提供 HTTPS 入口。

### 6.1 穿透目标

生产演示时，Quick Tunnel 指向同时提供前端、API 与 WebSocket 的 Nginx origin：

```powershell
cloudflared.exe tunnel --url http://localhost:80
```

只穿透后端 `8080` 的隧道无法加载前端页面，不能作为现场参与者入口。

开发联调时可指向 Vite：

```powershell
cloudflared.exe tunnel --url http://localhost:5173
```

Vite 必须保留 `/api` 与 `/ws` 代理配置。

### 6.2 公网地址

终端输出类似：

```text
https://xxxx-xxxx-xxxx.trycloudflare.com
```

该地址作为 `publicBaseUrl`。参与者入口：

```text
{publicBaseUrl}/
```

线下二维码：

```text
{publicBaseUrl}/?mode=offline&event=jisong
```

主持人入口：

```text
{publicBaseUrl}/host
```

### 6.3 稳定性

- Quick Tunnel 进程与终端窗口保持运行。
- 笔记本接通电源，并关闭接通电源时的自动休眠。
- 现场准备备用网络，例如手机热点。
- 重新启动隧道会生成新域名；新域名会形成新的浏览器存储空间。
- 活动开始前固定域名并完成扫码、音频与锁定测试。

## 7. 现场配置

### 7.1 后端参数

`backend/src/main/resources/application.yml`：

| 配置 | 作用 |
| :--- | :--- |
| `server.port` | 后端端口，默认 `8080`。 |
| `app.frontend-origin` | 允许访问 API 的前端 origin。 |
| `app.host-password` | 主持人登录密码。 |
| `app.timeline-file` | 线下编排文件路径。 |
| `app.online-timeline-file` | 线上编排文件路径；置空时线上题集取 `app.timeline-file`。 |
| `app.audio-url` | 线下音频地址；置空时输出编排同目录的本地音频。 |
| `app.audio-url-online` | 线上音频地址；置空时回落 `app.audio-url`，再回落本地线上音频。 |
| `app.leaderboard-file` | 线下快照文件路径。 |
| `app.online-leaderboard-file` | 线上锁定记录文件路径。 |
| `app.leaderboard-txt-file` | 文本总结路径。 |

`app.online-leaderboard-file` 缺省为 `data/online_leaderboard.json`，与 `app.leaderboard-file` 使用同一数据目录。两个音频地址在本地联调与生产环境都指向 OSS 对象；置空时回落本地文件。配置契约见 [`../data-and-config/dual-master-audio.md`](../data-and-config/dual-master-audio.md) 第 5 节。

### 7.2 前端参数

`VITE_API_BASE` 缺省为 `/api`。同域部署保持缺省值，使前端、API、音频与 WebSocket 共用一个 origin。主持人控制台的音频地址由 `VITE_AUDIO_URL` 注入，指向线下母带对象。

### 7.3 主持人密码

活动前修改默认密码，设置足够长度，并通过受控渠道交给主持人。主持人令牌只保存在当前标签页 `sessionStorage`。

## 8. 上线前流程

1. 启动后端。
2. 构建并启动前端静态服务。
3. 启动隧道或确认公网域名。
4. 验证 `/api/health`。
5. 验证根地址进入线上登记页。
6. 验证线下二维码进入线下登记页。
7. 验证主持人登录与轮次列表。
8. 使用手机完整播放线上音频并完成一次预览。
9. 使用测试参与者锁定一次成绩。
10. 验证线上榜、总榜与文本总结更新。
11. 清理测试锁定记录与测试线下快照，保留正式活动初始状态。
12. 备份空榜单文件与素材。

## 9. 活动期间操作

### 9.1 线上

- 参与者访问根地址并输入昵称。
- 点击开始后连续播放全部题目音频。
- 全部题目结束后出现预览分。
- 参与者选择重试或锁定。
- 锁定后显示线上榜与总榜名次。

线上不需要主持人启动轮次。

### 9.2 线下

- 主持人登录 `/host`。
- 选择轮次并点击「开始播放」。
- 参与者扫描二维码加入。
- 服务端按时间轴推进，主持人按需暂停、恢复或手动兜底。
- `FINISH` 生成本轮榜。
- 主持人切换下一轮并重新开始。

### 9.3 榜单核对

- 本轮榜只显示线下成绩。
- 线上榜只显示线上锁定成绩。
- 总榜合并线下各轮最佳成绩与线上成绩。
- 文本总结包含三个板块。

## 10. 备份

活动前备份：

- `questions.json`
- `timeline.json`
- 完整音频
- 空的 `leaderboards.json`
- 空的 `online_leaderboard.json`

每轮结束或线上锁定高峰后备份：

- `leaderboards.json`
- `online_leaderboard.json`
- `leaderboard_summary.txt`

备份文件保存在独立目录，不以相同文件名覆盖正式存储路径。

## 11. 部署验收

- 根地址进入线上模式。
- 线下二维码指向 `/offline` 路径。
- 同一 origin 提供页面、API、音频与 WebSocket。
- 手机可加载音频并自动出题。
- 页面隐藏后可恢复进度。
- 线上成绩锁定后线上榜与总榜更新。
- 主持人可查看三类榜单与文本总结。
- 重启服务后已写入的榜单记录仍可读取。
