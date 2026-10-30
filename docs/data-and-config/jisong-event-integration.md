# 姬松活动素材接入规范

## 1. 文档用途

本文规定「姬松活动」素材接入本仓库系统的完整做法：素材交付形态、音频成品规格、`timeline.json` 与 `questions.json` 的生成规则、启动自检、联调流程与回滚方式。按本文执行后，活动以 `eventId=demo` 运行线下四轮与线上全题库两种模式。

读者对象为素材制作方与部署执行者。数据契约的完整定义见 [`timeline-spec.md`](timeline-spec.md)，接口字段见 [`../backend/api-spec.md`](../backend/api-spec.md)，本文只覆盖本次活动的具体数值与操作顺序。

## 2. 素材现状

| 文件 | 位置 | 实测内容 |
| :--- | :--- | :--- |
| `编排.docx` | `姬松活动/` | 4 轮 × 10 题 = 40 题；每题含音频编号、题目作者、原曲截取区间、题目片段起止、`LOAD/NEXT/LOCK/REVEAL`、题干、题型、难度、四个选项、正确答案、作品名、解析 |
| `总音频.mp3` | `姬松活动/` | 2593.149 秒，44.1 kHz 立体声，约 130 kbps VBR，42.3 MB |
| `debug.log` | `姬松活动/` | 浏览器网络监听日志，与本活动无关 |

编排文件的时间基准是轮内时间，格式为 `HH:MM:SS.mmm`。四轮各自从 `00:00:00.000` 起算。

`总音频.mp3` 的四轮首尾相接，每题片段之后接 10 秒或 40 秒静音；Q-24 由四段拼合，依次为 A 段 24 秒、5 秒静音、B 段 20 秒、约 7.6 秒静音、C 段 27 秒、5 秒静音、D 段 20 秒、约 12 秒尾静音。四轮分轨文件不在交付目录中，单独重导某一轮需向素材方索取。

## 3. 目标文件布局

```text
backend/data/
├── timeline.json
├── questions.json
├── event-demo.mp3
├── leaderboards.json
├── online_leaderboard.json
└── leaderboard_summary.txt
```

- `eventId` 固定为 `demo`，前端路由、线下二维码与主持人入口沿用现有地址。
- 音频路径相对 `timeline.json` 所在目录解析，音频文件与编排文件位于同一目录。
- `leaderboards.json`、`online_leaderboard.json`、`leaderboard_summary.txt` 由服务在首次写入时生成。

## 4. 音频成品

### 4.1 规格

| 项目 | 要求 |
| :--- | :--- |
| 时长 | 2593.000 秒（与末题 `audioEndMs` 的差值不超过 100 毫秒） |
| 采样率 | 44100 Hz |
| 声道 | 2 |
| 编码 | MP3 |
| 体积 | 约 42 MB |
| 文件名 | `event-demo.mp3`，单段文件名，不含目录与空格 |

### 4.2 尾部裁切

`总音频.mp3` 实测 2593.149 秒，比编排的末题 `audioEndMs`（2593000）多 149 毫秒，超出 100 毫秒容差。文件尾部为静音，按流复制裁切即可，不做重编码：

```powershell
ffmpeg -y -i "姬松活动\总音频.mp3" -t 2593.000 -c copy "backend\data\event-demo.mp3"
```

裁切点落在 MP3 帧边界，成品实测 2593.032 秒，与编排相差 32 毫秒。成品尾部仍保留约 40 秒静音。

### 4.3 成品校验

```powershell
ffprobe -v error -show_entries format=duration,size,bit_rate -show_entries stream=sample_rate,channels -of default=noprint_wrappers=1 "backend\data\event-demo.mp3"
```

检查项：

- `duration` 落在 2592.900～2593.100 之间。
- `sample_rate=44100`，`channels=2`。
- 文件可被浏览器直接解码，拖到最后 5 秒为静音。

### 4.4 素材侧的约束

- 音频为单文件；四轮共用同一份音频，轮次以绝对时间定位。
- 题目片段之间以静音填充，静音时长由 `LOCK`、`REVEAL` 与下一题 `NEXT` 的相对位置体现。
- HTTP Range 由后端接口实现，素材侧不需要切开文件。

## 5. 编排文件

### 5.1 顶层字段

```json
{
  "eventId": "demo",
  "eventName": "旮旯原声之届不到的 BGM",
  "audioFile": "event-demo.mp3",
  "rounds": []
}
```

### 5.2 轮次

| id | name | startMs | endMs |
| :--- | :--- | ---: | ---: |
| `round-1` | 第一轮 | 0 | 501000 |
| `round-2` | 第二轮 | 501000 | 1234000 |
| `round-3` | 第三轮 | 1234000 | 1905000 |
| `round-4` | 第四轮 | 1905000 | 2593000 |

轮次按 `startMs` 升序排列，区间不重叠，相邻轮次首尾相接：后一轮的 `startMs` 等于前一轮的 `endMs`。

编排以「轮次开始：00:00.000」与「轮次结束：08:21.000」标注每轮时长，四轮时长依次为 501000、733000、671000、688000 毫秒；第 n 轮的 `startMs` 为前 n-1 轮时长之和，`endMs` 为前 n 轮时长之和。

### 5.3 条目

每道题包含四条动作，顺序固定为 `LOAD → NEXT → LOCK → REVEAL`；每轮末尾追加一条 `FINISH`。

| 字段 | 适用动作 | 取值 |
| :--- | :--- | :--- |
| `atMs` | 全部 | 编排文件的轮内时刻加轮次偏移 |
| `action` | 全部 | `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH` |
| `questionId` | `LOAD` | `q-01`～`q-40` |
| `audioStartMs` | `LOAD` | 该题「题目音频片段开始」加轮次偏移 |
| `audioEndMs` | `LOAD` | 下一题「题目音频片段开始」加轮次偏移；轮末题取本轮 `endMs` |

`FINISH` 的 `atMs` 等于本轮 `endMs`。

示例（第 1 题与第 2 题）：

```json
[
  { "atMs": 0, "action": "LOAD", "questionId": "q-01", "audioStartMs": 0, "audioEndMs": 46000 },
  { "atMs": 1, "action": "NEXT" },
  { "atMs": 43000, "action": "LOCK" },
  { "atMs": 44000, "action": "REVEAL" },
  { "atMs": 45000, "action": "LOAD", "questionId": "q-02", "audioStartMs": 46000, "audioEndMs": 105000 },
  { "atMs": 46000, "action": "NEXT" }
]
```

### 5.4 生成规则

```text
轮次偏移 = 该轮 startMs
动作 atMs = 轮内时刻 + 轮次偏移
audioStartMs = 题目音频片段开始 + 轮次偏移
audioEndMs = 下一题题目音频片段开始 + 轮次偏移（轮末题取轮次 endMs）
```

轮首题的 `NEXT` 比片段起点晚 1 毫秒，`audioStartMs` 取片段起点。全部 40 题的绝对时刻见 5.6。

### 5.5 数据必须满足的不变量

1. 同一轮内全部条目的 `atMs` 严格递增。
2. 每题满足 `LOAD < NEXT < LOCK < REVEAL`。
3. 下一题的 `LOAD` 晚于上一题的 `REVEAL`。
4. 每题满足 `audioStartMs <= NEXT < LOCK <= audioEndMs`。
5. 相邻题满足 `audioEndMs` 等于下一题的 `audioStartMs`。
6. 第一题的 `audioStartMs` 为 0。
7. 末题的 `audioEndMs` 为 2593000。
8. 每轮 `FINISH` 等于 `endMs`，且晚于末题 `REVEAL`。
9. 每轮内不重复引用同一 `questionId`；全题库 40 题各被引用一次。
10. `audioStartMs` 不小于轮次 `startMs`，`audioEndMs` 不大于轮次 `endMs`。

### 5.6 40 题绝对时刻（毫秒）

| 题号 | id | 轮次 | audioStart | NEXT | LOCK | REVEAL | audioEnd |
| ---: | :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| 1 | `q-01` | 第一轮 | 0 | 1 | 43000 | 44000 | 46000 |
| 2 | `q-02` | 第一轮 | 46000 | 46000 | 102000 | 103000 | 105000 |
| 3 | `q-03` | 第一轮 | 105000 | 105000 | 155000 | 156000 | 158000 |
| 4 | `q-04` | 第一轮 | 158000 | 158000 | 190000 | 191000 | 193000 |
| 5 | `q-05` | 第一轮 | 193000 | 193000 | 233000 | 234000 | 236000 |
| 6 | `q-06` | 第一轮 | 236000 | 236000 | 293000 | 294000 | 296000 |
| 7 | `q-07` | 第一轮 | 296000 | 296000 | 333000 | 334000 | 336000 |
| 8 | `q-08` | 第一轮 | 336000 | 336000 | 398000 | 399000 | 401000 |
| 9 | `q-09` | 第一轮 | 401000 | 401000 | 440000 | 441000 | 443000 |
| 10 | `q-10` | 第一轮 | 443000 | 443000 | 498000 | 499000 | 501000 |
| 11 | `q-11` | 第二轮 | 501000 | 501001 | 542000 | 543000 | 545000 |
| 12 | `q-12` | 第二轮 | 545000 | 545000 | 584000 | 585000 | 587000 |
| 13 | `q-13` | 第二轮 | 587000 | 587000 | 647000 | 648000 | 650000 |
| 14 | `q-14` | 第二轮 | 650000 | 650000 | 747000 | 748000 | 750000 |
| 15 | `q-15` | 第二轮 | 750000 | 750000 | 848000 | 849000 | 851000 |
| 16 | `q-16` | 第二轮 | 851000 | 851000 | 917000 | 918000 | 920000 |
| 17 | `q-17` | 第二轮 | 920000 | 920000 | 1008000 | 1009000 | 1011000 |
| 18 | `q-18` | 第二轮 | 1011000 | 1011000 | 1108000 | 1109000 | 1111000 |
| 19 | `q-19` | 第二轮 | 1111000 | 1111000 | 1158000 | 1159000 | 1161000 |
| 20 | `q-20` | 第二轮 | 1161000 | 1161000 | 1231000 | 1232000 | 1234000 |
| 21 | `q-21` | 第三轮 | 1234000 | 1234001 | 1266000 | 1267000 | 1269000 |
| 22 | `q-22` | 第三轮 | 1269000 | 1269000 | 1321000 | 1322000 | 1324000 |
| 23 | `q-23` | 第三轮 | 1324000 | 1324000 | 1356000 | 1357000 | 1359000 |
| 24 | `q-24` | 第三轮 | 1359000 | 1359000 | 1477000 | 1478000 | 1480000 |
| 25 | `q-25` | 第三轮 | 1480000 | 1480000 | 1557000 | 1558000 | 1560000 |
| 26 | `q-26` | 第三轮 | 1560000 | 1560000 | 1632000 | 1633000 | 1635000 |
| 27 | `q-27` | 第三轮 | 1635000 | 1635000 | 1707000 | 1708000 | 1710000 |
| 28 | `q-28` | 第三轮 | 1710000 | 1710000 | 1782000 | 1783000 | 1785000 |
| 29 | `q-29` | 第三轮 | 1785000 | 1785000 | 1827000 | 1828000 | 1830000 |
| 30 | `q-30` | 第三轮 | 1830000 | 1830000 | 1902000 | 1903000 | 1905000 |
| 31 | `q-31` | 第四轮 | 1905000 | 1905001 | 1977000 | 1978000 | 1980000 |
| 32 | `q-32` | 第四轮 | 1980000 | 1980000 | 2017000 | 2018000 | 2020000 |
| 33 | `q-33` | 第四轮 | 2020000 | 2020000 | 2091000 | 2092000 | 2094000 |
| 34 | `q-34` | 第四轮 | 2094000 | 2094000 | 2166000 | 2167000 | 2169000 |
| 35 | `q-35` | 第四轮 | 2169000 | 2169000 | 2243000 | 2244000 | 2246000 |
| 36 | `q-36` | 第四轮 | 2246000 | 2246000 | 2323000 | 2324000 | 2326000 |
| 37 | `q-37` | 第四轮 | 2326000 | 2326000 | 2398000 | 2399000 | 2401000 |
| 38 | `q-38` | 第四轮 | 2401000 | 2401000 | 2467000 | 2468000 | 2470000 |
| 39 | `q-39` | 第四轮 | 2470000 | 2470000 | 2515000 | 2516000 | 2518000 |
| 40 | `q-40` | 第四轮 | 2518000 | 2518000 | 2590000 | 2591000 | 2593000 |

## 6. 题库文件

### 6.1 字段映射

| 编排字段 | 题库字段 | 规则 |
| :--- | :--- | :--- |
| 题号 | `id` | `q-` 加两位题号，例如 `q-01` |
| 音频编号 | `audioCode` | 原样，`TRACK-01`～`TRACK-40` |
| 题干 | `prompt` | 原样；Q-24 在原句末尾追加「音频按选项 A 至 D 顺序播放。」 |
| 题型 | `type` | 原样，`WORK` 或 `ATMOSPHERE` |
| 难度 | `difficulty` | 原样，`EASY`、`MEDIUM`、`HARD` |
| 选项A～选项D | `options` | 去掉「选项X：」前缀，按 A、B、C、D 顺序构成四项 |
| 正确答案 | `correctOption` | A→0、B→1、C→2、D→3 |
| 作品名 | `workName` | 原样 |
| 解析 | `explanation` | 原样 |
| 题目作者 | `author` | 原样 |

### 6.2 40 题映射

| 题号 | id | audioCode | type | difficulty | 答案 | correctOption | 出题人 |
| ---: | :--- | :--- | :--- | :--- | :---: | ---: | :--- |
| 1 | `q-01` | `TRACK-01` | WORK | MEDIUM | B | 1 | Munch |
| 2 | `q-02` | `TRACK-02` | ATMOSPHERE | EASY | D | 3 | Munch |
| 3 | `q-03` | `TRACK-03` | WORK | MEDIUM | A | 0 | christina |
| 4 | `q-04` | `TRACK-04` | ATMOSPHERE | HARD | A | 0 | christina |
| 5 | `q-05` | `TRACK-05` | ATMOSPHERE | EASY | A | 0 | christina |
| 6 | `q-06` | `TRACK-06` | ATMOSPHERE | EASY | A | 0 | christina |
| 7 | `q-07` | `TRACK-07` | WORK | HARD | C | 2 | treefish |
| 8 | `q-08` | `TRACK-08` | WORK | HARD | D | 3 | treefish |
| 9 | `q-09` | `TRACK-09` | WORK | HARD | B | 1 | 不可思议の逆天酱喵 |
| 10 | `q-10` | `TRACK-10` | ATMOSPHERE | EASY | D | 3 | 不可思议の逆天酱喵 |
| 11 | `q-11` | `TRACK-11` | WORK | HARD | C | 2 | 不可思议の逆天酱喵 |
| 12 | `q-12` | `TRACK-12` | ATMOSPHERE | MEDIUM | B | 1 | 不可思议の逆天酱喵 |
| 13 | `q-13` | `TRACK-13` | ATMOSPHERE | MEDIUM | B | 1 | 不可思议の逆天酱喵 |
| 14 | `q-14` | `TRACK-14` | ATMOSPHERE | EASY | C | 2 | Aki_BG7ZGA |
| 15 | `q-15` | `TRACK-15` | ATMOSPHERE | HARD | A | 0 | そらつね |
| 16 | `q-16` | `TRACK-16` | ATMOSPHERE | HARD | B | 1 | そらつね |
| 17 | `q-17` | `TRACK-17` | ATMOSPHERE | MEDIUM | A | 0 | そらつね |
| 18 | `q-18` | `TRACK-18` | WORK | EASY | C | 2 | そらつね |
| 19 | `q-19` | `TRACK-19` | ATMOSPHERE | MEDIUM | D | 3 | そらつね |
| 20 | `q-20` | `TRACK-20` | ATMOSPHERE | MEDIUM | B | 1 | christina |
| 21 | `q-21` | `TRACK-21` | WORK | HARD | A | 0 | DIO |
| 22 | `q-22` | `TRACK-22` | ATMOSPHERE | MEDIUM | A | 0 | christina |
| 23 | `q-23` | `TRACK-23` | WORK | EASY | B | 1 | 千石ユノ |
| 24 | `q-24` | `TRACK-24` | WORK | EASY | D | 3 | Aki_BG7ZGA |
| 25 | `q-25` | `TRACK-25` | ATMOSPHERE | EASY | B | 1 | taffy |
| 26 | `q-26` | `TRACK-26` | ATMOSPHERE | EASY | B | 1 | taffy |
| 27 | `q-27` | `TRACK-27` | ATMOSPHERE | EASY | C | 2 | taffy |
| 28 | `q-28` | `TRACK-28` | ATMOSPHERE | EASY | B | 1 | taffy |
| 29 | `q-29` | `TRACK-29` | WORK | MEDIUM | B | 1 | taffy |
| 30 | `q-30` | `TRACK-30` | ATMOSPHERE | EASY | B | 1 | taffy |
| 31 | `q-31` | `TRACK-31` | ATMOSPHERE | HARD | C | 2 | taffy |
| 32 | `q-32` | `TRACK-32` | WORK | HARD | B | 1 | 千石ユノ |
| 33 | `q-33` | `TRACK-33` | ATMOSPHERE | MEDIUM | A | 0 | 千石ユノ |
| 34 | `q-34` | `TRACK-34` | ATMOSPHERE | MEDIUM | B | 1 | 千石由乃 |
| 35 | `q-35` | `TRACK-35` | ATMOSPHERE | EASY | B | 1 | 千石由乃 |
| 36 | `q-36` | `TRACK-36` | ATMOSPHERE | EASY | A | 0 | 千石由乃 |
| 37 | `q-37` | `TRACK-37` | ATMOSPHERE | MEDIUM | B | 1 | taffy |
| 38 | `q-38` | `TRACK-38` | ATMOSPHERE | MEDIUM | B | 1 | 千石由乃 |
| 39 | `q-39` | `TRACK-39` | ATMOSPHERE | MEDIUM | B | 1 | larter |
| 40 | `q-40` | `TRACK-40` | ATMOSPHERE | MEDIUM | C | 2 | taffy |

### 6.3 文本约束

- `prompt`、`workName`、`explanation`、`author`、`audioCode` 非空。
- `options` 恰好四项，每项非空，顺序与编排一致。
- `correctOption` 取 0 至 3。
- `id` 全库唯一，不含逗号、换行与路径分隔符。
- 题干、选项与解析中的全角标点原样保留。
- 选项文本不保留「选项A：」之类的前缀。

### 6.4 出题人署名

`author` 取自编排的「题目作者」，在线下揭晓、主持人控制台与线上答题页展示。

| 展示位置 | 内容 |
| :--- | :--- |
| 线下揭晓卡（`ANSWER_REVEALED`、`FINISHED`） | 「出题人：<署名>」，与作品名、解析同一区域 |
| 主持人控制台题目预览区 | 当前题的出题人署名 |
| 线上答题页题卡 | 「出题人：<署名>」，与题干、题型、难度同区 |

署名分布：Munch 2 题、christina 6 题、treefish 2 题、不可思议の逆天酱喵 5 题、Aki_BG7ZGA 2 题、そらつね 5 题、DIO 1 题、千石ユノ 3 题、千石由乃 4 题、taffy 9 题、larter 1 题。

署名约定：

`千石ユノ`（TRACK-23、TRACK-32、TRACK-33）与 `千石由乃`（TRACK-34、TRACK-35、TRACK-36、TRACK-38）作为两种写法原样保留，不合并。

## 7. 启动与自检

### 7.1 备份

```powershell
Copy-Item backend\data backend\data-backup -Recurse
```

### 7.2 放置文件

1. 将裁切后的音频命名为 `event-demo.mp3` 放入 `backend/data/`。
2. 将生成的 `timeline.json` 与 `questions.json` 放入 `backend/data/`。
3. 将 `leaderboards.json` 与 `online_leaderboard.json` 移出数据目录，或写入空数组 `[]`，使活动从零榜单开始。

### 7.3 启动

```powershell
cd backend
mvn clean compile
mvn spring-boot:run
```

`TimelineStore` 在 Bean 构造阶段读取并校验编排与题库。数据不合规时启动中断，异常信息指出具体条目编号与失败规则；数据合规时服务正常监听 8080。

### 7.4 接口自检

```powershell
Invoke-RestMethod http://localhost:8080/api/health
Invoke-RestMethod http://localhost:8080/api/events/demo/online/questions
Invoke-RestMethod -Method Head http://localhost:8080/api/events/demo/audio
```

检查项：

| 项 | 预期 |
| :--- | :--- |
| `/api/health` | 返回运行中标识 |
| `/api/events/demo/online/questions` | `totalQuestions` 为 40，题集首题 `audioStartMs` 为 0，末题 `audioEndMs` 为 2593000，`audioUrl` 指向 `/api/events/demo/audio` |
| 音频请求 | 状态 200，`Accept-Ranges: bytes`；带 `Range: bytes=0-1023` 时返回 206 |
| 题集版本 | `questionSetVersion` 为非空十六进制字符串 |

## 8. 联调

### 8.1 线下

1. 启动前端：`cd frontend && npm install && npm run dev`。
2. 打开 `http://localhost:5173/host`，用 `app.host-password` 配置的口令登录（默认 `123456`）。
3. 轮次列表显示四轮，名称与 `timeline.json` 一致。
4. 选择第一轮并开始播放，音频从 0 秒开始，`LOAD/NEXT/LOCK/REVEAL` 依次触发。
5. 第一轮在 501 秒处进入 `FINISH`，主持人界面出现本轮榜。
6. 切换第二轮，确认音频定位到 501000 毫秒，画面从 `q-11` 开始。
7. 依次验证第三、四轮；第四轮在 2593 秒结束。

### 8.2 线上

1. 打开 `http://localhost:5173/`，输入昵称进入线上模式。
2. 开始答题，音频从头连续播放，题目按 `answerStartMs` 上屏。
3. 题卡显示题干、题型、难度与出题人署名。
4. 第 40 题结束后出现预览分。
5. 锁定成绩，确认线上榜与总榜写入。
6. 刷新页面，确认旧进度与题集版本匹配。

### 8.3 移动端

1. 手机与电脑处于同一局域网，或使用公网隧道地址。
2. 扫描线下二维码进入 `/offline?event=demo`。
3. 确认音频可加载、可跳转，页面隐藏后恢复进度。

## 9. 上线前检查清单

- `backend/data/event-demo.mp3` 时长为 2593.0 秒量级，浏览器可拖到末尾。
- `timeline.json` 的 `audioFile` 与实际文件名一致。
- 四轮区间为 0/501000、501000/1234000、1234000/1905000、1905000/2593000。
- 40 题在线上接口中顺序出现，题数与题库一致。
- 主持人四轮均可启动、暂停、恢复与结算。
- 揭晓卡显示正确选项、作品名、解析与出题人。
- 线上完整跑完一次 43 分 13 秒，预览与锁定可用。
- 榜单文件为空数组，测试记录已清理。
- 活动前备份 `backend/data` 目录。

## 10. 回滚

数据替换前保留 `backend/data-backup`。出现以下情况时整目录还原并重启服务：

- 启动阶段校验失败且短时间无法修正数据。
- 联调中音频与题目错位，且无法在十分钟内定位。
- 轮次区间与音频长度不一致导致播放中断。

还原后 `questionSetVersion` 变化，浏览器本地线上进度失效，参与者重新开始。

## 11. 已知约束

1. 线上与线下共用 43 分 13 秒的单文件音频，首次加载与任意跳转依赖 HTTP Range；服务端按请求区间返回音频片段。
2. 合并文件内各题静音起点相对编排时刻存在 170 毫秒以内的累计偏移，偏移全部落在题目之间的静音段内，不影响出题、锁卷与揭晓。
3. 四轮分轨文件不在交付目录；单独重导某一轮需向素材方索取原始分轨。
4. Q-24 的四段音频按 A、B、C、D 依次拼接，与四个选项一一对应；题库中该题题干末尾包含「音频按选项 A 至 D 顺序播放。」，与四段音频的对应关系一致。
5. 轮首题的 `NEXT` 比片段起点晚 1 毫秒，`audioStartMs` 取片段起点，线上作答窗口从 1 毫秒开始。
