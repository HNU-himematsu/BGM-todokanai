# 接口规范与协议文档

## 1. 通用响应结构 (`ApiResponse<T>`)

除纯文本流式接口外，所有 REST 接口均统一返回以下 JSON 结构：

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

### 2.2 获取活动当前状态
- **方法与路径**：`GET /api/events/{eventId}/state?playerId={playerId}`
- **响应数据 (`data`)**：
  ```json
  {
    "eventId": "demo",
    "eventName": "百团大战猜歌活动",
    "status": "ANSWERING",
    "currentQuestionIndex": 0,
    "totalQuestions": 6,
    "deadlineEpochMs": 1791358945000,
    "question": {
      "id": "q1",
      "prompt": "【简单·作品题】这首充满治愈感的钢琴曲出自哪部作品？",
      "audioCode": "TRACK-01",
      "difficulty": "EASY",
      "type": "WORK",
      "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
      "analysis": null
    },
    "autoMode": true,
    "playerCount": 12,
    "answeredCount": 8,
    "playerDurationMs": 2340
  }
  ```

### 2.3 提交答案
- **方法与路径**：`POST /api/events/{eventId}/answers`
- **请求体**：
  ```json
  {
    "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
    "questionId": "q1",
    "optionIndex": 0
  }
  ```
- **响应数据**：返回提交后最新的 `EventState` 快照。

### 2.4 查看排行榜
- **实时本轮排行榜**：`GET /api/events/{eventId}/leaderboard`
- **历史轮次快照列表**：`GET /api/events/{eventId}/leaderboards/history`
- **纯文本格式大总结**：`GET /api/events/{eventId}/leaderboard/summary-txt`（返回 `text/plain;charset=UTF-8`）

---

## 3. 主持人控制端 REST API

所有主持人接口均需在 HTTP 标头中附带身份验证凭证：`X-Host-Token: {token}`。

| 接口路径 | HTTP 方法 | 请求参数 / 请求体 | 核心作用 |
| :--- | :--- | :--- | :--- |
| `/api/host/login` | POST | `{"password": "..."}` | 登录验证，返回有效 Token |
| `/api/host/logout` | POST | 无 | 注销当前 Token 会话 |
| `/api/events/{eventId}/host/start` | POST | 无 | 手动开启当前题目作答 |
| `/api/events/{eventId}/host/end` | POST | 无 | 提前截动作答，进入锁卷 |
| `/api/events/{eventId}/host/reveal` | POST | 无 | 提前揭晓正确答案与解析 |
| `/api/events/{eventId}/host/next` | POST | 无 | 提前切换至下一道题目 |
| `/api/events/{eventId}/host/reset` | POST | 无 | 重置房间，清空在线玩家 |
| `/api/events/{eventId}/host/pause` | POST | 无 | 暂停当前调度器自动轮转 |
| `/api/events/{eventId}/host/resume` | POST | 无 | 恢复当前调度器自动轮转 |
| `/api/events/{eventId}/host/toggle-auto`| POST | `enabled=true/false` (Query) | 开启或关闭全自动轮转模式 |

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
  - `QUESTION_STARTED`：新题目开始，倒计时启动。
  - `ANSWER_REVEALED`：作答窗口关闭，公布正确答案与解析。
  - `QUESTION_NEXT`：切题通知。
  - `ACTIVITY_FINISHED`：整轮题目结束，榜单已归档。
  - `ACTIVITY_RESET`：活动已重置，回归准备阶段。
  - `ACTIVITY_PAUSED`：自动调度已暂停。
  - `ACTIVITY_RESUMED`：自动调度已恢复。
  - `PLAYER_JOINED` / `PLAYER_ANSWERED`：玩家加入或提交变动。
