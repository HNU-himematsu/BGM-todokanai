# 部署状态与上线清单

记录日期：2026-10-31

本文记录当前代码、服务器、OSS 与发布流水线的状态，以及上线前的剩余步骤。

## 1. 代码状态

- 前端为 Vue 3 + Vite 7，后端为 Spring Boot 3.5.5，构建目标为 Java 17。
- 音频支持服务器本地文件与 OSS 直连两种模式。
- `app.audio-url` 或 `APP_AUDIO_URL` 非空时，线上题集返回外部音频地址，`/api/events/{eventId}/audio` 返回 302 跳转到该地址。
- `VITE_AUDIO_URL` 非空时，主持人控制台直接播放外部音频地址。
- `app.audio-url` 为空时，后端使用 `/opt/bgm-guess/shared/data` 中的本地音频，行为与原有部署方式一致。
- 后端测试共 18 项，覆盖本地音频 Range、外部音频 URL、线上答题流程与榜单兼容。
- 前端生产构建通过。

前端产物位于 `frontend/dist`，后端产物位于 `backend/target/bgm-guess-backend-0.0.1-SNAPSHOT.jar`。

## 2. 服务器状态

接入前核查结果：

- 操作系统为 Debian 12。
- Nginx 已安装，配置语法检查通过。
- 主站 `himematsu.cn` 与 `www.himematsu.cn` 使用 `/etc/letsencrypt/live/himematsu.cn/` 证书。
- `game.himematsu.cn` 的 `A` 记录指向 `81.71.64.37`。
- Nginx 配置中没有 `server_name game.himematsu.cn` 的独立站点。
- `http://game.himematsu.cn/` 落到 Nginx 默认站点，返回欢迎页。
- `https://game.himematsu.cn/` 落到主站 443 配置，301 跳转到 `https://himematsu.cn/`。
- 主站证书不包含 `game.himematsu.cn`，子域 HTTPS 存在证书名称不匹配。
- 后端 `8080` 未对外提供健康响应，应用尚未部署。

服务器侧的目录、systemd 服务、子域 Nginx 站点与证书均待初始化。

## 3. OSS 状态

目标对象：

```text
oss://<bucket>/bgm/event-demo.mp3
```

待确认项：

| 项目 | 状态 |
| :--- | :--- |
| 对象上传 | 待完成 |
| 对象 URL | 待填写 |
| 访问权限 | 建议公共读 |
| `Content-Type` | `audio/mpeg` |
| `Cache-Control` | `public, max-age=31536000, immutable` |
| CORS 来源 | `https://game.himematsu.cn` |
| CORS 方法 | `GET, HEAD, OPTIONS` |
| CORS 请求头 | `Range` |
| CORS 暴露头 | `Content-Length, Content-Range, Accept-Ranges, ETag` |
| 普通请求 | 预期 `200` |
| Range 请求 | 预期 `206` |
| 手机拖动进度 | 待验收 |

OSS 配置的完整说明见 [`oss-audio.md`](oss-audio.md)。

## 4. 发布流水线状态

- `.github/workflows/ci.yml` 在拉取请求与手动触发时构建前端并验证后端。
- `.github/workflows/deploy.yml` 通过手动触发执行发布或回滚。
- 发布任务使用 `production` environment。
- GitHub Secrets、发布用户、SSH 公钥与 sudoers 规则待配置。
- 服务器目录与 systemd 单元由 `deploy/bootstrap-server.sh` 初始化。
- `main` 推送自动发布在服务器初始化与 Secrets 配置完成后启用。

CI/CD 的完整说明见 [`cicd.md`](cicd.md)。

## 5. 上线顺序

1. 上传音频到 OSS，设置 ACL、Content-Type、Cache-Control 与 CORS。
2. 验证 OSS 对象普通请求返回 `200`，Range 请求返回 `206`。
3. 在服务器执行 `deploy/bootstrap-server.sh`，创建 `bgm`、`deploy` 用户与目录结构。
4. 将 `timeline.json`、`questions.json`、榜单文件放入 `/opt/bgm-guess/shared/data`。音频不再放入该目录。
5. 编辑 `/etc/bgm-guess/bgm-guess.env`，填写 `APP_HOST_PASSWORD` 与 `APP_AUDIO_URL`。
6. 使用 `VITE_PUBLIC_BASE_URL` 与 `VITE_AUDIO_URL` 构建前端。
7. 使用 Java 17 构建后端 JAR。
8. 上传首个 release，切换 `current`，启动 `bgm-guess`。
9. 为 `game.himematsu.cn` 签发证书并启用 `deploy/nginx-game.conf.example`。
10. 配置 GitHub Secrets 与发布用户，手动运行一次 `Deploy`。
11. 完成验收后，按需启用 `main` 推送自动发布。

## 6. 验收清单

- [ ] `https://game.himematsu.cn/` 返回本系统页面。
- [ ] `https://game.himematsu.cn/host` 刷新不出现 404。
- [ ] `https://game.himematsu.cn/offline?event=demo` 进入线下入口。
- [ ] `https://game.himematsu.cn/api/health` 返回健康信息。
- [ ] `https://game.himematsu.cn/api/events/demo/online/questions` 的 `audioUrl` 为 OSS 地址。
- [ ] 主持人页面 `<audio>` 的 `src` 为 OSS 地址。
- [ ] OSS 对象 `HEAD` 返回 `200`。
- [ ] OSS Range 请求返回 `206`。
- [ ] 手机可播放并拖动进度。
- [ ] `/ws` 完成 WebSocket Upgrade。
- [ ] 重启 `bgm-guess` 后榜单记录仍可读取。
- [ ] 移除服务器本地 MP3 后线上题集与播放仍正常。

## 7. 回滚与备份

- 代码回滚通过激活 `/opt/bgm-guess/releases/` 中的历史版本完成。
- 活动数据在修改前备份整个 `/opt/bgm-guess/shared/data`。
- OSS 音频使用版本化对象名，回滚时切换 `APP_AUDIO_URL` 与 `VITE_AUDIO_URL` 并重新发布前端。
- 发布流水线不修改 `/opt/bgm-guess/shared/data`。
