# 设计：B 档备份恢复 —— staged 换库 + 启动应用 + 失败回滚

- 日期：2026-10-26
- 状态：阶段 1+2 代码完成（PendingRestoreStore + 13 项 JVM 测试；RikkaHubApp boot 编排 + StartupState + RouteActivity 全屏 gate + 引擎切 stage-only，全量 2456 项 0 失败）；真机验收（§9 阶段 3）✅ 已通过（2026-10-26 用户确认，四场景均验）。实现偏差：①入库校验为 applyIfPending 的 validate lambda 注入（原稿 StagedDbValidator 接口 + xerial——实际 lambda + fake 即可，无新依赖）；②Room 打开确认失败按「不可重试」处理（重试会撞已启动的 Koin），进程重启后 rollbackIfUnconfirmed 自动回滚，比原稿「记录后照常 Ready」更稳。
- 来源：Yuihub 3.0.1（`6dcc7224`）`PendingRestoreStore` + `StartupState` 蓝图；本仓账本 §五表 15
- 前置：A 档（恢复前自动安全备份，`filesDir/safety_backup/` 滚动 3 份）已落地（`aaadfaf86`）

## 1. 动机：现状的两处真实风险

现行恢复（`BackupArchiveEngine.restoreFromBackupFileLocked`）在**运行中的进程里**完成换库：

1. **换库窗口期 Koin 持有已关闭的单例**（`applyStagedDatabaseFiles` → `appDatabase.close()` → `moveIntoPlace`，BackupArchiveEngine.kt:612-630）：`close()` 到 `exitProcess(0)` 之间，任何 DAO 访问都会打在已关闭的库上。重启弹窗虽不可 dismissing，但 `AppScope` 上的后台协程（记忆整理、FTS、用量记录）并不停——撞上就是 crash。
2. **换库后没有验证**：`moveIntoPlace` 成功即报成功并退出。若重启后 Room 打不开（迁移链断、onOpen 回调抛异常、磁盘瞬时故障），用户面对的是已覆盖的库 + 一个需要手动操作才能用上的 safety_backup。A 档保证「能救」，但没保证「自动救」。

B 档把「换库」从运行中的进程整体挪到**下一次启动、startKoin 之前**：那时 Room 还没打开、没有后台写者、失败可以无损回退——回滚窗口天然封闭。

## 2. Yuihub 3.0.1 蓝图（核实自 GitHub master；本地副本停在 2.5.8，实现前先 `git fetch`）

```
恢复侧（旧进程）                          启动侧（新进程）
─────────────────                        ─────────────────
stage():                                 onCreate:
  restore_pending/staged.db + 附加         ├─ WorkManager.initialize（手动，早于一切）
  + settings → fsync                      ├─ pending.marker 存在？
  → pending.marker 原子改名（最后写）       │   └─ 是 → applyIfPending()
                                          │         ├─ 成功 → startKoin → Ready
UI 只出「重启」弹窗，不碰现库               │         └─ 失败 → Failed(可重试) 全屏
                                          └─ 否 → rollbackIfUnconfirmed()
                                                  ├─ applied.marker 在但 rollback 目录也在
                                                  │   ⇒ 上次换库后没活过确认 → 回滚 rollback.db
                                                  └─ 无事 → startKoin → Ready

确认：startKoin 后用 Koin 里的 AppDatabase 跑 SELECT 1
     （证明 Room 含迁移链打开成功）→ confirmApplied() 删 rollback 目录
```

- `StartupState { Restoring / Ready / Failed(message, canRetry) }`：StateFlow，**初值 Restoring**，RouteActivity 全屏消费——非 Ready 一律渲染阻塞屏（转圈 / 错误+重试按钮）。
- **WorkManager「先 initialize 再 await」**：SystemJobService 可能在 Koin 之前拉起 worker，所以手动早初始化、worker 里 `awaitStartup()` 后再取 Koin。
- `applied.marker` 与 rollback 目录的存在组合区分三种状态：都无=无事；pending.marker=待应用；applied.marker+rollback 目录=已换库未确认（待回滚判定）。
- 170 行 JVM 测试（`PendingRestoreStoreTest.kt`，JUnit4 + TemporaryFolder，无 Robolectric——纯 File + SQLiteDatabase）钉 12 个场景。

## 3. 本仓方案与三处有意偏差

### 3.1 保留的骨架

原样移植：`StartupState` 状态机、startKoin 推迟、`PendingRestoreStore` 的 marker/目录约定（`restore_pending/`、`pending.marker`、`applied.marker`、rollback 目录、`restore_applied` 状态）、fail-closed 原则（任何失败不清 marker、不碰现库）、确认后才删 rollback、全屏阻塞 UI。

### 3.2 偏差一：WorkManager 层整段省略

**本仓没有 worker**（全仓无 `CoroutineWorker`；manifest 已 remove 默认初始化器；Koin 里的 `workManagerFactory()` 是死注册）。Yuihub 最绕的一段对我们不存在。动作：`RikkaHubApp.onCreate` 不需要手动 `WorkManager.initialize`；无 `awaitStartup()`。`startKoin` 推迟后 worker 不会凭空出现——如果将来立项 worker，必须补回这一层（在设计里留一句警句即可）。

### 3.3 偏差二：只有「换库」走启动期；settings 与附加文件照旧在恢复流内完成

Yuihub 把 settings/attachments 也 stage 到下次启动。我们不跟，理由：

- settings 走 DataStore + 应用层合并（`settingsStore`），写的是**另一套文件**，与换库无耦合，运行中应用是安全的（A 档批次已验证）；
- upload/fonts/skills/avatars 等附加文件恢复进 `filesDir/` 本来就与库无关；
- 把它们推迟到启动期只会拉长阻塞屏时间（118MB 库的 integrity_check 已经要几秒），没有收益。

**B 档范围 = 只把 `rikka_hub(.db/-wal/-shm)` 三件套的换位推迟。** staged 家族（`prepareStagedDatabase` 家族分流/旧版转换仍在恢复流内完成——那是纯文件操作）从 `cacheDir/restore_*` 移交进 `filesDir/restore_pending/`，写 `pending.marker`。

### 3.4 偏差三：rollback 用「改名让位」而非整库复制

Yuihub 在 `applyIfPending` 里把现库**复制**进 rollback 目录。改名让位（rename）等价且零拷贝：

```
校验 staged（user_version + integrity_check）——失败即停，现库未动
→ 现库三件套 rename 进 rollback/（同一目录，瞬时原子）
→ staged 主库 rename 为 <db>.restoring 再原子 rename 到位；sidecar 跟进
→ 写 applied.marker
```

崩溃点恢复规则相应放宽一条（Yuihub 只有「applied.marker+rollback 在」→ 回滚）：

- `pending.marker` 在、rollback 目录也在 ⇒ 上次在「让位后、staged 换入前」死了 ⇒ **先把 rollback rename 回来**，再重试应用（或直接当作可重试失败进 Failed 屏）。

rollback 目录里放的是让位出的原库三件套 + 一个 `db_did_not_exist` 标记（全新安装首次恢复的场景：回滚方向=删掉换入的库，而不是恢复一个不存在的旧库）。

## 4. 启动改造：RikkaHubApp.onCreate

现状（RikkaHubApp.kt:80-191）：

```
isolated 进程早退(85) → startKoin(86-91) → 通知渠道 → MemoryConsolidationRuntime.start()
→ 分词器预热 → … → AppScope.launch 一串清理/迁移(150-188)
```

目标：

```kotlin
onCreate:
  super / StartupTracer 等纯库初始化（不碰 Koin）
  if (isolated 进程) return          // 早退保持在最前
  _startupState = Restoring
  AppScope.launch(IO) {
      if (PendingRestoreStore.hasPending(filesDir)) {
          runCatching { PendingRestoreStore.applyIfPending(...) }
              .onFailure { _startupState = Failed(it.message, canRetry = true); return@launch }
      } else {
          runCatching { PendingRestoreStore.rollbackIfUnconfirmed(...) }
              .onFailure { log }       // 回滚失败只记日志，照常初始化（最后手段）
      }
      runCatching { initializeDependencies() }   // ← startKoin 从这里进
          .onSuccess {
              confirmRestoreAfterDatabaseOpen()      // Koin 拿 AppDatabase 跑 SELECT 1 → confirmApplied
              _startupState = Ready
              // 原 onCreate 里 startKoin 之后的所有步骤原样跟在 Ready 前：
              // 通知渠道、MemoryConsolidation、分词器、QuickJS、PluginManager、AppScope 清理…
          }
          .onFailure { _startupState = Failed(it.message, canRetry = false) }
  }
```

- 原 onCreate 中 **startKoin 之后的每一步都不得提前**：它们依赖 Koin（MemoryConsolidationRuntime）或依赖 Ready 语义（AppScope 清理会写库，如 avatar 迁移）。逐一过一遍挪进 `onSuccess` 分支，顺序不变。
- **RouteActivity 消费 StartupState**（基类 `RouteActivity`，两个 launcher 子类共享，一处 gate 全覆盖）：非 Ready 渲染全屏阻塞——Restoring=转圈+「正在安装已校验的备份…」；Failed(canRetry)=错误+重试按钮（`retryPendingRestore()` 重跑 apply，不重启进程）；Failed(!canRetry)=「请退出后重新启动」。内容页在 Ready 前不渲染。
- 边界：`WorkspaceDocumentsProvider` 被外部查询时 Koin 可能未就绪——可接受（崩溃重启进程，恢复继续），设计里记录不为它加 gate。

## 5. 恢复侧改造

`BackupArchiveEngine.restoreFromBackupFileLocked`：

- 保留：条目流/预算/manifest 校验/settings 应用/`prepareStagedDatabase`（家族分流+旧版转换，纯文件操作）；
- **删除**：`applyStagedDatabaseFiles` 调用（现库 close/清 sidecar/换位整套）；
- 新增：staged 家族三件套 `moveIntoPlace` 到 `filesDir/restore_pending/{staged.db,-wal,-shm}`，fsync，最后原子写 `pending.marker`（marker 最后写 ⇒ 不存在「marker 有、payload 无」状态）；
- `pending.marker` 已存在时第二次 stage **抛错**（同 Yuihub，测试钉住）；
- 恢复完成的 UI 语义不变：toast + 不可 dismissing 的「重启」弹窗（BackupPage.kt:128）→ `exitProcess(0)`；用户从最近任务杀掉进程结果等价——下次启动自动应用。

## 6. 失败矩阵（每一步的死法与出路）

| 崩溃点 | 现场状态 | 下次启动行为 |
|---|---|---|
| stage 中途（写 staged.db） | 无 marker（最后写） | 无感知，垃圾文件被下次 stage 清理 |
| stage 完成、用户没重启 | pending.marker 在 | 启动即应用（即使用户已在旧库上聊过——不可 dismissing 弹窗使窗口趋近于零；记录为已知权衡） |
| apply：staged 校验失败 | 现库未动 | Failed(可重试)；用户可重试；重试仍败可进备份页重选备份 |
| apply：让位后、换入前 | rollback 目录满员 + pending.marker 在 | 恢复规则：rollback rename 回位 → 重试或 Failed |
| apply：换入完成、applied.marker 未写 | rollback 目录满员 | 按「已换入未确认」处理：不回滚，直接进确认流程 |
| 换库成功、Room 打不开（迁移崩） | applied.marker + rollback 在 | **rollbackIfUnconfirmed 自动回滚** → startKoin → Ready；用户无感知拿到旧库 |
| 回滚也失败 | — | 只记日志，照常 startKoin（最后手段：一个可能损坏的库好过白屏） |
| 确认前正常退出、确认后 Room 打开成功 | confirmApplied 删 rollback | 无事 |

## 7. 测试计划（对标 170 行蓝图，纯 JVM）

`PendingRestoreStoreTest`（目标 ≥12 场景，JUnit4 + TemporaryFolder，PendingRestoreStore 只依赖 File + android.database.sqlite.SQLiteDatabase（JVM 不可用——用 `org.xerial.sqlite-jdbc`？）…

**修正**：Android `SQLiteDatabase` 在 JVM 不可用。方案：把 integrity_check / user_version 读取抽成 `StagedDbValidator` 接口（生产实现用 SQLiteDatabase，测试注入 JDBC 实现跑同一条 `PRAGMA integrity_check`）。xerial 依赖仅 testImplementation，不进产物。

场景（对齐 Yuihub 12 条 + 本仓偏差）：
1. staging 不触碰现库；2. 二次 stage 在 pending 未消费前抛错；3. staged 校验失败 → 现库 sidecar 完好、marker 保留可重试；4. staged payload 缺失 → fail-closed marker 保留；5. 启动安装完成：现库让位进 rollback、staged 到位、旧 sidecar 清除、marker 消费；6. 全新安装（原库不存在）安装 + 回滚方向=删除换入库；7. 让位后崩溃（rollback 满 + pending 在）→ rename 回位；8. applied.marker + rollback 在 → rollbackIfUnconfirmed 恢复原库；9. confirmApplied 删 rollback；10. 回滚失败不抛出只记日志；11. marker 原子性（模拟中途失败不产生半 marker）；12. staged wal/shm 一并安装与清除。

UI 层不测（全屏 gate 是纯 Compose 分支）。

## 8. 风险与开放问题

- **R-1 重启窗口权衡**：用户恢复后若长期不重启且继续在旧库产生新消息，重启即丢失这段增量。缓解=现有不可 dismissing 弹窗 + `exitProcess(0)`；进一步可在 stage 后直接自动退出（更激进，默认不做）。
- **R-2 onCreate 后移的步骤清单**必须逐一核对无「需要 Koin 的 Main 线程早访问者」（widget/快捷方式/服务）。已知 EntryProvider 之外的访问者：WorkspaceDocumentsProvider（接受崩溃重启）。
- **R-3 `exitProcess(0)` 仍是重启手段**（无进程自重启）；Yuihub 同款，保持。
- **R-4 safety_backup（A 档）保留**：它与 B 档回滚互补（A=恢复起点的快照，B=换库瞬间的现场）；恢复前仍先做 A。

## 9. 分阶段落地

1. **阶段 1**：`PendingRestoreStore`（含 rollback/apply/confirm/rollbackIfUnconfirmed 全套）+ `StagedDbValidator` 抽象 + 12 场景 JVM 测试。不接线。
2. **阶段 2**：启动接线——RikkaHubApp onCreate 改造 + StartupState + RouteActivity 全屏 gate + confirmRestoreAfterDatabaseOpen；恢复侧引擎切到 stage-only、删 in-flow 换库。字符串 3 语言。
3. **阶段 3**：真机验收——正常恢复重启后数据到位；恢复后立即 kill 进程再启动自动应用；模拟 Room 打不开（暂存一个 schema 超前的库）验证自动回滚；全新安装恢复 + 回滚。

## 10. 修订：恢复后强制重启（2026-10-27，审计 #16）

审计 #16 指出：staged 恢复完成后，旧进程仍在旧库上读写，这些写入在下次启动换库时被整体覆盖
（静默丢数据）；而原实现的「重启」弹窗依赖 `BackupPage` 的 `remember` 状态且只由备份页渲染，
Activity 重建/用户离开备份页后窗口重新打开。§8 的 R-1 是同一问题的已知权衡。

本轮改动（`app` 侧）：

1. `utils/AppRestart.kt`：launcher intent + `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`
   拉起新实例，随后 `exitProcess(0)`；拿不到 launcher 入口或 `startActivity` 抛异常时只记日志再退出
   ——**绝不留在旧进程里继续写**。
2. `ui/pages/backup/components/RestoreRestartGate.kt`：根 UI（`RouteActivity` 的 Ready 分支）外包一层。
   `LaunchedEffect(Unit)` + `LifecycleEventObserver(ON_RESUME)` 检查
   `PendingRestoreStore.hasPending(filesDir)`；另有一个进程级信号 `RestoreRestartSignal` 供恢复完成
   当场上报。命中即在内容页之上渲染**唯一**的不可 dismiss `BackupDialog`（`onDismissRequest={}`、
   `dismissOnBackPress=false`、`dismissOnClickOutside=false`，700ms 后 `AppRestart.restart`）。
3. `BackupPage.kt` 不再持有弹窗状态，三处恢复成功回调只调 `RestoreRestartSignal.requestRestart()`。
   旋转/配置变更/路由弹栈都不会丢「需重启」；结构上也不可能叠两个对话框。

**为什么不会重启环**：`PendingRestoreStore.applyIfPending` 的所有成功分支（正常换入、
applied 残留、interrupted-swap、recoverRollback）都在 `StartupState.Ready` 之前 `clearPending`，
所以 Ready 之后 `hasPending == false`，gate 不会在每次启动触发重启。

**语义变化**：`needsRestartDialog=true` 但不 stage DB 的导入（providers-only）也会走强制重启。
旧实现同样只有「重启」一个动作、且不可取消（`onDismissRequest={}`），差别只是现在不需要用户点
按钮——记录为有意统一：任何「需重启」导入后继续在旧进程写都会丢。
