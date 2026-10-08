# 前端架构设计

## 1. 工程目录结构

前端基于 Vue 3 + Vite 构建，源码目录规划如下：

```text
frontend/
├── package.json                            # 依赖与脚本定义
├── vite.config.js                          # Vite 构建与代理配置
├── .env.example                            # 环境变量模板
├── src/
│   ├── main.js                             # 前端入口，注册 Pinia、路由与 Ant Design
│   ├── App.vue                             # 顶层布局容器
│   ├── styles.css                          # 全局样式与移动端适配变量
│   ├── router/
│   │   └── index.js                        # 路由定义 (/ -> JoinView, /play -> PlayView, /host -> HostView)
│   ├── services/
│   │   ├── api.js                          # Axios HTTP 请求封装
│   │   └── socket.js                       # STOMP WebSocket 客户端生命周期封装
│   ├── stores/
│   │   └── activity.js                     # Pinia 状态树 (玩家凭据、活动状态、排行榜)
│   └── views/
│       ├── JoinView.vue                    # 扫码登记与昵称输入页面
│       ├── PlayView.vue                    # 移动端实时答题与个人结算页面
│       └── HostView.vue                    # 主持人控制台（音频播放、轮次切换、状态调度）
```

## 2. 状态管理设计 (`stores/activity.js`)

前端利用 Pinia 集中管理响应式状态：
- **`userState`**：缓存当前参与者的 `playerId`、`nickname` 以及关联的 `eventId`；凭据保存在本地 `sessionStorage` 防止刷新丢失，昵称同时保存在本地存储中用于回填，轮次重置回到登记页时无需重复输入。
- **`activityState`**：维护当前房间的全局状态快照（`status`、`question`、`questionAudioStartEpochMs`、`questionAudioEndEpochMs`、`timelineRunning`、`answeredCount` 等）。
- **`leaderboard`**：存储当前轮次或全场排行榜数据。

## 3. 网络与实时通讯层封装

### 3.1 REST API 封装 (`services/api.js`)
- 使用 Axios 实例统一配置 `baseURL`（优先读取环境变量 `VITE_API_BASE`，缺省指向 `/api`）。
- 设定 8000ms 超时限制，针对控制台请求自动挂载 `X-Host-Token` 头信息。
- 音频流接口 `GET /api/events/{eventId}/audio` 的地址供控制台音频元素直接引用。

### 3.2 WebSocket 封装 (`services/socket.js`)
- 使用 `@stomp/stompjs` 库中的 `Client` 对象。
- 支持根据当前页面协议自动推导 WebSocket 地址（`https:` 对应 `wss:`，`http:` 对应 `ws:`）。
- 设置 `reconnectDelay: 3000`，在现场弱网导致长连接断开后，每 3 秒发起一次静默重连，保障消息通道自动恢复。

## 4. 路由定义 (`router/index.js`)

- `/`：默认路由，指向 `JoinView.vue`，展示活动标题与昵称登记表单；轮次结束后参与者由答题页返回本页，表单自动回填已保存的昵称。
- `/play`：答题核心路由，指向 `PlayView.vue`，仅在存在有效登记凭据时准入，否则回退至 `/`。
- `/host`：管理路由，指向 `HostView.vue`，仅能通过直接输入网址进入，前端各页面均不设置指向该路由的跳转链接；进入控制面板前需完成管理员密码核验。

## 5. 控制台音频播放

- `HostView.vue` 内置音频播放元素，音源指向 `GET /api/events/{eventId}/audio`。
- 主持人点击开始播放时，播放器定位到当前轮次的 `startMs` 位置开始播放，同时调用 `/host/start` 建立时间轴零点。
- 收到 `ACTIVITY_PAUSED` / `ACTIVITY_RESUMED` / `ROUND_ENDED` / `ACTIVITY_RESET` 广播时，播放器分别执行暂停、恢复、停在结束标记与停止并回到轮次起点。