# 接口规范与协议文档

## 1. 文档范围

本文档定义线上模式、线下模式、主持人控制台与实时广播使用的全部 HTTP 与 STOMP 协议。字段名称、枚举值、校验规则、错误响应和幂等语义构成前后端联调的接口基线。

- REST 基础路径：`/api`
- JSON 字符集：`UTF-8`
- JSON 请求头：`Content-Type: application/json`
- 二进制音频响应：`audio/*`，支持 `Range`
- 实时广播端点：`/ws`
- 广播主题：`/topic/events/{eventId}`
- 时间戳格式：ISO-8601 UTC，例如 `2026-10-20T10:02:12.128Z`
- 音频与时间轴位置单位：毫秒

题库、编排与音频文件由运营方提供。接口在素材就绪后按本文档校验并返回数据。

## 2. 通用协议

### 2.1 成功响应结构

除音频流与纯文本接口外，REST 接口统一返回 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `code` | Integer | 业务结果码，与 HTTP 状态码一致。 |
| `message` | String | 成功时为 `success`；失败时为可展示的错误说明。 |
| `data` | Object / Array / null | 业务数据。无返回内容时为 `null`。 |

### 2.2 失败响应结构

```json
{
  "code": 400,
  "message": "昵称不能为空",
  "data": null
}
```

| HTTP 状态 | `code` | 使用场景 |
| :--- | :--- | :--- |
| `200 OK` | `200` | 查询、预览、锁定、主持人操作成功。 |
| `400 Bad Request` | `400` | 字段缺失、格式错误、状态守卫不满足、题目或轮次参数不合法。 |
| `401 Unauthorized` | `401` | 主持人令牌缺失、失效或密码错误。 |
| `404 Not Found` | `404` | 活动、轮次或静态素材不存在。 |
| `409 Conflict` | `409` | 题集版本不一致、同一参与者以不同 attempt 重复锁定。 |
| `416 Range Not Satisfiable` | `416` | 音频 Range 超出文件长度。 |
| `500 Internal Server Error` | `500` | 榜单写入、文件读取或音频流输出失败。 |

前端以 HTTP 状态判断请求结果，以 `message` 展示错误。前端不得依据错误文本推断答题结果。

### 2.3 通用校验

| 字段 | 规则 |
| :--- | :--- |
| `eventId` | 非空，与 `timeline.json` 的 `eventId` 一致。 |
| `nickname` | 去除首尾空白后长度 1 至 20。 |
| `participantId` | 非空，长度 1 至 64；浏览器生成，不是认证凭据。 |
| `playerId` | 服务端生成的线下玩家会话标识，UUID 字符串。 |
| `attemptId` | 客户端生成的线上答题尝试标识，长度 1 至 64。 |
| `questionId` | 必须属于当前线上题集或当前线下轮次。 |
| `optionIndex` | 整数，取值 `0`、`1`、`2`、`3`。 |
| `durationMs` | 非负整数，单位为毫秒。 |
| 时间字段 | 非负整数，单位为毫秒，对应完整音频内的绝对位置。 |

请求体中的未知字段忽略。缺少必填字段、枚举值不合法或数值越界时返回 `400`。

### 2.4 参与者身份

| 标识 | 生成方 | 存储方 | 用途 |
| :--- | :--- | :--- | :--- |
| `participantId` | 浏览器 | 浏览器 `localStorage`；服务端写入榜单记录 | 线上与线下身份合并、总榜聚合。 |
| `nickname` | 参与者 | 浏览器与服务端记录 | 页面展示与榜单展示。 |
| `playerId` | 服务端 | 浏览器 `sessionStorage`；线下活动内存 | 线下单次会话与答题提交。 |
| `attemptId` | 浏览器 | 浏览器线上进度与服务端锁定记录 | 线上重试、预览和锁定幂等。 |

`participantId` 不承担认证职责。清除站点数据或更换访问域名会生成新的 `participantId`，并形成新的线上锁定记录。

## 3. 参与者公共接口

### 3.1 健康检查

`GET /api/health`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": "bgm-guess-backend is running"
}
```

### 3.2 获取活动摘要

`GET /api/events/{eventId}`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "jisong",
    "name": "旮旯原声之届不到的 BGM",
    "totalQuestions": 6,
    "status": "WAITING"
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | String | 活动标识。 |
| `name` | String | 活动名称。 |
| `totalQuestions` | Integer | 线下当前轮次的题目数量。 |
| `status` | String | 线下活动状态枚举。 |

### 3.3 加入活动

`POST /api/events/{eventId}/join`

线上请求示例：

```json
{
  "nickname": "风见幽香",
  "mode": "ONLINE",
  "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f"
}
```

线下请求示例：

```json
{
  "nickname": "风见幽香",
  "mode": "OFFLINE",
  "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f"
}
```

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `nickname` | String | 是 | 去空白后长度 1 至 20。 |
| `mode` | String | 否 | `ONLINE` 或 `OFFLINE`；缺省为 `ONLINE`。 |
| `participantId` | String | 否 | 浏览器已保存的身份；缺省时由服务端生成。 |

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "eventName": "旮旯原声之届不到的 BGM",
    "mode": "ONLINE",
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "playerId": null,
    "nickname": "风见幽香",
    "onlineLocked": false,
    "onlineScore": null,
    "lockedAt": null
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 活动标识。 |
| `eventName` | String | 活动名称。 |
| `mode` | String | 本次加入使用的模式。 |
| `participantId` | String | 浏览器应长期保存的参与者标识。 |
| `playerId` | String / null | 线下模式返回新生成的会话标识；线上模式为 `null`。 |
| `nickname` | String | 去除首尾空白后的昵称。 |
| `onlineLocked` | Boolean | 该 `participantId` 是否已有线上锁定成绩。 |
| `onlineScore` | Integer / null | 已锁定时为线上得分，否则为 `null`。 |
| `lockedAt` | String / null | 已锁定时为锁定时间，否则为 `null`。 |

线下响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "eventName": "旮旯原声之届不到的 BGM",
    "mode": "OFFLINE",
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
    "nickname": "风见幽香",
    "onlineLocked": false,
    "onlineScore": null,
    "lockedAt": null
  }
}
```

规则：

- `ONLINE` 加入不创建线下玩家会话。
- `OFFLINE` 加入生成新的 `playerId`，并记录 `participantId`、昵称和加入时刻。
- 当前线下轮次处于 `FINISHED` 时，`OFFLINE` 加入返回 `400`。
- 同一浏览器重复加入生成新的线下 `playerId`，但沿用同一 `participantId`。
- 未知 `mode` 返回 `400`；前端在调用接口前把无法识别的查询参数归一为 `ONLINE`。

### 3.4 获取音频

`GET /api/events/{eventId}/audio`

查询参数：

| 参数 | 必填 | 缺省 | 说明 |
| :--- | :--- | :--- | :--- |
| `mode` | 否 | `offline` | `offline` 输出线下母带；`online` 输出线上纯 BGM 母带。未知取值按 `offline` 处理。 |

请求头：

```http
Range: bytes=0-1048575
```

响应：

- 对应模式配置外部地址时返回 `302 Found`，`Location` 为 OSS 对象地址，不经过应用服务器转发音频。
- 无 `Range` 时返回 `200 OK` 和完整音频。
- 有效 `Range` 时返回 `206 Partial Content`、`Content-Range`、`Accept-Ranges: bytes` 与对应字节片段。
- 单个 Range 请求按请求区间返回完整片段；开放式区间返回至文件末尾。多区间请求返回第一个区间。
- `Content-Type` 根据音频文件扩展名生成，常见值为 `audio/mpeg`、`audio/wav`、`audio/ogg`。
- 文件缺失或不可读时返回 `404` 或 `500`，响应体为 `ApiResponse`。

### 3.5 获取榜单文本总结

`GET /api/events/{eventId}/leaderboard/summary-txt`

等价路径：`GET /api/events/{eventId}/leaderboards/summary-txt`

响应头：

```http
Content-Type: text/plain;charset=UTF-8
Content-Disposition: inline; filename="leaderboard_summary.txt"
```

响应体包含线下轮次榜、线上积分榜与总榜三个板块。文件结构与生成规则见 [`../data-and-config/leaderboard-and-storage.md`](../data-and-config/leaderboard-and-storage.md)。

## 4. 线上模式接口

### 4.1 获取线上题集

`GET /api/events/{eventId}/online/questions`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "eventName": "旮旯原声之届不到的 BGM",
    "audioUrl": "https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3",
    "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c",
    "audioStartMs": 0,
    "audioEndMs": 90000,
    "totalQuestions": 6,
    "questions": [
      {
        "index": 0,
        "id": "q-01",
        "prompt": "【中等·作品题】这首曲子出自哪部作品？",
        "type": "WORK",
        "difficulty": "MEDIUM",
        "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
        "author": "Munch",
        "audioStartMs": 0,
        "audioEndMs": 15000,
        "answerStartMs": 1000,
        "answerEndMs": 12000
      },
      {
        "index": 1,
        "id": "q-02",
        "prompt": "【简单·氛围题】这段旋律更适合哪一种场景？",
        "type": "ATMOSPHERE",
        "difficulty": "EASY",
        "options": ["放学后的海边散步", "最终决战前夜", "密室推理现场", "热闹的祭典舞台"],
        "author": "Munch",
        "audioStartMs": 15000,
        "audioEndMs": 30000,
        "answerStartMs": 15000,
        "answerEndMs": 27000
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 活动标识。 |
| `eventName` | String | 活动名称。 |
| `audioUrl` | String | 线上音频地址，取 `app.audio-url-online`，可直接赋给音频元素的 `src`；该值为空时回落 `app.audio-url`。 |
| `questionSetVersion` | String | 线上题集版本，SHA-256 小写十六进制字符串。 |
| `audioStartMs` | Long | 第一道线上题目的音频起点。 |
| `audioEndMs` | Long | 最后一道线上题目的音频终点。 |
| `totalQuestions` | Integer | 线上题集题目总数。 |
| `questions` | Array | 按答题顺序排列的脱敏题目数组。 |

线上题目字段：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `index` | Integer | 从 0 开始的答题顺序。 |
| `id` | String | 题目编号。 |
| `prompt` | String | 题干。 |
| `type` | String | `WORK` 或 `ATMOSPHERE`。 |
| `difficulty` | String | `EASY`、`MEDIUM` 或 `HARD`。 |
| `author` | String | 出题人署名。 |
| `options` | String[] | 四个选项。 |
| `audioStartMs` | Long | 本题音频片段起点。 |
| `audioEndMs` | Long | 本题音频片段终点。 |
| `answerStartMs` | Long | 本题开放作答的音频位置。 |
| `answerEndMs` | Long | 本题关闭作答的音频位置。 |

脱敏规则：

- 响应不得包含 `correctOption`、`workName`、`explanation`；`author` 随题下发。
- 响应不得包含逐题正确性、得分或排名。
- 题目数组不得包含重复 `id`，并覆盖题库全部 `id`。
- 相邻题目的 `audioEndMs` 与下一题的 `audioStartMs` 相等。

题集生成与版本规则：

1. 按轮次数组顺序和轮次内条目数组顺序遍历 `LOAD`。
2. 同一题目的 `NEXT` 记录 `answerStartMs`，`LOCK` 记录 `answerEndMs`。
3. 忽略 `REVEAL`、`FINISH` 与轮次边界。
4. 重复 `questionId` 保留首次出现。
5. `questionSetVersion` 对有序元组 `questionId,audioStartMs,audioEndMs,answerStartMs,answerEndMs` 计算 SHA-256；元组以换行连接且末尾不附加换行。

### 4.2 查询线上锁定状态

`GET /api/events/{eventId}/online/status?participantId={participantId}`

未锁定响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "status": "NOT_LOCKED",
    "score": null,
    "answeredCount": null,
    "correctCount": null,
    "wrongCount": null,
    "totalQuestions": null,
    "durationMs": null,
    "onlineRank": null,
    "totalRank": null,
    "lockedAt": null
  }
}
```

已锁定响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "status": "LOCKED",
    "score": 18,
    "answeredCount": 5,
    "correctCount": 3,
    "wrongCount": 2,
    "totalQuestions": 6,
    "durationMs": 68210,
    "onlineRank": 12,
    "totalRank": 9,
    "lockedAt": "2026-10-20T10:02:12.128Z"
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `participantId` | String | 被查询的参与者标识。 |
| `status` | String | `NOT_LOCKED` 或 `LOCKED`。 |
| `score` | Integer / null | 锁定成绩；未锁定时为 `null`。 |
| `answeredCount` | Integer / null | 已提交选项的题目数量。 |
| `correctCount` | Integer / null | 已提交且正确的题目数量；未锁定时为 `null`。 |
| `wrongCount` | Integer / null | 已提交且错误的题目数量；未锁定时为 `null`。 |
| `totalQuestions` | Integer / null | 锁定时线上题集总题数。 |
| `durationMs` | Long / null | 锁定记录中的有效作答耗时。 |
| `onlineRank` | Integer / null | 线上积分榜名次；未锁定时为 `null`。 |
| `totalRank` | Integer / null | 总榜名次；未锁定时为 `null`。 |
| `lockedAt` | String / null | 锁定时间。 |

查询不创建记录，不修改榜单。`participantId` 缺失时返回 `400`。

### 4.3 计算线上预览分

`POST /api/events/{eventId}/online/preview`

请求示例：

```json
{
  "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
  "nickname": "风见幽香",
  "attemptId": "3e8d7c56-32b1-4f80-9d1f-5e6a4a9b2c77",
  "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c",
  "durationMs": 68210,
  "answers": [
    {
      "questionId": "q-01",
      "optionIndex": 2
    },
    {
      "questionId": "q-03",
      "optionIndex": 0
    }
  ]
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "score": 7,
    "answeredCount": 2,
    "correctCount": 1,
    "wrongCount": 1,
    "totalQuestions": 6,
    "durationMs": 68210,
    "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c"
  }
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `participantId` | String | 是 | 参与者合并键。 |
| `nickname` | String | 是 | 当前展示昵称。 |
| `attemptId` | String | 是 | 本次线上尝试标识。 |
| `questionSetVersion` | String | 是 | 客户端持有的题集版本。 |
| `durationMs` | Long | 是 | 有效作答耗时，不含页面隐藏期间的暂停时间。 |
| `answers` | Array | 是 | 已选答案；允许空数组。 |

答案数组规则：

- 每项只包含 `questionId` 与 `optionIndex`。
- `questionId` 必须属于当前线上题集。
- 每个 `questionId` 最多出现一次；重复项返回 `400`。
- 未出现在数组中的题目按 0 分处理。
- 服务端按题集顺序归一化答案，客户端提交顺序不影响计分。
- `questionSetVersion` 与服务端不一致时返回 `409`。

预览规则：

- 答对按题目难度计分：简单题 2 分，中等题 3 分，困难题 5 分；错误或未作答得 0 分。
- 响应包含总分、已答数、答对数、答错数、总题数、耗时和题集版本。
- 响应不得包含正确答案、作品名、解析、逐题对错或榜单名次。
- 预览不写入 `online_leaderboard.json`，不改变锁定状态，不广播榜单事件。

### 4.4 锁定线上分数

`POST /api/events/{eventId}/online/lock`

请求体与预览接口一致：

```json
{
  "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
  "nickname": "风见幽香",
  "attemptId": "3e8d7c56-32b1-4f80-9d1f-5e6a4a9b2c77",
  "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c",
  "durationMs": 68210,
  "answers": [
    {
      "questionId": "q-01",
      "optionIndex": 2
    },
    {
      "questionId": "q-02",
      "optionIndex": 0
    }
  ]
}
```

首次锁定响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "attemptId": "3e8d7c56-32b1-4f80-9d1f-5e6a4a9b2c77",
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "nickname": "风见幽香",
    "score": 7,
    "answeredCount": 2,
    "correctCount": 1,
    "wrongCount": 1,
    "totalQuestions": 6,
    "durationMs": 68210,
    "onlineRank": 18,
    "totalRank": 14,
    "lockedAt": "2026-10-20T10:02:12.128Z"
  }
}
```

锁定规则：

- 仅在前端处于全部题目结束后的结果页时提供入口；未完成全部题目的客户端不显示锁定按钮。
- 锁定成绩以本题集全部题目为范围；未作答题目按 0 分，不阻塞锁定。
- 服务端根据题库答案重新判分，忽略任何客户端分数。
- 以 `eventId + participantId` 作为线上记录唯一键，每个参与者保留一条记录。
- 同一 `participantId` 与同一 `attemptId` 重复请求返回首次锁定结果，状态为 `200`。
- 同一 `participantId` 已锁定后，以不同 `attemptId` 请求返回 `409`。
- 锁定成功时写入 `online_leaderboard.json`，刷新文本总结，并广播 `ONLINE_SCORE_LOCKED`。
- `onlineRank` 在该活动线上榜内计算；`totalRank` 在线上成绩与线下历史成绩合并后的总榜内计算。

### 4.5 获取线上积分榜

`GET /api/events/{eventId}/leaderboards/online`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "generatedAt": "2026-10-20T10:03:00.000Z",
    "entries": [
      {
        "rank": 1,
        "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
        "nickname": "风见幽香",
        "score": 27,
        "answeredCount": 6,
        "totalQuestions": 6,
        "durationMs": 74120,
        "lockedAt": "2026-10-20T10:02:12.128Z"
      }
    ]
  }
}
```

排序规则：

1. `score` 降序。
2. `durationMs` 升序。
3. `lockedAt` 升序。
4. `nickname` 升序。

线上榜只包含线上锁定记录，不包含线下轮次成绩。响应不含答题选项、正确答案与解析。

### 4.6 获取总榜

`GET /api/events/{eventId}/leaderboards/total`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "generatedAt": "2026-10-20T10:03:00.000Z",
    "entries": [
      {
        "rank": 1,
        "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
        "nickname": "风见幽香",
        "totalScore": 100,
        "offlineScore": 70,
        "onlineScore": 30,
        "totalDurationMs": 91210,
        "offlineRoundCount": 2,
        "onlineLocked": true,
        "lastActivityAt": "2026-10-20T10:02:12.128Z",
        "offlineContributions": [
          {
            "roundId": "round-1",
            "roundName": "第一轮",
            "score": 40,
            "durationMs": 12000,
            "finishedAt": "2026-10-20T09:20:00.000Z"
          },
          {
            "roundId": "round-2",
            "roundName": "第二轮",
            "score": 30,
            "durationMs": 11000,
            "finishedAt": "2026-10-20T09:40:00.000Z"
          }
        ],
        "onlineContribution": {
          "score": 30,
          "durationMs": 68210,
          "lockedAt": "2026-10-20T10:02:12.128Z"
        }
      }
    ]
  }
}
```

总榜聚合规则：

- 线下部分按 `participantId` 分组；同一轮次存在多次记录时取最高分，同分取更短耗时。
- 线下部分把每个轮次的最佳成绩跨轮次求和。
- 线上部分取该 `participantId` 的线上锁定成绩，最多一条。
- `totalScore = offlineScore + onlineScore`。
- `totalDurationMs` 为各轮最佳线下耗时与线上锁定耗时之和。
- `lastActivityAt` 取线下最佳成绩结束时间与线上锁定时间中的较晚值。
- 展示昵称取 `lastActivityAt` 对应记录的昵称。
- 只有至少存在一条线下记录或线上记录的参与者进入总榜。

总榜排序规则：

1. `totalScore` 降序。
2. `totalDurationMs` 升序。
3. `lastActivityAt` 升序。
4. `nickname` 升序。

总榜按请求实时聚合，不单独持久化名次。

## 5. 线下模式接口

### 5.1 获取线下活动状态

`GET /api/events/{eventId}/state?playerId={playerId}`

`playerId` 可省略，用于获取不含个人答题信息的房间快照。有效 `playerId` 存在时，响应附带该玩家的选择与结算信息。

`playerId` 非空但不在当前活动名册时返回 `400`，页面回到线下登记页重新加入。

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "jisong",
    "eventName": "旮旯原声之届不到的 BGM",
    "status": "ANSWERING",
    "currentQuestionIndex": 0,
    "totalQuestions": 3,
    "questionAudioStartEpochMs": 1791358940000,
    "questionAudioEndEpochMs": 1791358952000,
    "answerRevealed": false,
    "timelineRunning": true,
    "revision": 42,
    "canAnswer": true,
    "question": {
      "id": "q-01",
      "prompt": "【中等·作品题】这首曲子出自哪部作品？",
      "difficulty": "MEDIUM",
      "type": "WORK",
      "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
      "workName": null,
      "analysis": "党争、三角恋、下雪天",
      "author": null,
      "answerIndex": null
    },
    "hasAnswered": false,
    "selectedOption": null,
    "playerScore": 0,
    "playerWrongCount": 0,
    "playerDurationMs": 0,
    "playerRank": 0,
    "playerCount": 12,
    "answeredCount": 8,
    "leaderboard": []
  }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 活动标识。 |
| `eventName` | String | 活动名称。 |
| `status` | String | `WAITING`、`ANSWERING`、`ANSWER_LOCKED`、`ANSWER_REVEALED` 或 `FINISHED`。 |
| `currentQuestionIndex` | Integer | 当前轮次题目下标，从 0 开始；未上屏时为 `-1`。 |
| `totalQuestions` | Integer | 当前轮次题目总数。 |
| `questionAudioStartEpochMs` | Long / null | 当前题音频段起点的墙上时刻。 |
| `questionAudioEndEpochMs` | Long / null | 当前题音频段终点的墙上时刻。 |
| `answerRevealed` | Boolean | 当前题答案是否处于公开状态。 |
| `timelineRunning` | Boolean | 时间轴是否运行。 |
| `revision` | Long | 活动状态版本号。每次广播加一；客户端只接受不小于本地已应用版本号的快照与通知。 |
| `canAnswer` | Boolean | 玩家已加入且当前状态为 `ANSWERING`。 |
| `question` | Object / null | 当前题脱敏视图。 |
| `hasAnswered` | Boolean | 当前玩家是否已提交本题。 |
| `selectedOption` | Integer / null | 当前玩家提交的选项下标。 |
| `playerScore` | Integer | 本轮得分；非 `FINISHED` 状态为 0。 |
| `playerWrongCount` | Integer | 本轮答错题数，统计已提交且错误的题目；非 `FINISHED` 状态为 0。 |
| `playerDurationMs` | Long | 本轮累计有效耗时。 |
| `playerRank` | Integer | 本轮名次；非 `FINISHED` 状态为 0。 |
| `playerCount` | Integer | 当前在线玩家数。 |
| `answeredCount` | Integer | 当前题已提交人数。 |
| `leaderboard` | Array | `FINISHED` 状态下的本轮榜单；其他状态为空数组。 |

题目视图字段：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | String | 题目编号。 |
| `prompt` | String | 题干。 |
| `difficulty` | String | 难度枚举。 |
| `type` | String | 题型枚举。 |
| `options` | String[] | 四个选项。 |
| `workName` | String / null | 作品名；仅 `ANSWER_REVEALED`、`FINISHED` 与主持人快照可见。 |
| `analysis` | String / null | 提示文本；线下带 `playerId` 的参与者快照自 `ANSWERING` 起可见，匿名快照与主持人快照仅在 `ANSWER_REVEALED` 及之后可见。 |
| `author` | String / null | 出题人署名；仅 `ANSWER_REVEALED`、`FINISHED` 与主持人快照可见。 |
| `answerIndex` | Integer / null | 正确选项下标；仅 `ANSWER_REVEALED` 与 `FINISHED` 可见。 |

状态约束：

- `WAITING`：参与者在册，尚未上屏题目。
- `ANSWERING`：当前题开放作答。
- `ANSWER_LOCKED`：当前题关闭作答，正确答案保持隐藏。
- `ANSWER_REVEALED`：正确答案、作品名与出题人公开；`analysis` 作为提示自 `ANSWERING` 起已可见。
- `FINISHED`：本轮完成结算并生成线下快照。
- 暂停期间墙上时刻字段冻结；恢复后按暂停时长平移。

### 5.2 提交线下答案

`POST /api/events/{eventId}/answers`

请求示例：

```json
{
  "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
  "questionId": "q-01",
  "optionIndex": 2
}
```

响应数据为提交后的完整 `EventState` 快照。

校验规则：

- `playerId` 必须属于当前活动在线名册。
- 活动状态必须为 `ANSWERING`。
- `questionId` 必须等于当前上屏题目。
- `optionIndex` 必须为 0 至 3。
- 同一玩家对同一题目只接受一次提交；重复提交返回 `400`。
- 提交成功后记录选项、正确性、得分和本题耗时，并广播 `PLAYER_ANSWERED`。
- 单题耗时等于提交墙上时刻减去本题 `NEXT` 墙上时刻，最小值为 0。

### 5.3 获取线下本轮榜

`GET /api/events/{eventId}/leaderboard`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "rank": 1,
      "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
      "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
      "nickname": "风见幽香",
      "score": 30,
      "durationMs": 43120,
      "answeredCount": 3,
      "totalQuestions": 3
    }
  ]
}
```

- 活动未进入 `FINISHED` 时返回空数组。
- 活动进入 `FINISHED` 后返回当前在线玩家名册的本轮成绩。
- 本轮榜用于现场评奖，只包含该轮线下成绩。
- 排序：`score` 降序、`durationMs` 升序、玩家加入时间升序、`nickname` 升序。

### 5.4 获取线下历史快照

`GET /api/events/{eventId}/leaderboards/history`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "eventId": "jisong",
      "eventName": "旮旯原声之届不到的 BGM",
      "roundId": "round-1",
      "roundName": "第一轮",
      "finishedAt": "2026-10-20T09:20:00.000Z",
      "entries": [
        {
          "rank": 1,
          "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
          "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
          "nickname": "风见幽香",
          "score": 30,
          "durationMs": 43120,
          "answeredCount": 3,
          "totalQuestions": 3,
          "joinedAt": "2026-10-20T09:15:02.000Z"
        }
      ]
    }
  ]
}
```

历史快照按 `finishedAt` 升序返回。历史文件字段与兼容规则见 [`../data-and-config/leaderboard-and-storage.md`](../data-and-config/leaderboard-and-storage.md)。

## 6. 主持人接口

所有主持人接口在请求头中携带主持人令牌：

```http
X-Host-Token: 6dc1e7aa-3f7d-4c61-92e4-8ef317cd09a4
```

令牌缺失、无效或已注销时返回 `401`。

### 6.1 登录

`POST /api/host/login`

请求示例：

```json
{
  "password": "现场管理密码"
}
```

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "6dc1e7aa-3f7d-4c61-92e4-8ef317cd09a4"
  }
}
```

密码来自服务端配置 `app.host-password`。密码错误返回 `401`。

### 6.2 注销

`POST /api/host/logout`

请求头携带当前令牌。响应 `data` 为 `null`。注销使该令牌失效。

### 6.3 获取轮次清单

`GET /api/events/{eventId}/host/rounds`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "currentRoundId": "round-1",
    "rounds": [
      {
        "id": "round-1",
        "name": "第一轮",
        "startMs": 0,
        "endMs": 45000,
        "totalQuestions": 3
      },
      {
        "id": "round-2",
        "name": "第二轮",
        "startMs": 45000,
        "endMs": 90000,
        "totalQuestions": 3
      }
    ]
  }
}
```

### 6.4 选择轮次

`POST /api/events/{eventId}/host/round`

请求示例：

```json
{
  "roundId": "round-2"
}
```

响应数据为切换后的 `EventState`。

选择轮次会清空在线玩家与答题记录，把状态重置为 `WAITING`，并广播 `ACTIVITY_RESET`。时间轴运行期间选择轮次返回 `400`。

### 6.5 获取主持人快照

`GET /api/events/{eventId}/host/state`

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "state": {
      "eventId": "jisong",
      "eventName": "旮旯原声之届不到的 BGM",
      "status": "ANSWERING",
      "currentQuestionIndex": 0,
      "totalQuestions": 3,
      "questionAudioStartEpochMs": 1791358940000,
      "questionAudioEndEpochMs": 1791358952000,
      "answerRevealed": false,
      "timelineRunning": true,
      "audioPositionMs": 45000,
      "revision": 42,
      "canAnswer": false,
      "question": {
        "id": "q-01",
        "prompt": "【中等·作品题】这首曲子出自哪部作品？",
        "difficulty": "MEDIUM",
        "type": "WORK",
        "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
        "workName": "《示例作品》",
        "analysis": null,
        "author": "Munch",
        "answerIndex": null
      },
      "hasAnswered": false,
      "selectedOption": null,
      "playerScore": 0,
      "playerWrongCount": 0,
      "playerDurationMs": 0,
      "playerRank": 0,
      "playerCount": 12,
      "answeredCount": 8,
      "leaderboard": []
    },
    "players": [
      {
        "playerId": "4b6c8e31-8f92-4d22-92ab-52cb2da9c011",
        "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
        "nickname": "风见幽香",
        "answered": true,
        "score": 0,
        "durationMs": 0,
        "joinedAt": "2026-10-20T09:15:02.000Z"
      }
    ],
    "rounds": [
      {
        "id": "round-1",
        "name": "第一轮",
        "startMs": 0,
        "endMs": 45000,
        "totalQuestions": 3
      }
    ],
    "currentRoundId": "round-1",
    "timelineRunning": true,
    "canAdvanceQuestion": false,
    "canRetreatQuestion": false,
    "canFinishRound": false
  }
}
```

`canAdvanceQuestion` 仅当时间轴已启动、主持人状态为 `WAITING` 或 `ANSWER_REVEALED` 且本轮存在下一题时为 `true`。控制台使用该字段控制「下一题」按钮。下一题尚未预加载时，主持人动作直接读取本轮编排中的下一道题，并取消该题尚未触发的 `LOAD` 与 `NEXT` 条目；后续题目的自动触发保持不变。

`canRetreatQuestion` 仅当时间轴已启动、主持人状态为 `WAITING` 或 `ANSWER_REVEALED` 且当前题之前本轮存在上一题时为 `true`。控制台使用该字段控制「上一题」按钮。回退动作把当前题定位到上一题，并把时间基准对齐到该题 `audioStartMs`；从该题起重新登记锁卷、揭晓与后续题目的编排任务，已提交的作答记录与玩家分数保持不变。回退后主持人状态为 `ANSWERING`。

`canFinishRound` 仅当时间轴已启动、当前题为本轮最后一题且状态为 `ANSWER_REVEALED` 时为 `true`。控制台使用该字段控制「结束本轮」按钮。

`audioPositionMs` 为当前时间轴对应的音频毫秒位置；时间轴尚未启动时为 `null`。主持人播放器使用该字段校准播放位置。

主持人快照允许展示 `workName` 与 `author`，用于现场提示。正确答案与解析仍只在揭晓状态下公开。`players[].answered` 表示该玩家是否已提交当前题。

### 6.6 线下动作接口

全部动作接口返回动作完成后的 `EventState`，成功状态为 `200`。

| 方法与路径 | 请求体 | 前置状态 | 作用与广播 |
| :--- | :--- | :--- | :--- |
| `POST /api/events/{eventId}/host/start` | 无 | `WAITING`，时间轴未运行，已选轮次 | 记录零点 `T0`，启动时间轴，广播 `ACTIVITY_STARTED`。 |
| `POST /api/events/{eventId}/host/pause` | 无 | 时间轴运行中 | 注销挂起任务并冻结墙上时刻，广播 `ACTIVITY_PAUSED`。 |
| `POST /api/events/{eventId}/host/resume` | 无 | 时间轴已暂停 | 平移时间基准并重排剩余任务，广播 `ACTIVITY_RESUMED`。 |
| `POST /api/events/{eventId}/host/lock` | 无 | `ANSWERING` | 关闭当前题作答并补记缺席耗时，广播 `ANSWER_LOCKED`。 |
| `POST /api/events/{eventId}/host/reveal` | 无 | `ANSWER_LOCKED` | 公开当前题答案、解析与出题人，广播 `ANSWER_REVEALED`。 |
| `POST /api/events/{eventId}/host/next` | 无 | `WAITING` 或 `ANSWER_REVEALED`，本轮存在下一题 | 上屏下一题并开放作答，把时间基准对齐到新题 `audioStartMs` 并重排剩余任务，广播 `QUESTION_STARTED`。 |
| `POST /api/events/{eventId}/host/prev` | 无 | `WAITING` 或 `ANSWER_REVEALED`，当前题之前本轮存在上一题 | 回退到上一题并开放作答，把时间基准对齐到该题 `audioStartMs`，从该题起重排锁卷、揭晓与后续任务，广播 `QUESTION_STARTED`。 |
| `POST /api/events/{eventId}/host/finish` | 无 | `ANSWER_REVEALED`，当前题为本轮最后一题 | 立即结算本轮并归档线下榜，停止时间轴并广播 `ACTIVITY_FINISHED` 与 `ROUND_ENDED`。 |
| `POST /api/events/{eventId}/host/reset` | 无 | 任意状态 | 清空在线玩家、答题记录与时间轴，回到 `WAITING`，广播 `ACTIVITY_RESET`。 |

轮次结束后，主持人可调用现有 `POST /api/events/{eventId}/host/round` 选择任一已配置轮次，再调用 `POST /api/events/{eventId}/host/start` 开启该轮。选择轮次会清空当前在线玩家与作答记录。

动作不满足前置状态时返回 `400`，服务端状态保持不变。前端动作按钮以主持人快照中的状态为准，不自行推测成功。

## 7. WebSocket 与 STOMP 协议

### 7.1 连接

- STOMP Broker URL：`/ws`
- 客户端协议：WebSocket
- 订阅主题：`/topic/events/{eventId}`
- 重连间隔：3 秒
- HTTPS 页面使用 `wss://`，HTTP 页面使用 `ws://`

### 7.2 消息结构

广播消息采用轻量通知，不携带题目正文、答案或榜单明细：

```json
{
  "type": "QUESTION_STARTED",
  "eventId": "jisong",
  "roundId": "round-1",
  "revision": 42
}
```

`roundId` 为事件发生时的当前轮次；活动尚未选择轮次时为 `null`。`revision` 是活动内单调递增的状态版本号，每产生一条广播加一，并与同一时刻 HTTP 快照中的 `EventState.revision` 一致。

客户端收到影响页面状态的广播后调用对应快照接口或榜单接口，以服务端数据渲染页面。客户端按以下规则处理广播：

- 音频控制在该广播到达时同步执行，不等待快照请求完成。音频控制使用广播自带的 `roundId` 查找轮次，不使用稍后可能变化的当前轮次。
- `revision` 小于本地已应用版本号的广播直接丢弃。HTTP 快照的 `revision` 小于本地已应用版本号时同样丢弃，避免旧响应覆盖新状态。
- 同一 `PLAYER_ANSWERED`、`PLAYER_JOINED` 等高频事件在短时间内连续到达时，主持人页面合并为一次快照刷新；同一时刻最多存在一个快照请求。

| 事件类型 | 触发条件 | 客户端处理 |
| :--- | :--- | :--- |
| `ACTIVITY_STARTED` | 线下时间轴启动 | 线下参与者与主持人刷新快照，主持人校准音频播放。 |
| `QUESTION_STARTED` | 线下 `NEXT` 上屏 | 线下参与者刷新当前题与作答状态。 |
| `ANSWER_LOCKED` | 线下 `LOCK` 关闭作答 | 线下参与者禁用选项并刷新提交状态。 |
| `ANSWER_REVEALED` | 线下 `REVEAL` 公开答案 | 线下参与者刷新答案、解析、作品名与出题人。 |
| `ACTIVITY_FINISHED` | 线下 `FINISH` 完成结算 | 线下参与者与主持人刷新本轮榜与结算快照；主持人立即暂停音频并定位到本轮终点。 |
| `ROUND_ENDED` | 到达轮次 `endMs` 或主持人结束末题 | 主持人暂停音频并定位到 `round.endMs`；页面保留当前结算数据。 |
| `ACTIVITY_RESET` | 重置活动或切换轮次 | 线下参与者返回登记页；主持人刷新轮次与房间状态。 |
| `ACTIVITY_PAUSED` | 线下时间轴暂停 | 主持人暂停音频并刷新状态。 |
| `ACTIVITY_RESUMED` | 线下时间轴恢复 | 主持人恢复音频并刷新状态。 |
| `PLAYER_JOINED` | 线下玩家加入 | 主持人刷新在线名册与人数。 |
| `PLAYER_ANSWERED` | 线下玩家提交答案 | 主持人刷新作答进度。 |
| `ONLINE_SCORE_LOCKED` | 线上成绩锁定成功 | 主持人刷新线上榜与总榜；线上参与者以锁定响应更新页面。 |

广播不包含昵称、分数和答案。主持人榜单数据通过榜单接口读取。

## 8. 错误与边界矩阵

| 场景 | HTTP 状态 | 服务端行为 |
| :--- | :--- | :--- |
| 昵称为空或超过 20 个字符 | `400` | 拒绝加入，不创建玩家。 |
| 未知 `mode` | `400` | 拒绝接口请求；前端把未知查询参数归一为 `ONLINE`。 |
| 活动标识不存在 | `404` | 返回活动不存在。 |
| 线下轮次已 `FINISHED` 时加入 | `400` | 拒绝新玩家。 |
| 线下不存在当前题时提交答案 | `400` | 拒绝提交。 |
| 线下重复提交同一题 | `400` | 保留首次提交。 |
| 线下题目已切换后提交 | `400` | 拒绝提交。 |
| 线上题集版本不一致 | `409` | 要求客户端重新加载题集。 |
| 线上答案包含未知题目 | `400` | 拒绝预览或锁定。 |
| 线上答案包含重复题目 | `400` | 拒绝预览或锁定。 |
| 线上选项下标越界 | `400` | 拒绝预览或锁定。 |
| 同一 attempt 重复锁定 | `200` | 返回首次锁定结果，不产生新记录。 |
| 已锁定参与者以不同 attempt 锁定 | `409` | 返回已有锁定状态。 |
| 音频或榜单文件不可读 | `404` 或 `500` | 返回可诊断错误，不返回半成品数据。 |
| 主持人令牌失效 | `401` | 拒绝主持人操作。 |
| 主持人动作状态守卫不满足 | `400` | 保持原状态，不执行动作。 |

## 9. 幂等、并发与一致性

- 线上预览无持久化副作用，可重复调用。
- 线上锁定以 `eventId + participantId` 作为唯一记录键，以 `attemptId` 识别重复请求。
- 线下答案按 `playerId + questionId` 唯一，首次提交生效。
- 线下 `FINISH` 对同一轮次只写入一次快照；重复调度不产生重复记录。
- 线上锁定、线下结算与文本榜单刷新使用串行保护。
- 总榜按请求聚合，聚合过程只读取已持久化记录与当前内存线下状态。
- 榜单读取可以使用不同请求时刻的数据；每次成功锁定或线下结算后刷新文本总结。

## 10. 兼容与缺省值

- `mode` 查询参数缺省为 `online`；未知值由前端归一为 `online` 并显示提示。
- `event` 查询参数缺省为 `jisong`。
- 加入接口的 `mode` 请求字段缺省为 `ONLINE`。
- `participantId` 缺省时由服务端生成并写入响应。
- 线下历史快照缺少 `participantId` 时，以 `playerId` 作为总榜合并键。
- 线下历史快照缺少 `roundId` 时，以该活动内快照序号生成 `legacy-round-{n}`。
- 线下历史条目缺少 `answeredCount` 或 `totalQuestions` 时按 0 处理。
- 榜单接口只读取持久化记录与当前结算状态，不根据昵称合并身份。
