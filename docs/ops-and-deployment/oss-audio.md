# 外部音频（阿里云 OSS）

本文定义音频文件存放在阿里云 OSS、由浏览器直接播放的部署方式。应用服务器保留 API、`timeline.json`、`questions.json` 与榜单文件，音频流量不经过应用服务器。

## 1. 适用场景

- 线上模式由每位参与者播放完整音频，并发播放会占用大量服务器上行带宽。
- 音频文件体积较大，放入 OSS 后可按对象独立缓存与版本化。
- 线下模式只有主持人播放音频，OSS 直连同样可用。

## 2. 配置项

后端配置文件 `application.yml`：

```yaml
app:
  audio-url: https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
```

`app.audio-url` 为空时，后端沿用本地音频：`/api/events/{eventId}/audio`。

前端构建变量：

```text
VITE_AUDIO_URL=https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
```

`VITE_AUDIO_URL` 用于主持人控制台。线上参与者的音频地址由后端 `OnlineQuestionSet.audioUrl` 返回。

后端与前端配置指向同一个 OSS 对象。两处地址不一致时，主持人或参与者的播放行为会分叉。

`timeline.json` 的 `audioFile` 保持文件名，例如 `event-demo.mp3`，不写 OSS 地址。外部地址由 `app.audio-url` 提供。

## 3. OSS 对象设置

| 项目 | 值 |
| :--- | :--- |
| 对象路径 | `bgm/event-demo.mp3` |
| 访问权限 | 公共读，或使用可覆盖活动周期的签名 URL |
| Content-Type | `audio/mpeg` |
| Cache-Control | `public, max-age=31536000, immutable` |
| 传输协议 | HTTPS |

对象名带版本后缀，例如 `event-demo-v2.mp3`。音频内容变化时更换对象名，避免浏览器与 CDN 命中旧缓存。

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

### 3.2 域名与备案

- OSS 默认域名支持 HTTPS，可直接使用。
- 自定义 CDN 域名需要完成 ICP 备案并配置证书。
- 页面为 HTTPS 时，音频地址必须为 HTTPS，避免混合内容拦截。

## 4. 上传音频

使用 ossutil：

```bash
ossutil cp backend/data/event-demo.mp3 \
  oss://your-bucket/bgm/event-demo.mp3 \
  --meta Content-Type:audio/mpeg \
  --meta Cache-Control:public,max-age=31536000,immutable
```

也可以在 OSS 控制台上传对象，并设置对象 ACL、Content-Type 与 Cache-Control。

上传后检查：

```bash
curl -I https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
curl -I -H 'Range: bytes=0-1023' https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
```

预期状态码分别为 `200` 与 `206`。

## 5. 发布流程

1. 上传音频到 OSS，确认对象可读。
2. 在 `/etc/bgm-guess/bgm-guess.env` 增加：

   ```text
   APP_AUDIO_URL=https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
   ```

3. 构建前端时设置：

   ```bash
   VITE_AUDIO_URL=https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3 npm run build
   ```

4. 重新构建并发布后端 JAR。
5. 重启 `bgm-guess`。
6. 验证线上题集接口、主持人页面与手机播放。
7. 验证通过后，可从 `/opt/bgm-guess/shared/data` 移除音频文件。移除前保留独立备份。

`/opt/bgm-guess/shared/data` 继续保存 `timeline.json`、`questions.json`、榜单文件与 `leaderboard_summary.txt`。

## 6. 验证项

```bash
curl -sS https://game.himematsu.cn/api/events/demo/online/questions
curl -I https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
curl -I -H 'Range: bytes=0-1023' https://your-bucket.oss-cn-region.aliyuncs.com/bgm/event-demo.mp3
```

- 线上题集响应中的 `audioUrl` 为 OSS 地址。
- 主持人页面 `<audio>` 的 `src` 为 OSS 地址。
- OSS 对象返回 `200`，Range 请求返回 `206`。
- 手机可播放、暂停、拖动进度，切题定位与本地音频一致。
- `/api/events/demo/audio` 在外部音频配置下返回跳转到 OSS 的响应。

## 7. 私有 Bucket

私有 Bucket 需要签名 URL。静态签名 URL 有有效期，过期后主持人或参与者无法播放。活动周期较长时，使用覆盖活动周期的有效期，或由后端按请求生成签名 URL。

当前实现配置单个外部地址，适用于公共读对象或长效签名 URL。动态签名与 STS 需要额外的后端接口与凭证管理。

## 8. 常见故障

| 现象 | 检查 |
| :--- | :--- |
| 403 Forbidden | 对象 ACL、Bucket 权限、签名 URL 是否过期 |
| 404 Not Found | 对象路径、大小写、URL 编码 |
| 能播放但拖动失败 | OSS Range 支持、CORS 暴露 `Content-Range` 与 `Accept-Ranges` |
| 浏览器拦截 | HTTPS 页面引用 HTTP 音频、CORS 来源不匹配 |
| 换了音频仍播放旧内容 | 对象名未变更、CDN 缓存、`Cache-Control` 过长 |
| 主持人有声音、参与者没有 | `app.audio-url` 与 `VITE_AUDIO_URL` 不一致，或线上题集未重新发布 |
| 后端启动仍报音频不可读 | `APP_AUDIO_URL` 未注入，`app.audio-url` 为空 |
