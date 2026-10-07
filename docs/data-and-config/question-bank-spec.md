# 题库配置规范与扩展指南

## 1. 题库文件位置与格式

题库以静态 JSON 数组形式存储于以下路径：
- 文件路径：`backend/data/questions.json`
- 编码格式：UTF-8 字符集

## 2. 字段规范定义

| 字段名 | 数据类型 | 约束条件 | 字段说明与取值示例 |
| :--- | :--- | :--- | :--- |
| `id` | String | 唯一键，必填 | 题目全局唯一编号，如 `"q-01"` |
| `audioCode` | String | 必填 | 放音代号，如 `"TRACK-01"`，与现场音箱播放列表严格对应 |
| `prompt` | String | 必填 | 题干文本，如 `"这段旋律更适合哪一种场景？"` |
| `type` | String | 枚举，必填 | 题型：`"ATMOSPHERE"`（氛围题）或 `"WORK"`（作品题） |
| `difficulty` | String | 枚举，必填 | 难度梯度：`"EASY"`（简单）、`"MEDIUM"`（中等）、`"HARD"`（困难） |
| `options` | List<String> | 长度为 4 | 4 个候选选项文字 |
| `correctOption`| Integer | 0 至 3 | 正确选项的数组索引（从 0 开始计数） |
| `workName` | String | 可选 | 音乐出处的游戏原作名称 |
| `explanation` | String | 必填 | 答案揭晓阶段展示的解析与作品说明 |

## 3. 标准 JSON 条目范例

```json
[
  {
    "id": "q-01",
    "audioCode": "TRACK-01",
    "prompt": "这段旋律更适合哪一种场景？",
    "type": "ATMOSPHERE",
    "difficulty": "EASY",
    "options": [
      "放学后的海边散步",
      "最终决战前夜",
      "密室推理现场",
      "热闹的祭典舞台"
    ],
    "correctOption": 0,
    "workName": "秋之回忆",
    "explanation": "明亮、舒缓的吉他与钢琴和声，用于轻松日常与海边漫步情节。"
  },
  {
    "id": "q-02",
    "audioCode": "TRACK-02",
    "prompt": "这首经典的插曲出自哪一部视觉小说作品？",
    "type": "WORK",
    "difficulty": "MEDIUM",
    "options": [
      "《CLANNAD》",
      "《白色相簿2》",
      "《命运石之门》",
      "《千恋＊万花》"
    ],
    "correctOption": 1,
    "workName": "白色相簿2",
    "explanation": "本曲为作品冬季剧情的关键配乐，标志性的萨克斯音色极具辨识度。"
  }
]
```

## 4. 扩展题库注意事项

1. **放音编号对齐**：现场音箱播放列表中音频文件名需与 `audioCode` 严格一致（例如准备名为 `TRACK-01.mp3`、`TRACK-02.mp3` 的音频文件），确保放音人员看到控制台编号即可秒级切歌。
2. **选项长度均衡**：建议 4 个选项字数尽量相近，避免因某项字数明显偏长或偏短诱导盲猜。
3. **难度比例控制**：扩充整体题库至 30~50 首时，建议维持 3 (EASY) : 2 (MEDIUM) : 1 (HARD) 的宏观比例。
