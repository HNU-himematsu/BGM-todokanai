# 部署状态与上线清单

记录日期：2026-11-05

本文记录当前代码、服务器、OSS 与发布流水线的状态，以及上线前的剩余步骤。

## 1. 代码状态

- 前端为 Vue 3 + Vite 7，后端为 Spring Boot 3.5.5，构建目标为 Java 17。
- 生产环境使用 OSS 直连音频；本地联调可通过空值切换到编排目录中的本地音频。
- 线下母带为 `bgm/event-offline.mp3`，由 `app.audio-url` 提供；线上母带为 `姬松活动/bgm-music.mp3`，由 `app.audio-url-online` 提供。
- 线上题集返回 `app.audio-url-online` 的地址；`/api/events/{eventId}/audio` 返回 302 跳转到线下对象，`/api/events/{eventId}/audio?mode=online` 返回 302 跳转到线上对象。
- `VITE_AUDIO_URL` 指向线下对象，主持人控制台直接播放该地址。
- `app.audio-url` 为空时，后端使用线下编排同目录的本地音频；`app.audio-url-online` 为空时依次回落 `app.audio-url` 与本地线上音频。
- 本地联调母带位于 `backend/data/event-offline.mp3`，时长 2780.110 秒，192 kbps CBR；该文件不进入版本库。
- 后端测试共 34 项，覆盖本地音频 Range、外部音频 URL、无本地音频启动、线上答题流程、双母带加载与交叉校验、音频模式跳转与榜单兼容。
- 双母带与双编排的后端实现覆盖配置、加载、交叉校验、音频模式分支与线上题集；配置契约见 [`../data-and-config/dual-master-audio.md`](../data-and-config/dual-master-audio.md) 第 5 节，测试清单见该文档第 8 节。
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

线上对象为纯 BGM 母带，已存在并直接复用：

```text
oss://himematsu/姬松活动/bgm-music.mp3
https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3
```

| 项目 | 状态 |
| :--- | :--- |
| 对象上传 | 已完成，2026-10-31 |
| 文件大小 | 42,274,159 字节 |
| 时长 | 2593.149 秒 |
| 编码 | MP3 VBR，平均约 130 kbps，44.1 kHz / 2 声道 |
| ETag | `AD58949BC8932351DA7062203FC47E2C` |
| 访问权限 | 公共读 |
| `Content-Type` | `audio/mpeg` |
| `Cache-Control` | 未设置，由 OSS 默认值决定 |
| CORS | 未启用；`<audio>` 直接播放与 Range 不受影响 |
| 普通请求 | `200`，已实测 |
| Range 请求 | `206`，已实测 |
| 逐题定位 | 待按 40 题验收 |

线下对象为含四轮轮首播报的成品母带：

```text
oss://himematsu/bgm/event-offline.mp3
https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3
```

| 项目 | 状态 |
| :--- | :--- |
| 对象上传 | 已完成，2026-11-06，分片上传 |
| 文件大小 | 66,724,510 字节 |
| MD5 | `33688E3C8AFB41BC5D898CDAE15E1A48` |
| 时长 | 2780.110 秒 |
| 编码 | MP3 CBR 192 kbps，44.1 kHz / 2 声道 |
| ETag | `88FEAA824926B8E444A3662F575AD210-8` |
| 访问权限 | 公共读 |
| `Content-Type` | `audio/mpeg` |
| `Cache-Control` | `public, max-age=31536000, immutable` |
| 普通请求 / Range | `200` / `206`，已实测 |

回滚对象为单母带形态的历史成品，保留于 OSS：

| 项目 | 状态 |
| :--- | :--- |
| 对象路径 | `oss://himematsu/bgm/event-demo.mp3` |
| 文件大小 | 64,309,541 字节 |
| MD5 | `5f429dd0678259341c93d5673e9f5c6d` |
| 用途 | 双母带回滚时临时指向 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` |

OSS 子账号具备对象上传、读取与列举权限，不具备 `oss:DeleteObject`。删除对象或历史版本通过 OSS 控制台执行，或为子账号授予目标前缀的删除权限。

OSS 配置的完整说明见 [`oss-audio.md`](oss-audio.md)。

## 4. 发布流水线状态

双母带相关配置的代码与流水线取值如下：

- `.github/workflows/ci.yml` 在拉取请求与手动触发时构建前端并验证后端。
- `.github/workflows/deploy.yml` 通过手动触发执行发布或回滚。
- 前端构建注入 `VITE_AUDIO_URL`，缺省指向线下对象 `bgm/event-offline.mp3`。
- 后端 `app.audio-url` 缺省指向线下对象，`app.audio-url-online` 缺省指向线上对象；`APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 可覆盖两个地址。
- 发布任务使用 `production` environment。
- GitHub Secrets、发布用户、SSH 公钥与 sudoers 规则待配置。
- 服务器目录与 systemd 单元由 `deploy/bootstrap-server.sh` 初始化。
- `main` 推送自动发布在服务器初始化与 Secrets 配置完成后启用。

CI/CD 的完整说明见 [`cicd.md`](cicd.md)；不依赖 GitHub Actions 的手工发布流程见 [`manual-deploy-runbook.md`](manual-deploy-runbook.md)。

## 5. 上线顺序

1. 确认线上对象 `姬松活动/bgm-music.mp3` 可读；上传线下对象 `bgm/event-offline.mp3`，设置 ACL、Content-Type 与 Cache-Control。
2. 验证两个 OSS 对象的普通请求返回 `200`，Range 请求返回 `206`。
3. 在服务器执行 `deploy/bootstrap-server.sh`，创建 `bgm`、`deploy` 用户与目录结构。
4. 将 `timeline.json`、`timeline-online.json` 与 `questions.json` 放入 `/opt/bgm-guess/shared/data`。榜单文件由服务首次写入时创建，该目录不放置音频文件。
5. 编辑 `/etc/bgm-guess/bgm-guess.env`，填写 `APP_HOST_PASSWORD`、`APP_AUDIO_URL`、`APP_AUDIO_URL_ONLINE` 与 `APP_ONLINE_TIMELINE_FILE`。
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
- [ ] `https://game.himematsu.cn/api/events/demo/online/questions` 返回 40 题，`audioUrl` 为 `姬松活动/bgm-music.mp3` 的对象地址。
- [ ] 主持人页面 `<audio>` 的 `src` 为 `bgm/event-offline.mp3` 的对象地址。
- [ ] 两个 OSS 对象的 `HEAD` 返回 `200`。
- [ ] 两个 OSS 对象的 Range 请求返回 `206`。
- [ ] 主持人控制台四轮区间为 0/562355、562355/1341690、1341690/2054605、2054605/2780110，每轮开始先播放轮首播报。
- [ ] 线上 40 题逐题定位偏差不超过 0.5 秒，无「倒计时在走、声音空白」的题目。
- [ ] `/opt/bgm-guess/shared/data` 中没有音频文件，播放仍正常。
- [ ] 手机可播放并拖动进度。
- [ ] `/ws` 完成 WebSocket Upgrade。
- [ ] 重启 `bgm-guess` 后榜单记录仍可读取。
- [ ] 移除服务器本地 MP3 后线上题集与播放仍正常。

## 7. 回滚与备份

- 代码回滚通过激活 `/opt/bgm-guess/releases/` 中的历史版本完成。
- 活动数据在修改前备份整个 `/opt/bgm-guess/shared/data`。
- OSS 音频使用版本化对象名，回滚时把 `APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 切回 `bgm/event-demo.mp3`，`APP_ONLINE_TIMELINE_FILE` 置空，同时还原上一版 `timeline.json`，再重新发布前端。
- 发布流水线不修改 `/opt/bgm-guess/shared/data`。
