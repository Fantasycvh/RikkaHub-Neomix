# 增量备份设计稿：文件区内容寻址 + DB 分层增量

> 状态：**设计草稿（2026-11-03 起，2026-11-04 更新）**。**P0 前置修复已落地**（2026-11-03，见 §2.4）；
> **P1 已完成**（2026-11-04，见 §0.2–§0.5；存储端加固与 Android 框架验收见 §0.4）；
> **P2 已落地（工作树未提交，见 §0.6–§0.7）**：DB 逻辑对象增量、跨 schema 恢复、FTS 重建进度 UX、
> 对象 GC/保留策略均已实现；更细对象、append-only 游标与真实远端 UI 冒烟仍未实施。
> 本稿是「如何实现增量备份」的完整设计输入，不是已完成功能的记录。
> **2026-11-04 复核**：新增 §0.1，记录当前代码核对和协议修订。与后文原方案冲突时以 §0.1 为准；
> 其中「P1 暂不包含自动保留/对象 GC」是 P1 阶段的决策，P2 已按 §0.7 补齐（GC 限单设备仓库）。
> **2026-11-04 已确认范围**：用户接受本地目录仓库，选择一个“备份文件夹”，与 S3/WebDAV 共用增量协议；
> 详见 §5.14。此后用户已要求开始 P1 实现，当前落地范围与验证结果见 §0.2。
>
> 调研依据：本仓只读源码核对 + 真机数据实测（`_measure/m.db` 115 MB / `_measure/settings.pb` 1.45 MB），
> 四份调研底稿在 `.tmp/incremental-backup/`（`.tmp/` 已 gitignore，**不入库**；本稿已内联需要的证据）。
> 上游诉求：rikkahub#2280「feat: 支持增量备份」（open，2026-10-31，正文「每次备份都要打包并上传一个
> 不断膨胀的压缩包，死慢死慢的」）<https://github.com/rikkahub/rikkahub/issues/2280>。
>
> 关联设计稿：`design-backup-workspace-files.md`（备份范围/大文件加固）、
> `design-backup-manifest-and-safety.md`（manifest v2 + 校验）、`design-backup-staged-restore.md`（staged 换库）、
> `design-conversation-import-export.md`（对象导出格式）、`design-export-retention.md`（本地留存）。
>
> 约定：`A/` = `app/src/main/java/app/androidrik/rikkahub/`，`T/` = `app/src/test/java/app/androidrik/rikkahub/`，
> `D/` = `docs/design/`。行号对应当前工作树（2026-11-03）。

## 0. TL;DR（先看这里）

- **现状**：每次备份 = 一份**全量 zip**（settings.json + rikka_hub.db + FILES + WORKSPACE_FILES），
  上传到 WebDAV/S3 或用 SAF 导出。远端**只增不减**、无逐文件清单、无上传断点。
  最坏数据量 2.8 GB（工作区，近不可压缩），20 Mbps 下 ≈ 19 分钟；DB 115 MB **每次整库重传**。
- **方案骨架**：把一次备份拆成「**自包含快照清单** + **内容寻址对象**」：
  - 快照清单列出该次备份的**全部文件**（路径 → 原始内容 SHA-256 → 对象位置）以及 DB/settings 对象；
  - 对象按**内容哈希**命名，内容没变就不重传；快照之间**没有父子链**（每份都能独立恢复），
    因此删旧快照/上传中断都不会断链，WebDAV 缺少 MOVE/条件写也能工作。
- **分两层落地**：
  - **P1（推荐先做）**：文件区（FILES + WORKSPACE_FILES）增量；DATABASE 仍整库快照、settings 整份，
    二者各自作为内容寻址对象；恢复 = 本地拼回一份 v2 zip → **复用现有 `restoreFromBackupFile`**
    （破坏性路径、manifest 校验、safety_backup、staged 换库全部零改动）。
    存储目标包括 **本地 SAF 目录仓库、S3、WebDAV**；用户选择备份文件夹后复用其中的内容对象，
    原有单文件全量 ZIP 导入/导出继续保留。
  - **P2（条件成熟再做）**：DB 逻辑增量（会话/设置/记忆等按对象，剔除可重建的 FTS），
    把「每次 ~50 MB DB 重传」降到「只传改动的会话（通常 KB～MB 级）」；恢复在启动期把对象合并进
    现库副本再走 staged 换库，不改 Room 写路径。
- **明确不做**：CDC/restic 全套块级去重（收益窄、状态机重）、`sqlite3session`（需自编 native/JNI）、
  按 `updatedAt` 拉行（本仓 `update_at` 漏写 10+ 处、`message_node` 无时间戳）、
  把「自动定时备份」顺带实现（独立产品决策）、本地链式增量 ZIP、持续改写单个大型备份容器。
- **顺带发现并已修的 4 个既有缺口（P0，2026-11-03 落地）**：
  ① `backgrounds/` 打包但恢复分支缺失（恢复后助手背景丢）；② `avatar-frames/` 既不打包也不豁免；
  ③ 工作区条目没接 STORED 压缩策略（2.8 GB git pack 白烧 DEFLATE）；
  ④ 远端列举缺口：S3 `maxKeys=1000` 不翻页（>1000 对象旧备份静默不可见）、WebDAV `path` 含 `/` 被编码成 `%2F`
  （放不了多级对象目录）。详见 §2.4。

### 0.1 2026-11-04：结合当前代码的复核与调整

**结论**：继续采用「完整快照清单 + 内容寻址对象」；P1 先实现文件级增量、完整 DB/settings 对象、
拼 v2 zip 恢复。小文件 pack 可以保留在协议中，但自动保留、共享对象回收和 DB 逻辑恢复不进入 P1。
以下是实施前对原草稿的修订要求；后续落地状态见 §0.2。

#### 实施前核实的实现边界（历史记录）

- P0 确实已在当前源码中：`BackupFolderRegistry.kt` 驱动打包和恢复，包含 backgrounds、avatar-frames；
  `WorkspaceBackupHelper` 使用 `addFileToZip`；`S3Sync` 调用 `listAllObjects`；WebDAV 使用
  `WebDavPaths` 和逐级 `mkdirs`。不再新建 §5.12 所列的第二份 `BackupPackingRegistry`。
- 当前没有 `data/sync/repo/` 的快照协议、planner、对象仓库或恢复组装器；`BackupManifest` 仍为 v2。
- 当前 `addDatabaseToZip` 在 VACUUM 失败后仍会热拷主库与 WAL/SHM；`DataOperationLock` 只互斥
  备份/恢复/清理，不冻结普通 DAO、附件写入或终端写入。
- 本轮是源码与设计复核，未重新运行测试、未重新测量真机；§2.1 数据及 §2.4 的 2916 项测试结果
  保留为原稿的历史证据，不代表 2026-11-04 的新测量或新测试结果。

#### P1 实施前必须补齐的正确性约束

1. **哈希缓存不能保证不漏变更（修订 §5.6）**。
   同路径、同 size、同 mtime 的文件仍可能内容不同，工作区可原地改写并保留时间戳。
   复用旧哈希再与远端旧清单比较，只会让两边一起沿用旧值，不能证明本地未变。
   P1 默认对可变文件重新读内容计算哈希；不可变文件只有在写入约束可证明、缓存有效时才走快路径。
   全量重算作为默认保守退路。性能与正确性分别说明，不能继续宣称元数据缓存“不影响正确性”。

2. **计划必须固定实际上传的字节（修订 §5.7）**。
   `plan` 与 `commit` 之间存在确认窗口；即使持有 `DataOperationLock`，用户/终端仍可改写文件。
   待上传文件应在生成计划时复制到任务暂存区，并对暂存内容计算哈希；commit 只上传这些固定字节。
   若采用提交时重新读取方案，必须重算哈希、更新清单并在上传量变化时刷新预估。
   这提供逐文件捕获语义，不承诺 DB/settings/工作区在同一个全局时点一致。
   扫描失败、目录不可读、捕获时文件消失不得静默当成删除；重试或明确失败。
   瞬态文件过滤限定到已知写入方的目录与命名规则，不能全局跳过工作区里的 `*.tmp`/`*.part`
   （它们可能是用户需要备份的真实文件）。工作区遍历还应定义符号链接策略，防逃逸与递归环。

3. **复用引用前检查远端存在性（修订 §5.5–5.7）**。
   最新清单是索引，不是对象仍然存在的证明；远端对象可能被用户或其他客户端删除。
   对拟复用的唯一 object/pack 做存在性检查，缺失则由本地内容重新物化；pack 缺失时重打包本次所需文件。
   对象层增加明确区分“存在/404 不存在/查询失败”的 stat 结果，不把鉴权、超时、5xx 当不存在。
   HEAD/长度仅证明存在，不证明内容正确；恢复必须校验完整哈希，另可提供仓库完整性巡检。
   仓库状态应包含持久 repoId；本地缓存键使用目的地完整身份与 repoId，仓库重建后失效。

4. **提交不能只判断清单路径存在（修订 §5.2）**。
   `createdAt` 秒级时间 + deviceShort 不能保证唯一，同设备同秒重试可能覆盖旧清单。
   引入 snapshot UUID，时间仅用于展示/排序。先上传对象，再上传 manifest，最后上传
   `complete` 标记，标记保存 manifest 的压缩字节哈希及长度；读侧校验标记、清单和引用关系。
   列表中“有文件”不是“已成功提交”；半份清单、损坏标记和未知版本不得作为 planner 基准。
   提交响应丢失时以同一 snapshotId 查询并核验，处理“服务端已提交、客户端未知”的情况。
   首次能力探测使用任务唯一探针路径，不能共用固定探针后互相删除。

5. **共享对象 GC 必须推迟（修订 §5.7、§7、§8）**。
   “删除本设备旧快照的引用 − 本设备保留快照的引用”可能包含其他设备仍在使用的对象。
   即使读取所有设备的已提交清单，也可能与“复用旧对象、尚未提交”的上传任务竞争。
   P1 默认不限保留，不自动删除清单或共享对象；手动删除快照只移除其提交标记和清单。
   后续 GC 至少需要全仓可达性分析、失败即停止，以及明确的跨设备写入/回收协调协议。
   宽限期只降低竞争概率，不能独自提供安全保证；“不列举全量对象”也不等于“不误删”。
   （2026-11-05 已按此实现：全设备可达集 + mtime 宽限期 + 仓库租约 + 删除前复核 + 提交前 stat 兜底，见 §0.10。）

6. **P1 数据库对象与现有回退路径不兼容（修订 §5.7、§5.8）**。
   P1 只有一个 database 引用、恢复不含 WAL；不能直接复用热拷回退后只保留主库，
   否则会遗漏尚在 WAL 中的已提交数据。抽出保证一致且自包含的数据库快照接口，VACUUM
   失败时新路径明确失败，直到具备经验证的替代方案。旧 ZIP 回退路径作为独立问题处理。
   DB 尚未创建也要单独建模：`未选择 DATABASE`、`选择但源库不存在`、`已有可恢复库`不能混淆；
   沿用现有“无源库则跳过换库、保留目标库”语义时，清单应明确表达该状态，不能简单要求
   `DATABASE in items => database != null`。
   SQLite 官方确认 VACUUM INTO 输出为一致性快照，但中断可能留下不完整输出：
   <https://www.sqlite.org/lang_vacuum.html>。只有成功完成并通过必要检查的产物才能进入计划。

7. **恢复仍有语义与空间边界（修订 §5.8）**。
   当前恢复是覆盖写文件，没有删除快照之外的目标文件；“清单不含某路径”只能描述备份状态，
   不能据此承诺恢复后目标目录精确等于快照。P1 明确继承覆盖语义；精确还原另设受限目录策略。
   当前回滚保护的是数据库，不是 settings + 用户文件的全量事务；组装 ZIP 不会自动提升这一点。
   空间预算必须按任务阶段计算峰值：下载的 packs/编码对象、解码/校验临时文件、组装 ZIP、
   引擎暂存 DB 和 safety_backup 可能同时存在，不能只复用旧 ZIP 的单次解压估算。
   逐 pack 消费和清理、已校验内容流式写 ZIP，可降低峰值；必要时复用已落地的 STORED 策略，
   不应在 GB 级不可压缩文件恢复中无条件退回 DEFLATE。

#### 协议细节需一并收紧

- `SnapshotObjectRef` 明确拆分完整远端 key、原始 SHA-256、rawBytes，避免 §5.3 把 key
  同时用作路径与摘要。对象头为编码事实来源；同内容不同编码会生成不同存储字节，不能声称
  同 key 的所有 PUT 字节相同。P1 固定编码规则，校验头与声明；编码策略升级需定义兼容规则。
- pack 文件哈希与条目原始哈希都验证；拒绝重复 ZIP entry、规范化后重复路径和解压超限。
  `Pack.entry` 是源条目定位，`SnapshotFileEntry.path` 是恢复目标，允许改名后复用原条目。
  全包格式仍需按整包下载；小变更长期留下部分有效 pack 的空间放大留待后续压实策略测量。
- 清单自身 gzip 解压、JSON 条目数/长度、引用 key 前缀、压缩输入大小也要有预算，
  不能仅在最终文件解码时应用 2 GB/8 GB 限额。哈希提供内容一致性，不提供清单来源认证。
- `list(prefix)` 当前在 S3 表示递归前缀、在 WebDAV 表示直接子项；接口应明确
  `listChildren`/递归列举契约，避免未来 GC 在两端拿到不同范围。
- `plan` 会写 DB 快照和任务暂存文件，应称“不写远端/不提交索引”，不称纯只读。
  明确任务目录、过期时间和取消/崩溃清理；短临界区保护暂存区生命周期，不能误把持锁等待
  用户确认视为冻结业务数据。组装阶段与恢复阶段交接时避免重入非可重入的 `DataOperationLock`。

#### P2 调整：先验证 FTS 精简，再研究逻辑对象

原稿 P2 的“当前库副本 + 列交集插入”会混入恢复设备数据，且列交集无法执行 Room 的语义迁移。
启动前也还没有 Koin/Room 服务可直接复用；组装、应用、FTS 重建、确认和失败重启需有独立状态机。
因此不把这一方案作为已确定路线。

建议先做独立 P2-lite 实验：只修改一致性快照副本中的 FTS 虚表内容和派生映射，再压实副本；
保留 Room 所需 schema，不直接删除 FTS shadow tables。明确重建标记与启动时序，并验证搜索结果、
重建中断恢复、完整性、实际压缩字节与耗时。原稿 53.8% 是样本页足迹，不能直接推导
“上传 50 MB 降到 22 MB”；收益必须测量生成的压缩产物。

若随后实施逻辑对象：从同一一致性 DB 快照导出，规范化完整行与顺序；现有 node signatures
只是应用内增量保存的辅助表示，不是覆盖全部表字段的规范备份协议。流水表也不能仅记录
上次游标后的行，否则破坏完整清单独立恢复，且漏掉 trim/更新/删除。
跨 schema 应有显式对象版本迁移或在旧版本临时库重建后走既有 Room 迁移链；
先限定同 schema 可减小首轮范围。恢复需验证外键、主键/自增及业务引用，不以 integrity_check
通过代替业务正确性；全新设备无当前库时也必须能恢复。

#### 调整后的交付与验收

1. P1 协议/捕获：UUID + 完成标记、引用校验、文件捕获、repoId/缓存边界、单库快照接口。
2. P1 上传/恢复：先单对象闭环，再按小文件实测决定 pack 是否同时开启；两端适配、失败重试、
   峰值空间预检、拼 ZIP 与现有恢复接线。本地 SAF/S3/WebDAV 三端共用协议，旧 ZIP 保持兼容，默认不做 GC。
3. P1.5：测量真机吞吐与缓存收益、pack 放大和长任务中断；定义安全回收协议后再做保留与 GC。
4. P2-lite / P2：以 FTS 精简实验与真实上传瓶颈决定投入，不沿用原稿未经验证的压缩收益或工期。

新增验收重点：同 size/mtime 改内容；plan 后改文件；扫描目录读失败；远端引用对象被删；
半份清单/提交响应丢失；同秒两次备份；多设备共享引用；VACUUM 失败；无源库；
工作区合法 `.tmp` 文件；pack 重名/解压超限；低存储恢复；取消与清理竞争。
现有 §6 测试列表继续适用，但不足以替代这些场景。

### 0.2 P1 首版落地记录（2026-11-04）

**已实现代码**：`A/data/sync/repo/` 新增完整清单/完成标记、原始内容 SHA-256、对象编码、
固定内容的 plan/commit、引用存在性检查、仓库身份验证、快照列举/删除与 v2 ZIP 恢复组装器；
接入 `SafBackupObjectStore`、`S3BackupObjectStore`、`WebDavBackupObjectStore` 和共享服务。
备份页增加独立“增量备份（实验）”入口（2026-11-05 起备份页改为二级列表 + 三级详情页，见 §0.13），
可选择本地文件夹、WebDAV、S3，扫描预估后确认执行，
列出恢复点、确认覆盖恢复或删除快照。原有 ZIP 按钮继续走原路径，没有自动切换其行为。

**首版取舍**：

- 所有文件使用单对象引用；pack 尚未开启，按 §0.1 的“先单对象闭环”顺序实施。
  首备大量小文件的 provider/网络请求开销尚需实测；没有据此承诺吞吐目标。
  引入 pack 引用时须升级快照格式版本，不能仅添加被旧 reader 忽略的字段。
- 每次重新读取源文件算哈希，不启用 size/mtime 哈希缓存；本地 `backup_index/` 只保存设备 ID
  和文件夹关联。无需旧索引即可在新设备读取整份仓库。
- 计划最长保留 30 分钟，取消/退出页所属 VM 时清理；进程中断残留在共享服务首次使用时清理。
  计划阶段不写仓库；首次确认提交才初始化 `repo.json`。本进程任务串行，跨设备同时初始化/写入
  同一个 SAF 仓库尚未验证，不承诺安全并发写入。
- 新上传对象读回并解码校验，清单/完成标记读回确认；预估显示新增写入/上传量，
  **不包含读回校验流量**。只读本地仓库允许恢复，备份/删除入口禁用。
- 新路径 VACUUM 失败直接失败，不热拷；源库不存在明确记录 ABSENT。禁止选择本应用工作区
  provider 作为目标，防止仓库被递归备份自身。共享对象 GC、自动保留、定时器和加密未实现。
- SAF 新建文件写入失败时尽力移除本任务刚创建的 document；不删除既有对象。
  无法清理的半写对象或仓库损坏会明确失败，完整的修复工具不在首版。

**验证证据**：

- `:app:assembleDebug` 通过，生成 arm64/x86_64/universal Debug APK。
- 最后一次 `:app:testDebugUnitTest --tests 'app.androidrik.rikkahub.data.sync.*'`：**167 项，0 失败**，
  其中新增 repo 测试 **41 项**（引擎 29、SAF 假 provider 10、文件枚举 2）。
- 此前全量运行 **2981 项、12 失败、2 跳过**（当时新增测试 35 项）；在 HEAD `3add4f081` 的独立
  工作区运行相应 5 个原有测试类，**29 项中同样 12 项失败**，失败测试名称一致，涉及
  PurgeObservationStore、DataStore、SkillAtomicWrite 和 FilesRoutesPathScope。
  因此全量结果不能写为全绿；不是本批引入的这 12 项基线失败未在本批修复。
- IME 审计 A/A' 为 0，B/C 为既有告警；`git diff --check` 通过。
- **未进行真实云端上传、真实 SAF provider/可移动存储故障及恢复后重启的真机验收**。
  JVM SAF 假 provider 测试不能替代 §5.14 的真机矩阵；P1 不标记为验收完成。

### 0.3 全量测试基线修复（2026-11-04）

用户随后要求先处理 §0.2 的 12 项失败，已完成修复与全量回归：

- 7 项共因是 `writeFileAtomically` 使用 `File.renameTo`，在 Windows 无法替换已有文件：
  改为同目录 NIO `ATOMIC_MOVE + REPLACE_EXISTING`，失败时不删旧目标、不退化为拷贝。
  净化观察计数的后续持久化恢复正常，技能文件可覆盖保存；补充中途读流失败保留旧内容、清理暂存的回归。
- DataStore 的 3 项失败来自 Android FileStorage 的重命名操作在 Windows JVM 上不适用：
  设置工厂允许注入 Storage 和协程作用域，测试使用真实 Okio 文件后端并 `cancelAndJoin` 释放；
  Android 默认文件后端、设置路径、protobuf 格式、迁移和损坏备份策略保持原有行为。
  测试另验证 Okio 写出的设置可用默认生产后端重新读取。
- Web 文件路径的 2 项失败来自 Windows `canonicalFile` 未解析符号链接：改为 NIO `toRealPath`，
  并明确拒绝软链白名单根目录，正常媒体路径仍通过既有测试。
- 最终 `:app:testDebugUnitTest`：**2988 项，0 失败，0 错误，2 跳过**；原 5 个失败测试类合计
  **30 项全部通过**。`:app:assembleDebug` 与 `git diff --check` 同样通过。

§0.2 的失败数字保留为当时的基线记录，当前不再有这 12 项失败。上述回归不替代 P1 真机验收。

### 0.4 P1 存储端加固与 Android 框架集成验证（2026-11-04）

本轮继续收尾 P1，不推进共享对象 GC 或 P2。以下改动不改变已提交的快照格式：

- WebDAV 只在目录 PROPFIND 返回 404 时创建目录，鉴权/网络/其他错误直接失败；创建后再次验证
  确为目录，避免将 405 或同名文件误认为建目录成功。
- WebDAV 列表按请求集合的 URI 识别直接子项，不依赖响应顺序或 `displayname`。
  支持编码后的空格/中文，拒绝跨集合、跨站、重复解码名称和非直接子项；旧 ZIP 列表同步使用此规则。
  S3 列举也检查响应 key 确实位于请求前缀内。
- SAF 按返回 Cursor 的列名读取 document ID、名称、类型和大小，不假定列顺序。
- S3/WebDAV 下载原语接受 `maxBytes`，在响应头已超限时拒绝，在未知长度时逐块检查后才写盘。
  增量仓库将清单/对象预算直接传到下载原语；旧 ZIP 调用保留原有默认行为。

**新增验证**：

- 新增 4 项 JVM WebDAV 列表测试，覆盖父集合位于末尾、显示名不等于文件名、编码与越界/重复响应。
- 最终 `:app:testDebugUnitTest`：**2992 项，0 失败/错误，2 跳过**；常规应用 ID 的
  `:app:assembleDebug` 和 `git diff --check` 通过。
- 在 Android 15 / API 35 真机运行 `IncrementalStorageInstrumentedTest`：**6 项通过，0 失败/跳过**。
  SAF 使用真实 `DocumentsContract`、Cursor 和文件描述符，经 `ContentResolver.wrap` 接入测试
  `DocumentsProvider`（重排返回列且大小未知）；包含首备、零变化复用、修改后增量、旧/新快照恢复与删除。
  另验证提供方故障和自动改名不会被当作正常提交。
- 同一真机上使用实际 Ktor/OkHttp、Android XML 解析与 S3/WebDAV 客户端访问 **127.0.0.1 回环夹具**，
  验证三轮备份、旧/新恢复、403 时不 MKCOL、带/不带 Content-Length 时的下载预算。
  S3 夹具只检查存在签名请求，不验证 AWS 身份认证或厂商实现，也未覆盖 multipart 上传。
- 使用 `-PbackupIsolatedTests=true` 构建独立包
  `app.androidrik.rikkahub.backupverification.debug`，复用隔离 runner，避免安装覆盖已有用户/Debug 配置；
  全部输入为自造内容，不读取设置、对话或备份凭据。测试命令：

```text
gradlew :app:connectedDebugAndroidTest -PbackupIsolatedTests=true -Pandroid.testInstrumentationRunnerArguments.class=app.androidrik.rikkahub.data.sync.repo.IncrementalStorageInstrumentedTest
```

**仍待验收**：系统目录选择与持久授权、真实 DocumentsUI/第三方 provider、可移动卷与进程中断、
真实 S3/WebDAV 厂商及大文件 multipart、应用恢复后重启/回滚；以上框架和回环测试不替代这些场景。

### 0.5 P1 完成结论（2026-11-04）

用户确认 P1 完成。完成时的验收边界：

- **已通过**：全量 JVM 单测 **2992 项，0 失败/错误，2 跳过**（2026-11-04 复核重跑）；
  `:app:assembleDebug` 通过；新增 WebDAV 列表（4 项）、S3 前缀校验、SAF 假 provider（41 项）
  等 JVM 测试；Android 15 真机 `IncrementalStorageInstrumentedTest` **6 项通过**（§0.4），
  含真实 `DocumentsContract`/Cursor 与 loopback Ktor/OkHttp 夹具。
- **未覆盖（不阻塞 P1 完成，按 §0.2/§0.4 记录）**：真实第三方 DocumentsUI provider/可移动卷/
  进程中断，真实 S3/WebDAV 厂商与大文件 multipart，恢复后重启与回滚矩阵；共享对象 GC、
  自动保留、定时备份、加密按设计明确不包含在 P1。
- **后续**：P2（DB 对象级增量）未启动；P1.5 可在需要时评估对象 GC、pack 引用与哈希缓存。

### 0.6 P2 首版落地记录（2026-11-04，工作树未提交）

> 用户已要求开始 P2。本记录只描述**已写进工作树、尚未提交**的首版实现与验证；
> 是否提交/推送需另行授权（AGENTS.md）。

**范围与决策（首版收窄，先跑通再扩）**：

- **快照格式 v2**：`BackupSnapshotManifest` 新增 `dbObjects: List<SnapshotDbObject>`；
  新快照不再包含 `rikka_hub.db` 文件对象，而是若干逻辑 DB 对象（内容寻址、可跨快照去重）。
  v1 清单仍可读、可恢复（`formatVersion` 缺省为 1；v1 继续允许 `dbVersion <= 当前`）。
- **对象集合（首版只做两类，覆盖 98% 字节）**：
  - `conversation:<uuid>`：该会话的 `ConversationEntity` 行 + 其全部 `message_node` 行 +
    `compression_checkpoint` 行，三者放在同一个 JSONL 对象里（会话是硬删 + CASCADE，
    按会话整体替换最贴近现有语义）；
  - `table:<name>`：其余**全部非排除表**整表导出（含空表）——首版用「整表替换」换正确性，
    不按 assistant/游标拆对象。
  - **排除**：`message_fts*`（含 `message_fts_rowid`，派生可重建）、`room_master_table`/
    `android_metadata`/`sqlite_*`（库簿记）、`memory_review_cursor`（本机复核水位，可重建）。
    注意 `memory_consolidation` **不能排除**：它挂 CASCADE 外键指向 `conversationentity`，
    排除后合并会留下孤儿进度行并让 `foreign_key_check` 失败，所以它作为整表对象随快照替换
    （体积可忽略）。排除清单集中在 `DatabaseObjectProtocol.EXCLUDED_DB_TABLES`。
- **payload 格式**（`DatabaseObjectProtocol.kt`）：每对象一个 JSONL 文件，首行 header
  `{"v":1,"objectKey":...,"tables":{"<实际表名>":["列",...]}}`，其后每行
  `{"t":"<表>","r":[值...]}`。值编码：JSON null、整数/浮点（保留 INTEGER/FLOAT 亲和性）、
  TEXT 字符串、`{"$blob":base64}`、`{"$real":"NaN|Infinity|-Infinity"}`。
  压缩仍复用 `SnapshotObjectCodec`（整对象 DEFLATE），对象 sha256/大小与文件对象同一套去重协议。
- **捕获**（`DatabaseObjectCapture.kt`）：`VACUUM main INTO` 出的**一致性快照**上，
  用平台 `android.database.sqlite` 只读普通表（先过滤排除表再读列，避免 FTS 分词器在
  无 `libsimple` 的进程里被解析），会话对象按 `id`/`conversation_id` 过滤，表对象按主键
  （否则 rowid）排序；payload 写完即删快照与 `-wal/-shm`。
- **恢复合并**（`DatabaseObjectMerge.kt`）：以**当前库的 `VACUUM INTO` 副本**为基底
  （当前 schema，天然含新表/新列），`PRAGMA foreign_keys=OFF` + 单事务：先清空快照覆盖的
  全部表，再按对象表顺序插入；插入列 = payload 列 ∩ 当前表列（新列取默认值，旧列忽略），
  最后 `PRAGMA foreign_key_check`，有违例则整份丢弃、恢复失败；`journal_mode=DELETE` 后
  交给现有 staged restore。**FTS 表故意不重建**：随基底保留，启动后整库重建。
- **拼 zip 复用现有恢复**（`IncrementalBackupEngine.assemble` 增加 `mergeDatabase` 回调）：
  v2 快照恢复时先下载/解码 db 对象 → 合并出 `rikka_hub.db` → 与 settings/files 一起拼成
  **标准 v2 zip**（`DATABASE` section 的字节数/哈希按合并后的文件填）→ 原样调用
  `BackupArchiveEngine.restoreFromBackupFile`。破坏性恢复、`PendingRestoreStore`、
  safety backup、重启换库全部零改动。
- **FTS 重建**：恢复成功后写 `files/backup_index/fts_rebuild_pending` 标记
  （`FtsRebuildPending`）；`RikkaHubApp` 在 `StartupState.Ready` 之后用 `AppScope` 后台调
  `ConversationRepository.rebuildAllIndexes()`，成功清标记、失败保留以便下次启动重试。
- **版本闸门**：v2 快照要求 `snapshot.dbVersion <= 当前 AppDatabase.SCHEMA_VERSION`；更新版本快照拒绝。
  跨 schema 的合并语义（清空范围、列交集/默认值、跳过已删除表并告警）与验证见 §0.7；v1 同样 `<=`。
- **UI**：快照对象数/数据库大小按 `files + dbObjects` 汇总显示（`SnapshotBackupTab`）。

**验证**：

- JVM：`:app:testDebugUnitTest` **3008 项通过，0 失败/错误，2 跳过**（新增 16 项：
  `DatabaseObjectProtocolTest` 6、`IncrementalDatabaseBackupTest` 8、`FtsRebuildPendingTest` 2，
  另有既有 repo 测试覆盖 v1 兼容/损坏对象/版本闸门等）。
- Android 15 真机（PJX110）：
  - `DatabaseObjectCaptureMergeInstrumentedTest` **3/3**：合成库捕获→合并、清空/插入顺序、
    FK 违例拒绝、版本不匹配拒绝、额外本机表保留、列交集默认值；
  - `DatabaseObjectRealSchemaInstrumentedTest` **1/1**：真实 Room schema（含 `libsimple` FTS）
    捕获→合并，平台 SQLite `integrity_check=ok`、`foreign_key_check` 无违例、Room 可正常打开，
    即与恢复暂存校验同款检查通过。含「快照里没有的旧会话 + 其记忆整理进度」用例：验证
    `memory_consolidation` 必须随快照整表替换，否则孤儿进度行会让 FK 校验失败。
  - 另在真机空库（当前 schema）做了一次只读捕获冒烟：21 个表对象、payload 4.3 KB、
    FTS 对象 0、`memory_consolidation` 正常导出。
- **真实数据补测（2026-11-04 19:20–19:22，debug 库 269 会话 / 7,588 节点 / 113.5 MiB）**：
  用 `adb shell am instrument` 直接跑一次性测量（只 VACUUM 副本，不改 app 数据）：
  - 现库 118,989,824 B（+ WAL 17,848 B）→ VACUUM 快照 118,990,848 B / **537 ms**；
  - 捕获 **3.06 s** → 290 个对象（269 会话 + 21 整表）；payload **55,706,027 B（53.1 MiB，
    占 DB 46.8%）**；其中会话对象 54,645,349 B、表对象 1,060,678 B（`usage_record` 855 KB、
    `MemoryEntity` 160 KB 为主）；FTS 对象 0、`memory_consolidation` 正常导出；
  - 首份 v2 快照上传体积估算（逐对象 DEFLATE，2.3 s）：**19,956,998 B（19.0 MiB）**，
    同数据 P1 整库压缩态约 50 MB ⇒ **约 -60%**；单会话改动上传约 **KB～0.7 MB**
    （最大的 5 个会话压缩后 527–727 KB，`usage_record` 表 75 KB）；
  - 同 schema 合并回当前库副本 **3.57 s**：merged 118,866,944 B；`integrity_check=ok`、
    `foreign_key_check` 0 违例、`user_version=58`；会话/节点/检查点/记忆整理行数与现库完全一致，
    逐表 payload 行数 vs 合并结果 mismatch=0；
  - FTS 重建端到端实测：预置 `fts_rebuild_pending` 后冷启动，**20 s 内标记被清除**，
    logcat 出现 `RikkaHubApp: search index rebuilt after snapshot restore`（重建本身约 15 s，
    非此前担心的分钟级），启动钩子链路验证通过。
- **真实数据全链路 e2e（2026-11-04 19:29–19:33，同一 debug 库，跑了两轮）**：
  一次性 instrumentation 用测试本地内容寻址目录实现 `BackupObjectStore`（与 SAF/S3/WebDAV 同一
  `v3/` 布局，避开 UI/SAF 授权依赖），走真实 `plan→commit→assemble(merge)→restoreFromBackupFile
  →标记 FTS→冷启动换库`，并在备份后插入 canary 会话验证回滚：
  - 首份快照：290 对象 / payload 55,706,027 B，内容寻址对象实际上传 `60,665,061 B（57.9 MiB，
    含 FILES 对象）`，其中 DB 逻辑对象 19.96 MB（上条前测）；
  - 数据未变的第二份快照：**内容对象上传 0 B**（`uploadBytes=0`，仅提交新的快照 manifest），
    即增量语义在真实仓库上成立；
  - 组装 + 合并：**12.6～13.8 s**，产出 zip 114–127 MB；`restoreFromBackupFile` 暂存换库
    **20.2～22.2 s**（`workspaceFilesRestored=3`、`settingsWarnings=[]`、`pending=true`）；
  - 冷启动（instrumentation 起进程）：boot 应用暂存换库 → Ready → FTS 重建完成，
    从 tokenizer 安装到 `search index rebuilt` 约 **20～21 s**；
  - 校验（两轮均通过）：会话/节点/检查点/记忆整理行数与字节数与快照基线**完全一致**
    （269/7,588/2/6，`messageBytes=32,966,764`、`nodesBytes=538`），canary 会话/节点均为 0 行，
    `message_fts` **7,762 行**，`integrity_check=ok`、无 FK 违例。
- `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 通过；`git diff --check` 通过。

**首版边界（其中前三项已在 §0.7 补齐）**：~~跨 schema 对象恢复~~、~~FTS 重建进度 UX~~、
~~对象 GC/保留策略~~（均已实现，见 §0.7）；仍未做：`memory_store:<assistantId>` 等更细对象、
append-only 流水游标、真实 S3/WebDAV/SAF 远端网络上传与 UI 入口冒烟
（本轮 e2e 用本地目录 store 验证同一协议与恢复链路）、GC 跨设备协调。

### 0.7 P2 后续三项落地记录（2026-11-04，工作树未提交）

> 接 §0.6。用户要求继续处理「跨 schema 恢复、FTS 重建进度 UX、对象 GC/保留策略」。
> 以下只描述**已写进工作树、尚未提交/推送**的实现与验证（AGENTS.md 提交授权另议）。

**1. 跨 schema 恢复**

- **版本闸门**：`assemble` 对 v2 只要求 `snapshot.dbVersion <= 当前 AppDatabase.SCHEMA_VERSION`；
  更新版本的快照仍直接拒绝（「Snapshot database is from a newer app version」）。
  首版保守的 `==` 已放开（见 §5.9 对应条目）。
- **合并语义**（`DatabaseObjectMerge.merge(baseDb, objects, currentDbVersion, snapshotDbVersion, onWarning)`）：
  - 基底仍是当前库的 `VACUUM INTO` 副本，且基底 `user_version` 必须等于当前版本；
  - `snapshotDbVersion < currentDbVersion` 时**清空所有非排除表**——旧快照不含新版本新增的表，
    保留当前内容会与新恢复的会话产生悬空引用（FK 校验失败）；清空等价于「旧版本全新库 → 走 Room 迁移」；
    同版本时只清快照覆盖的表，保留本机/新增表（与原行为一致）；
  - 插入列 = payload 列 ∩ 当前表列：新增列取默认值并告警，已删除列忽略；快照里有而当前库没有的**表/对象**
    跳过并告警（不中断恢复）；空插入列但有行等非法 payload 仍拒绝；
  - 合并后 `foreign_key_check`，有违例整份放弃；`journal_mode=DELETE` 后交给现有 staged restore。
- **无法自动迁移**：列改名/语义变化只能靠告警（设计稿 §8.1 R12）。恢复确认框与快照卡片都显示
  「快照 vN → 当前 vM」提示，让用户在覆盖前看到风险。
- 调用方传入 `currentDbVersion = AppDatabase.SCHEMA_VERSION`、`snapshotDbVersion = snapshot.dbVersion`，
  合并 warning 汇总进 logcat。

**2. FTS 重建进度 UX**

- 新增 `A/data/db/fts/SearchIndexRebuildState.kt`：`Idle / Running(done,total) / Succeeded(conversations) /
  Failed(message)` + `SearchIndexRebuildSignal`（StateFlow；started/progress/succeeded/failed/reset）。
- `RikkaHubApp.rebuildSearchIndexIfPending()` 在 `StartupState.Ready` 后发 started/progress/succeeded/failed；
  成功清 `fts_rebuild_pending`，失败保留标记并在状态里给出原因；新增 `retrySearchIndexRebuild()`
  （仅 Ready 时允许，重置状态后重跑）。
- 新增 `SearchIndexRebuildBanner`（根 UI 覆盖层，`RouteActivity` 用它包住 `AppStartupGateContent`）：
  重建中显示「正在重建搜索索引 done/total 个会话」；失败显示错误 + 「重试」。任何页面可见，成功自动消失。
- `SnapshotBackupTab` 同步显示同一状态（运行中/失败/已重建 N 个会话），便于在备份页确认重建是否完成。
- 横幅只做可见化，不改变恢复链路；失败标记仍保留，下次冷启动自动重试。

**3. 对象 GC / 快照保留策略**

- **保留策略**：按**本设备**保留最新 N 份快照（默认 `DEFAULT_SNAPSHOT_RETENTION = 10`，0=不限），存
  `files/backup_index/snapshot-retention.json`（AtomicFile + `SnapshotRetentionConfig`）；
  `commit` 成功后自动 `pruneSnapshots(store, deviceId, keep)`——只删本设备旧快照的清单+完成标记，
  **不删对象**（对象留给 GC 统一回收，避免动到其它设备/快照共享的对象）。
- **手动 GC**：`garbageCollect(store)` 严格可达性——
  - 先用 `snapshotsStrict` 读**所有**已完成快照，任何清单损坏/校验失败立即放弃（宁可少删）；
  - 只允许**单设备仓库**：发现多个 `deviceId` 直接拒绝（跨设备回收需要协调协议，见 §0.1 第 5 条；
    该限制已被 §0.10 的多设备 GC 取代）；
  - 校验对象键必须是 `v3/objects/<sha[:2]>/<sha>` 且哈希合法，任何意外对象/目录都失败而不是删除；
  - 删除无任何快照引用的对象，返回 `ObjectGcResult(deleted, freedBytes, kept)`。
- **递归列举**：`BackupObjectStore` 新增 `listAll(prefix)`（默认逐层 `listChildren` 递归；S3 覆盖为
  `listAllObjects` 一次前缀列举），保证 GC 在 S3（无目录概念）与 SAF/WebDAV（有目录项）拿到一致的对象集合。
- **UI**：快照 tab 增加「保留策略」选择（不限/5/10/20 份，写回服务）、「回收未引用对象」确认框
  （说明单设备限制与失败即停）、回收结果 notice；删除快照对话框文案改为「保留策略只删清单，对象需手动回收」。
- **服务 API**：`prune(destination)` / `garbageCollect(destination)` 走 `DataOperationLock` 的
  `STORAGE_CLEANUP`；`snapshotRetention()` / `setSnapshotRetention(keep)`。

**验证**

- JVM：全量 `:app:testDebugUnitTest` **3015 项通过，0 失败/错误，2 跳过**。新增
  `IncrementalBackupRetentionTest` 6 项（保留策略只删本设备旧快照、0=不限、GC 只删未引用对象、
  多设备仓库拒绝、清单损坏 fail-stop 不误删、删最后一份快照后全量回收）、
  `SearchIndexRebuildSignalTest` 1 项、`IncrementalDatabaseBackupTest` 跨版本闸门用例
  （旧版本允许且需 merge 回调、新版本拒绝）。
- 真机（PJX110，debug 包，未清用户数据）：
  - `DatabaseObjectCrossSchemaMergeInstrumentedTest` **3/3**：旧快照合并进当前 schema——新增列取默认值、
    当前版本新增表被清空、已删除表/对象告警跳过、`integrity_check=ok`、`foreign_key_check` 干净；
    更新版本快照拒绝；基础库版本不匹配拒绝；
  - `DatabaseObjectCaptureMergeInstrumentedTest` **3/3**、`DatabaseObjectRealSchemaInstrumentedTest` **1/1**
    回归通过（共 7 项）。
- 真机 UX：预置 `fts_rebuild_pending` 后冷启动，首页横幅显示「正在重建搜索索引 29 / 269 个会话」，
  约 18 s 后标记清除、logcat 出现 `search index rebuilt after snapshot restore: 269 conversations`；
  快照 tab 显示「搜索索引已重建（269 个会话）」与保留策略行/按钮；点「保留 5 份」后
  `snapshot-retention.json` 落盘 `{"keep":5}`。
- `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 通过；`git diff --check` 通过。

**本轮仍明确未做**：`memory_store:<assistantId>` 等更细对象、append-only 流水游标、
真实 S3/WebDAV/SAF 远端网络上传与 UI 入口冒烟、GC 的跨设备协调与自动触发、
列改名/语义变化的自动迁移（只能告警）。

### 0.8 增量备份性能（速度）优化落地记录（2026-11-04，工作树未提交）

> 接 §0.7。用户要求「增量备份的性能（速度）优化」。先真机测全链路基线，再按阶段改；
> 以下只描述**工作树改动与实测数据**（AGENTS.md 提交授权另议）。
> 测量工具：`A/androidTest/.../repo/IncrementalPerformanceMeasurement.kt`（手工触发，不在常规
> instrumented 套件里；只读真实 debug 数据、临时文件跑完即删；tag `PerfMeasure`）。

**测量口径**：PJX110 / Android 15 / debug 包（未清数据）；269 会话 / 7,588 节点 /
`conversation:<uuid>` 290 个对象、payload 55,706,027 B；`VACUUM main INTO` 快照 → 捕获 →
plan → commit → 再捕获/plan（预热）→ assemble（含 merge 回调）→ 解压 + 校验 zip 内 DB →
FTS 全量重建。DB 此时约 208.6–240.4 MB（多轮测试/重建后含空闲页，文件会缓慢变大）。

| 阶段 | 优化前 | 优化后 | 变化 |
| --- | ---: | ---: | --- |
| `VACUUM main INTO` 一致性快照 | 654–938 ms | 763–1327 ms | 平台 IO 波动，非本轮目标 |
| 捕获（290 对象，55.7 MB payload） | 2,807 ms | 2,259 ms | −20% |
| 捕获（数据未变的第二次） | 2,623 ms | 2,182 ms | −17% |
| 哈希全部对象（SHA-256） | 155 ms | 169 ms | 持平（已并行） |
| `plan` 新仓库（291 上传 / 19.97 MB） | 3,313 ms | 886 ms | **−73%** |
| `commit` 新仓库（上传 291 对象） | 7,708 ms | 762 ms | **−90%** |
| `plan` 复用快照（0 上传） | 761 ms | 183 ms | **−76%** |
| merge（55.7 MB JSONL → 当前库副本） | 3,603 ms | 3,669 ms | 持平（实验见下） |
| `assemble` 总计（下载/解码/合并/zip） | 13,529 ms | 5,361–5,646 ms | **−58%～−60%** |
| 其中 zip 内 DB 条目 | 压缩（zip 86.0 MB） | STORED（zip 208.6–240.4 MB） | 体积换 CPU，见取舍 #2 |
| 解压 zip（恢复暂存侧） | — | 339–399 ms | 新增测量 |
| 校验解压出的 DB | — | 199–206 ms（`integrity_check=ok`、269 会话） | 新增测量 |
| FTS 全量重建（7,762 行） | 19,907 ms | 10,115–11,366 ms | **−43%～−49%** |

组合口径：首份快照「捕获 + plan + commit」约 **13.8 s → 3.9 s（约 3.5×）**；
恢复端「下载/解码/merge/zip」约 **13.5 s → 5.4–5.6 s（约 2.4×）**；恢复后搜索索引重建
**19.9 s → 10.1–11.4 s（约 1.8×）**。数据未变的日常增量快照上传量仍为 0 B（语义未变）。

**1. 捕获（`DatabaseObjectCapture`）**

- 新增 `DbObjectPayloadFastWriter`：直接 `StringBuilder` 拼 JSONL 行（表名/字符串转义一次，
  整数/浮点/blob 直写），不再为每个单元格构造 `JsonElement`、每行构造 `JsonObject/JsonArray`；
  语义与旧写入器一致（同一 reader 可读，边界值有 JVM 回归测试）。
- 会话对象按 id 列表分给固定线程池（`min(4, 核数)`）并行导出，每个 worker 独立只读连接；
  结果按原顺序组装，对象顺序/哈希仍确定。表对象小，仍顺序导出。
- 剩余瓶颈：从一致性快照读 55.7 MB + 写 55.7 MB JSONL，受 SQLite 游标逐列取值与磁盘 IO 限制；
  并行收益仅 −20%，未再强推。

**2. plan（`IncrementalBackupEngine.plan`）**

- DB 逻辑对象是本次捕获刚写出的不可变暂存文件：直接原地哈希/压缩，删掉原来每对象
  `raw-*` 的 55.7 MB 级重复拷贝。
- 哈希、`store.stat`（远端是 HEAD 网络往返，串行 290 次在真机是十几秒）、复用校验、
  DEFLATE 全部按对象并行（`PARALLEL_OBJECTS = 4`）；`store.ensureDir` 与目录解析保持顺序。
- 去重仍按内容寻址 key（`v3/objects/<sha[:2]>/<sha>`）判定，`uploads` map 的键改为内容 key，
  清单条目顺序保持输入顺序。

**3. commit（`IncrementalBackupEngine.commit`）**

- 上传改为并行（目录先顺序预建，避免 SAF 并发创建同父目录）；进度用 `AtomicLong`。
- **去掉「上传后逐对象整份回读 + 解码 + SHA」**：首备 20 MB 对象下载回来校验约占 7.7 s 里的
  6 s，远端仓库还要再下一遍。改为「本地编码文件解码 + SHA 校验（证明编码产物正确）+
  发布前对所有对象 `stat` 复检存在性/类型」；传输层（TLS / S3 签名 / SAF 写入成功）保证字节完整，
  恢复端仍逐对象 SHA-256 校验（设计稿 §8.1 R8），最终一致性不降级。失败/疑似部分写的对象
  仍走 `verifyObject` 的完整回读路径。

**4. assemble（`IncrementalBackupEngine.assemble`）**

- DB 对象与文件对象都「并行下载 + 解码」到唯一临时文件，再单线程写 zip（`ZipOutputStream`
  非并发安全）；本地 SAF 仓库逐对象解析目录的往返、远端仓库的网络往返都被并行化。
- **DB 条目在对象恢复 zip 里改为 STORED**：换库恢复会立即解压回来，省掉 208 MB 级 DEFLATE
  （以及恢复端 inflate）；zip 临时体积变大（见取舍 #2），`BackupSpaceCheck` 仍按未压缩总量预检。
- 保留 v2 manifest 语义（`databaseSha256`/`databaseBytes` 仍按原始 DB 计算），
  现有 `restoreFromBackupFile` 零改动。

**5. FTS 重建（`ConversationRepository.rebuildAllIndexes` + `MessageFtsManager`）**

- 整段包进**一个** Room 事务（嵌套 `withTransaction` 加入外层），把 269 次 WAL 提交合成一次；
- 重建路径 `indexConversation(conversation, incremental = false)` 跳过「先查旧行 + 反向差集」
  的整轮查询（索引刚被清空，旧行必然为空）。

**6. 并发契约**

`BackupObjectStore` 文档新增约定：引擎会对相互独立对象并行调用 `stat/put/get/ensureDir`，
实现必须可并发（SAF ContentResolver / S3 / WebDAV 满足；测试替身 `MemoryBackupStore` 改用
`synchronizedMap/List`）。

**做过但回退的实验（保留记录，避免重复踩）**

- **流式 merge 解析器**：手写 JSONL 线性扫描 + 直接绑定 `SQLiteStatement`，意图省掉
  `readDbObjectPayload` 的 `JsonElement` 树。实测 merge **3.6 s → 7.4 s（更慢）**：
  手写扫描在 55 MB / 7.6k 行上比 kotlinx 解析器慢约 2×（解析样本 293 ms vs 166 ms），
  且解析与插入交错后插入侧也变慢。**已整体回退**（保留旧物化解析路径）。
- **并行 FTS 抽取**：`buildFtsIndexRows` 在 4 线程并行 + 批量写入。实测 **10.1 s → 11.2 s
  （无收益，噪声级变差）**：Room/SQLite 单连接把 DAO 读取串行化，抽取不是唯一瓶颈；
  FTS5 `simple` 分词 + 插入仍是串行主体。已回退为「顺序抽取 + 单事务批量写」，代码更简单。

**取舍与风险**

1. **commit 不再逐对象回读**：备份成功的「远端字节 = 上传字节」由传输层保证，不再由备份端证明；
   恢复端对每个对象解码 + SHA-256，仍能发现损坏并拒绝恢复。若未来接入不自带完整性保证的
   传输层，可在 `commit` 恢复完整回读（或只对抽样对象回读）。
2. **对象恢复 zip 的 DB 条目 STORED**：本次 zip 86.0 MB → 208.6–240.4 MB（DB 原始大小；多轮测试后 DB 含空闲页会变大），
   换取 assemble 少一次 208 MB DEFLATE；恢复暂存解压 339 ms、校验 206 ms。空间预检
   （`BackupSpaceCheck`）按未压缩总量判定，常规设备可通过；若遇到 cache 紧张的 2.8 GB 级
   DB，可按 DB 大小阈值回退 DEFLATE 或改 level-1（设计稿 §5.8 原「统一 DEFLATE」的折中）。
3. **捕获写入器字节变化**：快速写入器与旧 kotlinx 写入器**语义一致但字节不同**，升级后
   所有对象 SHA 变化 → 首次快照会全量重传一次（本次约 20 MB 对象），之后恢复增量；
   恢复兼容性不变（读取端同一格式、同校验）。
4. **并行 object store 契约**：自研 `BackupObjectStore` 实现必须自行加锁；不支持并发的
   远端可在实现内串行化（引擎不强制并发）。
5. **FTS 重建仍是恢复后最大单点（~10 s）**：后续若继续优化，方向是减少写入文本量
   （按消息长度/类型分级）或评估分词器成本，而非并行 DAO 读取。

**验证**

- JVM：全量 `:app:testDebugUnitTest` **3017 项通过，0 失败/错误，2 跳过**（新增
  `DatabaseObjectProtocolTest` 快速写入器往返/宽度拒绝 2 项）；`git diff --check` 通过。
- 真机 instrumented（PJX110，debug 包）：`DatabaseObjectCaptureMergeInstrumentedTest` 3/3、
  `DatabaseObjectCrossSchemaMergeInstrumentedTest` 3/3、`DatabaseObjectRealSchemaInstrumentedTest` 1/1、
  `IncrementalStorageInstrumentedTest` 6/6（含 assemble zip 内容回归），共 **13/13**。
- 真机性能测量（`PerfMeasure`，两轮）：见上表；assemble 产物解压后 DB `integrity_check=ok`、
  `conversationentity` 269 行，说明 STORED 条目 + 并行下载/解码链路正确。
- `:app:assembleDebug` / `:app:assembleDebugAndroidTest` 通过。

**本轮仍明确未做**：merge 的进一步加速（物化解析已是当前最优）、捕获端更激进的格式/分批优化、
FTS 分词器层优化、真实 S3/WebDAV 网络下的并发收益实测（本地 SAF/目录仓库已验证并发正确性）。

### 0.9 备份任务前台服务 / 离页与后台继续落地记录（2026-11-05，本地提交未推送）

> 用户要求「备份离页/后台继续（前台服务）」；本轮只做这一项（快照导出完整 ZIP、pack 与多设备
> GC 明确稍后再说）。R10 的长任务中断风险由此关闭（进程被杀仍不续传，见下）。

- 新增 `data/sync/BackupRunCoordinator`（Koin 单例，任务跑在 `AppScope`）：
  - `await(kind, block)`：**调用方取消不取消任务**（页面 ViewModel 销毁后任务继续跑完）；
  - 单任务串行：已有一个长任务时第二个 `await` 立即抛 `IllegalStateException`（UI 同步禁用入口）；
  - 进度 `StateFlow` 供页面与通知共用；`cancel()` 供页面/通知栏取消；
  - 扫描计划放进程级（30 分钟 TTL，替换/过期时关闭并清理临时文件），离开备份页不再丢计划。
- 新增 `service/BackupForegroundService`（`foregroundServiceType="dataSync"`、
  `stopWithTask="false"`、通知 id 2004、`START_NOT_STICKY`）：只负责前台优先级与进度/结果通知
  （标题按任务类型、进度条、取消 action），任务状态仍由 coordinator 单点持有；超时/被回收只退前台。
- `RikkaHubApp` 新增 `backup` 通知渠道（IMPORTANCE_LOW、静默、无角标）；Manifest 与 Koin 注册。
- `BackupVM` 全部长任务改走 coordinator：增量扫描/提交/恢复/GC、WebDAV/S3 全量备份与恢复、
  本地 ZIP 恢复、导出；导出收敛为 `exportToUri(uri)`（生成 + 写入 SAF 同任务生命周期，临时文件由
  任务 `finally` 删除）；`onCleared` 不再关闭快照计划。
- UI：增量备份 tab 显示「任务进行中：可以离开此页或切到后台，通知栏会继续显示进度，也可以在那里
  取消」+「取消任务」；WebDAV/S3/本地导入导出按全局任务状态禁用重复入口，任务结束自动刷新列表。
- 验证：`:app:compileDebugKotlin` / `:app:compileDebugAndroidTestKotlin` ✅；全量
  `:app:testDebugUnitTest` **3048 项 0 失败/错误、2 跳过** ✅；真机 PJX110
  `BackupRunCoordinatorTest` **4/4** ✅（调用方取消后任务继续、单任务拒绝、进度、cancel）；真机手测：
  导出经 coordinator 成功、扫描中离开备份页时 FGS 通知 `id=2004` 为 `ONGOING|FOREGROUND_SERVICE`+
  「取消」action、回页后计划仍在、确认提交完成并刷新列表 ✅。
- **仍未做**：进程被杀后的断点续传（无 WorkManager，明确不承诺）；真实 S3/WebDAV 长任务手测；
  快照导出完整 ZIP（后续单独立项）；pack 与多设备 GC 已在 §0.10 完成。

### 0.10 pack 打包与多设备 GC 落地记录（2026-11-05，工作树未提交）

> 接 §0.9。用户要求「pack 与多设备 GC」；本轮只做这两项（快照导出完整 ZIP 仍未做）。
> 以下只描述**工作树实现与真机验证**（AGENTS.md 提交授权另议）。

**1. pack 文件（快照格式 v3）**

- **动机**：小文件逐个对象上传时每个文件都要 stat + put（SAF/S3/WebDAV 都是请求级开销），
  一次备份几百个小文件时请求数主导耗时。pack 把「一次备份里新增/变更的小文件」打成 zip，
  请求数从 N 降到 1，而 pack 本身内容寻址 → 内容不变可整包复用。
- **格式**：`v3/packs/<sha256>.zip`，文件名 = 整个 zip 字节的 SHA-256；条目名 = 对象内容的 SHA-256
  （与对象同一个内容寻址键，清单里的 `sha256` 直接对应条目名），条目时间固定为 1980-01-01
  （`PACK_FIXED_TIME`）保证「同样输入 → 同样 pack 字节」；只有 `size < PACK_ENTRY_LIMIT`(256 KB)
  的对象进 pack，单 pack 原始总量 ≤ `PACK_TARGET_BYTES`(32 MB)，按 sha 排序分批；
  同样内容的 pack 在一批里会被去重（同一 zip 字节只写一次）。
- **清单 v3**：`SNAPSHOT_FORMAT_V3`；`SnapshotObjectRef` 新增可选 `val pack: String?`（**裸 pack 内容 SHA**，
  不是路径——布局只由引擎计算，清单不耦合路径），`storageKey`/`packKey` 由 sha 推导。
  校验：v3+ 才允许 `pack`、`pack` 必须合法 64 hex、声明在 pack 里的对象 `size` 必须小于阈值
  （防止把大对象塞进 pack 破坏「大对象独立」的语义）；v1/v2 清单行为不变。
- **复用策略**：plan 只信任**本设备最新快照**的引用——
  - 信任引用存在（`stat` 非空且 size 相同）→ 直接复用原引用（保留原 pack 指针，不再读内容）；
  - 不受信的独立对象若已存在 → 完整回读 + 解码 + SHA-256 校验后才复用（防「上次上传失败留下半截对象」
    被新快照引用）；
  - 否则上传。**只有 upload 候选进入新 pack**；未变化的引用继续指向旧 pack，旧 pack 只要仍被引用就整包保留
    （pack 内单条无法单独回收 → 空间放大，见 §8.1 R5）。
- **恢复**：按下发引用分组，同一个 pack 只下载一次并整体校验 SHA-256，再用 `ZipFile` 按条目名抽取、
  逐条核对 sha/size；独立对象仍走 `v3/objects` 解码；v2 快照（无 pack 字段）完全兼容。
- 代码：新增 `A/data/sync/repo/SnapshotPacking.kt`（`write`/`verify`/`extractAll`/`downloadLimit`）；
  `AppFilesPacking.addFileToZip` 增加 `entryTime` 参数供确定性打包；`IncrementalBackupEngine.plan/commit/assemble`
  接入 pack；`BackupSnapshotManifest` 增加 v3 与 pack 校验。

**2. 多设备仓库 GC（安全协议）**

- 旧实现发现多个 `deviceId` 直接拒绝。现在 `garbageCollect` 用 `snapshotsStrict` 读**所有设备**的已提交快照
  求可达集（任一清单损坏/校验失败即放弃），引用集同时覆盖 `v3/objects/<aa>/<sha>` 与 `v3/packs/<sha>.zip`
  （pack 被引用则整包保留）。§0.1 第 5 条要求的「跨设备协调协议」落为下面四条：
  1. **mtime 宽限期**：对象扫描新增 `lastModified`（SAF `COLUMN_LAST_MODIFIED`、S3 `Last-Modified`、
     WebDAV `getlastmodified`）；只删「时间已知且早于 `GC_GRACE`(默认 10 分钟)」的候选，
     另一台设备刚上传、清单还没提交的对象不会被删；拿不到时间的 provider 一律保留并计入 `skippedRecent`。
  2. **仓库租约**：`v3/gc.json` 存 `GcLease(deviceId, repoId, startedAt)`，TTL 30 分钟。回收开始先读：
     别的设备的**新鲜**租约 → 抛「另一台设备正在回收未引用对象，请稍后重试」。`commit` 前也做同样检查，
     避免与并发回收抢「刚上传、尚未被清单引用」的对象。租约是可变文件，走新增的
     `BackupObjectStore.putOverwrite`（SAF 用 `"wt"` 截断重写；`put` 仍保持内容寻址不可变语义）；
     `finally` 在 `NonCancellable` 中释放，且**只删自己的租约**（本机回收超时、别家接手后不能把别家租约删掉）；
     崩溃遗留的旧租约由下一次 `putOverwrite` 覆盖，不会永久锁死仓库。
  3. **删除前静默复核**：删之前再严格列举一次快照 id 集合，与开始时不一致即中止（fail-stop：
     说明别的设备正在提交）。
  4. **兜底**：即使极端窗口下误删了别的设备刚上传的对象，它提交前的 `stat` 存在性复检会失败
     → 只会提交失败，不会产出坏快照（R18 同一思路）。
- `ObjectGcResult` 增加 `deletedPacks` / `skippedRecent`；UI 提示补「、N 个 pack」「保留 N 个最近修改/时间未知条目」，
  快照卡片显示「N 个 pack」；GC 确认框文案改为多设备可达性 + 宽限期说明。

**3. 真机暴露的两个性能问题（已修）**

- `SafBackupObjectStore` 原先只有默认 `listAll`：每个对象目录都用 `resolve` 重走整条路径（4 次 provider 查询
  + 1 次列出），真机仓库 111 个对象文件 / 91 个分片目录时整次 GC 要数分钟（初看像卡死）。
  改为覆盖 `listAll`：按目录 id 逐层列举（1 次查询/目录），同一层兄弟目录 8 路并行；
  **不做递归并行**（每层各自持许可会互相等待）。
- 删除原先逐条串行（每条 delete 又要 `resolve` 一次路径），上百孤儿对象要一分多钟；改为按对象并行删除。
- 结果：真机同一仓库 GC 从分钟级降到数秒级（111 → 22 个对象，二次 GC 0 删除 < 8 s）。

**验证**

- JVM：全量 `:app:testDebugUnitTest` **3064 项 0 失败/错误、2 跳过** ✅。新增
  `SnapshotPackingTest` 8 项（确定性字节、verify 拒绝篡改/缺条目、extractAll 校验、引擎打包 + 恢复、
  大对象留独立对象、未变备份 `uploadBytes == 0` 复用、变更对象进新包、v2 清单拒绝 pack 指针/非法 pack 哈希）；
  `IncrementalBackupRetentionTest` +2 项（崩溃遗留同设备租约可覆盖回收、过期别家租约可接手且收尾只删自己的）；
  `SafBackupObjectStoreTest` +3 项（嵌套 `listAll` 只回文件、`putOverwrite` 覆盖语义、覆盖创建缺失文件）。
- 真机（PJX110 / Android 15，debug 包，SAF 仓库 `/sdcard/Rikkahub_plus/debug/test`）：
  - 混合仓库：旧 v2 快照与新 v3 快照共存，新快照卡片显示「22 个对象 · 36.5 KB · 1 个 pack」；
    从设备 pull 回 pack 与清单核对：pack 文件 SHA-256 = 文件名、zip 内条目名 = settings 的清单 sha、
    解压内容 SHA 一致 ✅。
  - 从 v3 快照恢复：下载 pack → 整包校验 → 按条目抽取校验 → 组装 → 换库重启成功 ✅。
  - GC：111 → 22 个对象（其余均不可达），被引用的 `1 个 pack` 保留，租约 `v3/gc.json` 回收后消失；
    再次 GC 提示「回收 0 个未引用对象，释放 0B」且 < 8 s ✅。
  - instrumented：包 `data.sync.repo` **15/15** ✅，新增
    `IncrementalStorageInstrumentedTest.nativeSafGarbageCollectionReclaimsUnreferencedContentAndReleasesLease`
    （真实 DocumentsProvider：编辑产生新 pack、删快照后回收旧 pack、租约释放、剩余快照仍能组装还原）。
- `:app:compileDebugKotlin` / `:app:compileDebugAndroidTestKotlin` ✅；`git diff --check` 通过。

**仍未做**：pack 之间并行上传（现在仍按对象并行）；真实 S3/WebDAV 上的 pack + GC 手测；
跨设备 GC 的自动触发（仍手动）；快照导出完整 ZIP（已在 §0.11 完成）；pack 内单条回收/拆包（已在 §0.12 完成）。

### 0.11 快照导出完整 ZIP 落地记录（2026-11-05，工作树未提交）

> 接 §0.10。用户要求「快照导出完整 ZIP」（此前 §1.3/§8.2 第 6 条一直列为待定项）。
> 本轮只加导出，不改变仓库协议与恢复链路。

- **做法**：复用恢复用的 `IncrementalBackupEngine.assemble`——它本来就把快照重组成既有 v2 ZIP
  （settings + FILES + WORKSPACE + `rikka_hub.db` + 末条目 `manifest.json`）。导出只是在拼好后把 ZIP
  写进用户选的 SAF 文档，不新增第二套打包逻辑。
- `IncrementalBackupService.exportSnapshot(destination, snapshot, target, onProgress)`：
  `DataOperationLock(BACKUP)` + 服务互斥 → `assemble`（v2+ 快照用「当前库 VACUUM 副本 + 对象合并」得到
  `rikka_hub.db`，与恢复同一路径）→ 流式拷到 `ContentResolver.openOutputStream(target)` →
  `finally`（`NonCancellable`）删任务目录；失败不会留下半份临时 ZIP，目标文件由系统文档提供方管理。
  merge 逻辑抽成 `mergeSnapshotDatabase(...)`，restore / export 共用。
- `BackupRunCoordinator` 新增 `SNAPSHOT_EXPORT`（通知标题「正在导出快照」/完成「快照导出完成」），
  导出与其他长任务一样可离页、可通知栏取消、串行互斥。
- UI：快照卡片新增「导出 ZIP」；走 `ActivityResultContracts.CreateDocument("application/zip")`，
  默认文件名 `snapshot_<本地时间>.zip`（与卡片显示时间一致）；完成 toast「快照已导出为完整 ZIP」。
- **语义与边界**：导出的 ZIP 与「恢复时组装出来的 ZIP」完全相同，因此可走 App 自己的
  「完整 ZIP 导入」入口恢复；`rikka_hub.db` 是**当前 schema + 快照数据**（跨 schema 快照导出时
  旧表/旧列按合并规则处理，与恢复一致），所以**旧版 App 只有在数据库版本兼容时才能导入**，
  UI 不承诺旧版兼容。导出是只读操作（不写仓库），可与恢复并发？——受同一服务互斥与
  `DataOperationLock(BACKUP)` 约束，不与写入类任务并行。
- 验证：`:app:compileDebugKotlin` ✅；真机（PJX110/SAF 仓库）对含 pack 的 v3 快照导出成功，
  文件 114,971 B，ZIP 内 `settings.json`(33,099) / `rikka_hub.db`(103,424) / `manifest.json`(419)；
  回读校验：`manifest.json` 的 `settingsSha256` = 实际 settings 文件 SHA-256 = 快照清单里的
  settings 对象 SHA（`aa03edd…`），`databaseSha256`/`databaseBytes` 与 ZIP 内 DB 一致；随后删除测试文件 ✅。
- **未做**：批量导出多个快照；导出后自动校验可导入性（只靠既有 ZIP 校验/恢复路径）；旧版 App 实测导入；
  导出进度 UI（仅通知栏进度）。

### 0.12 pack 内单条回收（拆包）落地记录（2026-11-05，工作树未提交）

> 接 §0.10/§0.11。用户要求「pack 内单条回收（拆包）」——解决 §8.1 R5 记录的放大：
> pack 只要还有任意条目被引用就整包保留，删旧快照后 pack 里的不可达条目永远占着空间。
> 本轮把「拆包」并入现有手动 GC（不新增按钮/任务类型），协议上唯一的突破是**在租约内改写已提交快照的清单**。

- **触发条件**：GC 先按全部已提交快照算出每个 pack 里仍被引用的条目（sha→大小）；对「被引用条目数 < pack 内条目数」
  的 pack 才拆包。删除阶段原有的 mtime 宽限期同样用于拆包：pack 本身必须已「静默」（早于 10 分钟）才动它，
  刚提交/刚上传的 pack 先不拆（否则会为一份新 pack 白改快照）。
- **流程**（都在 `v3/gc.json` 租约内）：
  1. 下载旧 pack（上限 = 单 pack 原始量上限 32 MB + 8 MB 开销），整包 SHA-256 校验；
  2. `SnapshotPacking.entrySizes` 读条目表，确认每个被引用条目都在且大小一致（缺条目 → fail-stop，仓库异常）；
  3. 只把仍被引用的条目抽到临时文件（`SnapshotPacking.extract`），逐条复核原始 SHA；
  4. `SnapshotPacking.write` 重新打成内容寻址 pack（条目名 = 对象 SHA、固定条目时间 → 确定性字节）；
     同一批内容若已存在（上次中断已上传）则直接复用，不重复上传；
  5. 对**所有**引用旧 pack 的快照改写清单：`files[]`/`dbObjects[]` 的 `pack` 指针旧→新，
     其余字段（逻辑路径、sha256、size、createdAt、deviceId…）原样保留，`validateSnapshot` 复核；
  6. 改写完成后再做一次严格列举，校验「快照 id 集合不变、独立对象集合不变、pack 指针等于预期替换映射」，
     任一不符即中止（fail-stop，别的设备正在提交）；
  7. 旧 pack 回到普通删除阶段，仍受 mtime 宽限期保护，被并行删除。
- **崩溃安全（清单是可变文件——本协议唯一的例外面）**：发布顺序为
  「① 旧清单备份到 `v3/rewrite/<id>.json.gz` → ② 覆盖清单 → ③ 覆盖完成标记 → ④ 回读校验 → ⑤ 删备份」。
  `snapshotsStrict` 读取前先做 repair：
  - 完成标记匹配当前清单 → 改写已完成/未开始，删备份；
  - 完成标记匹配旧备份、但当前清单对不上 → 退回到 ②–③ 之间的中断，覆盖写回旧清单（旧标记仍然匹配），删备份；
  - 两者都不匹配 → 直接报错（仓库需要人工检查），不会继续删任何东西。
  因此读侧只会看到「旧清单+旧标记」或「新清单+新标记」，不会把别的快照误判为损坏。
- **并发语义**：拆包写清单需要租约，`commit` 检查别家新鲜租约、`pruneSnapshots` 现在也检查（避免和别家 GC 的
  改写/回滚互相踩）；另一台设备若在拆包完成后用旧列表恢复，`assemble` 的「快照与当前发布一致」检查会失败并提示刷新，
  不会用坏数据。极端窗口误删旧 pack 也被同一检查拦住。
- **结果/UI**：`ObjectGcResult.repackedPacks`；`freedBytes` = 删除对象/旧 pack 字节 − 本次新上传的 pack 字节（净释放，下限 0）。
  GC 提示补「、重构 N 个 pack」，确认框补「打包文件里只剩部分条目仍被引用时，会把它拆包重写成更小的 pack
  （会改写受影响快照的清单，需要写入权限）」。
- **代码**：`SnapshotPacking` 新增 `entrySizes`/`extract`；引擎新增 `reachability`、`listPacks`、
  `repackPacks`、`rewriteSnapshotPacks`、`repairSnapshotRewrites`、`readCompletion`（拆分自 `readSnapshot`），
  `snapshotsStrict` 与 `commit` 会先 repair；`BackupSnapshotManifest.kt` 新增 `REWRITE_DIR`/`snapshotRewriteKey`。
- **验证**：JVM 全量 `:app:testDebugUnitTest` **3069 项 0 失败/错误、2 跳过** ✅；`IncrementalBackupRetentionTest`
  16 项通过（新增 5 项：部分引用 pack 拆包并恢复、全引用不拆、宽限期不拆、清单中途写坏可回滚、
  改写完成后的遗留备份会清理）。真机 instrumented（隔离包 `-PbackupIsolatedTests=true`，不覆盖调试包）：
  `IncrementalStorageInstrumentedTest.nativeSafGarbageCollectionRepacksPartiallyReferencedPacks` 在真实
  DocumentsProvider/`SafBackupObjectStore` 上通过（覆盖写清单/标记走 `putOverwrite`），该包 16 项中仅
  既有 `IncrementalPerformanceMeasurement.measure` 因隔离 runner 无 `RikkaHubApp` 失败（与本次无关）；
  真机默认包 GC 冒烟：无拆分需求时 0 删除、`v3/rewrite` 无残留、租约释放。
- **未做**：拆包进度提示（仍在通知栏显示 GC 进度）；S3/WebDAV 上的拆包手测；拆包失败的自动重试；
  单快照内多个大 pack 的并行拆包（已在 §0.14 实现：准备阶段并行、上传阶段串行去重）；
  「pack 内条目很多但只删少数」时是否值得拆的成本模型
  （目前只要可拆就拆，收益 = 旧包大小 − 新包大小）。

### 0.13 备份页结构：顶栏 tab → 二级列表 + 三级详情页（2026-11-05，本地提交）

**背景**：备份页从 P1 起用顶栏 `SecondaryScrollableTabRow` + `HorizontalPager` 承载
「本地 / WebDAV备份 / S3备份 / 提醒 / 增量备份」五节。问题：
① 横向 tab 与大标题折叠（`exitUntilCollapsedScrollBehavior`）抢手势，横滑翻页和竖向滚动容易误触；
② 五节共用一个 `BackupVM` 与同一层返回栈，深链/恢复状态无法区分「当前在哪一节」；
③ 与设置页其他二级页（CardGroup 列表 → 独立详情页）交互不一致。

**改动**：
- `BackupPage` 只保留二级列表：大标题 + 三个 CardGroup（备份方式 / 自动化 / 实验功能），
  每行是图标 + 标题 + 一句说明，点击进入对应三级详情页；本页不再创建 `BackupVM`。
- 新增 5 个三级页 `BackupImportExportPage` / `BackupWebDavPage` / `BackupS3Page` /
  `BackupReminderPage` / `BackupSnapshotPage`（`ui/pages/backup/BackupDetailPages.kt`），
  共用私有 `BackupDetailScaffold`（`LargeFlexibleTopAppBar` + `BackButton` + 让出 contentPadding），
  内容直接复用原 tab 组件（`ImportExportTab` 等），tab 组件本身不改滚动/交互。
- `RouteActivity` 增加 `Screen.BackupImportExport/BackupWebDav/BackupS3/BackupReminder/BackupSnapshot`
  五个 `@Serializable data object` 路由与 entry；`Screen.Backup` 仍是入口。
- 去重：原 tab 内容里的自绘标题与页面顶栏重复，删掉 `SnapshotBackupTab` 的
  "增量备份（实验）"标题行，WebDAV/S3 的状态卡标题从「WebDAV备份/S3备份」改为「备份状态」。
- 新增字符串（values / values-zh / values-zh-rTW）：`backup_page_section_methods`
  （备份方式）、`backup_page_section_automation`、`backup_page_section_experimental`、
  `backup_page_import_export_title/desc`、`backup_page_webdav_desc`、`backup_page_s3_desc`、
  `backup_page_reminder_desc`、`backup_page_snapshot_title/desc`、`backup_page_status`。

**代价与取舍**：
- 导航库按 entry 分配 ViewModelStore，因此每个三级页各自 `koinViewModel()` 一个 `BackupVM`；
  长任务状态来自进程级 `BackupRunCoordinator`（离开页面继续跑），设置来自共享 `SettingsStore`，
  所以进度/配置不丢；代价是每进一个三级页 `BackupVM.init` 都会重新拉一次 WebDAV/S3 文件列表
  （与旧版打开备份页一次等价，反复进出会多几次只读列举）。
- 三级页返回即销毁对应 VM，恢复点列表/扫描计划不跨页保留（扫描计划在 coordinator，仍保留）。

**验证**：编译 ✅；全量 `:app:testDebugUnitTest` **3081 项 0 失败/错误、2 跳过** ✅；
`python scripts/audit_ime_trap.py` A/A' = 0 ✅；真机 PJX110 手测：设置 → 数据备份进入列表页，
五个条目逐页进入/返回（各自独立顶栏与滚动位置），增量备份页读回 `test` 文件夹关联与
`2026-11-05 20:43:46` 快照（22 个对象 · 36.5 KB · 1 个 pack），WebDAV/S3 页状态卡不再与标题重复。

### 0.14 单次回收内并行拆包（2026-11-06，工作树未提交）

**背景**：§0.12 的拆包按 `referencedPacks` 串行处理；一份快照里新对象超过 32 MB 就会产生多个 pack，
删旧快照后可能同时有多个 pack 变成「部分不可达」。串行时每个 pack 都要完整下载 + 整包 SHA-256 +
解压 + 重压缩，多个 pack 时回收耗时线性叠加。

**改动**（`IncrementalBackupEngine`）：
- 拆包拆成「准备」与「提交」两阶段：
  1. **准备（并行）**：`repackOnePack` 做下载 → 整包 SHA 校验 → 条目表核对 → 抽出仍被引用条目并逐条校验 SHA
     → `SnapshotPacking.write` 重新打成确定性新 pack；每个 pack 用独立临时文件名（`repack-<sha>.zip`、
     `repack-entry-<objsha>`、`SnapshotPacking.write` 内部 `createTempFile`），互不共享可变状态。
     并行度用 `REPACK_PARALLEL = 3`（比对象级 `PARALLEL_OBJECTS = 4` 保守一档：拆包同时有下载 + 解压 +
     重压缩，低端机上并发压缩流太多会抢 IO/内存）；`parallelMap` 增加可选 `parallelism` 参数。
  2. **提交（串行）**：准备阶段全部成功后才上传新 pack。按新 pack 的 SHA 去重后逐个 `stat` → 缺失才
     `put`，避免「同一批对象在不同设备各自打进不同 pack」时两个任务同时创建同一个内容寻址文档
     （也避免重复上传）。任一准备失败则不进入上传阶段：此次只留下任务目录里的临时 zip，不产生半套新 pack。
- 清单改写仍在所有替换关系就绪后进行（`rewriteSnapshotPacks` 按快照遍历）：**一份快照可能同时引用多个
  被拆的 pack，只改写一次**，避免同一清单被覆盖两遍、减少崩溃窗口。
- 语义变化：仅耗时/并发路径；`ObjectGcResult` 字段、mtime 宽限期、租约、复核逻辑、崩溃回滚（§0.12）不变。

**验证**：编译 ✅；全量 `:app:testDebugUnitTest` **3082 项 0 失败/错误、2 跳过** ✅；
`IncrementalBackupRetentionTest` **17 项**通过（新增「一份快照引用两个部分可达 pack → 一次 GC 并行拆两个、
只改写一份清单、恢复结果正确」）；真机 instrumented 隔离包（`-PbackupIsolatedTests=true`，不覆盖调试包）
`nativeSafGarbageCollectionRepacksMultiplePacksOfOneSnapshot` 在真实 `DocumentsProvider`/`SafBackupObjectStore`
上通过（并行下载/上传/覆盖写清单），该包 16 项中仍只有既有 `IncrementalPerformanceMeasurement.measure`
因隔离 runner 无 `RikkaHubApp` 失败（与本次无关）。

**未做**：拆包进度提示（仍在通知栏显示 GC 总进度）；快照清单的并行改写（改写是几个 KB 的元数据写，
通常只有 1–2 份，未单独并行）；S3/WebDAV 上的并行拆包手测；按 pack 大小/网络类型动态调并行度。

## 1. 问题与目标

### 1.1 现状与痛点（实测/代码事实）

| 事实 | 数值/位置 | 证据 |
|---|---|---|
| 每次备份都是完整 zip | settings + 可选 DB/FILES/WORKSPACE_FILES + 末条目 `manifest.json` | `A/data/sync/BackupArchiveEngine.kt:62-158` |
| 备份文件名 | `backup_yyyyMMdd_HHmmss.zip`（每次新名字，天然不覆盖） | 同上 `:66-68` |
| 远端保留 | **无**：WebDAV/S3 只增不减，删旧只有 UI 手动 | `A/data/sync/S3Sync.kt:77-126`、`A/data/sync/webdav/WebDavSync.kt:80-130` |
| 远端可列举字段 | 只有名字/大小/修改时间；无逐文件清单 | `A/data/sync/S3Sync.kt:84-93`、`A/data/sync/webdav/WebDavSync.kt:88-97` |
| 逐文件哈希 | **无**（manifest 只有 section 汇总 + settings/主库双 SHA-256） | `A/data/sync/BackupManifest.kt:16-37` |
| 上传断点 | 无（S3 分片 uploadId 只在内存；WebDAV 单 PUT） | `A/data/sync/s3/S3Client.kt:131-206` |
| DB 体量 | 115 MB（`_measure/m.db`，page 1 KiB），其中 98.9% 是消息与 FTS | 本稿 §2.1 实测 |
| DB 增量现状 | `VACUUM INTO` 每次生成整份新快照 → 每次都全量上传 | `A/data/sync/BackupArchiveEngine.kt:641-677` |
| 工作区最坏量级 | 2.8 GB/次（用户实测，不可压缩数据 DEFLATE 1.000x） | `D/completed/design-backup-workspace-files.md:164-171` |
| 恢复前安全备份 | 本地 `safety_backup/` 滚动 3 份（**仅本地**） | `A/data/sync/BackupArchiveEngine.kt:482-497` |

### 1.2 目标

1. **上传量随「新增内容」增长**而不是随「总数据量」增长：首次全量，之后只传新增/变更文件
   （以及 P2 后只传变更的会话对象）。
2. **每份快照可独立恢复**：不依赖上一份快照是否还在，删除旧快照不会让新快照失效。
3. **恢复安全性不降级**：保留现有「校验通过才执行破坏性操作」、恢复前 safety_backup、
   staged 换库、fail-closed 回滚；新增路径不得绕过这些机制。
4. **与旧全量 zip 长期共存**：旧版 App 看不到新结构但不被破坏；新版 App 同时能恢复旧 zip；
   旧版 App 做的 zip 仍走原路径。
5. **不动 Room 写路径**（P1 与 P2 都是）：备份只读查询；恢复在启动窗口内换库/合并。
6. **本地目录增量**（2026-11-04 用户确认）：用户选择专用备份文件夹，首次全量写入，之后复用对象；
   整个仓库目录可搬迁，新设备重新选择目录即可列举和恢复，无需原设备索引。详见 §5.14。

### 1.3 非目标（本稿明确不解决）

- 自动/定时备份（复用 `service/ScheduledTaskAlarms.kt` + WorkManager 是独立立项，见 §8 Q3）。
- 备份加密（上游 #2254，独立立项，需 AES-GCM + Keystore，加在快照层之上）。
- 双向同步 / 跨设备合并（本方案是单向备份，多设备只共享内容寻址对象，不做合并语义）。
- 不把原有“导出完整 ZIP”改成链式增量文件，也不持续改写单个 ZIP 容器；
  **本地目录仓库增量已纳入 P1**。SAF 支持目录树，之前据“单文件语义”排除本地的理由撤回。
- 块级 CDC/restic/borg 全套（见 §4 方案 D）。

### 1.4 收益预期（粗估，需真机实测校验）

> 假设：DB 压缩率 2.27x（本仓实测）、媒体/工作区近不可压缩（1.0x）；「增量」只相当于改动量，
> 且**首次仍是全量**。以下是量级判断，不是承诺指标。

| 用户画像 | 全量 zip 上传量级 | P1（文件区增量 + DB 整库对象） | P2（再加 DB 对象增量） |
|---|---:|---:|---:|
| 聊天型（DB 115 MB、文件区 300 MB，单次改动 ~10 MB 文件 + 少量会话） | ≈ 350 MB | ≈ 50 MB（DB） + 10 MB ≈ **60 MB**（约 6x↓） | ≈ 10 MB 文件 + 1～2 MB 会话 ≈ **12 MB**（约 29x↓） |
| 工作区型（DB 115 MB、工作区 2.8 GB，单次改动 ~50 MB） | ≈ 2.9 GB | ≈ 50 MB + 50 MB ≈ **100 MB**（约 29x↓） | ≈ 50 MB + 60 MB ≈ **110 MB**（DB 不再是大头） |

关键点：**上传量由「本次改动」决定，而不是由「历史总量」决定**；工作区型用户收益最大，
聊天型用户的 DB 部分要等 P2 才完全消掉（P1 仍每次传整库对象）。收益/成本是否值得，
应在 P1 上线后用真实数据（planner 日志里的「计划上传 / 复用」）校准。

## 2. 现状核实（证据）

### 2.1 备份数据构成与量级（决定增量切哪里）

真机快照 `_measure/m.db`（115,393,536 B，`user_version=38`，250 会话 / 7,428 消息节点 / 7,606 FTS 行）
的 dbstat/列长实测：

| 组成 | 页/字节足迹 | 占比 | 可变性 | 增量策略 |
|---|---:|---:|---|---|
| `message_node.messages` | 49.59 MB（列长实测） | 45.1%（页迹） | 追加为主，编辑/删除会原地改写 | P2：按会话对象增量 |
| `message_fts_data`（FTS5 倒排） | 37.55 MB（列长实测） | 34.1% | **派生数据**，可重建 | **不进备份**，恢复后重建 |
| `message_fts_content`（FTS5 原文副本） | 20.52 MB（列长实测） | 19.7% | 同上 | **不进备份** |
| 其余 22 张表（会话行/记忆/收藏/工作区/…） | ≈0.75 MB | **0.7%** | 低频 | 按对象/整表，P2 |
| 附件二进制 | **不在 DB**：part 只存 `file://` URL | — | — | 走 FILES 文件区 |

结论：
- **DB 的 98.9% 是「消息文本 + 可重建 FTS」**；FTS 不备份可省 53.8%。
- DB 增量是**纯文本**问题，体积远小于工作区/附件。
- 文件区才是上传量支配项（最坏 2.8 GB），且 6/7 个 FILES 目录是 UUID/时间戳命名的**不可变文件**
  （见 §2.2），文件级哈希足够，不需要块级去重。

settings 真机样本 `_measure/settings.pb`：**1.45 MB / 75 个 key**，其中 `prompt_presets` 76.2%、
`mcp_servers` 10.8%、`providers` 4.3%、`lorebooks` 4.2%、`assistants` 3.6%。
备份产物是整份 `settings.json`（`A/data/sync/BackupArchiveEngine.kt:95-103`），一次改任意 key 整段都变 →
P1 用整对象 + SHA-256 门控（未变则不上传），P2 再考虑按 key 存（收益有限）。

### 2.2 文件区清单、形态与可变性

`BACKED_UP_FILE_FOLDERS`（`A/data/sync/BackupItem.kt:42-50`）：
`upload / images / avatars / backgrounds / fonts / skills / media_creation`；
其中 `skills / images / media_creation` 递归（`:62-66`），其余平铺。
`WORKSPACE_FILES` = `workspaces/<root>/files/**` 递归，排除 rootfs（≥30 MB/工作区）与 tmp
（`A/data/sync/WorkspaceBackupHelper.kt:71-117`、`D/completed/design-backup-workspace-files.md:22-46`）。

| 目录 | 写入方/命名 | 内容可变？ | 增量判定 |
|---|---|---|---|
| `upload` | `UUID.ext`（`A/data/files/FilesManager.kt:333`） | **不可变** | 路径+size 即可，hash 兜底 |
| `avatars` / `backgrounds` | UUID（`FilesManager.kt:219,299`） | **不可变** | 同上 |
| `images` | `<ts>_<model>_<i>.png` | **不可变** | 同上（含旧版子目录） |
| `media_creation` | `<sessionUuid>/<recordUuid>/<uuid>.ext` | **不可变**（下载中 `.part` 除外） | 同上 |
| `fonts` | `chat_font.<ts>.<ext>` | 换字体=新名+删旧 | 路径+size |
| `skills` | 技能名目录，保存=staging + 整目录 rename | **可变** | (path,size,mtime)→sha256 |
| `workspaces/*/files/**` | 用户/AI 任意命名、可就地改写 | **可变** | (path,size,mtime)→sha256 |

已有可复用范式：`RemoteFileStore` 的 `(length,lastModified)→sha256` 缓存
（`A/data/files/RemoteFileStore.kt:163-175`）、会话导出 `attachments/<sha256前16>` 内容寻址
（`A/data/export/ExportAttachment.kt:47-56`）、`BackupManifest.sha256Hex`。

**注意**：备份目录内会混入瞬态文件（`atomic-*.tmp`、`skills/.<name>.staging.*.tmp`、`media_creation/**/*.part`、
`fonts/chat_font_import.tmp`），增量清单必须显式跳过，否则每次备份都「有变化」。

### 2.3 现有实现与传输缺口（增量必须骑上去的部分）

| 环节 | 现状 | 证据 |
|---|---|---|
| 打包 | 逐文件写 zip entry；大文件采样判 STORED | `A/data/sync/AppFilesPacking.kt:97-133`、`A/data/sync/BackupZipStrategy.kt:18-53` |
| 工作区打包 | 直接 `putNextEntry`，**没接 STORED** | `A/data/sync/WorkspaceBackupHelper.kt:104-115` |
| DB 快照 | `VACUUM main INTO` 一致性快照；失败回退热拷三件套 | `A/data/sync/BackupArchiveEngine.kt:637-677` |
| 校验 | manifest v2：settings/主库双 SHA-256 + section 条目数；纯函数 `verifyBackupManifest` | `A/data/sync/BackupManifest.kt:87-133` |
| 恢复 | 先读毕校验 → staging DB → safety_backup → settings 写入 → PendingRestoreStore 启动期换库 → 强制重启 | `A/data/sync/BackupArchiveEngine.kt:194-474`、`A/data/sync/PendingRestoreStore.kt:26-176` |
| 上传 | S3 单 PUT/8 MB 分片（片级重试 1 次）；WebDAV 单 PUT | `A/data/sync/s3/S3Client.kt:82-318`、`A/data/sync/webdav/WebDavClient.kt:90-121` |
| S3 列举 | 解析了 ETag/Size/LastModified，客户端支持 continuationToken，但 `S3Sync` **只取首页 1000 条** | `A/data/sync/s3/S3Client.kt:489-527`、`A/data/sync/S3Sync.kt:77-95` |
| WebDAV 列举 | `PROPFIND Depth:1`，只请求 5 个属性，无 `getetag`、无分页、不递归 | `A/data/sync/webdav/WebDavClient.kt:281-345` |
| WebDAV 多级路径 | `buildUrl` 把 `config.path` 当**一个**路径段编码：`path` 或 `path` 参数里的 `/` → `%2F` | `A/data/sync/webdav/WebDavClient.kt:47-59` |
| 条件写/Range/MOVE | 全缺（S3/WebDAV 客户端均无 If-Match、Range GET、MOVE/COPY） | 同两文件方法清单 |

**ETag 不能当内容哈希**：S3 分片上传的 ETag = `md5-of-part-md5-N`（本仓 Complete 请求就是拿分片 ETag 拼的
`A/data/sync/s3/S3Client.kt:278-285`），WebDAV ETag 由服务端定义且当前没请求。
→ 增量清单必须自建 SHA-256；ETag/mtime 只能用于「跳过上传」的快速路径，不能用于恢复校验。

### 2.4 顺带发现的既有缺口（P0：做增量之前先修；**已落地 2026-11-03**）

这些不是「增量」独有，而是调研中核实的现有正确性问题；不先修，增量清单会把它们固化：

1. **`backgrounds/` 打包但恢复无分支**：`BACKED_UP_FILE_FOLDERS` 含 `FileFolders.BACKGROUNDS`
   （`A/data/sync/BackupItem.kt:46`），但 `restoreUserFileEntry` 的 `when` 没有 `backgrounds/`
   分支（`A/data/sync/BackupArchiveEngine.kt:270-316`）⇒ 恢复后助手背景文件不落盘（设置里的引用悬空）。
   该目录 2026-10-31 才独立出来（`A/data/files/FilesManager.kt:922`），恢复侧漏改。
2. **`avatar-frames/` 既不打包也不豁免**：`A/data/files/FilesManager.kt:909` 有真实写入方
   （`:245-279`），但不在 `BACKED_UP_FILE_FOLDERS` 也不在 `EXCLUDED_FILE_FOLDERS`
   （`A/data/sync/BackupItem.kt:42-50,96-108`）⇒ 头像框素材静默不备份。
3. **`BackupCoverageTest` 的目录全集是手写集合**（`T/data/sync/BackupCoverageTest.kt:19-32`），
   不是从 `FileFolders` 枚举派生 ⇒ 上述两类缺口测试不会红；且没有任何测试覆盖恢复分派 `when`。
4. **S3 列表不翻页 / WebDAV 路径不可多级**（见 §2.3 表最后两行）：增量方案要写对象目录树，
   两处都必须先具备；S3 顺带修好「>1000 对象旧备份静默不可见」。

**P0 修法**（小而独立，可单批上线）：
- 把「目录全集 → 打包形态 → 恢复分派 → 豁免理由」收敛为**一份数据**（`BackupFolderRegistry`），
  打包/预检/恢复/测试都由它派生；恢复按 registry 分派，缺分支即编译期/测试期失败。
- 补 `backgrounds/` 恢复分支；`avatar-frames/` 明确「进备份」或「写豁免理由」二选一（推荐进备份，体积小）。
- 工作区打包接 `BackupZipStrategy`（复用 AppFilesPacking 的 `prepareZipEntry` 思路）——**P1 增量不再走
  工作区 zip 也能受益，但全量 zip 路径仍在使用，值得修**。
- `S3Sync.listBackupFiles` 改为循环 `continuationToken`；`WebDavClient.buildUrl` 按 `/` 拆段逐段编码，
  新增 `mkdirs(path)`（逐级 MKCOL）供对象分片目录使用。

**P0 落地记录（2026-11-03，工作树）**：

- 新增 `A/data/sync/BackupFolderRegistry.kt`：`BACKUP_FOLDER_SPECS` 一份数据派生
  `BACKED_UP_FILE_FOLDERS` / `RECURSIVE_FILE_FOLDERS` / `EXCLUDED_FILE_FOLDERS` / `appFilesPackingPlan`，
  并新增 `BackupRestoreKind`（FLAT/FONTS/SKILLS）与 `backupFolderSpecFor(entryName)`。
  `backgrounds` 的恢复分支由 registry 自动产生；`avatar-frames` 决定**进备份**（平铺）；
  新增 `FileFolders.BACKUP_INDEX`（P1 本地索引用）并登记豁免理由。
- `BackupArchiveEngine.restoreUserFileEntry` 改为 registry 分派（不再手写目录 `when`）；
  `tool_outputs` 仅保留 legacy 兼容分支。
- `WorkspaceBackupHelper` 改用 `AppFilesPacking.addFileToZip`，工作区条目接入 STORED 采样策略。
- S3：新增 `S3Client.listAllObjects`（纯函数 `collectS3Pages` 翻页），`S3Sync.listBackupFiles` 走全量。
- WebDAV：新增 `WebDavPaths.kt`（拆段/逐段编码/URL 拼装/cumulativeSegments）；`WebDavClient` 支持多级
  `config.path` 与相对路径，新增 `mkdirs` 逐级 MKCOL，`ensureCollectionExists` 委托它（签名不变）。
- 护栏：`BackupCoverageTest` 改为**反射 `FileFolders` 全集**比对（新增目录不同步 registry 即失败），
  并新增「每个进备份目录都有恢复分派规格」「backgrounds/avatar-frames 回归」断言；
  `WorkspaceBackupHelperTest` 新增 STORED 回归；新增 `WebDavPathsTest` / `S3PaginationTest`。
- 验证：`:app:testDebugUnitTest` 全量 **2916 项通过，0 失败**；sync 包 16 个测试类全绿。
  真机 S3/WebDAV（多级目录、大文件 STORED、>1000 对象）留批次验收。
- 已知行为差异：`mkdirs` 每级一次 PROPFIND（P1 对象层应缓存）；`config.path` 为空白按未配置处理
  （与旧实现一致）；`mkdirs("")` + 空 `config.path` 为 no-op（旧实现会尝试对 base 发 MKCOL，边界差异）。

## 3. 设计约束（先定这些，再谈算法）

| # | 约束 | 理由 / 证据 |
|---|---|---|
| C1 | **快照自包含**：每份快照清单列出全部文件与 DB/settings 对象，恢复不依赖其他快照 | 避免增量链断裂；WebDAV 无 MOVE/条件写、无法可靠维护 `latest` 指针（`WebDavClient.kt` 方法清单） |
| C2 | **DB/settings 整体替换**（P1）；DB 逻辑增量单独立项（P2） | 保住 `VACUUM INTO` + 整库换入 + Room 迁移 + `sqlite_sequence`/记忆 id 语义（`D/completed/design-backup-workspace-files.md:272-285`）；DB 分块重组会引入「半库」风险 |
| C3 | **内容哈希校验，元数据只做跳过上传** | S3 分片 ETag 不是内容 MD5；WebDAV 无 ETag（§2.3） |
| C4 | **旧版共存**：新对象树只放 `v3/` 子目录；旧全量 zip 不动 | 旧版 App 只列 `backup_*.zip`，看到新目录也不会误读；降级不锁死 |
| C5 | **不绕过现有破坏性路径的安全设计**：manifest 校验、safety_backup、staged 换库、`DataOperationLock` | `A/data/sync/BackupArchiveEngine.kt:55-60,194-474`、`A/data/sync/PendingRestoreStore.kt` |
| C6 | **不偷偷接定时器**：增量仍是手动动作 | 自动备份是明确未立项项（`D/completed/design-backup-manifest-and-safety.md:97`） |
| C7 | **快照格式版本化且 fail-closed**：未知/更高版本拒绝，不猜 | 沿用 `formatVersion > 支持版本直接报错`（`A/data/sync/BackupManifest.kt:96-101`） |

## 4. 方案比选

| # | 方案 | 增量单位 | 恢复语义 | 代价/风险 | 结论 |
|---|---|---|---|---|---|
| A | 维持全量 zip | 无 | 现有 | 每次 2.8 GB；上游 #2280 即此痛点 | 基线 |
| B | **文件级清单增量 + 内容寻址对象**（本稿 P1） | 文件（原始内容 SHA-256） | 拼回 v2 zip → 现有引擎 | 需新格式/对象层/清单；DB 仍整库；首备全量 | **采纳（P1）** |
| C | **DB 对象级逻辑增量**（本稿 P2） | 会话/设置/记忆等对象 | 启动期合并进现库副本 → staged 换库 | 需保留 id 的 upsert + 删除语义 + FTS 重建；有版本兼容面 | **采纳（P2，条件成熟）** |
| D | DB 整文件分块去重（CDC，restic 式） | 变长块 | 拼回整库文件 → staged 换库 | 需 block index/GC/Range；`VACUUM INTO` 页布局稳定性与命中率**未实测**；FTS 53.8% 白搬；版本闸门卡链 | 不采纳（仅在实测证明收益后评估固定块） |
| E | 远端镜像 / rsync 式文件树 | 文件 | 目标=源镜像 | DB 单文件每次都变；每文件一次 PUT 在 WebDAV 放大；无服务端同步语义 | 不采纳（B 已覆盖其价值） |
| F | 增量链（parent + delta，restic/borg 式） | 链上 delta | 按链叠加 | 断链即废；WebDAV 无原子指针；删除/GC 复杂 | 不采纳（C1 自包含替代） |

**为什么选 B+C 分层**：
- 上传量支配项是文件区（2.8 GB）→ B 用文件级内容寻址即可拿到主要收益，且**不需要合并语义**；
- DB 是「对象元数据 0.7% + 消息文本」→ 文件级对 DB 无收益（整库对象每次都变），C 才能继续降；
- 两层都保持「**每份快照独立可恢复**」，避免链式风险；
- B 的恢复完全复用现有引擎（风险最低），C 的恢复落在启动期窗口（不碰 Room 写路径）。

## 5. 详细设计

### 5.1 总体结构

```
源数据                                        目标仓库（本地 SAF / WebDAV / S3，root 下新增 v3/）
┌─────────────────────────────┐               ┌──────────────────────────────────────────────┐
│ 备份数据（现状不变）         │               │ v3/                                          │
│  settings（DataStore）       │               │   snapshots/<createdAt>_<device>.json.gz      │  ← 提交点（最后写）
│  rikka_hub.db（VACUUM 快照） │               │   packs/<sha256>.zip                          │  ← 小文件批量包（内容寻址）
│  files/{upload,images,...}   │  ──上传──▶    │   objects/<aa>/<sha256>                       │  ← 大文件/DB/settings 对象（内容寻址）
│  workspaces/<root>/files/**  │               └──────────────────────────────────────────────┘
│                              │
│ filesDir/backup_index/       │               * 旧全量 backup_yyyyMMdd_HHmmss.zip 仍在 root，不动
│  <destId>/state.json（缓存） │
└─────────────────────────────┘
```

**快照 = 一次备份的完整视图**：清单里每条文件都指向具体内容（独立对象或 pack 内条目），
加上 DB/settings 对象引用。对象按内容寻址 → 跨快照天然去重；清单本身每份都完整 → 快照间无依赖。

### 5.2 远端布局与提交协议

| 路径 | 内容 | 命名 | 写入时机 |
|---|---|---|---|
| `v3/snapshots/<createdAt>_<deviceShort>.json.gz` | 快照清单（压缩 JSON） | UTC 时间戳 + deviceId 短哈希，唯一 | **最后写**（提交点） |
| `v3/packs/<sha256>.zip` | 一批小文件（zip，条目名=备份内相对路径） | **内容哈希**（= 文件字节的 SHA-256） | 清单之前 |
| `v3/objects/<aa>/<sha256>` | 单文件/DB/settings 对象 | **原始内容 SHA-256**（`aa` = 前两位，分片目录） | 清单之前 |

> **实际落地（2026-11-05）**：快照文件为 `<uuid>.json.gz` + `<uuid>.complete.json`（§0.1 第 4 条），
> pack 条目名是对象内容 SHA 而非备份内路径，清单 v3 用逐引用 `pack` 字段，详见 §0.10。

- **提交语义**：先传所有对象/pack，再传清单；清单存在 = 快照有效。中断只留下未引用对象/pack，
  由 GC 清理；**不需要 MOVE、不需要条件写、不需要最新指针**（每份快照可独立列出/恢复）。
- **列举快照**：WebDAV `PROPFIND Depth:1 v3/snapshots/`；S3 `ListObjectsV2(prefix, 翻页)`；
  按文件名（时间戳）倒序，取最新用于增量 diff。多设备：清单含 `deviceId`，默认只对**本设备**快照做
  保留与 diff，其他设备快照在 UI 单列（不自动删）。
- **对象分片**：`objects/<aa>/` 256 个目录，避免单目录十万文件（WebDAV 需要逐级 MKCOL，
  是 P0 要修的 `buildUrl`/`mkdirs`；S3 前缀天然支持）。
- **不变量**：对象名 = 原始内容 SHA-256，写入后不可变；重复 PUT 幂等（内容相同），无需条件写。

### 5.3 快照清单格式（`SnapshotManifest` v1）

新文件 `A/data/sync/repo/BackupSnapshotManifest.kt`（与 zip 内的 `BackupManifest` v2 是两个格式，互不复用版本号）：

```kotlin
@Serializable
data class BackupSnapshotManifest(
    val formatVersion: Int = SNAPSHOT_FORMAT_VERSION, // = 1
    val createdAt: String,            // "yyyyMMdd_HHmmss"（UTC），与文件名一致
    val deviceId: String,             // 本机持久 UUID（首个快照时生成）
    val appVersionCode: Int,
    val dbVersion: Int,               // AppDatabase.SCHEMA_VERSION（备份时）
    val items: List<BackupItem>,      // 本快照包含的备份项
    val sections: Map<BackupManifestSection, BackupSectionStats>, // 预览/交叉校验
    val files: List<SnapshotFileEntry>,          // FILES + WORKSPACE_FILES 全量
    val database: SnapshotObjectRef?,            // P1：整库对象；P2 起可为空 + dbObjects
    val settings: SnapshotObjectRef,             // 整份 settings.json 对象
    val dbObjects: List<SnapshotDbObject> = emptyList(), // P2 预留
    val previous: String? = null,     // 仅 UI/诊断，不参与恢复
)

@Serializable
data class SnapshotFileEntry(
    val path: String,        // zip 内相对路径，如 "upload/<uuid>.png" / "workspace_files/<root>/a/b"
    val size: Long,          // 原始字节数
    val mtime: Long,         // 原始文件 lastModified（毫秒；用于本地缓存快速判定，不参与校验）
    val sha256: String,      // 原始内容 SHA-256（**校验基准**）
    val ref: SnapshotRef,    // 对象或 pack 内条目
)

@Serializable
sealed interface SnapshotRef {
    @Serializable @SerialName("object")
    data class Object(val key: String) : SnapshotRef          // objects/<aa>/<sha256>
    @Serializable @SerialName("pack")
    data class Pack(val pack: String, val entry: String) : SnapshotRef // packs/<sha256>.zip 内条目名
}

@Serializable
data class SnapshotObjectRef(
    val key: String,          // 原始内容 SHA-256
    val rawBytes: Long,       // 原始长度
    val codec: String,        // "stored" | "deflate"（对象 blob 自描述头之外的冗余声明，供 UI/空间估算）
)
```

- **清单体积**：条目 ≈ 150～250 B（JSON 字段名 + 64 hex）。10 万文件 ≈ 15～25 MB，gzip 后约 2～4 MB。
  这是 B 方案的主要规模风险：**P1 先接受**（每次备份上传 2～4 MB 可接受），
  在 `files.size > 20_000` 时打 warn 埋点；若实测突破，P2 改为「按路径哈希分页的清单树」
  （每页一个对象，父清单只存页哈希）——写入/恢复协议不变，只换清单组织。
- **校验规则**（纯函数，JVM 直测，风格同 `verifyBackupManifest`）：
  ① `formatVersion` 高于支持版本 → 拒绝（旧 App 不认新包）；
  ② `items` ⊆ 已知枚举；③ `path` 唯一、非空、必须以已知前缀开头、拒绝 `..`/绝对路径/反斜杠；
  ④ `sha256` 为 64 位小写 hex；⑤ `size`/`mtime` ≥ 0；⑥ pack 条目必须在 pack 索引内（偏移/长度不越界）；
  ⑦ `items` 含 DATABASE 时 `database != null`；⑧ 条目总数/总字节不超过上限（复用 2 GB/条、8 GB 总量思路，
  防止伪造清单把恢复撑爆）；⑨ `sections` 与派生统计一致（可选，用于提前发现清单截断）。
- **清单本身没有签名**：与今天 v2 同信任级（篡改者需同时改对象才能通过内容哈希）。
  加密/HMAC 是独立立项（`docs/upstream-analysis.md:1481`）。

### 5.4 对象与 pack 格式

**单对象（`objects/<aa>/<sha256>`）**：文件名 = 原始内容 SHA-256。blob 自描述：

```
byte 0: 0x00 = 原始字节 | 0x01 = DEFLATE（raw deflate，无 zlib 头）
byte 1..: 编码后的 payload
```

- 压缩决策：对可读内容采样复用 `BackupZipStrategy.shouldStoreUncompressed`（64 KB 头、Deflater 同级），
  不可压缩 → `0x00`；可压缩（DB/settings/文本）→ `0x01`。DB 实测 2.27x 可压，115 MB → 约 50 MB。
- 恢复时先解码、再算 SHA-256 与清单比对；不符即拒绝（单对象坏不会写半套数据）。
- **为什么内容哈希不放在 blob 头里**：对象名已经承载哈希；自描述头只解决「用哪种编码」，
  避免「同一内容在不同设备压缩决策不同 → 同名对象字节不同」的歧义（解码后必回同一原始内容）。

**pack（`packs/<sha256>.zip`）**：
- 一次备份里所有**小于阈值（建议 256 KB）的新增/变更文件**打进一批或几批 zip（每批原始总量 ≤ 32 MB，
  避免单对象过大；S3 分片与 WebDAV 单 PUT 都可承受 32 MB）。
- zip 条目名 = 文件在备份内的相对路径（与快照清单的 `path` 一致），条目内容 = 原始字节；
  条目压缩用现有采样 STORED 策略（P0 抽公共 `prepareZipEntry` 后复用）。
- pack 文件名 = **整个 zip 字节的 SHA-256**；清单 `SnapshotRef.Pack(pack, entry)`。
- 大文件（≥256 KB）走单对象（`0x00`/`0x01` 自描述），避免把 GB 级 git pack 塞进 zip 再读一遍。
- 恢复：每个被引用的 pack 下载到 cacheDir 一次，用 `ZipFile` 逐条抽；同一 pack 只下载一次。

> **实际落地（2026-11-05，§0.10）**：条目名取对象内容 SHA（同一内容寻址键，清单 `sha256` 直接对应条目名），
> 条目时间固定 1980-01-01 以获得确定性字节；清单 v3 在每条引用上加可选 `pack = <zip 字节 SHA-256>`，
> 而不是 `SnapshotRef` 联合类型；256 KB 阈值与 32 MB/批不变，pack 之间不再按路径分批而是按 sha 排序分批；
> 部分条目失效时 GC 会在租约内拆包重写（§0.12）。

**边界**：沿用现有恢复上限（单条目 2 GB、总量 8 GB，`A/data/sync/BackupManifest.kt:31-35`）——
超过 2 GB 的单文件在现有 zip 恢复里也会失败，增量恢复保持一致并在预检时报错。

### 5.5 对象层接口与两端适配

新文件 `A/data/sync/repo/BackupObjectStore.kt`：

```kotlin
/** 增量仓库的存储原语（本地 SAF / S3 / WebDAV 共用）。 */
interface BackupObjectStore {
    suspend fun listChildren(prefix: String): Result<List<RemoteObject>> // 三端统一为直接子项；S3 内部处理分页
    suspend fun stat(key: String): Result<ObjectPresence>              // 存在/缺失；查询错误走 Result.failure
    suspend fun get(key: String, target: File): Result<Unit>            // 下载到文件
    suspend fun put(key: String, file: File, contentType: String): Result<Unit>
    suspend fun delete(key: String): Result<Unit>
    suspend fun ensureDir(prefix: String): Result<Unit>                 // S3 no-op；WebDAV 逐级 MKCOL
}

data class RemoteObject(val key: String, val size: Long, val lastModified: Instant?)
// ObjectPresence：Missing | Present(size: Long?)；不使用 -1 表示不存在，读取失败不得降为 Missing。
```

实现：
- `S3BackupObjectStore`：包 `S3Client`；`list` 用 `continuationToken` 循环（修 P0）；
  `put` 复用 multipart（>8 MB）；`delete` 复用。
- `WebDavBackupObjectStore`：包 `WebDavClient`；`put` 需要多级路径（P0 修 `buildUrl` + `mkdirs`）；
  `listChildren` = `PROPFIND Depth:1`；`get` 用现有流式下载。
- `SafBackupObjectStore`：以持久目录 URI 为根，用 `ContentResolver`/`DocumentsContract` 读写；
  不转换成真实文件路径。`ensureDir` 逐层建立目录，具体能力与失败规则见 §5.14。
- **不新增协议**：不要求 If-Match/Range/MOVE；快照提交靠「唯一文件名 + 最后写」。
- **能力探测**：首次启用时 `ensureDir("v3/objects/00")` + `put` 一个 1 B 探针对象再删，失败给出
  「该 WebDAV 服务端不支持多级目录」的明确错误（而不是备份跑到一半失败）。

### 5.6 本地索引（哈希缓存 + 仓库状态）

新目录 `filesDir/backup_index/`（`FileFolders.BACKUP_INDEX`，加入 `EXCLUDED_FILE_FOLDERS`，
理由「本地增量索引，可再生（重算哈希）」；同步更新 `BackupCoverageTest` 的声明集合）：

```
backup_index/
  <destId>/state.json      # destId = sha256(endpoint|bucket/dir)[:16]
```

`state.json` 内容（`@Serializable`，原子写复用 `AtomicFileWrite`）：
```kotlin
data class BackupRepoState(
    val repoFormatVersion: Int = 1,
    val destinationId: String,
    val deviceId: String,
    val lastSnapshot: String?,                 // 快照文件名
    val lastSnapshotCreatedAt: String?,
    val hashCache: Map<String, CachedHash>,    // path -> 缓存
)
data class CachedHash(val size: Long, val mtime: Long, val sha256: String)
```

- **命中条件**：`size` 与 `mtime` 都相同才复用 sha256；否则重算并覆盖。
- **失效/重建**：提供「重建备份索引」（全量重算）入口；恢复后文件 mtime=now（现有恢复不回写时间，
  `A/data/sync/BackupArchiveEngine.kt:737-757`），首次增量会把所有文件重算一遍——与今天打包读一遍同量级，
  可接受，但要在 UI 说明「恢复后首次备份会重新校验」。
- **可信度**：索引只用于**少读盘**，正确性不依赖它——每次仍会与远端最新快照清单比对哈希；
  索引丢失/损坏最多导致重算，不会漏传（漏传 = 恢复缺文件，必须由「远端清单为准」保证）。
- 索引**不进备份**（豁免项），也不进 Android auto-backup（路径不在 `backup_rules.xml` 白名单）。

### 5.7 备份流水线（P1）

新文件 `A/data/sync/repo/IncrementalBackupEngine.kt`（与 `BackupArchiveEngine` 平级，复用其 DB/settings 快照逻辑或抽公共方法）：

```
suspend fun plan(items, onProgress): BackupSnapshotPlan
suspend fun commit(plan, onProgress): BackupSnapshotRef
```

`plan`（只读、可单独用于「预估上传量」）：

1. `DataOperationLock.runWaiting(DataOperation.BACKUP)` 包住整个 plan+commit（与全量备份互斥）。
2. 生成 DB 快照（`VACUUM INTO`，复用 `addDatabaseToZip` 的快照部分）与 settings JSON；算 SHA-256。
3. 枚举文件（**与打包同一份 registry**，跳过瞬态文件：`*.tmp`、`.staging.*`、`*.part`）；
   对每个文件用 §5.6 缓存算 sha256；按阈值分「小文件（进 pack）/大文件（单对象）」。
4. 读最新快照清单（`list v3/snapshots` → 下载最新一个）：
   - `远端内容哈希 → ref` 映射（来自最新清单；同一内容在旧 pack 里也可复用 ref）；
   - 计算 `toUpload`：内容哈希不在映射里的 pack 条目/对象；DB/settings 哈希不同才传。
   - 删除语义：新清单不包含的路径自然消失（清单是新的完整视图），**无需 tombstone**。
5. 输出 `BackupSnapshotPlan`：候选清单 + 待传对象/pack + 复用字节/待传字节（UI 预估）。
6. `plan` 不写远端、不改本地状态；用户取消零副作用。

> 性能实现（2026-11-04，§0.8）：DB 逻辑对象直接原地哈希/压缩（不再复制 `raw-*`），
> 哈希/`store.stat`/复用校验/DEFLATE 按对象并行（`PARALLEL_OBJECTS = 4`）；`ensureDir` 保序。

`commit`：

1. 生成 pack（cacheDir 临时 zip，算文件哈希即得 pack 名）、大对象（cacheDir 临时文件，自描述头）。
2. 逐个上传 pack/对象（串行，失败可重试该对象；不做整包断点）；进度按字节上报。
3. 组装并上传快照清单（**最后写**）→ 成功即提交。
4. 写本地 `state.json`（lastSnapshot + hashCache）。
5. 保留策略：列出本设备快照，超过 `keepLastN`（默认 10）删最旧清单；
   然后 GC：被删清单的引用集 − 保留清单引用集 = 可删对象/pack（**不列举远端全量对象**，
   所以不会误删别的设备/未知对象；孤儿对象留给「深度清理」，见 §5.8）。
6. `onProgress` 阶段：`SCANNING`（枚举+哈希）→ `UPLOADING`（对象/pack）→ `COMMITTING`（清单）→
   可复用 `BackupProgress`；`BackupProgressRow` 只显示字节/百分比，不依赖阶段文案（`:13-18` 注释），
   新增枚举值零 UI 改动。

**并发与校验（性能实现，§0.8）**：上传按对象并行（目录先顺序预建）；上传后不再逐对象
回读，改为「本地编码文件解码 + SHA + 发布前 `stat` 复检」，恢复端仍逐对象校验 SHA-256。

**失败语义**：
- 清单上传失败 = 本次快照不存在，已传对象成为孤儿；下次计划只与「最新快照」diff → 孤儿会重新上传（浪费一次），
  深度清理可回收；不引入半份快照。
- 进程被杀：同上；不做断点续传（P1 明确不做，长备份建议后续接前台服务/Worker，见 §8）。
- 对象内容写坏：内容寻址 + 恢复时校验兜底；上传后可选回读校验（P1 不做，S3 SigV4 已保证入站完整性）。

**预估/确认交互**：备份按钮 → `plan`（扫描可在秒级到分钟级）→ 弹「本次预计上传 X（其中数据库 Y），
复用 Z」→ 用户确认 → `commit`。首次备份预估 = 全量，文案明确「首次会全量上传，之后只传变更」。

### 5.8 恢复流水线（P1：拼回 v2 zip，复用现有引擎）

新文件 `A/data/sync/repo/SnapshotRestoreAssembler.kt`：

1. 选择快照（列表来自 `v3/snapshots/`，显示时间/大小/sections/设备）；下载清单并校验（§5.3）。
2. 版本闸门：`formatVersion > 支持` → 拒绝；`dbVersion > AppDatabase.SCHEMA_VERSION` → 拒绝
   （与 staged 库 `user_version ∈ 1..SCHEMA_VERSION` 同口径，`A/RikkaHubApp.kt:260-268`）。
3. 逐条下载所需 pack/对象到 cacheDir（同一 pack 只下一次），每个对象/pack 校验 SHA-256；
   条目校验原始内容哈希。
4. 在 cacheDir **组装一份 v2 zip**：
   - `settings.json`（settings 对象解码后写入，计算 SHA-256/字节数）；
   - `rikka_hub.db`（DB 对象解码后写入；P1 无 `-wal/-shm`，因 VACUUM 快照自包含）；
   - 文件条目按路径写入（`upload/…`、`workspace_files/<root>/…` 等，与现有恢复分派完全一致）；
   - 生成 v2 `manifest.json`（sections/settingsSha256/settingsBytes/databaseSha256/databaseBytes），
     **让现有 `verifyBackupManifest` 照常工作**。
5. 调 `BackupArchiveEngine.restoreFromBackupFile(zip, items = snapshot.items)`：
   现有逻辑负责校验、safety_backup、staged DB、settings 写入、文件落盘、强制重启。
6. 清理 cacheDir 临时文件。

**为什么 P1 选择「拼 zip 再走引擎」**：
- 破坏性路径、manifest v2 校验、safety_backup、staged 换库、legacy 转换全部**零改动**，风险最低；
- 空间开销与今天「下载全量 zip 再恢复」同量级（zip + 解压产物），现有 `BackupSpaceCheck` 可复用
  （`A/data/sync/BackupArchiveEngine.kt:222-243`）；
- 代价：多一次本地写 zip（2.8 GB 级约数分钟）与两次 zip 读；P2 的原生恢复可省掉。

**组装细节（实现约束）**：
- zip 条目用 `ZipOutputStream` 写入（DEFLATE 或按采样 STORED；STORED 需预置 CRC，实现上二选一）；
  对象恢复路径实测（§0.8）：文件/DB 对象**并行下载 + 解码**后单线程写 zip，DB 条目用 STORED
  （换库恢复会立即解压，省一次 208 MB 级 DEFLATE；临时 zip 体积 ≈ DB 原始大小，空间预检照旧）；
- 每个条目写入前完成对象哈希校验（失败立即中止，不产生「部分 zip」）；
- 条目名来自清单 → 必须用与现有恢复同一套 `classifyBackupEntry` + 路径安全规则做二次校验；
- 单文件 > 2 GB 直接拒绝（现有恢复上限）。

**P2 原生恢复（后续，不在 P1）**：逐对象校验后直接落盘（FILES/WORKSPACE），DB 走逻辑合并（§5.9），
可用本地索引跳过「已与目标相同的文件」；省掉 zip 中转与一次全量读。

### 5.9 DB 逻辑增量（P2：把 DB 从「每次整库」变成「按对象」）

> 目标：备份上传量里仍占大头的 DB 快照（P1 约 50 MB/次压缩后）进一步降到「改动的会话」。
> **P2 不是 P1 的前置**；P1 上线并测量后，按数据决定是否做。
>
> **首版落地记录见 §0.6**：v2 快照改用逻辑对象、按会话/整表替换、恢复走合并 + 启动重建 FTS；
> 下面保留原始设计中的细粒度方案（跨 schema、按 assistant/游标拆对象）作为后续增强。

**数据事实（实测，§2.1）**：DB 98.9% = `message_node` + 可重建 FTS；对象元数据 0.7%。
FTS 不备份，恢复后重建（`A/data/repository/ConversationRepository.kt:596-607`、
`A/data/db/fts/MessageFtsManager.kt:38-67`）。

**对象划分**（`SnapshotDbObject(objectKey, sha256, ...)`）：

| 对象 | 内容 | 哈希基准 | 优先级 |
|---|---|---|---|
| `conversation:<id>` | `ConversationEntity` 行 + 该会话全部 `message_node` 行 + `compression_checkpoint` 行 | 行规范化字段 + `getNodeSignaturesOfConversation(id)`（现成查询，`A/data/db/dao/MessageNodeDAO.kt:50-54`）+ checkpoint 行 | P2 首做（覆盖 98% 字节） |
| `settings` | 整份 Settings JSON | 字节 SHA-256 | P1 已有（对象） |
| `memory_store:<assistantId>` | 该助手 `MemoryEntity`（稳定键 `public_id`）+ `memory_trash` + `memory_history` | 行序列 | P2 |
| `favorites` / `workspaces` / `conversation_folder` | 小表整表 | 行序列 | P2（<0.1 MB，收益小） |
| append-only 流水（`usage_record`/`translation_history`/`scheduled_task_runs`） | 自增 id/时间游标之后的行 | 游标 + 行数 | P2（注意 trim 会删尾，游标必须容忍） |
| `media_creation_*` / `scheduled_tasks` / `knowledge_*` / `agent_run` | 按对象整份 | 行序列/`update_at` | P2 视体积 |
| **排除** | `message_fts*`（派生）、`room_master_table`/`android_metadata`/`sqlite_sequence`（库簿记）、`memory_review_cursor`（进度可重建；`memory_consolidation` 因挂会话外键必须随快照，见 §0.6） | — | — |

**变更检测**：备份时对每个对象算哈希（会话用 node signatures；总量 ~50 MB 文本，SHA-256 秒级），
与最新快照清单比对；**不引入触发器、不依赖 `updatedAt`**——
`ConversationEntity.update_at` 有 10+ 个单列 UPDATE 不刷新（`A/data/db/dao/ConversationDAO.kt:176/183/194/202/217/243/254/258/263/345/352`），
`message_node` 根本没有时间戳列（`A/data/db/entity/MessageNodeEntity.kt:21-32`）。

**删除语义**：快照清单是新完整视图；旧清单有、新清单无的对象即删除，**不需要 tombstone**；
恢复侧按 id 删除（现有外键 CASCADE 覆盖子表）。注意：会话是硬删 + CASCADE
（`A/data/repository/ConversationRepository.kt:567-585`）。

**存储**：每个对象作为内容寻址对象（JSON/行序列，DEFLATE），跨快照去重；
快照清单新增 `dbObjects` 映射（对象键 → sha256），不再有 `database: SnapshotObjectRef`。

**恢复（启动期，`startKoin` 之前）**：
1. 校验所有 db 对象下载完整、哈希正确；
2. 以**当前库副本**为基底（当前 schema，已含新表；避免复制期 Room 打开）；
3. 对快照覆盖的表：清空后按「列交集」插入对象行（复用
   `A/data/sync/LegacyBackupConverter.kt:157-209` 的 copyTable 手法；新列用默认值，旧列忽略）；
   注意插入顺序（会话行 → 节点 → 检查点；外键）；
4. 写入与现有 staged restore 同款 `PendingRestoreStore`（现库 rename 让位、失败回滚）；
5. Room 打开后重建 FTS（全库或只重建受影响会话），`confirmApplied`。

**必须保留的语义**：恢复后 `user_version` 保持当前（基底是现库副本，不跨 Room 迁移）；
快照 `dbVersion > 当前` → 拒绝；`< 当前` → 按列交集插入（新表/新列保留本机数据）。

**版本风险**：跨版本「对象来自旧 schema」比「整库文件来自旧 schema」更难验证（列交集只是向前兼容，
不处理语义迁移）。P2 实施前必须做三组实验：
① 用 v38/v58 真机库互导对象（含新表缺列）并跑 `PRAGMA integrity_check` + 关键 UI 冒烟；
② FTS 全库重建耗时（49.6 MB 文本 / 7,606 条，未知，可能分钟级；需可「只重建受影响会话」）；
③ 会话对象 upsert 与活跃会话内存态的冲突（启动期应用天然规避，但需确认时序）。

**P2 的简化退路（P2-lite，若逻辑合并风险过高）**：DB 仍整文件，但快照时**剔除 FTS 表**
（53.8% 字节），恢复后重建 FTS。收益：DB 上传从 ~50 MB 降到 ~22 MB；代价：恢复后多一次 FTS 重建，
且需要「DB 文件来自快照但 FTS 为空」的启动处理。先测量再选。

**明确不做**：
- `sqlite3session`（本仓 SQLite 是 requery fork + 自定义扩展，Java 层无 session API；需自编
  `SQLITE_ENABLE_SESSION` + JNI + FTS shadow table 兼容，成本最高，`A/di/DataSourceModule.kt:89-136`）；
- 按 `updatedAt` 拉行（漏 bump 太多，见上）；
- DB 整文件 CDC（页布局稳定性/命中率未实测，且版本闸门卡链）。

### 5.10 兼容、迁移与回滚

| 场景 | 行为 |
|---|---|
| 旧版 App 看到新结构 | 只列 root 下 `backup_*.zip`，忽略 `v3/`；**不破坏、不误删**（保留策略只在增量模式内执行） |
| 新版 App 恢复旧 zip | 现有路径不变（`S3Sync/WebDavSync.restore*` → `BackupArchiveEngine`） |
| 新版 App 恢复新快照 | 拼 v2 zip → 现有引擎；旧版无法读新快照，文案明确 |
| 新快照 → 旧版 App | 不支持；用户应保留至少一份旧全量 zip（UI 可提示「导出完整备份」） |
| 设置持久化 | `Settings` 新增 `backupConfig`（`enabled`, `keepLastN`, `hashCache` 开关）字段；
旧 JSON 缺省取默认；未知新字段由 `ignoreUnknownKeys` 丢弃（降级不炸） |
| 回滚 | 关闭增量开关即回到全量；`v3/` 数据可保留或删除；DB schema **无变更**，无需迁移 |
| 保留策略删除 | 自动保留只删**本设备**的旧快照清单；手动 GC 做全仓可达性分析、仅单设备仓库执行、失败即停（§0.7） |

### 5.11 UI / UX

入口：备份页（`BackupPage`/`S3Tab`/`WebDavTab`/`ImportExportTab`）新增：
1. **模式开关**：设置 → 备份 → 「增量备份（实验）」。开启后 WebDAV/S3 的备份按钮走增量；
   本地增加独立“备份文件夹”入口，同样走增量。原有完整 ZIP 导入/导出继续保留（文案说明）。
2. **首次说明卡**：「首次将上传全量数据，之后只上传新增/变更；恢复方式不变」。
3. **备份前预估**：`plan` 后弹「预计上传 X（数据库 Y / 文件 Z），复用 W」+ 确认；
   取消零副作用。
4. **快照列表**：时间 / 逻辑总大小 / 本次上传 / 设备 / 项目（sections）；操作：恢复、删除、
   导出为完整 zip（可选，复用拼包）。旧全量 zip 列表保留（分 tab 或分组）。
5. **保留设置与 GC**（已实现，§0.7）：默认按本设备保留最新 10 份（0=不限，选择 0/5/10/20），
   提交后自动清理旧清单；「回收未引用对象」手动触发（确认框说明单设备限制、失败即停）。
6. **进度**：备份/恢复复用 `BackupProgressRow`；恢复后 FTS 重建在根 UI 显示横幅（done/total、失败重试），
   快照 tab 同步显示重建状态（§0.7）。
7. **错误文案**：WebDAV 不支持多级目录 / 清单版本过新 / DB 版本过新 / 单文件 >2 GB /
   空间不足（需多少/有多少）——沿用现有错误风格。
8. 字符串：若实施，按 `locale-tui-localization` 补 en/zh/zh-TW。
9. **跨 schema 提示**（已实现，§0.7）：快照卡片显示 `数据库 vN`，旧版本快照在卡片与恢复确认框提示
   「按当前 vM 结构恢复，新增字段用默认值，已删除表/列跳过」。

### 5.12 代码落点与改动清单

**新增**（`A/data/sync/repo/`）：

| 文件 | 职责 |
|---|---|
| `BackupSnapshotManifest.kt` | 快照清单模型 + `verifySnapshotManifest` 纯函数 + 序列化 |
| `BackupObjectStore.kt` | 远端对象层接口 + `RemoteObject` |
| `S3BackupObjectStore.kt` / `WebDavBackupObjectStore.kt` | 两端适配（复用现有 Client） |
| `SafBackupObjectStore.kt` | 本地目录 URI 适配、按层查询/创建、流读写与缺失/失败区分 |
| `LocalBackupTargetStore.kt` | 本机目录授权关联（treeUri/repoId/显示名），与可导出的 Settings 分离 |
| 复用已有 `BackupFolderRegistry.kt` | 目录单一真源，补共享枚举与按写入方限定的瞬态过滤，不再新增第二份 registry |
| `BackupInventoryStore.kt` | `state.json`（destId、deviceId、哈希缓存）读写 |
| `IncrementalBackupPlanner.kt` | 枚举/哈希/diff/plan（IO 可注入，便于 JVM 测试） |
| `IncrementalBackupEngine.kt` | plan+commit 编排，`DataOperationLock`；P1 不包含共享对象 GC |
| `SnapshotRestoreAssembler.kt` | 下载/校验/拼 v2 zip/调现有引擎 |

**修改**：

| 文件 | 改动 |
|---|---|
| `A/data/sync/BackupItem.kt` | 由 registry 派生；补 `BACKUP_INDEX` 豁免；`backgrounds` 恢复、`avatar-frames` 决策 |
| `A/data/sync/BackupArchiveEngine.kt` | `backgrounds/` 恢复分支；抽「DB 快照」「settings JSON」公共方法供 planner 复用；事件/阶段 |
| `A/data/sync/WorkspaceBackupHelper.kt` | 接 `BackupZipStrategy`（全量 zip 路径受益） |
| `A/data/sync/S3Sync.kt` / `webdav/WebDavSync.kt` | 新增快照备份/列表/恢复/删除；列表翻页；`path` 多级 |
| `A/data/sync/s3/S3Client.kt` | `listObjectsAll`（内部翻页）或 Sync 内循环 |
| `A/data/sync/webdav/WebDavClient.kt` | `buildUrl` 按 `/` 拆段；`mkdirs`；可选返回 ETag |
| `A/data/sync/BackupProgress.kt` | 新增 `SCANNING`/`COMMITTING` 阶段（纯枚举） |
| `A/data/datastore/PreferencesStore.kt` | `Settings.backupConfig` + key 读写 |
| `A/ui/pages/backup/BackupVM.kt` + tabs/components | 三端快照列表、预估、进度；本地目录选择/重新关联；原 ZIP 入口保留 |
| `app/src/main/res/values*/strings.xml` | 新增文案（en/zh/zh-TW） |
| `T/data/sync/BackupCoverageTest.kt` | 由 registry 派生断言；新增恢复分派覆盖测试 |

**不改**：Room schema/DAO/实体（P1/P2 都不加表、不加触发器、不加迁移）；zip 内 `BackupManifest` v2 格式；
`PendingRestoreStore` 状态机；`restoreFromBackupFile` 的破坏性路径。

### 5.13 测试与护栏

- 纯 JVM：清单序列化/校验（版本、路径、哈希、pack 越界、上限）；planner diff（新增/修改/删除/复用）；
  pack 切分与抽取；对象 blob 编解码（stored/deflate，CRC 与哈希）；哈希缓存命中/失效；
  registry 覆盖 `FileFolders` 全集 + 恢复分派一一对应（补 `backgrounds`/`avatar-frames` 回归）；
  拼装 zip 的条目前缀/分类/计数与 v2 manifest 校验往返。
- 用内存假 `BackupObjectStore` 做端到端（JVM）：plan→commit→list→assemble→`inspectBackup` 校验；
  篡改对象 → 恢复拒绝；中断（不上传清单）→ 不产生快照。
- 真机矩阵（S3: MinIO/R2/阿里 OSS 兼容端；WebDAV: Nextcloud/坚果云/自建）：
  首备全量 → 改 1 会话 + 加 1 文件 → 备 → 断言上传字节 ≈ 变更（planner 日志/远端清单）；
  恢复旧快照与最新快照；杀进程中断；手动删除快照（对象保留）；空间不足；旧全量 zip 恢复不受影响。
- 本地目录仓库的假 provider、仪器测试与真机验收见 §5.14；GC 并发安全属于 P1.5 前置验证。
- 性能断言（目标值，先测量后定）：增量备份上传 ≤ 全量的 10%（工作区型数据）；
  恢复时间 ≤ 现有全量恢复 × 1.2（拼 zip 的额外开销）；哈希缓存命中时扫描 ≤ 数秒。

### 5.14 本地目录仓库（2026-11-04 用户确认，纳入 P1）

**已确认的产品选择**：让用户选择一个“备份文件夹”，在该目录里维护完整快照清单和共享内容对象。
本地、S3、WebDAV 使用相同仓库格式、planner、对象编解码、提交校验与恢复组装器。
本节记录已确认的本地设计范围；后续首版实现与仍待完成的验收见 §0.2。

#### 入口与用户流程

1. 备份页新增“备份文件夹”区域：初始按钮“选择备份文件夹”，通过 `ACTION_OPEN_DOCUMENT_TREE`
   授权目录。取消选择不创建仓库、不改变原目标；更换目标先验证新目录再替换本机关联。
2. 选择空目录后显示“在此建立备份仓库”摘要；用户执行首次备份时初始化仓库。非空目录若存在
   合法 `repo.json` 则打开；未知非空目录不覆盖，提示选择专用空文件夹或已有仓库。
3. 已关联目录显示名称、可用状态、最近成功备份及已提交快照；操作为“立即备份”“恢复快照”
   “重新选择文件夹”“解除关联”。解除关联不删除目录或备份数据。
4. 备份前预估显示“预计新增写入 / 复用 / 数据库大小”；首次说明“首次完整写入，之后只写变化内容”。
   进度使用“扫描 / 写入 / 提交”，不显示网络“上传”；扫描、哈希、DB 快照仍有本地 I/O 成本。
5. 另保留“导出完整 ZIP”和“从 ZIP 恢复”；分享或单文件归档走完整 ZIP。
   目录仓库迁移需携带 `repo.json` 与整个 `v3/`，仅复制某份清单不足以恢复。

#### 授权、存储边界与关联信息

- 通过返回 intent 实际授予的 flags 获取读写权限，并持久保存允许持久化的 URI 授权。
  备份需要读、列举、创建和写入能力；已有仓库若只能读取，可用于列举/恢复，但禁用备份和删除。
  权限拒绝、失效或存储暂不可用应显示不同状态，保留关联供重新选择，不自动初始化新仓库。
- 所选目录为仓库根，布局为 `repo.json` + `v3/snapshots/` + `v3/objects/<aa>/` + `v3/packs/`。
  `repo.json` 至少包含格式版本、随机 repoId、创建时间；快照与完成标记沿用 §0.1 的 UUID 与哈希规则。
  初始化中断后的目录不自动当作新空目录覆盖；仅在能确认属于本仓库初始化任务时恢复，否则要求另选空目录。
- 仓库清单只保存相对 key；不写 treeUri、documentId、绝对路径或原设备的本地索引。
  本机关联记录为 treeUri + repoId + 显示名，存于可再生本地状态区，与完整 Settings 导出分离；
  URI 字符串被复制到另一台设备也不会授予访问权。
- 搬迁、重装或换设备后，重新选择目录、校验 repoId/版本即可读取全部已提交快照，
  不依赖旧设备 `backup_index`。重建本机哈希缓存，不假定新 URI 对应新仓库，也不盲信旧 URI。
- 本地目录选择器可能包含云端文档提供方；SAF 接口本身不保证数据位于物理本机。
  UI 提示“存储位置由所选文件提供方决定”，验证矩阵区分设备存储、SD 卡/U 盘与第三方 provider。
  不请求全盘文件管理权限；Android 11+ 的目录选择限制交由系统 picker 执行。

官方依据：Android SAF 支持目录树访问、持久 URI 授权及 provider 能力查询；目录移动或删除可能需要重新授权。
<https://developer.android.com/training/data-storage/shared/documents-files>

#### SAF 适配与提交要求

- `SafBackupObjectStore` 用 URI 流读写；不依赖文件随机写、真实路径、原子 rename/MOVE、可靠 mtime
  或可用空间查询。支持顺序流读写与按层列举即能完成基础协议；大小未知明确标记未知。
- `listChildren` 按层查询并建立任务内“逻辑 key → document URI”映射，避免每个对象重复遍历根目录。
  URI 缓存只用于加速，打开失败时重新查询；元数据请求失败不能解释成对象不存在。
  pack 对小文件请求数的改善须在 SAF 真机上测量，保持与其他两端相同协议。
- 对文件名自动加后缀、重名 document、文件/目录类型冲突必须检测。不能按第一个同名 document
  随机取值，也不能在 `foo(1)` 实际创建成功后提交引用 `foo` 的清单；无法消除歧义就中止提交。
  本进程对同仓库写任务串行化；跨设备同时写入若 provider 无冲突保障，不承诺安全并发写入。
- 新对象写完并成功关闭流后才能被本次清单引用；首次写入的对象/pack 读回验证哈希，失败不写完成标记。
  清单写完后读回验证，再写包含其哈希和长度的完成标记；标记也读回确认后才记录本地备份成功时间。
  不承诺文件提供方的物理掉电持久性；恢复仍检查全部内容，残留半份文件不成为有效快照。
- 复用对象沿用 §0.1 的存在性检查。已存在但确认为损坏的同 key 对象不直接原地覆盖；
  中止并报告仓库损坏，后续修复/隔离策略独立设计，避免影响已有快照或读者。
- P1 默认不限保留，不自动 GC。手动删除快照先删除完成标记，再删除清单；删除失败报告实际状态，
  不顺带删除共享对象。第三方 provider 的外部同步与其他写者协调同样属于后续回收协议问题。

#### 恢复与失败处理

- 从已关联或新选择的仓库列出已提交快照，校验版本、完成标记及清单；下载/读取时验证对象，
  在应用内部任务暂存区组装 v2 ZIP，然后复用现有恢复引擎。P1 继承文件覆盖语义与数据库 staged 回滚，
  不引入精确目录镜像恢复；DB 仍为完整快照，源库不存在的状态按 §0.1 单独处理。
- 恢复完整性验证、对象暂存和 ZIP 组装完成前不进入现有破坏性流程。即使仓库在本机，
  也不假定可随机访问它的文档；读取校验与内部暂存受峰值空间预算约束。
- SD 卡/U 盘移除、授权撤销、provider 断连、写满或流关闭失败：终止任务，不更新成功时间；
  已完成的对象可在下次重试时核验复用。提交结果不确定时按同一 snapshotId 查询核实，不能仅凭异常判定未提交。
- 同分区存储时，仓库新增数据与内部暂存竞争同一空间；能查询空间则分别预检并考虑共享分区峰值，
  查不到则显示未知并在流写入失败时清理本任务内部暂存。默认不自动删除目录内的残留物。
- P1 先要求应用保持前台，不承诺后台定时备份、持久断点续传或掉电事务。

#### 验收与实施顺序

- 共用协议层先用 JVM 假 store 验证，再用 SAF 假 provider/仪器测试覆盖重名、后缀改名、未知大小、
  查询失败、半写与关闭失败；不能用普通 `File` 测试替代 provider 行为测试。
- 真机：设备存储首备→新增/修改/删除源文件→第二次备份→旧/新快照均恢复；
  核对实际新增写入字节和复用字节，DB 变化时允许完整 DB 对象重新写入。
- 授权：取消选择、重启后关联仍可用、撤销授权、重新选择原目录、只读仓库、未知非空目录与未知格式拒绝。
- 搬迁：复制完整目录到另一位置/另一设备，清空本地索引后重新关联并恢复；未复制完整目录时报缺失对象。
- 故障：写满、进程被杀、移除可移动存储、同名 document 冲突、损坏对象/清单/标记；
  失败不产生有效半快照，不误删旧快照内容，仓库端失败发生在进入恢复引擎前时本机用户数据不变。
- 执行顺序为“共用协议与任务捕获 → 本地 SAF 首备/增量/恢复闭环 → S3/WebDAV 适配与同一套回归”。
  所有端都沿用 §0.1 的安全约束。此顺序方便先验证协议，不改变三端均属于 P1 的目标；
  实际工期需在 provider 性能与故障测试后重新估算。

## 6. 验证计划（分阶段验收）

| 阶段 | 验收 |
|---|---|
| P0 | ✅ 2026-11-03 单测全绿（全量 2916 项）；真机待验收：备份→恢复后 `backgrounds` 存在；`avatar-frames` 有明确归属；工作区大文件备份 CPU 明显下降；S3 对象 >1000 时旧备份可见；WebDAV 服务端可建多级目录 |
| P1 | JVM 端到端 + 假 store；本地 SAF（含授权/搬迁/故障矩阵，§5.14）及 S3/WebDAV 各完成「首备→增量→恢复」；会话/附件/工作区/背景齐全；旧 ZIP 兼容；中断无有效半快照；新增写入/上传量日志 |
| P1.5 | 保留策略/GC 正确（保留快照可恢复，被删快照消失，引用对象不误删）；预估与实际偏差 < 20% |
| P2 | 三组实验（§5.9）通过；v38/v58 库对象互导 + integrity_check + FTS 重建 + 关键 UI 冒烟；对象增量的上传量 ≤ P1 的 10%（聊天型数据） |

## 7. 分阶段实施与工作量（估算）

| 阶段 | 内容 | 估算 | 可否独立上线 |
|---|---|---|---|
| **P0** | registry 单一真源 + `backgrounds`/`avatar-frames` + 工作区 STORED + S3 翻页 + WebDAV 多级路径/`mkdirs` | 1～2 天 | ✅ **已落地（2026-11-03）**：纯修 bug/基建，无格式变更 |
| **P1** | 共用快照协议 + 捕获/索引 + planner/engine + 拼 ZIP 恢复 + 本地 SAF/S3/WebDAV 三端 + 文件夹关联与快照 UI + 测试 | 待重估：新增 SAF 与 §0.1 正确性约束，原 5～7 天不再适用 | 可分端验证；保留旧 ZIP，默认不做 GC |
| **P1.5** | 保留/GC（手动+自动）、深度清理、pack 参数调优、清单体积埋点、前台服务/长任务健壮性（可选） | 2～3 天 | ✅ |
| **P2** | DB 对象模型 + 启动期合并 + FTS 重建 + 三组实验 + 测试/真机 | 6～10 天（含 1～2 天 spike） | ✅（独立开关；失败可退回 P1 整库对象） |
| **P3** | CDC 固定块实验（仅 P2 后仍嫌 DB 大时）、加密、自动定时备份 | 不排期 | 独立立项 |

> 原 P1 约 1.5 周、P2 累计约 3 周是本地目录与 §0.1 修订前的粗估，现已失效；
> 按三端矩阵与 P2 实验重新估算。原有全量 ZIP 仍可独立使用。

## 8. 风险与开放问题

### 8.1 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 清单体积随文件数膨胀（10 万文件 ≈ 2～4 MB gzip） | 每次备份固定上传/解析成本 | P1 埋点 + 阈值；超过则清单树化（P2，协议不变） |
| R2 | WebDAV 小文件 PUT 放大（首备 1 万文件） | 首备慢/失败 | pack 批量（≤32 MB/批）+ 串行重试；限并发 2～4；错误可重试单批 |
| R3 | 组装 zip 的临时空间 + 时间 | 恢复慢/空间不足 | `BackupSpaceCheck` 预检；P2 原生恢复省掉 |
| R4 | P1 仍每次上传 ~50 MB DB（聊天型用户收益变小） | 增量收益打折 | P2（对象级）；P1 先做 DB 哈希门控（未变不上传，但通常每次都会变） |
| R5 | 中断留下孤儿对象/pack；pack 内单条不可达不拆包 | 远端体积涨 | 已实现多设备 GC（§0.10：全设备可达集 + mtime 宽限期 + 租约 + 删除前复核）；**拆包已实现（§0.12）**：部分引用的 pack 在租约内重打包并改写快照清单，带旧清单备份/回滚 |
| R6 | 多设备共用同一目录 | 快照互相覆盖/保留误删 | `deviceId` 隔离保留；补：GC 已跨设备求可达集 + mtime 宽限期 + 租约串行（§0.10）；仍不做合并 |
| R7 | 恢复后 mtime 重置导致哈希缓存失效 | 首次增量重算全量 | 可接受（与今天读一遍同量级）；UI 说明；「重建索引」入口 |
| R8 | 清单/对象被篡改 | 数据损坏 | 每对象 SHA-256 + 恢复前统一校验；与现有 v2 同信任级；加密独立立项 |
| R9 | 单文件 >2 GB | 恢复拒绝 | 与现有上限一致；预检明确报错 |
| R10 | 进程被杀（无 WorkManager） | 长备份中断 | 已实现 P1.5 前台服务（`BackupForegroundService`，dataSync）：任务挂在应用级作用域，离页/切后台/划掉最近任务不中断，通知栏显示进度与取消；进程被杀仍不续传（不承诺断点续传） |
| R11 | WebDAV 服务端差异（MKCOL/多级路径/大小上限） | 首备失败 | 能力探测（1 B 探针 + 明确错误）；文档列出已测服务端 |
| R12 | P2 跨 schema 对象合并语义 | 静默数据丢失 | 已实现：版本闸门（旧允许/新拒绝）+ 跨 schema 清空/列交集/默认值/跳过表告警 + `foreign_key_check` + 失败整份放弃；改名/改义仍无法自动迁移（§0.7） |
| R13 | 快照清单含敏感信息（路径/大小） | 隐私 | 与现有备份同级（远端已有全量 zip）；加密独立立项 |
| R14 | SAF 授权失效、卷移除、provider 断连/写满 | 本地任务中断 | 状态分型、重新关联、完成标记、成功时间不提前更新；§5.14 |
| R15 | SAF 查询开销、自动改名、重名 document、大小未知 | 慢或引用歧义 | 按层批量查询、任务缓存、严格名称检查、流式校验；有歧义不提交 |
| R16 | 仓库搬迁后 URI 变化/误选其他目录 | 本地索引串用 | repoId 与 URI 分离；新目录验证后关联，缓存重建，不修改源仓库 |
| R17 | 对象恢复 zip 的 DB 条目 STORED，临时 zip ≈ DB 原始大小 | cache 空间紧张（2.8 GB 级 DB） | `BackupSpaceCheck` 按未压缩总量预检；实测 208 MB DB 解压 339 ms；必要时按 DB 大小阈值回退 DEFLATE/level-1（§0.8 取舍 #2） |
| R18 | commit 不再逐对象回读（只校验本地编码产物 + 发布前 stat） | 依赖传输层完整性 | 恢复端仍逐对象解码 + SHA-256，损坏即拒绝恢复；不自带完整性保证的传输层需恢复回读（§0.8 取舍 #1） |

### 8.2 已确认范围与后续决策

1. **范围已确认（2026-11-04）**：本地目录仓库 + WebDAV/S3；用户选择“备份文件夹”，原有完整 ZIP 导入/导出保留，详见 §5.14。用户随后要求开始 P1，完成结论见 §0.5。
2. **P1 是否接受「DB 仍每次整库（~50 MB 压缩）」**，还是一开始就投入 P2 的 DB 对象增量？
3. **保留/GC 已定（2026-11-04，§0.7）**：默认按设备保留最新 10 份（0=不限），提交后自动 prune 清单；
   手动 GC 严格可达性、失败即停；跨设备回收已实现（2026-11-05，§0.10：mtime 宽限期 + 仓库租约 +
   删除前复核，仍为手动触发）。
4. **多设备**：已支持多台设备备份到同一 WebDAV/S3/SAF 目录并互不干扰：保留/回收按 `deviceId` 隔离，
   GC 跨所有设备求可达集并用租约/宽限期串行（§0.10）；仍不做合并（§1.3）。
5. **加密**是否要并入本批（否则快照里的 DB/settings 与现有 zip 一样明文）？
6. **是否要「把快照导出成完整 zip」**：已实现（2026-11-05，§0.11）——复用恢复组装链路输出 v2 ZIP，
   可走 App 自己的完整 ZIP 导入入口；旧版 App 仅数据库版本兼容时可导入。
7. **实现时机**：P1 已完成（2026-11-04，见 §0.2–§0.5）；真实 provider/云端与恢复重启矩阵作为后续验收项记录在 §0.4，不阻塞 P1 完成。P2 首版及后续三项（跨 schema、FTS 重建进度 UX、对象 GC/保留）已落地（§0.6–§0.7，工作树未提交）。

## 9. 明确不做（与既有决策对齐）

- 不做全量逐文件 SHA-256 进**全量 zip**（本稿只在增量仓库做逐文件哈希；
  旧 zip 路径的 manifest v2 不变，`D/completed/design-backup-manifest-and-safety.md:35-36,94`）。
- 不做 S3/WebDAV 恢复前预览 UI（本稿的快照列表提供摘要，但不解析全量）；
  清单体积许可时可在 P1.5 加「恢复前预览快照内容」。
- 不做自动定时备份（独立立项，`D/completed/design-backup-manifest-and-safety.md:97`）。
- 不做 `tool_outputs`/rootfs/exports 进备份（`A/data/sync/BackupItem.kt:96-108`）。
- 不做跨会话/全局附件去重（只在单次导出内，`D/completed/design-conversation-import-export.md:271-272`）；
  本稿的**内容寻址去重是「同一内容不重复上传」**，不改变恢复时的文件归属与删除语义。
- 不做 rsync/双向同步、不做跨设备合并。
- 不做本地链式增量 ZIP 或原地更新单个大型备份容器；本地增量采用用户选择目录的仓库模式（§5.14）。
- 不做备份加密（独立立项；若做，加在快照清单/对象层之上，不改本稿协议）。

## 附录 A：关键证据索引 / 调研底稿

- 现存实现：`A/data/sync/BackupArchiveEngine.kt`、`BackupManifest.kt`、`BackupItem.kt`、
  `AppFilesPacking.kt`、`WorkspaceBackupHelper.kt`、`BackupZipStrategy.kt`、`BackupProgress.kt`；
  `A/data/sync/S3Sync.kt`、`s3/S3Client.kt`；`A/data/sync/webdav/WebDavSync.kt`、`WebDavClient.kt`。
- 数据实测：`_measure/m.db`（115,393,536 B / v38）、`_measure/settings.pb`（1,451,512 B）；
  本稿 §2.1 的 dbstat/列长数据由只读查询得到（脚本在 `.tmp/incremental-backup/data-delta.md` 描述的
  `dbstat` + `length(cast(col as blob))` 方法；`.tmp/` 不入库）。
- 相关设计稿：`D/completed/design-backup-workspace-files.md`、`design-backup-manifest-and-safety.md`、
  `design-backup-staged-restore.md`、`design-conversation-import-export.md`、`design-export-retention.md`。
- 上游：rikkahub#2280 <https://github.com/rikkahub/rikkahub/issues/2280>；
  上游备份文档（文案写「增量」、实现为全量 zip）<https://docs.rikka-ai.com/zh/settings/backup>。
- 同类实现基准：restic/borg/rsync/AWS CLI `s3 sync`/Chatbox/Cherry Studio 的对比见
  `.tmp/incremental-backup/benchmark.md`（不入库）；本稿 §4 已提炼结论。
- 本仓安全属性：`A/data/sync/PendingRestoreStore.kt`、`A/RikkaHubApp.kt:167-184,260-268`、
  `A/data/sync/LegacyBackupConverter.kt:157-209`。
