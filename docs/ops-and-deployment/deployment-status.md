# 部署状态与上线清单

记录日期：2026-11-12

本文记录当前代码、服务器、OSS 与发布流水线的状态，以及上线前的剩余步骤。

## 1. 代码状态

- 前端为 Vue 3 + Vite 7，后端为 Spring Boot 3.5.5，构建目标为 Java 17。
- 编排同目录的本地音频可读时优先以 Range 流输出；服务端没有本地音频时使用 OSS 对象。
- 线下母带为 `bgm/event-offline.mp3`，由 `app.audio-url` 提供；线上母带为 `bgm/event-online-cbr192.mp3`，由 `app.audio-url-online` 提供；本地联调分别读取 `backend/data/event-offline.mp3` 与 `backend/data/event-online.mp3`。
- 本地音频可读时，线上题集 `audioUrl` 为 `/api/events/{eventId}/audio?mode=online`，`/api/events/{eventId}/audio` 输出线下本地流。本地音频缺失时，题集 `audioUrl` 取 `app.audio-url-online`，两个音频接口分别返回 302 跳转到线下与线上对象。
- `VITE_AUDIO_URL` 缺省为空，主持人控制台请求 `/api/events/{eventId}/audio` 并由后端解析音频来源；填写该值时主持人控制台直接播放该地址。
- `app.audio-url` 与 `app.audio-url-online` 为空时，后端只使用编排同目录的本地音频；本地文件缺失时音频接口返回素材错误。
- 本地联调母带位于 `backend/data/event-offline.mp3`，时长 2429.278 秒，58,304,095 字节，MD5 `33E02A32F5FBE15FC8D180E83E7BD70E`，192 kbps CBR；该文件不进入版本库。
- 本地线上母带位于 `backend/data/event-online.mp3`，时长 2244.491 秒，53,869,130 字节，MD5 `AEE6304CC699D65E65E06DAAAC6650A9`，192 kbps CBR，内容与 `music/bgm-music.mp3` 相同。
- 后端测试共 54 项，覆盖本地音频 Range、本地优先与外链回落、无本地音频启动、线上答题流程、双母带加载与交叉校验、音频模式分支、昵称认领与冲突处理以及榜单兼容。
- 双母带与双编排的后端实现覆盖配置、加载、交叉校验、音频模式分支与线上题集；配置契约见 [`../data-and-config/dual-master-audio.md`](../data-and-config/dual-master-audio.md) 第 5 节，测试清单见该文档第 8 节。
- 前端生产构建通过。

前端产物位于 `frontend/dist`，后端产物位于 `backend/target/bgm-guess-backend-0.0.1-SNAPSHOT.jar`。

## 2. 服务器状态

线上服务器状态（2026-11-11）：

- 操作系统为 Debian 13（trixie），SSH 端口 `33`，密钥登录。
- 运行时为 OpenJDK 21，满足 Java 17 字节码要求。
- `bgm-guess` 由 systemd 管理并设为开机自启，后端监听 `8080`，公网流量只经 Nginx `443` 进入。
- 当前版本为 `20261111234219-3fe31fc`，`current` 指向 `/opt/bgm-guess/releases/20261111234219-3fe31fc`。
- 当前版本包含出题人署名的随题展示，以及昵称认领、冲突记录与主持人释放/改绑接口。
- `/opt/bgm-guess/shared/data` 保存 `timeline.json`、`timeline-online.json`、`questions.json` 与 `nickname_claims.json`；榜单文件由服务运行时生成，该目录不放置音频文件。
- `game.himematsu.cn` 使用独立 Nginx 站点与 certbot 证书，`certbot.timer` 负责续期。
- 主站 `himematsu.cn` 与 `www.himematsu.cn` 继续使用 `/etc/letsencrypt/live/himematsu.cn/` 证书，未受子域变更影响。
- `https://game.himematsu.cn/`、`/host` 与 `/api/health` 返回 `200`；`/api/events/jisong/audio` 返回 `302` 跳转到 OSS 线下对象；`/ws` 完成 WebSocket Upgrade。\n- 线上加入接口写入 `shared/data/nickname_claims.json` 正常，空表内容为 `[]`。

## 3. OSS 状态（已发布版本）

以下为 2026-11-06 已发布对象的现状。仓库与本地数据已更新为新版音频与编排，OSS 尚未替换；新版对象上传前，线上环境继续使用本节所述对象与旧编排。

线上对象为纯 BGM 母带，由 `music/bgm-music.mp3` 单次重编码为 CBR 192 kbps 后上传：

```text
oss://himematsu/bgm/event-online-cbr192.mp3
https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-online-cbr192.mp3
```

| 项目 | 状态 |
| :--- | :--- |
| 对象上传 | 已完成，2026-11-06 |
| 文件大小 | 62,233,783 字节 |
| MD5 | `CF242A2CAF35D8AEB0A13A0AB687BA78` |
| 时长 | 2593.000 秒 |
| 编码 | MP3 CBR 192 kbps，44.1 kHz / 2 声道 |
| ETag | `4139A3C56E24A5FB6F1CEFF0BCDF8E2A-6` |
| 访问权限 | 公共读 |
| `Content-Type` | `audio/mpeg` |
| `Cache-Control` | `public, max-age=31536000, immutable` |
| CORS | 未启用；`<audio>` 直接播放与 Range 不受影响 |
| 普通请求 | `200`，已实测 |
| Range 请求 | `206`，已实测 |
| 逐题定位 | 40 题全部通过，首次出声偏差最大 0.274 秒 |

上线初期的 VBR 对象 `姬松活动/bgm-music.mp3` 保留在 OSS，作为线上音频的回滚对象。

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
- 前端构建不注入 `VITE_AUDIO_URL`，主持人控制台通过后端音频入口取值。
- 后端 `app.audio-url` 缺省指向线下对象，`app.audio-url-online` 缺省指向线上对象；`APP_AUDIO_URL` 与 `APP_AUDIO_URL_ONLINE` 可覆盖两个地址。
- 发布任务使用 `production` environment。
- 发布用户 `deploy`、sudoers 规则与服务器目录已由 `deploy/bootstrap-server.sh` 初始化；GitHub Secrets 与 Variables 待配置。
- 首个版本通过手工上传发布包完成激活，发布记录为 `20261106165538-local`。
- 2026-11-10 通过手工发布流程激活 `20261110193553-21a8aa1`。
- `main` 推送自动发布在服务器初始化与 Secrets 配置完成后启用。

CI/CD 的完整说明见 [`cicd.md`](cicd.md)；不依赖 GitHub Actions 的手工发布流程见 [`manual-deploy-runbook.md`](manual-deploy-runbook.md)。

## 5. 上线顺序

1. 确认线上对象 `bgm/event-online-cbr192.mp3` 可读；上传线下对象 `bgm/event-offline.mp3`，设置 ACL、Content-Type 与 Cache-Control。
2. 验证两个 OSS 对象的普通请求返回 `200`，Range 请求返回 `206`。
3. 在服务器执行 `deploy/bootstrap-server.sh`，创建 `bgm`、`deploy` 用户与目录结构。
4. 将 `timeline.json`、`timeline-online.json` 与 `questions.json` 放入 `/opt/bgm-guess/shared/data`。榜单文件由服务首次写入时创建，该目录不放置音频文件。
5. 编辑 `/etc/bgm-guess/bgm-guess.env`，填写 `APP_HOST_PASSWORD`、`APP_AUDIO_URL`、`APP_AUDIO_URL_ONLINE` 与 `APP_ONLINE_TIMELINE_FILE`。
6. 上传新版 `bgm/event-offline.mp3` 与线上纯 BGM 对象，确认普通请求返回 `200`、Range 请求返回 `206`。
7. 设置 `VITE_PUBLIC_BASE_URL` 并构建前端，`VITE_AUDIO_URL` 保持为空。
8. 使用 Java 17 构建后端 JAR。
9. 上传 release，切换 `current`，启动 `bgm-guess`。
10. 为 `game.himematsu.cn` 签发证书并启用 `deploy/nginx-game.conf.example`。
11. 配置 GitHub Secrets 与发布用户，手动运行一次 `Deploy`。
12. 完成验收后，按需启用 `main` 推送自动发布。

## 6. 验收清单

- [ ] `https://game.himematsu.cn/` 返回本系统页面。
- [ ] `https://game.himematsu.cn/host` 刷新不出现 404。
- [ ] `https://game.himematsu.cn/?mode=offline` 进入线下入口。
- [ ] `https://game.himematsu.cn/api/health` 返回健康信息。
- [ ] `https://game.himematsu.cn/api/events/jisong/online/questions` 返回 40 题，`audioUrl` 为 `bgm/event-online-cbr192.mp3` 的对象地址。
- [ ] 主持人页面 `<audio>` 的 `src` 为 `/api/events/jisong/audio`，该请求返回 302 跳转到线下对象。
- [ ] 两个 OSS 对象的 `HEAD` 返回 `200`。
- [ ] 两个 OSS 对象的 Range 请求返回 `206`。
- [ ] 主持人控制台四轮区间为 0/562469、562469/1259149、1259149/1871684、1871684/2429278，每轮开始首题立即上屏并同时播放轮首播报。
- [ ] 线上题集末题 `audioEndMs` 为 2244491，线下末题 `audioEndMs` 为 2429278。
- [ ] 两个 OSS 对象与 `backend/data/event-offline.mp3`、`backend/data/event-online.mp3` 的 MD5 一致。
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
