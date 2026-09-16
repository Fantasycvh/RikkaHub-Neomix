# 导出文件留存与「真落地」设计稿

> 状态：**已实现**（2026-09-16，`534a5eef`）。真机验证：导出的 JSON 落在 `files/exports` 且已登记
> `managed_files`、`cache/temp` 不再产生导出残留；TTL 清理实测（植入 30 天前文件 → 冷启动 →
> 被清、当日文件保留、`.tmp-` 残留被清，日志 `removed 1 expired export(s) (retention=7d)`）。
> 单测 `ExportRetentionTest` 10 项；全量 536 项仅基线失败。
>
> **实现中又发现并修掉一个破坏性 bug（原设计稿未列）**：`onDismissRequest()` 会清掉 ChatPage 的
> `exportThenRegenerateFor`，而 `onExported` 正是读它来续作 scope B——原实现先 dismiss 后 exported
> ⇒ **「先导出再继续」的移除从未执行过**（方向安全但功能是死的）。见 §六.8。
> 用户决策：**①「先导出再继续」走 SAF `CreateDocument` 真落地（取消 = 中止移除）；
> ② 引入 `files/exports` 留存区 + 启动清理 + 可选周期任务 + 「导出管理」入口（可再分享/删除）**。
> 关联：`design-regenerate-scope.md` §六（导出与回退的辨析）、`design-backup-workspace-files.md`（备份项）。

## 一、现状核实（实测）

### 1.1 导出落点全是 `cache/temp`，且**冷启动整目录删除**

四处导出都写 `context.appTempFolder`（= `cacheDir/temp`）：

| 格式 | 落点 | 文件名 |
|---|---|---|
| JSON | `Export.kt:362` | `"$safeTitle.json"`（**按标题，无时间戳**） |
| Markdown | `Export.kt:488` | `chat-export-<时间戳>.md` |
| HTML | `Export.kt:747` | `chat-export-<时间戳>.html` |
| PNG | `Export.kt:822` | `<时间戳>-pN.png`（多页，上限 `MAX_EXPORT_PAGES = 200`） |

全仓**唯一的清理**是 `RikkaHubApp.deleteTempFiles()` → `cache/temp.deleteRecursively()`，
在 `Application.onCreate` 执行（`RikkaHubApp.kt:145`）。

**实测确认**（设备 `<device>`，2026-09-16）：放入标记文件 → `force-stop` + 冷启动 → 目录 `GONE`、
文件 `GONE`。**留存物活不过一次冷启动**。且 `cacheDir` 本身可被系统（低存储时）回收。

### 1.2 三个成因（性质不同，须分开治）

#### ① 真 bug：「先导出再继续」不检验是否真的落地（最严重）

`shareFile()` 是 `startActivity(createChooser(...))`，**无结果回调**。所以"导出成功"的实际含义
只是「临时文件写完 + 面板弹出」，**不是**「用户保存了」。而 JSON 卡片的续作是**无条件**的：

```kotlin
runCatching { exportToConversationJson(...) }.onSuccess {
    toaster.show(jsonSuccessMessage, ...)   // 报「已导出」
    onDismissRequest()
    onExported?.invoke()                    // → 执行 scope B：移除下游
}
```

用户点「先导出再继续」→ 分享面板弹出 → **随手取消** → 下游**照样被移除**。
保障程序在"没保存任何东西"的情况下放行了破坏性操作。

**加重项**：Markdown / HTML 的卡片连 `runCatching` 都没有——`exportToMarkdown` /
`exportToHtml` 内部 `catch (e: Exception) { printStackTrace() }` **吞掉异常**，卡片的成功 toast
与 `onExported` 是无条件执行的；PNG 虽套了 `runCatching`，但 `exportToImage` 自己也吞异常
（`Export.kt:889`）→ 写盘失败同样报"已导出"。

#### ② 删除后写入（delete-then-write）→ 失败会毁掉前一份

四处都是这个形状：

```kotlin
if (file.exists()) file.delete()
file.createNewFile()
FileOutputStream(file).use { it.write(...) }
```

JSON 的文件名**按标题**（`"$safeTitle.json"`），「同一会话导出两次」必然命中：
**先删旧、再写新**——写入中途失败（磁盘满等）则旧的那份已没了，只剩残缺文件。
这就是字面意义的「一次导出失败后导出文件即消失」。Markdown/HTML/PNG 用时间戳命名，
仅在同秒重导时撞上。

#### ③ 过期后不可寻回

- 撤销 toast 仅 **6 秒**（`ChatPage.doRegenerate`：`duration = 6.seconds`）。
- **全仓无「导出记录 / 再次分享」入口**（grep 无此概念）。

文件一旦过期或被冷启动清掉，用户既无提醒、也无法找回或重发——只能重导，而下游已删。

## 二、已定决策（2026-09-16）

| # | 决策 | 后果 |
|---|---|---|
| 1 | **「先导出再继续」走 SAF `CreateDocument` 真落地**；取消 = **中止移除** | ① 消掉：有真实结果回调，"已保存"可验证 |
| 2 | **引入 `files/exports` 留存区**（非 `cacheDir`） | 不受系统缓存回收影响；可被文件管理页发现 |
| 3 | **启动清理 + 可选周期任务**（TTL） | 有界，不无限增长 |
| 4 | **给「导出管理」入口**（可**再分享 / 删除**） | "留存"名副其实——找得到、发得出 |

## 三、两种需求的分离（本设计的核心）

问题的根因是**两种需求被混在一条路径里**：

| | (a) 分享中转 | (b) 破坏操作前的留存 |
|---|---|---|
| 需要活多久 | 秒到分钟（目标 app 读完即可） | **跨重启** |
| 位置 | `cacheDir` 正确 | `cacheDir` **错误**（系统可回收） |
| 交互 | `ACTION_SEND` 分享面板 | **`CreateDocument` 让用户明确落盘** |
| 失败语义 | 无所谓 | **必须可验证**（否则不该放行破坏操作） |

**结论：两条路分开做，不共用实现。** (a) 保持分享面板语义（`cacheDir` 是对的），
(b) 走 SAF。

> **为什么 (b) 用 SAF 而不是"写进 `files/exports` 再提示"**：SAF 是**用户可见的落盘位置**
> （下载目录/网盘），且**有结果回调**——这正是 ① 的解。而 `files/exports` 是**App 私有**的，
> 单靠它仍无法回答"用户是否留存了"，也无法把文件交给外部 app。

### 3.1 (b) 与留存区的关系（**需要用户确认的衔接点**）

`CreateDocument` 已经让用户拿到外部副本，那**留存区还要不要也存一份**？两个选项：

- **选项 A（推荐）**：**不额外存**。`CreateDocument` 成功后直接执行移除，留存区只服务 (a) 侧
  的"最近导出"浏览。语义最干净：用户选的位置就是留存位置，不产生重复副本。
- **选项 B**：**两边都存**——先写 `files/exports`（保证 App 内可寻回），再走 `CreateDocument`
  让用户另存。好处是"即使外部没保存成功，App 内还有一份"；代价是同一份内容存两份，
  且用户会看到两个来源不同的副本。

> 我倾向 **A**：`CreateDocument` 的返回值已经能判定成功，再存一份是冗余；
> 且"两份副本"容易被误解为版本不同。**但若你认为 App 内可寻回比省空间重要，选 B。**

## 四、(a) 侧：`files/exports` 留存区

### 4.1 目录与登记

- 新目录 `files/exports`（`FileFolders.EXPORTS = "exports"`）。
- **必须走 `FilesManager.saveManagedFromBytes(folder, bytes, displayName, mimeType)`** ——
  它同时登记 `managed_files`；否则文件管理页会把 UUID 当显示名（与二阶段 zip 同一约束）。
- **命名**：`managed_files.relative_path` 有 **UNIQUE 索引**，故不能靠"同名覆盖"去重。
  用 `buildUuidFileName(displayName, mimeType)`（现成，与 `upload` 同款，保扩展名），
  **displayName 保留人类可读名**（如 `逆向工作台.json`），二者分离。

### 4.2 原子写入（修 ②）

不要再 `delete + createNewFile + write`。改为 **写临时文件 + `renameTo`**：

```
写 files/exports/.tmp-<uuid> → fsync → renameTo(最终名)
```

`renameTo` 在同一文件系统上是原子的，故**不存在"删了旧的、新的没写成"的中间态**。
（`cacheDir` 与 `filesDir` 同属 app 私有存储，同一分区。）

JSON 侧的文件名含标题但**不含时间戳**，覆盖语义由此变为"安全覆盖"（旧文件在新文件 rename
落定前始终完好）。

### 4.3 清理策略（决策 3）

| 时机 | 动作 | 成本 |
|---|---|---|
| **启动**（`RikkaHubApp`，与 `deleteTempFiles` 并列） | 删除 `files/exports` 中**过期**的（按 `managed_files.createdAt` 或文件 mtime） | **零新增调度**——只是把"全删"改成"删过期的" |
| **可选周期任务** | `WorkManager` 周期任务（`PeriodicWorkRequest`）做同样的清理 | 中：**目前全仓无任何 Worker 实现**，Koin workmanager 集成已有（`workmanagerFactory()`），故只是新增首个 Worker |

**TTL 设置**（三件套，`Settings` 顶层平铺项）：`exportRetentionDays: Int`（默认建议 7 天，
`0` = 不自动清理）。**可选间隔**即由它承载。

> **为什么启动清理足够、周期任务只是"可选"**：清理只在"文件过期"时才需要发生，
> 而"过期"判定的粒度是天。用户下次打开 App 时清一次，效果与周期任务等价——
> 周期任务的价值仅在"用户长期不打开 App"时避免占用空间，属锦上添花。

### 4.4 「导出管理」入口（决策 4）

**复用 `SettingFilesPage`（现成，318 行）**，给它加第二个文件夹 chip：

- 该页已有 `FolderRow(folders, selectedFolder, ...)`，当前硬编码 `listOf(FileFolders.UPLOAD)`
  （`SettingFilesPage.kt:72`）→ 加 `FileFolders.EXPORTS` 即可，**UI 结构零改动**。
- `folderDisplayName()` 加一个分支 + 一个字符串（现为 `when (folder) { UPLOAD -> ...; else -> folder }`）。
- **缺的能力**：该页现有动作只有**删除**（单条）与**清空**（整夹），**没有分享**。
  需给 `FileItem` 加"分享"动作（复用 `Export.kt` 的 `shareFile(context, uri, mimeType)`，
  需将其从 `private` 提为 `internal` 或抽到公共工具）。

## 五、改动清单

| # | 改动 | 文件 |
|---|---|---|
| 1 | `FileFolders.EXPORTS = "exports"` | `FilesManager.kt` |
| 2 | `saveManagedFromBytes` 复用（已有）+ 原子写入helper | `FilesManager.kt` |
| 3 | `Settings.exportRetentionDays` 三件套 | `PreferencesStore.kt` |
| 4 | 启动清理改为"删过期"（`files/exports`） | `RikkaHubApp.kt` |
| 5 | 可选：首个 `CoroutineWorker` + `PeriodicWorkRequest` | 新增 |
| 6 | 「导出管理」：`FolderRow` 加 `EXPORTS` chip + `FileItem` 加分享 | `SettingFilesPage.kt` |
| 7 | `shareFile` 提为 `internal`（或将分享逻辑抽公共工具） | `Export.kt` |
| 8 | JSON/Markdown/HTML/PNG 四处导出改写 `files/exports` | `Export.kt` |
| 9 | **卡片的成功 toast 与 `onExported` 改为条件化**（修 ① 假成功） | `Export.kt` |
| 10 | (b)：`CreateDocument` 取代分享面板 + 取消 = 中止移除 | `Export.kt` / `ChatPage.kt` |
| 11 | 6 语言字符串（导出管理/分享/留存天数等） | `strings.xml` |

## 六、风险与注意点

1. **① 的修法有两条，别只做一条**：既要 (b) 换成有结果回调的 `CreateDocument`，
   也要 (a) 侧把 `catch + printStackTrace` 改成能上报（否则写盘失败仍报"已导出"）。
   后者是**独立缺陷**，与 SAF 无关。
2. **`file_paths.xml` 要加 `exports`**：`FileProvider` 的 `<files-path>` 白名单现为
   `upload/tool_outputs/logs` + `cache-path path="."`。留存区在 `files/` 下，
   **不加则分享会抛 `IllegalArgumentException`**。
   （注意该文件注释明说要"收窄 filesDir 暴露面"，故只加 `exports/` 这一条，不用 `path="."`。）
3. **备份是否包含 `exports`**：`addAppFilesToZip` 现打包 `upload/fonts/skills`（不含 `tool_outputs`）。
   `exports` 是**用户留存的产物副本**，性质介于两者之间——**建议不打包**（会话本体已在
   `rikka_hub.db` 里，导出文件是冗余副本；打包会让备份体积随导出历史膨胀）。
   若用户希望"备份即带上留存"，再单加一个 `BackupItem`。**建议默认不含。**
4. **`managed_files` UNIQUE 约束**：`relative_path` 唯一 → 不能"同名覆盖"。原子写入的目标名
   必须唯一（用 `buildUuidFileName`），显示名另存 `display_name`。
5. **PNG 已有持久副本**：PNG 每页都 `exportImage(activity, ...)` → **相册（MediaStore）**，
   那才是 PNG 的主留存路径。留存区对 PNG 属**冗余**，可考虑 PNG 不进留存区（待定，见 §七）。
6. **体积**：PNG 上限 200 页、单页 15000px 高，单次导出可很大。TTL 必须有默认值，
   不能默认"永不清理"。
7. **`SettingFilesPage` 的删除语义**：该页删除走 `filesManager.delete(id, deleteFromDisk = true)`，
   对留存区同样适用；但**清空**按钮作用于 `selectedFolder`，加 chip 后语义自动正确
   （因为它读的是 `selectedFolder`）。
8. **⚠️ 回调顺序（实现中发现的破坏性 bug）**：`onDismissRequest()` 会清掉 ChatPage 的
   `exportThenRegenerateFor`，而 `onExported` 正是读它来续作 scope B。原实现是
   `onDismissRequest(); onExported?.invoke()` ⇒ **续作读到的必为 null，「先导出再继续」的移除
   从未执行过**（方向上是安全的——没有误删——但功能是死的）。
   修正为**先 `onExported` 后 `onDismissRequest`**。教训：**"清场"与"续作"共用同一状态时，
   顺序即语义**；两个 lambda 在同一同步栈内顺序执行，MutableState 写入立即生效。
9. **`PNG` 的 `activity == null` 分支原先静默 `return`**（只弹 Toast）→ 调用方随即报「已导出」，
   又一处假成功。已改为抛错走失败分支。
10. **分享面板弹 ≠ 成功**：`shareFile` = `startActivity(createChooser)`，无结果回调。
    故"交给别的 app"语义下**不能**把"面板弹出"当作"已留存"——只有 SAF `CreateDocument`
    的返回值可判定。这正是 (b) 侧必须换 SAF 的根本原因。

## 七、待确认

1. **§3.1 的选项 A / B** → **用户选 B**：两边都存（留存区始终写一份 + `CreateDocument` 让用户另存）。
   实现即如此：`deliver()` 总是先 `writeExportBytes`（必然落留存区），再按 `saveToDocument`
   分流到「分享面板」或「另存对话框」。故**取消另存也不丢内容**，与「取消 = 中止移除」并存不冲突
   （留存区那份是"真落地"的保证，我们自己写、写没写成可验证）。
2. **TTL 默认值**：建议 7 天；`0` = 不清理。是否合适？
3. **周期任务是否要（现在）做** → **本次未做**（按建议：启动清理已能兜住——"过期"粒度是天，
   启动时清一次与周期任务等价）；周期任务仍是**首个 Worker**，按需再立项。
4. **PNG 是否进留存区** → **本次仍进**（与其他格式统一走 `writeExportBytes`，避免分支），
   但它同时进相册（MediaStore）——**主留存路径是相册**。另存对话框对 PNG 只存第一页
   （多页由相册承载）。若将来要省空间，可单独把 PNG 排除出留存区。
5. **b 取消移除的文案** → **什么都不做**（实现即此：取消 = 弹一条 Info「已取消保存。未执行移除——
   文件已在『文件管理 → 导出』留存区」并中止续作）。理由：取消通常就是反悔，且内容已在留存区。

## 八、明确不做

- **不做"导出历史数据库表"**：`managed_files` 已够（含 folder/时间/大小），无需新表。
- **不做跨设备/云端的导出同步**（S3/WebDAV 有自己的整库备份路径）。
- **不给 `cacheDir` 加 TTL**：`cacheDir` 的正确策略就是"启动清掉"——那是分享中转的正确语义。
  给它做留存期反而是错位的（见 §三）。
