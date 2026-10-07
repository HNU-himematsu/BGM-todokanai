# 数据流与实时通信规范

## 1. 通讯机制总览
系统采用“**REST 主动交互 + STOMP WebSocket 被动通知**”相结合的双通道设计：
- **REST 通道**：负责具备明确结果反馈的单向业务操作，如用户登记昵称、提交选项、主持人发起控制指令以及全量快照拉取。
- **STOMP 通道**：负责向所有在线客户端轻量广播状态变更事件。广播消息仅包含事件类型与活动 ID，不携带大量重载业务数据；客户端在收到广播后按需拉取针对自身身份脱敏后的状态快照。

## 2. 参与者全流程数据流

```text
[手机端 PlayView]                     [Spring Boot 服务端]
        │                                      │
        ├────── 1. POST /api/events/{id}/join ─>│ 校验昵称，生成 playerId
        │<───── 2. 返回 JoinResponse ──────────┤
        │                                      │
        ├────── 3. CONNECT /ws ────────────────>│ 建立 STOMP 会话
        ├────── 4. SUBSCRIBE /topic/events/{id}─>│ 注册主题监听
        │                                      │
        │<───── 5. 收到广播: QUESTION_STARTED ──┤ 调度器触发开题广播
        ├────── 6. GET /api/events/{id}/state ─>│ 请求当前题目（脱敏）
        │<───── 7. 返回 EventState ────────────┤
        │                                      │
        ├────── 8. POST /api/events/{id}/answers> 提交单选，记录毫秒耗时
        │<───── 9. 返回最新状态 ────────────────┤
        │                                      │
        │<───── 10. 收到广播: ANSWER_REVEALED ──┤ 倒计时结束广播揭晓
        ├────── 11. GET /api/events/{id}/state ─>│ 拉取正确答案与解析
        │<───── 12. 返回含解析的 EventState ────┤
        │                                      │
        │<───── 13. 收到广播: ACTIVITY_FINISHED─┤ 最后一题结束
        ├────── 14. GET /api/events/{id}/leaderboard
        │<───── 15. 返回最终排行榜清单 ─────────┤
```

## 3. 主持人控制数据流
1. 主持人通过 `/api/host/login` 传入密码完成登录，获取临时会话令牌 `token`。
2. 控制台向 `/api/events/{eventId}/host/{action}` 发送管理请求，在 HTTP 请求头中携带 `X-Host-Token: {token}`。
3. 服务端拦截器校验令牌有效性，执行活动状态流转，并通过 `SimpMessagingTemplate` 向 `/topic/events/{eventId}` 发送广播通知。

## 4. 数据脱敏与安全控制
- **题目数据脱敏**：在 `ANSWERING` 状态下，服务端下发的 `QuestionView` 仅包含 `id`、`prompt`、`audioCode`、`difficulty`、`type` 与 `options`，不包含正确答案下标 `answerIndex` 与文本解析 `analysis`，杜绝客户端通过开发者工具提前获取答案。
- **管理权限隔离**：所有 `/host/*` 管理接口均要求有效的 `X-Host-Token` 认证凭据，未授权请求均返回 `401 Unauthorized` 或对应业务异常。
