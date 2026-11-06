# 前端架构设计

## 1. 文档定位

前端采用 Vue 3、Vite、Pinia、Vue Router 与 Ant Design Vue，承载线上独立答题、线下现场答题与主持人控制台三类界面。本文档定义路由、状态、浏览器存储、音频时钟、网络层、页面拆分与实现约束；页面布局与交互细则见 [`views-and-ui.md`](views-and-ui.md)。

前端遵循以下原则：

- 网页根地址进入线上模式，线下模式由二维码查询参数进入。
- 线上题目的推进时钟来自音频播放位置，题目顺序由服务端题集固定。
- 线上答题进度只保存在浏览器本地；服务端保存锁定成绩，不保存进行中的 attempt。
- 线下状态以服务端快照为准；实时广播只作为刷新触发信号。
- 线上界面不提供任意跳题、回看、暂停、答案揭晓或逐题对错；当前题已选答案后可按顺序进入下一题。
- 线上与线下共用 `participantId`，总榜按该标识合并成绩。

## 2. 工程目录

目标目录结构：

```text
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
    ├── main.js
    ├── App.vue
    ├── theme.js
    ├── styles.css
    ├── router/
    │   └── index.js
    ├── services/
    │   ├── api.js
    │   ├── socket.js
    │   ├── storage.js
    │   └── audioClock.js
    ├── stores/
    │   ├── activity.js
    │   ├── online.js
    │   └── host.js
    ├── components/
    │   ├── OfflinePlayPanel.vue
    │   ├── OnlinePlayPanel.vue
    │   ├── OnlineProgress.vue
    │   ├── OnlineResultPanel.vue
    │   └── LeaderboardPanel.vue
    └── views/
        ├── JoinView.vue
        ├── PlayView.vue
        └── HostView.vue
```

文件职责：

| 文件 | 职责 |
| :--- | :--- |
| `router/index.js` | 注册 `/`、`/play`、`/host`，执行模式与凭据守卫。 |
| `services/api.js` | 封装 REST 请求、错误归一化、主持人令牌与音频地址。 |
| `services/socket.js` | STOMP 连接、订阅、断线重连与事件分发。 |
| `services/storage.js` | 读写模式、身份、线上进度与主持人令牌，处理 JSON 损坏与存储不可用。 |
| `services/audioClock.js` | 管理音频元素、播放位置、暂停恢复、当前题推导与进度快照。 |
| `stores/activity.js` | 保存模式、活动信息、参与者身份、线下快照与线下榜单。 |
| `stores/online.js` | 保存线上题集、attempt、答案、预览结果、锁定结果与页面阶段。 |
| `stores/host.js` | 保存主持人令牌、轮次、主持人快照与榜单页签。 |
| `components/OfflinePlayPanel.vue` | 线下等待、逐题作答、锁卷、揭晓与结算。 |
| `components/OnlinePlayPanel.vue` | 线上开始、自动答题、进度控件、结果页与锁定。 |

## 3. 启动与模式解析

### 3.1 地址参数

参与者入口使用查询参数：

| 参数 | 允许值 | 缺省值 | 说明 |
| :--- | :--- | :--- | :--- |
| `mode` | `online` / `offline`，大小写不敏感 | `online` | 页面模式。 |
| `event` | 活动标识 | `jisong` | 活动标识。 |

地址示例：

```text
https://example.test/
https://example.test/online?event=jisong
https://example.test/?mode=offline
https://example.test/?mode=online&event=jisong
https://example.test/?mode=offline
```

`/offline`、`/online` 为路径入口，`?mode=offline`、`?mode=online` 为查询入口，两者语义相同。
线下二维码使用路径入口。

归一规则：

1. 优先取路由路径：`/offline` 为线下，`/online` 为线上。
2. 路径为 `/` 时读取 `mode` 查询参数。
3. `mode` 转小写；缺省为 `online`。
4. `mode` 为 `online` 或 `offline` 时采用该值。
5. 其他值归一为 `online`，页面显示“模式参数无效，已进入线上模式”。
6. `event` 去空白；空值与缺省值均为 `jisong`。
7. 归一后的模式写入 `sessionStorage` 的 `bgm.mode`。
8. `JoinView.vue` 根据模式渲染不同登记页；进入 `/play` 时模式随查询参数一并传递。

### 3.2 路由守卫

| 路由 | 准入条件 | 不满足时 |
| :--- | :--- | :--- |
| `/`、`/offline`、`/online` | 无条件 | 正常显示登记页。 |
| `/play` | 查询参数或 `sessionStorage` 存在有效模式；线上存在 `participantId` 与昵称；线下存在 `playerId` 与昵称 | 回到 `/{当前模式}?event={eventId}`。 |
| `/host` | 主持人密码核验成功并持有会话令牌 | 显示登录表单，不跳转参与者页面。 |

`/play` 的模式按查询参数、`sessionStorage` 顺序取值，取值后回写 `sessionStorage`。
两处都缺失时回到 `/`。主持人路由不读取参与者模式。

### 3.3 模式切换

- 线下登记页提供「进入线上模式」按钮，跳转到 `/online?event={eventId}`。
- 线上登记页提供现场二维码说明，不把线上答题直接切换为线下房间。
- 切换模式不删除 `participantId`、昵称与线上进度。
- 线下 `playerId` 只属于线下会话；切回线上时保留但不使用。
- 同一浏览器再次通过线下二维码加入时，生成新的 `playerId`，沿用现有 `participantId`。

## 4. 浏览器存储

### 4.1 存储键

| 键 | 存储区 | 内容 |
| :--- | :--- | :--- |
| `bgm.participant-id.v1` | `localStorage` | 跨线上与线下共用的参与者标识。 |
| `bgm.nickname.v1` | `localStorage` | 昵称回填值。 |
| `bgm.online.progress.v1` | `localStorage` | 线上 attempt 进度。 |
| `bgm.mode` | `sessionStorage` | 当前标签页模式：`ONLINE` 或 `OFFLINE`。 |
| `bgm.player-id` | `sessionStorage` | 线下玩家会话标识。 |
| `bgm.nickname.session` | `sessionStorage` | 当前标签页昵称。 |
| `bgm.host-token` | `sessionStorage` | 主持人令牌。 |

读取时兼容历史昵称键 `bgm-nickname` 与线下历史玩家键 `bgm-player-id`。写入统一使用上表键名。

### 4.2 `participantId` 生成

页面首次进入时读取 `bgm.participant-id.v1`。值缺失时：

1. 优先调用 `crypto.randomUUID()`。
2. 不可用时使用 `p-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`。
3. 写入 `localStorage`，并在加入接口请求体中提交。

`participantId` 是合并键，不用于授权。页面不把昵称、`playerId` 或 `attemptId` 作为身份合并键。

### 4.3 线上进度结构

```json
{
  "version": 1,
  "eventId": "jisong",
  "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
  "attemptId": "3e8d7c56-32b1-4f80-9d1f-5e6a4a9b2c77",
  "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c",
  "status": "IN_PROGRESS",
  "currentIndex": 3,
  "answers": {
    "q-01": 2,
    "q-02": 0
  },
  "audioPositionMs": 42000,
  "activeDurationMs": 39150,
  "questionRemainingMs": 3200,
  "startedAt": "2026-10-20T10:00:00.000Z",
  "updatedAt": "2026-10-20T10:02:12.000Z",
  "previewScore": null,
  "previewAnsweredCount": null
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `version` | Integer | 本地结构版本，当前为 `1`。 |
| `eventId` | String | 活动标识。 |
| `participantId` | String | 参与者合并键。 |
| `attemptId` | String | 本次尝试标识。 |
| `questionSetVersion` | String | 题集版本。 |
| `status` | String | `IN_PROGRESS` 或 `RESULT_READY`。 |
| `currentIndex` | Integer | 当前题下标；首题开始前为 `-1`。 |
| `answers` | Object | `questionId` 到 `optionIndex` 的映射。 |
| `audioPositionMs` | Long | 音频元素当前播放位置。 |
| `activeDurationMs` | Long | 本次尝试耗时；进行中时由 `startedAt` 计算到当前时刻，结束后固定为提交时刻值。 |
| `questionRemainingMs` | Long | 当前题距 `audioEndMs` 的剩余时长。 |
| `startedAt` | String | 本次 attempt 首次开始时间。 |
| `updatedAt` | String | 最近一次写入时间。 |
| `previewScore` | Integer / null | 预览总分。 |
| `previewAnsweredCount` | Integer / null | 预览已答数。 |

写入时机：

- 用户点击「开始答题」或「继续答题」。
- 当前题下标变化。
- 选项变化。
- 每 500 毫秒保存一次播放位置与有效时长。
- 页面隐藏、刷新、关闭前。
- 预览请求返回后。

清理时机：

- 锁定成功。
- 用户确认重试。
- `questionSetVersion` 不一致。
- JSON 解析失败、字段类型错误或 `version` 不支持。

### 4.4 存储恢复

读取 `bgm.online.progress.v1` 后依次校验：

1. JSON 可解析且为对象。
2. `version === 1`。
3. `eventId`、`participantId`、`attemptId` 非空。
4. `questionSetVersion` 等于服务端返回值。
5. `status` 为 `IN_PROGRESS` 或 `RESULT_READY`。
6. `currentIndex` 位于 `-1` 至 `totalQuestions - 1`。
7. `answers` 的键属于题集，值为 0 至 3。
8. `audioPositionMs` 位于 `0` 至 `audioEndMs`。

任一校验失败时删除本地进度并回到开始答题状态。题集版本不一致时额外提示“题目已更新，请重新开始”。

`localStorage` 不可用、配额耗尽或被隐私策略阻止时，页面允许开始答题，并显示“当前浏览器无法保存进度，刷新后需重新开始”。

线上进度没有应用层过期时间；其生命周期与当前 origin 的站点数据一致，直到用户清理站点数据、浏览器删除存储或服务端题集版本变化。

### 4.5 存储安全

- 本地进度不保存正确选项、解析、作品名或服务端判定结果。
- 本地进度可被用户修改；锁定请求由服务端重新判分。
- 清除站点数据会删除 `participantId`，线上锁定状态随新身份失去关联。
- 更换访问域名会形成独立存储空间；现场二维码域名应保持稳定。

## 5. Pinia 状态设计

### 5.1 `activity` store

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 当前活动标识。 |
| `eventName` | String | 活动名称。 |
| `mode` | String | `ONLINE` 或 `OFFLINE`。 |
| `participantId` | String | 参与者合并键。 |
| `nickname` | String | 当前昵称。 |
| `playerId` | String | 线下玩家会话标识。 |
| `state` | Object / null | 线下 `EventState` 快照。 |
| `leaderboard` | Array | 线下本轮榜。 |
| `leaderboardHistory` | Array | 线下历史快照。 |
| `loading` | Boolean | 请求进行中。 |
| `error` | String | 可展示错误。 |

主要动作：

| 动作 | 行为 |
| :--- | :--- |
| `initializeFromLocation()` | 解析查询参数、模式、活动标识与昵称。 |
| `setMode(mode)` | 写入 store 与 `sessionStorage`。 |
| `joinOnline()` | 调用加入接口，保存 `participantId`、昵称与锁定状态。 |
| `joinOffline()` | 调用加入接口，保存 `playerId`、`participantId` 与昵称。 |
| `refreshState()` | 读取线下快照；无 `playerId` 时读取房间公开快照。 |
| `refreshLeaderboards()` | 读取本轮榜与历史快照。 |
| `clearOfflinePlayer()` | 清空线下玩家凭据与线下页面状态，保留 `participantId` 与昵称。 |

### 5.2 `online` store

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `phase` | String | `IDLE`、`IN_PROGRESS`、`RESULT_READY`、`LOCKED` 或 `ERROR`。 |
| `questionSet` | Object / null | 线上题集响应。 |
| `attemptId` | String | 当前 attempt 标识。 |
| `answers` | Object | `questionId` 到选项下标。 |
| `currentIndex` | Integer | 当前题下标。 |
| `audioPositionMs` | Long | 当前音频位置。 |
| `activeDurationMs` | Long | 本次尝试耗时。 |
| `questionRemainingMs` | Long | 当前题剩余音频时长。 |
| `preview` | Object / null | 预览分响应。 |
| `lockResult` | Object / null | 锁定响应。 |
| `status` | Object / null | 服务端锁定状态。 |
| `visiblePause` | Boolean | 页面隐藏导致的暂停。 |
| `errorMessage` | String | 可展示错误。 |

计算属性：

| 名称 | 说明 |
| :--- | :--- |
| `questions` | `questionSet.questions`，空时返回空数组。 |
| `currentQuestion` | `questions[currentIndex]`。 |
| `displayQuestionNumber` | `currentIndex + 1`，仅在 `currentIndex >= 0` 时有意义。 |
| `answeredCount` | `answers` 中有效题目的数量。 |
| `unansweredCount` | 总题数减已答数。 |
| `progressPercent` | `(currentIndex + 1) / totalQuestions × 100`。 |
| `isWindowOpen` | `phase === 'IN_PROGRESS'` 且音频位置位于当前题音频区间内。 |
| `canAdvanceQuestion` | 当前题音频区间开放且存在下一题。 |
| `canFinishAttempt` | 最后一题已选答案且音频仍在播放。 |
| `canLock` | `phase === 'RESULT_READY'` 且锁定请求未进行中。 |

主要动作：

| 动作 | 行为 |
| :--- | :--- |
| `loadQuestionSet()` | 读取题集与服务端锁定状态，校验本地进度。 |
| `beginAttempt()` | 创建 `attemptId`，清空答案，写入初始进度。 |
| `resumeAttempt()` | 验证本地进度后恢复答案与音频位置。 |
| `selectOption(index)` | 仅当前题可答时写入答案并保存。 |
| `goToQuestion(index)` | 切换到任意题目，音频位置设为目标题 `audioStartMs` 并立即保存进度。 |
| `advanceToNextQuestion()` | 顺序切换至下一题，内部调用 `goToQuestion(index + 1)`。 |
| `persistProgress()` | 写入完整本地进度。 |
| `previewScore()` | 结果页调用预览接口。 |
| `retryAttempt()` | 清空进度，创建新 `attemptId`，回到首题。 |
| `lockScore()` | 调用锁定接口，保存名次，清理进度。 |
| `recoverLockedState()` | 服务端返回已锁定时恢复锁定结果并禁止重试。 |

### 5.3 `host` store

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `token` | String | 主持人令牌。 |
| `authenticated` | Boolean | 令牌是否可用。 |
| `rounds` | Array | 轮次清单。 |
| `currentRoundId` | String | 当前轮次。 |
| `snapshot` | Object / null | 主持人快照。 |
| `onlineLeaderboard` | Array | 线上积分榜。 |
| `totalLeaderboard` | Array | 总榜。 |
| `activeLeaderboardTab` | String | `round`、`online` 或 `total`。 |
| `audioStatus` | String | `IDLE`、`PLAYING`、`PAUSED` 或 `ENDED`。 |

主持人动作成功后以响应或重新获取的主持人快照更新页面。动作失败时展示服务端错误，不修改本地状态。

## 6. 网络层

### 6.1 REST 封装

`services/api.js` 使用 Axios 实例：

- `baseURL` 取 `VITE_API_BASE`，缺省为 `/api`。
- 超时 8000 毫秒。
- 参与者请求不携带主持人令牌。
- 主持人请求自动附加 `X-Host-Token`。
- 响应统一解包 `data.data`。
- 错误统一提取 `response.data.message`；网络错误使用页面定义的提示。

目标函数：

| 函数 | 接口 |
| :--- | :--- |
| `getEvent(eventId)` | 获取活动摘要。 |
| `joinEvent(eventId, payload)` | 线上或线下加入。 |
| `getOnlineQuestions(eventId)` | 获取线上题集。 |
| `getOnlineStatus(eventId, participantId)` | 查询线上锁定状态。 |
| `previewOnlineScore(eventId, payload)` | 计算预览分。 |
| `lockOnlineScore(eventId, payload)` | 锁定线上成绩。 |
| `getOnlineLeaderboard(eventId)` | 读取线上榜。 |
| `getTotalLeaderboard(eventId)` | 读取总榜。 |
| `getEventState(eventId, playerId)` | 读取线下参与者快照。 |
| `submitAnswer(eventId, payload)` | 提交线下答案。 |
| `getLeaderboard(eventId)` | 读取线下本轮榜。 |
| `getLeaderboardHistory(eventId)` | 读取线下历史快照。 |
| `getAudioUrl(eventId)` | 生成完整音频地址。 |
| `hostLogin(password)` | 主持人登录。 |
| `hostLogout(token)` | 主持人注销。 |
| `getHostRounds(eventId, token)` | 读取轮次清单。 |
| `selectHostRound(eventId, roundId, token)` | 选择轮次。 |
| `getHostState(eventId, token)` | 读取主持人快照。 |
| `hostAction(eventId, action, token)` | 执行主持人动作。 |

### 6.2 STOMP 封装

`services/socket.js` 使用 `@stomp/stompjs`：

- Broker URL 根据页面协议生成 `ws://` 或 `wss://`。
- 端点路径为 `/ws`。
- 订阅 `/topic/events/{eventId}`。
- `reconnectDelay` 为 3000 毫秒。
- 收到广播时把 `type` 与 `eventId` 推送给订阅者。
- 断线重连成功后触发一次状态重同步。
- 页面卸载时断开连接。

订阅者按页面角色处理事件：

- 线下参与者刷新 `GET /state`。
- 线下刷新返回参与者身份无效时，清除 `playerId` 与本地进度并返回线下登记页；网络错误保留现有身份。
- 主持人刷新 `GET /host/state`； `ONLINE_SCORE_LOCKED` 时同时刷新线上榜与总榜。
- 线上参与者不依赖广播推进题目；音频位置是推进时钟。

### 6.3 请求竞态

- 每次刷新保存递增请求序号，只有最新响应可以写入 store。
- 锁定请求进行中禁用重复点击，防止同一页面并发提交。
- 服务端锁定接口仍以 `attemptId` 保证幂等。
- 主持人动作连续点击时，按钮在请求期间禁用，响应返回后按新快照恢复。

## 7. 线上音频时钟

### 7.1 时钟来源

线上模式以 `HTMLAudioElement.currentTime` 作为唯一答题时钟：

- 音频元素由 `OnlinePlayPanel.vue` 持有，播放源取题集响应的 `audioUrl`。
- 音频元素设置 `preload="auto"`、`playsInline`，不显示原生控制条。
- 时间轴位置换算为 `audioPositionMs = Math.round(audio.currentTime * 1000)`。
- 播放起点为第一题的 `audioStartMs`，播放终点为最后一题的 `audioEndMs`。
- 不使用服务端墙上时刻推进线上题目，不使用固定 `setTimeout` 序列替代音频位置。

### 7.2 开始与恢复

开始条件：

- 题集加载完成。
- 用户已加入并持有昵称与 `participantId`。
- 服务端状态为 `NOT_LOCKED`。
- 本地进度不存在、已失效或已被用户确认重试。

恢复条件：

- 本地进度通过版本与字段校验。
- 用户点击「继续答题」。
- 音频元数据已加载，目标播放位置可设置。

开始或恢复动作：

1. 等待音频元数据加载并确认目标播放位置可设置；等待超过 12 秒按加载失败处理。
2. 新 attempt 的目标位置取第一题 `audioStartMs`；恢复 attempt 的目标位置取 `savedPositionMs`。
3. 设置 `audio.currentTime = targetPositionMs / 1000`。
4. 在用户点击事件中调用 `audio.play()`，满足浏览器自动播放策略。
5. 等待播放 Promise 成功后记录有效播放起点。
6. 进入 `IN_PROGRESS`。
7. 立即执行一次题目位置推导与进度保存。

页面加载后不自动播放音频；恢复进度也需要用户再次点击。
开始与恢复操作在请求进行期间保持单实例，重复点击不创建并行等待或重复重置本地 attempt。

### 7.3 当前题推导

设 `t = audioPositionMs`，题目集合按 `index` 升序排列：

```text
t < questions[0].audioStartMs
  → 尚未进入第一题音频区间

questions[i].audioStartMs <= t < questions[i].audioEndMs
  → 当前题为 questions[i]，选项可修改

questions[i].audioEndMs <= t < questions[i + 1].audioStartMs
  → 题间边界

t >= questions[last].audioEndMs
  → 最后一题结束，停止播放并进入结果流程
```

边界规则：

- `audioStartMs` 到达时立即上屏对应题目。
- `audioEndMs` 到达时自动切换到下一题；最后一题进入结果流程。
- 手动切题直接改变 `currentIndex`，回看不重置已保存答案。
- 相邻题目的 `audioEndMs` 与下一题 `audioStartMs` 相等时，题集顺序决定展示顺序。
- 手动点击题号、上一题或下一题时，将音频定位到目标题 `audioStartMs`，再应用目标题状态。

### 7.4 答题交互

- 当前题展示期间，选项可点击并写入 `answers[questionId]`。
- 进入结果预览前允许在任何题目间切换并修改已选答案。
- 进度圆点为按钮，显示已答题、当前题与未答题，点击切换题目。
- 「上一题」在非首题可用；「下一题」在非末题可用，未答题目允许跳过。
- 切题时将音频定位到目标题 `audioStartMs`；定位与缓冲期间保持当前题画面并禁用交互。
- 音频进入可播放状态后切换到目标题、保存进度，并按切换前播放状态继续或暂停。
- 单题进度条支持拖动；重播按钮定位到当前题 `audioStartMs` 并播放。
- 页面不提供全局进度条、倍速和音量控制。
- 最后一题任意时刻可点击「结束答题」进入结果流程。
- 提前耗尽音频数据时进入 `ERROR`，不伪造题目结束。

### 7.5 尝试耗时

`activeDurationMs` 表示一次线上尝试从开始到提交的总耗时：

- `beginAttempt()` 写入 `startedAt`。
- 进行中请求按 `Date.now() - startedAt` 计算。
- `completeAttempt()` 写入提交时刻，结果预览与锁定使用同一耗时值。
- 页面隐藏、刷新、音频暂停、缓冲与重播不暂停或清零计时。
- 刷新后恢复时保留原 `startedAt`，总耗时继续累计。

计时用于同分排序。服务端信任该数值，不提供防篡改保证。

### 7.6 页面隐藏与关闭

监听以下事件：

- `visibilitychange`
- `pagehide`
- `beforeunload`

页面进入隐藏状态时：

1. 暂停音频。
2. 记录暂停原因 `visiblePause = true`。
3. 读取当前题、答案、音频位置、有效耗时与剩余作答时间。
4. 同步写入 `bgm.online.progress.v1`。

页面重新可见时：

- 保持暂停状态，不自动继续播放。
- 显示「继续答题」遮罩。
- 用户点击后从保存位置恢复。

页面刷新或关闭时允许丢失最后一次 500 毫秒内的播放位置更新；答题结束、选项变化与页面隐藏事件均立即保存。

### 7.7 错误处理

| 场景 | 页面状态 | 处理 |
| :--- | :--- | :--- |
| 音频元数据加载失败 | `ERROR` | 显示「重新加载音频」与「重试本次答题」。 |
| 音频元数据等待超时 | `IDLE` | 结束等待并提示重新加载音频，开始按钮恢复可用。 |
| `audio.play()` 被策略拒绝 | `IDLE` | 保留开始按钮，等待用户点击。 |
| 播放中断且无法恢复 | `ERROR` | 保存进度，禁止锁定，提供重新加载。 |
| 网络断开但音频已缓存 | `IN_PROGRESS` | 继续本地播放；锁定与预览需要网络。 |
| 预览请求失败 | `RESULT_READY` | 显示分数加载失败与「重新获取分数」。 |
| 锁定请求失败 | `RESULT_READY` | 保留答案与预览结果，允许重新锁定。 |
| 锁定返回 `409` 已有记录 | `LOCKED` | 读取服务端锁定状态并显示已有名次。 |
| 题集版本不一致 | `IDLE` | 清理进度并提示题目更新。 |

## 8. 线上答题组件

### 8.1 `PlayView.vue`

`PlayView.vue` 是模式分发容器：

- 从 `activity` store 读取 `mode`。
- `ONLINE` 渲染 `OnlinePlayPanel.vue`。
- `OFFLINE` 渲染 `OfflinePlayPanel.vue`。
- 模式缺失或凭据不完整时由路由守卫回到登记页。

### 8.2 `OnlinePlayPanel.vue`

页面阶段与组件：

| 阶段 | 展示内容 | 主操作 |
| :--- | :--- | :--- |
| `IDLE` 无进度 | 规则摘要、题量、音频总长度、开始说明 | 「开始答题」 |
| `IDLE` 有进度 | 恢复题号、已答数、上次保存时间 | 「继续答题」 |
| `IDLE` 有预览结果 | 预览分、已答数、重试与锁定 | 「重试」「锁定分数」 |
| `IN_PROGRESS` | 只读进度、当前题、选项、剩余时间、「下一题」 | 「下一题」 |
| `RESULT_READY` | 总分、已答数、重试与锁定 | 「重试」「锁定分数」 |
| `LOCKED` | 总分、线上名次、总榜名次、锁定时间 | 无重试 |
| `ERROR` | 错误说明与保存状态 | 「重新加载音频」或「返回登记页」 |

锁定交互：

1. 点击「锁定分数」打开确认层，说明锁定后不可重试。
2. 确认后调用锁定接口并禁用按钮。
3. 成功时清理本地进度并进入 `LOCKED`。
4. 失败时保留结果页与答案，显示错误并恢复按钮。

答题推进交互：

1. 「上一题」「下一题」位于当前题卡片底部操作区，题号圆点位于进度卡片。
2. 「上一题」在非首题可用，「下一题」在非末题可用；未答题目允许跳过。
3. 当前题卡片显示单题音频进度条与「重播本题」按钮。
4. 切题时先将音频定位到目标题 `audioStartMs`，等待 `seeked` 与可播放状态；定位期间显示加载态。
5. 定位完成后保存进度，并按切题前的播放状态决定继续播放或保持暂停。
6. 切题过程不出现答案揭晓或暂停遮罩；定位期间显示音频定位状态。

### 8.3 `OnlineProgress.vue`

题目选择控件包含：

- 「第 x / N 题」。
- 线性进度条。
- 已答与未答数量。
- 题目序号圆点按钮：已答、当前、未答三种状态，点击切换题目。
- 「上一题」与「下一题」由当前题卡片承载。

### 8.4 `OnlineResultPanel.vue`

结果页只展示聚合答案信息：

- 总分。
- 已答题目数 / 总题目数。
- 有效耗时。
- 「重试」和「锁定分数」。
- 锁定后的线上榜名次、总榜名次与锁定时间。

结果页不得渲染正确选项、作品名、解析、逐题对错或答案分布。

### 8.5 离开页面

- 线上答题中离开 `/play` 前保存当前进度。
- 页面不弹出阻塞式离开确认；返回同一浏览器可继续。
- 已锁定时离开不会生成新的 attempt。
- 清理站点数据等同于放弃恢复能力。

## 9. 线下答题组件

### 9.1 `OfflinePlayPanel.vue`

线下面板按 `EventState.status` 渲染：

| 状态 | 界面 |
| :--- | :--- |
| `WAITING` | 等待主持人开始，显示活动名称与当前轮次提示。 |
| `ANSWERING` | 显示当前题、四个选项、本题剩余音频时间与提交状态。 |
| `ANSWER_LOCKED` | 选项只读，提示等待揭晓，不显示正确答案。 |
| `ANSWER_REVEALED` | 显示正确选项、解析、作品名与出题人。 |
| `FINISHED` | 显示本轮得分、耗时、名次与本轮榜。 |

线下提交：

1. 点击选项后立即调用提交接口。
2. 响应快照更新 `hasAnswered` 与 `selectedOption`。
3. 同一题提交成功后选项禁用。
4. 服务端拒绝时刷新快照并以服务端状态为准。

### 9.2 线下刷新

- 页面进入 `/play` 时读取一次 `GET /state`。
- 收到线下相关广播后刷新快照。
- 断线重连后刷新快照。
- 轮次切换或重置广播触发后清空线下会话并回到登记页。
- `ROUND_ENDED` 只停止时间轴，不直接清空页面；`FINISHED` 保留结算内容。

## 10. 主持人控制台

### 10.1 登录与令牌

- 登录页提交密码后调用 `POST /api/host/login`。
- 令牌写入 `sessionStorage` 的 `bgm.host-token`。
- 所有主持人请求携带 `X-Host-Token`。
- `401` 时清除令牌并回到登录页。

### 10.2 二维码

二维码内容：

```text
{publicBaseUrl}/?mode=offline
```

`publicBaseUrl` 取当前页面协议的 origin，或在配置中显式指定。二维码不含主持人令牌、玩家标识或本地进度。
主持人控制台同时显示该地址文本，扫码失败时可手动输入。

### 10.3 音频播放

- 主持人音频元素指向 `GET /api/events/{eventId}/audio`。
- 「开始播放」由点击事件触发，定位到当前轮次 `startMs` 并调用启动接口。
- 启动接口失败时立即暂停本地音频并展示错误。
- `ACTIVITY_PAUSED`、`ACTIVITY_RESUMED`、`ROUND_ENDED`、`ACTIVITY_RESET` 分别驱动暂停、恢复、停在结束位置与回到轮次起点。
- 轮次运行期间禁用轮次切换。
- 断线重连后读取主持人快照：运行中按快照 `audioPositionMs` 定位并继续播放；已暂停或已结束则暂停音频并定位到快照位置；定位失败或播放被策略拒绝时显示继续入口。
- 手动「下一题」将服务端时间基准对齐到新题 `audioStartMs`，音频播放器定位到同一位置；运行状态下继续播放，暂停状态下保持暂停。

### 10.4 榜单视图

榜单区提供三个页签：

| 页签 | 数据来源 | 刷新条件 |
| :--- | :--- | :--- |
| 本轮榜 | `GET /leaderboard` | 线下结算、手动刷新、`ACTIVITY_FINISHED`。 |
| 线上榜 | `GET /leaderboards/online` | 页面进入、`ONLINE_SCORE_LOCKED`、手动刷新。 |
| 总榜 | `GET /leaderboards/total` | 页面进入、`ONLINE_SCORE_LOCKED`、线下结算、手动刷新。 |

线上锁定广播只触发重新读取，不把广播内容直接拼入榜单。

### 10.5 文本总结

主持人可打开或下载 `GET /api/events/{eventId}/leaderboard/summary-txt`。文本总结按线下轮次榜、线上榜、总榜顺序输出。

## 11. 视觉主题与设计令牌

### 11.1 主色唯一定义点

`src/theme.js` 导出品牌主色：

```text
ACCENT = rgb(188, 74, 216) / #BC4AD8
```

组件库主题与全局 CSS 的主色阶元由该常量驱动。调整主色只修改 `theme.js`，其余色阶自动派生。

### 11.2 组件库令牌

`App.vue` 通过 `<a-config-provider>` 注入：

| 令牌 | 值 |
| :--- | :--- |
| `colorPrimary` | `ACCENT` |
| `colorInfo` | `ACCENT` |
| `colorLink` | `ACCENT` |
| `borderRadius` | `2` |
| `fontFamily` | `Inter, "PingFang SC", "Microsoft YaHei", sans-serif` |

`main.js` 在挂载前把 `ACCENT` 写入根元素 `--accent` 变量。

### 11.3 全局色阶

`styles.css` 的根令牌：

| 令牌 | 取值或派生规则 | 典型用途 |
| :--- | :--- | :--- |
| `--accent` | 主色本体 | 主按钮、选中边框、进度填充。 |
| `--accent-dark` | 主色与黑色 25% 混合 | 悬停、按压、重点文字。 |
| `--accent-mid` | 主色与白色 36% 混合 | 第二名徽章。 |
| `--accent-line` | 主色与白色 70% 混合 | 第三名徽章、轻边框。 |
| `--accent-light` | 主色与白色 94% 混合 | 选中项浅底。 |
| `--ink` | `#171717` | 核心标题与正文。 |
| `--muted` | `#595959` | 辅助说明。 |
| `--line` | `#E9E9E9` | 分割线与边界。 |
| `--soft` | `#F7F7F8` | 轻量容器底衬。 |
| `--green` | `#168267` | 成功、正确答案。 |
| `--danger` | `#C94A42` | 错误与警示。 |

主按钮使用主色实体底与纯白文字；悬停与按压使用 `--accent-dark`。禁用态保留实体底与纯白文字，避免低对比度。

### 11.4 阅读舒适度

- 正文字号不小于 14px，行高保持 1.6 至 1.75。
- 手机端可点击控件触控高度不小于 44px。
- 题目选项之间的间距保持独立可点区域。
- 页面主体使用白色与浅灰白，卡片使用轻度阴影，不使用大面积高饱和底色。
- 颜色不是唯一状态信号；已答、当前题与锁定状态同时提供文字或形状差异。

## 12. 响应式与兼容性

### 12.1 手机端

- 参与者页面以 320px 至 430px 视口为主要设计宽度。
- 题目、选项、进度和结果卡片纵向排列。
- 主持人页面在手机端单列堆叠，在宽屏端多列分区。
- 固定操作区不覆盖选项；键盘弹出时不遮挡当前题。
- 横屏下选项区域保持可滚动，不裁切题干与倒计时。

### 12.2 浏览器能力

依赖能力：

- ES Modules、Fetch/XHR、WebSocket。
- `localStorage`、`sessionStorage`、`crypto.randomUUID()`。
- HTML5 Audio 与 Range 请求。
- `color-mix()` 需要 Chrome/Edge 111+、Safari 16.2+、Firefox 113+。

不支持 `crypto.randomUUID()` 时使用随机标识回退。存储被禁用时页面提示无法保存进度。

### 12.3 移动端音频

- 音频元素在用户手势中调用 `play()`。
- `play()` 返回的 Promise 被捕获，拒绝时回到可重试状态。
- 页面切换为后台时主动暂停并保存。
- 来电、系统媒体按键或其他应用抢占音频后，依据音频事件恢复页面状态。
- 音频文件需支持 Range 与移动浏览器可解码的编码格式。
- 音频跳转后等待 `seeked` 与可播放状态，再恢复计时与题目推进；`waiting` 与 `playing` 事件驱动缓冲提示与计时暂停。

## 13. 性能与资源

- 题集接口一次返回全部题目，线上答题过程只使用本次题集响应。
- 音频使用浏览器原生流式加载与 Range 缓存；前端不把完整音频转成 Base64。
- 进度保存节流为 500 毫秒，选项变化与页面隐藏立即写入。
- 主持人快照与榜单请求在广播密集时合并刷新，避免同一事件循环内重复请求。
- 组件卸载时移除音频事件、`visibilitychange`、`pagehide` 与 STOMP 订阅。

## 14. 前端验收

### 14.1 模式与路由

- 访问 `/` 显示线上登记页。
- 访问 `/?mode=offline` 或 `/offline?event=jisong` 显示线下登记页。
- 无效 `mode` 进入线上模式并显示提示。
- 线下登记页可进入线上模式。
- `/play` 刷新后保持当前模式；模式缺失时回到 `/`。

### 14.2 线上答题

- 参与者可任意切题、回看与跳过未答题。
- 单题音频可拖动定位与重播。
- 最后一题任意时刻可结束并进入结果预览。
- 尝试耗时从开始答题持续累计到提交。
- 页面不存在全局进度条、倍速与音量控件。
- 未答题按 0 分提交。
- 结束页不显示答案、解析、作品名或逐题对错。

### 14.3 本地恢复

- 刷新页面后可继续同一 attempt。
- 页面隐藏时音频暂停，返回后需要点击继续。
- 题集版本变化后进度失效并提示重新开始。
- 锁定成功后进度删除。
- 重试生成新的 `attemptId`。

### 14.4 榜单与主持人

- 锁定成功显示线上名次与总榜名次。
- 已锁定参与者重新进入时显示锁定结果。
- 主持人收到 `ONLINE_SCORE_LOCKED` 后刷新线上榜与总榜。
- 主持人二维码指向线下模式。
- 线下轮次切换、揭晓、结算与榜单展示保持服务端状态驱动。
