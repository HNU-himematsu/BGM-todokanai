# 旮旯原声之届不到的 BGM · 系统工程文档

本项目为高校社团百团大战现场互动活动量身定制，是一套轻量级多人实时在线猜歌系统。系统采用前后端分离架构，支持手机端扫码参与、现场音箱统一放音、题目倒计时自动轮转、毫秒级答题竞速排名以及全场总榜持久化归档。

## 文档目录导航

### 1. 架构设计篇 (`architecture/`)
- [`overview.md`](architecture/overview.md)：系统总体架构设计，包括分层架构、技术选型及系统组件拓扑。
- [`state-machine.md`](architecture/state-machine.md)：活动生命周期状态机，说明等待、答题、揭晓与结束状态的调度流转规则。
- [`data-flow.md`](architecture/data-flow.md)：数据流与实时通信规范，说明 WebSocket STOMP 与 RESTful API 的协作机制。

### 2. 业务规则篇 (`business/`)
- [`event-rules.md`](business/event-rules.md)：活动规则与竞速计分算法，说明题目梯度配置、得分计算与耗时仲裁规则。
- [`host-sop.md`](business/host-sop.md)：现场多岗位协同 SOP，涵盖主持人、场地放音员、秩序引导员与奖品核销员的工作流程。

### 3. 后端工程篇 (`backend/`)
- [`backend-architecture.md`](backend/backend-architecture.md)：Spring Boot 后端架构与核心类设计，说明状态机管理、定时调度器与数据存储层实现。
- [`api-spec.md`](backend/api-spec.md)：接口规范与协议文档，详细定义 RESTful API 端点与 STOMP 消息格式。

### 4. 前端工程篇 (`frontend/`)
- [`frontend-architecture.md`](frontend/frontend-architecture.md)：Vue 3 前端架构设计，说明组件结构、路由配置与状态管理。
- [`views-and-ui.md`](frontend/views-and-ui.md)：页面视图与交互规范，包含移动端答题界面与主持人控制台交互逻辑。

### 5. 数据与配置篇 (`data-and-config/`)
- [`question-bank-spec.md`](data-and-config/question-bank-spec.md)：题库配置规范与扩展指南，说明 `questions.json` 结构与放音编号规则。
- [`leaderboard-and-storage.md`](data-and-config/leaderboard-and-storage.md)：排行榜存储格式与解析说明，包含 JSON 快照格式、去重奖品榜逻辑与 Python 离线脚本。

### 6. 运维保障篇 (`ops-and-deployment/`)
- [`local-and-tunnel-guide.md`](ops-and-deployment/local-and-tunnel-guide.md)：本地启动与内网穿透指南，说明前后端启动参数与 Cloudflare Tunnel 配置。
- [`troubleshooting.md`](ops-and-deployment/troubleshooting.md)：故障排查手册与应急预案，说明弱网断线、操作失误及数据备份处理方案。
