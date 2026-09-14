# 工作区文件备份（B 方案）设计稿

> 状态：**已实现并真机验证**（2026-09-13）。
> 实现提交：`e077d8bb`（统一 BackupItem 枚举 + 辅助）、`b9ddd5a7`（抽 BackupArchiveEngine）、`0d391d3a`（UI selector 抽取 + 修恢复路径 bug + 安全加固）。
> **真机验证**：本地导出含 `workspace_files/<root>/...` 5 条目（与设备工作区 5 文件一致），rootfs 未包含（体积 71MB 未见 30MB rootfs）；settings/database/upload/skills 各项未受影响。
> 需求来源：用户指出备份**不含工作区**，导致跨设备迁移时工作区里的数据会丢失——「其他设备上会有一些难以再次找到的数据（被下架仓库的克隆等），一旦忘记导出就会丢失内容」。

## 一、现状核实

### 1.1 备份包含什么

`S3Config.BackupItem` 只有两项（`S3Config.kt:37`）：

| 项 | 打包内容 | 恢复 |
|---|---|---|
| （恒含）`settings.json` | Settings | ✅ |
| `DATABASE` | `rikka_hub.db` + `-wal` + `-shm` | ✅ |
| `FILES` | `files/upload`、`files/skills`、`files/fonts` | ✅ |

### 1.2 缺口

工作区数据位于 `<filesDir>/workspaces/<root>/`，结构（`WorkspaceManager`）：

```
workspaces/<root>/
├── files/    ← 用户文件区（FILES 区；UI「文件」页与 AI workspace_* 工具的默认区域）
├── linux/    ← Ubuntu rootfs（含 bin/sh 判定「已安装」），30MB+，可重装
└── tmp/      ← PRoot 临时文件，可弃
```

**备份完全未覆盖该目录** ⇒ 恢复后工作区（含 rootfs 与工作区内文件）整体缺失，DB 里的 `WorkspaceEntity` 记录虽被恢复（在 DATABASE 内）却指向不存在的目录。

### 1.3 一个容易误解的相关事实

「工作区文件仍在」的错觉来源：工作区文件**发送到对话**时会被复制进 `files/upload`（在备份范围内），所以那份副本还在。但**工作区本体**（以及从未发送过的工作区文件）会丢。

另注：`files/tool_outputs`（工具输出外置目录，`FileFolders` 有定义）**已于 2026-09-13 纳入备份**（`b9ddd5a7` 顺带补齐，打包与恢复两侧都有）——此前的「未纳入」表述已过时，见 §6 第 2 条。

## 二、目标与范围

**目标**：备份包含**工作区文件区**（`workspaces/*/files/**`），**排除 rootfs**（`linux/`）与 `tmp/`。

**理由**：
- 用户真正不可再生的数据在 `files/`（下架仓库克隆、生成物等）
- `linux/` 是标准 Ubuntu rootfs，可用现有「安装 rootfs」重装 ⇒ 排除可让备份体积可控（否则至少 +30MB/工作区，压缩后仍大）
- `tmp/` 是 PRoot 运行期临时目录，无备份价值

## 三、格式设计

### 3.1 新增备份项

```kotlin
enum class BackupItem {
    DATABASE,
    FILES,
    WORKSPACE_FILES,   // 新增：工作区文件区（不含 rootfs）
}
```

**两处枚举需同步**：`PreferencesStore.kt:715`（设置持久化用）与 `S3Config.kt:37`（S3 配置用）。二者目前是重复定义，本方案**保持各改一处**（不顺手合并，避免扩大改动面；合并列为 §6 待决）。

**向后兼容**：`@Serializable` enum 新增成员后，旧备份/旧配置反序列化不受影响（未知成员才需 `ignoreUnknownKeys`；这里是新增可选项，旧数据无该值 → 视为未勾选）。默认值建议**不勾选**（体积考虑），由用户在备份设置中主动开启。

### 3.2 zip 内路径

沿用现有「以 `FileFolders` 名为前缀」的约定，工作区条目加独立前缀：

```
workspace_files/<root>/<相对路径>
```

例：`workspace_files/01w5enkg/myrepo/src/main.rs`

- `<root>` = `WorkspaceEntity.root`（目录名，非 UUID）——与磁盘结构一一对应
- 只遍历 `workspaces/<root>/files/`，**不遍历 `linux/`、`tmp/`**

### 3.3 恢复路径与安全

- 目标：`<filesDir>/workspaces/<root>/files/<相对路径>`（父目录按需 `mkdirs`）
- **必须做 zip-slip 防护**（与现有 `upload/` 分支同款，`S3Sync.kt:268` 已有先例）：
  - `resolve(...).canonicalFile` 后校验仍位于 `workspaces/<root>/files/` 内
  - 同时**校验 `<root>` 段合法**（复用 `WorkspaceManager.requireValidRoot` 的规则：拒绝 `/`、`..`、空等），防止用备份里的 `root` 逃逸到其他目录
- 覆盖语义：**合并写入**（已存在同名文件则覆盖，不删除未出现在备份中的文件）——避免「恢复旧备份」意外清空现有工作区内容

## 四、与 DB 中工作区记录的一致性

`WorkspaceEntity`（含 `id`、`root`、`name`）在 `DATABASE` 内，恢复后已存在；工作区文件的恢复按 `root` 对齐即可，**不需要新建实体**。

**三种不一致情形与处理**：

| 情形 | 处理 |
|---|---|
| DB 有该 `root`，备份也有 → 正常恢复文件 | 覆盖/合并写入 |
| 备份有文件但 DB 无对应 `root`（用户只勾了 `WORKSPACE_FILES` 未勾 `DATABASE`，或 DB 是另一份） | **仅落盘文件**，不创建 `WorkspaceEntity`；在恢复结果里提示「有 N 个工作区文件已落盘但无对应工作区记录」 |
| DB 有 `root` 但备份无文件 | 不动（工作区为空文件区） |

**rootfs 缺失的提示（重要）**：恢复后工作区**没有 rootfs**，AI 的 shell 工具与终端会用不了。需在恢复完成后提示：「工作区文件已恢复，rootfs 需重新安装」。可选增强：恢复后自动检测 `!hasRootfs(root)` 并在工作区详情页显示「安装 rootfs」引导（现有 UI 已有该入口）。

## 五、实施步骤（建议拆分）

| 步骤 | 内容 | 规模 |
|---|---|---|
| 1 | 两个 `BackupItem` 加 `WORKSPACE_FILES`；备份设置页加勾选项（6 语言） | 小 |
| 2 | 打包：遍历 `workspaces/*/files/**` → `workspace_files/<root>/...`（S3Sync + WebDavSync **两处都要改**） | 中 |
| 3 | 恢复：`workspace_files/` 分支 + root 校验 + zip-slip 防护（两处 Sync 都要改） | 中 |
| 4 | 恢复后提示「rootfs 需重装」；无对应实体时的提示 | 小 |
| 5 | 单测：路径映射、root 校验拒绝 `..`/绝对路径、zip-slip 拒绝、合并语义（不删现有文件） | 中 |
| 6 | 真机验证：备份→删工作区→恢复→文件齐全且 rootfs 提示正确 | 中 |

**注意**：备份逻辑目前**在 `S3Sync` 与 `WebDavSync` 各有一份独立实现**（几乎是复制粘贴），两处必须同步改，否则 WebDAV 用户得不到该功能。这本身是 §6 的重构隐患。

## 六、待决 / 不在本方案范围

1. ~~本地备份/导出是否是第三份实现~~ **已核实：不是**。`BackupVM` 的本地导出直接复用
   `webDavSync.prepareBackupFile`（`BackupVM.kt:86`）——因此**只需改 S3Sync + WebDavSync 两处**，本地导出自动获益。
2. ~~**`files/tool_outputs` 未纳入备份**~~ **已解决（2026-09-13 `b9ddd5a7`）**：打包（`addAppFilesToZip` 的 `UPLOAD/FONTS/TOOL_OUTPUTS` 平铺遍历）与恢复（`restoreFlatEntry` 分支）都已覆盖，本地/WebDAV/S3 三入口共用同一 engine。原始判断里唯一的行动项（「FILES 分支加一个目录遍历」）已落地，**无需再改**。
   **但核查时发现一个更实质的问题（未处理，待决策）**：`RikkaHubApp.cleanupToolOutputs()` 在**每次 App 启动**时无条件 `deleteRecursively()` 整个 `files/tool_outputs`（承自 `5c93723c` 包名迁移，非新引入）。由此：
   - **默认备份流程下，备份 tool_outputs 实际无效**——恢复会弹「重启应用」（`exitProcess(0)`，`BackupDialog`），而重启的 `onCreate` 立刻把刚恢复的文件删掉；默认备份含 DATABASE，故必然走到这一步。
   - **「附件无法打开」的根因是 cleanup，不是备份遗漏**——任何一次重启后，历史消息里的 `/tool_outputs/<toolCallId>.txt` 引用都会悬空。
   - **影响有界**：截断消息把输出**前 4KB 内联**在消息正文里（`TOOL_OUTPUT_PREVIEW_CHARS`，随消息落库、在备份范围内），丢的是「重新读取完整输出（>32KB 部分）」，不是内容本身。
   - **决策二选一**：①承认 tool_outputs 是**会话级临时产物** → 从备份移除（现在只是徒增 zip 体积，恢复后必被删）+ 接受悬空引用（已有 4KB 预览兜底）；②希望它**跨重启留存** → 把无条件清理改成**保留策略**（按时间/总量上限，或只清不被引用的），此时备份才有意义。**②才需要写代码，成本中（策略 + 测试）**。
3. **S3Sync / WebDavSync 重复实现** —— 两套备份/恢复逻辑高度重复，理想是抽公共层；本方案不顺手做（避免与功能改动纠缠），列为独立重构项。
4. **备份体积与进度反馈** —— 工作区文件可能很大；是否需要在打包时显示进度/体积预估？建议首版先在 UI 提示「工作区文件可能较大」，不做进度条。
5. **是否可选「包含 rootfs」** —— 本方案排除 rootfs。若将来有用户希望连 rootfs 一起备份（离线迁移且无法联网重装），可加第三档选项；当前不做。

## 七、后续增强（2026-09-13 已落地，`04173c0f`）

**① 备份内容全局化**：新增 `Settings.backupItems`（三件套），删除 `S3Config.items`/`WebDavConfig.items` ——
三处（WebDAV/S3/本地）读写同一来源，本地 tab 也获得选择器。**默认全部三项**（旧默认 DATABASE+FILES 是
「当时尚无工作区项」的局限，非用户偏好）。**per-destination 差异未失去**：备份为纯手动（提醒仅卡片、无自动调度），
需要「云端精简、本地完整」时备份前临时切换勾选即可。

**② 恢复前确认弹窗**（`RestoreConfirmDialog`，三入口共用）：恢复是**覆盖**且不可逆（对话记录为整库替换），
弹窗列出将覆盖的内容项 + 红字警示「不确定时先导出当前数据」；本地入口在选文件**前**弹。

## 八、数据库恢复的 WAL 修复（2026-09-13）

**问题（上游包恢复静默失效）**：上游导出包只含 `rikka_hub.db`，**不含 `-wal`/`-shm`**。
原实现把备份条目**原地覆盖**到现库路径，而恢复全程 Room 连接一直开着（Koin `single`，全仓无 `close()`）：

1. SQLite WAL 模式下，同名 `rikka_hub-wal` 里通常有未 checkpoint 的旧帧；
2. 覆盖主库后，这条**陈旧 WAL 仍被下次打开识别为属于该库并回放**，把刚恢复的数据整体盖掉；
3. 表现为「提示恢复成功、聊天记录没变」，且可能是「新库结构 + 旧库部分页」的**混杂损坏**；
4. `exitProcess(0)` 只是杀进程，**不触发 checkpoint**，无法兜底。

离线复现（备份 `[999]`、现库 `[111,222]`）：保留陈旧 wal 读出 `[111,222]`（旧数据胜出）；删 wal 后读出 `[999]`。

**修复**（`BackupArchiveEngine`）：
- 恢复时数据库条目先**暂存到 cacheDir**，zip 全部读完后才处理（读到一半失败不影响现库）；
- `applyStagedDatabaseFiles`：①先把暂存文件复制到目标目录（失败发生在关库前）→ ②`appDatabase.close()`
  （WAL 下会 checkpoint 并释放文件）→ ③删陈旧 `-wal`/`-shm` → ④同目录 rename 换到位；
- 纯文件操作抽到 `DatabaseRestoreFiles`（可 JVM 单测：`DatabaseRestoreFilesTest`）。

**代价与约束**：恢复后数据库连接已关闭，**必须重启应用**（现流程已有「重启」弹窗）——这是正确行为，
而非新增限制。真机验证：上游包（165 会话/3916 节点）恢复后未重启即读到该数据、目录里只剩主库一个文件，
重启后仍为该数据（修复前会被旧 WAL 盖回 215/5112）。

**顺带清理**：移除死开关 `DisplaySetting.showUpdates`（fork 无更新检查实现，该开关无消费方；上游对应字段
`updateCheckDisabledUntilEpochMillis` 语义相反，导入时本就丢弃）——UI + 字段 + 6 语言字符串一并删除。

## 九、大文件备份加固（2026-09-13 评估定稿，**暂不实现**）

**触发**：用户指出「其他设备上导出时可能有 **2.8GB** 量级的数据」（工作区里难以再次获取的仓库克隆等）。
当前实现按中小体积设计，2.8GB 下会暴露若干问题。以下按收益排序，**均与是否引入 Rust 无关**
（评估见 `docs/design-auto-compaction.md` §6.5）。

**实测依据**（PC `zlib`，与 Java `Deflater` 同库）：真实 81.4MB DB 级别 6 压缩 3.3s（2.27x）；
**不可压缩 16MB（git pack 近似）级别 6 压缩比 1.000x、仍以 24 MB/s 烧单核**——即对已压缩数据 DEFLATE 纯属浪费。
2.8GB 下 DEFLATE ≈ 2 分钟（PC）/ 3–5 分钟（手机），上传前的全量 SHA-256 再读一遍 → **两次读盘**，
而网络上传（20 Mbps ≈ 19 分钟）才是支配项。

| 优先级 | 项 | 现状 | 建议 |
|---|---|---|---|
| **P0** | 不可压缩数据用 STORED | 一律 DEFLATE（级别 6） | 按扩展名/可压缩性判定，跳过压缩（`ZipEntry.setMethod(STORED)`）——**归零数分钟 CPU，成本最低** |
| **P0** | S3 分片上传 | `S3Client` **无 multipart**，单次 PUT 传整个文件 | 改 multipart（Part 5MB+）；2.8GB 单 PUT 失败即从头重来，且兼容端（R2/MinIO）限制更严——**健壮性问题** |
| P1 | 单遍哈希 | `sha256Hex` 全量读完再上传（两遍读盘） | 边流式上传边算，或流式签名（`UNSIGNED-PAYLOAD`/chunked） |
| P1 | zip 直传网络 | 写 cacheDir → 再读出上传 | 流式 `ZipOutputStream` → 网络 sink，省 5.6GB 磁盘往返 |
| P2 | 空间预检 + 字节级进度 | 备份前不查剩余空间；UI 仅 spinner | 预检 cacheDir 空间；按字节显示进度（数分钟内用户无从判断是否卡死） |
| P2 | ZIP64 | — | 工作区超 4GB 需 ZIP64；Java `ZipOutputStream` 会自动处理，但个别 WebDAV/兼容端可能不认 |

> **若将来换压缩格式**（zstd 多线程，level 1–3 达 200–500 MB/s/核、比率不输 DEFLATE），才是 Rust `zstd`+rayon
> 的舒适区；但这是格式决策（恢复需向后兼容），且 `zstd-jni` 在 Kotlin 侧同样可用——不构成现在引入 Rust 的理由。

**结论**：先做 P0 两项即可把该量级从「可能失败/极慢」变为「可用」；P1/P2 为体验完善。本项与工作区备份功能解耦，
可独立排期。

### 9.1 空间预检 + 字节级进度（2026-09-14 定稿，**先落实此项**）

P2 中的这一项被提前，理由：**纯新增、不改备份格式、向后兼容**，且直接消除最坏的失败模式
（跑几分钟才失败 / 磁盘写满导致部分覆盖），性价比高于 P0 的重构类改动。

**空间预检的必要性（安全 > 体验）**：

- **恢复是流式直接写最终目标**（`restoreFromBackupFile` 逐条目写 `filesDir`/目标目录）——磁盘中途写满 =
  **部分文件已被覆盖**，比整体失败更糟；数据库虽已改为「暂存后交换」，但暂存本身也要 cacheDir 空间。
- 备份打包同理：zip 写 `cacheDir`，写满即失败且无提示。

**两个方向的峰值构成**（必须都算，别只按 zip 大小判）：

| 方向 | 峰值需求 | 数据来源 |
|---|---|---|
| 备份打包 | zip ≈ **输入总量**（压缩只会更小，最坏持平） | **走盘求和**：`rikka_hub`(+`-wal`/`-shm`) 长度 + `upload/skills/fonts/tool_outputs` 递归 + `workspaces/*/files` 递归 |
| 恢复 | `cacheDir` 里的 zip **与**解压产物**同时存在** | zip 大小 = 列表项 `contentLength`；解压总量 = `ZipFile` **中央目录** `entry.size` 求和（不用解压，开销极小） |

**判据用保守值**（备份按不压缩计）：DB 是 JSON 型实测 2.27x 可压、工作区 git pack 近乎 1.0x，
混合数据真实比率无法预判，故宁可保守误拒，也不要在 2.8GB 跑到一半失败。失败提示须给出
**需要多少 / 现有多少**。

**字节级进度（四阶段，数据来源均现成）**：

| 阶段 | 来源 | 备注 |
|---|---|---|
| 打包 | 累计**读入**字节 / 走盘估算总量 | 计输入比计输出有意义（输出是压缩后） |
| 上传 | ktor 3.5.1 `onUpload { bytesSentTotal, contentLength }` | 已确认 `BodyProgress` 插件在 jar 内，**无需自造 channel 包装** |
| 下载 | ktor `onDownload` 或复用 `downloadToFile` 读循环计数 | `contentLength` 可能为 null（chunked）→ 退化为不确定态 |
| 解压 | 已处理 zip 字节 / zip 总长 | — |

**管道设计**：用**回调参数** `onProgress: (BackupProgress) -> Unit` 逐层传入，**不在引擎上挂 StateFlow**
（`BackupArchiveEngine` 被两个 Koin singleton 各持一份，挂共享 StateFlow 有并发串扰风险）；VM 持
`MutableStateFlow<BackupProgress?>` 接收、UI 收集。显式、无生命周期耦合、便于单测（传假回调断言序列）。

**UI**：备份/恢复按钮内 spinner → 带百分比的 `LinearProgressIndicator` + `已完成/总量`。
**不必加 6 语言字符串**（数字与百分比是纯量，阶段名可省）。

**坑（按重要性）**：

1. **S3 上传前的全量 SHA-256 是隐形空窗**：`S3Client.putObject(file)` 先 `file.sha256Hex()` 完整读一遍再上传，
   2.8GB 下数十秒**零反馈**；只接 `onUpload` 会让进度停 0% 像是坏了。必须把这段作为「校验/准备」阶段上报，
   或顺手做 P1 单遍哈希。**不处理则 S3 上的进度是假的。**
2. `onUpload`/`onDownload` 按 chunk 高频触发 → 写 StateFlow 会狂重组 Compose，需**节流**（≥1% 或 ≥100ms），
   并在 100% 强制补一次。
3. 回调在 IO 线程 → 用 `MutableStateFlow`（线程安全），勿直接改 Compose state。
4. **本地（SAF）目标/源空间查不到**（`ContentResolver` 不提供容量查询，见 9.2）→ 只预检 `cacheDir`，
   UI 需说明。
5. 分母在开始时**冻结**，进度做 clamp（打包中文件增删会让估值偏差）。

### 9.2 本地备份同享（SAF 边界）

**本地路径复用同一套引擎，故自动受益**：本地导出 `BackupVM.exportToFile()` → `webDavSync.prepareBackupFile()`
→ 同一 `BackupArchiveEngine`；本地导入 `restoreFromLocalFile()` 同理（`BackupVM.kt:87/93`）。
与「备份内容全局化」同一原理——engine 一处改动，本地/WebDAV/S3 三入口同时获得。

**本地反而更适合首先验证**：本地导入/导出是**用户主动全量归档**最常用的入口（2.8GB 多半即本地导出），
且没有 S3 的哈希空窗与网络不确定性，进度是干净的四段（拷贝入 / 打包 / 拷贝出 / 解压）。

**特有边界（无解，需在 UI 说明）**：本地导出/导入是**两段式**，第二段走 SAF：

| 段 | 位置 | 空间可查？ |
|---|---|---|
| 导出：`prepareBackupFile()` 写 cacheDir | app 私有 | ✅ `File.usableSpace` |
| 导出：`copyTo(contentResolver 流)` 到用户选定位置（`ImportExportTab.kt:65`） | SAF（SD 卡/云盘等） | ❌ 平台不提供 |
| 导入：从 SAF 源 `copyTo` 进 cacheDir（`ImportExportTab.kt:110`） | app 私有 | ✅ |
| 导入：引擎解压到目标 | app 私有 | ✅ |

故本地路径**只预检 `cacheDir`**，文案说明「仅检查应用缓存空间，目标位置由系统保证」。**导入侧预检尤其值得**：
用户已选中文件、点过确认，此时才因空间不足失败最恼人。

## 十、记忆（MemoryEntity）在备份/恢复中的 id 语义（2026-09-14 核实）

**问题背景**：记忆用整数 `id` 做 `memory_tool` 的 edit/delete 目标，历史消息里记录了这些 id（上游导出包中 158 处 `memory_tool` 调用含 id 引用）。恢复后这些 id 是否还对得上？

**结论：对得上，且这是表结构强制的，不是数据碰巧。**

| 事项 | 事实 |
|---|---|
| id 来源 | `MemoryEntity.id` = `INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL` → **行不可能无 id**；实测上游导出包 49 条记忆 id ∈ [1,66]，**空/非正 id = 0 条**（id 有空洞=删除留下的，AUTOINCREMENT 不复用旧号，`sqlite_sequence` 记录下次起点） |
| 恢复方式 | **整库文件替换**（`DatabaseRestoreFiles.moveIntoPlace` 同目录 rename），非逐行 INSERT → id、空洞、`sqlite_sequence` **原样保留**；历史消息里引用的 id 恢复后仍指向同一条 |
| 是否有 JSON 入口 | **无**。全仓无 `AssistantMemory` 的反序列化路径（只有 encode 成工具结果），记忆只能来自 DB——不存在「从 JSON 读进一条无 id 记忆」的通道 |
| 对话导出 | **不含记忆库**（Export.kt 只导出对话内容，`memory_tool` 调用仅作为历史渲染）→ **记忆只靠整库备份迁移** |

**唯一真实缺口：2025-04 之前的「内嵌记忆」格式会被静默丢弃。** 上游最早的记忆功能（`598d9c91`，2025-04-23）把记忆存在 **assistant JSON 内**（`Assistant.memories: List<AssistantMemory>`，id 为 **Uuid**）；次日 `d50d5cd7` 才迁到 DB 表（id 变 Int）。fork 的 `Assistant` 已无 `memories` 字段，Json 为 `ignoreUnknownKeys = true`，且 `SettingsJsonMigrator` **无任何一处**读 `assistants[].memories` → 拿 2025-04 前的旧备份恢复，这批内嵌记忆**不落 DB 表、UI 不显示、也不注入**（表现为记忆丢失，而非 id 报错）。**当前不处理**（极端老备份、低频）；若将来要兜底，可在 `SettingsJsonMigrator` 加一条：把 `assistants[].memories` 内容按 `assistant_id` 补建进 `MemoryEntity`。

**悬空 id（引用的记忆已被删）行为**（非恢复问题，但同族）：注入侧只取活行不引用死 id；会碰到的是审批预览 `getMemoryById(id)` 返回 null（卡片不显示目标内容，不崩）、`edit` 抛 `"Memory record #N not found"`、`delete` 删 0 行**静默无操作**。

### 10.1 删除后序号重排评估（2026-09-14，**不做**）

**触发**：上游 issue #1830《memory_tool 记忆编号删除后留空洞，希望能支持重排或指定编号》——删除不复用 id，用久后编号呈 1,2,17,18,28（中间空洞）。已 **closed as wontfix**（re-ovo「id不影响任何东西」；贡献者 duzhaokun123「记忆不应该暴露自增 ID——避免助手乱猜测记忆数量 / 避免用户看了难受」）。**上游选择「隐藏显示」而非「重排」**，无任何「重排难」的书面记录。

**关键更正：上游那次隐藏是半截修复。** 已核 `upstream/master`：`buildMemoryPrompt` 仍 `put("id", memory.id)`，`memory_tool` schema/示例（`{"action":"delete","id":7}`）仍暴露 id → duzhaokun123 的第一条理由（防助手猜数量，报告者实证「deepseek 把编号 28 理解成有 28 条记忆」）**未被修复**，`08c2648b` 只治了「用户看着难受」。

**重排难度分五层**（DB 副本实测，未动原数据）：

| 层 | 内容 | 难度 |
|---|---|---|
| 1. DB 更新 | 裸 `UPDATE id = row_number()` **不可靠**——实测抛 `UNIQUE constraint failed`（SQLite 更新行序不保证，撞未腾出的槽即炸）；正解=临时表重建（新序 INSERT 后 rename）或先偏移高位再回填 | 中（可控） |
| 2. `sqlite_sequence` | 即使排成 1..49，seq 仍停历史最大 66 → 下条 insert 拿 67 而非 50（实测）；须同步重置该表 | 低（易漏） |
| 3. **历史引用** | `message_node.messages` 存着工具调用**原始 JSON**（含 `"id": N`）——本备份 **158 处**引用、**含指向已删 id 的**（`delete 21`/`30`/`63`）。重排后 `delete 21` 会指向一条**仍活着**的记忆 = **历史语义被静默改写**；要处理须重写全部历史 JSON，而 fork 原则是「轨迹只追加」 | **高（真难点）** |
| 4. 在飞状态 | 待审批卡片/审批预览按 id 引用（2026-09-14 落地的审批功能）→ 重排会让进行中的卡片指向错条 | 中 |
| 5. **跑步机效应** | 重排只清「执行那一刻」的空洞；下次 delete 立刻再生空洞，新建仍从 max+1 递增。**不解决任何持久问题**（报告者自述「全删重建也解决不了…空洞只是换了个位置」） | 无解 |

**结论：DB 重排 ≈ 中-高且不值得**（治不了本 → 上游 wontfix 合理）。若确在意，三条路：①**显示层连续序号**（UI `forEachIndexed`，不动 DB，~0.5h，零迁移零破坏，即报告者最低诉求；但模型侧仍收原始 id）；②**稳定 opaque id**（uuid/短哈希，同时根治「空洞难看」+「不泄漏条数」且永不需要重排；难度中：DB 迁移加列 + 工具 schema + 提示词 + UI，须验证模型能否稳定用 uuid 做 edit/delete 目标）；③**DB 重排——不做**。

**fork 现状（已演进）**：方案 ②「稳定 opaque id」**已于 2026-09-14 落地**（`e416e7d4`）——
- `MemoryEntity.public_id`（8 位小写十六进制，32 bit 随机）；DB **27→28**（`ALTER TABLE` + `lower(hex(randomblob(4)))` 就地回填，纯 SQL）
- `AssistantMemory.id: Int → String`（对外 token）；加 `@Transient sortKey`（自增主键）仅用于注入选「最新 60 条」，不参与序列化
- 仓库按 token 查/改/删；**兼容历史消息里的旧自增编号引用**（token 查不到时按 id 回退）
- 工具 schema `id` integer→string + 描述强调「copy exactly, not a count」；提示词注入 token
- UI：审批预览按 token 查；记忆页新建哨兵 `id == ""`
**真机端到端实证**：迁移 54 行全获唯一 token（零空值、integrity ok）→ 请求日志 `"id":"b135fd7a"` → 模型回 `{"action":"delete","id":"b135fd7a"}` → 审批卡正确显示目标 → 批准后该行删除。**模型能稳定用 token 做 edit/delete 目标（此前的唯一未知项已消除）。**

**对上游 #1830 wontfix 的重新评估**：上游「隐藏显示」（`08c2648b`）**只治了「用户看着难受」，没治「模型猜条数」**（提示词仍注入自增编号，报告者实证的 deepseek 误判仍在）。本方案同时根治两者，且**永不需要重排**（随机 token 无「空洞」概念）——即当初判定「重排不做」是对的，但「隐藏显示」不是终点，opaque id 才是。


