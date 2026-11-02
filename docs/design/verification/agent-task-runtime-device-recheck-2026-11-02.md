# Agent 任务运行时真机复核（P1a / P1b / P0 开关，2026-11-02）

## 结论

PJX110 / Android 15 上复跑通过：

- **P1a**：真实 GLM-4.7 模型、SYSTEM 载体长时 run 71817ms（工具等待 60s），父会话 busy 后补投一次；
  USER 兼容载体 11456ms。外部 force-stop 后重启，账本 `INTERRUPTED` + delivered，未自动续跑父生成。
  两次验收前后正常 provider 设置文件 SHA-256 一致。
- **P1b**：受控后端下平台通知路由、批准/拒绝、通知权限拒绝、批准路由不可用、数据库/UI 验收全部通过；
  隔离测试库与隔离 settings 文件，正常 provider 设置未变。
- **P0 回滚开关**：`taskCenterEnabled` / `namespacedNotifications` / `dynamicRunGate` /
  `killShellTasksOnRunCancel` 已进 **设置 → 通用 → 常规 → 护栏与预算**（默认开，三语）；
  真机隔离 DataStore 落盘/回读通过；手拨 `taskCenterEnabled=off` 后 Extensions 页任务中心入口消失，
  随后恢复默认。截图见 [task-runtime-rollback-switches](../../img/task-runtime-rollback-switches.png)。

## 环境与隔离

- 设备：PJX110，Android 15，`adb` 序列 `6c841f95`。
- 包：`app.androidrik.rikkahub.debug`（debug）+ `MemoryIsolationTestRunner`；未覆盖 release。
- P1a/P1b 使用 `runtime-acceptance` 隔离库与隔离 settings；正常设置文件只做只读 SHA-256 指纹。
- 临时智谱 key 仅存在于本机回环代理内存（`TASK_RUNTIME_TEST_KEY` 环境变量传入，代理不打印/不落盘）；
  手机只拿到 `127.0.0.1:19001` 占位地址。验收后代理、fixture、`adb reverse` 均已清理；
  `git grep`/`.tmp` 复查未发现 key。**建议按约定轮换该 key。**

## 结果

| 场景 | 命令/证据 | 结果 |
|---|---|---|
| P1a SYSTEM + GLM-4.7 真实长时 | `verify_task_runtime.py --seconds 60 --carrier system` | `duration_ms=71817; busy_deferred=true; delivered_once=true`；上游 3 次 HTTP 200 |
| P1a USER 兼容载体 | `--seconds 1 --carrier user` | `duration_ms=11456; busy_deferred=true; delivered_once=true` |
| P1a 外部杀进程重启 | 同上（seed/verify） | `interrupted + delivered once; no automatic generation` |
| P1b 平台通知 | `verify_task_runtime_p1b.py` | passed |
| P1b 批准 / 拒绝 | 同上 | approve: executed=true；deny: executed=false；均 terminal+delivered |
| P1b 权限拒绝 / 批准路由不可用 | 同上 | passed；不假装可批准 |
| P1b 数据库 + notice UI | 同上 | passed，task-tree 截图已查看 |
| P0 四开关设置页 | 手工导航 + 截图 | 四个开关默认全开 |
| P0 开关 DataStore 往返 | `P0RollbackSwitchInstrumentedTest` | OK (1 test) |
| P0 taskCenter 关闭入口 | 手工拨 off → Extensions 页 | 任务中心入口不存在（dump 计数 0），随后恢复 on |

JVM 全量（`cleanTest` 强制重跑）：**app 2823 例、ai 479 例、workspace 169 例，0 失败**
（app 2 个既有跳过、workspace 20 个既有跳过）。

## 验收边界（明确未覆盖）

1. **Doze 未在本轮重跑**：P1a 报告中的 deep IDLE 用例依赖设备进入 Doze 与进程可能被杀；
   本轮以「外部 force-stop → 重启补投」覆盖同一恢复契约，不等价于无限后台存活承诺。
2. **P1b 的审批/通知路径用受控后端**，未用真实 GLM 模型跑审批通知动作；真实模型覆盖在 P1a。
3. **开关的消费端行为**：仅 `taskCenterEnabled` 做了真机手拨验证；其余三个（通知命名空间、
   动态闸、取消级联）由 JVM 默认值测试 + 源码接线守卫钉住，未在真机上逐个观察行为差异。
4. **父会话 busy** 仍由生产 `ConversationSession.setJob` 注册受控 Job 模拟，没有同时运行第二个付费父模型。
5. 完整聊天页人工导航、不同 ROM 的后台存活、无网/弱网仍未扩展验证。

## 复现

```bash
./gradlew :app:cleanTestDebugUnitTest :ai:cleanTestDebugUnitTest :workspace:cleanTestDebugUnitTest \
  :app:testDebugUnitTest :ai:testDebugUnitTest :workspace:testDebugUnitTest
./gradlew :app:installDebug :app:installDebugAndroidTest -PmemoryIsolatedTests=true
# 若 Gradle 装包受 USB 掉线影响，可手工：
adb install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

python scripts/task_runtime_fixture.py --port 19000 &
adb reverse tcp:19000 tcp:19000
python scripts/verify_task_runtime_p1b.py --base-url http://127.0.0.1:19000/v1 \
  --output .tmp/runtime-acceptance/p1b

TASK_RUNTIME_TEST_KEY="$(read -s -p key)" python scripts/task_runtime_proxy.py \
  --upstream https://open.bigmodel.cn/api/paas/v4/chat/completions --port 19001 &
adb reverse tcp:19001 tcp:19001
python scripts/verify_task_runtime.py --base-url http://127.0.0.1:19001/v1 --protocol openai \
  --model glm-4.7 --backend real --seconds 60 --carrier system \
  --output .tmp/runtime-acceptance/glm-system-p1a
python scripts/verify_task_runtime.py --base-url http://127.0.0.1:19001/v1 --protocol openai \
  --model glm-4.7 --backend real --seconds 1 --carrier user \
  --output .tmp/runtime-acceptance/glm-user-p1a
```

证据目录：`.tmp/runtime-acceptance/p1b/`、`glm-system-p1a/`、`glm-user-p1a/`、`p0-switches/`
（gitignored，仅本地，不含 API key）。

---

## 2026-11-02 晚补充复跑（任务中心分段 / 三语 / 设备清单）

变更起点：`5c94e12c1`（人优先分段 + P1b/P4 文案三语）。设备仍是 PJX110 / Android 15 / `6c841f95`。

### 复跑结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| JVM 全量 | `:app:testDebugUnitTest :ai:testDebugUnitTest :workspace:testDebugUnitTest` | app **2830**（2 跳过）、ai **479**、workspace **169**（20 跳过），0 失败 0 错误 |
| 真机 P1a 投递库 | `:app:connectedDebugAndroidTest` 指定 `AgentRunDeliveryInstrumentedTest`（Gradle 装包） | 8/8 通过 |
| 真机 P0 开关 | 同上 `P0RollbackSwitchInstrumentedTest` | 2/2 通过（含 `scheduledHeadlessEnabled` 默认关/落盘/回读） |
| 真机通知平台 | 手工 `adb install` → `pm grant POST_NOTIFICATIONS` → `am instrument -e class TaskNotificationInstrumentedTest` | **3/3 通过**；修正了取消通知的 Binder 快照竞态（见下） |
| 真机 notice UI | 同上 `NoticeUiAcceptanceTest` | 3/3 通过（SYSTEM/USER 同卡、任务树批准） |
| 真机工作区后台任务 | 同上 `WorkspaceBackgroundTaskInstrumentedTest` | 8 例执行、0 失败；无 rootfs 的用例按 `assumeTrue` 跳过 |
| P1b 生产运行时验收（受控后端） | `python scripts/verify_task_runtime_p1b.py --base-url http://127.0.0.1:19000/v1`（本机 fixture + `adb reverse`） | 全部通过：approve `executed=true`、deny `executed=false`、路由不可用 `executed=false`，三条均 `terminal=completed;delivered=true`；权限拒绝、DB+UI 均 passed；provider 设置指纹未变，通知权限已恢复 |

### 环境坑（下次照做）

1. **Gradle `connectedDebugAndroidTest` 会重装 target 包，运行时权限被复位**：通知类用例必须手工装包后
   `adb shell pm grant app.androidrik.rikkahub.debug android.permission.POST_NOTIFICATIONS`（+ `cmd appops set ... POST_NOTIFICATION allow`），
   再 `adb shell am instrument -w -e class <Class> app.androidrik.rikkahub.debug.test/androidx.test.runner.AndroidJUnitRunner`。
2. 这台 ROM 上 `NotificationManager.cancel()` 后的 `activeNotifications` 快照会晚一拍；
   `TaskNotificationInstrumentedTest` 已改为轮询 3s（`5c94e12c1`）。
3. 重装 target 包会清掉设备上的工作区 rootfs，`WorkspaceBackgroundTaskInstrumentedTest` 因此整组
   `assumeTrue` 跳过；要真跑需先在应用内装好一个工作区。
4. `verify_task_runtime_p1b.py` 用 `MemoryIsolationTestRunner`，Gradle 默认 APK 未声明它；必须先
   `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PmemoryIsolatedTests=true` 再 `adb install`，
   否则报 `Unable to find instrumentation info`。
5. 本轮 P1b 证据在 `.tmp/runtime-acceptance/p1b-2026-11-02-late/`（含 `results.json` 与任务树截图）。

### 真机待验清单（按优先级）

- [ ] **定时取消时序**：到点运行中在通知里点「停止」→ `ACTION_STOP` → `Job.cancel` → 账本 `CANCELLED`
      （P4a/P0.5 单测已覆盖状态机；真机竞态未验）。
- [ ] **五分钟审批超时**：真机实等一次（而非虚拟时间），确认无遗留待批、工具被拒绝且 run 收尾。
- [ ] **系统通知动作人工点击**：批准/拒绝/停止由人手点一次（当前由测试调用生产队列）。
- [ ] **启动 orphan 对账**：保留一条运行中 run，强杀进程后重启，确认任务中心/账本出现 `INTERRUPTED`
      且不自动续跑父生成（P1a 已验投递，P3 扫描仅 JVM 报告）。
- [ ] **旧备份 ZIP → v58 完整导入**：用真实旧版本备份走一遍导入（目前只验了独立库迁移与降级隔离）。
- [ ] **完整聊天页人工导航**：会话内任务树入口 → 详情 → 返回；以及任务中心三段在真实数据下的滚动。
- [ ] **KeepAlive / 不同 ROM 长时后台**：Doze、省电策略、厂商 ROM 杀后台后 run/通知是否存活。

