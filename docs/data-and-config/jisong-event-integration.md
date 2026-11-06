# 姬松活动素材接入规范

## 1. 文档用途

本文规定「姬松活动」素材接入本仓库系统的完整做法：素材交付形态、音频成品规格、`timeline.json` 与 `questions.json` 的生成规则、启动自检、联调流程与回滚方式。按本文执行后，活动以 `eventId=jisong` 运行线下四轮与线上全题库两种模式。

读者对象为素材制作方与部署执行者。数据契约的完整定义见 [`timeline-spec.md`](timeline-spec.md)，接口字段见 [`../backend/api-spec.md`](../backend/api-spec.md)，本文只覆盖本次活动的具体数值与操作顺序。

## 2. 素材现状

| 文件 | 位置 | 实测内容 |
| :--- | :--- | :--- |
| `编排.docx` | `music/` | 4 轮 × 10 题 = 40 题；每题含音频编号、题目作者、原曲截取区间、题目片段起止、`LOAD/NEXT/LOCK/REVEAL`、题干、题型、难度、四个选项、正确答案、作品名、解析 |
| `bgm-music.mp3` | `music/` | 2593.149 秒，44.1 kHz 立体声，约 130 kbps VBR，42.3 MB |
| `0d00/` | `music/` | 主持人播报素材 12 段，合计 150.625 秒，32 kHz 单声道；逐段时长与用途见 [`announcer-voice.md`](announcer-voice.md) 第 3 节 |
| `音频分段时间戳识别表.docx` | `music/` | 12 段播报的分界表，覆盖开场、9 处题间边界、第 10 题之后的结算致辞与谢幕 |
| `debug.log` | `music/` | 浏览器网络监听日志，与本活动无关 |

编排文件的时间基准是轮内时间，格式为 `HH:MM:SS.mmm`。四轮各自从 `00:00:00.000` 起算。

`0d00/` 的 12 段与 `音频分段时间戳识别表.docx` 的 12 行一一对应，合成到题间静音段的规则见 [`announcer-voice.md`](announcer-voice.md)。

`bgm-music.mp3` 的四轮首尾相接，每题片段之后接 10 秒或 40 秒静音；Q-24 由四段拼合，依次为 A 段 24 秒、5 秒静音、B 段 20 秒、约 7.6 秒静音、C 段 27 秒、5 秒静音、D 段 20 秒、约 12 秒尾静音。四轮分轨文件不在交付目录中，单独重导某一轮需向素材方索取。

## 3. 目标文件布局

```text
backend/data/
├── timeline.json               # 线下编排
├── timeline-online.json        # 线上编排
├── questions.json
├── event-offline.mp3           # 线下母带，本地联调
├── event-online.mp3            # 线上母带，本地联调
├── leaderboards.json
├── online_leaderboard.json
└── leaderboard_summary.txt
```

- `eventId` 固定为 `jisong`，前端路由、线下二维码与主持人入口沿用现有地址。
- `backend/data/event-offline.mp3` 是线下母带的本地联调产物；部署环境通过 `APP_AUDIO_URL` 指向 `oss://himematsu/bgm/event-offline.mp3`，服务器不保存音频文件。
- 本地未配置外部地址时，音频路径相对各自编排文件所在目录解析；两份编排的 `audioFile` 保留文件名。
- 线上模式播放 `姬松活动/bgm-music.mp3`，内容为纯 BGM；线下模式播放含播报的母带。两种模式的拓扑与配置见 [`dual-master-audio.md`](dual-master-audio.md)。
- `leaderboards.json`、`online_leaderboard.json`、`leaderboard_summary.txt` 由服务在首次写入时生成。

## 4. 音频成品

### 4.1 规格

| 项目 | 要求 |
| :--- | :--- |
| 时长 | 2780.110 秒（与末题 `audioEndMs` 的差值不超过 100 毫秒） |
| 采样率 | 44100 Hz |
| 声道 | 2 |
| 编码 | MP3 |
| 体积 | 约 66.7 MB |
| 文件名 | `event-offline.mp3`，单段文件名，不含目录与空格 |
| 码率 | CBR 192 kbps；保留 VBR 时全文件最高与最低码率之比不超过 2 |
| 定位精度 | 定位落点与素材内容的时间偏差不超过 0.5 秒 |
| 播报 | 12 段主持人语音，按 [`announcer-voice.md`](announcer-voice.md) 的槽位混入题间静音段；第二至四轮轮首复用开幕段 |

码率、静音垫与定位精度的定义、测量方法与修复方案见 [`audio-seek-quality.md`](audio-seek-quality.md)。

### 4.2 母带合成

`event-offline.mp3` 由 `music/bgm-music.mp3` 与 `music/0d00/` 的 12 段播报一次性合成：按每题音乐终点切分源母带，依次拼接音乐段、播报槽与余量静音；第二至四轮在轮次起点插入开幕段整段，再单次编码。

槽位起点、槽长、插入量与时间轴重算规则见 [`announcer-voice.md`](announcer-voice.md)。成品总长 2780110 毫秒，编码参数为 44.1 kHz、立体声、MP3 CBR 192 kbps。

### 4.3 成品校验

```powershell
ffprobe -v error -show_entries format=duration,size,bit_rate -show_entries stream=sample_rate,channels -of default=noprint_wrappers=1 "backend\data\event-offline.mp3"
```

检查项：

- `duration` 落在 2780.000～2780.220 之间。
- `sample_rate=44100`，`channels=2`。
- 文件可被浏览器直接解码，末尾 300 毫秒为静音。
- 每个播报槽内 `max_volume` 高于 −20 dB，槽内末尾 400 毫秒低于 −50 dB。
- `bit_rate` 与目标码率一致；VBR 素材的最高与最低码率之比不超过 2。
- Xing/Info TOC 与帧表一致，或素材为 CBR。
- 播报片段与下一题音乐起点之间保留不少于 400 毫秒静音；母带由单次编码产出，定位精度按 [`announcer-voice.md`](announcer-voice.md) 第 9.4 节复测。

### 4.4 素材侧的约束

- 线下母带为单文件；四轮共用同一份音频，轮次以绝对时间定位。线上母带同样为单文件，内容为纯 BGM。
- 题目片段之间以静音填充，静音时长由 `LOCK`、`REVEAL` 与下一题 `NEXT` 的相对位置体现。
- HTTP Range 由后端接口实现，素材侧不需要切开文件。

## 5. 编排文件

### 5.1 顶层字段

```json
{
  "eventId": "jisong",
  "eventName": "旮旯原声之届不到的 BGM",
  "audioFile": "event-offline.mp3",
  "rounds": []
}
```

### 5.2 轮次

| id | name | startMs | endMs |
| :--- | :--- | ---: | ---: |
| `round-1` | 第一轮 | 0 | 562355 |
| `round-2` | 第二轮 | 562355 | 1341690 |
| `round-3` | 第三轮 | 1341690 | 2054605 |
| `round-4` | 第四轮 | 2054605 | 2780110 |

轮次按 `startMs` 升序排列，区间不重叠，相邻轮次首尾相接：后一轮的 `startMs` 等于前一轮的 `endMs`。

编排以「轮次开始」与「轮次结束」标注每轮音乐时长，四轮依次为 501000、733000、671000、688000 毫秒。母带按 [`announcer-voice.md`](announcer-voice.md) 的槽位插入播报，四轮区间依次为 0/562355、562355/1341690、1341690/2054605、2054605/2780110。

### 5.3 条目

每道题包含四条动作，顺序固定为 `LOAD → NEXT → LOCK → REVEAL`；每轮末尾追加一条 `FINISH`。

| 字段 | 适用动作 | 取值 |
| :--- | :--- | :--- |
| `atMs` | 全部 | 按 5.4 节的锚点由音乐起点、音乐终点或轮末计算 |
| `action` | 全部 | `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH` |
| `questionId` | `LOAD` | `q-01`～`q-40` |
| `audioStartMs` | `LOAD` | 本题音乐起点；第一题取 0 |
| `audioEndMs` | `LOAD` | 下一题音乐起点；轮末题取本轮 `endMs` |

`FINISH` 的 `atMs` 等于本轮 `endMs`。

示例（第 1 题与第 2 题）：

```json
[
  { "atMs": 33535, "action": "LOAD", "questionId": "q-01", "audioStartMs": 0, "audioEndMs": 79535 },
  { "atMs": 33536, "action": "NEXT" },
  { "atMs": 76535, "action": "LOCK" },
  { "atMs": 77535, "action": "REVEAL" },
  { "atMs": 78535, "action": "LOAD", "questionId": "q-02", "audioStartMs": 79535, "audioEndMs": 138535 },
  { "atMs": 79535, "action": "NEXT" }
]
```

### 5.4 生成规则

```text
累计插入量 = 本轮前序播报槽的插入量之和（第一轮含开幕 33535 毫秒，第二至四轮含轮首播报 33535 毫秒）
音乐起点 = 本题音乐片段开始 + 累计插入量
音乐终点 = 本题音乐片段结束 + 累计插入量
LOAD.atMs = 音乐起点 − 1000（轮首题取音乐起点）
NEXT.atMs = 音乐起点（轮首题取音乐起点 + 1）
LOCK.atMs = 音乐终点 + 该题锁卷偏移（7000、9000 或 37000）
REVEAL.atMs = LOCK.atMs + 1000
audioStartMs = 音乐起点（第一题取 0）
audioEndMs = 下一题音乐起点（轮末题取本轮 endMs）
```

每轮首题 `audioStartMs` 取本轮 `startMs`，音频窗口包含轮首播报段；其余题取本题音乐起点。轮首题的 `NEXT` 比音乐起点晚 1 毫秒。全部 40 题的绝对时刻见 5.6。

### 5.5 数据必须满足的不变量

1. 同一轮内全部条目的 `atMs` 严格递增。
2. 每题满足 `LOAD < NEXT < LOCK < REVEAL`。
3. 下一题的 `LOAD` 晚于上一题的 `REVEAL`。
4. 每题满足 `audioStartMs <= NEXT < LOCK <= audioEndMs`。
5. 相邻题满足 `audioEndMs` 等于下一题的 `audioStartMs`。
6. 第一题的 `audioStartMs` 为 0。
7. 末题的 `audioEndMs` 为 2780110。
8. 每轮 `FINISH` 等于 `endMs`，且晚于末题 `REVEAL`。
9. 每轮内不重复引用同一 `questionId`；全题库 40 题各被引用一次。
10. `audioStartMs` 不小于轮次 `startMs`，`audioEndMs` 不大于轮次 `endMs`。
11. 播报片段结束时刻加 400 毫秒后不晚于下一题音乐起点。
12. 每轮第 10 题的播报片段早于 `FINISH` 时刻结束。
13. 谢幕片段结束后保留 300 毫秒静音，末轮 `FINISH` 等于母带总长。

### 5.6 40 题绝对时刻（毫秒）

表中 `audioEnd` 为题目音频窗口终点，包含到下一题音频起点的余量静音；音乐片段终点由 `LOCK` 减 7000、9000 或 37000 得到。`NEXT` 列为 `NEXT.atMs`。

| 题号 | id | 轮次 | audioStart | NEXT | LOCK | REVEAL | audioEnd |
| ---: | :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| 1 | `q-01` | 第一轮 | 0 | 33536 | 76535 | 77535 | 79535 |
| 2 | `q-02` | 第一轮 | 79535 | 79535 | 135535 | 136535 | 138535 |
| 3 | `q-03` | 第一轮 | 138535 | 138535 | 188535 | 189535 | 194815 |
| 4 | `q-04` | 第一轮 | 194815 | 194815 | 226815 | 227815 | 235155 |
| 5 | `q-05` | 第一轮 | 235155 | 235155 | 275155 | 276155 | 278875 |
| 6 | `q-06` | 第一轮 | 278875 | 278875 | 335875 | 336875 | 342115 |
| 7 | `q-07` | 第一轮 | 342115 | 342115 | 379115 | 380115 | 385815 |
| 8 | `q-08` | 第一轮 | 385815 | 385815 | 447815 | 448815 | 455335 |
| 9 | `q-09` | 第一轮 | 455335 | 455335 | 494335 | 495335 | 499095 |
| 10 | `q-10` | 第一轮 | 499095 | 499095 | 554095 | 555095 | 562355 |
| 11 | `q-11` | 第二轮 | 562355 | 595891 | 636890 | 637890 | 639890 |
| 12 | `q-12` | 第二轮 | 639890 | 639890 | 678890 | 679890 | 681890 |
| 13 | `q-13` | 第二轮 | 681890 | 681890 | 741890 | 742890 | 748170 |
| 14 | `q-14` | 第二轮 | 748170 | 748170 | 845170 | 846170 | 848170 |
| 15 | `q-15` | 第二轮 | 848170 | 848170 | 946170 | 947170 | 949170 |
| 16 | `q-16` | 第二轮 | 949170 | 949170 | 1015170 | 1016170 | 1021410 |
| 17 | `q-17` | 第二轮 | 1021410 | 1021410 | 1109410 | 1110410 | 1112410 |
| 18 | `q-18` | 第二轮 | 1112410 | 1112410 | 1209410 | 1210410 | 1216930 |
| 19 | `q-19` | 第二轮 | 1216930 | 1216930 | 1263930 | 1264930 | 1268690 |
| 20 | `q-20` | 第二轮 | 1268690 | 1268690 | 1338690 | 1339690 | 1341690 |
| 21 | `q-21` | 第三轮 | 1341690 | 1375226 | 1407225 | 1408225 | 1410225 |
| 22 | `q-22` | 第三轮 | 1410225 | 1410225 | 1462225 | 1463225 | 1465225 |
| 23 | `q-23` | 第三轮 | 1465225 | 1465225 | 1497225 | 1498225 | 1503505 |
| 24 | `q-24` | 第三轮 | 1503505 | 1503505 | 1621505 | 1622505 | 1627845 |
| 25 | `q-25` | 第三轮 | 1627845 | 1627845 | 1704845 | 1705845 | 1707845 |
| 26 | `q-26` | 第三轮 | 1707845 | 1707845 | 1779845 | 1780845 | 1782845 |
| 27 | `q-27` | 第三轮 | 1782845 | 1782845 | 1854845 | 1855845 | 1857845 |
| 28 | `q-28` | 第三轮 | 1857845 | 1857845 | 1929845 | 1930845 | 1932845 |
| 29 | `q-29` | 第三轮 | 1932845 | 1932845 | 1974845 | 1975845 | 1979605 |
| 30 | `q-30` | 第三轮 | 1979605 | 1979605 | 2051605 | 2052605 | 2054605 |
| 31 | `q-31` | 第四轮 | 2054605 | 2088141 | 2160140 | 2161140 | 2163140 |
| 32 | `q-32` | 第四轮 | 2163140 | 2163140 | 2200140 | 2201140 | 2203140 |
| 33 | `q-33` | 第四轮 | 2203140 | 2203140 | 2274140 | 2275140 | 2277140 |
| 34 | `q-34` | 第四轮 | 2277140 | 2277140 | 2349140 | 2350140 | 2352140 |
| 35 | `q-35` | 第四轮 | 2352140 | 2352140 | 2426140 | 2427140 | 2429140 |
| 36 | `q-36` | 第四轮 | 2429140 | 2429140 | 2506140 | 2507140 | 2509140 |
| 37 | `q-37` | 第四轮 | 2509140 | 2509140 | 2581140 | 2582140 | 2584140 |
| 38 | `q-38` | 第四轮 | 2584140 | 2584140 | 2650140 | 2651140 | 2653140 |
| 39 | `q-39` | 第四轮 | 2653140 | 2653140 | 2698140 | 2699140 | 2702900 |
| 40 | `q-40` | 第四轮 | 2702900 | 2702900 | 2774900 | 2775900 | 2780110 |


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

1. 本地联调时将线下母带命名为 `event-offline.mp3` 放入 `backend/data/`；线上母带命名为 `event-online.mp3` 放入同一目录。
2. 将 `timeline.json`、`timeline-online.json` 与 `questions.json` 放入 `backend/data/`。
3. 将 `leaderboards.json` 与 `online_leaderboard.json` 移出数据目录，或写入空数组 `[]`，使活动从零榜单开始。
4. 部署时把 `backend/data/event-offline.mp3` 上传到 `oss://himematsu/bgm/event-offline.mp3`；线上母带复用 `oss://himematsu/姬松活动/bgm-music.mp3`；服务器数据目录只放两份编排、`questions.json` 与榜单文件。

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
Invoke-RestMethod http://localhost:8080/api/events/jisong/online/questions
Invoke-RestMethod -Method Head http://localhost:8080/api/events/jisong/audio
```

检查项：

| 项 | 预期 |
| :--- | :--- |
| `/api/health` | 返回运行中标识 |
| `/api/events/jisong/online/questions` | `totalQuestions` 为 40，题集首题 `audioStartMs` 为 0，末题 `audioEndMs` 为 2593000 |
| `/api/events/jisong/state` | 四轮区间 0/562355、562355/1341690、1341690/2054605、2054605/2780110 |
| 本地联调 `audioUrl` | 未配置 `app.audio-url-online` 时回落 `app.audio-url`；两者均为空时为 `/api/events/jisong/audio?mode=online` |
| 部署环境 `audioUrl` | 指向 `https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3` |
| 音频请求 | OSS 对象普通请求返回 200；带 `Range: bytes=0-1023` 时返回 206 |
| 题集版本 | `questionSetVersion` 为非空十六进制字符串 |

## 8. 联调

### 8.1 线下

1. 启动前端：`cd frontend && npm install && npm run dev`。
2. 打开 `http://localhost:5173/host`，用 `app.host-password` 配置的口令登录（默认 `123456`）。
3. 轮次列表显示四轮，名称与 `timeline.json` 一致。
4. 选择第一轮并开始播放，音频从 0 秒开始并播放开幕播报，首题在 33.5 秒上屏，`LOAD/NEXT/LOCK/REVEAL` 依次触发。
5. 第一轮在 562.4 秒处进入 `FINISH`，主持人界面出现本轮榜。
6. 切换到第二轮，音频定位到 562355 毫秒并播放轮首播报，`q-11` 在 595890 毫秒上屏。
7. 依次验证第三、四轮；第四轮在 2780.1 秒结束。

### 8.2 线上

1. 打开 `http://localhost:5173/`，输入昵称进入线上模式。
2. 开始答题，音频从头连续播放；首题在 1 毫秒上屏，音频为纯 BGM。
3. 题卡显示题干、题型、难度与出题人署名。
4. 第 40 题结束后出现预览分。
5. 锁定成绩，确认线上榜与总榜写入。
6. 刷新页面，确认旧进度与题集版本匹配。

### 8.3 移动端

1. 手机与电脑处于同一局域网，或使用公网隧道地址。
2. 扫描线下二维码进入 `/?mode=offline`。
3. 确认音频可加载、可跳转，页面隐藏后恢复进度。

## 9. 上线前检查清单

- `backend/data/event-offline.mp3` 时长为 2780.1 秒量级，浏览器可拖到末尾。
- OSS 对象 `bgm/event-offline.mp3` 的大小与 MD5 与本地成品一致，匿名 `HEAD` 返回 200；线上对象 `姬松活动/bgm-music.mp3` 返回 200、`Content-Length` 42,274,159。
- `timeline.json` 的 `audioFile` 与实际文件名一致。
- 四轮区间为 0/562355、562355/1341690、1341690/2054605、2054605/2780110。
- 每轮首题音乐起点为本轮 `startMs + 33535`，轮首播报在首题音乐之前播放。
- 40 题在线上接口中顺序出现，题数与题库一致。
- 主持人四轮均可启动、暂停、恢复与结算。
- 揭晓卡显示正确选项、作品名、解析与出题人。
- 线上完整跑完一次 43 分 13.1 秒，预览与锁定可用。
- 每轮第 10 题的播报在 `FINISH` 前结束，题间播报不与下一题音乐重叠。
- 榜单文件为空数组，测试记录已清理。
- 活动前备份 `backend/data` 目录。

## 10. 回滚

数据替换前保留 `backend/data-backup`。出现以下情况时整目录还原并重启服务：

- 启动阶段校验失败且短时间无法修正数据。
- 联调中音频与题目错位，且无法在十分钟内定位。
- 轮次区间与音频长度不一致导致播放中断。

还原后 `questionSetVersion` 变化，浏览器本地线上进度失效，参与者重新开始。

## 11. 已知约束

1. 线下母带 46 分 20.1 秒、线上母带 43 分 13.1 秒，两者均为单文件；首次加载与任意跳转依赖 HTTP Range，部署环境由 OSS 返回区间响应，本地联调由后端返回区间响应。
2. 播报槽位落在题目之间的静音段内，播报结束到下一题音乐起点不少于 400 毫秒；播报不进入下一题音乐片段。
3. 四轮分轨文件不在交付目录；单独重导某一轮需向素材方索取原始分轨。
4. Q-24 的四段音频按 A、B、C、D 依次拼接，与四个选项一一对应；题库中该题题干末尾包含「音频按选项 A 至 D 顺序播放。」，与四段音频的对应关系一致。
5. 轮首题的 `NEXT` 比片段起点晚 1 毫秒；线下轮首题 `audioStartMs` 取本轮 `startMs`，线上首题作答窗口从 1 毫秒开始。
6. 单文件编排的定位依赖浏览器按 Xing TOC 估算字节位置。母带为 CBR 192 kbps，TOC 与帧表一致；替换素材时保持 CBR，或全文件最高与最低码率之比不超过 2，校验方法见 [`audio-seek-quality.md`](audio-seek-quality.md)。
7. 播报与音乐的相对音量在生成阶段固定，现场只能整体调节。
