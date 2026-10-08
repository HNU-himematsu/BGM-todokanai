# 数据流与实时通信规范

## 1. 通讯机制总览

系统采用“**REST 主动交互 + STOMP WebSocket 被动通知**”相结合的双通道设计：
- **REST 通道**：负责具备明确结果反馈的单向业务操作，如用户登记昵称、提交选项、主持人发起控制指令、音频文件拉取以及全量快照拉取。
- **STOMP 通道**：负责向所有在线客户端轻量广播状态变更事件。广播消息仅包含事件类型与活动 ID，不携带业务数据；客户端在收到广播后按需拉取针对自身身份脱敏后的状态快照。

## 2. 参与者全流程数据流

```text
[手机端 PlayView]                     [Spring Boot 服务端]                     [主持人控制台 HostView]
        │                                      │                                      │
        ├────── 1. POST /api/events/{id}/join ─>│ 校验昵称，生成 playerId                │
        │<───── 2. 返回 JoinResponse ──────────┤                                      │
        │                                      │<──── 3. POST /host/round（选择轮次）───┤
        │                                      │<──── 4. POST /host/start（启动 T0）────┤ 同时播放音频
        ├────── 5. CONNECT /ws ────────────────>│                                      │
        ├────── 6. SUBSCRIBE /topic/events/{id}─>│ 注册主题监听                          │
        │                                      │                                      │
        │      （编排 LOAD：静默预加载题目内容）  │                                      │
        │<───── 7. 收到广播: QUESTION_STARTED ───┤ 编排 NEXT：上屏并开放作答              │
        ├────── 8. GET /api/events/{id}/state ──>│ 请求当前题目（脱敏）                   │
        │<───── 9. 返回 EventState ─────────────┤                                      │
        │                                      │                                      │
        ├────── 10. POST /api/events/{id}/answers > 提交单选，记录毫秒耗时                 │
        │<───── 11. 返回最新状态 ────────────────┤                                      │
        │                                      │                                      │
        │<───── 12. 收到广播: ANSWER_LOCKED ─────┤ 编排 LOCK：关闭作答通道，不公布答案     │
        ├────── 13. GET /api/events/{id}/state ─>│ 拉取已锁卷状态                         │
        │<───── 14. 返回不含答案的 EventState ───┤                                      │
        │                                      │                                      │
        │<───── 15. 收到广播: ANSWER_REVEALED ───┤ 编排 REVEAL：公布答案与解析            │
        ├────── 16. GET /api/events/{id}/state ─>│ 拉取含解析的 EventState                │
        │<───── 17. 返回揭晓状态 ────────────────┤                                      │
        │                                      │      （后续题循环 7~17）               │
        │<───── 18. 收到广播: ACTIVITY_FINISHED ─┤ 编排 FINISH：结算并归档榜单            │
        ├────── 19. GET /api/events/{id}/leaderboard
        │<───── 20. 返回最终排行榜清单 ──────────┤                                      │
        │                                      │                                      │
        │<───── 21. 收到广播: ROUND_ENDED ───────┤ 到达轮次结束标记：暂停时间轴            │ 暂停音频
```

## 3. 主持人控制数据流

1. 主持人通过 `/api/host/login` 传入密码完成登录，获取临时会话令牌 `token`。
2. 控制台通过 `GET /api/events/{eventId}/host/state` 获取主持人专用快照，其中包含当前房间状态、在线玩家名册、轮次清单与选中轮次。
3. 控制台通过 `POST /api/events/{eventId}/host/round` 选择本轮要运行的轮次；选择操作重置房间并清空在线玩家。
4. 主持人点击开始播放时，控制台将音频元素定位到轮次开始标记并开始播放，同时调用 `POST /api/events/{eventId}/host/start` 记录本轮零点 `T0`。
5. 时间轴按编排自动推进各动作；主持人可随时通过 `pause` / `resume` 暂停或恢复时间轴（控制台同步暂停或恢复音频），并可通过 `lock` / `reveal` / `next` 实施手动兜底。
6. 服务端执行状态流转后，通过 `SimpMessagingTemplate` 向 `/topic/events/{eventId}` 发送广播通知。

## 4. 音频播放与时间同步

- 后端通过 `GET /api/events/{eventId}/audio` 输出当前活动绑定的完整音频文件，供主持人控制台播放。
- 编排文件中的所有时间标记（轮次起止、动作时刻、题目音频段起止）均为该音频文件内的绝对毫秒位置。
- 服务端在动作触发时按“轮次开始标记 + 时间轴已运行时长”换算状态字段中的时间信息，参与者端据此计算本题音频段的剩余时间。
- 暂停期间时间轴与音频同步冻结；恢复后服务端按剩余时长平移后续动作与结束标记。

## 5. 数据脱敏与安全控制

- **题目数据脱敏**：在 `ANSWERING` 与 `ANSWER_LOCKED` 状态下，服务端下发的 `QuestionView` 仅包含 `id`、`prompt`、`difficulty`、`type` 与 `options`，不包含正确答案下标 `answerIndex`、文本解析 `analysis` 与作品名 `workName`，杜绝客户端通过开发者工具提前获取答案。
- **预加载隔离**：`LOAD` 动作仅将内容写入服务端待上屏槽位，不产生广播，也不通过参与者接口下发。
- **管理权限隔离**：所有 `/host/*` 管理接口均要求有效的 `X-Host-Token` 认证凭据，未授权请求均返回 `401 Unauthorized` 或对应业务异常。