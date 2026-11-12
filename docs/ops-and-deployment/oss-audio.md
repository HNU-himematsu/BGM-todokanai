# 外部音频（阿里云 OSS）

本文定义音频文件存放在阿里云 OSS、由浏览器直接播放的部署方式。应用服务器保留 API、`timeline.json`、`questions.json` 与榜单文件，音频流量不经过应用服务器。

## 1. 适用场景

- 线上模式由每位参与者播放完整音频，并发播放会占用大量服务器上行带宽。
- 音频文件体积较大，放入 OSS 后可按对象独立缓存与版本化。
- 线下模式只有主持人播放音频，OSS 直连同样可用。
- 线上与线下使用两个对象：线上为纯 BGM 母带，线下为含播报母带。

## 2. 配置项

后端配置文件 `application.yml`：

```yaml
app:
  audio-url: "${APP_AUDIO_URL:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3}"
  audio-url-online: "${APP_AUDIO_URL_ONLINE:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3}"
```

| 配置项 | 作用对象 | 解析顺序 |
| :--- | :--- | :--- |
| `app.audio-url` | 主持人控制台、`/api/events/{eventId}/audio` | 编排同目录的本地音频可读时使用 `/api/events/{eventId}/audio` 本地流；否则使用该外部地址；两者都不可用时接口返回素材错误 |
| `app.audio-url-online` | 线上题集 `OnlineQuestionSet.audioUrl` | 线上编排同目录的本地音频可读时使用 `/api/events/{eventId}/audio?mode=online` 本地流；否则使用该外部地址；为空时回落 `app.audio-url`；三者都不可用时接口返回素材错误 |

服务端目录中不存在音频文件时，接口使用两个外部地址，适用于不含音频文件的部署形态。

前端构建变量：

```text
# 缺省留空，主持人控制台请求 /api/events/{eventId}/audio
VITE_AUDIO_URL=
```

`VITE_AUDIO_URL` 只用于主持人控制台。留空时主持人控制台请求 `/api/events/{eventId}/audio`，音频来源由后端按本地优先、外链回落的顺序解析。填写该值时主持人控制台直接播放该地址，绕过后端解析。线上参与者的音频地址始终由后端 `OnlineQuestionSet.audioUrl` 返回。

`timeline.json` 与 `timeline-online.json` 的 `audioFile` 保持文件名，不写 OSS 地址。外部地址由两个配置项提供。

## 3. OSS 对象设置

| 项目 | 线上对象 | 线下对象 |
| :--- | :--- | :--- |
| Bucket | `himematsu` | `himematsu` |
| Region | `oss-cn-guangzhou` | `oss-cn-guangzhou` |
| 对象路径 | `bgm/event-online-v2.mp3` | `bgm/event-offline-v2.mp3` |
| 对象 URL | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3` | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3` |
| 大小 | 53,869,130 字节 | 58,304,095 字节 |
| 时长 | 2244.491 秒 | 2429.278 秒 |
| MD5 | `AEE6304CC699D65E65E06DAAAC6650A9` | `33E02A32F5FBE15FC8D180E83E7BD70E` |
| 访问权限 | 公共读 | 公共读 |
| Content-Type | `audio/mpeg` | `audio/mpeg` |
| Cache-Control | `public, max-age=31536000, immutable` | `public, max-age=31536000, immutable` |
| 传输协议 | HTTPS | HTTPS |

线上对象为纯 BGM 母带，源文件为 `music/bgm-music.mp3`；线下对象为含播报母带，源文件为 `backend/data/event-offline.mp3`。两个对象均使用 MP3 CBR 192 kbps、44.1 kHz、双声道编码。对象名带 `-v2` 版本后缀，避免浏览器与 CDN 命中历史缓存。

表中对象于 2026-11-12 分片上传。生产环境的 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 指向 `bgm/event-offline-v2.mp3` 与 `bgm/event-online-v2.mp3`；音频对象与对应编排在同一发布步骤切换。

### 3.1 CORS

OSS 跨域规则：

| 项目 | 值 |
| :--- | :--- |
| 来源 | `https://game.himematsu.cn` |
| 方法 | `GET`、`HEAD`、`OPTIONS` |
| 允许请求头 | `Range` |
| 暴露响应头 | `Content-Length`、`Content-Range`、`Accept-Ranges`、`ETag` |
| 缓存时间 | `600` 秒 |

浏览器拖动进度条依赖 Range 请求。OSS 返回 `206 Partial Content` 与 `Content-Range` 时，音频定位正常。

当前 Bucket 未启用 CORS。`<audio>` 直接播放与 Range 拖动不依赖 CORS；页面若通过 WebAudio、canvas 采样或 `fetch` 读取音频响应头，需要在 OSS 控制台按本节规则启用 CORS。

### 3.2 域名与备案

- OSS 默认域名支持 HTTPS，可直接使用。
- 自定义 CDN 域名需要完成 ICP 备案并配置证书。
- 页面为 HTTPS 时，音频地址必须为 HTTPS，避免混合内容拦截。

## 4. 上传音频

`bgm/event-offline-v2.mp3` 与 `bgm/event-online-v2.mp3` 于 2026-11-12 分片上传。匿名 `HEAD` 请求返回 `200`，`Content-Length` 与本地文件一致；Range `bytes=0-1023` 返回 `206`；完整下载后的 MD5 与本地文件一致。分片上传产生的 ETag 带分片后缀，与 MD5 不相等属于正常现象。

对象校验值：

| 对象 | ETag | MD5 |
| :--- | :--- | :--- |
| `bgm/event-offline-v2.mp3` | `17DF38F2D7E0255C67B181C102E19EF2-7` | `33E02A32F5FBE15FC8D180E83E7BD70E` |
| `bgm/event-online-v2.mp3` | `E8C7D0FFB3BA42849080333C3D48AA39-7` | `AEE6304CC699D65E65E06DAAAC6650A9` |

覆盖上传可使用 ossutil：

```bash
ossutil cp backend/data/event-offline.mp3 \
  oss://himematsu/bgm/event-offline-v2.mp3 \
  --meta Content-Type:audio/mpeg \
  --meta Cache-Control:public,max-age=31536000,immutable

ossutil cp backend/data/event-online.mp3 \
  oss://himematsu/bgm/event-online-v2.mp3 \
  --meta Content-Type:audio/mpeg \
  --meta Cache-Control:public,max-age=31536000,immutable
```

版本化对象上传后，把 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 切到新对象名。

也可以在 OSS 控制台上传对象，并设置对象 ACL、Content-Type 与 Cache-Control。

上传后检查：

```bash
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3
```

预期状态码分别为 `200` 与 `206`。

### 4.1 对象管理权限

当前 OSS 子账号具备 `PutObject`、`GetObject` 与 `ListObjects` 权限，不具备 `oss:DeleteObject`。删除对象有两种方式：

- 在 OSS 控制台删除对象或对象历史版本。
- 为子账号授予目标前缀的 `oss:DeleteObject` 权限后使用 `ossutil rm`。

对象覆盖上传不需要删除权限。需要保留回滚能力时，使用新对象名上传，再切换 `app.audio-url`。

## 5. 发布流程

1. 上传线下母带到 OSS，确认对象可读；确认线上对象 `bgm/event-online-v2.mp3` 返回 `200`。
2. 在 `/etc/bgm-guess/bgm-guess.env` 增加：

   ```text
   APP_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
   APP_AUDIO_URL_ONLINE=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3
   ```

3. 构建前端。`VITE_AUDIO_URL` 保持为空，主持人控制台通过 `/api/events/{eventId}/audio` 取值。

4. 重新构建并发布后端 JAR。
5. 重启 `bgm-guess`。
6. 验证线上题集接口、主持人页面与手机播放。
7. 确认 `/opt/bgm-guess/shared/data` 中没有音频文件。

`/opt/bgm-guess/shared/data` 只保存两份编排、`questions.json`、榜单文件与 `leaderboard_summary.txt`。音频的唯一运行期来源是 OSS 对象。

## 6. 验证项

```bash
curl -sS https://game.himematsu.cn/api/events/jisong/online/questions
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline-v2.mp3
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-v2.mp3
```

- 线上题集响应中的 `audioUrl` 在服务端没有本地音频时为线上对象地址，长度 53,869,130。
- 主持人页面 `<audio>` 的 `src` 为 `/api/events/jisong/audio`。
- 两个对象均返回 `200`，Range 请求返回 `206`。
- 手机可播放、暂停、拖动进度，切题定位与本地音频一致。
- `/api/events/jisong/audio` 在服务端没有本地音频时返回跳转到线下对象的响应。
- `/opt/bgm-guess/shared/data` 中没有音频文件，播放仍正常。

## 7. 私有 Bucket

私有 Bucket 需要签名 URL。静态签名 URL 有有效期，过期后主持人或参与者无法播放。活动周期较长时，使用覆盖活动周期的有效期，或由后端按请求生成签名 URL。

当前实现支持两个外部地址，适用于公共读对象或长效签名 URL。动态签名与 STS 需要额外的后端接口与凭证管理。

## 8. 常见故障

| 现象 | 检查 |
| :--- | :--- |
| 403 Forbidden | 对象 ACL、Bucket 权限、签名 URL 是否过期 |
| 404 Not Found | 对象路径、大小写、URL 编码 |
| 能播放但拖动失败 | OSS Range 支持、CORS 暴露 `Content-Range` 与 `Accept-Ranges` |
| 浏览器拦截 | HTTPS 页面引用 HTTP 音频、CORS 来源不匹配 |
| 换了音频仍播放旧内容 | 对象名未变更、CDN 缓存、`Cache-Control` 过长 |
| 主持人有声音、参与者没有 | `app.audio-url-online` 指向的对象不可读，或线上题集未重新发布 |
| 线上听到主持人播报 | `app.audio-url-online` 仍指向线下对象 |
| 线下没有轮首播报 | `timeline.json` 与所播放母带不是同一版本 |
| 后端启动仍报音频不可读 | 本地音频不存在且 `APP_AUDIO_URL` 未注入，`app.audio-url` 为空 |
