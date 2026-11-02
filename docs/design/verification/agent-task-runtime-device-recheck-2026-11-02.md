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
