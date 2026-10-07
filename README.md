# 旮旯原声之届不到的 BGM

百团大战现场猜歌活动的第一版可运行骨架。

## 项目结构

```text
frontend/   Vue 3 + Vite + JavaScript
backend/    Spring Boot + WebSocket/STOMP + 内存活动状态 + JSON 成绩文件
```

## 本地运行

### 启动后端

需要 Java 17 或更高版本，以及 Maven。

```bash
cd backend
mvn -s maven-settings.xml spring-boot:run
```

后端默认地址：`http://localhost:8080`

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

- 参与者入口：`/join`
- 参与者答题：`/play`
- 主持人控制台：`/host`

首版活动固定使用 `demo` 活动和 6 道示例题。题目、当前活动状态和参与者分数保存在后端内存中；每轮结束时，最终排行榜会写入后端电脑上的 `backend/data/leaderboards.json`，重启后仍可读取历史榜单。

## 现场使用方式

1. 主持人电脑打开 `/host`，输入密码 `123456`。
2. 主持人把控制台中的二维码展示给现场参与者。
3. 参与者扫描二维码，输入昵称加入活动。
4. 第一题开始后新加入的人可以观看题目，但会被标记为旁观者，后端不会接受其答题，也不会进入最终排行榜。
5. 主持人在控制台依次点击开始本题、结束答题、公布答案、下一题。
6. 答题中的排行榜和累计分数不公开，最后一题结束后才统一显示最终排行榜与分数。
7. BGM 暂由现场音箱播放，网页负责题目同步和答题。

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
