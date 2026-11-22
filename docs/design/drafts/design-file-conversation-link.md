# 文件管理页：显示文件所属会话并跳转 设计稿

> 状态：**Phase A+B 已实施，真机验证通过（debug 包，2026-11-22）**——A：扫描 567 路径/810ms、单会话行+跳转已验；B：v79→80 迁移成功、回填 570 行、展示层切 `message_file_ref` LEFT JOIN 已验。**多会话 Sheet 分支未真机验**（无多引用数据）；写路径稀疏合并/删除清理有 FileRefSyncTest（Fake DAO）钉住语义。§7.3 单测清单已全部落地（扫描提取含 Tool 嵌套/中文文件名/http+data 排除；回填重写/合并幂等；diff 重写幂等+稀疏语义），同步核心抽为 `FileRefSync`（仓库/回填/兜底三处共享）。Phase A `f1ce06ce4`、Phase B `daf709f67d`。
> 实测备注：验证必须用 debug 包 `app.androidrik.rikkahub.debug`（release 包无后缀且 not debuggable，UI 改动在 release 上完全不可见）。
> 关联：`design-storage-management.md`（存储管理 Phase 3 只读行未做，本稿的「未关联文件」展示可与其对齐）· `design-export-retention.md`（exports 生命周期）
> 硬约束：不动 IME 宿主拓扑；新表迁移走 v79→v80；扫描/回填一律后台 IO + 单事务（套 `rebuildAllIndexes` 骨架），禁止启动阻塞。

---

## 0. 拍板记录

| # | 拍板点 | 决定 |
|---|--------|------|
| 1 | 关联数据来源 | **双轨**：Phase A 扫描兜底（存量/漏网数据立即可见）+ Phase B 写路径精确落库（新数据即时精确）；展示层统一读关联表，扫描仅作回填与兜底 |
| 2 | 多会话归属 | 文件可被多会话引用：单会话直接显示会话名；多会话显示「N 个会话」点击弹列表（BottomSheet） |
| 3 | 跳转方式 | 复用 `navController.navigate(Screen.Chat(conversationId))`（同 `ScheduledTaskDetailPage.kt:110` / `AgentRunDetailPage.kt:163` 先例），页面用 `LocalNavController.current` 取导航器，不改页面签名 |

---

## 1. 现状事实（调研结论）

1. **文件管理页**：`SettingFilesPage.kt:60`，无 VM、无 navController，数据 `filesManager.observe(folder)` → Room `managed_files` 表；仅展示 `upload/`、`exports/` 两个目录（FilterChip 切换），两列瀑布流 `FileItem`（点击分享、长按多选）。
2. **消息内文件引用格式**：`Image/Video/Audio/Document` 分片的 `url` 字段存的是 **`file:///data/.../files/upload/<uuid>.<ext>` 绝对 URI**（写入口统一经 `FilesManager.createChatFilesByContents/ByByteArrays`，`file.toUri()` 产出）；`/upload/xxx` 相对路径只是发送时的 prompt 文本（`DocumentAsPromptTransformer.kt:47`），不是存储格式；`http(s)`/`data:`/`content:` 不落 `managed_files`。工具产出（workspace `read_image`、`LocalTools`）也以 `file://` Image 分片进消息。
3. **会话与文件之间无任何关联表**：关联只存在于 `message_node.messages` JSON 字符串内。已有先例 `MessageNodeDAO.hasFileReference`（`MessageNodeDAO.kt:144`，`instr(messages, :encodedFileUrl)` 子串匹配）证明 JSON 子串匹配是既有惯例，但它是布尔查询、不返回 conversationId。
4. **统一写收口**：`ConversationRepository.updateConversation`（`ConversationRepository.kt:648-690`）——`withTransaction` 内同时可拿 `conversation.id` 与全部 `messageNodes` 的 parts。流式检查点 `checkpointGeneratingNode`（:711）只写单节点。调用点：`ChatService.saveConversation`（`ChatService.kt:3491`，生成收尾 :2261/:2281）。
5. **DB**：v79（`AppDatabase.kt:99`）；AutoMigration 惯例（78→79 在列），手写迁移 `Migration_71_72` 风格（JSON1 回填 + `DatabaseMigrationTracker`）。
6. **全量遍历骨架**：`ConversationRepository.rebuildAllIndexes`（:806-821）——`getAllIds()` 逐会话加载 + 单事务合并提交（真机 269 次提交约 10s 开销的教训），触发时机走「pending 标记 + Ready 后后台重建」（`RikkaHubApp.kt:290-311` FTS 先例）。
7. **rename 安全**：`FilesManager.rename` 只改 `displayName`，`relativePath` 不变 → 关联键用 relativePath 不会被重命名破坏。

## 2. 目标与非目标

**目标**

1. 文件管理页（upload/exports 两个 tab）每个文件卡片显示其所属会话（名称缩略 + 数量）。
2. 点击跳转：单会话直接 `Screen.Chat(id)`；多会话先弹会话列表再跳。
3. 关联数据 Phase B 起持久化，存量数据一次性后台回填，之后增量维护。

**非目标**

- 不做文件管理页的 VM 重构/列表项改版（仅加一行会话信息，`FileItem` 布局最小侵入）。
- 不覆盖 `tool_outputs/`、`images/` 等非列表目录（tool_outputs 启动即清、images 走生图记录页，各自有更直接的归属来源）。
- 不做「从会话页反查文件」的反向入口。
- 不处理会话删除后跳转 404 以外的清理语义（会话删除时其 `message_node` 行级联删除，关联表按会话 id 清理即可，见 §4.3）。

## 3. Phase A：扫描式关联（无 schema 变更）

> 目的：不动 DB 就能让存量数据立即可见；后续作为 Phase B 的**回填引擎**与**兜底校验**复用，不是一次性弃子。

### 3.1 扫描器 `FileConversationScanner`

```kotlin
// app/.../data/files/FileConversationScanner.kt
class FileConversationScanner(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val context: Context,
) {
    // 一次全表读 message_node(conversation_id, messages)，
    // 逐行正则提取 file:// 子串 → FileUtils.getRelativePathInFilesDir 归一成
    // relativePath → Map<relativePath, Set<conversationId>>
    suspend fun scan(): Map<String, Set<String>>
}
```

- 提取用正则 `"file://[^"\\]*"`（JSON 转义后 `"` 与 `\\` 是路径终止符），**不**逐消息 kotlinx 反序列化——`hasFileReference` 已证明子串匹配惯例，且全量反序列化成本高得多。
- 归一：`file://…/files/upload/x.png` → `upload/x.png`（`FileUtils.getRelativePathInFilesDir` 现成）。归一失败的（外部路径）丢弃。
- 结果只保留命中 `managed_files.relative_path` 的条目（在展示层 JOIN 时过滤亦可，见 3.2）。
- 耗时控制：读在 `Dispatchers.IO` 单事务（Room `withTransaction` 包查询，套 rebuildAllIndexes 的 WAL 教训）；VM 内存缓存；进页面后台触发，扫完前卡片显示「…」不显示会话行。

### 3.2 Phase A 展示（VM 引入）

- `SettingFilesPage` 引入轻量 VM `SettingFilesVM`（页面现状无 VM，直接 koinInject 数据流；引入 VM 仅为持有扫描缓存与导航回调，不改数据源）。
- 卡片 `FileItem` 下方加一行 `supportingContent`：单会话 → 会话标题（从 `ConversationDAO` 批量取 title）；多会话 → `N 个会话`。
  - **遵守 ListItem supportingContent 槽位陷阱**（agents.md）：条件行必须 `Column { }` 包裹，不得与主控件平级。
- 点击：单会话直接 navigate；多会话 BottomSheet 列表（会话标题 + 时间），选中跳转。**文件管理页是设置页**（无 TextField 嵌 Lazy 坑），Sheet 内纯列表无输入，不触发 IME 拓扑约束。

## 4. Phase B：关联表 + 写路径落库（v79→v80）

### 4.1 Schema：新表 `message_file_ref`

```kotlin
@Entity(
    tableName = "message_file_ref",
    primaryKeys = ["conversation_id", "relative_path"],
)
data class MessageFileRefEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("relative_path") val relativePath: String,
    @ColumnInfo("first_seen_at") val firstSeenAt: Long,
)
```

- **不设 FK**：`managed_files` 行可能被 syncFolder/清理重插（id 漂移），关联按 `relative_path` 字符串对齐（与 `hasFileReference` 的匹配粒度一致）；孤儿关联（文件已删）在展示层 LEFT JOIN 自然消失，无需维护。
- 迁移：**AutoMigration 79→80**（纯建表、无回填，`NOT NULL` 免 spec）。存量回填不做在 Migration 里（JSON 全表解析放迁移里会拖慢启动），改走运行时回填（4.4）。

### 4.2 写路径：`updateConversation` 收口

在 `ConversationRepository.updateConversation` 事务内（`ConversationRepository.kt:648`）追加：

1. 遍历 `conversation.messageNodes` 全部 messages 的 parts（**含 Tool 分片内嵌内容**——workspace 读图输出在 tool result 里，正则同 Phase A 扫描器，提取逻辑抽成共享函数放 `FileConversationScanner` 伴生对象），得本会话引用的 relativePath 集合。
2. Diff：`DELETE FROM message_file_ref WHERE conversation_id = ?` + 批量 INSERT 新集合（会话消息可被编辑/删除分支，全量重写该会话的关联最简单且事务内幂等）。
3. `checkpointGeneratingNode`（:711）**不动**：流式半截消息不落关联，最终 `saveConversation` → `updateConversation` 收口时补齐；关联晚 2 秒可见无语义影响。
4. 备份恢复：关联表随数据库一起备份/恢复（`message_file_ref` 在同库，`BackupArchiveEngine` 按库打包），无需单独登记。

### 4.3 删除路径

- 会话删除：`message_node` 有 `ForeignKey.CASCADE`，但 `message_file_ref` 不设 FK → 需显式清理。在 `ConversationRepository` 现有删除会话入口追加 `messageFileRefDAO.deleteByConversation(id)`（事务内）。**排查点**：会话删除的收口方法（级联删除走 Room onDelete，repository 侧删 conversation 行处即插桩点）。
- 文件删除：不清理关联（孤儿关联在 LEFT JOIN 展示层不可见）；若文件重建同名（UUID 名，概率趋零）关联自动复活，语义无害。

### 4.4 存量回填：一次性后台 Job

- 套 FTS pending 标记模式（`RikkaHubApp.rebuildSearchIndexIfPending` 先例）：DataStore 布尔标记 `fileRefBackfillDone`，默认 false；App Ready 后后台 IO 执行 `FileConversationScanner.scan()` → 批量 INSERT `message_file_ref`（单事务）→ 置 true。
- 失败可重试（标记不置 true）；不提供用户可见进度 UI（一次性、秒级，静默即可；会话量极大时按 rebuildAllIndexes 教训保持单事务即可）。
- Phase A 的扫描器此处复用为回填引擎。

### 4.5 Phase B 后的展示层

- 展示统一改读 `managed_files LEFT JOIN message_file_ref`（`ManagedFileDAO` 新增查询，返回 `(ManagedFileEntity, List<conversationId>)` 或独立 Flow）+ 批量取会话标题。
- 扫描器从「实时数据源」降级为**兜底**：关联表查不到且扫描器命中时（理论上只发生在回填失败/写路径 bug），Phase A 的 UI 逻辑保留为 fallback，减少 Phase B 缺口。

## 5. UI 细节

1. `FileItem` 会话行：`Lucide.MessageCircle` + 会话标题（单）/`N 个会话`（多）/不显示（未关联，如 exports 手动导出产物）。
2. 多会话 Sheet：`ModalBottomSheet` + `Column+verticalScroll`（常驻子项，无输入框），列表项 = 会话标题 + 更新时间，点击 `navigate(Screen.Chat(id))` 后关 Sheet。
3. 跳转目标会话已被删除（孤儿关联残留窗口）：`Screen.Chat(id)` 进入后页面自会显示空态，无需预检（与通知跳转同语义）。
4. 导航来源：`LocalNavController.current`（`ScheduledTaskDetailPage.kt:61` 同款），不改 `SettingFilesPage` 签名。

## 6. 边界与风险

| 风险 | 处置 |
|------|------|
| 扫描正则漏配（JSON 内路径转义变体） | 与 `hasFileReference` 的 `JsonInstant.encodeToString` 编码口径对齐：匹配时用 `file://` + 路径直到 `"` 或 `\`；单测覆盖含中文/空格文件名（UUID 文件名实际是 ASCII + 原始扩展名，风险低） |
| 大库扫描耗时（message_node 全表） | 后台 IO + 进页面非阻塞 + VM 缓存；实测真机量级后决定是否加 LIMIT/分页（首版不做） |
| `updateConversation` 高频路径加压 | 提取是正则子串匹配非反序列化，且事务内批量 INSERT；ChatService 一轮只调 1-2 次，量级可控 |
| exports 目录多为「无消息引用」产物 | 未关联就显示空，语义正确（导出文件本就不在消息里）；若后续想支持「导出来源会话」，另立字段（非本稿） |
| 分叉树（多分支消息） | 扫描/收口遍历**全部** messages（含未选中分支），引用了就算——文件被任一分支引用即归属该会话 |

## 7. 实施顺序

1. **Phase A**（可独立提交）：扫描器 + VM + FileItem 会话行 + 多会话 Sheet + 跳转。验证：真机文件管理页可见会话名、点击落入对应会话、多引用文件弹列表。
2. **Phase B**（可独立提交）：AutoMigration 80 + `message_file_ref` + `updateConversation` 收口 + 会话删除清理 + 回填 Job + 展示层切 JOIN + 扫描器降级 fallback。
3. 单测：扫描提取函数（含 Tool 分片嵌套、中文文件名、http/data 排除）；回填幂等；`updateConversation` diff 重写幂等。

## 8. 验证计划

- 真机（6c841f95）：发图文消息 → 文件管理页立即（Phase B）/刷新后（Phase A）显示该会话；删除会话后关联消失（Phase B）；备份恢复后关联保留；重命名文件后关联不变。
- 边界：exports 文件显示「未关联」；多分支会话任一分支引用均可归属。
