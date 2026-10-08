# 接口规范与协议文档

## 1. 通用响应结构 (`ApiResponse<T>`)

除纯文本流式接口与音频流接口外，所有 REST 接口均统一返回以下 JSON 结构：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

---

## 2. 参与者端 REST API

### 2.1 参与者加入活动
- **方法与路径**：`POST /api/events/{eventId}/join`
- **请求体**：
  ```json
  {
    "nickname": "风见幽香"
  }
  ```
- **响应数据 (`data`)**：
  ```json
  {
    "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
    "nickname": "风见幽香",
    "eventId": "demo"
  }
  ```
- **说明**：本轮到达 `FINISHED` 状态后拒绝新玩家加入；中途加入的玩家自加入时刻起即可参与作答并进入排行榜。

### 2.2 获取活动当前状态
- **方法与路径**：`GET /api/events/{eventId}/state?playerId={playerId}`
- **响应数据 (`data`)**：
  ```json
  {
    "eventId": "demo",
    "eventName": "旮旯原声之届不到的 BGM",
    "status": "ANSWERING",
    "currentQuestionIndex": 0,
    "totalQuestions": 6,
    "questionAudioStartEpochMs": 1791358940000,
    "questionAudioEndEpochMs": 1791358970000,
    "answerRevealed": false,
    "timelineRunning": true,
    "canAnswer": true,
    "question": {
      "id": "q-01",
      "prompt": "【中等·作品题】这首曲子出自哪部作品？",
      "difficulty": "MEDIUM",
      "type": "WORK",
      "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
      "workName": null,
      "analysis": null,
      "answerIndex": null
    },
    "hasAnswered": false,
    "selectedOption": null,
    "playerScore": 0,
    "playerDurationMs": 2340,
    "playerRank": 0,
    "playerCount": 12,
    "answeredCount": 8,
    "leaderboard": []
  }
  ```
- **关键字段说明**：
  - `status`：`WAITING` / `ANSWERING` / `ANSWER_LOCKED` / `ANSWER_REVEALED` / `FINISHED`。
  - `currentQuestionIndex`：当前轮次题目下标（从 0 开始计数）；题目未上屏时为 `-1`。
  - `totalQuestions`：当前轮次的题目总数。
  - `questionAudioStartEpochMs` / `questionAudioEndEpochMs`：当前题音频段起止的墙上时刻，用于计算本题音频段剩余时间；无当前题时为 `null`。暂停期间字段冻结，恢复后按剩余时长平移。
  - `timelineRunning`：时间轴处于运行状态为 `true`，暂停或未启动为 `false`。
  - `question.workName` / `question.analysis` / `question.answerIndex`：仅在 `ANSWER_REVEALED` 与 `FINISHED` 状态下发，其余状态为 `null`，杜绝客户端提前获取答案。
  - `canAnswer`：玩家已加入且当前处于 `ANSWERING` 状态时为 `true`。
  - `playerScore` / `playerRank`：仅在 `FINISHED` 状态下发个人得分与名次，作答过程中为 `0`。

### 2.3 提交答案
- **方法与路径**：`POST /api/events/{eventId}/answers`
- **请求体**：
  ```json
  {
    "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
    "questionId": "q-01",
    "optionIndex": 0
  }
  ```
- **响应数据**：返回提交后最新的 `EventState` 快照。
- **说明**：仅在 `ANSWERING` 状态下接受提交；状态进入 `ANSWER_LOCKED` 后拒绝答案请求。

### 2.4 查看排行榜
- **实时本轮排行榜**：`GET /api/events/{eventId}/leaderboard`
- **历史轮次快照列表**：`GET /api/events/{eventId}/leaderboards/history`
- **纯文本格式大总结**：`GET /api/events/{eventId}/leaderboard/summary-txt`（返回 `text/plain;charset=UTF-8`）

### 2.5 获取活动音频
- **方法与路径**：`GET /api/events/{eventId}/audio`
- **响应**：返回编排文件 `audioFile` 字段指向的音频文件字节流（`audio/*` 类型，支持 HTTP Range 请求），供主持人控制台播放与现场音箱放音。

---

## 3. 主持人控制端 REST API

所有主持人接口均需在 HTTP 标头中附带身份验证凭证：`X-Host-Token: {token}`。

| 接口路径 | HTTP 方法 | 请求参数 / 请求体 | 核心作用 |
| :--- | :--- | :--- | :--- |
| `/api/host/login` | POST | `{"password": "..."}` | 登录验证，返回有效 Token |
| `/api/host/logout` | POST | 无 | 注销当前 Token 会话 |
| `/api/events/{eventId}/host/rounds` | GET | 无 | 返回轮次清单与当前选中轮次 |
| `/api/events/{eventId}/host/round` | POST | `{"roundId": "round-2"}` | 切换选中轮次；重置房间并清空在线玩家 |
| `/api/events/{eventId}/host/state` | GET | 无 | 主持人专用状态快照（含房间状态、玩家名册与轮次信息） |
| `/api/events/{eventId}/host/start` | POST | 无 | 启动本轮时间轴并记录零点 `T0` |
| `/api/events/{eventId}/host/pause` | POST | 无 | 暂停时间轴（控制台同步暂停音频） |
| `/api/events/{eventId}/host/resume` | POST | 无 | 恢复时间轴（控制台同步恢复音频） |
| `/api/events/{eventId}/host/lock` | POST | 无 | 手动锁卷当前题目（提前执行 `LOCK`） |
| `/api/events/{eventId}/host/reveal` | POST | 无 | 手动揭晓答案与解析（提前执行 `REVEAL`） |
| `/api/events/{eventId}/host/next` | POST | 无 | 手动上屏下一题（提前执行 `NEXT`） |
| `/api/events/{eventId}/host/reset` | POST | 无 | 重置房间，清空在线玩家并回到 `WAITING` |

`GET /api/events/{eventId}/host/rounds` 的响应数据为轮次数组与 `currentRoundId` 字段，结构与下文快照中的对应字段一致。

主持人专用状态接口 `GET /api/events/{eventId}/host/state` 的响应数据 (`data`) 结构：

```json
{
  "state": { "...EventState 完整字段": "同 2.2，question 额外包含作品名 workName" },
  "players": [
    {
      "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
      "nickname": "风见幽香",
      "answered": true
    }
  ],
  "rounds": [
    {
      "id": "round-1",
      "name": "第一轮",
      "startMs": 0,
      "endMs": 180000,
      "totalQuestions": 6
    }
  ],
  "currentRoundId": "round-1",
  "timelineRunning": false
}
```

- `state`：与参与者接口同构的 `EventState` 快照，题目中的 `workName` 不做脱敏，供主持人看板展示作品提示。
- `players[].answered`：该玩家是否已提交当前题目答案。
- `rounds`：编排文件中定义的全部轮次及其音频区间与题目数量。
- `currentRoundId`：当前选中的轮次标识。
- `timelineRunning`：时间轴是否处于运行状态。

**手动指令的状态守卫**：手动 `lock` 仅在 `ANSWERING` 状态有效，手动 `reveal` 仅在 `ANSWER_LOCKED` 状态有效，手动 `next` 要求当前处于 `WAITING` 或 `ANSWER_REVEALED` 且待上屏槽位存在内容，`start` 仅在 `WAITING` 且时间轴未运行时有效，`pause` / `resume` 要求时间轴处于对应状态；不满足守卫的指令返回对应业务异常。

---

## 4. WebSocket (STOMP) 广播协议规范

- **连接端点 (Broker URL)**：`/ws`（支持 WebSocket 原生协议）
- **客户端订阅主题**：`/topic/events/{eventId}`
- **广播消息体结构**：
  ```json
  {
    "type": "QUESTION_STARTED",
    "eventId": "demo"
  }
  ```
- **核心事件类型定义**：
  - `ACTIVITY_STARTED`：本轮时间轴启动，零点 `T0` 生效。
  - `QUESTION_STARTED`：`NEXT` 上屏新题目，作答通道开放。
  - `ANSWER_LOCKED`：`LOCK` 锁卷，作答通道关闭，不公布答案。
  - `ANSWER_REVEALED`：`REVEAL` 公布正确答案与解析。
  - `ACTIVITY_FINISHED`：`FINISH` 结算本轮成绩，榜单已归档。
  - `ROUND_ENDED`：到达轮次结束标记，时间轴与音频暂停。
  - `ACTIVITY_RESET`：活动已重置或切换轮次，回归准备阶段。
  - `ACTIVITY_PAUSED`：时间轴已暂停。
  - `ACTIVITY_RESUMED`：时间轴已恢复。
  - `PLAYER_JOINED` / `PLAYER_ANSWERED`：玩家加入或提交变动。