# 双模式系统文档改进意见

## 1. 基线与总体判断

本文针对提交 `233f2183597e3a6aa21c3dc7b7276e02b9db6cca` 的文档系统。`docs/README.md` 已提供双模式开发基线，主规格、接口、时间轴、素材接入、音频定位、OSS、CI/CD 和故障手册均存在。文档问题主要是跨文件重复、实现与目标规格交错、测试证据缺少可重复基线，以及外部部署状态易失效。

建议保持领域目录结构，把治理重点放在每条行为声明的权威来源、实现状态和验证入口。本文中的建议表示目标规格，不代表代码已有该能力。代码证据与优先级见 [Review 报告](repository-review.md)。

## 2. 事实与契约核对

| 编号 | 文档及声明 | 实现证据/边界 | 建议与优先级 |
| --- | --- | --- | --- |
| D01 | README 描述单机现场骨架，列出 `/` 参与者入口但不解释双模式 | `/` 为线上，现场二维码为 `/offline`；具有线上/线下/总榜 | 根入口应列双模式用途、素材准备和 docs 主规格链接，P1 |
| D02 | README 与 local-and-tunnel-guide 要求 Node.js 18+ | 锁文件 Vite 7.3.6 要求 `^20.19.0 || >=22.12.0`，CI 使用 22 | 统一受支持版本及锁文件安装命令 npm ci，P1 |
| D03 | README 启动命令缺少题库准备条件 | questions.json 不入库，TimelineStore 必须读取；干净检出启动/上下文测试失败 | 分清生产素材准备与测试夹具；提供无需真实题库的测试入口，P1 |
| D04 | deployment-status 记录“后端测试共 18 项”、构建通过 | 基线实际 20 项，干净副本 19 通过、1 错误 | 测试结论绑定 SHA、命令、夹具与产物；固定状态文档不承担永久通过保证，P1 |
| D05 | frontend-architecture、主规格异常表要求主持人重连校准音频 | HostView 刷新状态但不按 audioPositionMs 校准；见 C02 | 明确目标与实现缺口，补恢复验收矩阵，P1 |
| D06 | 存储规格 §7.1 声称任一阶段失败不更新内存成功状态 | finishRound 在存储前设置 FINISHED；JSON 提交后 TXT 还可能失败 | 定义 JSON 提交、TXT 导出和 UI 状态三条边界，P1 |
| D07 | 存储规格 §7.2 声称线下 eventId+roundId+finishedAt 不重复写入 | save 每次生成 Instant.now 并追加，无显式唯一性判断 | 区分进程内 leaderboardSaved 与持久化幂等；定义结算实例 ID，P1 |
| D08 | CI/CD 承诺失败回滚、保留最近五版 | restart 非零会提前退出；清理可删除被激活的旧版 | 写清保护集合和全部失败点，配临时目录演练，P1 |
| D09 | 状态机定义暂停平移时间，作答规则只给普通提交公式 | 暂停期间仍可答，恢复后时间基准又平移 | 统一暂停中答题、锁卷、加入的规则，P1 |
| D10 | 题集版本变化使旧进度失效 | 哈希仅覆盖题号和时间字段；内容及答案变更不影响版本，符合现有明确公式 | 标注该版本是编排版本，或制定完整内容版本契约，P2 |
| D11 | 主规格说明加载超时进入 ERROR | ensureMetadata 无超时，开始流程可并发等待 | 补加载阶段、超时、取消和重复点击状态，P2 |
| D12 | 主规格允许存储不可用时开始答题 | 路由守卫读取持久存储，storage.write 对 null 存储可返回 true | 区分本地存储不可用与会话存储也不可用；验证内存降级策略，P2 |
| D13 | 前端工程目录列 audioClock.js、stores/host.js、LeaderboardPanel.vue | 目录已标注“目标”，这些文件不在基线中；职责实际在大型 Vue 组件 | 增加实现状态与实际落点，避免读者将目标目录作为源码导航，P2 |
| D14 | 主规格 §6.5 声称本地不保存服务端判定结果 | 同节示例和 online.js 保存 previewScore/previewAnsweredCount | 限定禁止保存的是逐题答案/解析；明确允许缓存总分预览，P2 |
| D15 | API 总榜段落写聚合持久化记录与内存线下状态 | totalLeaderboard 只读持久化线下快照与线上记录 | 明确未结算线下得分不进入总榜，P2 |
| D16 | API finish 前置条件只列末题 ANSWER_REVEALED | canFinishRound 还要求 timelineRunning=true | 标明暂停状态不可结束，或调整目标规则并实现，P2 |
| D17 | 部署配置包含 APP_FRONTEND_ORIGIN | REST/WS 配置使用任意 Origin，未读取该配置 | 标注未生效限制或实现单一白名单来源，P2 |
| D18 | 音频和上线文档包含测量结果、目标值、外部对象与服务器状态 | Git 内容不能证明当前 OSS 对象和部署与测量样本一致 | 测量报告绑定素材 SHA、对象版本、浏览器版本、日期和采样结果，P1 |

位置依据包括 [主规格](../product/dual-mode-development-spec.md)、[存储规格](../data-and-config/leaderboard-and-storage.md)、[前端架构](../frontend/frontend-architecture.md)、[API](../backend/api-spec.md)、[CI/CD](../ops-and-deployment/cicd.md)、[部署状态](../ops-and-deployment/deployment-status.md)、[音频定位规格](../data-and-config/audio-seek-quality.md)。代码对应项集中列于 Review 报告，避免复制第二套问题编号。

## 3. 建议的文档分工

### 3.1 保留主规格，但减少重复契约

主规格负责模式边界、核心业务不变量、信任模型和跨模块验收；接口字段仅由 api-spec/OpenAPI 定义，时间字段与版本公式仅由 timeline-spec 定义，排名与去重由 event-rules 和存储契约明确分工。架构概述应链接这些规则，避免复制完整 JSON 和长表。

| 主题 | 权威文档 | 实现来源 | 验收来源 |
| --- | --- | --- | --- |
| 入口与参与模式 | dual-mode-development-spec | router、JoinView | 路由与跨模式场景 |
| 线下状态/时间 | state-machine | ActivityService | ActivityFlowTests、ManualControlTimelineTests、暂停边界测试 |
| 线上尝试与锁定 | 主规格 + API | online.js、OnlineAttemptService | OnlineFlowTests、浏览器生命周期测试 |
| 编排/题集版本 | timeline-spec | TimelineStore | 输入夹具、内容/时间变更测试 |
| 榜单提交与兼容 | leaderboard-and-storage | LeaderboardStore、Python 脚本 | CompatibilityTests、写盘故障与共享样例 |
| 音频交互 | frontend-architecture + views-and-ui | HostView、OnlinePlayPanel | 刷新/重连/seek/等待/播放受限矩阵 |
| 配置与部署 | local guide、cicd、oss-audio | env、workflow、shell、systemd | 干净构建、临时发布演练、外网检查 |

### 3.2 分开现状、目标与实测证据

每篇规格可使用统一元信息：适用代码范围、文档类型、实现入口、验收入口、外部依赖。正文中的现状声明必须有代码或环境证据；目标行为标注“目标规格”；测量结论列出样本与方法。

deployment-status 的服务器 IP、证书、OSS 配置和 Secrets 配置具有时间性，适合作为注明观测日期的运维记录，不能作为源码的永久部署保证。修订原因与实现经过由 Git/PR 表达，不在工程正文维护迁移叙事。

音频文档已有很有价值的素材实测：CBR 文件大小、帧表、定位误差、静音统计。建议将结果固定在独立 measurement 文件和机器可读 JSON 中，规格仅保留判定标准和索引。题尾静音上限属于目标；0.139 秒定位误差属于特定测量样本，两者不能互相替代。

## 4. 应补齐的具体契约

### 4.1 状态转换表增加失败列

每条动作包含：起始状态、timelineRunning 条件、roundId 与运行实例、时间基准变化、存储结果、广播、重复请求和失败恢复。重点覆盖 finish/lock、暂停期间答题、末题提前结束和重连恢复。

归档表至少区分四种结果：JSON 未写入；JSON 成功而 TXT 失败；全部写入成功但广播失败；客户端超时但服务端已提交。相应 UI 文案、重试操作和幂等键应由契约决定。

### 4.2 媒体状态与业务状态分离

明确 metadata-ready、seeking、buffering、playing、autoplay-blocked、paused、error 与业务 IDLE/IN_PROGRESS/RESULT_READY 的组合。为每种异步操作规定取消条件和上下文归属，不能仅以一个 phase 字段承载所有网络和媒体状态。

主持人恢复需要规定：可信音频位置来源、允许误差、服务端正在运行但浏览器无法播放时的处置、另一控制台推进后的同步责任。线上恢复需要规定：本地数据失效、题集更换、跨标签页锁定及晚到响应的处理。

### 4.3 发布 manifest 与干净检出契约

建议 manifest 至少含代码 SHA、前后端产物哈希、题库/编排哈希、音频对象版本、配置模板版本和数据格式兼容范围。代码回滚与素材回滚是两个操作，应列出联合恢复要求。

构建文档明确测试只读取已入库夹具；生产素材必须在运行前准备。既有 CI 已存在，治理目标是让该 CI 在干净检出可通过，并为 release 激活脚本增加故障测试，不是重复搭建一套流水线。

## 5. 建议目录增量

保留已有目录，按实际需要增加以下文件；这些是建议落点，不代表均已实现：

```text
docs/quality/test-strategy.md             # 测试分层、干净检出、失败路径
docs/quality/acceptance-matrix.md         # 需求到自动测试/人工验收映射
docs/backend/openapi.yaml                # HTTP 可机读契约
docs/data-and-config/config-reference.md # 配置来源、消费位置、构建期/运行期
docs/data-and-config/release-manifest.md # 素材与产物兼容标识
docs/measurements/                       # 有样本标识的音频与设备测量
docs/reviews/                           # 基线、问题、证据及复现说明
```

根 README 应能在一页内回答：系统做什么、线上/线下入口、支持工具链、素材从哪里准备、如何运行测试、正式部署读哪篇。场控 SOP 保留岗位操作与异常出口，避免包含 API 字段和源码布局。

## 6. 实施优先级与完成标准

| 阶段 | 内容 | 完成标准 |
| --- | --- | --- |
| A：可运行与可恢复 | D01–D09、D18；构建夹具、音频恢复、归档和回滚语义 | 新检出可运行测试；现场人员可按 SOP 处理断线、保存失败和发布失败 |
| B：契约明确 | 版本/身份/媒体状态、配置消费表、API 失败结果 | 每项核心声明有实现来源或明确目标标签；重复定义收敛到权威文件 |
| C：证据可持续 | OpenAPI/Schema、链接检查、验收矩阵、素材 manifest | CI 校验文档示例与契约，发布可追溯到具体代码和素材 |

PR 的必要检查为：相关工程文档先行；目标与现状可区分；变更影响的正常与失败场景都有验收；外部测量不冒充当前生产事实。高风险功能测试应集中覆盖业务不变量，文案及链接调整使用轻量校验即可。
