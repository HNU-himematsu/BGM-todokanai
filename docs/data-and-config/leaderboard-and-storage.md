# 排行榜持久化存储与解析

## 1. 存储文件

| 文件 | 内容 | 写入时机 |
| :--- | :--- | :--- |
| `backend/data/leaderboards.json` | 线下轮次快照数组 | 每轮 `FINISH`。 |
| `backend/data/online_leaderboard.json` | 线上锁定记录数组 | 线上成绩首次锁定成功。 |
| `backend/data/nickname_claims.json` | 昵称认领记录数组 | 首次认领、主持人释放或改绑后。 |
| `backend/data/leaderboard_summary.txt` | 线下轮次榜、线上榜与总榜的文本导出 | 每次线下结算或线上锁定后。 |

文件编码统一为 UTF-8。JSON 文件使用两空格缩进，时间字段使用 ISO-8601 UTC 字符串。

进行中的线上 attempt 不写入服务端文件。线上答题进度由浏览器 `localStorage` 保存；服务端只接收预览与锁定请求。

## 2. 身份与合并键

| 标识 | 用途 |
| :--- | :--- |
| `participantId` | 线上与线下共用的总榜合并键，同时是昵称归属对象。 |
| `playerId` | 线下单次玩家会话标识，用于本轮答题与快照条目。 |
| `attemptId` | 线上 attempt 标识，用于幂等锁定与审计。 |

昵称不参与总榜聚合，但通过认领表与 `participantId` 建立唯一归属。历史线下条目缺少 `participantId` 时，以 `playerId` 作为总榜合并键。

### 2.1 昵称认领

每个活动维护一份昵称认领表，`eventId + nicknameKey` 唯一：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `claimId` | String | 认领记录标识；主持人操作按该字段定位。 |
| `eventId` | String | 活动标识。 |
| `nicknameKey` | String | 归一化后的昵称键。 |
| `nickname` | String | 认领时的展示昵称。 |
| `participantId` | String | 昵称归属的参与者标识。 |
| `source` | String | `JOIN`、`HOST` 或 `SEED`。 |
| `claimedAt` | String | 首次认领时间。 |
| `updatedAt` | String | 最近一次创建或改绑时间。 |

归一化规则：Unicode NFKC、去除首尾空白、连续空白折叠为单个空格、去除零宽字符、转小写。归一化只用于判定，展示昵称保留参与者输入。

加入判定：

1. 无认领记录时创建记录并放行。
2. 记录中的 `participantId` 与请求身份一致时放行。
3. 记录中的 `participantId` 与请求身份不一致时返回 `409`，同时记录一条待处理冲突。

认领表在服务端串行保护下读写，写入使用临时文件原子替换。认领写入失败时加入请求失败，不降级放行。

认领文件不存在时视为空表，并在首次认领时创建。

同一轮次内同一 `participantId` 重复加入时复用已有 `playerId`，不创建第二个玩家会话。切换轮次后在线玩家会话清空，下一次加入生成新的 `playerId`，昵称归属保持不变。

昵称认领不随轮次切换、活动重置或服务重启失效。释放、改绑与改名只通过主持人接口执行。

## 3. 线下轮次快照

### 3.1 文件根结构

`leaderboards.json` 为数组，每项对应一次线下轮次结算：

```json
[
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
```

### 3.2 快照字段

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 活动标识。 |
| `eventName` | String | 活动名称。 |
| `roundId` | String | 轮次标识。 |
| `roundName` | String | 轮次名称。 |
| `finishedAt` | String | 本轮结算时间。 |
| `entries` | Array | 本轮参与玩家成绩。 |

### 3.3 条目字段

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `rank` | Integer | 本轮名次，从 1 开始。 |
| `participantId` | String / null | 总榜合并键。 |
| `playerId` | String | 本轮线下会话标识。 |
| `nickname` | String | 本轮展示昵称。 |
| `score` | Integer | 本轮得分，按答对题目的难度分累加：简单题 2 分、中等题 3 分、困难题 5 分。 |
| `durationMs` | Long | 本轮累计有效耗时。 |
| `answeredCount` | Integer | 本轮提交过答案的题目数量。 |
| `totalQuestions` | Integer | 本轮题目总数。 |
| `joinedAt` | String | 玩家加入本轮时间。 |

### 3.4 本轮耗时

- 已提交题目耗时 = 提交时刻减本题 `NEXT` 时刻。
- 锁卷时未提交题目耗时 = `LOCK` 时刻减本题开放时刻。
- 玩家在本题 `NEXT` 之后加入时，开放时刻取玩家加入时刻。
- 玩家在本题锁卷之后加入时不补记该题耗时。
- 单题耗时最小值为 0。
- 本轮 `durationMs` 为各题耗时之和。

### 3.5 本轮排序

1. `score` 降序。
2. `durationMs` 升序。
3. `joinedAt` 升序。
4. `nickname` 升序。

名次在快照写入时固定，不随后续轮次变化。线下榜用于现场评奖；线上成绩不进入该文件。

## 4. 线上锁定记录

### 4.1 文件根结构

`online_leaderboard.json` 为数组，每个 `eventId + participantId` 最多一条记录：

```json
[
  {
    "eventId": "jisong",
    "attemptId": "3e8d7c56-32b1-4f80-9d1f-5e6a4a9b2c77",
    "participantId": "18f1e6a2-6af8-4fc2-bc87-13d603e4868f",
    "nickname": "风见幽香",
    "score": 7,
    "answeredCount": 2,
    "correctCount": 1,
    "wrongCount": 1,
    "totalQuestions": 6,
    "durationMs": 68210,
    "questionSetVersion": "b3f1f5f4b4d07b8f0d9f0d5c2a3e5c1f0e9c8a7b6d5e4f3a2b1c0d9e8f7a6b5c",
    "answers": [
      {
        "questionId": "q-01",
        "optionIndex": 2
      },
      {
        "questionId": "q-03",
        "optionIndex": 0
      }
    ],
    "lockedAt": "2026-10-20T10:02:12.128Z"
  }
]
```

### 4.2 记录字段

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `eventId` | String | 活动标识。 |
| `attemptId` | String | 本次锁定 attempt。 |
| `participantId` | String | 线上唯一键。 |
| `nickname` | String | 锁定时展示昵称。 |
| `score` | Integer | 服务端重新判分后的总分。 |
| `answeredCount` | Integer | 已提交选项的题目数量。 |
| `correctCount` | Integer | 已提交且正确的题目数量。 |
| `wrongCount` | Integer | 已提交且错误的题目数量。 |
| `totalQuestions` | Integer | 锁定时的线上题集总题数。 |
| `durationMs` | Long | 有效作答耗时。 |
| `questionSetVersion` | String | 锁定时题集版本。 |
| `answers` | Array | 规范化后的答案，按题集顺序排列。 |
| `lockedAt` | String | 锁定时间。 |

`answers` 用于审计和重新判分核对，不通过榜单接口返回。

### 4.3 唯一性与幂等

- 唯一键：`eventId + participantId`。
- 同一 `attemptId` 重复请求返回首次记录结果，不新增记录。
- 已存在同一参与者记录时，以不同 `attemptId` 请求返回 `409`。
- 重试不写榜；只有锁定成功才产生记录。
- 分数由服务端根据题库 `correctOption` 计算，客户端分数不参与。
- 线上榜不包含线下轮次记录。

### 4.4 线上榜排序

1. `score` 降序。
2. `durationMs` 升序。
3. `lockedAt` 升序。
4. `nickname` 升序。

名次按查询实时计算，不写入文件。

## 5. 总榜聚合

### 5.1 输入

总榜读取：

- `leaderboards.json` 的全部线下轮次快照。
- `online_leaderboard.json` 的全部线上锁定记录。
- 当前活动仍然存在于内存中的线下结算结果，在线下快照写入后与文件一致。

### 5.2 规范化

对每条线下条目：

1. 合并键取 `participantId`；缺失时取 `playerId`。
2. 轮次键取 `roundId`；缺失时取该活动内快照顺序生成的 `legacy-round-{n}`。
3. 轮次名称取 `roundName`；缺失时取 `第 {n} 轮`。
4. `answeredCount`、`totalQuestions` 缺失时取 0。
5. `joinedAt` 缺失时取所属快照 `finishedAt`。

对同一条线上记录：

1. 合并键取 `participantId`。
2. 同一参与者存在多条历史记录时，取 `lockedAt` 最晚的一条。
3. `lockedAt` 相同时取 `attemptId` 字典序较小的一条。

### 5.3 线下部分

同一参与者在同一轮次存在多条记录时：

1. 取 `score` 最高的一条。
2. 分数相同取 `durationMs` 较短的一条。
3. 分数与耗时相同取 `finishedAt` 较早的一条。
4. 仍相同取记录在文件中的先后顺序。

把每个参与者在各轮次的最佳成绩求和：

```text
offlineScore = Σ(每个 roundId 的最佳 score)
offlineDurationMs = Σ(对应最佳记录的 durationMs)
```

同一参与者的不同 `playerId` 在相同 `participantId` 下合并。

### 5.4 线上部分

```text
onlineScore = 线上锁定 score，无记录时为 0
onlineDurationMs = 线上锁定 durationMs，无记录时为 0
onlineLocked = 是否存在线上锁定记录
```

### 5.5 总榜字段

| 字段 | 类型 | 计算 |
| :--- | :--- | :--- |
| `rank` | Integer | 聚合后排序名次。 |
| `participantId` | String | 合并键。 |
| `nickname` | String | 最近活动记录的昵称。 |
| `totalScore` | Integer | `offlineScore + onlineScore`。 |
| `offlineScore` | Integer | 各轮最佳成绩之和。 |
| `onlineScore` | Integer | 线上锁定成绩或 0。 |
| `totalDurationMs` | Long | 各轮最佳耗时之和加线上锁定耗时。 |
| `offlineRoundCount` | Integer | 线下贡献轮次数。 |
| `onlineLocked` | Boolean | 是否有线上锁定成绩。 |
| `lastActivityAt` | String | 线下最佳记录 `finishedAt` 与线上 `lockedAt` 的较晚值。 |
| `offlineContributions` | Array | 每轮最佳成绩明细。 |
| `onlineContribution` | Object / null | 线上锁定明细。 |

线下贡献明细：

```json
{
  "roundId": "round-1",
  "roundName": "第一轮",
  "score": 30,
  "durationMs": 12000,
  "finishedAt": "2026-10-20T09:20:00.000Z"
}
```

线上贡献明细：

```json
{
  "score": 40,
  "durationMs": 68210,
  "lockedAt": "2026-10-20T10:02:12.128Z"
}
```

### 5.6 昵称选择

总榜昵称取 `lastActivityAt` 对应记录的昵称；时间相同时优先线上锁定昵称。昵称不参与合并。

### 5.7 排序

1. `totalScore` 降序。
2. `totalDurationMs` 升序。
3. `lastActivityAt` 升序。
4. `nickname` 升序。

### 5.8 聚合示例

| 参与者 | 线下第一轮 | 线下第二轮 | 线上锁定 | 总分 | 总耗时 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 甲 | 30 分 / 10s | 20 分 / 9s | 40 分 / 30s | 90 | 49s |
| 乙 | 无 | 无 | 50 分 / 20s | 50 | 20s |
| 丙 | 60 分 / 11s | 无 | 无 | 60 | 11s |

总榜顺序：甲、丙、乙。

若参与者甲在第一轮参加两次，一次 20 分 / 8s、一次 30 分 / 10s，则第一轮贡献取 30 分 / 10s，第二轮与线上成绩正常累加。

## 6. 文本总结

### 6.1 文件位置与编码

`backend/data/leaderboard_summary.txt` 使用 UTF-8 编码与系统换行符。文本按线下轮次榜、线上积分榜、总榜顺序输出。

### 6.2 结构

```text
================================================================================
【线下轮次榜 · 第 1 轮】旮旯原声之届不到的 BGM
结束时间: 2026-10-20 17:20:00 | 参与人数: 12 人
--------------------------------------------------------------------------------
排名       玩家昵称             得分       耗时          已答       参与者ID
--------------------------------------------------------------------------------
第 1 名    风见幽香             30 分      43.1 秒       3/3        18f1e6a2-...
================================================================================
【线上积分榜】旮旯原声之届不到的 BGM
生成时间: 2026-10-20 18:03:00 | 锁定人数: 20 人
--------------------------------------------------------------------------------
排名       玩家昵称             得分       耗时          已答       锁定时间
--------------------------------------------------------------------------------
第 1 名    风见幽香             40 分      68.2 秒       5/6        2026-10-20 18:02:12
================================================================================
【总榜】旮旯原声之届不到的 BGM
规则: 线下各轮最佳成绩之和 + 线上锁定成绩
--------------------------------------------------------------------------------
排名       玩家昵称             总分       线下分     线上分     总耗时       线下轮次     线上锁定
--------------------------------------------------------------------------------
第 1 名    风见幽香             70 分      30 分      40 分      111.3 秒     1 轮         是
================================================================================
```

### 6.3 线下轮次榜板块

每个快照输出一个板块：

- 轮次序号与轮次名称。
- 结束时间与参与人数。
- 条目按快照 `rank` 展示。
- 字段：排名、昵称、得分、耗时、已答数、参与者标识。

### 6.4 线上榜板块

- 生成时间与锁定人数。
- 条目按线上榜排序。
- 字段：排名、昵称、得分、耗时、已答数、锁定时间。

### 6.5 总榜板块

- 显示聚合规则。
- 条目按总榜排序。
- 字段：排名、昵称、总分、线下分、线上分、总耗时、线下轮次数、线上锁定状态。

### 6.6 对齐

- 中文按双字节显示宽度计算填充。
- 表格列之间保留空格。
- 超长昵称不截断身份数据；列宽按当前内容调整。
- 时间以服务端本地时区渲染，JSON 中保留 UTC 时间。

## 7. 写入一致性

### 7.1 串行保护

线下结算、线上锁定、榜单查询与文本刷新使用同一服务内的串行保护。写入顺序：

1. 读取现有 JSON 数组。
2. 追加新记录。
3. 写入同目录临时文件。
4. 原子替换目标文件。
5. 将 JSON 提交视为持久化成功。
6. 重新聚合总榜并刷新文本总结。

JSON 读取或写入失败时抛出错误，不更新内存中的成功状态。文本总结属于派生导出：刷新失败记录日志并保留已提交的 JSON，导出接口可重新生成。临时文件不得作为正式榜单读取。

线下结算先完成 JSON 写入，再设置 `FINISHED` 并广播 `ACTIVITY_FINISHED`。JSON 写入失败时活动保留 `ANSWER_REVEALED`，主持人可重试结束操作；TXT 导出失败不阻止结算状态推进。

### 7.2 幂等写入

- 线下同一 `eventId + roundId + finishedAt` 不重复写入。
- 线上同一 `eventId + participantId` 不重复写入。
- 服务重启后再次收到相同锁定请求，以持久化记录判断幂等。

### 7.3 启动加载

- 文件不存在时视为空数组，并在首次写入时创建。
- JSON 可解析且根节点为数组时加载。
- JSON 损坏时保留文件并阻止新的榜单写入，日志输出文件路径与解析位置。
- 历史字段缺失时按兼容规则在内存中补齐，不自动覆盖原始文件。

## 8. Python 离线工具

`parse_leaderboard.py` 在无 Java 服务时可重新生成文本总结：

```powershell
python parse_leaderboard.py
```

目标行为：

1. 定位项目根目录与 `backend/data/`。
2. 读取 `leaderboards.json`；文件缺失时按空数组处理。
3. 读取 `online_leaderboard.json`；文件缺失时按空数组处理。
4. 使用与 Java 服务一致的兼容规则、最佳成绩聚合与排序。
5. 生成三段文本。
6. 写入 `backend/data/leaderboard_summary.txt`。
7. JSON 损坏或字段类型错误时输出可定位错误并以非零状态退出。

脚本不修改两个 JSON 文件，只读取并输出文本。

### 8.1 昵称认领种子

`seed_nickname_claims.py` 从历史榜单生成昵称认领种子报告：

```powershell
python seed_nickname_claims.py --offline backend/data/leaderboards.json --online backend/data/online_leaderboard.json --output work/nickname-claim-seed.json
```

目标行为：

1. 读取线下轮次快照与线上锁定记录，文件缺失时按空数组处理。
2. 按 `eventId + nicknameKey` 分组，归一化规则与 Java 服务一致。
3. 同一昵称键对应多个 `participantId` 时，按累计得分、线下轮次数、首次出现时间、`participantId` 依次排序，得分最高者作为候选归属。
4. 输出 `claims` 与 `review` 两段；`claims` 可直接作为 `nickname_claims.json` 的根数组，`review` 列出需要人工确认的同名记录。
5. 脚本不写 `backend/data/nickname_claims.json`。确认候选归属后，把 `claims` 写入该文件，再启动服务。

活动开始前预置历史昵称，避免同名玩家在首轮抢占他人昵称。归属有歧义时由主持人依据 `review` 候选列表决定，必要时通过主持人改绑接口调整。

## 9. 备份与恢复

- 活动开始前备份 `questions.json`、`timeline.json`、`nickname_claims.json` 与音频文件。
- 每轮结束后备份 `leaderboards.json` 与文本总结。
- 线上活动期间定期备份 `online_leaderboard.json`。
- 主持人释放、改绑或预置昵称认领后备份 `nickname_claims.json`。
- 恢复时把备份文件放回 `backend/data/`，再启动服务。
- 恢复文件后重新生成文本总结，核对线下总人数、线上锁定人数与总榜条目。

## 10. 数据保护

- 榜单接口只返回昵称、分数、耗时、名次、时间与参与者标识。
- 不返回玩家答案、正确答案、解析与作品名。
- `online_leaderboard.json` 中的 `answers` 仅用于服务端审计。
- `participantId` 是浏览器生成的合并键，不视为认证凭据。
- 文本总结可包含参与者标识；公开下载前由主持人确认展示范围。

## 11. 验收

- 每个线下 `FINISH` 生成一条快照。
- 快照条目包含 `roundId`、`participantId`、得分、耗时与已答数。
- 同一参与者线下多轮成绩按轮次最佳值求和。
- 线上锁定按参与者唯一，重复 attempt 幂等。
- 线上榜不混入线下成绩，线下轮次榜不混入线上成绩。
- 总榜包含线下各轮最佳成绩之和与线上锁定成绩。
- 文本总结包含三个板块且排序与接口一致。
- 服务重启后历史快照与线上锁定记录保持可读。
- Python 工具生成的文本与 Java 服务输出规则一致。
- 同一活动内一个归一化昵称只归属一个 `participantId`。
- 昵称归属者切换轮次或跨天重新加入时正常放行，并生成新的 `playerId`。
- 其他身份使用已认领昵称时返回 `409`，不创建玩家会话。
- 同一轮次内同一 `participantId` 重复加入只保留一个玩家会话。
- 主持人释放昵称后，其他身份可以重新认领。
- 主持人改绑昵称后，新身份加入成功，旧身份使用该昵称返回 `409`。
