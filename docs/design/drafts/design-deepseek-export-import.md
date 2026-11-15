# design-deepseek-export-import：DeepSeek 官方导出包导入

> 状态：implemented（编译 + 单测 + assembleDebug 通过，待真机全量导入验证）
> 参考：官方 web 端同格式参考实现 `F:\build\projects\chat-demo`（`src/services/chat/history/`，export_format_version 3）；
> 实测样本 `deepseek_data-2026-11-15.zip`（570MB / 1071 会话 / 无图片原件）。
> 复用管线：备份页导入（`design-conversation-import-export.md` §八 的 ImportFormat 描述符体系）。

## 一、目标与非目标

**目标**：用户在备份页选择 DeepSeek 官方导出的 `deepseek_data-*.zip`，把全部会话（含分支树）导入为本应用 Conversation，归属于用户指定的助手。

**非目标**：
- 不做 DeepSeek API 协议适配（provider 层已有 deepseek 适配，与本设计无关）；
- 不做导出（DeepSeek 数据不出本应用侧再导回官方）；
- 不解析 `user.json` 账号信息；
- 不还原 `READ_LINK.signature`（服务端私有签名，物理上无法跨端续传正文）、
  `accumulated_token_usage`（沿分支累计的上下文 token，与 TokenUsage 的 prompt/completion 口径不可对齐）。

## 二、源格式规格（v3）

### 2.1 ZIP 条目布局

```
manifest.json                                   {"export_format_version":3}，必须是首个条目
user.json                                       账号信息，跳过（可位于 manifest 与会话之间）
chat_sessions/{session_id}.json                 全部会话 JSON（含消息树）
chat_session_index.json                         会话索引（位置不定：官方写在会话后、图片前；导入需容忍任意位置）
chat_sessions/{session_id}_files/{file_id}_{file_name}   图片原件（本次样本中不存在）
```

实测样本共 1074 条目 = manifest 1 + user 1 + index 1 + 会话 1071；单会话最大数百 KB，消息数最大 627。

### 2.2 SessionFile / ChatMessage 结构

```
SessionFile { id, title|null, pinned, current_message_id, inserted_at, updated_at, messages[] }
ChatMessage { id(会话内自增int), parent_id|null, role(USER|ASSISTANT), status, thinking_enabled,
              search_enabled, accumulated_token_usage, inserted_at, fragments[] }
```

官方不变式（chat-demo README）：父 id < 子 id、角色沿分支交替、根为提问。**导入侧只做防御性校验，不把它当解析前提**（见 §7.3）。

`current_message_id` 是线性化唯一依据（= 当前分支叶子）。

### 2.3 fragments 类型（13 种）

| type | 载荷 | 语义 |
|---|---|---|
| `REQUEST` | content | 用户提问 |
| `RESPONSE` | content | 模型回答（引用标记已剥除） |
| `THINK` | content, elapsed_secs | 思考正文 + 耗时秒 |
| `TEMPLATE_RESPONSE` | content | 服务端模板（过载/过滤/超长等），**不进上下文** |
| `TIP` | content, style | 兜底提示条（文案固定为导出时的英文） |
| `CONTENT_RISK` | content, style | 内容风险提示条 |
| `PARTIAL_FILE_READ` | ratio | 文件截断读取提示 |
| `TOOL_SEARCH` | queries, results[], status? | 联网搜索（含结果 url/title/snippet） |
| `TOOL_OPEN` | status, url?, title?… | 边想边搜打开页面 |
| `TOOL_FIND` | status, pattern? | 页内查找 |
| `READ_LINK` | results[]{signature, url?, title?} | 阅读链接（signature 服务端恢复正文） |
| `FILE` | files[]{id, file_name, file_size, export_path, is_image, …} | 附件（非图片仅元数据） |
| `TOOL_IMAGE_SEARCH` | status, results[]… | 图搜（README 明示无展示对应，官方直接丢弃） |

时间均为 UTC RFC3339 `Z` 结尾、小数秒精度不固定；可空字段显式 `null`、可选字段可缺省。

### 2.4 版本门禁与校验策略

照抄 chat-demo `checkManifest` 语义（schema.ts L230）：
- `export_format_version > 3` → 拒绝（提示「导出格式更新，请升级 App」）；
- `≠ 3` → 拒绝（不识别的格式）。

校验容错对齐官方：**容器/元数据/消息树结构不合法 → 该会话整体失败（计数，不中止整包）；单个 fragment 解析失败 → 丢弃该片段**。比官方宽松的点：官方导入是「全量替换」，我们是「旁路追加」，单会话失败不应毁掉整次导入。

## 三、现有管线与挂接点

备份页导入已有 4 种格式（[ImportFormat.kt](../../app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/ImportFormat.kt)）：LOCAL_BACKUP / CONVERSATION / CHATBOX / CHERRY_STUDIO，UI 卡片由 `ImportFormatSpecs` 描述符自动渲染。新增一种格式 = 加一个枚举值 + 一个 spec + 一个解析分支。

最贴近的对齐对象是 **CHATBOX**（多会话、归属指定助手、流式解析）：

- [ChatboxImporter.importStreaming](../../app/src/main/java/app/androidrik/rikkahub/data/sync/importer/ChatboxImporter.kt#L98-L133)：`onConversation` 逐会话回调，由调用方落库；
- [BackupVM.restoreFromChatBox](../../app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/BackupVM.kt#L571-L617)：`existsConversationById` 去重 + `insertConversation` 逐会话插入 + 结果统计。

## 四、架构与文件级改动

| 文件 | 改动 |
|---|---|
| `data/sync/importer/DeepSeekTypes.kt`（新） | v3 格式的 Kotlin 数据类 + 手写宽松解析（`JsonElement` 取值，风格对齐 ChatboxImporter；不用 kotlinx 严格反序列化——官方格式「可选字段可缺省」用 `ignoreUnknownKeys` 也能做，但 fragment 是 13 种的带判别字段 union，手写解析容错粒度更细，单 fragment 失败可丢弃） |
| `data/sync/importer/DeepSeekImporter.kt`（新） | `importStreaming(zip, assistantId, imageMaterializer, onConversation)`：流式读 ZIP → 逐会话解析 → 产出 Conversation |
| `ui/pages/backup/ImportFormat.kt` | 枚举加 `DEEPSEEK` + spec（mimes=`application/zip`，needsAssistant=true，needsRestartDialog=false） |
| `ui/pages/backup/BackupVM.kt` | `restoreFromDeepSeek(file, targetAssistantId)`，形态对齐 `restoreFromChatBox`；进度回调 |
| `ui/pages/backup/tabs/ImportExportTab.kt` | 无需改（卡片自动渲染）；接入进度流 + 结果摘要对话框 |
| `app/src/main/res/values*/strings.xml` | 卡片文案 + 结果摘要文案（三语，走 locale-tui） |
| `app/src/test/.../DeepSeekImporterTest.kt`（新） | 见 §11 |

模块归属：全部在 app 模块（导入格式是应用层关注点，ai/common 不动）。

## 五、ID 策略与重复导入检测

对齐 ChatboxImporter 的既有约定（「Stable UUIDs are derived from Chatbox ids so importing the same file again can skip existing conversations」）：

- **会话 id** = `UUIDv5(namespace, "deepseek-session:" + {session_id})`（session_id 本身是 UUID，直接 v5 拼接即可）；
- **消息节点 id** = `UUIDv5(namespace, "deepseek-node:" + {session_id} + ":" + {message_id})`；
- **parent_id** = 由 §6 的映射表把 DeepSeek int 父 id 换算成上述节点 Uuid；`parent_id: null` → 节点根。

性质：确定性、跨次导入稳定 → `existsConversationById` 天然幂等，重复导入同一 zip 时已存在会话全部跳过；两个用户各自导出自己的包也不会撞 id（v5 输入含各自 session uuid）。

实现注意：`java.util.UUID.nameUUIDFromBytes` 是 MD5-based v3 UUID（非 v5），ChatboxImporter 已在用它——**沿用 nameUUIDFromBytes 即可**，不必引入 v5 依赖；关键是「同一输入恒得同一输出」。

## 六、映射矩阵

### 6.1 会话层

| DeepSeek | Conversation | 备注 |
|---|---|---|
| `id` | `id` | §5 确定性 Uuid |
| `title` | `title` | null → `""` |
| `pinned` | `isPinned` | |
| `inserted_at` | `createAt` | |
| `updated_at` | `updateAt` / `lastMessageAt` | lastMessageAt 取激活路径末消息 inserted_at |
| — | `assistantId` | 用户选择的目标助手 |
| — | `activeLeafNodeId` | §7.2 |

### 6.2 消息树层（1 DeepSeek message = 1 MessageNode）

RikkaHub 树形分支（design-conversation-tree-branching.md）以 `parentId` 为权威、兄弟节点即分支，与 DeepSeek 树同构，**1:1 映射不做折叠**（不并入 `MessageNode.messages` 的 alternatives——那是同节点多候选的展示结构，混树会丢孙代）：

| DeepSeek | MessageNode / UIMessage | 备注 |
|---|---|---|
| `id` | `MessageNode.id` | §5 |
| `parent_id` | `MessageNode.parentId` | 映射后 |
| `role` | `UIMessage.role` | USER/ASSISTANT 直接对位 |
| `status` | §6.4 | |
| `inserted_at` | `UIMessage.createdAt` | UTC → LocalDateTime |
| fragments | parts | §6.3 |
| `thinking_enabled` | 丢弃 | 每轮开关对跨 provider 重发无意义 |
| `search_enabled` | 丢弃 | 同上 |
| `accumulated_token_usage` | 丢弃 | 口径不可对齐（§一非目标） |

### 6.3 fragments → parts

| fragment | 映射 | 默认 |
|---|---|---|
| `REQUEST` | `Text`（user 消息唯一 part） | 保留 |
| `RESPONSE` | `Text` | 保留 |
| `THINK` | `Reasoning(reasoning=content)`；`elapsed_secs` 存 `metadata {"deepseekElapsedSecs": x}`；`createdAt/finishedAt` 用消息 inserted_at 填充 | 保留。无 signature，重发给任意 provider 无校验障碍（对照本仓 d6f8140d1 的签名推理块禁编辑语义：导入块天然无签名，不设禁编） |
| `FILE`（`is_image=true` 且 `export_path` 有原件） | `Image(url="file://…")`，物化见 §8 | 保留 |
| `FILE`（图片但原件缺失） | 计入 missingAttachments，不出 part | 跳过 |
| `FILE`（非图片） | **无 part**（zip 只有元数据无本体） | 计数报告 |
| `TEMPLATE_RESPONSE` | **丢弃**（§7.1） | 丢弃 |
| `TIP` / `CONTENT_RISK` / `PARTIAL_FILE_READ` / `TOOL_IMAGE_SEARCH` | **丢弃**（官方亦无展示对应/无续传价值） | 丢弃 |
| `TOOL_SEARCH` / `TOOL_OPEN` / `TOOL_FIND` / `READ_LINK` | **默认丢弃**；可选「保留搜索轨迹」见 §7.1 | 丢弃 |

**关键依据——为什么默认丢弃而不是转 Notice**：本仓 Notice 不是「不入上下文」载体。主请求装配链 `PromptAssembly.kt:401` 对全部消息调 `expandNotices()`（ai/ui/Notices.kt），Notice part 会展开成 SYSTEM + ExternalNotice 注解的合成消息**进入主请求**（仅辅助生成 `messagesForAuxGeneration` 才过滤）。官方语义「模板/搜索所在轮次不进上下文」在本仓没有现成载体，最忠实的实现就是不导入。

### 6.4 status 映射

| DeepSeek status | 处理 |
|---|---|
| `FINISHED` | 正常保留，`finishReason = null` |
| `INCOMPLETE` / `WIP` | 保留为正文，`finishIncomplete = true`（本仓「裸断流」标记的语义正解：未正常收尾） |
| `FAILED` / `TIMEOUT` / `CONTEXT_LENGTH_EXCEEDED` | 该消息**丢弃**（与其携带的 TEMPLATE_RESPONSE 同轮同弃）；其父提问节点保留，成为激活路径的合法叶尾——语义上等于「问了但没答」，续聊自然接上 |
| `CONTENT_FILTER` | 同上丢弃（官方语义「拒绝轮不进上下文、不可重新生成」） |

丢弃计数进导入结果摘要（用户可见「跳过 N 条失败回复」）。

## 七、三个设计决策

### 7.1 错误轮次与搜索轨迹：默认丢弃 + 可选保留

- **默认丢弃**（理由见 §6.3 关键依据），结果摘要报告丢弃量；
- **可选开关「保留搜索轨迹为正文」**：开启时 `TOOL_SEARCH` 的 queries + 结果 title/url 序列化为一段 `Text`（格式：`> 🔍 {queries.joinToString()} → {title} ({url})` 逐行），`READ_LINK` 保留 url/title 行、**signature 永不落库**。用户显式开启即接受其进入上下文。默认关闭。

不做 `ServerTool` 映射：ServerTool parts 会在请求转换中被序列化回 provider，DeepSeek 搜索块发给 OpenAI 兼容端点是未知字段，风险面大收益小；列为 P2 候选（届时按 googleSearch/urlContext 的 ServerTool 形状专项设计）。

### 7.2 激活路径定位

`current_message_id` → 节点 Uuid → `activeLeafNodeId`。防御：

- `current_message_id` 指向的节点不存在（损坏/校验丢弃）→ 兜底取「id 最大的保留节点」；
- 会话没有 ASSISTANT 节点（只有一条孤提问）→ 正常导入，提问即叶尾。

### 7.3 树完整性防御

官方不变式（父 id < 子 id 等）只作**加速假设不作正确性前提**：

- 构树前先按 `id → node` 建索引；`parent_id` 查不到（孤儿）→ 按 `healMissingParents` 同款策略：**按创建序回填前驱**（本仓 P0 修复的存量坑：parent_id 缺失按创建序补前驱，否则边界子树计算会丢正文——导入侧直接在源头保证不产生这种行）；
- 整个会话 messages 非法（非数组/空）→ 会话级失败，计数不中止。

### 7.4 「物理上无法跨端还原」项：显式丢弃 + 逐项计数 + 完成提示

`READ_LINK` 的 `signature` 与 `accumulated_token_usage` 属于**物理上无法跨端还原**的数据：
前者是 DeepSeek 服务端私有签名（只有官方后端能凭它取回网页正文，换端/换账号即失效），
后者是沿分支累计的上下文 token（累加口径，与 `TokenUsage.prompt/completion` 不可对齐）。
两者**一律显式丢弃，不做任何近似还原**，并在导入完成摘要里逐项提示——否则用户会把
「阅读链接只剩标题」「统计页用量为 0」当成导入 bug。

| 源数据 | 丢弃点 | 计数 | 完成提示文案（zh） |
|---|---|---|---|
| `READ_LINK.results[].signature` | [DeepSeekImporter](../../app/src/main/java/app/androidrik/rikkahub/data/sync/importer/DeepSeekImporter.kt) `ReadLink` 分支：signature 永不落库；开启「保留搜索轨迹」时也只留 `title`/`url` 纯文本 | `droppedReadLinks`（READ_LINK 块数，**与搜索轨迹开关无关**） | 「N 个网页阅读结果仅保留标题/链接：正文依赖 DeepSeek 服务端签名，无法跨端还原。」 |
| `accumulated_token_usage > 0` 的消息 | 不写入 `UIMessage.usage`（保持 null）；统计页不显示导入会话的历史用量 | `droppedTokenUsageMessages`（仅统计**保留下来**的消息，失败轮已整条丢弃不重复计） | 「N 条消息的历史 token 用量已丢弃：用量统计不包含导入的会话。」 |

补充约定：

1. **README 级口径一致**：`keepSearchTrails` 只影响「是否留标题/链接文本」，不改变
   「正文不可还原」这一事实，因此 `droppedReadLinks` 在两种模式下都计数；
2. **失败轮不重复计**：`status ∈ {FAILED, TIMEOUT, CONTEXT_LENGTH_EXCEEDED, CONTENT_FILTER}`
   的消息整条丢弃（§6.4），其 token 用量随节点消失，不再进 `droppedTokenUsageMessages`；
3. **提示呈现为「只在有值时出现」**：两项计数为 0 时不显示对应行，避免正常导入也挂一串
   「无法还原」噪音；确认对话框里另有一行静态说明（`..._limits`）覆盖全部不可还原项；
4. **不写 Notice、不写注解**：本仓 Notice 会经 `expandNotices()` 进入主请求上下文
   （§6.3 关键依据），「提示用户」只能发生在导入完成的 UI 摘要，不能落在消息体上。


## 八、附件与图片物化

对齐 `commitConversationImport` 的既有纪律（BackupVM.kt §三注释）：

- zip 条目 `chat_sessions/{session}_files/{file_id}_{file_name}` 按 `FILE.export_path` 匹配；
- 物化走 `FilesManager.saveManagedFromBytes(folder=UPLOAD)`（必须登记 managed_files，否则文件管理页把 UUID 当显示名）；
- **生成新文件名真拷贝**，不复用原路径：`deleteConversation` 按 part URL 删盘，复用会造成删副本连带删原件；
- MIME 由字节识别补充，识别不出仍按图片导入（对齐官方）；条目缺失/校验不符 → 该图跳过计数，URL 不改写；
- `zipReader` 级别的 CRC 校验不做（java.util.zip 内建 CRC on read），条目名比较按 NFC 归一（对齐官方；Kotlin 侧 `java.text.Normalizer`）。

## 九、流式导入与性能

样本规模：570MB zip / 1071 会话 / 最大 627 消息。纪律：

1. **不整包进内存**：`ZipInputStream` 顺序读条目，manifest 首条目校验版本后，逐条目处理、即用即弃。`chat_session_index.json` 仅用于导入前预览（可选阶段，见 §10），不参与逐会话落库；
2. **会话 JSON 流式解析**：`JsonReader`（android.util）逐字段读，或 kotlinx `JsonElement`——单会话 ≤ 数百 KB，JsonElement 内存可接受，优先 kotlinx（代码更短），超阈值（>5MB 单会话，实测不存在）再退 JsonReader；
3. **逐会话事务**：`onConversation` 回调里 `insertConversation`（与 Chatbox 相同），单会话失败回滚该会话不影响其余；
4. **图片延迟物化**：图片条目出现在会话 JSON 之后（官方布局），采用两遍法——第一遍只记录 `export_path → 条目位置` 索引不读字节，会话落库后第二遍按需解压物化（本次样本无图片，两遍法主要保障有图大包不被 IO 卡死）；
5. **UI 进度**：按会话计数 emit 进度（`已导入 n / 总数 N`），N 来自 index 或计数器；
6. **与长会话链路协同**：导入的会话动辄数百节点，落库后首次打开即走稀疏加载/`pathNodes()` 索引缓存链路（design-conversation-lazy-load.md），无需导入侧特殊处理——但**导入完成的会话不预载**（列表轻投影即可）。

## 十、UI 流程

复用备份页既有模式（对齐 CHATBOX 卡片）：

1. 卡片「从 DeepSeek 导入」（`needsAssistant=true`）：选文件前先弹助手选择（`ImportTargetAssistantDialog` 既有组件）；
2. 选文件 → 快速预检（读 manifest 校验版本 + 可选读 index 显示「检测到 N 个会话」）→ 确认对话框（含「重复导入将跳过已存在的会话」提示）；
3. 导入中：进度行（对齐 `BackupProgressRow`），期间禁止触发其他备份任务；
4. 完成摘要（对话框/toast）：
   - 导入会话数 / 跳过（已存在）数 / 失败会话数；
   - 丢弃统计：失败回复条数、搜索块数、模板回复条数、非图片附件数、缺失图片数；
   - **不可还原项（§7.4）**：READ_LINK 正文条数、历史 token 用量消息数——有值才显示；
   - 全量成功（无跳过/失败/丢弃）时降级为普通 toast，不弹摘要；
   - 可选开关「保留搜索轨迹」的位置：确认对话框内的复选项（默认关）。

## 十一、测试计划

单测（`DeepSeekImporterTest`，构造最小 zip fixture）：

1. 版本门禁：v2/v4/缺失 manifest → 拒绝；
2. 线性会话：REQUEST/RESPONSE/THINK → Text/Text/Reasoning，`parent_id` 链与 `activeLeafNodeId` 正确；
3. 分支会话：`current_message_id` 指向旁支 → 激活路径走旁支，主支保留为兄弟节点；
4. 幂等：同一 zip 导入两次 → 第二次全部 skipped；
5. 容错：单 fragment 非法（如 TOOL_SEARCH.results 内一项缺 url）→ 丢片段保消息；messages 非法 → 会话失败不中止；孤儿 parent_id → 按创建序回填；
6. status：INCOMPLETE → finishIncomplete=true；FAILED → 丢弃 + 计数；
7. 图片：export_path 有/无条目两态；非图片 FILE → 计数无 part；
8. 时间：小数秒 0/3/6 位精度解析；
9. 不可还原项（§7.4）：READ_LINK 的 signature 在「保留搜索轨迹」开关两态都不落库、
   `droppedReadLinks` 恒计数；`accumulated_token_usage > 0` 的消息进
   `droppedTokenUsageMessages`、`UIMessage.usage` 保持 null，失败轮不重复计。

## 十二、边界与已知损失（导入完成提示的固定文案素材）

- `READ_LINK.signature` 丢弃：DeepSeek 服务端私有签名，跨端无法恢复网页正文；
- 非图片附件仅元数据：官方导出不含本体；
- `accumulated_token_usage` 丢弃：口径不符，统计页不显示导入会话的历史用量；
- 模型信息官方即不导出：导入会话无模型归属，续聊用当前助手配置的模型；
- 图搜结果、提示条：官方亦无展示对应；
- 无法续写的是「导入正文本身」，不是会话：导入会话在新模型下正常续聊，历史正文作为普通上下文发送。

## 十三、实施清单（按依赖序）

1. `DeepSeekTypes.kt`：格式数据类 + 宽松解析（纯 Kotlin，可先于 UI 单测）；
2. `DeepSeekImporter.kt`：流式 zip → Conversation（含 §5 id、§6 映射、§7 防御、§8 附件索引）；
3. 单测 §11 全绿；
4. `ImportFormat` + spec + `BackupVM.restoreFromDeepSeek` + 进度/结果流；
5. UI 接线（卡片、助手选择、确认、进度、摘要）+ 三语文案（locale-tui）；
6. `assembleDebug` + 全量单测 + audit_ime_trap（表单有 ModalBottomSheet 的话按 TextField 宿主规范自查）；
7. 真机验证：用本次 570MB 样本全量导入，核对幂等/分支/长会话打开性能。
