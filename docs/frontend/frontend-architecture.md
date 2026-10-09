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
│   ├── theme.js                            # 品牌主色唯一定义点 (ACCENT 常量)
│   ├── styles.css                          # 全局样式与移动端适配变量
│   ├── router/
│   │   └── index.js                        # 路由定义 (/ -> JoinView, /play -> PlayView, /host -> HostView)
│   ├── services/
│   │   ├── api.js                          # Axios HTTP 请求封装
│   │   └── socket.js                       # STOMP WebSocket 客户端生命周期封装
│   ├── stores/
│   │   └── activity.js                     # Pinia 状态树 (玩家凭据、活动状态、排行榜)
│   └── views/
│       ├── JoinView.vue                    # 扫码登记、昵称输入与答题规则弹窗
│       ├── PlayView.vue                    # 移动端实时答题与个人结算页面
│       └── HostView.vue                    # 主持人控制台（音频播放、轮次切换、状态调度）
```

## 2. 状态管理设计 (`stores/activity.js`)

前端利用 Pinia 集中管理响应式状态：
- **`userState`**：缓存当前参与者的 `playerId`、`nickname` 以及关联的 `eventId`；凭据保存在本地 `sessionStorage` 防止刷新丢失，昵称同时保存在本地存储中用于回填，轮次切换或活动重置回到登记页时无需重复输入。
- **`activityState`**：维护 `GET /api/events/{eventId}/state` 返回的参与者快照（`status`、`question`、`canAnswer`、`hasAnswered`、`selectedOption`、`questionAudioStartEpochMs`、`questionAudioEndEpochMs`、`timelineRunning`、`answeredCount` 等）；活动状态以服务端快照为准。
- **`leaderboard`**：存储当前轮次或全场排行榜数据。

## 3. 网络与实时通讯层封装

### 3.1 REST API 封装 (`services/api.js`)
- 使用 Axios 实例统一配置 `baseURL`（优先读取环境变量 `VITE_API_BASE`，缺省指向 `/api`）。
- 设定 8000ms 超时限制，针对控制台请求自动挂载 `X-Host-Token` 头信息。
- 音频流接口 `GET /api/events/{eventId}/audio` 的地址供控制台音频元素直接引用。
- 参与者与主持人端点、请求体及响应字段遵循 [`api-spec.md`](../backend/api-spec.md)；提交答案与主持人指令成功后以响应或重新获取的状态快照更新界面。

### 3.2 WebSocket 封装 (`services/socket.js`)
- 使用 `@stomp/stompjs` 库中的 `Client` 对象。
- 支持根据当前页面协议自动推导 WebSocket 地址（`https:` 对应 `wss:`，`http:` 对应 `ws:`）。
- 设置 `reconnectDelay: 3000`，在现场弱网导致长连接断开后，每 3 秒发起一次静默重连，保障消息通道自动恢复。
- 订阅 `/topic/events/{eventId}`，广播消息提供事件类型与活动标识；收到状态相关广播或完成重连后，请求对应的参与者或主持人快照，避免仅凭广播类型拼接题目与榜单数据。

## 4. 路由定义 (`router/index.js`)

- `/`：默认路由，指向 `JoinView.vue`，引导参与者登记昵称并通过弹窗展示答题规则；轮次切换或活动重置后返回本页时表单自动回填已保存的昵称。页面仅面向参与者，不提供主持人端入口。
- `/play`：答题核心路由，指向 `PlayView.vue`，仅在存在有效登记凭据时准入，否则回退至 `/`；就绪等待阶段专注呈现联机状态与发题等待指示。
- `/host`：管理路由，指向 `HostView.vue`，仅能通过直接输入网址进入，前端各页面均不设置指向该路由的跳转链接；进入控制面板前需完成管理员密码核验。

## 5. 控制台音频播放

- `HostView.vue` 内置音频播放元素，音源指向 `GET /api/events/{eventId}/audio`。
- 主持人点击开始播放时，播放器定位到当前轮次的 `startMs` 位置开始播放，同时调用 `POST /api/events/{eventId}/host/start` 建立时间轴零点；请求失败时停止本地播放并显示错误。
- 收到 `ACTIVITY_PAUSED` / `ACTIVITY_RESUMED` / `ROUND_ENDED` / `ACTIVITY_RESET` 广播时，播放器分别执行暂停、恢复、停在结束标记与停止并回到轮次起点。
- 轮次切换读取 `startMs` / `endMs` 并重新定位播放器；服务端时间轴负责题目上屏、锁卷、揭晓与结算，播放器只负责当前轮次的现场放音。

## 6. 页面数据与接口映射

### 6.1 参与者页面

| 页面动作或状态 | 接口与数据 | 界面响应 |
| :--- | :--- | :--- |
| 登记昵称并确认规则 | `POST /api/events/{eventId}/join`，请求体为 `nickname` | 保存 `playerId` 与昵称后进入 `/play`；`FINISHED` 状态拒绝加入时显示服务端错误。 |
| 进入答题页、刷新或广播到达 | `GET /api/events/{eventId}/state?playerId={playerId}` | 以 `status`、`question`、`canAnswer`、`hasAnswered` 与 `selectedOption` 恢复题目和提交状态。 |
| 提交单选答案 | `POST /api/events/{eventId}/answers`，提交 `playerId`、`questionId` 与 `optionIndex` | 仅在 `ANSWERING` 且 `canAnswer` 为 `true` 时开放确认提交；服务端响应决定最终锁定状态。 |
| 结算排名 | 状态快照中的个人成绩及 `GET /api/events/{eventId}/leaderboard` | 仅在 `FINISHED` 展示个人得分、耗时、名次与本轮榜单。 |

`WAITING` 隐藏题目；`ANSWERING` 展示脱敏题目与音频剩余时间条；`ANSWER_LOCKED` 禁止提交且隐藏答案；`ANSWER_REVEALED` 展示答案与解析，并保持到下一次 `QUESTION_STARTED`；`FINISHED` 展示结算。时间条使用 `questionAudioStartEpochMs` / `questionAudioEndEpochMs`，暂停时依据 `timelineRunning` 冻结显示；音频段终点不控制提交截止，提交截止由 `ANSWER_LOCKED` 与 `canAnswer` 决定。`ACTIVITY_RESET` 表示玩家凭据失效，答题页返回登记页并保留昵称供回填；`ROUND_ENDED` 仅表示时间轴与音频暂停。

### 6.2 主持人页面

| 控制区与操作 | 接口与数据 | 可用条件及界面响应 |
| :--- | :--- | :--- |
| 登录 | `POST /api/host/login` | 保存 Token；主持人接口附带 `X-Host-Token`。 |
| 轮次选择 | `GET /api/events/{eventId}/host/rounds`、`POST /api/events/{eventId}/host/round` | 选择操作清空在线玩家并回到 `WAITING`；运行中且未暂停时禁用切换。 |
| 控制台快照 | `GET /api/events/{eventId}/host/state` | 从 `state`、`players`、`rounds`、`currentRoundId` 与 `timelineRunning` 构建状态栏、播放器、题目预览与作答进度。 |
| 开始、暂停、恢复 | `POST /api/events/{eventId}/host/start`、`pause`、`resume` | 按服务端状态守卫启用按钮；音频播放状态与时间轴广播同步。 |
| 手动推进 | `POST /api/events/{eventId}/host/lock`、`reveal`、`next` | 按 `status` 控制按钮可用性；`next` 所需的待上屏题目由服务端验证。成功后重新获取主持人快照。 |
| 重置 | `POST /api/events/{eventId}/host/reset` | 清空在线玩家并回到 `WAITING`；播放器停止并回到轮次起点。 |
| 榜单与导出 | `GET /api/events/{eventId}/leaderboard`、`GET /api/events/{eventId}/leaderboard/summary-txt` | `FINISHED` 展示本轮榜单，文本接口用于总榜查看和下载。 |

控制台在 `ACTIVITY_STARTED`、`QUESTION_STARTED`、`ANSWER_LOCKED`、`ANSWER_REVEALED`、`ACTIVITY_FINISHED`、`ROUND_ENDED`、`ACTIVITY_PAUSED`、`ACTIVITY_RESUMED`、`ACTIVITY_RESET`、`PLAYER_JOINED` 与 `PLAYER_ANSWERED` 广播后刷新主持人快照。参与者端在影响个人界面的状态广播后刷新参与者快照；断线重连后两端均主动刷新。轮次结束标记与 `FINISH` 的作用分别是暂停播放和结算归档，界面不把两者合并为同一状态。

## 7. 视觉主题与设计令牌规范 (`theme.js` / `App.vue` / `styles.css`)

前端视觉层建立在以亮兰紫 `rgb(188, 74, 216)`（十六进制 `#BC4AD8`）为主色的色彩体系之上。主色在 `src/theme.js` 中单点定义，组件库主题与全局 CSS 的全部主色阶元均由该常量驱动，调整色调只需修改这一处定义。

### 7.1 主色唯一定义点 (`theme.js`)

`src/theme.js` 导出品牌主色常量，构成主色系的唯一手写色值：
- `ACCENT`: `rgb(188, 74, 216)`（十六进制 `#BC4AD8`，品牌主色）

### 7.2 组件库主题令牌 (`App.vue`)

顶层使用 Ant Design Vue 的 `<a-config-provider>` 注入全局主题令牌：
- `colorPrimary`: 取自 `theme.js` 的 `ACCENT`（全局品牌主色，组件库据此自动派生悬停、激活、禁用等全部状态色）
- `colorInfo`: 取自 `ACCENT`（信息提示色）
- `colorLink`: 取自 `ACCENT`（超链接文本色）
- `borderRadius`: `2`（微圆角矩形）
- `fontFamily`: `Inter, "PingFang SC", "Microsoft YaHei", sans-serif`

`main.js` 在应用挂载前将 `ACCENT` 写入文档根元素的 `--accent` 变量，原生 CSS 层与组件库由此共用同一主色来源。

### 7.3 全局设计令牌 (`styles.css`)

全局样式表根作用域 `:root` 声明设计令牌。主色阶元由 `--accent` 经 `color-mix()` 函数按固定配方派生，主色变更时全部阶元自动联动（括号内为 `--accent` 取值 `rgb(188, 74, 216)` / `#BC4AD8` 时的渲染结果）：
- `--accent`: 主色本体。运行时由 `theme.js` 注入覆盖，样式表中保留同值缺省声明作为回退
- `--accent-dark`: `color-mix(in srgb, var(--accent), #000 25%)`（→ `#8D38A2`，主色深阶：交互悬停、激活态与重要高亮文字）
- `--accent-mid`: `color-mix(in oklch, var(--accent), #fff 36%)`（→ `#D691E9`，主色中间阶：排行榜第二名徽章底色）
- `--accent-line`: `color-mix(in oklch, var(--accent), #fff 70%)`（→ `#EDCCF6`，主色浅阶：排行榜第三名徽章底色与轻量强调边框）
- `--accent-light`: `color-mix(in oklch, var(--accent), #fff 94%)`（→ `#FCF5FD`，主色柔和底衬：选项选中态浅淡底色）
- 输入框聚焦光晕由 `color-mix(in srgb, var(--accent) 20%, transparent)` 派生，随主色联动

中性色与语义状态色保持独立取值，不随主色派生：
- `--ink`: `#171717`（核心文字颜色，对比度 16.1:1，防眩光）
- `--muted`: `#595959`（辅助文字颜色，对比度 7.0:1，达到 WCAG AAA 级标准）
- `--line`: `#E9E9E9`（结构分割线与轻度边界）
- `--soft`: `#F7F7F8`（轻量容器与面板底衬）
- `--green`: `#168267`（成功与正确反馈色，浅底为 `#F1FBF8`）
- `--danger`: `#C94A42`（错误与警示反馈色，浅底为 `#FFF3F2`）

### 7.4 色调调整方法与约束

调整全站色调的步骤：
1. 修改 `src/theme.js` 中 `ACCENT` 的色值。
2. 组件库主题、CSS 主色阶元与输入框聚焦光晕随主色自动重算，其余文件无需改动。
3. `styles.css` 中 `--accent` 的缺省回退值与 `theme.js` 保持同值（仅影响脚本注入前的瞬时状态）。

选色约束：
- 主色同时承担实体按钮底色与白字承载，主色与白色文字的对比度需保持可读性（`#BC4AD8` 与白色文字对比度为 4.11:1；主色深阶 `#8D38A2` 与白色对比度为 6.52:1，满足 WCAG AA 级标准）。
- 各阶元按固定配方派生，更换主色后保持同一色相；派生结果需微调时，调整对应 `color-mix()` 的混色比例。
- `color-mix()` 函数需要 Chrome/Edge 111+、Safari 16.2+、Firefox 113+ 及以上版本浏览器支持。

### 7.5 舒适阅读与工程实现标准

- 全局排版保证正文行高在 1.6 至 1.75 之间，段间距保持充足留白。
- 卡片组件（`.ant-card`）配置微羽化低透明度阴影 `0 18px 45px rgba(0, 0, 0, 0.045)`，弱化生硬边框视觉刺激，保障长时间参与答题与场控调度过程中的眼部舒适度。
- 主操作按钮 `.ant-btn-primary` 的视觉由 `styles.css` 显式覆盖：底色与边界取 `--accent`，悬停与按压态取 `--accent-dark`，禁用态文字取纯白 `#FFFFFF`，禁用态保留 `--accent` 实体底填充。白字与 `#BC4AD8` 底的对比度为 4.11:1；组件库缺省的半透明黑禁用文字色叠加在同一底色上对比度仅 1.59:1。
