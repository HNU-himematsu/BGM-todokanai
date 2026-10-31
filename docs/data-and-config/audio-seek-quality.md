# 音频可定位性规格与切题静音修复

## 1. 文档用途

本文定义完整音频文件的可定位性（seek 落点精度）要求、`backend/data/event-demo.mp3` 的实测结论、修复方案与验收指标。

适用场景：

- 线上模式点击「下一题」后，音频定位到下一题 `answerStartMs`。
- 线下主持人开始播放、拖动进度时，音频定位到轮次区间。
- 新素材导入时，判断音频是否满足单文件编排的可定位性要求。

音频与编排的文件契约见 [`timeline-spec.md`](timeline-spec.md)；姬松活动的素材接入流程见 [`jisong-event-integration.md`](jisong-event-integration.md)。

## 2. 症状与判定

**症状**：线上点击「下一题」后，新题正常上屏、倒计时正常递减，声音持续空白 10～40 秒；同一份题集中另有部分题目点击后立即出声。

**判定方法**（同时满足下列条件时按本文处理）：

| 观测项 | 判定值 |
| :--- | :--- |
| `audio.paused` | `false` |
| `audio.currentTime` | 按一倍速递增 |
| `audio.readyState` | 4 |
| `audio.networkState` | 1（IDLE）或 2（LOADING） |
| `waiting` / `stalled` 事件 | 不触发 |
| 播放输出电平 | -180 dB（数字静音）持续 10 秒以上 |

**已排除的原因**：

- 时间戳：40 题的 `answerStartMs` 之后 3 秒内均检出音乐，峰值 -0.1～-11.0 dB，时间轴未指进静音。
- 服务端 Range（原始 VBR 文件）：`GET /api/events/demo/audio` 对 `Range: bytes=33027057-` 返回 `206`、`Content-Range: bytes 33027057-42272878/42272879`、`Content-Length: 9245822`，区间未被截断。
- 前端等待逻辑：`seeked` 事件在 26～40 毫秒内返回，`readyState` 达到 4，`seekToReady()` 判定成功。

## 3. 定位机制

- 线上倒计时取音频播放位置。`OnlinePlayPanel.vue` 的 `tick()` 每 200 毫秒调用 `syncClock()`，`useOnlineStore().updateClock(position, duration)` 以 `position` 计算 `questionRemainingMs`。播放位置推进，倒计时即推进，与是否有可听内容无关。
- 切题定位。`goToNextQuestion()` 把 `currentTime` 设为目标题 `answerStartMs`，等待 `seeked` 与 `readyState >= 3` 后继续播放。
- 浏览器把 `currentTime` 换算成字节偏移。MP3 的换算依据是 Xing/Info 帧头的 TOC（100 项，每项对应百分之一时长）与平均码率。TOC 精度与文件中码率的一致性共同决定落点误差；码率差异越大，TOC 的 256 级量化换算成时间的误差越大。

## 4. 素材实测

测量对象：VBR 混拼原始文件，备份于 `work/event-demo-vbr-original-20261030.mp3`（42,272,879 字节）。

浏览器：Chrome 156.0.8078.76，headless，`captureStream()` 接 `AnalyserNode` 采样播放输出电平。

### 4.1 容器结构

| 项目 | 实测值 |
| :--- | :--- |
| 时长 | 2593.032 秒 |
| MP3 帧数 | 99266 |
| 帧数据总字节 | 42272799 |
| 平均码率 | 130 kbps |
| 首帧码率 | 128 kbps |
| 采样率 / 声道 | 44100 Hz / 2 |
| ID3v2 | 80 字节，位于文件头 |
| Xing 头 | 第一帧内偏移 116，`frames=99265`、`bytes=42272799`、100 项 TOC 齐全 |

### 4.2 码率分布

| 码率 | 帧数 | 占比 |
| :--- | ---: | ---: |
| 32 kbps | 35504 | 35.8% |
| 192 kbps | 34670 | 34.9% |
| 160 kbps | 19275 | 19.4% |
| 224 kbps | 4772 | 4.8% |
| 256 / 320 / 128 kbps | 1903 / 746 / 2009 | 4.7% |
| 56～112 kbps | 387 | 0.4% |

同文件内最低码率与最高码率相差 10 倍，32 kbps 段为数字静音填充。

### 4.3 静音垫

| 项目 | 实测值 |
| :--- | :--- |
| 数字静音段数（阈值 -60 dB，短于 1 秒不计） | 52 |
| 超过 5 秒的静音段 | 41 |
| 超过 5 秒静音合计 | 927 秒（占全长 35.7%） |
| 最长静音段 | 41.4 秒 |

典型区间（秒）：36.0～46.4、95.0～105.0、148.0～158.0、183.0～193.0、226.0～236.0、286.0～296.0、326.0～336.0、391.0～401.0、1680.5～1709.9、1745.2～1785.0。

静音垫位于每题音频片段的尾部，即 `answerEndMs` 之后到下一题 `audioStartMs` 之间。

### 4.4 TOC 精度

按 TOC 第 i 项推出的字节位置换算回时间，与 `i% × 总时长` 比较：

| 位置 | 目标时间（秒） | TOC 字节 | 帧表字节 | 时间偏差（秒） |
| :--- | ---: | ---: | ---: | ---: |
| 5% | 129.7 | 2642129 | 2605797 | +1.5 |
| 10% | 259.3 | 5119051 | 5151308 | -1.4 |
| 40% | 1037.2 | 18989813 | 19105661 | -4.7 |
| 50% | 1296.5 | 23778529 | 23888988 | -4.9 |
| 60% | 1555.8 | 28071860 | 28219970 | -35.9 |
| 66% | 1711.4 | 30053398 | 30168636 | -22.6 |
| 90% | 2333.8 | 38640060 | 38743824 | -6.1 |

Xing 头与 TOC 均存在，TOC 数值与帧表不一致，最大偏差 35.9 秒。

### 4.5 浏览器落点实测

定位到 `1710` 秒（q-28 的 `answerStartMs`）后按 0.5 秒采样输出电平：

| 浏览器时间（秒） | 输出电平 |
| :--- | ---: |
| 1710.4～1729.4 | -180 dB |
| 1729.5～1764.1 | -15.9～-10.2 dB |
| 1764.6 起 | -180 dB |

文件在 1710.0～1745.0 为音乐（35.0 秒），1745.2～1785.0 为静音。浏览器播出的音乐段长 34.6 秒，是同一段音乐整体延后 19.5 秒，落点约 1690.5 秒，处于 1680.5～1709.9 的静音垫内。

### 4.6 A/B 验证

定位点：46、105、650、750、1011、1359、1480、1635、1710、1980、2169、2401、2470、2518 秒，每点定位后采样 4 秒。

| 素材 | 定位后 4 秒内全静音的点数 |
| :--- | ---: |
| 原文件 | 10 / 14 |
| 仅重写 TOC（不重编码） | 1 / 14（650 秒处仍有 10 秒静音） |
| CBR 192 kbps 重编码 | 0 / 14（650 秒处 1 秒静音来自素材自带前导静音） |

### 4.7 结论

1. 时间轴、题集与 Range 均正确，空白来自音频定位落点。
2. 文件由 32 kbps 静音垫与 160～192 kbps 音乐混拼，字节与时间的对应关系严重非线性；TOC 的 100 项 × 256 级量化在该码率分布下等效 ±20 秒粒度，且文件内 TOC 数值本身与帧表不一致（最大 35.9 秒）。
3. 浏览器按 TOC 估算字节位置，落点最多提前约 20 秒，落入每题尾部 10～41 秒的静音垫，表现为切题成功、倒计时正常、声音空白。
4. 落点偏差随题目位置波动。落在音乐段时立即出声（实为错位片段），落在静音垫时长时间无声，形成「有的正常有的空白」。
5. 线下主持人「下一题」同时推进题目、服务端时间基准与音频播放位置；音频定位到新题 `audioStartMs`，避免停留在上一题静音垫。

### 4.8 CBR 成品实测

测量对象：由 `music/bgm-music.mp3` 单次编码的 `backend/data/event-demo.mp3`（62,233,783 字节）。

| 项目 | 实测值 |
| :--- | :--- |
| 时长 | 容器 2593.000 秒；帧表 2593.071 秒 |
| 帧数 | 99266 |
| 帧数据总字节 | 62233703 |
| 码率 | 全部帧 192 kbps，极差 1.0 倍，平均 192 kbps |
| 采样率 / 声道 | 44100 Hz / 2 |
| ID3v2 | 80 字节 |
| Xing 头 | `Info` 帧，`frames=99265`、`bytes=62233703`、100 项 TOC |
| TOC 偏差 | 字节位置与帧表最大偏差 1.24 个量化级（约 12.6 秒） |
| 静音垫 | 44 段（-60 dB、0.5 秒阈值），最短 0.68 秒、最长 41.32 秒，素材自带静音垫全部保留 |
| 题尾静音 | 每题音乐结束至 `answerEndMs` 为 6.9～37.0 秒，至 `audioEndMs` 为 9.9～40.0 秒；1.0 秒上限由方案 B 达成 |
| 逐题定位 | 40 题定位 `answerStartMs` 后，首次出声时刻与素材实际出声时刻最大偏差 0.139 秒 |

定位测量方法：headless Chrome 经 CDP 逐题设置 `currentTime`，`seeked` 后用 `captureStream()` 接 `AnalyserNode`，每 100 毫秒记录 `[currentTime, 电平]`，采样 6 秒；素材侧以 `silencedetect=noise=-30dB:d=0.2` 给出每题的首次出声时刻。电平判据取 -30 dB，避开 8 位时域采样在 -48 dB 附近的量化底噪。

## 5. 目标状态（素材规格）

| 指标 | 要求 | 适用方案 |
| :--- | :--- | :--- |
| 码率 | CBR，或全文件最高与最低码率之比不超过 2 | A |
| 字节与时间的对应关系 | 线性，误差不超过 0.5 秒 | A |
| TOC | 与帧表一致；100 项 × 8 位 TOC 在 2593 秒全长上每级约 10.1 秒，字节位置偏差不超过 1.3 级，落点精度以定位实测为准 | A |
| 题间静音 | 每题音频片段尾部静音不超过 1.0 秒 | B |
| 定位效果 | 定位到任一 `answerStartMs` 后，出声时刻与该时刻素材内容一致，偏差不超过 0.5 秒 | A、B |

## 6. 修复方案

### 6.1 方案 A：CBR 重编码（首选）

```powershell
ffmpeg -y -i "music\bgm-music.mp3" -c:a libmp3lame -b:a 192k -ar 44100 -ac 2 -t 2593.000 "work\event-demo-cbr192.mp3"
```

- 输入取 `music/bgm-music.mp3`，即题集混拼前的源素材；命令单次编码直出成品，`backend/data/event-demo.mp3` 与 `work/event-demo-cbr192.mp3` 之间不经过二次编码。
- `-t 2593.000` 把成品裁到编排末题 `audioEndMs = 2593000`；源素材时长 2593.149 秒，末段 149 毫秒不入成品。
- 体积：约 62 MB（192 kbps × 2593 秒），原文件约 42 MB。
- 时长：容器时长 2593.000 秒，帧表 2593.071 秒（99266 帧），与编排末题 `audioEndMs` 相差 71 毫秒，满足 100 毫秒容差。
- 代价：相对源素材的一次有损编码。
- 收益：字节与时间的对应关系线性，`timeline.json`、题库与前端保持现状。

### 6.2 方案 B：裁掉题尾静音并重算时间轴

1. 以 `silencedetect` 列出每题片段内的静音起点。
2. 把每题片段尾部静音裁到 0.8 秒。
3. 依次重算 `audioStartMs`、`audioEndMs`、`answerStartMs`、`answerEndMs`、`round.startMs`、`round.endMs`，以及 `LOAD`、`NEXT`、`LOCK`、`REVEAL`、`FINISH` 的 `atMs`。
4. 时长变化后重新生成线上题集与题集版本号。

- 收益：缩短 927 秒（35.7%），声源体积与加载时间同步下降，定位落点风险消除。
- 代价：整条时间轴重算，需重跑线下联调与 40 题映射核对。
- 与方案 A 叠加时，先裁静音再编码，体积约为 49 MB。

### 6.3 方案 C：每题独立音频

编排与接口增加题级音频文件字段，切题只替换 `audio.src`，播放从 0 秒开始，不做定位；后端按题返回音频。

- 收益：彻底规避定位精度问题，题间静音不再进入音源。
- 代价：后端接口、前端加载与预加载逻辑、素材交付方式同步改造，改动面最大。

### 6.4 方案 D：只重写 TOC

按帧表重算 100 项 TOC 并写回 Xing 头，其余字节不动。实测 650 秒处仍有 10 秒静音：256 级量化在 32 kbps 静音段等效 ±20 秒，单靠 100 项 TOC 无法达到 0.5 秒指标。该方案只作过渡手段，用于无法重编码的场合。

## 7. 实施步骤（方案 A）

1. 备份 `backend/data/event-demo.mp3` 与 `backend/data/timeline.json` 到 `work/`。
2. 执行 6.1 节命令，由 `music/bgm-music.mp3` 生成 `work/event-demo-cbr192.mp3`。
3. 用 9.1 节脚本校验：帧数、时长、码率极差、TOC 误差。
4. 按 9.3 节方法逐题定位，覆盖全部 40 题的 `answerStartMs`，确认出声时刻与素材内容一致。
5. 替换文件并重启后端，确认 `GET /api/events/demo/audio` 返回 `206`、`Content-Range` 覆盖完整区间、`Accept-Ranges: bytes`。
6. 按第 8 节逐项验收。

## 8. 验收指标

1. 素材自检（方案 A）：码率极差不超过 2 倍；TOC 与帧表一致，字节位置偏差不超过 1.3 个量化级；每题尾部静音上限 1.0 秒属于方案 B 指标，方案 A 保留素材自带静音垫。
2. 逐题定位：40 题分别定位到 `answerStartMs`，采样 6 秒；浏览器首次出声时刻与素材在该时刻的实际出声时刻相差不超过 0.5 秒，且不出现超过 0.5 秒的多余连续静音。
3. 线上流程：完整走完 40 题，无「倒计时在走、声音空白」的题目。
4. 线下流程：主持人开始播放、逐题「下一题」、轮次切换三种操作均即时出声；手动下一题后音频位置与新题起点一致。
5. 服务端：`Range` 请求返回 `206`，`Content-Range` 覆盖请求区间，`Accept-Ranges: bytes`。

## 9. 诊断复现

### 9.1 素材自检脚本

```python
import bisect
import struct
import sys

path = sys.argv[1]
data = open(path, "rb").read()
size = len(data)
BITRATE = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320]

offset = 0
if data[:3] == b"ID3":
    offset = 10 + (((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14)
                   | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F))

times, offsets, rates = [], [], []
time = 0.0
while offset < size - 4 and data[offset] == 0xFF and (data[offset + 1] & 0xE0) == 0xE0:
    head = data[offset + 2]
    rate = BITRATE[(head >> 4) & 0x0F] * 1000
    sample_rate = [44100, 48000, 32000][(head >> 2) & 0x03]
    length = int(1152 / 8 * rate / sample_rate) + ((head >> 1) & 1)
    times.append(time)
    offsets.append(offset)
    rates.append(rate)
    time += 1152 / sample_rate
    offset += length

duration = time
stream = size - offsets[0]
print("frames", len(times), "duration", round(duration, 3),
      "avg_kbps", round(size * 8 / duration / 1000))
print("kbps_min_max", min(rates) // 1000, max(rates) // 1000,
      "ratio", round(max(rates) / min(rates), 2))

side = 17 if data[offsets[0] + 3] >> 6 == 3 else 32
xing = offsets[0] + 4 + side
if data[xing:xing + 4] in (b"Xing", b"Info"):
    flags = struct.unpack(">I", data[xing + 4:xing + 8])[0]
    cursor = xing + 8 + (4 if flags & 1 else 0) + (4 if flags & 2 else 0)
    toc = data[cursor:cursor + 100] if flags & 4 else b""
    worst = 0.0
    for index in range(1, 100):
        target = index / 100 * duration
        byte = int(toc[index] / 256 * stream) + offsets[0]
        worst = max(worst, abs(times[bisect.bisect_right(offsets, byte) - 1] - target))
    print("toc_items", len(toc), "toc_max_error_s", round(worst, 2))
else:
    print("no_xing_header")
```

### 9.2 静音统计

```powershell
ffmpeg -hide_banner -i "backend\data\event-demo.mp3" -af "silencedetect=noise=-60dB:d=1" -f null NUL
```

### 9.3 浏览器落点采样

以 `--headless=new --remote-debugging-port=9333 --autoplay-policy=no-user-gesture-required` 启动 Chrome，页面与音频地址保持同源，经 CDP `Runtime.evaluate` 执行：

```js
const a = document.createElement('audio');
a.preload = 'auto';
a.src = 'http://127.0.0.1:8080/api/events/demo/audio';
document.body.appendChild(a);
await new Promise((r) => a.addEventListener('loadedmetadata', r, { once: true }));

const ctx = new AudioContext();
await ctx.resume();
const src = ctx.createMediaStreamSource(a.captureStream());
const an = ctx.createAnalyser();
an.fftSize = 2048;
src.connect(an);
const buf = new Uint8Array(2048);
window.__db = () => {
  an.getByteTimeDomainData(buf);
  let sum = 0;
  for (const value of buf) {
    const sample = (value - 128) / 128;
    sum += sample * sample;
  }
  return +(20 * Math.log10(Math.max(Math.sqrt(sum / 2048), 1e-9))).toFixed(1);
};

a.currentTime = 1710;
a.play();
// 每 250 毫秒读取一次 [a.currentTime, window.__db()]
```

约束：

- 页面与音频地址同源，`captureStream()` 对跨源媒体抛 `SecurityError`。
- `createMediaElementSource` 在 headless 下输出恒为 0，采样必须走 `captureStream()`。
- 采样点为 `-180 dB` 表示数字静音，低于 -60 dB 即视为无声。

### 9.4 时间戳与内容对齐

```powershell
ffmpeg -hide_banner -ss 1710 -t 3 -i "backend\data\event-demo.mp3" -af volumedetect -f null NUL
```

`max_volume` 高于 -20 dB 表示该时刻有音乐。

## 10. 回滚

方案 A 不改动 `timeline.json` 与题库，回滚时把 `work/event-demo-vbr-original-20261030.mp3` 换回 `backend/data/event-demo.mp3` 并重启后端。方案 B 同时改动音频与编排，回滚需同时恢复 `timeline.json`、`questions.json` 与线上题集版本。

## 11. 关联文档

下列文档与本文口径一致：

| 文档 | 对应内容 |
| :--- | :--- |
| [`jisong-event-integration.md`](jisong-event-integration.md) | 4.1 的可定位性要求、4.3 的码率与 TOC 校验、11 的定位偏差约束 |
| [`timeline-spec.md`](timeline-spec.md) | 7.4 的定位精度与题尾静音上限 |
| [`troubleshooting.md`](../ops-and-deployment/troubleshooting.md) | 4.6「切题后长时间无声」 |
| [`docs/README.md`](../README.md) | 数据与配置篇的本文目录项 |
