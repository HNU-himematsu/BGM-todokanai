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
  audio-url: "${APP_AUDIO_URL:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3}"
  audio-url-online: "${APP_AUDIO_URL_ONLINE:https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3}"
```

| 配置项 | 作用对象 | 为空时的行为 |
| :--- | :--- | :--- |
| `app.audio-url` | 主持人控制台、`/api/events/{eventId}/audio` | 输出本地线下音频，入口仍为 `/api/events/{eventId}/audio` |
| `app.audio-url-online` | 线上题集 `OnlineQuestionSet.audioUrl` | 回落 `app.audio-url`；两者均为空时使用 `/api/events/{eventId}/audio?mode=online` |

前端构建变量：

```text
VITE_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
```

`VITE_AUDIO_URL` 只用于主持人控制台。线上参与者的音频地址始终由后端 `OnlineQuestionSet.audioUrl` 返回。

`VITE_AUDIO_URL` 与 `app.audio-url` 指向同一个线下对象。两处不一致时，主持人直接播放与后端跳转会分叉。

`timeline.json` 与 `timeline-online.json` 的 `audioFile` 保持文件名，不写 OSS 地址。外部地址由两个配置项提供。

## 3. OSS 对象设置

| 项目 | 线上对象 | 线下对象 |
| :--- | :--- | :--- |
| Bucket | `himematsu` | `himematsu` |
| Region | `oss-cn-guangzhou` | `oss-cn-guangzhou` |
| 对象路径 | `姬松活动/bgm-music.mp3` | `bgm/event-offline.mp3` |
| 对象 URL | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3` | `https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3` |
| 大小 | 42,274,159 字节 | 66,724,510 字节 |
| 时长 | 2593.149 秒 | 2780.110 秒 |
| 访问权限 | 公共读 | 公共读 |
| Content-Type | `audio/mpeg` | `audio/mpeg` |
| Cache-Control | 未设置 | `public, max-age=31536000, immutable` |
| 传输协议 | HTTPS | HTTPS |

线上对象为纯 BGM 母带，发布时直接复用；线下对象为含播报母带，由 `work/event-offline.mp3` 上传。首次发布使用固定对象名 `bgm/event-offline.mp3`；音频内容变化时上传带版本后缀的新对象，例如 `event-offline-v2.mp3`，并同步切换 `APP_AUDIO_URL` 与 `VITE_AUDIO_URL`，避免浏览器与 CDN 命中旧缓存。

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

线下对象已上传，源文件为 `work/event-offline.mp3`，MD5 为 `33688E3C8AFB41BC5D898CDAE15E1A48`。OSS 记录的对象大小为 66,724,510 字节，与源文件一致。分片上传产生的 ETag 带分片后缀（`88FEAA824926B8E444A3662F575AD210-8`），与 MD5 不相等属于正常现象。

覆盖上传可使用 ossutil：

```bash
ossutil cp work/event-offline.mp3 \
  oss://himematsu/bgm/event-offline.mp3 \
  --meta Content-Type:audio/mpeg \
  --meta Cache-Control:public,max-age=31536000,immutable
```

线上对象 `姬松活动/bgm-music.mp3` 直接复用，不上传、不重编码。

也可以在 OSS 控制台上传对象，并设置对象 ACL、Content-Type 与 Cache-Control。

上传后检查：

```bash
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
```

预期状态码分别为 `200` 与 `206`。

### 4.1 对象管理权限

当前 OSS 子账号具备 `PutObject`、`GetObject` 与 `ListObjects` 权限，不具备 `oss:DeleteObject`。删除对象有两种方式：

- 在 OSS 控制台删除对象或对象历史版本。
- 为子账号授予目标前缀的 `oss:DeleteObject` 权限后使用 `ossutil rm`。

对象覆盖上传不需要删除权限。需要保留回滚能力时，使用新对象名上传，再切换 `app.audio-url` 与 `VITE_AUDIO_URL`。

## 5. 发布流程

1. 上传线下母带到 OSS，确认对象可读；确认线上对象 `姬松活动/bgm-music.mp3` 返回 `200`。
2. 在 `/etc/bgm-guess/bgm-guess.env` 增加：

   ```text
   APP_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
   APP_AUDIO_URL_ONLINE=https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
   ```

3. 构建前端时设置：

   ```bash
   VITE_AUDIO_URL=https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3 npm run build
   ```

4. 重新构建并发布后端 JAR。
5. 重启 `bgm-guess`。
6. 验证线上题集接口、主持人页面与手机播放。
7. 确认 `/opt/bgm-guess/shared/data` 中没有音频文件。

`/opt/bgm-guess/shared/data` 只保存两份编排、`questions.json`、榜单文件与 `leaderboard_summary.txt`。音频的唯一运行期来源是 OSS 对象。

## 6. 验证项

```bash
curl -sS https://game.himematsu.cn/api/events/demo/online/questions
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
curl -I -H 'Range: bytes=0-1023' https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
curl -I https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
```

- 线上题集响应中的 `audioUrl` 为线上对象地址，长度 42,274,159。
- 主持人页面 `<audio>` 的 `src` 为线下对象地址。
- 两个对象均返回 `200`，Range 请求返回 `206`。
- 手机可播放、暂停、拖动进度，切题定位与本地音频一致。
- `/api/events/demo/audio` 在外部音频配置下返回跳转到线下对象的响应。
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
| 主持人有声音、参与者没有 | `app.audio-url` 与 `VITE_AUDIO_URL` 不一致，或线上题集未重新发布 |
| 线上听到主持人播报 | `app.audio-url-online` 仍指向线下对象 |
| 线下没有轮首播报 | `timeline.json` 与 `event-offline.mp3` 不匹配，或前端仍指向旧对象 |
| 后端启动仍报音频不可读 | `APP_AUDIO_URL` 未注入，`app.audio-url` 为空 |
