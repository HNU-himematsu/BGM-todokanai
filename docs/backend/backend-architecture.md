# 后端架构与核心实现

## 1. 后端工程目录结构

后端采用标准 Maven 单工程结构，关键包路径与文件布局如下：

```text
backend/
├── pom.xml                                 # 项目依赖定义 (Spring Boot 3.5.5, Java 17)
├── maven-settings.xml                      # Maven 构建镜像配置
├── data/                                   # 本地数据存储目录
│   ├── timeline.json                       # 线下编排（轮次、动作时间轴与题目引用）
│   ├── timeline-online.json                # 线上编排（线上题集与音频时刻）
│   ├── questions.json                      # 题库文件（题干、选项、答案与解析）
│   ├── event-offline.mp3                   # 线下母带，本地联调；部署环境由 app.audio-url 指向 OSS
│   ├── event-online.mp3                    # 线上母带，本地联调；部署环境由 app.audio-url-online 指向 OSS
│   ├── leaderboards.json                   # 线下轮次快照 (JSON)
│   ├── online_leaderboard.json             # 线上锁定记录 (JSON)
│   └── leaderboard_summary.txt             # 线下榜/线上榜/总榜文本总结 (TXT)
└── src/main/java/com/jisong/bgm/
    ├── BgmApplication.java                 # Spring Boot 主启动类
    ├── config/
    │   ├── WebConfig.java                  # CORS 跨域配置
    │   └── WebSocketConfig.java            # STOMP 消息代理与 WebSocket 端点注册
    ├── controller/
    │   ├── EventController.java            # 参与者端、线上答题与榜单接口
    │   ├── HostController.java             # 主持人管理与轮次控制接口
    │   └── GlobalExceptionHandler.java     # 全局统一异常捕获
    ├── dto/
    │   ├── ApiResponse.java                # 统一 REST 响应封装
    │   ├── JoinRequest.java                # 昵称、模式与 participantId
    │   ├── JoinResponse.java               # 模式、playerId、participantId、锁定状态
    │   ├── EventState.java                 # 线下房间实时状态脱敏快照
    │   ├── QuestionView.java               # 客户端题目展示对象
    │   ├── OnlineQuestionSet.java          # 线上题集、音频地址与题集版本
    │   ├── OnlineQuestionView.java         # 线上脱敏题目与题目级时间字段
    │   ├── OnlinePreviewRequest.java       # 线上预览请求
    │   ├── OnlineLockRequest.java          # 线上锁定请求
    │   ├── OnlineScoreResult.java          # 预览分与锁定结果
    │   ├── OnlineStatusResponse.java       # 线上锁定状态
    │   ├── LeaderboardEntry.java           # 线下排行榜条目
    │   ├── OnlineLeaderboardEntry.java     # 线上积分榜条目
    │   ├── TotalLeaderboardEntry.java      # 总榜条目
    │   ├── LeaderboardSnapshot.java        # 线下轮次归档快照
    │   └── OnlineScoreRecord.java          # 线上锁定持久化记录
    ├── model/
    │   ├── AppMode.java                    # ONLINE / OFFLINE
    │   ├── ActivityStatus.java             # WAITING / ANSWERING / ANSWER_LOCKED / ANSWER_REVEALED / FINISHED
    │   ├── ActionType.java                 # LOAD / NEXT / LOCK / REVEAL / FINISH
    │   ├── Difficulty.java                 # EASY / MEDIUM / HARD
    │   ├── QuestionType.java               # WORK / ATMOSPHERE
    │   ├── Question.java                   # 题库题目内容实体
    │   ├── TimelineItem.java               # 单条动作条目（时间、动作、可选题目）
    │   ├── Round.java                      # 轮次实体（音频区间与条目列表）
    │   ├── Timeline.java                   # 编排文件根对象
    │   ├── OnlineQuestionWindow.java       # 线上题目的音频与作答窗口
    │   └── Player.java                     # 线下玩家会话与答题明细
    └── service/
        ├── ActivityService.java            # 线下状态机调度与业务核心
        ├── OnlineAttemptService.java       # 线上题集、预览、锁定与榜单查询
        ├── TimelineStore.java              # 题库/编排加载、线上题集、版本与校验
        ├── LeaderboardStore.java           # 线下快照、线上记录、总榜与文本生成
        └── HostAuthService.java            # 主持人令牌与密码认证
```

## 2. 核心服务职责

### 2.1 `ActivityService`（线下状态机与调度中心）

- 内部持有 `Map<String, Activity>` 集合，默认活动标识为 `jisong`。
- 维护当前在线玩家、选中轮次、待上屏内容、当前题目、状态机枚举、本轮零点 `T0` 与时间轴任务。
- 启动轮次时按 `T0 + (atMs - startMs)` 挂载一次性任务；暂停时注销任务并记录锚点；恢复时按剩余时长重排。
- 手动 `LOCK` / `REVEAL` 注销当前题目的同类挂起任务，其余题目的同类条目保留并按到达时刻执行；状态守卫使语义已满足的条目空转。
- 排他计算线下得分与耗时：提交时记录提交时刻与本题 `NEXT` 时刻差值；`LOCK` 时为未提交玩家补记耗时。
- 切换轮次时清空在线玩家并回到 `WAITING`；到达轮次结束标记或主持人结束末题后暂停时间轴并广播 `ROUND_ENDED`。
- `getHostState` 返回完整 `EventState`、在线玩家名册、轮次清单与选中轮次；主持人快照保留 `workName`。

### 2.2 `OnlineAttemptService`（线上答题与榜单）

- 提供线上题集读取、预览分计算、锁定写入、线上榜查询与总榜查询。
- 不保存进行中的线上答题进度；进度由浏览器 `localStorage` 承载。
- 预览请求校验题目编号、选项下标与题集版本，按题库正确答案计算分数，不返回答案。
- 锁定请求重新判分，以 `participantId` 为唯一键写入线上记录。
- 同一 `attemptId` 重复锁定返回已有结果；不同 `attemptId` 在已锁定后返回冲突。
- 锁定成功后广播 `ONLINE_SCORE_LOCKED`。

### 2.3 `TimelineStore`（编排加载与线上题集）

- 启动时读取 `timeline.json`、可选的 `timeline-online.json` 与同目录 `questions.json`，按 `questionId` 解析题目引用。
- 两份编排执行交叉校验：`eventId`、`eventName` 与题目序列一致，题目集合覆盖题库全集。
- 校验轮次区间、动作顺序、`LOAD`/`NEXT` 配对、`LOCK` 位置、音频片段、题库覆盖与枚举值。
- 生成线上题集：由 `timeline-online.json` 按轮次顺序与条目顺序拉平，忽略 `REVEAL`、`FINISH` 与轮次边界，按 `questionId` 去重；该文件缺省时取 `timeline.json`。
- 计算 `questionSetVersion`，输入为线上题集的题目编号、音频起止与作答窗口。
- 分别解析线下与线上音频路径与地址，供主持人、线上题集与音频接口使用。
- 校验线上相邻题目音频首尾相接，题目之间无空白段。

### 2.4 `LeaderboardStore`（榜单持久化与聚合）

- 线下 `FINISH`：向 `leaderboards.json` 追加轮次快照，刷新文本总结。
- 线上锁定：向 `online_leaderboard.json` 追加锁定记录，刷新文本总结。
- 总榜聚合：按 `participantId` 合并线下各轮最佳成绩与线上锁定成绩。
- 文本总结：输出线下轮次榜、线上积分榜与总榜；汉字宽度按 2、西文字符宽度按 1 对齐。
- 历史数据兼容：缺少 `participantId` 的线下记录以 `playerId` 作为合并键；缺少 `roundId` 的快照以序号生成轮次键。

### 2.5 `HostAuthService`（主持人认证）

- 校验登录口令并签发临时会话令牌。
- 主持人接口在执行业务前校验 `X-Host-Token` 请求头。
- 口令错误、凭据缺失或令牌失效时返回 `401 Unauthorized`。

## 3. 并发与一致性

- 线下状态流转与玩家答题提交使用同步保护。
- 线上锁定与线下结算写入使用同步保护。
- 同一 `participantId` 的线上锁定只允许一条记录。
- 总榜按请求实时聚合，不单独持久化排行结果。
- 线上预览无持久化副作用。

## 4. 核心配置

```yaml
server:
  port: 8080

spring:
  application:
    name: bgm-guess-backend

app:
  frontend-origin: http://localhost:5173
  host-password: "123456"
  timeline-file: data/timeline.json
  online-timeline-file: data/timeline-online.json
  audio-url: "${APP_AUDIO_URL:https://himematsu.oss-cn-guangzhou.aliyuncs.com/bgm/event-offline.mp3}"
  audio-url-online: "${APP_AUDIO_URL_ONLINE:https://himematsu.oss-cn-guangzhou.aliyuncs.com/%E5%A7%AC%E6%9D%BE%E6%B4%BB%E5%8A%A8/bgm-music.mp3}"
  leaderboard-file: data/leaderboards.json
  online-leaderboard-file: data/online_leaderboard.json
  leaderboard-txt-file: data/leaderboard_summary.txt
```

- 编排文件中的 `audioFile` 相对编排文件所在目录解析。
- 音频通过 `GET /api/events/{eventId}/audio` 输出，支持 HTTP Range；`mode=online` 输出线上母带。
- `audio-url` 与 `audio-url-online` 非空时，音频接口返回 302 跳转到对应对象；`online-timeline-file` 为空时线上题集取 `timeline.json`。
- 线上题集与锁定接口的完整字段见 [`api-spec.md`](api-spec.md)。

## 5. 异常映射

| 异常类别 | HTTP 状态 | 说明 |
| :--- | :--- | :--- |
| 参数校验失败 | `400` | 昵称、模式、题目编号、选项下标或请求体结构不合法。 |
| 业务状态守卫失败 | `400` | 线下动作、重复提交或答题窗口不满足。 |
| 主持人认证失败 | `401` | 密码错误、令牌缺失或失效。 |
| 活动或轮次不存在 | `404` | 路径资源无法解析。 |
| 题集版本冲突 | `409` | 客户端题集版本与服务端不一致。 |
| 线上重复锁定 | `409` | 同一参与者已以不同 attempt 锁定。 |
| 文件读写失败 | `500` | 榜单写入、文本导出或音频读取失败。 |

错误响应沿用 `ApiResponse`，`code` 与 HTTP 状态一致，`message` 提供可展示说明，`data` 为 `null`。

## 6. 数据脱敏

- 线下 `ANSWERING` 与 `ANSWER_LOCKED` 下发的题目不含 `answerIndex`、`workName`、`analysis`、`author`。
- 线上题集下发 `author`，不含答案、解析与作品名；预览与锁定响应不含题目内容。
- 主持人快照保留 `workName` 与 `author`，仅供控制台展示。

完整功能与验收标准见 [`../product/dual-mode-development-spec.md`](../product/dual-mode-development-spec.md)。
