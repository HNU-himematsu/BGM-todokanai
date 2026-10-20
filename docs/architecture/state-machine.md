# 线上与线下状态机

## 1. 模式边界

系统包含两套独立状态机：

- **线下状态机**：由 `ActivityService` 维护，以活动、轮次与时间轴为核心，服务端按编排动作推进。
- **线上状态机**：由浏览器与 `OnlineAttemptService` 协作，以一次全题库 attempt 为核心，浏览器按题目级时间标记自动推进，服务端负责预览与锁定。

两套状态机共用题库、音频、计分规则与 `participantId`，不共享进行中的题目游标与作答状态。

## 2. 线下状态机

### 2.1 状态枚举 `ActivityStatus`

```text
WAITING ──NEXT──> ANSWERING ──LOCK──> ANSWER_LOCKED ──REVEAL──> ANSWER_REVEALED
   ▲                                                                  │
   │                                              后续题 NEXT（回到 ANSWERING）
   │                                                                  │
   │                                               最后一题 FINISH      ▼
   └──────────── 切换轮次 / reset() ────────────── FINISHED
```

| 状态 | 说明 |
| :--- | :--- |
| `WAITING` | 活动初始化、重置或切换轮次后的就绪状态。允许参与者加入。 |
| `ANSWERING` | `NEXT` 上屏当前题，开放作答。服务端下发脱敏题目。 |
| `ANSWER_LOCKED` | `LOCK` 关闭作答，不公布答案。锁卷时为未提交玩家补记耗时。 |
| `ANSWER_REVEALED` | `REVEAL` 公布正确答案、解析与作品名。 |
| `FINISHED` | `FINISH` 结算本轮并归档线下榜单。拒绝新玩家加入。 |

### 2.2 轮次与时间轴

- 编排文件定义一个完整音频内的多个轮次；每个轮次由 `[startMs, endMs]` 与动作条目组成。
- 轮次之间不重叠；轮次可重复选择播放，用于接待不同批次参与者。
- 主持人控制台在未开始、已暂停或已结束时允许切换轮次；切换操作清空在线玩家并回到 `WAITING`。
- 主持人将音频定位到当前轮次 `startMs` 后开始播放，同时调用启动接口建立零点 `T0`。
- 动作触发时刻按下式计算：

```text
触发时刻 = T0 + (atMs - startMs)
```

- 到达 `round.endMs` 时，服务端停止时间轴并广播 `ROUND_ENDED`，控制台暂停音频。

### 2.3 动作语义

| 动作 | 语义 | 状态流转 | 广播事件 |
| :--- | :--- | :--- | :--- |
| `LOAD` | 预加载题目与音频片段至待上屏槽位 | 状态不变 | 无 |
| `NEXT` | 待上屏内容上屏、题号递增、开放作答 | `WAITING` / `ANSWER_REVEALED` → `ANSWERING` | `QUESTION_STARTED` |
| `LOCK` | 关闭作答并补记缺席耗时 | `ANSWERING` → `ANSWER_LOCKED` | `ANSWER_LOCKED` |
| `REVEAL` | 公布正确答案与解析 | `ANSWER_LOCKED` → `ANSWER_REVEALED` | `ANSWER_REVEALED` |
| `FINISH` | 结算本轮并归档线下榜 | 进行态 → `FINISHED` | `ACTIVITY_FINISHED` |

状态守卫：

- `NEXT` 要求处于 `WAITING` 或 `ANSWER_REVEALED`，且存在已预加载题目。
- `LOCK` 仅在 `ANSWERING` 状态执行。
- `REVEAL` 仅在 `ANSWER_LOCKED` 状态执行。
- `FINISH` 执行后，当前轮次其余条目跳过。
- 手动指令先取消对应挂起任务；时间轴条目在触发时因状态守卫空转。

### 2.4 线下调度

- `ScheduledExecutorService` 在线性时间轴上挂载一次性任务。
- 启动时为所有条目与轮次结束标记挂载延时任务。
- 暂停时注销挂起任务并记录暂停锚点；恢复时按剩余时长重排，并平移题目时间字段。
- 主持人可执行暂停、恢复、锁卷、揭晓、下一题、重置与切换轮次。

### 2.5 线下作答与耗时

- 单题作答窗口等于该题 `NEXT` 与 `LOCK` 的时间间隔。
- 单题有效耗时 = 提交时刻 − 本题 `NEXT` 时刻。
- `LOCK` 时未提交玩家按“锁卷时刻 − max(本题 NEXT 时刻, 加入时刻)”补记耗时。
- 加入前已锁卷的题目不产生耗时记录。
- 本轮累计耗时为各题耗时之和。
- 每题正确得 10 分；错误或未提交得 0 分。
- 线下榜排序：得分降序，累计耗时升序，加入时间升序，昵称升序。

## 3. 线上状态机

### 3.1 浏览器状态

```text
IDLE ──开始/继续──> IN_PROGRESS ──全部题目结束──> RESULT_READY
                         │                            │
                         │                            ├──重试──> IN_PROGRESS
                         │                            └──锁定──> LOCKED
                         └──音频/网络错误──> ERROR ──重试──> IDLE
```

| 状态 | 入口 | 出口 |
| :--- | :--- | :--- |
| `IDLE` | 进入线上页、题集加载完成、无本地进度 | 点击开始或继续 → `IN_PROGRESS` |
| `IN_PROGRESS` | 音频开始播放 | 最后一题结束 → `RESULT_READY`；错误 → `ERROR` |
| `RESULT_READY` | 全部题目音频结束 | 预览分加载或重试；重试 → `IN_PROGRESS`；锁定 → `LOCKED` |
| `LOCKED` | 锁定成功或查询到已有锁定记录 | 页面刷新后保持 `LOCKED` |
| `ERROR` | 音频、题集或网络异常 | 重新加载 → `IDLE` |

### 3.2 服务端锁定状态

| 状态 | 说明 |
| :--- | :--- |
| `NOT_LOCKED` | 该 `participantId` 没有线上锁定记录。 |
| `LOCKED` | 该 `participantId` 已有线上锁定记录。 |

服务端不保存 `IN_PROGRESS` 与 `RESULT_READY`。这两个状态由浏览器本地进度与页面状态承载。

### 3.3 线上题集

- 按 `rounds` 顺序、轮次内 `items` 顺序遍历。
- 每个 `LOAD` 记录题目、`audioStartMs`、`audioEndMs`。
- 后续 `NEXT` 记录 `answerStartMs`；后续 `LOCK` 记录 `answerEndMs`。
- 忽略 `REVEAL`、`FINISH` 与轮次边界。
- 按 `questionId` 去重，保留首次出现。
- 线上题集覆盖题库全部题目。
- 服务端生成 `questionSetVersion`，浏览器在本地进度中保存并校验。

### 3.4 线上推进规则

- 浏览器以音频播放位置作为时钟。
- 音频从第一题 `audioStartMs` 连续播放到最后一题 `audioEndMs`。
- 当前题在 `answerStartMs` 上屏，在 `answerEndMs` 锁定。
- 当前题作答窗口开放且已选择答案时，用户可点击「下一题」按顺序提前推进。
- 点击「下一题」将音频定位到下一题 `answerStartMs`，当前题下标前进一步，并立即保存本地进度。
- 当前题未选择答案时「下一题」禁用；最后一题不显示该按钮。
- 未点击「下一题」时，题目按音频位置自动推进；题目之间不暂停、不揭晓、不等待用户确认。
- 手动推进跳过的音频区间不计入有效作答耗时。
- 用户不能任意跳题、回看、暂停或修改已锁定题目。
- 未答题目按 0 分。
- 页面隐藏或关闭时暂停音频并保存位置；用户返回后点击「继续答题」恢复。

### 3.5 线上预览与锁定

```text
全部题目结束
   │
   ├──提交答案──> POST /online/preview ──> 只返回分数
   │
   ├──重试──> 清空本地进度，新 attempt
   │
   └──锁定──> POST /online/lock ──> 服务端重算 ──> 线上榜 + 总榜名次
```

- 预览不写榜，不返回答案。
- 锁定以 `participantId` 为唯一键，每个参与者一条线上记录。
- 同一 `attemptId` 重复锁定幂等；不同 `attemptId` 在已锁定后返回冲突。
- 锁定成功后清空浏览器本地进度。

### 3.6 线上本地进度

本地键：`bgm.online.progress.v1`。

保存字段：

- `eventId`
- `participantId`
- `attemptId`
- `questionSetVersion`
- `status`
- `currentIndex`
- `answers`
- `audioPositionMs`
- `activeDurationMs`
- `questionRemainingMs`
- `startedAt` / `updatedAt`
- `previewScore` / `previewAnsweredCount`

写入时机：开始答题、题目上屏、选择/修改选项、题目锁定、点击「下一题」、最后一题音频结束、页面隐藏、页面关闭前、预览返回。

清理时机：锁定成功、点击重试、题集版本不一致、本地数据损坏。

## 4. 状态同步与刷新

- 线下参与者通过 `GET /api/events/{eventId}/state` 获取服务端快照；广播到达后刷新。
- 线下主持人通过 `GET /api/events/{eventId}/host/state` 获取完整快照；广播到达后刷新。
- 线上参与者通过 `GET /api/events/{eventId}/online/questions` 获取题集，通过本地进度恢复题目位置。
- 线上锁定状态通过 `GET /api/events/{eventId}/online/status` 查询。
- 线上榜与总榜通过对应榜单接口读取。
- 线上锁定成功后广播 `ONLINE_SCORE_LOCKED`，主持人页面刷新线上榜与总榜。

详细流程与验收标准见 [`../product/dual-mode-development-spec.md`](../product/dual-mode-development-spec.md)。
