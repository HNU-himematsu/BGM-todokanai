# 编排文件规范与制作指南

## 1. 文件与职责

| 项目 | 约定 |
| :--- | :--- |
| 编排文件 | `backend/data/timeline.json` |
| 题库文件 | `backend/data/questions.json` |
| 音频文件 | 与编排文件同目录，文件名由 `audioFile` 指定 |
| 字符集 | UTF-8 |
| 时间单位 | 毫秒 |
| 时间基准 | 从完整音频开头开始的绝对位置 |

`timeline.json` 定义完整音频内的线下轮次、题目引用、题目音频片段、作答窗口与状态动作。线上题集由同一编排文件生成，不维护第二份题目顺序文件。

素材未就绪时，后端仍可加载并校验结构；音频文件不可读时，音频接口与线上开始答题返回明确错误。题库与编排通过后，接口才可提供完整答题数据。

## 2. 顶层结构

```json
{
  "eventId": "demo",
  "eventName": "旮旯原声之届不到的 BGM",
  "audioFile": "event-demo.mp3",
  "rounds": []
}
```

| 字段 | 类型 | 必填 | 约束 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `eventId` | String | 是 | 非空，与访问路径一致 | 活动标识。 |
| `eventName` | String | 是 | 非空 | 活动展示名称。 |
| `audioFile` | String | 是 | 同目录文件名 | 完整音频文件名。 |
| `rounds` | Array | 是 | 至少 1 项 | 线下轮次列表。 |

`eventId` 不得包含路径分隔符。`audioFile` 使用相对文件名，不包含目录穿越片段。

## 3. 轮次结构

```json
{
  "id": "round-1",
  "name": "第一轮",
  "startMs": 0,
  "endMs": 30000,
  "items": []
}
```

| 字段 | 类型 | 必填 | 约束 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `id` | String | 是 | 活动内唯一 | 轮次标识。 |
| `name` | String | 是 | 非空 | 轮次展示名称。 |
| `startMs` | Long | 是 | `0 <= startMs < endMs` | 本轮音频起点。 |
| `endMs` | Long | 是 | 大于 `startMs` | 本轮音频终点。 |
| `items` | Array | 是 | 非空 | 动作条目列表。 |

轮次区间不得重叠。线下主持人开始本轮时，音频定位到 `startMs`，服务端以启动时刻为零点 `T0`，条目触发时刻为：

```text
T0 + (item.atMs - round.startMs)
```

到达 `endMs` 时，时间轴与音频暂停。`endMs` 不触发结算；结算只由 `FINISH` 触发。

## 4. 动作条目

### 4.1 字段

| 字段 | 类型 | 必填范围 | 约束 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `atMs` | Long | 全部条目 | 轮次内严格递增，位于 `[startMs, endMs]` | 动作的音频绝对位置。 |
| `action` | String | 全部条目 | 枚举 | `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH`。 |
| `questionId` | String | `LOAD` | 题库中存在的题目编号 | 题目引用。 |
| `audioStartMs` | Long | `LOAD` | 大于等于轮次 `startMs` | 本题音频片段起点。 |
| `audioEndMs` | Long | `LOAD` | 大于 `audioStartMs`，小于等于轮次 `endMs` | 本题音频片段终点。 |

非 `LOAD` 条目忽略题目与音频片段字段。编排文件不接受未定义的动作枚举。

### 4.2 动作语义

| 动作 | 语义 | 广播 |
| :--- | :--- | :--- |
| `LOAD` | 按 `questionId` 载入题目到待上屏槽位，登记本题音频片段；参与者画面不变。 | 无 |
| `NEXT` | 消费待上屏题目，题目上屏、开放作答，记录线上 `answerStartMs`。 | `QUESTION_STARTED` |
| `LOCK` | 关闭当前题作答，记录线上 `answerEndMs`，为未提交线下玩家补记耗时。 | `ANSWER_LOCKED` |
| `REVEAL` | 公开正确选项、作品名与解析。 | `ANSWER_REVEALED` |
| `FINISH` | 结算本轮、写入线下快照与文本总结。 | `ACTIVITY_FINISHED` |

### 4.3 标准序列

每一题按以下顺序声明：

```text
LOAD → NEXT → LOCK → REVEAL
```

一轮的完整序列：

```text
LOAD → NEXT → LOCK → REVEAL
LOAD → NEXT → LOCK → REVEAL
...
FINISH
```

约束：

- 每个 `LOAD` 后存在尚未消费的 `NEXT`。
- 每个 `NEXT` 消费其前面最近的未消费 `LOAD`。
- 每个 `NEXT` 后、下一个 `LOAD` 前存在当前题的 `LOCK`。
- 每个 `LOCK` 后、下一个 `LOAD` 前存在当前题的 `REVEAL`。
- `REVEAL` 晚于 `LOCK`。
- 下一个题目的 `LOAD` 晚于上一题的 `REVEAL`。
- `FINISH` 位于本轮最后一个 `REVEAL` 之后。
- 同一轮次内不得重复引用同一 `questionId`。

## 5. 题库文件

`questions.json` 为 JSON 数组，每项是一道题：

```json
[
  {
    "id": "q-01",
    "prompt": "【中等·作品题】这首曲子出自哪部作品？",
    "type": "WORK",
    "difficulty": "MEDIUM",
    "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
    "correctOption": 1,
    "workName": "《白色相簿2》",
    "explanation": "该旋律对应作品《白色相簿2》。",
    "audioCode": "TRACK-01"
  }
]
```

| 字段 | 类型 | 约束 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 全题库唯一，非空 | 题目编号。 |
| `prompt` | String | 非空 | 题干。 |
| `type` | String | `WORK` / `ATMOSPHERE` | 作品题或氛围题。 |
| `difficulty` | String | `EASY` / `MEDIUM` / `HARD` | 难度。 |
| `options` | String[] | 长度 4，每项非空 | 四个选项。 |
| `correctOption` | Integer | 0 至 3 | 正确选项下标。 |
| `workName` | String | 非空 | 作品名；线下揭晓与主持人可见，线上不返回。 |
| `explanation` | String | 非空 | 解析；线下揭晓可见，线上不返回。 |
| `audioCode` | String | 非空 | 素材核对编号。 |

`audioCode` 用于运营核对音频素材。题目与音频的播放位置关系由 `timeline.json` 的 `LOAD` 条目定义。

## 6. 线上题集生成

### 6.1 生成算法

服务端启动时执行：

1. 按 `rounds` 数组顺序遍历。
2. 在每轮 `items` 中按数组顺序遍历。
3. 遇到 `LOAD` 时创建候选条目，记录 `questionId`、`audioStartMs`、`audioEndMs`。
4. 遇到该题后续 `NEXT` 时记录 `answerStartMs = atMs`。
5. 遇到该题后续 `LOCK` 时记录 `answerEndMs = atMs`。
6. 忽略 `REVEAL`、`FINISH` 和轮次边界。
7. 按 `questionId` 去重，保留首次出现的全部时间字段。
8. 按首次出现顺序为题目分配 `index`。

候选条目在三个时间字段齐全前不得进入线上题集。字段缺失属于编排错误。

### 6.2 线上题目投影

```json
{
  "index": 0,
  "id": "q-01",
  "prompt": "【中等·作品题】这首曲子出自哪部作品？",
  "type": "WORK",
  "difficulty": "MEDIUM",
  "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
  "audioStartMs": 0,
  "audioEndMs": 15000,
  "answerStartMs": 1000,
  "answerEndMs": 12000
}
```

线上投影排除：

- `correctOption`
- `workName`
- `explanation`
- 逐题得分与榜单信息

### 6.3 题集覆盖

- 线上题集包含 `questions.json` 的全部题目。
- 每个题库 `id` 至少被一个轮次中的 `LOAD` 引用。
- 跨轮次重复引用的题目在线上题集中只出现一次。
- 线上题集顺序只由轮次与条目顺序决定，不按难度、题型或昵称排序。

### 6.4 题集版本

版本输入按题集顺序构造：

```text
questionId,audioStartMs,audioEndMs,answerStartMs,answerEndMs
```

多题之间使用单个换行 `\n` 连接，末尾不附加换行。示例：

```text
q-01,0,15000,1000,12000
q-02,15000,30000,15000,27000
q-03,30000,45000,31000,42000
q-04,45000,60000,45000,57000
```

对该 UTF-8 字节串计算 SHA-256，输出 64 位小写十六进制字符串，作为 `questionSetVersion`。

浏览器本地进度保存该版本。版本不一致时丢弃进度，要求从第一题重新开始。

## 7. 音频与时间约束

### 7.1 完整音频

- 线上与线下共用同一份完整音频。
- 音频包含全部题目的播放内容，题目片段之间不存在空白段。
- 第一题 `audioStartMs` 为 0。
- 最后一题 `audioEndMs` 作为线上播放终点，与音频可播放总时长的差值不超过 100 毫秒。
- 相邻题目满足 `questions[i].audioEndMs == questions[i + 1].audioStartMs`。
- 线上从第一题 `audioStartMs` 连续播放到最后一题 `audioEndMs`。
- 线上参与者点击「下一题」时，播放位置定位到下一题 `answerStartMs`；该操作仅按题集顺序前进一题。

### 7.2 作答窗口

每道题满足：

```text
audioStartMs <= answerStartMs < answerEndMs <= audioEndMs
```

- `answerStartMs` 对应 `NEXT`。
- `answerEndMs` 对应 `LOCK`。
- 题目音频尾段可以晚于 `answerEndMs` 结束，用于线下揭晓或线上题间过渡。
- 线上在 `answerStartMs` 上屏，在 `answerEndMs` 锁定。
- 线上手动进入下一题使用下一题 `answerStartMs` 作为音频定位点；素材无需为手动推进增加空白段。

### 7.3 轮次与音频

- 每个题目的音频片段必须位于所属轮次区间内。
- 轮次区间按音频顺序排列且互不重叠。
- 轮次之间可以首尾相接。
- 落在轮次区间之外且未被任何题目引用的音频内容不接受。
- 主持人播放本轮时从 `round.startMs` 开始，到 `round.endMs` 停止。

### 7.4 音频文件

- 文件编码需被目标手机浏览器与主持人浏览器共同支持。
- 后端音频接口支持 HTTP Range，移动端可分段读取。
- MIME 类型根据扩展名生成。
- 文件缺失、权限不可读或内容无法解码时，线上页面显示素材错误，主持人显示音频播放错误。
- 音频实际总时长与最后一题 `audioEndMs` 的差值不超过 100 毫秒；该项由素材制作与上线前检查确认，后端结构校验不解析音频波形。

## 8. 校验规则

后端启动时按以下顺序校验，任一失败即输出具体轮次、条目下标与原因，并拒绝提供可运行活动数据：

### 8.1 顶层

1. `eventId`、`eventName`、`audioFile` 非空。
2. `eventId` 与访问路径一致。
3. `rounds` 非空。
4. `audioFile` 为同目录相对文件名。

### 8.2 轮次

5. 轮次 `id` 唯一。
6. `startMs >= 0` 且 `startMs < endMs`。
7. 任意两个轮次区间不重叠。
8. 每轮 `items` 非空。
9. 轮次按 `startMs` 升序排列。

### 8.3 条目

10. `atMs` 位于 `[startMs, endMs]`。
11. 同一轮次内 `atMs` 严格递增。
12. `action` 属于定义枚举。
13. `LOAD` 的 `questionId` 存在于题库。
14. 同一轮次内 `questionId` 不重复。
15. `LOAD` 的 `audioStartMs < audioEndMs`。
16. `LOAD` 的音频片段位于轮次区间内。
17. `LOAD` 后存在未消费的 `NEXT`。
18. `NEXT` 前存在未消费的 `LOAD`。
19. 每个 `NEXT` 后存在 `LOCK`。
20. 每个 `LOCK` 后存在 `REVEAL`。
21. 下一题 `LOAD` 晚于上一题 `REVEAL`。
22. 每轮存在 `FINISH`。
23. `FINISH` 晚于本轮最后一个 `REVEAL`。

### 8.4 题库

24. 题目 `id` 全题库唯一。
25. 题目 `id` 不包含逗号、换行或路径分隔符。
26. `prompt`、`workName`、`explanation`、`audioCode` 非空。
27. `options` 长度为 4，每项非空。
28. `correctOption` 为 0 至 3。
29. `type`、`difficulty` 属于定义枚举。
30. 题库全部 `id` 至少被一个 `LOAD` 引用。

### 8.5 线上题集

31. 每个线上题目的 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。
32. 第一道线上题目的 `audioStartMs` 为 0。
33. 相邻题目的 `audioEndMs` 与下一题 `audioStartMs` 相等。
34. 线上题集不包含重复 `questionId`。
35. 线上题集覆盖题库全部题目。
36. 线上题集至少包含 1 道题。

### 8.6 时间与素材

37. 所有时间为非负整数。
38. `FINISH` 与 `endMs` 不早于最后一个 `REVEAL`。
39. 音频文件路径位于编排文件同目录。
校验失败时不生成线上题集，不接受加入与答题请求。主持人界面显示素材不可用状态。

## 9. 完整示例

以下示例包含两轮、四道题。每题音频片段首尾相接，第一段从 0 开始，最后一段到 60000 毫秒结束。

```json
{
  "eventId": "demo",
  "eventName": "旮旯原声之届不到的 BGM",
  "audioFile": "event-demo.mp3",
  "rounds": [
    {
      "id": "round-1",
      "name": "第一轮",
      "startMs": 0,
      "endMs": 30000,
      "items": [
        {
          "atMs": 0,
          "action": "LOAD",
          "questionId": "q-01",
          "audioStartMs": 0,
          "audioEndMs": 15000
        },
        {
          "atMs": 1000,
          "action": "NEXT"
        },
        {
          "atMs": 12000,
          "action": "LOCK"
        },
        {
          "atMs": 13000,
          "action": "REVEAL"
        },
        {
          "atMs": 14000,
          "action": "LOAD",
          "questionId": "q-02",
          "audioStartMs": 15000,
          "audioEndMs": 30000
        },
        {
          "atMs": 15000,
          "action": "NEXT"
        },
        {
          "atMs": 27000,
          "action": "LOCK"
        },
        {
          "atMs": 28000,
          "action": "REVEAL"
        },
        {
          "atMs": 29000,
          "action": "FINISH"
        }
      ]
    },
    {
      "id": "round-2",
      "name": "第二轮",
      "startMs": 30000,
      "endMs": 60000,
      "items": [
        {
          "atMs": 30000,
          "action": "LOAD",
          "questionId": "q-03",
          "audioStartMs": 30000,
          "audioEndMs": 45000
        },
        {
          "atMs": 31000,
          "action": "NEXT"
        },
        {
          "atMs": 42000,
          "action": "LOCK"
        },
        {
          "atMs": 43000,
          "action": "REVEAL"
        },
        {
          "atMs": 44000,
          "action": "LOAD",
          "questionId": "q-04",
          "audioStartMs": 45000,
          "audioEndMs": 60000
        },
        {
          "atMs": 45000,
          "action": "NEXT"
        },
        {
          "atMs": 57000,
          "action": "LOCK"
        },
        {
          "atMs": 58000,
          "action": "REVEAL"
        },
        {
          "atMs": 59000,
          "action": "FINISH"
        }
      ]
    }
  ]
}
```

生成结果：

| index | questionId | audioStartMs | audioEndMs | answerStartMs | answerEndMs |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 0 | `q-01` | 0 | 15000 | 1000 | 12000 |
| 1 | `q-02` | 15000 | 30000 | 15000 | 27000 |
| 2 | `q-03` | 30000 | 45000 | 31000 | 42000 |
| 3 | `q-04` | 45000 | 60000 | 45000 | 57000 |

## 10. 制作流程

1. 汇总题库并分配全题库唯一 `id`。
2. 把所有题目音频按答题顺序拼成完整音频。
3. 在音频编辑器中记录每题片段起点与终点。
4. 为每题设置 `NEXT`、`LOCK`、`REVEAL` 时刻。
5. 按现场节奏把题目划分到轮次；轮次边界落在音频位置。
6. 为每轮设置 `FINISH`，并核对其早于 `endMs`。
7. 检查相邻题目片段无缝衔接。
8. 检查全部题库题目均被引用。
9. 运行后端校验，修正全部错误。
10. 使用真实手机与主持人音频设备完成线上、线下彩排。

## 11. 上线前检查

- 完整音频可播放，时长与最后 `audioEndMs` 一致。
- 无题目片段缺失、重叠或空白。
- 线上按线上题集顺序播放，题集覆盖题库全部题目。
- 所有题目都有 `LOAD`、`NEXT`、`LOCK` 与 `REVEAL`。
- 每轮有且只有一个结算用 `FINISH`。
- 主持人开始播放的位置等于当前轮次 `startMs`。
- 手机端能正常加载音频并自动推进。
- 页面隐藏后恢复位置正确。
- 题集版本在重新部署后发生变化时，线上旧进度按规则失效。
