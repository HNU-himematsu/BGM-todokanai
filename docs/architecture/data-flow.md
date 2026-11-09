# 数据流与实时通信规范

## 1. 通讯机制总览

系统采用两种数据通道：

- **REST 主动交互**：线上题集读取、线上预览/锁定、线下加入、答案提交、主持人控制、音频拉取与榜单查询。
- **STOMP WebSocket 被动通知**：线下活动状态变更广播；线上锁定后向主持人页面广播榜单更新事件。

广播消息包含事件类型、活动标识、事件发生时的轮次标识与活动状态版本号，不包含题目正文、答案或榜单明细。客户端收到广播后按需拉取对应快照或榜单。

主持人页面的音频控制与状态刷新分离：广播到达时先按广播携带的轮次同步音频，再合并触发快照刷新。客户端丢弃版本号低于本地已应用版本的广播与快照，避免旧轮次事件在新轮次开始后暂停音频。

## 2. 线上模式数据流

```text
[浏览器 OnlinePlayPanel]                  [Spring Boot 服务端]              [本地浏览器存储]
        │                                        │                              │
        ├── POST /api/events/{id}/join ─────────>│ 校验昵称，读取 participantId  │
        │<── JoinResponse(mode=ONLINE) ──────────┤                              │
        │                                        │                              │
        ├── GET /api/events/{id}/online/questions>│ 返回脱敏题集、音频地址、版本  │
        │<── OnlineQuestionSet ──────────────────┤                              │
        │                                        │                              │
        ├── 读取本地进度 ──────────────────────────────────────────────────────>│
        │<── 进度/题集版本 ─────────────────────────────────────────────────────┤
        │                                        │                              │
        ├── 播放音频，按时间自动出题或顺序推进   │                              │
        ├── 选择答案 ──────────────────────────────────────────────────────────>│
        │                                        │                              │
        ├── 全部题目结束 ───────────────────────────────────────────────────────>│
        │                                        │                              │
        ├── POST /online/preview ───────────────>│ 校验题目与选项，计算预览分     │
        │<── score / answeredCount / wrongCount ─┤ 不写榜，不返回答案             │
        │                                        │                              │
        ├── 重试 ───────────────────────────────────────────────────────────────>│ 清空进度
        │                                        │                              │
        └── POST /online/lock ──────────────────>│ 重新判分，写线上榜             │
                 │<── score / onlineRank / totalRank ─┤ 刷新文本榜单             │
                 └── 清空本地进度 ────────────────────────────────────────────────>│
```

### 2.1 线上加入

- 请求：`POST /api/events/{eventId}/join`，请求体包含 `nickname`、`mode=ONLINE`、`participantId`。
- 服务端不创建线下 `playerId`。
- 服务端查询 `participantId` 的线上锁定状态。
- 返回 `mode`、`participantId`、`nickname`、`onlineLocked`、`onlineScore` 与 `lockedAt`。

### 2.2 线上题集

- 请求：`GET /api/events/{eventId}/online/questions`。
- 服务端从 `timeline-online.json` 生成拉平题集，按 `questionId` 去重；该文件缺省时取 `timeline.json`。
- 响应包含 `questionSetVersion`、`totalQuestions`、`audioUrl` 与题目数组。
- 题目数组包含 `author`，不包含 `correctOption`、`workName`、`explanation`。

### 2.3 线上本地推进

- 浏览器保存 `bgm.online.progress.v1`。
- 音频播放位置是答题时钟。
- 当前题窗口开放且已选答案时，浏览器可将音频定位到下一题 `answerStartMs`，顺序切换到下一题并立即保存进度。
- 手动推进跳过的音频区间不计入 `activeDurationMs`；音频保持播放。
- 页面隐藏、刷新或关闭时暂停音频并保存当前进度。
- 页面恢复时展示「继续答题」，用户点击后从保存位置恢复。

### 2.4 预览与锁定

- 预览请求包含 `participantId`、`nickname`、`attemptId`、`questionSetVersion`、`answers`、`durationMs`。
- 未出现在 `answers` 中的题目按 0 分计算。
- 预览只返回分数、已答数、总题数、耗时与题集版本。
- 锁定请求使用同一答案集合；服务端重新判分，写入线上榜。
- 同一 `attemptId` 重复锁定返回已有结果；不同 `attemptId` 在已锁定后返回冲突。
- 锁定成功后广播 `ONLINE_SCORE_LOCKED`。

## 3. 线下模式数据流

```text
[手机端 OfflinePlayPanel]                [Spring Boot 服务端]                [主持人 HostView]
        │                                      │                                      │
        ├── POST /api/events/{id}/join ───────>│ 生成 playerId，记录 participantId     │
        │<── JoinResponse(mode=OFFLINE) ───────┤                                      │
        │                                      │<── POST /host/round（选择轮次）──────┤
        │                                      │<── POST /host/start（启动 T0）───────┤ 播放音频
        ├── CONNECT /ws ──────────────────────>│                                      │
        ├── SUBSCRIBE /topic/events/{id} ─────>│                                      │
        │                                      │                                      │
        │<── QUESTION_STARTED ─────────────────┤ NEXT：上屏并开放作答                  │
        ├── GET /state ───────────────────────>│ 返回脱敏题目                         │
        ├── POST /answers ────────────────────>│ 记录选项与耗时                       │
        │<── ANSWER_LOCKED ────────────────────┤ LOCK：关闭作答                       │
        │<── ANSWER_REVEALED ──────────────────┤ REVEAL：公布答案、作品名与出题人   │
        │<── ACTIVITY_FINISHED ────────────────┤ FINISH：结算并归档线下榜             │
        ├── GET /leaderboard ─────────────────>│ 返回本轮榜                           │
        │<── ROUND_ENDED ──────────────────────┤ 自动到达轮次结束或主持人结束末题       │
```

线下流程要点：

- 主持人选择轮次并启动，服务端按 `T0 + (atMs - startMs)` 调度动作。
- 参与者只提交选项，题目推进与答案揭晓由服务端状态决定。
- `FINISH` 自动动作与主持人结束末题都会停止时间轴并暂停音频；到达 `endMs` 而未触发 `FINISH` 时由轮次结束任务停止时间轴。
- 主持人可暂停、恢复、锁卷、揭晓、上一题、下一题、重置与切换轮次。
- 同一玩家的每次提交都会广播 `PLAYER_ANSWERED`。主持人页面在一次刷新窗口内合并连续广播，不为单条广播单独发起快照请求。

## 4. 音频与时间同步

- `GET /api/events/{eventId}/audio` 输出线下母带，`mode=online` 输出线上母带，两者均支持 HTTP Range。
- 编排中的时间字段均为音频绝对毫秒位置。
- 线下主持人将音频定位到 `round.startMs`，服务端以启动时刻为零点。
- 线上浏览器从第一题 `audioStartMs` 连续播放到最后一题 `audioEndMs`；题目在 `answerStartMs` 上屏、`answerEndMs` 锁定。
- 线上已选答案后点击「下一题」时，浏览器定位到下一题 `answerStartMs`，音频继续播放；该定位不改变音频素材契约。
- 线上音频连续播放，题目之间无空白段。
- 音频缺失或不可读时，接口返回错误，页面显示不可开始状态。

## 5. 榜单数据流

- 线下 `FINISH`：服务端生成轮次快照，写入 `leaderboards.json`，刷新文本总结。
- 线上锁定：服务端写入 `online_leaderboard.json`，刷新文本总结，返回线上榜名次与总榜名次。
- 总榜：服务端按请求聚合线下各轮最佳成绩与线上锁定成绩；不单独持久化。
- 主持人页面可在「本轮榜 / 线上榜 / 总榜」之间切换。

## 6. 数据脱敏与安全

- 线下 `ANSWERING` 与 `ANSWER_LOCKED` 状态下发的题目不含正确答案、作品名与出题人；解析作为提示自 `ANSWERING` 起下发。`ANSWER_REVEALED` 与 `FINISHED` 状态下发答案、作品名与出题人。
- 线上题集与预览/锁定响应始终不含正确答案、解析与作品名。
- 线上分数由服务端根据提交答案重新计算；客户端提交的分数不参与计算。
- 线上本地进度可被用户修改；服务端以锁定请求中的答案判分。计时公平性由浏览器本地执行，服务端不提供防篡改计时。
- 主持人接口要求 `X-Host-Token`。

完整流程、字段与验收标准见 [`../product/dual-mode-development-spec.md`](../product/dual-mode-development-spec.md)。
