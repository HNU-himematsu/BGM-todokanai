# 基线复现说明

## 源码与环境

基线：`233f2183597e3a6aa21c3dc7b7276e02b9db6cca`。验证说明见 [verification-scope.md](../verification-scope.md)，结果见 [Review](../repository-review.md)。探针断言描述已观测行为，成功不代表产品正确。

## 干净检出与基线测试

从仓库根目录执行，使用唯一的隔离目录，不复制未跟踪活动素材：

```powershell
$reviewFolder = Join-Path (Get-Location) ('work/review-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $reviewFolder | Out-Null
git archive --format=zip --output="$reviewFolder/source.zip" 233f2183597e3a6aa21c3dc7b7276e02b9db6cca
Expand-Archive -LiteralPath "$reviewFolder/source.zip" -DestinationPath "$reviewFolder/source"
Push-Location "$reviewFolder/source/backend"
try { mvn -B -ntp test } finally { Pop-Location }
```

预期：20 项测试，contextLoads 因缺少 `data/questions.json` 产生 1 个错误。测试输出在隔离目录的 `backend/target/surefire-reports/`。请勿将真实题库复制到副本来掩盖干净检出失败。

前端在副本 `source/frontend` 内执行 `npm ci` 和 `npm run build`。

## Java 行为探针

将 [CurrentReviewTests.java](CurrentReviewTests.java) 复制到副本的 `backend/src/test/java/com/jisong/bgm/service/`，从该 backend 目录执行：

```powershell
mvn -B -ntp '-Dtest=CurrentReviewTests' test
```

4 项探针使用入库测试题库和 JUnit 临时目录，分别验证暂停期间作答计时、线下 TXT 导出失败后的 FINISHED、线上 TXT 失败后的实际锁定、题目答案变化但版本不变。

## JavaScript 行为探针

从基线仓库根目录执行：

```powershell
node docs/reviews/repro/current-frontend-probe.mjs
```

2 项探针使用源码函数及最小 ref/computed/网络替身，验证清理进度后 attemptId 保留、连接恢复事件不触发主持人音频校准。它们不替代真实浏览器事件和播放权限验收。

## 本地证据路径

本次原始日志位于 Git 忽略目录 `work/review-233f218/`：`backend-test.log`、`frontend-install.log`、`frontend-build.log`、`review-probes.log`。日志不随 Git 分发；仓库中的探针与执行步骤用于独立复核。
