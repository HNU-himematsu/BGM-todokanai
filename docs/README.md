# 旮旯原声之届不到的 BGM · 系统工程文档

本项目为高校社团百团大战互动活动量身定制，是一套覆盖线上独立答题与线下现场互动的猜歌系统。系统采用前后端分离架构，支持手机端扫码参与、单音频编排驱动的现场统一放音、线上全题库自动推进、毫秒级答题竞速排名，以及线下轮次榜、线上积分榜与总榜的持久化归档。

## 开发基线

- 网页根地址 `/` 进入线上模式；显式线上入口为 `/online?event=demo`。
- 线下二维码地址为 `/offline?event=demo`；`/?mode=offline&event=demo` 为等效查询入口。
- 线上覆盖题库全部题目，不使用轮次，浏览器本地保存进度，锁定后进入线上榜与总榜。
- 线下保留主持人控制的轮次、逐题揭晓与每轮独立榜，线下榜用于现场评奖。
- 总榜为每位参与者线下各轮最佳成绩之和与线上锁定成绩的合并结果。
- 题库、编排与音频的文件契约见 [`timeline-spec.md`](data-and-config/timeline-spec.md) 与 [`questions-format-example.txt`](data-and-config/questions-format-example.txt)。

开发前先阅读 [`product/dual-mode-development-spec.md`](product/dual-mode-development-spec.md)。接口字段以 [`backend/api-spec.md`](backend/api-spec.md) 为准，页面交互以 [`frontend/views-and-ui.md`](frontend/views-and-ui.md) 为准。

## 文档目录导航

### 1. 架构设计篇 (`architecture/`)
- [`overview.md`](architecture/overview.md)：系统总体架构设计，包括分层架构、技术选型及系统组件拓扑。
- [`state-machine.md`](architecture/state-machine.md)：线上与线下状态机，说明线下轮次时间轴、线上本地自动推进、预览与锁定状态的流转规则。
- [`data-flow.md`](architecture/data-flow.md)：数据流与实时通信规范，说明线上本地进度、线下时间轴广播与 RESTful API 的协作机制。

### 2. 业务规则篇 (`business/`)
- [`event-rules.md`](business/event-rules.md)：线上与线下活动规则、计分与竞速算法，说明题目梯度、未答计分、重试、锁定与三类榜单。
- [`host-sop.md`](business/host-sop.md)：现场多岗位协同 SOP，涵盖主场控、秩序引导员、奖品核销员与线上/总榜核对流程。

### 3. 后端工程篇 (`backend/`)
- [`backend-architecture.md`](backend/backend-architecture.md)：Spring Boot 后端架构与核心类设计，说明状态机管理、时间轴调度与数据存储层实现。
- [`api-spec.md`](backend/api-spec.md)：接口规范与协议文档，详细定义 RESTful API 端点与 STOMP 消息格式。

### 4. 前端工程篇 (`frontend/`)
- [`frontend-architecture.md`](frontend/frontend-architecture.md)：Vue 3 前端架构设计，说明双模式路由、组件拆分、线上本地进度、状态管理、设计令牌与主色调调整入口。
- [`views-and-ui.md`](frontend/views-and-ui.md)：页面视图与交互规范，包含线上全题库答题、线下逐题揭晓、主持人控制台与榜单视图。

### 5. 数据与配置篇 (`data-and-config/`)
- [`timeline-spec.md`](data-and-config/timeline-spec.md)：编排文件规范与制作指南，说明 `timeline.json` 的轮次、题目音频片段、线上题集生成规则，以及 `questions.json` 的题目内容格式。
- [`questions-format-example.txt`](data-and-config/questions-format-example.txt)：题库与编排的人工录入模板，包含两轮四题的时间、动作、选项、答案与线上题集映射示例。
- [`leaderboard-and-storage.md`](data-and-config/leaderboard-and-storage.md)：排行榜存储格式与解析说明，包含线下快照、线上锁定记录、总榜聚合与 Python 离线脚本。
- [`jisong-event-integration.md`](data-and-config/jisong-event-integration.md)：姬松活动素材接入规范，说明 `总音频.mp3` 与 `编排.docx` 的成品规格、音频裁切、四轮区间、40 题生成值、出题人署名展示与启动自检、联调、回滚流程。

### 6. 功能规格篇 (`product/`)
- [`dual-mode-development-spec.md`](product/dual-mode-development-spec.md)：双模式开发主规格，完整定义线上/线下入口、身份、答题流程、时间轴、接口、本地进度、榜单、异常、验收标准与开发任务。

### 7. 运维保障篇 (`ops-and-deployment/`)
- [`local-and-tunnel-guide.md`](ops-and-deployment/local-and-tunnel-guide.md)：本地启动与内网穿透指南，说明前后端启动参数与 Cloudflare Tunnel 配置。
- [`cicd.md`](ops-and-deployment/cicd.md)：自动化构建与发布，说明 GitHub Actions、服务器目录、systemd、Nginx、回滚与一次性初始化。
- [`oss-audio.md`](ops-and-deployment/oss-audio.md)：外部音频部署，说明阿里云 OSS 直连、CORS、Range、缓存与发布配置。
- [`deployment-status.md`](ops-and-deployment/deployment-status.md)：部署状态与上线清单，记录代码、服务器、OSS、流水线状态与验收项。
- [`troubleshooting.md`](ops-and-deployment/troubleshooting.md)：故障排查手册与应急预案，说明弱网断线、操作失误及数据备份处理方案。
