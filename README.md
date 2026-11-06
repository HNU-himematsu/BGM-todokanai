# 旮旯原声之届不到的 BGM

百团大战现场猜歌活动的第一版可运行骨架。

## 项目结构

```text
frontend/   Vue 3 + Vite + JavaScript
backend/    Spring Boot + WebSocket/STOMP + 内存活动状态 + 编排文件与 JSON 成绩文件
```

## 本地运行

### 启动后端

需要 Java 17 或更高版本，以及 Maven。

```bash
cd backend
mvn -s maven-settings.xml spring-boot:run
```

后端默认地址：`http://localhost:8080`

`spring-boot:run` 与 JAR 启动都读取 `app.audio-url` 指向的线下 OSS 对象；该值置空时后端改为流式输出编排同目录的本地音频。

### 启动前端

需要 Node.js 18 或更高版本。

```bash
cd frontend
npm install
npm run dev
```

前端默认地址：`http://localhost:5173`

本地调试时，二维码会使用主持人页面当前的地址。如果主持人通过 `127.0.0.1` 打开，二维码只能在这台电脑上使用；让别人访问时必须使用服务器域名或局域网 IP。

## 页面

- 参与者入口：`/`
- 参与者答题：`/play`
- 主持人控制台：`/host`

活动固定使用 `demo` 活动标识；线下轮次与动作时间轴由 `backend/data/timeline.json` 定义，线上题集由 `backend/data/timeline-online.json` 定义，题目文本由同目录的 `backend/data/questions.json` 保存。线上模式播放 OSS 对象 `姬松活动/bgm-music.mp3` 的纯 BGM 母带，线下模式播放 `bgm/event-offline.mp3` 的含播报母带；部署服务器不保存音频文件，外部地址置空时后端改为输出 `backend/data/` 中的同目录音频。题目、当前活动状态和参与者分数保存在后端内存中；结算动作触发时，最终排行榜会写入后端电脑上的 `backend/data/leaderboards.json`，重启后仍可读取历史榜单。

## 现场使用方式

1. 主持人电脑打开 `/host`，输入密码 `123456`。
2. 主持人把控制台中的二维码展示给现场参与者。
3. 参与者扫描二维码，输入昵称加入活动。
4. 主持人选择轮次并点击开始播放，控制台播放音频并建立时间轴零点；系统按编排自动推进题目上屏、锁卷与揭晓。每轮开头先播放轮首播报，首题在播报结束后上屏。
5. 中途加入的参与者自加入时刻起即可作答并进入排行榜；锁卷时未提交的题目按参与时长补记耗时。
6. 答题中的排行榜和累计分数不公开，结算后才统一显示最终排行榜与分数。
7. 到达轮次结束标记时时间轴与音频自动暂停；主持人切换轮次或重置后接待下一批参与者。
8. BGM 由主持人控制台直接播放 OSS 对象，网页负责题目同步和答题。

如果要修改主持人密码，编辑 `backend/src/main/resources/application.yml` 中的 `app.host-password`。

## 后续接入

当前接口已经按活动、玩家、题目和答题记录拆分，后续可以将 `ActivityService` 的内存实现替换为 MySQL + JPA，并保留前端页面和 WebSocket 消息结构。

## 部署提示

生产环境建议使用同一个域名：

```text
https://你的域名/       -> frontend/dist
https://你的域名/api/  -> Spring Boot 127.0.0.1:8080
https://你的域名/ws    -> Spring Boot WebSocket
```

部署步骤：

1. 在域名 DNS 中添加 `A` 记录，指向服务器公网 IP。
2. 在服务器安装 Nginx，并将 `deploy/nginx.conf.example` 中的 `server_name` 和 `root` 改成实际值。
3. 在本地或服务器构建前端：

   ```bash
   cd frontend
   npm run build
   ```

4. 将 `frontend/dist/` 上传到 Nginx 配置中的目录。
5. 在服务器启动 Spring Boot：

   ```bash
   cd backend
   mvn -s maven-settings.xml package -DskipTests
   java -jar target/bgm-guess-backend-0.0.1-SNAPSHOT.jar
   ```

6. 用浏览器访问 `https://你的域名/host`，输入密码 `123456`。此时二维码会自动使用正式域名。

如果主持人仍想在本机通过 `localhost` 打开控制台，但希望二维码指向正式域名，在构建前复制 `.env.example` 为 `.env.production`，填写：

```env
VITE_PUBLIC_BASE_URL=https://你的域名
```

更推荐正式活动时直接通过正式域名打开 `/host`。启用 HTTPS 后，前端会自动使用 `wss://` 连接 WebSocket。不要让参与者访问 `localhost` 或 `127.0.0.1`，也不要把后端 `8080` 端口直接写进二维码。
