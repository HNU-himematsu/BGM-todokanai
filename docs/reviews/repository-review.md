# 双模式答题系统 Review 报告

## 1. 审查基线与结论

审查日期为 2026-11-05，基线为远端 `origin/main` 提交 **`233f2183597e3a6aa21c3dc7b7276e02b9db6cca`**。工作分支经过远端获取与快进，`HEAD` 和 `origin/main` 一致。报告覆盖线上本地答题、线下时间轴、榜单聚合、音频交互、CI/CD 和工程文档，不推断生产服务器的实际部署状态。

系统已具备双模式服务拆分、题库强校验、HTTP 错误分类、Range 音频、外部音频、原子文件替换、线上锁定幂等、WebSocket 重连刷新，以及完整的发布目录和 systemd 配置。审查不将这些已实现能力列为缺失。

主要问题集中在 **干净检出的测试失败、主持人重连后的音频恢复、归档部分成功的处理，以及回滚目录保留**。代码缺陷列表包含 4 项 P1、7 项 P2；设计信任边界、性能和待测风险独立列出。

## 2. 验证方法与结果

使用 `git archive HEAD` 生成纯受版本控制文件的副本，位于 `work/review-233f218/source/`。不依赖开发机未跟踪题库或活动数据。环境：Windows 11、Java 26.0.2.1、Maven 3.9.11、Node.js 24.14.1、npm 11.11.0；项目和 CI 的 Java 编译目标为 17，本地结果不代表 Java 17 运行环境实测。

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| 前端 `npm ci --no-audit --no-fund` | 成功，136 个包 | 存在 lucide-vue-next 弃用提示 |
| 前端 `npm run build` | 成功，26.05 秒 | Ant Design chunk 1,495.17 kB，gzip 461.88 kB；存在大包警告 |
| 干净副本后端 `mvn test` | **20 项，19 通过，1 错误** | BgmApplicationTests 因缺少 data/questions.json 无法加载上下文 |
| 独立 Java 行为探针 | 4 项通过 | 暂停期间提交、线下 TXT 失败、线上 TXT 失败、内容变化但版本不变 |
| 独立 JavaScript 探针 | 2 项通过 | 进度清理保留 attemptId、SOCKET_CONNECTED 不校准主持人音频 |

探针成功表示问题或边界得到观测，不代表缺陷修复。[验证规格](verification-scope.md) 与 [复现说明](repro/README.md) 提供隔离方法、命令和证据路径。

未执行生产部署、真实服务器写操作、移动端浏览器验收、实际音频定位测量、容量压测、断电耐久性或依赖漏洞数据库审计。音频素材和生产配置不属于仓库可直接证明的事实。

## 3. 代码问题清单

| 编号 | 等级 | 问题 | 证据 |
| --- | --- | --- | --- |
| C01 | P1 | 上下文测试依赖未入库题库，干净构建失败 | 运行确认 |
| C02 | P1 | 主持人重连/刷新只恢复快照，不恢复音频位置和播放状态 | 隔离函数探针 + 静态调用链 |
| C03 | P1 | 线下归档失败后进入 FINISHED，缺少重试和准确成功状态 | 运行确认 |
| C04 | P1 | 回滚成功后的历史清理可删除正在运行的 release | 静态确认 |
| C05 | P2 | systemctl restart 失败绕过回滚分支 | 静态确认 |
| C06 | P2 | 漏收重置事件的线下玩家无法自动恢复身份 | 静态确认 |
| C07 | P2 | 并发快照请求缺少顺序约束 | 静态确认 |
| C08 | P2 | 暂停期间提交与恢复后提交使用不同的耗时基准 | 运行确认 + 公式分析 |
| C09 | P2 | 线上 JSON 已提交但 TXT 失败返回锁定失败 | 运行确认 |
| C10 | P2 | 线上音频元数据加载缺少超时与重复点击保护 | 静态确认 |
| C11 | P2 | 分域配置下主持人 TXT 导出使用错误基址 | 静态确认 |

### C01 [P1] 干净检出的后端测试无法完成

**位置**：[BgmApplicationTests.java](../../backend/src/test/java/com/jisong/bgm/BgmApplicationTests.java):6；[TimelineStore.java](../../backend/src/main/java/com/jisong/bgm/service/TimelineStore.java):132；[CI](../../.github/workflows/ci.yml)；[Deploy](../../.github/workflows/deploy.yml)。

`BgmApplicationTests` 没有测试属性，默认加载 `data/timeline.json` 及同目录 `questions.json`。Git 只跟踪 timeline，questions 被忽略。干净副本执行测试产生 `NoSuchFileException: .../backend/data/questions.json`，其余 19 项测试通过。

**影响**：CI 的 `mvn verify` 和发布构建的 `mvn clean package` 均包含该测试，仓库未提供生产题库注入步骤，干净执行会被阻断。开发机存在题库时可能掩盖问题。

**建议**：上下文测试显式使用已入库测试题库/编排，榜单输出放到临时目录。生产题库继续由运营提供，不必为通过测试提交真实答案。**验收**：纯 Git 检出执行 CI 相同命令全部通过，不读取 backend/data 下未跟踪文件。

### C02 [P1] 主持人快照恢复不包含音频校准

**位置**：[HostView.vue](../../frontend/src/views/HostView.vue):119–157、166–201；[socket.js](../../frontend/src/services/socket.js):23。

**触发**：活动播放中刷新主持人页面，或断线期间发生暂停、下一题、结束操作，随后重连。socket 会发出 SOCKET_CONNECTED 并触发 refresh；refresh 更新 `timelineRunning/status` 等字段，但不使用快照 `audioPositionMs` 校准音频。syncAudio 仅处理 RESET、ROUND_ENDED、PAUSED、RESUMED，没有连接恢复分支。隔离探针对 SOCKET_CONNECTED 观测到零次定位、零次播放。

**影响**：后端时间轴继续推进而主持人音频停留在起点或旧位置；漏收暂停/结束广播时，播放器也不会依照快照停止。单纯“重连刷新成功”不能证明放音同步恢复。

**建议**：基于服务端完整快照恢复音频位置与播放状态；等待 metadata/seeked/canplay，并处理浏览器禁止自动播放的显式继续入口。**验收**：运行中刷新、漏收暂停、漏收结束、另一控制台切题四种情况均能校准，或明确暂停服务端等待人工恢复。

### C03 [P1] 线下结算状态早于归档成功，失败后没有恢复入口

**位置**：[ActivityService.java](../../backend/src/main/java/com/jisong/bgm/service/ActivityService.java):543–554；[LeaderboardStore.java](../../backend/src/main/java/com/jisong/bgm/service/LeaderboardStore.java):68–80。

finishRound 先清理挂起动作并设置 FINISHED，再保存榜单；存储失败会跳过 ACTIVITY_FINISHED 广播。手动 finish 还会先停止时间轴。探针把 TXT 目标设为非空目录后完成末题：调用抛 StorageException，状态为 FINISHED，JSON 中已经有快照，重复 finish 被状态守卫拒绝。JSON 读取/写入失败同样发生在状态设置之后，但此时本轮可能根本没有落盘。

**影响**：内存结束态与归档结果不一致，页面可能缺少结束通知；业务无法安全区分未归档与仅导出失败。reset 清理内存会使未归档结果失去恢复基础。

**建议**：持久化提交与 TXT 派生导出拆分；提供 archiveStatus 和重试入口，以结算实例标识保证幂等。轮次 ID 表示可重复进行的业务轮次，不能单独充当一次结算的唯一键。**验收**：JSON/TXT 分别故障后状态准确、可恢复、广播结果明确且不重复归档。

### C04 [P1] 回滚版本可被成功后的清理逻辑删除

**位置**：[activate-release.sh](../../deploy/activate-release.sh):57–61。

脚本按 release 目录名倒序保留最近 KEEP_RELEASES 个，未排除 current 软链接目标。假设存在六个版本，KEEP_RELEASES=5，激活最老版本并健康检查成功，该版本仍处于清理列表。失败发布会遗留目录且不执行成功清理，因此正常流程也可能积累超过五个目录。

**影响**：脚本打印 activated，但 current 已指向删除的目录；Nginx 静态资源失效，后端后续重启失败。

**建议**：清理集合显式排除解析后的 current、目标 release 和需要保护的恢复版本；保留策略以这些不变量为先。**验收**：超过保留上限时激活最老存在版本，目标目录及 app.jar/dist 均保留，current 可解析。本审查未在真实服务器执行清理。

### C05 [P2] 重启命令失败不执行恢复

**位置**：[activate-release.sh](../../deploy/activate-release.sh):2、30–35、46–54。

脚本启用 `set -e`，切换 current 后直接执行 sudo systemctl restart。sudo 权限错误、单元配置错误或 systemctl 命令本身失败会立即退出，无法进入健康失败的回滚分支。

**建议**：将切换后的全部失败路径统一纳入恢复逻辑，区分首次发布无旧版本与有可恢复版本；恢复后检查实际服务结果。**验收**：替身 restart 返回非零，current 恢复到原目标且脚本失败退出；不能仅覆盖 HTTP 健康超时。

### C06 [P2] 漏收重置时失效身份没有前端恢复分支

**位置**：[OfflinePlayPanel.vue](../../frontend/src/components/OfflinePlayPanel.vue):61–87、135；[activity.js](../../frontend/src/stores/activity.js):91–101。

正常收到 ACTIVITY_RESET 会清除 playerId 并返回登记页。但断线期间重置、后端重启或用户持旧 sessionStorage 刷新时，服务端返回“参与者身份无效”，store 只设置 error，不清理身份。首次加载 state=null，而模板整个 section 使用 v-if="state"，错误提示也位于该 section 内，可能显示空内容。重连不能依靠补发历史 RESET 恢复。

**建议**：快照错误包含稳定身份错误码，刷新流程清理过期线下身份并跳转登记页；loading/error 独立于 state 渲染。**验收**：断线中 reset 和后端重启后的首次访问均有恢复入口，正常旁路网络错误不清除有效身份。

### C07 [P2] 快照乱序可以回退题目、控制状态和最终结果

**位置**：[activity.js](../../frontend/src/stores/activity.js):95；[HostView.vue](../../frontend/src/views/HostView.vue):166–190。

每次玩家作答、连接恢复、主持人操作都可能并行刷新；响应直接写入 store，没有请求序号、轮次运行实例或服务端 revision。较早请求的旧状态在较新状态之后抵达会覆盖页面。主持人 refresh 的多个 GET 也不是同一事务快照。

**建议**：合并刷新，至少过滤过期请求；更完整方案是返回运行实例 ID 与递增 revision，拒绝不属于当前上下文的响应。**验收**：乱序返回 ANSWERING、ANSWER_REVEALED、FINISHED，以及 reset 交错时页面不回退、不串轮。

### C08 [P2] 暂停期间的计时与恢复后计时不一致

**位置**：[ActivityService.java](../../backend/src/main/java/com/jisong/bgm/service/ActivityService.java):173–195、246–257。

暂停只停时间轴，不改变 ANSWERING；submitAnswer 不检查 timelineRunning，仍使用 now−questionNextEpochMs。恢复时 questionNextEpochMs 平移整个暂停时长。探针暂停约 160ms 后提交，答案成功且耗时包含暂停时间。

**影响**：A 在暂停期间提交，计入已等待的暂停时间；B 等恢复后提交，暂停时间被减掉。同分排序可能让更晚提交的人耗时更少。暂停期间锁卷补记也使用未平移基准。

**建议**：先在业务规格明确暂停是否允许答题；若允许，使用冻结逻辑时钟；若禁止，服务端与 UI 一并限制。**验收**：暂停中提交、恢复后提交、暂停中锁卷、暂停中加入四种情况的耗时符合统一公式。

### C09 [P2] 线上锁定部分成功被报告为失败

**位置**：[LeaderboardStore.java](../../backend/src/main/java/com/jisong/bgm/service/LeaderboardStore.java):83–105；[OnlineAttemptService.java](../../backend/src/main/java/com/jisong/bgm/service/OnlineAttemptService.java):80–112；[online.js](../../frontend/src/stores/online.js):293–327。

线上 JSON 写入成功后，refreshSummary 的 TXT 失败使请求抛错，ONLINE_SCORE_LOCKED 广播未发出。探针确认锁定记录实际存在，同 attempt 重试成功返回已有记录。前端只在 409 时查询锁定状态，500 则仍显示可重试结果页，用户可能选择重试答题，最终又遇到不可覆盖的已锁成绩。

**建议**：持久化成功作为锁定成功判据；TXT 导出单独报告并补偿重建。网络不确定/500 路径主动查询已有记录，确认前不承诺可重新答题覆盖。**验收**：TXT 故障下锁定状态一致、通知可恢复、同 attempt 无重复记录。

### C10 [P2] 元数据加载可无限等待并积累并发开始操作

**位置**：[OnlinePlayPanel.vue](../../frontend/src/components/OnlinePlayPanel.vue):179–237、494。

ensureMetadata 仅监听 loadedmetadata/error，没有超时、取消或卸载清理；开始按钮没有 starting/loading 锁。OSS 连接一直挂起且不发 error 时，开始操作持续等待，用户重复点击又执行 load 并增加监听。后续事件可能同时唤醒多个 startOrResume，反复重设本地 attempt 状态。

**建议**：元数据等待使用超时与 Abort/清理机制，开始流程单实例，离开页面后异步操作不得播放音频或改写状态。**验收**：无响应、延迟响应、重复点击与导航离开都有确定结果。

### C11 [P2] TXT 导出绕过 VITE_API_BASE

**位置**：[HostView.vue](../../frontend/src/views/HostView.vue):52–54；[api.js](../../frontend/src/services/api.js):3–6。

普通请求使用 VITE_API_BASE，TXT 链接却使用 VITE_PUBLIC_BASE_URL/页面 origin。前端域名 A、API 域名 B 或 API 自定义路径前缀时，控制台能使用 API，导出链接仍访问 A 的 `/api`。

**建议**：与 API 客户端共享端点解析，二维码基址只用于参与者页面。**验收**：同域、分域、自定义 API 前缀均能访问正确导出资源。

## 4. 设计边界与次级维护项

### 4.1 线上身份和计时是明确的信任模型选择

主规格明确 participantId 是浏览器生成的合并键，不是认证凭据；durationMs 由浏览器提供，不提供防篡改计时。因此“未认证的线上成绩”和“客户端耗时可修改”应作为设计边界，不能直接宣称违反既有规格。

该设计的实际后果包括：公开线下榜中的 participantId 可被另一调用者用于抢先锁定线上成绩；预览接口允许单题/部分题重复判分，少量请求即可推导答案；清站点数据可建立新身份。若线上/总榜仅供娱乐，文档应明确可信度；若用于兑奖，需服务端参与凭据、计时约束和受控计分过程。线下奖品榜与线上娱乐榜的用途不应混淆。

默认 `123456` 和部署模板 `change-me` 仍需部署门禁，但本次没有读取服务器秘密，不能认定生产正在使用默认口令。app.frontend-origin 未被 WebConfig/WS 配置消费，任意 Origin 仍获允许；CORS 本身不替代认证。

### 4.2 题集版本只标记编排元组

TimelineStore 的版本只含题号与音频/作答区间，符合主规格 §4.4。探针修改正确答案后版本保持相同。因此更换选项顺序、题干、答案或实际音频但保留时间字段，不会使浏览器进度失效，旧选项下标可能按不同内容计分。

建议把该事实列入素材发布契约；如需跨发布一致性，使用内容/答案/音频版本参与的规范化哈希。该项首先需要规格决策，不能只修改实现而不更新计算契约。

### 4.3 本地状态与媒体生命周期

独立 JS 探针确认 resetAttemptState 不清 attemptId；同一 Pinia 实例在删除进度或切换上下文后 beginAttempt 会沿用运行中的旧标识。该结果不等于自动串分，但削弱以 attemptId 过滤异步预览/锁定响应的可靠性。建议上下文重置同时清理 attemptId、锁定结果与异步请求代数。

storage.write 中可选链在浏览器存储访问返回 null 时仍返回 true，无法准确报告写入失败；路由守卫又只读取持久存储。应验证“存储不可用仍可答题”的文档承诺，包括 localStorage 和 sessionStorage 都受限的场景。

线下页面仍可能用未提交的 selected 草稿渲染揭晓选择；playerCount 仍表示登记数而不是在线或完成人数。这些属于显示契约问题；建议使用服务端 hasAnswered 和精确人数术语。

### 4.4 调度与容量

取消任务使用 cancel(false)，runItem 仅检查 timelineRunning 和 roundId，不检查回调是否仍在 pendingTasks；已开始并等待锁的回调可以在恢复/同轮重开后执行。建议使用运行代数与任务实例校验，特别覆盖手动提前 next、暂停恢复和同轮 reset。该竞态概率未实测。

每个 PLAYER_ANSWERED 广播触发全体线下客户端拉取，N 人答题约产生 N² 次请求；主持人一次 refresh 还包含多个 GET。ActivityService 与 Store 的同步锁及总榜重复文件读取是潜在瓶颈。先按现场人数和历史记录量测 p95/p99、调度偏差和文件写入时长，再决定缓存、合并刷新或存储迁移。

### 4.5 发布与数据边界

部署包不携带 shared/data；回滚代码也不会回滚题库、时间轴、外部音频和已写榜单结构。建议 release manifest 记录代码 SHA、题库/时间轴哈希、音频对象版本及最低兼容数据版本。健康接口仅证明 HTTP 进程存活，不验证实际题集、OSS 可播放、WS 或排行榜可写。

文件写入已经采用临时文件和原子移动回退，不存在“直接覆盖旧 JSON”这一缺陷；但多文件提交不具备事务性，原子移动回退的耐久性及多进程访问仍需明确支持边界。Python 导出与 Java 聚合需共享兼容样例，尤其是空时间、旧记录和跨活动数据。

## 5. 修复与验收顺序

1. **构建可重复性**：先为 C01 定义测试夹具路径和干净检出要求，使完整 CI 成为可靠入口。
2. **现场可靠性**：解决 C02、C03；为音频恢复和归档失败提供明确操作出口。
3. **部署可恢复性**：解决 C04、C05，并以假服务/临时目录覆盖失败恢复和保留策略。
4. **客户端与时间一致性**：C06–C10，补充断线、乱序、暂停、媒体超时和锁定部分成功测试。
5. **配置与规格治理**：C11、身份信任、题集内容版本、部署 manifest 和容量指标。

所有代码修复应先更新相应工程规格，再实现与验收。详细文档治理建议见 [文档系统改进意见](documentation-improvement-plan.md)。
