# 双模式答题系统开发规格

## 0. 文档定位

本文档是线上模式与线下模式开发的统一功能基线。后续代码、接口、数据结构、页面交互和验收用例以本文档为准。

题库文件 `questions.json`、编排文件 `timeline.json` 与完整音频文件由运营方提供。素材未到位时，开发按本文档定义的数据契约实现；素材到位后进行联调与验收，不改变本文档定义的流程与字段语义。

## 1. 系统范围

系统包含两种参与模式：

- **线上模式**：网页默认入口。参与者在一台设备上独立完成题库全部题目，浏览器本地保存答题进度；全部题目结束后由服务端预览总分，参与者选择重试或锁定；锁定后的总分进入线上积分榜与总榜。
- **线下模式**：现场二维码入口。参与者加入主持人选定的轮次，主持人控制本轮音频与时间轴；服务端按编排自动出题、锁卷、揭晓与结算；每轮生成独立线下榜，用于现场评奖；线下各轮成绩按参与者汇总后进入总榜。

两种模式共用同一份题库、同一段完整音频、同一套计分规则和同一套参与者身份标识。线上模式不使用轮次；线下模式保留轮次与主持人控制。

## 2. 术语

| 术语 | 定义 |
| :--- | :--- |
| 活动（Event） | 一次完整的猜歌活动，默认活动标识为 `demo`。 |
| 线上模式（ONLINE） | 参与者独立答题、无轮次、浏览器本地保存进度、锁定后入榜的模式。 |
| 线下模式（OFFLINE） | 主持人按轮次控制现场音频与时间轴、逐题揭晓、每轮独立榜单的模式。 |
| 题库（Question Bank） | `questions.json` 中的全部题目。 |
| 编排（Timeline） | `timeline.json` 中的完整音频、轮次、题目引用与动作标记。 |
| 线上题集（Online Question Set） | 按 `timeline.json` 顺序拉平、按 `questionId` 去重后的全部题目序列。 |
| 线上答题尝试（Online Attempt） | 一个参与者从开始答题到锁定或重试之间的一次完整答题过程。 |
| 线上进度（Online Progress） | 浏览器本地保存的当前题号、已选答案、音频位置与预览结果。 |
| 预览分（Preview Score） | 全部题目结束后，服务端根据提交答案计算、但不写入榜单的总分。 |
| 锁定（Lock） | 参与者确认本次总分，服务端写入线上积分榜并返回总榜名次。 |
| 重试（Retry） | 清空本地答题进度并开始新的 attempt，不写入任何榜单。 |
| 线下轮次（Offline Round） | 主持人选择并启动的一段现场答题区间。 |
| 线下榜（Offline Leaderboard） | 每个线下轮次结算生成的独立榜单，用于现场评奖。 |
| 线上积分榜（Online Leaderboard） | 线上锁定成绩组成的榜单，每个参与者一条记录。 |
| 总榜（Total Leaderboard） | 线下各轮最佳成绩之和与线上锁定成绩合并后的榜单。 |

## 3. 模式入口与路由

### 3.1 入口规则

| 入口 | 地址 | 模式 |
| :--- | :--- | :--- |
| 网页默认入口 | `/` | 线上模式 |
| 现场二维码 | `/?mode=offline&event=demo` | 线下模式 |
| 显式线上入口 | `/?mode=online&event=demo` | 线上模式 |

`event` 参数缺省值为 `demo`。`mode` 参数缺省值为 `online`。未知的 `mode` 值按 `online` 处理，并在页面显示提示。

### 3.2 页面路由

| 路由 | 页面 | 说明 |
| :--- | :--- | :--- |
| `/` | `JoinView.vue` | 线上或线下登记页，由 `mode` 参数决定页面文案与加入行为。 |
| `/play` | `PlayView.vue` | 根据当前模式进入线上答题面板或线下答题面板。 |
| `/host` | `HostView.vue` | 主持人控制台，仅线下流程使用。 |

### 3.3 模式切换

- 线下登记页提供「进入线上模式」按钮，跳转到 `/`，并将会话模式切换为 `online`。
- 线上登记页提供现场二维码提示，说明现场参与者通过二维码进入线下模式。
- 模式变化不删除浏览器中已有的线上答题进度。线上进度按 `eventId` 与 `questionSetVersion` 恢复；线下玩家凭据按 `sessionStorage` 恢复。
- `/play` 页面刷新时，从 `sessionStorage` 读取当前模式；模式缺失时回到 `/`。

## 4. 共用数据契约

### 4.1 题库文件 `questions.json`

题库为 JSON 数组，每道题包含以下字段：

| 字段 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 全题库唯一，非空 | 题目编号。 |
| `prompt` | String | 非空 | 题干文本。 |
| `type` | String | `WORK` / `ATMOSPHERE` | 作品题或氛围题。 |
| `difficulty` | String | `EASY` / `MEDIUM` / `HARD` | 难度等级。 |
| `options` | String[] | 长度 4 | 四个选项。 |
| `correctOption` | Integer | 0 至 3 | 正确选项下标。 |
| `workName` | String | 非空 | 作品名称，线上模式不得下发。 |
| `explanation` | String | 非空 | 解析文本，线上模式不得下发。 |
| `audioCode` | String | 非空 | 音频素材编号，用于素材核对。 |

### 4.2 编排文件 `timeline.json`

线上与线下共用一份 `timeline.json` 和一份完整音频文件。音频文件由 `audioFile` 字段指定，与编排文件同目录。

| 字段 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `eventId` | String | 非空 | 活动标识。 |
| `eventName` | String | 非空 | 活动名称。 |
| `audioFile` | String | 非空 | 完整音频文件名。 |
| `rounds` | Array | 至少 1 项 | 线下轮次列表。 |

轮次字段：

| 字段 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 全局唯一 | 轮次标识。 |
| `name` | String | 非空 | 轮次名称。 |
| `startMs` | Long | `startMs < endMs` | 轮次音频起始位置。 |
| `endMs` | Long | 与其它轮次不重叠 | 轮次音频结束位置。 |
| `items` | Array | 非空 | 动作条目列表。 |

动作条目字段：

| 字段 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `atMs` | Long | 轮次内严格递增，位于 `[startMs, endMs]` | 动作触发时刻。 |
| `action` | String | `LOAD` / `NEXT` / `LOCK` / `REVEAL` / `FINISH` | 动作类型。 |
| `questionId` | String | 仅 `LOAD` 必填 | 引用题库题目。 |
| `audioStartMs` | Long | 仅 `LOAD` 必填 | 本题音频片段起点。 |
| `audioEndMs` | Long | 仅 `LOAD` 必填，大于 `audioStartMs` | 本题音频片段终点。 |

### 4.3 线上题集生成规则

线上题集按以下顺序生成：

1. 按 `rounds` 数组顺序遍历轮次。
2. 按轮次内 `items` 数组顺序遍历动作。
3. 遇到 `LOAD` 时记录题目引用、`audioStartMs`、`audioEndMs`。
4. 遇到后续 `NEXT` 时记录该题的 `answerStartMs`。
5. 遇到后续 `LOCK` 时记录该题的 `answerEndMs`。
6. 按 `questionId` 去重；重复题目保留首次出现的位置、音频片段与作答窗口。
7. 忽略 `REVEAL` 与 `FINISH`，忽略轮次边界。

线上题目对象由以下字段组成：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `index` | Integer | 线上题集中的序号，从 0 开始。 |
| `id` | String | 题目编号。 |
| `prompt` | String | 题干。 |
| `type` | String | 题型。 |
| `difficulty` | String | 难度。 |
| `options` | String[] | 四个选项。 |
| `audioStartMs` | Long | 本题音频起点。 |
| `audioEndMs` | Long | 本题音频终点。 |
| `answerStartMs` | Long | 本题开放作答时刻。 |
| `answerEndMs` | Long | 本题关闭作答时刻。 |

线上题目对象不得包含 `correctOption`、`workName`、`explanation`。

### 4.4 线上题集版本

服务端启动时计算 `questionSetVersion`，计算输入为线上题集的有序元组：

```text
questionId, audioStartMs, audioEndMs, answerStartMs, answerEndMs
```

各题元组以换行 `\n` 连接，末尾不附加换行。题目编号不得包含逗号或换行。版本值取该 UTF-8 字节串的 SHA-256 小写十六进制字符串。浏览器本地进度保存该版本；版本不一致时丢弃旧进度。

### 4.5 音频约束

- 线上与线下共用一段完整音频。
- 音频包含全部题目的播放内容，题目之间无空白段。
- 第一题 `audioStartMs` 为 0。
- 最后一题 `audioEndMs` 为线上播放终点，与完整音频可播放总时长的差值不超过 100 毫秒。
- 线上按线上题集顺序连续播放；相邻题目的 `audioEndMs` 与下一题的 `audioStartMs` 相等。
- 线下按轮次区间播放；主持人定位到 `round.startMs`，到达 `round.endMs` 停止本轮播放。
- 线上题目的作答窗口满足 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。
- 音频文件缺失或不可读时，接口返回明确错误，页面显示不可开始答题的状态。

### 4.6 编排校验

服务端启动时执行以下校验，任一失败即拒绝启动：

1. 轮次区间合法且互不重叠。
2. 轮次内 `atMs` 严格递增并位于轮次区间内。
3. 每个 `LOAD` 后存在未消费的 `NEXT`，且每个 `NEXT` 前存在未消费的 `LOAD`。
4. 每个 `LOAD` 后存在 `LOCK`，且 `LOCK` 位于下一个 `LOAD` 前完成。
5. 每个 `LOAD` 的 `audioStartMs < audioEndMs`，音频片段位于所属轮次区间内。
6. 线上题集满足 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。
7. 线上题集中相邻题目的音频片段首尾相接，题目之间无空白段。
8. 题库中的每个 `id` 至少被一个 `LOAD` 引用。
9. 同一轮次内不得重复引用同一题目；跨轮次重复题目在线上题集中按首次出现去重。
10. `action`、`type`、`difficulty` 均属于定义内的枚举值。
11. 每个 `LOCK` 后存在当前题 `REVEAL`，下一题 `LOAD` 晚于上一题 `REVEAL`。
12. 每轮存在 `FINISH`，且 `FINISH` 晚于本轮最后一个 `REVEAL`。

## 5. 参与者身份

### 5.1 标识

| 标识 | 存储位置 | 生命周期 | 用途 |
| :--- | :--- | :--- | :--- |
| `participantId` | `localStorage` | 长期，直到清除站点数据 | 线上与线下共用身份，用于总榜合并。 |
| `nickname` | `localStorage` / `sessionStorage` | 长期，直到用户修改 | 页面展示与榜单展示。 |
| `playerId` | `sessionStorage` | 单次线下加入会话 | 线下轮次内的玩家会话标识。 |
| `attemptId` | `localStorage` | 单次线上尝试 | 线上重试、预览与锁定幂等。 |

`participantId` 由浏览器使用 `crypto.randomUUID()` 生成；不可用时使用时间戳与随机数组合。服务端只校验其非空与长度，不将其视为身份认证凭据。

### 5.2 线上加入

线上加入不创建线下玩家会话。`POST /api/events/{eventId}/join` 在 `mode=ONLINE` 时：

- 校验昵称非空、长度不超过 20。
- 读取或生成 `participantId`。
- 查询该 `participantId` 是否已锁定线上成绩。
- 返回 `mode=ONLINE`、`participantId`、`nickname` 与锁定状态。

### 5.3 线下加入

线下加入在 `mode=OFFLINE` 时：

- 校验昵称非空、长度不超过 20。
- 生成单次 `playerId`。
- 记录 `participantId`、昵称与加入时刻。
- 轮次已进入 `FINISHED` 时拒绝加入。

## 6. 线上模式流程

### 6.1 状态定义

浏览器侧状态：

| 状态 | 说明 |
| :--- | :--- |
| `IDLE` | 已进入线上页面，尚未开始答题。 |
| `IN_PROGRESS` | 音频播放与自动出题进行中。 |
| `RESULT_READY` | 全部题目音频结束，结果页可用；预览分处于加载、成功或失败状态。 |
| `LOCKED` | 已锁定，显示线上榜与总榜名次。 |
| `ERROR` | 音频、网络或数据异常，显示重试入口。 |

服务端侧状态：

| 状态 | 说明 |
| :--- | :--- |
| `NOT_LOCKED` | 该 `participantId` 没有线上锁定记录。 |
| `LOCKED` | 该 `participantId` 已有线上锁定记录。 |

### 6.2 主流程

1. 参与者访问 `/`，页面进入线上模式。
2. 输入昵称，阅读规则，调用加入接口。
3. 页面调用线上题集接口，获取题目、音频地址与 `questionSetVersion`。
4. 页面读取本地进度：
   - 版本一致且状态为 `IN_PROGRESS`：显示「继续答题」。
   - 版本一致且状态为 `RESULT_READY`：显示预览结果与重试/锁定。
   - 版本不一致、进度损坏或不存在：显示「开始答题」。
   - 服务端返回已锁定：显示锁定结果，不开始新 attempt。
5. 用户点击「开始答题」或「继续答题」，浏览器播放音频并建立答题时钟。
6. 页面按线上题集顺序自动展示题目：
   - `answerStartMs` 到达时显示当前题。
   - `answerEndMs` 到达时关闭当前题作答；下一题在自身 `answerStartMs` 到达时上屏。
   - 未答题按 0 分处理。
   - 不显示正确答案、解析、作品名或逐题对错。
7. 最后一题音频播放到 `audioEndMs` 后停止音频，状态进入 `RESULT_READY`。
8. 页面提交全部已选答案到预览接口，显示总分。
9. 结果页提供「重试」与「锁定分数」。
10. 重试：清空本地进度，生成新 `attemptId`，回到第 5 步。
11. 锁定：提交答案到锁定接口；服务端重新判分、写入线上积分榜、返回线上榜名次与总榜名次；页面清空本地进度并显示锁定结果。

### 6.3 自动推进规则

- 线上答题以音频播放位置作为时钟，不使用服务端轮次时间轴。
- 音频从第一题 `audioStartMs` 开始播放，连续播放到最后一题 `audioEndMs`。
- 当前题在 `answerStartMs` 上屏，在 `answerEndMs` 锁定。
- 题目之间不暂停、不显示揭晓、不等待用户确认。
- 用户不能跳题、不能返回上一题、不能暂停音频。
- 页面隐藏、刷新或关闭时，浏览器暂停音频并保存音频位置、当前题号、剩余作答时间与已选答案；用户返回后点击「继续答题」，从保存位置恢复。
- 音频播放失败、被浏览器策略阻止或加载超时时，进入 `ERROR`，显示「重新加载音频」与「重试本次答题」。

### 6.4 进度控件

线上答题页提供只读进度控件：

- 显示「第 x / N 题」。
- 显示总进度条。
- 显示已答与未答数量。
- 可显示题目序号圆点，但圆点不可点击。
- 不提供上一题、下一题、跳题、回看或修改已锁定题目的入口。

已锁定题目的选项保持只读；当前题在锁定前可修改选项。

### 6.5 本地进度结构

本地进度键：`bgm.online.progress.v1`。

```json
{
  "version": 1,
  "eventId": "demo",
  "participantId": "uuid",
  "attemptId": "uuid",
  "questionSetVersion": "sha256",
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

`activeDurationMs` 为音频实际播放时长累计值，页面隐藏、暂停与音频错误期间不增长。预览与锁定请求的 `durationMs` 取该值。

写入时机：

- 开始答题。
- 每道题上屏。
- 每次选择或修改选项。
- 每道题锁定。
- 最后一题音频结束，状态转为 `RESULT_READY`。
- 答题过程中每 500 毫秒。
- 音频暂停、页面隐藏、页面关闭前。
- 预览分返回。

清理时机：

- 锁定成功。
- 用户点击重试。
- `questionSetVersion` 不一致。
- 本地数据损坏或无法解析。

浏览器存储只保存答题进度，不保存正确答案、解析、作品名或服务端判定结果。

### 6.6 预览分

预览接口只计算分数，不写榜单：

- 请求包含 `participantId`、`nickname`、`attemptId`、`questionSetVersion`、`answers`、`durationMs`。
- `answers` 为已作答题目数组，每项包含 `questionId` 与 `optionIndex`。
- 每个 `questionId` 最多出现一次；未知题目、重复题目或越界选项返回错误。
- 未出现在 `answers` 中的题目按 0 分处理。
- `durationMs` 为有效音频播放耗时，不含页面隐藏期间的暂停时间。
- 服务端校验题目编号与选项下标，使用题库正确答案计算总分。
- 客户端提交的 `questionSetVersion` 必须与服务端一致。
- 响应只返回 `score`、`answeredCount`、`totalQuestions`、`durationMs`、`questionSetVersion`。
- 响应不包含 `correctOption`、`workName`、`explanation` 或逐题对错。

### 6.7 重试

- 仅在 `RESULT_READY` 状态可用。
- 清空本地进度与预览分。
- 生成新的 `attemptId`。
- 从第一题重新开始音频。
- 不写入线上榜，不影响历史锁定记录。

### 6.8 锁定

- 仅在全部题目音频播放结束且页面处于 `RESULT_READY` 状态时可用。
- 未作答题目按 0 分处理，不阻止锁定。
- 请求包含与预览相同的答案集合、`participantId`、`nickname`、`attemptId`、`questionSetVersion`、`durationMs`。
- 服务端重新判分，不信任客户端分数。
- 服务端校验 `questionSetVersion`，版本不一致时拒绝锁定。
- 服务端以 `participantId` 为唯一键写入线上积分榜；同一参与者只保留一条锁定记录。
- 同一 `attemptId` 重复锁定返回已有结果，不重复写入。
- 不同 `attemptId` 在已有锁定记录后再次锁定返回冲突错误。
- 锁定成功后返回 `score`、`onlineRank`、`totalRank`、`lockedAt`。
- 页面显示总分、线上榜名次与总榜名次，清空本地进度，进入 `LOCKED`。

### 6.9 结果页

结果页显示：

- 本次总分。
- 已答题目数 / 总题目数。
- 有效作答耗时。
- 「重试」按钮。
- 「锁定分数」按钮。

锁定后显示：

- 总分。
- 线上积分榜名次。
- 总榜名次。
- 锁定时间。
- 不显示重试按钮。

结果页不显示正确答案、解析、作品名或逐题对错。

## 7. 线下模式流程

线下模式按以下流程运行：

1. 参与者扫描二维码进入 `/?mode=offline&event=demo`，输入昵称加入。
2. 主持人登录 `/host`，选择轮次并点击开始播放。
3. 控制台将音频定位到 `round.startMs` 并播放，同时调用启动接口建立时间轴零点 `T0`。
4. 服务端按 `T0 + (atMs - startMs)` 触发 `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH`。
5. 参与者逐题作答；`NEXT` 上屏，`LOCK` 关闭作答，`REVEAL` 公布答案与解析。
6. `FINISH` 结算本轮，生成线下轮次快照与线下榜。
7. 到达 `round.endMs` 时时间轴与音频暂停，主持人切换轮次或重置。
8. 主持人可执行暂停、恢复、锁卷、揭晓、下一题等手动兜底操作。

线下模式保留逐题揭晓、轮次切换、主持人控制与每轮独立榜。

## 8. 计分与榜单

### 8.1 题目计分

- 每题正确得 10 分。
- 答错或未作答得 0 分。
- 线上总分 = 全部题目正确数 × 10。
- 线下单轮得分 = 本轮正确数 × 10。

### 8.2 线下榜

- 每个线下轮次在 `FINISH` 时生成独立榜单。
- 排序：得分降序，累计作答耗时升序，加入时间升序，昵称升序。
- 线下榜用于现场评奖。
- 线下榜不包含线上锁定成绩。

### 8.3 线上积分榜

- 每个 `participantId` 一条锁定记录。
- 排序：得分降序，有效作答耗时升序，锁定时间升序，昵称升序。
- 线上榜只包含线上锁定成绩。
- 线上成绩不写入线下榜。

### 8.4 总榜

总榜由两部分组成：

1. **线下部分**：按 `participantId` 分组，每个线下轮次取该参与者的最佳一次成绩；同分取耗时更短的一次；跨轮次求和。
2. **线上部分**：该 `participantId` 的线上锁定成绩，最多一条。

总榜条目包含：

- `participantId`
- `nickname`
- `totalScore`
- `offlineScore`
- `onlineScore`
- `totalDurationMs`
- `offlineRounds`
- `onlineLocked`
- `lastActivityAt`
- 线下各轮贡献明细

总榜排序：

1. `totalScore` 降序。
2. `totalDurationMs` 升序。
3. `lastActivityAt` 升序。
4. 昵称升序。

线上锁定成功后，结果页显示该参与者当前总榜名次。总榜名次随后续线下结算或线上锁定变化；页面刷新后展示最新名次。

### 8.5 身份合并

- 线上与线下共用 `participantId` 时，总榜合并同一参与者的线下各轮成绩与线上锁定成绩。
- 缺少 `participantId` 的历史线下记录以 `playerId` 作为合并键。
- 历史记录缺少 `roundId` 时，以快照序号生成轮次键。
- 昵称用于展示，不作为唯一合并键。

## 9. 接口契约

完整字段、示例与错误码见 [`../backend/api-spec.md`](../backend/api-spec.md)。接口分组如下：

### 9.1 参与者公共接口

| 方法与路径 | 用途 |
| :--- | :--- |
| `POST /api/events/{eventId}/join` | 线上或线下加入，返回模式、参与者标识与锁定状态。 |
| `GET /api/events/{eventId}` | 获取活动摘要。 |
| `GET /api/events/{eventId}/audio` | 获取完整音频流，支持 HTTP Range。 |

### 9.2 线上接口

| 方法与路径 | 用途 |
| :--- | :--- |
| `GET /api/events/{eventId}/online/questions` | 获取线上题集、音频地址与题集版本。 |
| `GET /api/events/{eventId}/online/status` | 查询指定参与者的线上锁定状态。 |
| `POST /api/events/{eventId}/online/preview` | 计算预览分，不写榜。 |
| `POST /api/events/{eventId}/online/lock` | 锁定成绩，写入线上榜并返回总榜名次。 |
| `GET /api/events/{eventId}/leaderboards/online` | 获取线上积分榜。 |
| `GET /api/events/{eventId}/leaderboards/total` | 获取总榜。 |

### 9.3 线下接口

线下接口沿用活动状态、答案提交、主持人控制、轮次榜单与音频接口。完整列表见 [`../backend/api-spec.md`](../backend/api-spec.md)。

## 10. 前端实现规格

### 10.1 路由与状态

- `router/index.js` 继续提供 `/`、`/play`、`/host`。
- `JoinView.vue` 解析 `mode` 与 `event` 查询参数。
- `activity` store 保存模式、活动信息、参与者身份与线下状态。
- `online` store 保存线上题集、`attemptId`、答案、音频位置、预览结果与锁定结果。
- `host` store 保存主持人令牌、轮次、主持人快照与三类榜单页签。
- `sessionStorage` 保存当前模式与线下玩家凭据。
- `localStorage` 保存 `participantId`、昵称与线上进度。

### 10.2 视图拆分

- `PlayView.vue` 作为模式分发容器。
- `OfflinePlayPanel.vue` 承载线下逐题答题、揭晓与结算界面。
- `OnlinePlayPanel.vue` 承载线上自动推进、进度控件、预览、重试与锁定界面。
- `HostView.vue` 增加线上榜与总榜查看区域。

### 10.3 线上答题界面

- 顶部显示活动名称、线上模式标识与退出入口。
- 显示只读进度控件：「第 x / N 题」、总进度条、已答/未答数量。
- 题目卡片展示题干、题型与四个选项。
- 当前题锁定前可修改选项；锁定后只读。
- 不显示答案、解析、作品名、正确/错误配色或逐题结果。
- 页面隐藏时暂停音频并保存进度；恢复时显示「继续答题」。
- 结果页显示总分与重试/锁定按钮；锁定后显示线上榜名次与总榜名次。

### 10.4 线下界面

线下界面保留登记、等待、逐题作答、锁卷、揭晓、结算与每轮榜单。线下登记页增加「进入线上模式」按钮。

### 10.5 主持人界面

- 二维码地址固定为 `${publicBaseUrl}/?mode=offline&event=${eventId}`。
- 控制台继续提供轮次选择、开始、暂停、恢复、锁卷、揭晓、下一题、重置。
- 榜单区域增加「本轮榜 / 线上榜 / 总榜」切换。
- 线上锁定触发 `ONLINE_SCORE_LOCKED` 广播后，主持人页面刷新线上榜与总榜。

## 11. 后端实现规格

### 11.1 服务拆分

| 组件 | 职责 |
| :--- | :--- |
| `ActivityService` | 线下轮次、玩家、时间轴、作答、结算。 |
| `TimelineStore` | 题库与编排加载、线上题集生成、校验、题集版本。 |
| `OnlineAttemptService` | 线上题集读取、预览分、锁定、线上榜查询、总榜查询。 |
| `LeaderboardStore` | 线下快照、线上锁定记录、总榜聚合、文本导出。 |
| `HostAuthService` | 主持人认证。 |

### 11.2 线上服务状态

- 线上答题进度保存在浏览器，服务端不保存进行中的 attempt。
- 服务端只保存锁定记录与线下轮次快照。
- 预览接口无持久化副作用。
- 锁定接口以 `participantId` 与 `attemptId` 保证幂等。

### 11.3 并发与一致性

- 线上锁定与线下结算写入使用同步保护。
- 同一 `participantId` 的线上锁定只允许一条记录。
- 总榜按请求实时聚合，不单独持久化排行结果。
- 文本榜单在每次线下结算或线上锁定后刷新。

## 12. 存储规格

完整结构见 [`../data-and-config/leaderboard-and-storage.md`](../data-and-config/leaderboard-and-storage.md)。

- `backend/data/leaderboards.json`：线下轮次快照数组。
- `backend/data/online_leaderboard.json`：线上锁定记录数组。
- `backend/data/leaderboard_summary.txt`：线下轮次榜、线上榜、总榜的文本导出。
- `backend/data/questions.json`：题库。
- `backend/data/timeline.json`：编排。
- `backend/data/<audioFile>`：完整音频。

## 13. 异常与边界

| 场景 | 处理 |
| :--- | :--- |
| 音频缺失或不可读 | 音频接口返回错误；线上页面显示不可开始答题；线下控制台显示播放错误。 |
| 浏览器阻止自动播放 | 线上页面要求用户点击「开始答题」或「继续答题」；线下控制台要求主持人点击「开始播放」。 |
| 本地进度损坏 | 丢弃进度，从第一题重新开始。 |
| 题集版本变化 | 丢弃旧进度，提示题目已更新。 |
| 本地存储不可用 | 允许开始答题，但刷新后无法恢复；页面提示进度不会保存。 |
| 预览请求失败 | 保留本地答案，显示重试预览入口。 |
| 锁定请求失败 | 保留本地答案与预览结果，允许再次锁定；服务端幂等。 |
| 重复锁定 | 同一 `attemptId` 返回已有结果；不同 `attemptId` 返回冲突。 |
| 已锁定后重新进入 | 显示锁定结果，不开始新 attempt。 |
| 线上用户跳过题目 | 未答题目按 0 分；全部题目结束后可预览与锁定。 |
| 线下轮次已结束 | 拒绝新玩家加入；主持人切换轮次或重置。 |
| 主持人断线重连 | 重新获取主持人快照并校准音频与时间轴状态。 |

## 14. 安全与数据保护

- 线上题目响应只包含题干、选项、题型、难度与音频时间字段，不包含正确答案、解析与作品名。
- 线上预览与锁定均由服务端计算分数；客户端提交的分数不参与计算。
- 本地进度可被用户修改；服务端以锁定请求中的答案重新判分。计时公平性由浏览器本地执行，服务端不提供防篡改计时。
- `participantId` 是浏览器生成的合并键，不是认证凭据。清除站点数据后生成新标识，可能形成新的线上锁定记录。
- 主持人接口继续使用 `X-Host-Token` 校验。
- 榜单只展示昵称、分数、耗时与时间，不展示答题选项。

## 15. 验收标准

### 15.1 模式与入口

- 访问 `/` 进入线上模式。
- 访问 `/?mode=offline&event=demo` 进入线下模式。
- 线下登记页可跳转到线上模式。
- 主持人二维码包含 `mode=offline&event=demo`。

### 15.2 线上答题

- 线上题集覆盖题库全部题目，按 timeline 顺序去重。
- 音频连续播放，题目之间无空白。
- 题目自动上屏、自动锁定、自动进入下一题。
- 进度控件只读，不能跳题、回看或修改已锁定题目。
- 未答题目按 0 分。
- 全部题目结束后显示预览分，不显示答案。

### 15.3 重试与锁定

- 重试清空本地进度，不写线上榜。
- 锁定写入一条线上记录，返回线上榜名次与总榜名次。
- 重复锁定同一 `attemptId` 不重复写入。
- 已锁定后不提供重试入口。

### 15.4 本地进度

- 刷新、关闭后重新打开，同一浏览器可恢复当前题号、答案与音频位置。
- 题集版本变化后旧进度失效。
- 锁定成功后本地进度清除。

### 15.5 线下流程

- 主持人选择轮次并开始后，时间轴按编排推进。
- 逐题揭晓与每轮榜单正常。
- 轮次切换清空在线玩家并回到 `WAITING`。
- 线下榜用于评奖。

### 15.6 榜单

- 线上榜只包含线上锁定成绩。
- 线下榜只包含对应轮次的线下成绩。
- 总榜包含线下各轮最佳成绩之和与线上锁定成绩。
- 总榜名次在锁定响应中返回。

## 16. 开发任务拆分

### 16.1 数据与素材

- 提供 `questions.json`。
- 提供 `timeline.json`。
- 提供完整音频文件。
- 按本文档校验规则补齐题目音频片段、作答窗口与轮次区间。

### 16.2 后端

- 增加 `AppMode` 枚举与加入接口模式字段。
- 增加 `participantId` 与线下玩家身份记录。
- 扩展 `TimelineStore`：线上题集、题集版本、线上校验。
- 增加 `OnlineAttemptService`：预览、锁定、线上榜、总榜。
- 扩展 `LeaderboardStore`：线下快照字段、线上锁定记录、总榜聚合、文本导出。
- 增加线上与总榜接口。
- 增加线上锁定广播。
- 增加单元测试与接口测试。

### 16.3 前端

- 增加模式解析与模式切换。
- 增加 `participantId` 与线上进度本地存储。
- 拆分线下/线上答题面板。
- 增加线上进度控件、自动推进、预览、重试、锁定与结果页。
- 增加主持人线上榜与总榜视图。
- 增加浏览器存储、音频播放与异常提示。

### 16.4 验收

- 按第 15 节逐项验收。
- 使用真实题库、编排与音频进行线上与线下联调。
- 使用两个浏览器验证线上锁定、线下轮次与总榜合并。

## 17. 素材与开发顺序

素材未到位时，先完成数据契约、接口、状态机、页面结构与本地进度逻辑；音频与题库到位后接入真实数据并执行联调。

开发顺序：

1. 文档与数据契约。
2. `TimelineStore` 线上题集与校验。
3. 线上预览与锁定接口。
4. 线下快照扩展与总榜聚合。
5. 前端模式分流与本地进度。
6. 线上答题界面与结果页。
7. 主持人榜单视图。
8. 题库、编排、音频联调。
9. 验收与问题修复。
