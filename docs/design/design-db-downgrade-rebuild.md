# 设计：数据库降级重建 —— 启动期隔离旧库 + 全新起库 + 升级回归可恢复

- 日期：2026-10-31
- 状态：代码完成（DowngradeQuarantineStore + doBoot 第 0 步 + 双弹窗 + 备份页常驻入口；14 项 JVM 单测绿、全量 295 个测试类绿、装机冒烟通过）；待真机验收 §7 四场景（A 降级/B 回升恢复/C 中间版本/D 隔离中途强杀）
- 前置：B 档 staged 恢复已落地（`PendingRestoreStore`，设计稿 `design-backup-staged-restore.md`）；A 档恢复前安全备份已落地（`filesDir/safety_backup/` 滚动 3 份）

## 0. 随案必修：`SCHEMA_VERSION` 已经漂移（现存 bug）

`AppDatabase.kt:228` 的 `SCHEMA_VERSION = 54`，而 `@Database(version = 56)`（AppDatabase.kt:89）。注释写着「必须与上方 `@Database(version = …)` 保持一致（启动期 staged 库校验用）」，实际已落后两个版本。后果：

- `validateStagedDatabase` 的 `check(version in 1..SCHEMA_VERSION)` 会把 v55/56 的 staged 库判为「来自不兼容版本」，**从最新备份恢复会被直接拒绝**；
- 本设计的降级检测同样要以这个常量为基准，漂移一天方案就错一天。

修法三件套（**已落地 2026-10-31**，变异验证过：常量错 1 单测即红；全量 294 个测试类通过）：

1. 常量改 56，注释记录事故与保险机制；
2. **编译期闸门（主闸）** `SchemaVersionConsistencyTest`：拿 `app/schemas/<fqcn>/` 下最新 JSON 的版本号（KSP 每次构建从 `@Database` 注解自动生成，就是 Room 认定的真实版本）与常量对账。曾考虑运行时反射注解，实测不成立——Room 2.8.4 的 runtime 里没有任何 `getAnnotation`，版本号烧在生成的 `RoomOpenDelegate(56, hash, hash)` 构造参数里（AppDatabase_Impl.kt），拿它要么开 in-memory 库要么反射 protected 方法，都不可取；
3. **运行时哨兵（兜底，已落地）**：`postReadyInit` 裸协程比对 `openHelper.writableDatabase.version != SCHEMA_VERSION`，漂移即抛——**接 CrashHandler 上报（2026-10-31 拍板）**，crash 文件落 `logs/crash-*.txt`、下次启动进 SafeMode 展示（进入即清标记，不会循环崩）。**实现要点：不能用 AppScope**——它的 `CoroutineExceptionHandler` 会把异常吞成 `Log.e`，走不到 CrashHandler 挂的默认 uncaught handler；须用不带 handler 的裸 `CoroutineScope(Dispatchers.Default).launch`。

## 1. 动机：降级今天是死局

场景：用户回退到旧版（试 beta 后回 stable、发版回滚、跨分支真机验证装错包）。现行表现：

- `DataSourceModule.kt:98` 的 builder 没有任何 `fallbackToDestructive*`；
- 降级后首次打开 Room 抛 `IllegalStateException`（迁移链缺失）→ 位置随机：没被 `runCatching` 兜住就是 crash loop，被 `ensureKoinStarted` 兜住就是 `StartupState.Failed(canRetry = false)` 全屏死路；
- 用户唯一的出路是清数据或装回新版——**数据事实上全部丢弃，且没有备份**。

而数据全丢并非必须：降级库本身是完好、可隔离的。B 档已经把「启动早期、startKoin 之前」变成了一个天然封闭的操作窗口（无 Room、无后台写者、失败可无损回退），直接复用。

## 2. 现状核查（实现前已逐条确认）

| 事实 | 位置 |
| --- | --- |
| builder 无降级回退 | `di/DataSourceModule.kt:98` |
| `doBoot()` 顺序：applyIfPending → rollbackIfUnconfirmed → startKoin → 开库确认 → Ready | `RikkaHubApp.kt`（boot 编排段） |
| `PRAGMA user_version` 只读开库校验的现成写法 | `RikkaHubApp.validateStagedDatabase` |
| fail-closed 换库骨架（marker 最后写、rollback、确认） | `PendingRestoreStore.kt` |
| 三件套伴随文件（-wal/-shm）清理与归位工具 | `DatabaseRestoreFiles.kt` |
| WAL 模式运行（JournalMode.WRITE_AHEAD_LOGGING） | `DataSourceModule.kt:99` |

## 3. 方案总览

三段式，全部发生在 `doBoot()` 内、startKoin 之前：

```
检测（currentVersion > SCHEMA_VERSION？）
  → 隔离（三件套 copy 到 downgrade_pending/，目录改名提交，再删原件）
  → 重建（让正常启动继续：Room 发现无库，按当前版本全新建库）
```

**明确不采纳的捷径**：`fallbackToDestructiveMigrationOnDowngrade(true)` 一行就「能跑」，但等于静默清库，且没有留任何恢复入口——与本项目 A/B 档建立的 fail-closed 基调相反。本方案比它多的成本只是启动期一次库文件复制（118MB 量级、秒级，且发生在 Restoring 屏期间）。

## 4. 细节设计

### 4.1 新组件 `DowngradeQuarantineStore`（纯 File + kotlinx.serialization，JVM 可测）

仿 `PendingRestoreStore` 的约定，目录 `filesDir/downgrade_pending/<时间戳>/`：

```
downgrade_pending/
  20261031_153000/            ← 目录改名 = commit（原子可见的完整三件套）
    rikka_hub                 ← 主库（copy）
    rikka_hub-wal             ← 有则 copy
    rikka_hub-shm             ← 有则 copy
    quarantine.marker         ← 元数据，内容见下
  20261020_091200/            ← 滚动保留 3 份，超出删最旧
```

`quarantine.marker`（JSON）：

```kotlin
@Serializable
data class QuarantineMarker(
    val schemaVersion: Int,        // 隔离时 PRAGMA user_version 读到的值
    val quarantinedAt: Long,       // epochMillis
    val databaseSize: Long,        // 主库 size（payload 残缺判定）
    val walSize: Long? = null,
    val shmSize: Long? = null,
)
```

### 4.2 隔离步骤（关键顺序）

1. `PRAGMA user_version`（`SQLiteDatabase.openDatabase(READONLY)`，照抄 `validateStagedDatabase`）；`<= SCHEMA_VERSION` → 直接返回，本次零开销。
2. 建 `downgrade_pending/tmp-<ts>/` 临时目录，**copy** 主库 + 两个伴随文件进去，逐一比对 size；任一对不上 → 删 tmp、放弃隔离（fail-closed：原件未动，宁可下次再试）。
3. 写 `quarantine.marker` 进 tmp（sync）。
4. **tmp 改名为正式时间戳目录 = commit**。崩溃点覆盖：改名前死 → tmp 孤儿目录，下次启动清理（规则：`tmp-*` 一律删）；改名后死 → 完整隔离件已在，走 5。
5. **提交后才删原件**（主库 + 伴随文件）。崩溃在 4 与 5 之间 → 原件还在、降级还在 → 下次启动重新隔离（committed 目录已存在，滚动去重）。

选 copy 而非 move 的理由：move 到一半崩溃会留下「主库已走、wal 残留原地」的窗口，陈旧 WAL 对着新建空库回放会造出混合数据（`PendingRestoreStore.kt` 对同款问题的注释）；copy 期间原件始终完整，最坏情况是多占一份空间。

**伴随文件语义**：三件套一起 copy，WAL 里未 checkpoint 的已提交事务随隔离件完整保留，无需事先 `wal_checkpoint`（不碰运行中的库是这个设计的前提——虽然此刻 Room 还没打开，但保持「只读不写」的纪律能少一类竞态）。

### 4.3 `doBoot()` 编排插入点

```
doBoot():
  ├─ DowngradeQuarantineStore.quarantineIfDowngraded(filesDir, dbFile, SCHEMA_VERSION)   ← 新增，第 0 步
  ├─ PendingRestoreStore.applyIfPending(...)        ← 原有
  ├─ PendingRestoreStore.rollbackIfUnconfirmed(...) ← 原有
  ├─ startKoin → 开库确认 → Ready
```

放在 `applyIfPending` **之前**的理由：隔离只动降级库、不碰 `restore_pending/`；若同时存在 staged 恢复（极罕见：新版里 stage 了旧版本备份后立刻降级），隔离后让位出的现库正好让 staged 管线照常工作——staged 库的版本校验自会裁决。隔离与恢复两条管线不互相感知、不互相清场。

### 4.4 重建侧

什么都不用做：隔离提交并删掉原件后，Room 发现无库，按当前 schema 全新建库（现有 `onOpen` 回调会重建 FTS 索引表，无需额外处理）。这也是不在 builder 上加 `fallbackToDestructive*` 的原因——降级路径已经被前置隔离接管，builder 保持「不带破坏性回退」的保守姿态不变。

### 4.5 降级发生的用户告知

隔离 + 重建完成后，首次 Ready 时弹一次性对话框（DataStore 记「已告知」标记，或用 marker 目录的存在性驱动）：

> 检测到数据库来自更新版本的应用，已重建空白数据库；原数据已安全备份，升级回对应版本后可恢复。

不做全屏阻塞、不给「放弃备份」选项（隔离件滚动 3 份自动淘汰，无用户管理成本）。

## 5. 升级回归后的恢复路径（复用 staged 管线，不造第二条换库路）

用户升级回 ≥ 隔离件 `schemaVersion` 的版本后，`doBoot()` 第 0 步之后新增扫描：

1. `downgrade_pending/` 存在，且某份 `quarantine.marker.schemaVersion <= SCHEMA_VERSION`（新应用能开它）→ Ready 后弹窗：「发现降级前的数据备份（日期 xxx），是否恢复？恢复将覆盖当前数据」；
2. 用户确认 → **当下 stage 进现有管线**：把三件套 move 进 `restore_pending/`（复用 `PendingRestoreStore.stage`）+ 重启 → 下次启动走 `applyIfPending` 的完整校验（`PRAGMA user_version` + `integrity_check`）+ rollback + 开库确认——**验证、回滚、崩溃恢复全部白拿，一行不新写**；
3. 用户拒绝 → 记「不再提示该份」，隔离件继续滚动保留，设置里留手动入口（挂在备份设置页，列出可用隔离件）。
4. `schemaVersion` 仍 > `SCHEMA_VERSION`（升得还不够高）→ 不提示，隔离件静默保留。

一个刻意的偏差：恢复走「stage + 重启」而不是当场换库，多花一次重启。理由与 B 档设计稿 §1 相同——运行中进程换库撞后台写者的窗口不值得为省一次重启冒。

## 6. 边界与不做的事

- **损坏库 / 迁移失败重建不在本期**：那是「打开失败后的自救」，触发点在开库确认而非版本比对，与 SafetyMode/CrashHandler 的关系需要单独梳理。本方案的隔离件机制未来可被它复用（隔离后重建 + 从 safety_backup 提示恢复），但先不扩。
- **不改 builder**：不加任何 `fallbackToDestructive*`。
- **不做降级内联迁移**：为降级写反向迁移链成本高且场景少，隔离换保真是更优解。
- **`"rikka_hub"` 字符串散置各处**（RikkaHubApp / BackupArchiveEngine / DataSourceModule / LegacyBackupConverter 四处硬编码）：本期顺手提一个常量（如 `AppDatabase.NAME`），减少第 0 步新代码再次硬编码。

## 7. 测试计划

JVM 单测（仿 `PendingRestoreStoreTest`，JUnit4 + TemporaryFolder，无 Robolectric）：

1. 隔离：版本低不动作；三件套齐全 copy；wal/shm 缺失时 size 记 null；
2. commit 语义：marker 最后写；tmp 残留清理；提交后删原件；
3. size 不符 fail-closed：原件未动、tmp 清空；
4. 滚动 3 份：最旧被删、committed 目录去重；
5. 恢复扫描：版本闸（`<= SCHEMA_VERSION` 才提示）、stage 转接、拒绝后不再提示；
6. 与 pending restore 并存：隔离不动 `restore_pending/`。

真机验收场景（PJX110 工作流）：

- A. 新版（v56）正常使用 → 降级装旧版 → 启动不 crash、全屏告知弹窗、空库可用、`downgrade_pending/` 有完整三件套；
- B. A 之后升级回新版 → 弹恢复提示 → 恢复 → 数据回来了（含降级前最后一次未 checkpoint 的消息）；
- C. A 之后装中间版本（仍 < v56）→ 不提示恢复、正常使用；
- D. 隔离过程中强杀（提交前/提交后删原件前各来一次）→ 下次启动状态收敛、无陈旧 WAL 回放。

## 8. 开放问题 —— 已全部拍板（2026-10-31 用户确认）

1. **告知弹窗：需要**（维持 §4.5 的全屏一次性对话框，不做 toast 降级）；
2. **滚动份数：3 份**，与 A 档 safety_backup 对齐（维持 §4.1）；
3. **恢复提示触发面：启动时提示 + 备份页常驻入口**（维持 §5.3，两者都要）；
4. **`SCHEMA_VERSION` 哨兵：接 CrashHandler 上报**（§0 已按此落地：漂移即抛、crash 落盘、SafeMode 兜住，无循环崩）。
