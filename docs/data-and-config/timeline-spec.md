# 编排文件规范与制作指南

## 1. 文件位置与职责

- **文件路径**：`backend/data/timeline.json`
- **编码格式**：UTF-8 字符集
- **音频文件**：`audioFile` 字段指向的文件与编排文件同目录存放（默认 `backend/data/`）
- **职责**：定义单音频内的全部轮次、动作时间轴与题目内容；后端启动时加载并校验，主持人控制台通过音频流接口播放同一音频文件。

所有时间字段均为音频文件内的绝对毫秒位置，与音频波形刻度一一对应。控制台将音频定位到轮次开始标记起播，服务端以启动接口接收时刻为零点 `T0`，条目实际触发时刻为 `T0 + (atMs − startMs)`。

## 2. 顶层结构

```json
{
  "eventId": "demo",
  "eventName": "旮旯原声之届不到的 BGM",
  "audioFile": "event-demo.mp3",
  "rounds": [ ... ]
}
```

| 字段名 | 数据类型 | 约束条件 | 字段说明 |
| :--- | :--- | :--- | :--- |
| `eventId` | String | 必填 | 活动唯一标识，如 `"demo"` |
| `eventName` | String | 必填 | 活动展示名称 |
| `audioFile` | String | 必填 | 完整音频文件名，相对编排文件所在目录 |
| `rounds` | Array | 至少 1 项 | 轮次列表 |

## 3. 轮次字段规范

| 字段名 | 数据类型 | 约束条件 | 字段说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 全局唯一，必填 | 轮次标识，如 `"round-1"` |
| `name` | String | 必填 | 轮次展示名称，如 `"第一轮"` |
| `startMs` | Long | `startMs < endMs` | 轮次在音频中的起始位置（毫秒） |
| `endMs` | Long | 与其它轮次区间不重叠 | 轮次结束标记位置（毫秒）；到达该位置时间轴自动暂停 |
| `items` | Array | 至少含 `LOAD`、`NEXT`、`FINISH` | 动作条目列表 |

## 4. 动作条目与题目内容规范

### 4.1 条目字段

| 字段名 | 数据类型 | 约束条件 | 字段说明 |
| :--- | :--- | :--- | :--- |
| `atMs` | Long | 轮次内严格递增，且落在 `[startMs, endMs]` | 条目的音频绝对毫秒位置 |
| `action` | String | 枚举值 | `"LOAD"` / `"NEXT"` / `"LOCK"` / `"REVEAL"` / `"FINISH"` |
| `question` | Object | 仅 `LOAD` 条目必填 | 题目内容对象 |

### 4.2 动作语义与标准序列

```text
LOAD → NEXT → LOCK → REVEAL → LOAD → NEXT → LOCK → REVEAL → … → FINISH
```

| 动作 | 语义 |
| :--- | :--- |
| `LOAD` | 预加载题目内容至待上屏槽位，不改变参与者屏幕，不产生广播 |
| `NEXT` | 待上屏内容上屏显示、题目序号 +1、开放作答通道，广播 `QUESTION_STARTED` |
| `LOCK` | 关闭作答通道并补记缺席耗时，不公布答案，广播 `ANSWER_LOCKED` |
| `REVEAL` | 公布正确答案与解析，广播 `ANSWER_REVEALED` |
| `FINISH` | 结算本轮成绩并归档榜单，广播 `ACTIVITY_FINISHED` |

### 4.3 题目内容字段

| 字段名 | 数据类型 | 约束条件 | 字段说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 轮次内唯一，必填 | 题目编号，如 `"q-01"` |
| `prompt` | String | 必填 | 题干文本 |
| `type` | String | 枚举，必填 | 题型：`"ATMOSPHERE"`（氛围题）或 `"WORK"`（作品题） |
| `difficulty` | String | 枚举，必填 | 难度梯度：`"EASY"`（简单）、`"MEDIUM"`（中等）、`"HARD"`（困难） |
| `options` | List<String> | 长度为 4 | 4 个候选选项文字 |
| `correctOption` | Integer | 0 至 3 | 正确选项的数组索引（从 0 开始计数） |
| `workName` | String | 必填 | 音乐出处的游戏原作名称 |
| `explanation` | String | 必填 | 答案揭晓阶段展示的解析与作品说明 |
| `audioStartMs` | Long | `audioStartMs < audioEndMs` | 本题音频段起点，用于参与者端剩余时间显示 |
| `audioEndMs` | Long | 建议落在所属轮次区间内 | 本题音频段终点，用于参与者端剩余时间显示 |

## 5. 完整示例

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
      "endMs": 180000,
      "items": [
        {
          "atMs": 10000,
          "action": "LOAD",
          "question": {
            "id": "q-01",
            "prompt": "这首治愈系钢琴曲出自哪部作品？",
            "type": "WORK",
            "difficulty": "EASY",
            "options": ["《CLANNAD》", "《白色相簿2》", "《命运石之门》", "《千恋＊万花》"],
            "correctOption": 0,
            "workName": "CLANNAD",
            "explanation": "明亮舒缓的钢琴主题曲，用于日常与家庭情节。",
            "audioStartMs": 0,
            "audioEndMs": 30000
          }
        },
        { "atMs": 15000, "action": "NEXT" },
        { "atMs": 30000, "action": "LOCK" },
        { "atMs": 31000, "action": "REVEAL" },
        {
          "atMs": 33000,
          "action": "LOAD",
          "question": {
            "id": "q-02",
            "prompt": "这段旋律更适合哪一种场景？",
            "type": "ATMOSPHERE",
            "difficulty": "MEDIUM",
            "options": ["放学后的海边散步", "最终决战前夜", "密室推理现场", "热闹的祭典舞台"],
            "correctOption": 1,
            "workName": "白色相簿2",
            "explanation": "紧张上行的和声与强烈鼓点，贴合决战与冲突场景。",
            "audioStartMs": 30000,
            "audioEndMs": 60000
          }
        },
        { "atMs": 36000, "action": "NEXT" },
        { "atMs": 60000, "action": "LOCK" },
        { "atMs": 61000, "action": "REVEAL" },
        { "atMs": 176000, "action": "FINISH" }
      ]
    },
    {
      "id": "round-2",
      "name": "第二轮",
      "startMs": 180000,
      "endMs": 360000,
      "items": [
        "...与第一轮同构的条目序列..."
      ]
    }
  ]
}
```

## 6. 校验规则

后端启动加载时逐项校验，任一不满足即拒绝启动并输出问题条目位置：

1. **轮次区间**：`startMs < endMs`；轮次 `id` 全局唯一；轮次区间互不重叠。
2. **条目时间**：同一轮次内 `atMs` 严格递增，且落在 `[startMs, endMs]` 区间内。
3. **预加载配对**：每个 `NEXT` 触发时要求存在尚未被消费的 `LOAD`；每轮 `LOAD` 数量与 `NEXT` 数量一致。
4. **结算位置**：每轮至少存在一条 `FINISH` 条目，且 `atMs ≤ endMs`。
5. **题目合法性**：轮次内题目 `id` 唯一；`options` 数量为 4；`correctOption` 取值范围 0 至 3；`audioStartMs < audioEndMs`。
6. **类型枚举**：`action`、`type`、`difficulty` 均为定义内的枚举值。

## 7. 编排制作指南

1. **音频制作**：将所有轮次所需的音乐段落串联为一个完整音频文件，各轮次为其中的连续区段；所有时间标记以该文件试听结果为准。
2. **窗口留白**：`NEXT` 与 `LOCK` 之间为作答窗口，按现场节奏留足时长；`LOAD` 可安排在题目音乐节点前的过门处提前预加载。
3. **揭晓节奏**：`REVEAL` 的 `atMs` 晚于 `LOCK`；下一题 `LOAD` 的 `atMs` 晚于 `REVEAL`，参与者揭晓画面持续到下一题 `NEXT` 上屏。
4. **结算位置**：`FINISH` 的 `atMs` 晚于最后一题 `REVEAL`，且不晚于 `endMs`；到达结束标记仅暂停，不产生结算。
5. **轮次复用**：轮次可被重复选择播放以接待不同批次参与者；同一活动调整编排只需修改本文件并重启服务。
6. **现场排练**：使用实际音频与编排文件完整彩排一次，核对每个动作触发时刻与音乐节点的对齐效果。