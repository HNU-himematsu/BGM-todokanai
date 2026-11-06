# 双母带音频与双编排规范

## 1. 文档用途

本文定义线上模式与线下模式使用不同音频母带、不同编排文件的运行形态：线上模式播放不含主持人播报的纯 BGM 母带，线下模式播放含 12 段题间播报与四轮轮首播报的母带。

读者对象为数据生成者、后端开发者与部署执行者。通用编排契约见 [`timeline-spec.md`](timeline-spec.md)，播报片段与槽位几何见 [`announcer-voice.md`](announcer-voice.md)，OSS 对象与发布流程见 [`../ops-and-deployment/oss-audio.md`](../ops-and-deployment/oss-audio.md)。

## 2. 运行期拓扑

| 模式 | 播放者 | 音频对象 | 编排文件 | 音频地址来源 |
| :--- | :--- | :--- | :--- | :--- |
| 线上 ONLINE | 参与者浏览器 | `bgm/event-online-cbr192.mp3` | `timeline-online.json` | 后端 `app.audio-url-online`，经 `OnlineQuestionSet.audioUrl` 下发 |
| 线下 OFFLINE | 主持人控制台 | `bgm/event-offline.mp3` | `timeline.json` | 构建变量 `VITE_AUDIO_URL`；为空时走 `/api/events/{eventId}/audio` |

- 两种模式共用 `questions.json`、计分规则、参与者身份标识与榜单存储。
- 音频与编排按模式分离；两份编排的题目序列必须一致。
- 线上参与者与线下现场可以同时活动，两边听到的音频内容不同，属于设计结果。

## 3. 线上模式

### 3.1 音频对象

| 项目 | 值 |
| :--- | :--- |
| 对象路径 | `bgm/event-online-cbr192.mp3` |
| 访问地址 | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3` |
| 大小 | 62,233,783 字节 |
| 时长 | 2593.000 秒 |
| 采样率 / 声道 | 44100 Hz / 2 |
| 编码 | MP3 VBR，平均约 130 kbps |
| 帧表 | 99270 帧，Xing TOC 99 项非零 |
| ETag | `AD58949BC8932351DA7062203FC47E2C` |
| 访问权限 | 公共读 |
| 普通请求 / Range | `200` / `206` |
| CORS | 未启用；`<audio>` 直接播放与 Range 不依赖 CORS |

- 对象内容为纯 BGM，不含主持人播报，上传于 2026-10-31。
- 对象与 `work/event-demo-vbr-original-20261030.mp3` 在 510 秒处逐样本一致，偏移 0 毫秒；对象多出的 5 帧、约 149 毫秒位于文件尾部，属于编码尾垫。
- 线上模式复用该对象，不重新编码、不重新上传。
- 中文路径在配置中写百分号编码，避免不同 HTTP 客户端对非 ASCII 路径处理不一致。

### 3.2 编排文件 `timeline-online.json`

| 项目 | 约定 |
| :--- | :--- |
| 位置 | `backend/data/timeline-online.json`，与 `questions.json` 同目录 |
| `eventId` | `jisong` |
| `eventName` | `旮旯原声之届不到的 BGM` |
| `audioFile` | `event-online.mp3`（仅本地联调解析，部署环境由 `app.audio-url-online` 覆盖） |
| 轮次划分 | 与线下编排相同的四轮与 10 题分组 |
| 题目顺序 | 与线下编排相同的 40 题顺序 |

轮次区间：

| id | name | startMs | endMs |
| :--- | :--- | ---: | ---: |
| `round-1` | 第一轮 | 0 | 501000 |
| `round-2` | 第二轮 | 501000 | 1234000 |
| `round-3` | 第三轮 | 1234000 | 1905000 |
| `round-4` | 第四轮 | 1905000 | 2593000 |

条目锚点与线下编排一致：轮首题 `LOAD.atMs` 等于音乐起点、`NEXT.atMs` 等于音乐起点 + 1；其余题 `LOAD.atMs` 等于音乐起点 − 1000；`LOCK` 位于音乐终点后 7 秒、9 秒或 37 秒；`REVEAL` 位于 `LOCK` 后 1 秒；`FINISH` 等于本轮 `endMs`。

首题取 `audioStartMs = 0`、`answerStartMs = 1`；末题取 `audioEndMs = 2593000`。

### 3.3 线上题集

| 项 | 值 |
| :--- | ---: |
| `totalQuestions` | 40 |
| 首题 `audioStartMs` | 0 |
| 首题 `answerStartMs` | 1 |
| 末题 `audioEndMs` | 2593000 |
| 音频对象时长 | 2593.149 秒 |

- `OnlineQuestionSet.audioStartMs` 取首题 `audioStartMs`，`audioEndMs` 取末题 `audioEndMs`。
- `questionSetVersion` 按既有算法对 40 题的 `questionId`、`audioStartMs`、`audioEndMs`、`answerStartMs`、`answerEndMs` 计算 SHA-256。
- 线上题集只由 `timeline-online.json` 生成，不读取线下编排。

### 3.4 定位要求

- 对象为 VBR，逐题定位依赖 Xing TOC。上线前按 [`audio-seek-quality.md`](audio-seek-quality.md) 第 9.3 节对 40 题逐题复测：首次出声与素材相差不超过 0.5 秒，且不出现超过 0.5 秒的多余静音。
- 复测不达标时，用同一音频内容重编码 CBR 192（`-c:a libmp3lame -b:a 192k -ar 44100 -ac 2`）上传为新对象并切换 `app.audio-url-online`；编排与题集版本保持不变。

### 3.5 40 题线上绝对时刻

线上编排取播报插入前的时间轴，四轮题目音乐首尾相接，不含轮首与题间播报。下表与 `jisong-event-integration.md` 第 5.6 节的线下表逐题对应，差值为各轮的累计插入量。

表中 `audioEnd` 为题目音频窗口终点，包含到下一题音频起点的余量静音；音乐片段终点由 `LOCK` 减 7000、9000 或 37000 得到。`NEXT` 列对应 `NEXT.atMs`。

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

## 4. 线下模式

### 4.1 母带结构

线下母带由四轮音乐段与 4 段轮首播报、12 段题间播报、1 段谢幕组成。轮首播报取第一轮开幕段整段（母带 0–33535 毫秒，含 600 毫秒尾静音）。

| 段 | 内容 | 起点 | 终点 |
| :--- | :--- | ---: | ---: |
| 第一轮 | 开幕 + 10 题 + 题间播报 + 结束致辞 | 0 | 562355 |
| 轮首播报 2 | 第一轮开幕段整段 | 562355 | 595890 |
| 第二轮 | 10 题 + 题间播报 + 结束致辞 | 595890 | 1341690 |
| 轮首播报 3 | 第一轮开幕段整段 | 1341690 | 1375225 |
| 第三轮 | 10 题 + 题间播报 + 结束致辞 | 1375225 | 2054605 |
| 轮首播报 4 | 第一轮开幕段整段 | 2054605 | 2088140 |
| 第四轮 | 10 题 + 题间播报 + 结束致辞 + 谢幕 | 2088140 | 2780110 |

母带总长 2780110 毫秒，即 46 分 20.110 秒。

### 4.2 轮首槽位规则

| 项目 | 规则 |
| :--- | :--- |
| 适用轮次 | 第二、三、四轮 |
| 槽起点 | 本轮 `startMs` |
| 前置留白 | 0 |
| 槽内容 | 母带 0–33535 毫秒整段，含开幕语音与 600 毫秒尾静音 |
| 槽长 | 33535 毫秒 |
| 本轮首题音乐起点 | `startMs + 33535` |
| 首题 `audioStartMs` | 本轮 `startMs`，音频窗口包含轮首播报段 |
| 首题 `LOAD.atMs` | 首题音乐起点 |
| 首题 `NEXT.atMs` | 首题音乐起点 + 1 |
| 轮次 `endMs` | 原 `endMs` + 33535 |

### 4.3 四轮区间

| 轮次 | startMs | 轮首播报 | 首题音乐起点 | endMs | 时长 | 本轮插入量 |
| :--- | ---: | :--- | ---: | ---: | ---: | ---: |
| 第一轮 | 0 | 0–33535（开幕） | 33535 | 562355 | 562355 | 61355 |
| 第二轮 | 562355 | 562355–595890 | 595890 | 1341690 | 779335 | 46335 |
| 第三轮 | 1341690 | 1341690–1375225 | 1375225 | 2054605 | 712915 | 41915 |
| 第四轮 | 2054605 | 2054605–2088140 | 2088140 | 2780110 | 725505 | 37505 |

四轮插入量合计 187110 毫秒；第一轮插入量含开幕 33535 毫秒，第二至四轮插入量含轮首播报 33535 毫秒与题间播报插入量。

### 4.4 时间轴重算规则

1. 第 N 轮（N≥2）全部条目的 `atMs` 与 `audioEndMs` 加 `33535 × (N − 1)`，即第二、三、四轮分别加 33535、67070、100605。
2. 第 N 轮首题的 `audioStartMs` 取本轮 `startMs`，其余题目的 `audioStartMs` 取上一题 `audioEndMs`。
3. 轮次 `startMs`、`endMs` 取 4.3 节数值。
4. 锚点偏移保持不变：`LOAD.atMs` 等于音乐起点 − 1000（轮首题取音乐起点）；`NEXT.atMs` 等于音乐起点（轮首题取音乐起点 + 1）；`LOCK.atMs` 等于本题音乐片段终点 + 7000 / 9000 / 37000；`REVEAL.atMs` 等于 `LOCK.atMs` + 1000；`FINISH.atMs` 等于本轮 `endMs`。
5. 末题 `audioEndMs` 等于母带总长 2780110，且等于第四轮 `FINISH.atMs`。

重算后的数据满足：

- 每题 `audioStartMs < audioEndMs`，且区间位于所属轮次内。
- 相邻题的 `audioEndMs` 等于下一题 `audioStartMs`；轮界处上一轮末题 `audioEndMs` 等于下一轮首题 `audioStartMs`。
- 每题 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。
- 每轮 `FINISH` 晚于末题 `REVEAL`。

全部 40 题的绝对时刻见 [`jisong-event-integration.md`](jisong-event-integration.md) 第 5.6 节。

### 4.5 母带生成

输入为含 12 段播报的母带 `backend/data/event-demo.mp3`（2679505 毫秒）。按 4.3 节的段边界切分，在三个轮界插入开幕段整段。

```powershell
ffmpeg -y -i backend\data\event-demo.mp3 -ar 44100 -ac 2 -c:a pcm_s16le work\full.wav
ffmpeg -y -ss 0 -t 33.535 -i work\full.wav -c:a pcm_s16le work\opening-slot.wav

ffmpeg -y -ss 0        -t 562.355 -i work\full.wav -c:a pcm_s16le work\seg0.wav
ffmpeg -y -ss 562.355  -t 745.800 -i work\full.wav -c:a pcm_s16le work\seg1.wav
ffmpeg -y -ss 1308.155 -t 679.380 -i work\full.wav -c:a pcm_s16le work\seg2.wav
ffmpeg -y -ss 1987.535 -t 691.970 -i work\full.wav -c:a pcm_s16le work\seg3.wav
```

`work\list.txt` 顺序为 `seg0 → opening-slot → seg1 → opening-slot → seg2 → opening-slot → seg3`，拼接与编码：

```powershell
ffmpeg -y -f concat -safe 0 -i work\list.txt -c:a libmp3lame -b:a 192k -ar 44100 -ac 2 -write_xing 1 work\event-offline.mp3
```

素材原件齐备时，以 `music/bgm-music.mp3` 与 `music/0d00/` 为输入，由 `tools/build_voice_mix.py` 增加轮首槽位规则后一次生成，音质优于二次编码。

生成后校验：

| 项 | 预期 |
| :--- | :--- |
| 时长 | 2780.110 秒 ± 0.1 秒 |
| 采样率 / 声道 / 码率 | 44100 Hz / 2 / 192 kbps CBR |
| 轮首槽语音 | 每个槽内 `max_volume` 高于 −20 dB |
| 轮首槽尾静音 | 槽末 600 毫秒 `max_volume` 低于 −50 dB |
| 首题音乐 | 每轮首题音乐起点后 1 秒 `max_volume` 高于 −20 dB |
| 定位 | 40 题 `answerStartMs` 逐题定位误差不超过 0.5 秒 |

## 5. 配置契约

`backend/src/main/resources/application.yml`：

```yaml
app:
  timeline-file: "${APP_TIMELINE_FILE:data/timeline.json}"
  online-timeline-file: "${APP_ONLINE_TIMELINE_FILE:data/timeline-online.json}"
  audio-url: "${APP_AUDIO_URL:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3}"
  audio-url-online: "${APP_AUDIO_URL_ONLINE:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3}"
```

| 配置项 | 缺省 | 说明 |
| :--- | :--- | :--- |
| `app.timeline-file` | `data/timeline.json` | 线下编排 |
| `app.online-timeline-file` | `data/timeline-online.json` | 线上编排；为空时取 `app.timeline-file` |
| `app.audio-url` | OSS 线下对象地址 | 线下音频地址；为空时使用 `/api/events/{eventId}/audio` 本地流 |
| `app.audio-url-online` | OSS 线上对象地址 | 线上音频地址；为空时依次回落 `app.audio-url` 与 `/api/events/{eventId}/audio?mode=online` |

环境变量：

| 变量 | 取值 |
| :--- | :--- |
| `APP_AUDIO_URL` | 线下对象地址 |
| `APP_AUDIO_URL_ONLINE` | 线上对象地址 |
| `VITE_AUDIO_URL` | 线下对象地址，构建时注入主持人控制台 |

`VITE_AUDIO_URL` 只作用于主持人控制台；线上参与者的音频地址始终来自 `OnlineQuestionSet.audioUrl`。

## 6. 后端行为规格

### 6.1 加载

`TimelineStore` 构造参数：

| 参数 | 配置键 | 缺省 |
| :--- | :--- | :--- |
| 线下编排路径 | `app.timeline-file` | `data/timeline.json` |
| 线上编排路径 | `app.online-timeline-file` | `data/timeline-online.json` |
| 线下音频地址 | `app.audio-url` | OSS 线下对象地址 |
| 线上音频地址 | `app.audio-url-online` | OSS 线上对象地址 |

加载顺序：

1. 读取并校验线下编排，解析同目录 `questions.json`。
2. `app.online-timeline-file` 为空时，线上编排取线下编排。
3. 非空时读取并校验线上编排；两份文件位于同一目录，题库只加载一次。
4. 执行 6.2 节交叉校验。
5. 由线上编排生成线上题集与 `questionSetVersion`。
6. 解析两份音频的本地路径。

### 6.2 交叉校验

两份编排必须同时满足，任一失败即启动失败：

1. `eventId` 相同。
2. `eventName` 相同。
3. 按轮次顺序拉平、按 `questionId` 去重后的题目序列相同。
4. 两份编排的题目集合覆盖题库全集。
5. 每份编排自身通过通用校验，见 [`timeline-spec.md`](timeline-spec.md) 第 8 节。

### 6.3 访问器

| 方法 | 返回 |
| :--- | :--- |
| `timeline()` | 线下编排 |
| `onlineTimeline()` | 线上编排 |
| `onlineQuestions()` | 由线上编排生成的线上题集窗口 |
| `questionSetVersion()` | 线上题集版本 |
| `audioPath()` | 线下本地音频路径 |
| `audioUrl()` | 线下音频地址；为空时返回 `/api/events/{eventId}/audio` |
| `onlineAudioPath()` | 线上本地音频路径 |
| `onlineAudioUrl()` | 线上音频地址；为空时回落 `audioUrl()`，仍为空时返回 `/api/events/{eventId}/audio?mode=online` |
| `requireAudioReadable()` | 校验线下音频可读，外部地址非空时直接通过 |
| `requireOnlineAudioReadable()` | 校验线上音频可读，外部地址非空时直接通过 |

### 6.4 接口映射

| 接口 | 数据来源 |
| :--- | :--- |
| `GET /api/events/{eventId}/online/questions` | 线上题集窗口；`audioUrl` 取 `onlineAudioUrl()`；`audioStartMs` 取首题，`audioEndMs` 取末题 |
| `GET /api/events/{eventId}/audio` | 线下音频；外部地址配置时返回 302，否则输出本地流并支持 Range |
| `GET /api/events/{eventId}/audio?mode=online` | 线上音频；外部地址配置时返回 302，否则输出线上本地流并支持 Range |
| `GET /api/events/{eventId}/state` | 线下编排的轮次、区间与当前状态 |
| 线上预览与锁定 | 以线上题集窗口与 `questionSetVersion` 校验 |

`mode` 缺省值为 `offline`；未知取值按 `offline` 处理。

### 6.5 失败行为

| 条件 | 行为 |
| :--- | :--- |
| 任一份编排结构校验失败 | 启动失败，日志指出文件与条目 |
| 交叉校验失败 | 启动失败，日志指出不一致的题目或字段 |
| 线上外部地址为空且线上本地音频缺失 | 线上题集接口返回素材错误，页面显示不可开始答题 |
| 线下外部地址为空且线下本地音频缺失 | 音频接口返回素材错误，主持人控制台显示播放失败 |

### 6.6 实施清单

改动面覆盖下列文件，前端代码保持不变：

| 文件 | 改动 |
| :--- | :--- |
| `backend/src/main/resources/application.yml` | 提供 `app.online-timeline-file` 与 `app.audio-url-online`，缺省值见第 5 节 |
| `backend/src/main/java/com/jisong/bgm/service/TimelineStore.java` | 构造参数接收线上编排路径与线上音频地址；加载并交叉校验两份编排；提供 `onlineTimeline()`、`onlineAudioPath()`、`onlineAudioUrl()`、`requireOnlineAudioReadable()`；`onlineQuestions()` 由线上编排生成 |
| `backend/src/main/java/com/jisong/bgm/service/ActivityService.java` | 音频访问器增加模式分支，线下路径与地址保持指向 `timeline.json` |
| `backend/src/main/java/com/jisong/bgm/controller/EventController.java` | `audio()` 增加 `mode` 查询参数，`online` 走线上音频访问器，其余取值走线下 |
| `backend/src/main/java/com/jisong/bgm/service/OnlineAttemptService.java` | 线上题集校验线上音频可读，`audioUrl` 取 `onlineAudioUrl()` |
| `backend/data/timeline-online.json` | 线上编排文件，数值见 3.2 节 |
| `backend/data/event-offline.mp3` | 由 4.5 节流程生成 |

开发顺序：

1. 生成 `timeline-online.json` 与 `event-offline.mp3`，用 `jisong-event-integration.md` 第 5.6 节核对线下 40 题时刻。
2. 扩展 `TimelineStore` 的加载、交叉校验与访问器，按第 8 节测试清单补齐单元测试。
3. 扩展控制器与线上服务的模式分支。
4. 执行第 8 节测试清单与第 11 节验收清单。
5. 按第 9 节发布顺序上传线下对象并切换配置。

## 7. 前端行为

前端代码不区分音频来源，行为由配置与接口决定：

- 线上答题面板使用 `OnlineQuestionSet.audioUrl` 作为 `<audio>` 的 `src`，并使用题集返回的 `audioStartMs`、`answerStartMs`、`answerEndMs`、`audioEndMs` 推进。
- 主持人控制台使用构建变量 `VITE_AUDIO_URL`；该值为空时使用 `/api/events/{eventId}/audio`。
- 主持人轮次列表、`round.startMs` 与 `round.endMs` 来自后端线下编排，不读取本地文件。
- 构建生产前端时注入 `VITE_AUDIO_URL` 指向线下对象。

## 8. 测试清单

| 编号 | 用例 | 预期 |
| :--- | :--- | :--- |
| T1 | 两份编排加载 | 启动成功，线上题集 40 题 |
| T2 | 线上题集边界 | 首题 `audioStartMs` 0、`answerStartMs` 1；末题 `audioEndMs` 2593000 |
| T3 | 线下轮次 | 四轮区间 0/562355、562355/1341690、1341690/2054605、2054605/2780110 |
| T4 | 轮首题窗口 | 第二、三、四轮首题 `audioStartMs` 等于本轮 `startMs`，`LOAD.atMs` 等于 `startMs + 33535` |
| T5 | 交叉校验 | 题目顺序、`eventId`、题库覆盖任一不一致时启动失败 |
| T6 | 线上题集地址 | `audioUrl` 等于 `app.audio-url-online`；该值为空时等于 `app.audio-url` |
| T7 | 线下音频地址 | `/api/events/{eventId}/audio` 返回 302 指向线下对象 |
| T8 | 本地模式 | 两个外部地址均为空时，线上题集 `audioUrl` 为 `/api/events/{eventId}/audio?mode=online` |
| T9 | 单编排兼容 | `app.online-timeline-file` 为空时，线上题集由线下编排生成，行为与单母带部署一致 |
| T10 | 既有用例 | 榜单、预览、锁定、Range 与主持人流程用例保持通过 |

## 9. 部署与回滚

### 9.1 对象

| 对象 | 状态 | 大小 |
| :--- | :--- | ---: |
| `bgm/event-online-cbr192.mp3` | 已上传，CBR 192 kbps 重编码 | 62,233,783 字节 |
| `bgm/event-offline.mp3` | 发布时上传 | 约 66.7 MB |
| `bgm/event-demo.mp3` | 保留，用于回滚 | 64,309,541 字节 |

### 9.2 发布顺序

1. 生成 `work/event-offline.mp3`，替换 `backend/data/event-offline.mp3`。
2. 上传 `bgm/event-offline.mp3`，设置 `Content-Type: audio/mpeg`、`Cache-Control: public, max-age=31536000, immutable`。
3. 备份 `backend/data` 到 `backend/data-backup`。
4. 将 `timeline.json`、`timeline-online.json`、`questions.json` 放入 `shared/data`；该目录不放置音频文件。
5. 设置 `APP_AUDIO_URL`、`APP_AUDIO_URL_ONLINE`。
6. 以 `VITE_AUDIO_URL` 指向线下对象构建前端。
7. 重启服务，按第 11 节验收。

### 9.3 回滚

1. `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 切回 `bgm/event-demo.mp3`，`APP_ONLINE_TIMELINE_FILE` 置空。
2. `shared/data` 整目录还原上一版备份。
3. 以回滚后的对象重建前端并重启服务。

## 10. 已知约束

1. 线上对象为 VBR，切题定位精度依赖 Xing TOC，需按 3.4 节复测；CBR 重编码是唯一兜底手段。
2. 轮首播报复用第一轮开幕段，第二、三、四轮的语音内容与第一轮相同；开幕词含轮次或规则描述时，现场会听到重复表述。
3. 线下母带总长 46 分 20.110 秒，比单母带形态长 1 分 40.6 秒，主持节奏与轮间安排按此排布。
4. 音频或题目变化时，两份编排必须同时重算；只改一份会导致交叉校验失败或线上、线下题目错位。
5. 线上题集版本与单母带形态的纯 BGM 版本一致，浏览器中残留的同版本进度可能被恢复，活动前清理一次本地存储。
6. 线上与线下同时活动时，参与者听纯 BGM、现场听带播报母带，两边内容不同。
7. 线上对象带 `Cache-Control: public, max-age=31536000, immutable`，浏览器与 CDN 命中长缓存；替换音频时使用新对象名并同步 `APP_AUDIO_URL_ONLINE`。

## 11. 验收清单

线上：

- [ ] `GET /api/events/jisong/online/questions` 返回 40 题，`audioUrl` 为 `bgm/event-online-cbr192.mp3` 的地址。
- [ ] 首题 `audioStartMs` 0、`answerStartMs` 1；末题 `audioEndMs` 2593000。
- [ ] 对象普通请求 `200`、Range 请求 `206`，`Content-Length` 62,233,783。
- [ ] 40 题逐题定位，首次出声与素材相差不超过 0.5 秒，无超过 0.5 秒的多余静音。
- [ ] 拍频抽检：轮次边界处的音频为音乐，不含人声。

线下：

- [ ] 主持人控制台四轮区间为 0/562355、562355/1341690、1341690/2054605、2054605/2780110。
- [ ] 每轮点击开始后先听到开幕播报，随后进入本轮第一题音乐。
- [ ] 每轮首题 `LOAD` 晚于轮首播报结束，`NEXT` 比首题音乐起点晚 1 毫秒。
- [ ] 每轮第 10 题的播报在 `FINISH` 前结束。
- [ ] 母带时长 2780.110 秒 ± 0.1 秒，44.1 kHz、2 声道、192 kbps CBR。

交叉：

- [ ] 两份编排的 40 题顺序一致，`TimelineStore` 启动校验通过。
- [ ] 主持人页面的 `<audio>` 地址为线下对象，线上题集的 `audioUrl` 为线上对象。
- [ ] `shared/data` 中不含音频文件，播放正常。
