# 双母带音频与双编排规范

## 1. 文档用途

本文定义线上模式与线下模式使用不同音频母带、不同编排文件的运行形态：线上模式播放不含主持人播报的纯 BGM 母带，线下模式播放含 12 段题间播报与四轮轮首播报的母带。

读者对象为数据生成者、后端开发者与部署执行者。通用编排契约见 [`timeline-spec.md`](timeline-spec.md)，播报片段与槽位几何见 [`announcer-voice.md`](announcer-voice.md)，OSS 对象与发布流程见 [`../ops-and-deployment/oss-audio.md`](../ops-and-deployment/oss-audio.md)。

## 2. 运行期拓扑

| 模式 | 播放者 | 音频对象 | 编排文件 | 音频地址来源 |
| :--- | :--- | :--- | :--- | :--- |
| 线上 ONLINE | 参与者浏览器 | 本地 `event-online.mp3` 或 `bgm/event-online-cbr192.mp3` | `timeline-online.json` | 后端按本地优先解析，经 `OnlineQuestionSet.audioUrl` 下发 |
| 线下 OFFLINE | 主持人控制台 | 本地 `event-offline.mp3` 或 `bgm/event-offline.mp3` | `timeline.json` | `/api/events/{eventId}/audio`；`VITE_AUDIO_URL` 填写时直连该地址 |

- 两种模式共用 `questions.json`、计分规则、参与者身份标识与榜单存储。
- 音频与编排按模式分离；两份编排的题目序列必须一致。
- 线上参与者与线下现场可以同时活动，两边听到的音频内容不同，属于设计结果。

## 3. 线上模式

### 3.1 音频对象

本地联调使用 `backend/data/event-online.mp3`，内容与 `music/bgm-music.mp3` 相同：

| 项目 | 值 |
| :--- | :--- |
| 路径 | `backend/data/event-online.mp3` |
| 源文件 | `music/bgm-music.mp3` |
| 大小 | 53,869,130 字节 |
| 时长 | 2244.491 秒 |
| 采样率 / 声道 | 44100 Hz / 2 |
| 编码 | MP3 CBR 192 kbps |
| MD5 | `AEE6304CC699D65E65E06DAAAC6650A9` |
| 内容 | 纯 BGM，不含主持人播报 |

OSS 上的 `bgm/event-online-cbr192.mp3` 仍是已发布版本：

| 项目 | 值 |
| :--- | :--- |
| 对象路径 | `bgm/event-online-cbr192.mp3` |
| 访问地址 | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3` |
| 大小 | 62,233,783 字节 |
| 时长 | 2593.000 秒 |
| 编码 | MP3 VBR，平均约 130 kbps |
| 帧表 | 99270 帧，Xing TOC 99 项非零 |
| ETag | `AD58949BC8932351DA7062203FC47E2C` |
| 访问权限 | 公共读 |
| 普通请求 / Range | `200` / `206` |

- 仓库内 `timeline-online.json` 对应 2244.491 秒的新纯 BGM；已发布 OSS 对象仍对应 2593.000 秒的旧编曲。两者不可混用。
- 发布新编排前，把 `music/bgm-music.mp3` 上传为新对象，并同步更新 `APP_AUDIO_URL_ONLINE` 与前端构建变量。
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
| `round-1` | 第一轮 | 0 | 501066 |
| `round-2` | 第二轮 | 501066 | 1153339 |
| `round-3` | 第三轮 | 1153339 | 1724423 |
| `round-4` | 第四轮 | 1724423 | 2244491 |

条目锚点：轮首题 `LOAD.atMs` 等于音乐起点、`NEXT.atMs` 等于音乐起点 + 1；其余题 `LOAD.atMs` 等于音乐起点 − 1000；`LOCK.atMs` 等于下一题音乐起点 − 3000（轮末题取本轮 `endMs` − 3000）；`REVEAL` 位于 `LOCK` 后 1 秒；`FINISH` 等于本轮 `endMs`。

首题取 `audioStartMs = 0`、`answerStartMs = 1`；末题取 `audioEndMs = 2244491`。

### 3.3 线上题集

| 项 | 值 |
| :--- | ---: |
| `totalQuestions` | 40 |
| 首题 `audioStartMs` | 0 |
| 首题 `answerStartMs` | 1 |
| 末题 `audioEndMs` | 2244491 |
| 音频对象时长 | 2244.491 秒 |

- `OnlineQuestionSet.audioStartMs` 取首题 `audioStartMs`，`audioEndMs` 取末题 `audioEndMs`。
- `questionSetVersion` 按既有算法对 40 题的 `questionId`、`audioStartMs`、`audioEndMs`、`answerStartMs`、`answerEndMs` 计算 SHA-256。
- 线上题集只由 `timeline-online.json` 生成，不读取线下编排。

### 3.4 定位要求

- 新版线上音频为 CBR 192 kbps，逐题定位按 [`audio-seek-quality.md`](audio-seek-quality.md) 第 9.3 节对 40 题复测：首次出声与素材相差不超过 0.5 秒，且不出现超过 0.5 秒的多余静音。
- 已发布的 VBR 对象在替换为新对象前继续按旧版编排使用；新编排只配套新对象。

### 3.5 40 题线上绝对时刻

线上编排由实际音频起点生成，四轮题目音乐首尾相接，不含轮首与题间播报。`audioEnd` 为题目音频窗口终点，等于下一题音乐起点；`LOCK` 为 `audioEnd` 前 3000 毫秒，`REVEAL` 为 `LOCK` 后 1000 毫秒。

| 题号 | id | 轮次 | audioStart | NEXT | LOCK | REVEAL | audioEnd |
| ---: | :--- | :--- | ---: | ---: | ---: | ---: | ---: |
| 1 | `q-01` | 第一轮 | 0 | 1 | 43403 | 44403 | 46403 |
| 2 | `q-02` | 第一轮 | 46403 | 46403 | 102025 | 103025 | 105025 |
| 3 | `q-03` | 第一轮 | 105025 | 105025 | 155021 | 156021 | 158021 |
| 4 | `q-04` | 第一轮 | 158021 | 158021 | 190023 | 191023 | 193023 |
| 5 | `q-05` | 第一轮 | 193023 | 193023 | 233022 | 234022 | 236022 |
| 6 | `q-06` | 第一轮 | 236022 | 236022 | 293022 | 294022 | 296022 |
| 7 | `q-07` | 第一轮 | 296022 | 296022 | 333021 | 334021 | 336021 |
| 8 | `q-08` | 第一轮 | 336021 | 336021 | 398025 | 399025 | 401025 |
| 9 | `q-09` | 第一轮 | 401025 | 401025 | 440025 | 441025 | 443025 |
| 10 | `q-10` | 第一轮 | 443025 | 443025 | 498066 | 499066 | 501066 |
| 11 | `q-11` | 第二轮 | 501066 | 501067 | 542981 | 543981 | 545981 |
| 12 | `q-12` | 第二轮 | 545981 | 545981 | 584079 | 585079 | 587079 |
| 13 | `q-13` | 第二轮 | 587079 | 587079 | 648369 | 649369 | 651369 |
| 14 | `q-14` | 第二轮 | 651369 | 651369 | 727091 | 728091 | 730091 |
| 15 | `q-15` | 第二轮 | 730091 | 730091 | 807558 | 808558 | 810558 |
| 16 | `q-16` | 第二轮 | 810558 | 810558 | 876561 | 877561 | 879561 |
| 17 | `q-17` | 第二轮 | 879561 | 879561 | 947562 | 948562 | 950562 |
| 18 | `q-18` | 第二轮 | 950562 | 950562 | 1047571 | 1048571 | 1050571 |
| 19 | `q-19` | 第二轮 | 1050571 | 1050571 | 1097788 | 1098788 | 1100788 |
| 20 | `q-20` | 第二轮 | 1100788 | 1100788 | 1150339 | 1151339 | 1153339 |
| 21 | `q-21` | 第三轮 | 1153339 | 1153340 | 1185351 | 1186351 | 1188351 |
| 22 | `q-22` | 第三轮 | 1188351 | 1188351 | 1240662 | 1241662 | 1243662 |
| 23 | `q-23` | 第三轮 | 1243662 | 1243662 | 1275862 | 1276862 | 1278862 |
| 24 | `q-24` | 第三轮 | 1278862 | 1278862 | 1396356 | 1397356 | 1399356 |
| 25 | `q-25` | 第三轮 | 1399356 | 1399356 | 1456355 | 1457355 | 1459355 |
| 26 | `q-26` | 第三轮 | 1459355 | 1459355 | 1511376 | 1512376 | 1514376 |
| 27 | `q-27` | 第三轮 | 1514376 | 1514376 | 1566393 | 1567393 | 1569393 |
| 28 | `q-28` | 第三轮 | 1569393 | 1569393 | 1621398 | 1622398 | 1624398 |
| 29 | `q-29` | 第三轮 | 1624398 | 1624398 | 1666397 | 1667397 | 1669397 |
| 30 | `q-30` | 第三轮 | 1669397 | 1669397 | 1721423 | 1722423 | 1724423 |
| 31 | `q-31` | 第四轮 | 1724423 | 1724424 | 1776433 | 1777433 | 1779433 |
| 32 | `q-32` | 第四轮 | 1779433 | 1779433 | 1815354 | 1816354 | 1818354 |
| 33 | `q-33` | 第四轮 | 1818354 | 1818354 | 1869057 | 1870057 | 1872057 |
| 34 | `q-34` | 第四轮 | 1872057 | 1872057 | 1923709 | 1924709 | 1926709 |
| 35 | `q-35` | 第四轮 | 1926709 | 1926709 | 1980707 | 1981707 | 1983707 |
| 36 | `q-36` | 第四轮 | 1983707 | 1983707 | 2035146 | 2036146 | 2038146 |
| 37 | `q-37` | 第四轮 | 2038146 | 2038146 | 2090001 | 2091001 | 2093001 |
| 38 | `q-38` | 第四轮 | 2093001 | 2093001 | 2138992 | 2139992 | 2141992 |
| 39 | `q-39` | 第四轮 | 2141992 | 2141992 | 2186493 | 2187493 | 2189493 |
| 40 | `q-40` | 第四轮 | 2189493 | 2189493 | 2241491 | 2242491 | 2244491 |

## 4. 线下模式

### 4.1 母带结构

线下母带由四轮音乐段、4 段轮首播报、40 段题间播报与 1 段谢幕组成。轮首播报取第一轮开幕段整段，含 200 毫秒前置留白、32935 毫秒语音与 400 毫秒尾静音。

| 段 | 内容 | 起点 | 终点 |
| :--- | :--- | ---: | ---: |
| 第一轮 | 开幕 + 10 题 + 题间播报 + 结束致辞 | 0 | 562469 |
| 第二轮 | 轮首播报 + 10 题 + 题间播报 + 结束致辞 | 562469 | 1259149 |
| 第三轮 | 轮首播报 + 10 题 + 题间播报 + 结束致辞 | 1259149 | 1871684 |
| 第四轮 | 轮首播报 + 10 题 + 题间播报 + 结束致辞 + 谢幕 | 1871684 | 2429278 |

母带总长 2429278 毫秒，即 40 分 29.278 秒。

### 4.2 轮首槽位规则

| 项目 | 规则 |
| :--- | :--- |
| 适用轮次 | 第一、二、三、四轮 |
| 槽起点 | 本轮 `startMs` |
| 前置留白 | 200 毫秒 |
| 槽内容 | `开幕.mp3` 32935 毫秒 + 400 毫秒尾静音 |
| 槽长 | 33535 毫秒 |
| 本轮首题音乐起点 | `startMs + 33535` |
| 首题 `audioStartMs` | 本轮 `startMs`，音频窗口包含轮首播报段 |
| 首题 `LOAD.atMs` | 本轮 `startMs` |
| 首题 `NEXT.atMs` | 本轮 `startMs` + 1 |
| 第 2–10 题 `audioStartMs` | 本题播报段起点 |
| 第 2–10 题 `LOAD.atMs` | 本题播报段起点 − 1000 |
| 第 2–10 题 `NEXT.atMs` | 本题播报段起点 |
| 轮次 `endMs` | 按 4.3 节取值 |

### 4.3 四轮区间

| 轮次 | startMs | 轮首播报 | 首题音乐起点 | endMs | 时长 | 本轮插入量 |
| :--- | ---: | :--- | ---: | ---: | ---: | ---: |
| 第一轮 | 0 | 0–33535（开幕） | 33535 | 562469 | 562469 | 61403 |
| 第二轮 | 562469 | 562469–596004 | 596004 | 1259149 | 696680 | 44407 |
| 第三轮 | 1259149 | 1259149–1292684 | 1292684 | 1871684 | 612535 | 41451 |
| 第四轮 | 1871684 | 1871684–1905219 | 1905219 | 2429278 | 557594 | 37526 |

四轮插入量合计 184787 毫秒；每轮包含 33535 毫秒轮首播报，第四轮另含 2210 毫秒谢幕。

### 4.4 时间轴重算规则

1. 每题音乐区间等于源音频区间加上截至该题的累计插入量。
2. 轮次 `startMs`、`endMs` 取 4.3 节数值。
3. `LOAD.atMs` 等于本题播报段起点 − 1000（轮首题取本轮 `startMs`）；`NEXT.atMs` 等于本题播报段起点（轮首题取本轮 `startMs` + 1）。
4. `LOCK.atMs` 等于下一题播报段起点 − 3000（轮末题取本轮 `endMs` − 3000）；`REVEAL.atMs` 等于 `LOCK.atMs` + 1000；`FINISH.atMs` 等于本轮 `endMs`。
5. 第 N 轮首题的 `audioStartMs` 取本轮 `startMs`，其余题目的 `audioStartMs` 等于本题播报段起点；`audioEndMs` 等于下一题播报段起点，轮末题取本轮 `endMs`。
6. 末题 `audioEndMs` 等于母带总长 2429278，且等于第四轮 `FINISH.atMs`。

重算后的数据满足：

- 每题 `audioStartMs < audioEndMs`，且区间位于所属轮次内。
- 相邻题的 `audioEndMs` 等于下一题 `audioStartMs`；轮界处上一轮末题 `audioEndMs` 等于下一轮首题 `audioStartMs`。
- 每题 `audioStartMs <= answerStartMs < answerEndMs <= audioEndMs`。
- 每轮 `FINISH` 晚于末题 `REVEAL`。

全部 40 题的绝对时刻见 [`jisong-event-integration.md`](jisong-event-integration.md) 第 5.6 节。

### 4.5 母带生成

输入为纯 BGM `music/bgm-music.mp3`（2244.491 秒）与 `work/voice-extract/` 的 12 段播报。生成器按实际出声点与静音区间重建边界，把播报计入下一题的音频窗口并锚定在本题音乐之前，单次编码输出线下母带。

```powershell
python tools\build_voice_mix.py `
  --source "music\bgm-music.mp3" `
  --voice-dir "work\voice-extract" `
  --script "music\编排.docx" `
  --timeline "backend\data\timeline.json" `
  --out-dir "work\rebuild" `
  --align-audio `
  --voice-anchor next `
  --online-template "backend\data\timeline-online.json"
```

槽位与重算细节见 [`announcer-voice.md`](announcer-voice.md) 第 4 至 7 节。

生成后校验：

| 项 | 预期 |
| :--- | :--- |
| 时长 | 2429.278 秒 ± 0.1 秒 |
| 采样率 / 声道 / 码率 | 44100 Hz / 2 / 192 kbps CBR |
| 播报区间 | 每段语音内 `max_volume` 高于 −20 dB |
| 槽尾静音 | 槽末 400 毫秒 `max_volume` 低于 −50 dB，谢幕为 300 毫秒 |
| 首题音乐 | 每轮首题音乐起点后 1 秒 `max_volume` 高于 −20 dB |
| 定位 | 40 题 `answerStartMs` 逐题定位误差不超过 0.5 秒 |

## 5. 配置契约

`backend/src/main/resources/application.yml`：

```yaml
app:
  timeline-file: "${APP_TIMELINE_FILE:data/timeline.json}"
  online-timeline-file: "${APP_ONLINE_TIMELINE_FILE:data/timeline-online.json}"
  audio-url: "${APP_AUDIO_URL:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3}"
  audio-url-online: "${APP_AUDIO_URL_ONLINE:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3}"
```

| 配置项 | 缺省 | 说明 |
| :--- | :--- | :--- |
| `app.timeline-file` | `data/timeline.json` | 线下编排 |
| `app.online-timeline-file` | `data/timeline-online.json` | 线上编排；为空时取 `app.timeline-file` |
| `app.audio-url` | OSS 线下对象地址 | 线下外链地址；编排同目录的本地音频可读时优先本地流 |
| `app.audio-url-online` | OSS 线上对象地址 | 线上外链地址；线上编排同目录的本地音频可读时优先本地流；为空时回落 `app.audio-url` |

环境变量：

| 变量 | 取值 |
| :--- | :--- |
| `APP_AUDIO_URL` | 线下对象地址 |
| `APP_AUDIO_URL_ONLINE` | 线上对象地址 |
| `VITE_AUDIO_URL` | 缺省为空；填写后主持人控制台直连该地址 |

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
| `GET /api/events/{eventId}/audio` | 线下音频；本地文件可读时输出本地流并支持 Range，本地缺失且外部地址配置时返回 302 |
| `GET /api/events/{eventId}/audio?mode=online` | 线上音频；本地文件可读时输出线上本地流并支持 Range，本地缺失且外部地址配置时返回 302 |
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
- 主持人控制台在 `VITE_AUDIO_URL` 为空时请求 `/api/events/{eventId}/audio`，由后端按本地优先、外链回落的顺序解析。
- 主持人轮次列表、`round.startMs` 与 `round.endMs` 来自后端线下编排，不读取本地文件。
- 生产前端构建不注入 `VITE_AUDIO_URL`；该变量仅用于临时直连外部对象。

## 8. 测试清单

| 编号 | 用例 | 预期 |
| :--- | :--- | :--- |
| T1 | 两份编排加载 | 启动成功，线上题集 40 题 |
| T2 | 线上题集边界 | 首题 `audioStartMs` 0、`answerStartMs` 1；末题 `audioEndMs` 2244491 |
| T3 | 线下轮次 | 四轮区间 0/562469、562469/1259149、1259149/1871684、1871684/2429278 |
| T4 | 轮首题窗口 | 每轮首题 `audioStartMs` 等于本轮 `startMs`，`NEXT.atMs` 等于 `startMs + 1`；第 2–10 题 `audioStartMs` 等于本题播报段起点 |
| T5 | 交叉校验 | 题目顺序、`eventId`、题库覆盖任一不一致时启动失败 |
| T6 | 线上题集地址 | 本地线上音频可读时 `audioUrl` 为 `/api/events/{eventId}/audio?mode=online`；本地缺失时等于 `app.audio-url-online`，该值为空时等于 `app.audio-url` |
| T7 | 线下音频地址 | 本地线下音频可读时返回 200/206 本地流；本地缺失时返回 302 指向线下对象 |
| T8 | 本地模式 | 两个外部地址均为空时，线上题集 `audioUrl` 为 `/api/events/{eventId}/audio?mode=online` |
| T9 | 单编排兼容 | `app.online-timeline-file` 为空时，线上题集由线下编排生成，行为与单母带部署一致 |
| T10 | 既有用例 | 榜单、预览、锁定、Range 与主持人流程用例保持通过 |

## 9. 部署与回滚

### 9.1 对象

| 对象 | 状态 | 大小 | MD5 |
| :--- | :--- | ---: | :--- |
| `backend/data/event-online.mp3` | 本地新版，纯 BGM | 53,869,130 字节 | `AEE6304CC699D65E65E06DAAAC6650A9` |
| `backend/data/event-offline.mp3` | 本地新版，含播报 | 58,304,095 字节 | `33E02A32F5FBE15FC8D180E83E7BD70E` |
| `bgm/event-online-v2.mp3` | OSS 生产版本 | 53,869,130 字节 | `AEE6304CC699D65E65E06DAAAC6650A9` |
| `bgm/event-offline-v2.mp3` | OSS 生产版本 | 58,304,095 字节 | `33E02A32F5FBE15FC8D180E83E7BD70E` |
| `bgm/event-online-cbr192.mp3` | 旧线上对象，保留 | 62,233,783 字节 | `CF242A2CAF35D8AEB0A13A0AB687BA78` |
| `bgm/event-offline.mp3` | 旧线下对象，保留 | 66,724,510 字节 | `33688E3C8AFB41BC5D898CDAE15E1A48` |
| `bgm/event-demo.mp3` | 保留，用于回滚 | 64,309,541 字节 | `5f429dd0678259341c93d5673e9f5c6d` |

### 9.2 发布顺序

1. 生成 `work/rebuild/event-offline.mp3`，替换 `backend/data/event-offline.mp3`；`backend/data/event-online.mp3` 取新版 `music/bgm-music.mp3`。两者可由 `tools/build_voice_mix.py --install --online-audio music/bgm-music.mp3` 一次写入。
2. 上传两个新音频对象，设置 `Content-Type: audio/mpeg`、`Cache-Control: public, max-age=31536000, immutable`。
3. 备份 `backend/data` 到 `backend/data-backup-<YYYYMMDD-HHMMSS>`。
4. 将 `timeline.json`、`timeline-online.json`、`questions.json` 放入 `shared/data`；该目录不放置音频文件。
5. 设置 `APP_AUDIO_URL`、`APP_AUDIO_URL_ONLINE`。
6. 构建前端，`VITE_AUDIO_URL` 保持为空。
7. 重启服务，按第 11 节验收。

### 9.3 回滚

1. `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 切回 `bgm/event-demo.mp3`，`APP_ONLINE_TIMELINE_FILE` 置空。
2. `shared/data` 整目录还原上一版备份。
3. 以回滚后的对象重建前端并重启服务。

## 10. 已知约束

1. 新版线上音频为 CBR 192 kbps，逐题定位按 3.4 节复测；已发布的 VBR 对象只配套旧版编排。
2. 轮首播报复用第一轮开幕段，第二、三、四轮的语音内容与第一轮相同；开幕词含轮次或规则描述时，现场会听到重复表述。
3. 线下母带总长 40 分 29.278 秒，比纯 BGM 母带长 184.787 秒，主持节奏与轮间安排按此排布。
4. 音频或题目变化时，两份编排必须同时重算；只改一份会导致交叉校验失败或线上、线下题目错位。
5. 线上题集版本由新版 `timeline-online.json` 决定，浏览器中残留的旧版本进度会失效，活动前清理一次本地存储。
6. 线上与线下同时活动时，参与者听纯 BGM、现场听带播报母带，两边内容不同。
7. OSS 对象带 `Cache-Control: public, max-age=31536000, immutable`，浏览器与 CDN 命中长缓存；发布新版音频时使用新对象名并同步 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE`。

## 11. 验收清单

线上：

- [ ] `GET /api/events/jisong/online/questions` 返回 40 题，`audioUrl` 为线上对象地址。
- [ ] 首题 `audioStartMs` 0、`answerStartMs` 1；末题 `audioEndMs` 2244491。
- [ ] 本地 `backend/data/event-online.mp3` 为 2244.491 秒、53,869,130 字节；OSS 对象在发布后与本地文件一致。
- [ ] 40 题逐题定位，首次出声与素材相差不超过 0.5 秒，无超过 0.5 秒的多余静音。
- [ ] 轮次边界处的音频为音乐，不含人声。

线下：

- [ ] 主持人控制台四轮区间为 0/562469、562469/1259149、1259149/1871684、1871684/2429278。
- [ ] 每轮点击开始后首题立即上屏，同时听到轮首播报，随后进入本轮第一题音乐。
- [ ] 每轮首题 `NEXT` 比本轮 `startMs` 晚 1 毫秒；第 2–10 题 `NEXT` 等于本题播报段起点。
- [ ] 每轮第 10 题的播报仍在 `FINISH` 前结束；题间播报与本题音乐之间保留 400 毫秒静音。
- [ ] 母带时长 2429.278 秒 ± 0.1 秒，44.1 kHz、2 声道、192 kbps CBR。

交叉：

- [ ] 两份编排的 40 题顺序一致，`TimelineStore` 启动校验通过。
- [ ] 主持人页面的 `<audio>` 地址为线下对象，线上题集的 `audioUrl` 为线上对象。
- [ ] `shared/data` 中不含音频文件，播放正常。
