# 后端架构与核心实现

## 1. 后端工程目录结构

后端采用标准 Maven 单工程结构，关键包路径与文件布局如下：

```text
backend/
├── pom.xml                                 # 项目依赖定义 (Spring Boot 3.5.5, Java 17)
├── maven-settings.xml                      # Maven 构建镜像配置
├── data/                                   # 本地数据存储目录
│   ├── timeline.json                       # 编排文件（轮次、动作时间轴与题目引用）
│   ├── questions.json                      # 题库文件（题干、选项、答案与解析）
│   ├── <audioFile>                         # 完整音频文件（轮次为其内部区段）
│   ├── leaderboards.json                   # 历史榜单快照 (JSON)
│   └── leaderboard_summary.txt             # 格式化全场大总结 (TXT)
└── src/main/java/com/jisong/bgm/
    ├── BgmApplication.java                 # Spring Boot 主启动类
    ├── config/
    │   ├── WebConfig.java                  # CORS 跨域配置
    │   └── WebSocketConfig.java            # STOMP 消息代理与 WebSocket 端点注册
    ├── controller/
    │   ├── EventController.java            # 参与者端业务接口与音频流接口
    │   ├── HostController.java             # 主持人管理与轮次控制接口
    │   └── GlobalExceptionHandler.java     # 全局统一异常捕获
    ├── dto/                                # 数据传输模型 (Record / DTO)
    │   ├── ApiResponse.java                # 统一 REST 响应封装
    │   ├── EventState.java                 # 房间实时状态脱敏快照
    │   ├── QuestionView.java               # 客户端题目展示对象
    │   ├── LeaderboardEntry.java           # 排行榜条目对象 (含耗时字段)
    │   └── LeaderboardSnapshot.java        # 轮次归档快照结构
    ├── model/                              # 领域实体
    │   ├── ActivityStatus.java             # WAITING / ANSWERING / ANSWER_LOCKED / ANSWER_REVEALED / FINISHED
    │   ├── ActionType.java                 # LOAD / NEXT / LOCK / REVEAL / FINISH
    │   ├── Difficulty.java                 # EASY / MEDIUM / HARD
    │   ├── QuestionType.java               # WORK / ATMOSPHERE
    │   ├── Question.java                   # 题库题目内容实体
    │   ├── TimelineItem.java               # 单条动作条目（时间、动作、可选题目）
    │   ├── Round.java                      # 轮次实体（音频区间与条目列表）
    │   ├── Timeline.java                   # 编排文件根对象
    │   └── Player.java                     # 玩家会话与答题明细
    └── service/
        ├── ActivityService.java            # 状态机调度与业务核心
        ├── TimelineStore.java              # 编排文件加载、轮次解析与合法性校验
        ├── LeaderboardStore.java           # 榜单持久化与等宽文本生成器
        └── HostAuthService.java            # 主持人令牌与密码认证
```

## 2. 核心服务职责详解

### 2.1 `ActivityService`（状态机与调度中心）
- **内存状态维护**：内部持有 `Map<String, Activity>` 集合（默认活动标识为 `demo`），维护当前在线玩家列表、选中轮次、待上屏内容槽位、当前题目序号、状态机枚举、本轮零点 `T0` 与已挂起的时间轴任务。
- **并发安全管理**：活动状态流转与玩家答题提交采用同步保护与原子校验，确保高并发作答时得分统计无冲突。
- **时间轴调度**：启动轮次时按“触发时刻 = T0 + (atMs − startMs)”为每个条目与轮次结束标记挂载一次性任务；条目执行前校验目标状态与轮次归属。暂停时注销全部挂起任务并记录剩余时长，恢复时按剩余时长重排任务并以暂停时长平移结束标记。
- **竞速耗时计算**：参与者提交答案时记录提交时刻与本题 `NEXT` 时刻之差；`LOCK` 执行时对未提交玩家按“锁卷时刻 − max(开题时刻, 加入时刻)”补记耗时，加入前已锁卷的题目不计耗时。
- **轮次管理**：提供轮次清单与选中轮次；切换轮次时重置房间（清空在线玩家）并回到 `WAITING`；到达轮次结束标记时暂停时间轴并广播 `ROUND_ENDED`。
- **主持人专用快照**：`getHostState` 返回完整的 `EventState`、在线玩家名册（`playerId` / `nickname` / `answered`）、轮次清单与选中轮次，题目中的作品名不做脱敏，供控制台现场作答进度监控墙与音频协同看板使用；参与者接口的快照保持脱敏。

### 2.2 `TimelineStore`（编排加载与校验）
- 启动时自动读取 `backend/data/timeline.json` 与同目录的 `questions.json`，按 `questionId` 解析题目引用并校验数据。
- 校验规则：轮次区间合法且互不重叠、条目时间在轮次区间内且严格递增、`NEXT` 触发时要求存在尚未被消费的 `LOAD`、`LOAD` 与 `NEXT` 数量匹配、每轮存在 `atMs` 不晚于 `endMs` 的 `FINISH`、题目选项数量与正确答案下标合法、题目编号唯一。
- 编排文件读取或校验失败时抛出启动异常并输出具体条目位置，避免带病运行。

### 2.3 `LeaderboardStore`（排行榜持久化与格式化）
- **双格式同步写入**：`FINISH` 动作触发结算时，向 `leaderboards.json` 追加包含轮次时间戳、参与人数和排名详情的快照对象，并同时刷新生成纯文本格式的 `leaderboard_summary.txt`。
- **等宽视觉对齐算法**：在生成控制台文本榜单时，准确计算汉字（宽度为 2）与西文字符（宽度为 1）的视觉显示列数，保证 Windows 记事本与终端展示时表格线完全对齐。
- **去重榜单归并**：扫描全量历史记录，按玩家昵称提取个人最佳得分与最短耗时，输出“个人最佳成绩总榜”。

### 2.4 `HostAuthService`（主持人认证）
- 校验登录口令并签发临时会话令牌；主持人接口在执行业务前校验 `X-Host-Token` 请求头，凭据缺失或失效时返回 `401 Unauthorized`，口令错误按业务异常返回。

## 3. 核心配置说明 (`application.yml`)

```yaml
server:
  port: 8080                               # 服务监听端口

spring:
  application:
    name: bgm-guess-backend

app:
  frontend-origin: http://localhost:5173   # 允许跨域的前端源地址
  host-password: "123456"                  # 主持人登录默认口令
  timeline-file: data/timeline.json        # 编排文件路径
  leaderboard-file: data/leaderboards.json # 榜单 JSON 持久化路径
  leaderboard-txt-file: data/leaderboard_summary.txt # 榜单文本大总结路径
```

- 编排文件中的 `audioFile` 字段相对编排文件所在目录解析，音频文件与编排文件同目录存放。
- 后端通过 `GET /api/events/{eventId}/audio` 输出音频文件字节流（支持 HTTP Range 请求），供主持人控制台播放。
