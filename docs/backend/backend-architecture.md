# 后端架构与核心实现

## 1. 后端工程目录结构

后端采用标准 Maven 单工程结构，关键包路径与文件布局如下：

```text
backend/
├── pom.xml                                 # 项目依赖定义 (Spring Boot 3.5.5, Java 17)
├── maven-settings.xml                      # Maven 构建镜像配置
├── data/                                   # 本地数据存储目录
│   ├── questions.json                      # 题库定义
│   ├── leaderboards.json                   # 历史榜单快照 (JSON)
│   └── leaderboard_summary.txt             # 格式化全场大总结 (TXT)
└── src/main/java/com/jisong/bgm/
    ├── BgmApplication.java                 # Spring Boot 主启动类
    ├── config/
    │   ├── WebConfig.java                  # CORS 跨域配置
    │   └── WebSocketConfig.java            # STOMP 消息代理与 WebSocket 端点注册
    ├── controller/
    │   ├── EventController.java            # 参与者端业务接口
    │   ├── HostController.java             # 主持人管理与控制接口
    │   └── GlobalExceptionHandler.java     # 全局统一异常捕获
    ├── dto/                                # 数据传输模型 (Record / DTO)
    │   ├── ApiResponse.java                # 统一 REST 响应封装
    │   ├── EventState.java                 # 房间实时状态脱敏快照
    │   ├── QuestionView.java               # 客户端题目展示对象
    │   ├── LeaderboardEntry.java           # 排行榜条目对象 (含耗时字段)
    │   └── LeaderboardSnapshot.java        # 轮次归档快照结构
    ├── model/                              # 领域实体
    │   ├── ActivityStatus.java             # WAITING / ANSWERING / ANSWER_REVEALED / FINISHED
    │   ├── Difficulty.java                 # EASY / MEDIUM / HARD
    │   ├── QuestionType.java               # WORK / ATMOSPHERE
    │   ├── Question.java                   # 题库原始实体
    │   └── Player.java                     # 玩家会话与答题明细
    └── service/
        ├── ActivityService.java            # 活动状态机调度与业务核心
        ├── QuestionBank.java               # 题库加载与分发管理
        ├── LeaderboardStore.java           # 榜单持久化与等宽文本生成器
        └── HostAuthService.java            # 主持人令牌与密码认证
```

## 2. 核心服务职责详解

### 2.1 `ActivityService`（状态机与调度中心）
- **内存状态维护**：内部持有 `Map<String, Activity> activities` 集合（默认活动标识为 `demo`），维护当前在线玩家列表、当前题目下标、状态机枚举、答题截止时间等。
- **并发安全管理**：活动状态切换与玩家答题提交采用同步保护与原子校验，确保高并发作答时得分统计无冲突。
- **竞速耗时计算**：当参与者提交答案时，记录系统当前时间戳与题目开始时间戳之差；超时未答题目统一按 20000 毫秒计入。
- **自动调度器**：初始化单线程池 `bgm-scheduler`。进入作答阶段挂载 20 秒超时任务；进入揭晓阶段挂载 6 秒切题任务。
- **主持人专用快照**：`getHostState` 返回完整的 `EventState` 与在线玩家名册（`playerId` / `nickname` / `answered` / `spectator`），题目中的作品名不做脱敏，供控制台现场作答进度监控墙与放音协同指示看板使用；参与者接口的快照保持脱敏。

### 2.2 `LeaderboardStore`（排行榜持久化与格式化）
- **双格式同步写入**：活动进入 `FINISHED` 状态时，向 `leaderboards.json` 追加包含轮次时间戳、参与人数和排名详情的快照对象，并同时刷新生成纯文本格式的 `leaderboard_summary.txt`。
- **等宽视觉对齐算法**：在生成控制台文本榜单时，准确计算汉字（宽度为 2）与西文字符（宽度为 1）的视觉显示列数，保证 Windows 记事本与终端展示时表格线完全对齐。
- **去重榜单归并**：扫描全量历史记录，按玩家昵称提取个人最佳得分与最短耗时，输出“个人最佳成绩总榜”。

### 2.3 `QuestionBank`（题库加载器）
- 启动时自动读取 `backend/data/questions.json`。
- 封装按难度分类的题目集合，并提供兜底内置题目，保障题库文件读取异常时服务正常启动。

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
  leaderboard-file: data/leaderboards.json # 榜单 JSON 持久化路径
  leaderboard-txt-file: data/leaderboard_summary.txt # 榜单文本大总结路径
  questions-file: data/questions.json      # 题库文件路径
```
