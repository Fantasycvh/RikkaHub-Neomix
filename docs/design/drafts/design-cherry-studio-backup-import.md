# Cherry Studio 备份导入兼容设计（v7 / direct backup）

> 状态：draft v2（已用真实备份校准，待实施）
> 关联：`app/.../data/sync/importer/CherryStudioProviderImporter.kt`（现状）、`docs/design/drafts/design-deepseek-export-import.md`（同族口径）
> 上游源码：`F:\build\projects\cherry studio`（master `b25bb3128a`，app 2.1.10）
> 真实样本：`D:\bacckup\agent\backup\cherry-studio.20261115190109.NiMoYin.windows.zip`（470MB，2026-11-15）

---

## 1. 背景与现状核对

### 1.1 本仓现状

[CherryStudioProviderImporter.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/sync/importer/CherryStudioProviderImporter.kt) 只认 v1 时代的 `data.json → localStorage["persist:cherry-studio"] → llm.providers`，且只导 providers。

### 1.2 上游现状

- 备份 = **direct backup v7**：`metadata.json`（`version:7`）+ `cache.json` + `Data/`（含 `Data/cherrystudio.sqlite`、`Data/Files/*`）+ `IndexedDB/` + `Local Storage/`；精简备份只留 sqlite + cache.json。
- 恢复端只接受 v7，v1–v6 一律拒绝：`Unsupported v1 backup. Cherry Studio v2 can only restore backup version 7.`（[LegacyBackupManager.ts#L716-L760](file:///F:/build/projects/cherry%20studio/src/main/services/LegacyBackupManager.ts#L716-L760)）
- 全仓已无 `data.json` 产出路径 → 现有解析器对当前备份**完全失效**。

### 1.3 实测基线（来自真实备份，用于定容量与降级口径）

| 指标 | 实测值 | 设计含义 |
|---|---|---|
| `metadata.resources.database` | **false**，但 `Data/cherrystudio.sqlite` 存在 | 不能用该 flag 判断是否含 DB |
| `Data/cherrystudio.sqlite` | **440,619,008 B** | 必须解压到 cache 再只读打开；需先校验剩余空间 |
| 其他 Data 大文件 | `agents.db` 126MB、`cherrystudio.sqlite.bak-kb-bind` 197MB、`.bak-proxy` 166MB | **一律忽略**（只认 `Data/cherrystudio.sqlite`） |
| providers / models | 87 / 411 | 其中启用且有 key 的仅 6 个（deepseek、openrouter、2 个自定义 openrouter 类、Realy_one、sensenova、rinko） |
| topics / messages | 12 / 174（含 12 条 `root`） | 树小，可全量在内存中构建 |
| 分支点 | 3 个 topic 共 6 处（每处 2 个兄弟） | 分支映射必须做（已确认「值得」） |
| message.status | success 145、paused 10、error 7 | 需要 status 门禁 |
| parts 类型（实测出现） | `tool-*` 383、`step-start` 356、`text` 342、`reasoning` 188、`file` 14、`data-clear` 5、`data-error` 5、`dynamic-tool` 2 | 映射表以实测类型为准 |
| file parts | md 7、txt 4、png 2、octet-stream 1 | **附件主要是文本/文档，不是图片** |
| `Data/Files` vs 引用 | 备份内仅 77 个文件，14 个被引用的 fileEntryId 中**只有 1 个存在** | 附件丢失是常态，必须计数提示 |
| `file_entry` | 228 行，全部 `origin=internal` | 以 `fileEntryId` 解析，不解析 URL 路径 |
| `pin` 表 | 0 行 | 本期不导入置顶 |

---

## 2. 目标与非目标

### 目标

1. 识别 direct backup v7（`metadata.json.version == 7`）并导入 providers + models（**阶段 A**）。
2. 导入 topics / messages / 附件为 `Conversation`（**阶段 B**），流程对齐 Chatbox（选目标助手 + 结果摘要）。
3. v1 `data.json` 备份继续可选导入（文件选择保留 `application/json`，按内容识别）。
4. 无法还原项逐项计数，并在结果弹窗提示（不写 Message/Notice）。

### 非目标

`cache.json`、`IndexedDB/`、`Local Storage/`、知识库、MCP、Agent、画图、笔记、翻译历史、pin 表、`*.bak-*` / `agents.db`。

---

## 3. 归档规格

```
cherry-studio.<ts>.<host>.<platform>.zip
├─ metadata.json            # {version:7, appVersion, resources{...}}
├─ cache.json               # 忽略
├─ Data/
│  ├─ cherrystudio.sqlite   # 唯一 DB 来源
│  ├─ cherrystudio.sqlite.bak-*   # 忽略
│  ├─ agents.db                   # 忽略
│  └─ Files/{fileEntryId}.{ext}   # 附件实体（可能大量缺失）
├─ IndexedDB/               # 忽略
└─ Local Storage/           # 忽略
```

识别顺序：

| 判据 | 结论 |
|---|---|
| `metadata.json` 且 `version == 7` | v7 管线 |
| 含 `data.json` | v1 管线（现有逻辑） |
| `metadata.json.version > 7` | 报「备份版本过新」 |
| 其他 | 报「不是有效的 Cherry Studio 备份」 |

### 3.1 SQLite 列（**实测为 snake_case**）

| 表 | 列 |
|---|---|
| `user_provider` | `provider_id, preset_provider_id, name, endpoint_configs, default_chat_endpoint, api_keys, auth_config, provider_settings, is_enabled` |
| `user_model` | `id("providerId::modelId"), provider_id, model_id, name, capabilities, input_modalities, output_modalities, endpoint_types, is_enabled, is_hidden` |
| `assistant` | `id, name, prompt, emoji, model_id, settings` |
| `topic` | `id, name, assistant_id, active_node_id, last_activity_at, created_at, updated_at, deleted_at` |
| `message` | `id, parent_id, topic_id, role, data, status, model_id, stats, created_at, deleted_at` |
| `file_entry` | `id, origin, name, ext, size, external_path` |

引用：`src/main/data/db/schemas/{userProvider,userModel,assistant,topic,message,file}.ts`。

---

## 4. 读取管线

1. `ZipFile` 校验 `metadata.json`。
2. 流式解出 `Data/cherrystudio.sqlite` 到 `cacheDir/cherry-import/{uuid}.sqlite`；解压前检查可用空间 ≥ DB 大小 × 1.2，不足则中止并提示。
3. `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`；`finally` 删除临时文件。
4. 所有 JSON 列宽松解析（缺字段不崩）；查询显式列名。

**POC（实施第一步）**：v7 DB 含 FTS5 虚拟表（`message_fts*`），需在 minSdk 设备上验证 `OPEN_READONLY` 能否打开。失败时按序降级：
- a) 复制为可写临时库，`PRAGMA writable_schema` 剔除 FTS 相关 schema 行；
- b) 引入纯 Java SQLite 读取库（仅 importer 使用）；
- c) 仅阶段 A 放弃，明确报错。

---

## 5. 映射规则

### 5.1 providers → `ProviderSetting`

筛选：`is_enabled = 1` 且 `api_keys` 中至少一个 `isEnabled=true && key` 非空。

> 本地提供商（`auth_config` 为 api-key 但无 key，如 `ollama`、`lmstudio`、`local-embedding`、`cherryai`）不导入：手机端 `localhost` 无意义 → `skippedLocalProviders`。

```
apiKey  = 第一个启用 key
name    = name
endpoint = default_chat_endpoint ?: 预设默认
baseUrl = endpoint_configs[endpoint].baseUrl ?: 预设目录[endpoint].baseUrl
adapter = endpoint_configs[endpoint].adapterFamily ?: 预设目录[endpoint].adapterFamily
type    : adapter ∈ {anthropic} → Claude
          adapter ∈ {google}   → Google
          其他（deepseek / openai-compatible / openai / …）→ OpenAI 兼容
          endpoint == openai-responses → useResponseApi = true
```

**预设目录来源**：从 Cherry 仓库 `packages/provider-registry/data/providers.json`（68 个预设、90KB）生成一份精简资源 `app/src/main/assets/cherry_providers.json`：

```json
{ "openrouter": { "defaultChatEndpoint": "openai-chat-completions",
                  "endpoints": { "openai-chat-completions": {"baseUrl":"https://openrouter.ai/api","adapterFamily":"openai-compatible"} } } }
```

只保留 `id / defaultChatEndpoint / endpoints{baseUrl, adapterFamily}`，预计 < 20KB。生成脚本一次性执行，产物入库。

无法确定 baseUrl（自定义 provider 且无 `endpoint_configs`）→ 跳过并计 `skippedNoBaseUrlProviders`。

去重：`type + baseUrl + apiKey` 相同则跳过（`skippedExistingProviders`）；随后仍走 `mergeProviders`。

### 5.2 user_model → `Model`

行过滤：`provider_id` 命中本次导入的 provider，`is_enabled=1`、`is_hidden=0`。

```
id           = stableUuid("cherry:model:${row.id}")
modelId      = model_id
displayName  = name ?: model_id          // name 常为 null（继承预设）
type         = capabilities 含 embedding → EMBEDDING
               含 image-generation 且不含 function-call → IMAGE
               否则 CHAT
inputModalities  = input_modalities(JSON) 映射 TEXT/VISION/AUDIO/VIDEO
                  + capabilities 含 image-recognition → IMAGE
outputModalities = output_modalities(JSON) ?: [TEXT]
abilities        = function-call → TOOL；reasoning → REASONING
```

`capabilities` 为 null（继承预设）→ 按 `[TEXT]` + CHAT 处理，不猜测。

### 5.3 assistants（阶段 B）

- 用户选择目标助手（`needsAssistant = true`）。
- Cherry `assistant.prompt` 与 topic 内 `role=system` 消息合并进 `Conversation.customSystemPrompt`（先 prompt 再 system，空行分隔）。
- 实测该备份只有 1 个助手且 `prompt=""` → 多数会话 `customSystemPrompt = null`。

### 5.4 topic → `Conversation`

```
id          = stableUuid("cherry:topic:${topic.id}")
assistantId = 用户选择的目标助手
title       = topic.name 非空 ?: "Cherry 会话 <created_at 日期>"
createAt    = topic.created_at
updateAt    = topic.last_activity_at ?: topic.updated_at
isPinned    = false
```

### 5.5 message → `MessageNode`（含分支 → alternatives）

v7 是邻接表树，每个 topic 恰有 1 条 `role='root'`（`parent_id IS NULL`），`topic.active_node_id` 指向当前叶。

算法：

1. 载入该 topic 全部 `role != 'root'` 且 `deleted_at IS NULL` 的消息；建 `children[parent_id]`（按 `created_at` 升序）。
2. 从 `active_node_id` 沿 `parent_id` 回溯到 root → `path`（root→leaf）。
3. 对 `path` 中每个消息 `m`：
   - `siblings = children[m.parent_id]`（m 的兄弟 = 同一父节点的其他分支）
   - `alternatives = siblings.mapNotNull(::parseMessage)`（保持顺序）
   - `selectIndex = alternatives.indexOfFirst { it.id == m.id }`（m 被丢弃时取 0）
   - 生成 `MessageNode(id = stableUuid("cherry:node:${m.id}"), messages = alternatives, selectIndex)`
4. 不在 `path` 上的**整棵子树**丢弃，`droppedInactiveBranchMessages` 计数（只计消息条数）。

> 这是「分支值得」的落点：兄弟消息作为同一 `MessageNode` 的 alternatives 保留，用户在本仓仍可切换；无法表达的是「某个分支自己的后续整段」——该子树丢弃。

兜底：`active_node_id` 为空或指向已删除行 → 取该 topic `created_at` 最大的消息回溯。

status 门禁：

| status | 处理 |
|---|---|
| `success` | 正常 |
| `paused` | 保留，`finishIncomplete = true`，计 `incompleteMessages` |
| `error` | 仅当存在可映射的 text/reasoning/tool 内容时保留（`data-error` 丢弃）；否则整条丢弃；若其有子节点，子节点上提到其父节点（不中断链） |

### 5.6 parts → `UIMessagePart`（以实测类型为准）

| Cherry part | RikkaHub | 说明 |
|---|---|---|
| `text` | `Text` | 原文 |
| `reasoning` | `Reasoning` | `startedAt` ← `providerMetadata.cherry.startedAt`；`finishedAt` ← `startedAt + thinkingMs` |
| `tool-<name>` | `Tool` | `toolName` 取 type 前缀去 `tool-`；`input` 序列化；`output` 取 `output.content[].text` 拼接；`output.error` → 文本；`state=output-available` 视为已执行 |
| `dynamic-tool` | `Tool` | `toolName` 取 `toolName` 字段 |
| `file` | `Image` / `Document` | 见 5.7 |
| `step-start` | 忽略 | 无内容标记 |
| `data-code` | `Text` | 降级为 ` ```lang ` 代码块，计 `downgradedCodeBlocks` |
| `data-clear` | 忽略 | 上下文边界标记 |
| `data-error` | 忽略 | 计 `droppedErrorParts` |
| `data-video` / `data-compact` / `data-compaction-anchor` / `data-knowledge-scope` / `data-agent-task-event` / `data-translation` | 忽略 | 统一计 `droppedCustomParts` |
| 其他未知 | 忽略 | 计 `unknownParts` |

### 5.7 附件（以 `fileEntryId` 为准）

实测 URL 有两种不可靠形式（`file://D:\…` 反斜杠、`file:///D:/…%20…` 编码），**不解析 URL**，统一用：

```
fileId = part.providerMetadata.cherry.fileEntryId
entry  = file_entry[fileId]           // 取 name + ext（ext 可能为 null）
zipEntry = "Data/Files/{fileId}.{ext}"（ext 为空则按前缀匹配 Data/Files/{fileId}.*）
```

- 命中：
  - `mediaType` 以 `image/` 开头 → 物化为 `UIMessagePart.Image(url)`
  - 其他（md / txt / pdf / octet-stream…）→ `UIMessagePart.Document(url, fileName = filename ?: entry.name, mime = mediaType)`
  - 统一 `FilesManager.saveManagedFromBytes(folder = UPLOAD, …)` 真拷贝
- 未命中（实测 14 个引用只有 1 个在包内）→ 丢弃该 part，计 `missingAttachments`
- `data:` URL → 解码物化；`http(s):` → 原样保留

### 5.8 usage / stats

`message.stats` → `TokenUsage`：`inputTokens / outputTokens / inputTokenDetails.cacheReadTokens / totalTokens`；缺失字段按 0，全 0 → null。仅统计保留消息。

---

## 6. 幂等

- 全部 id 用 `UUID.nameUUIDFromBytes`（v3），同 Chatbox/DeepSeek。
- 会话判重前置：解析前查 `existsConversationById`，存在则跳过（不解压附件）。
- provider 去重见 5.1；`mergeProviders` 行为不变。

---

## 7. 无法跨端还原 / 丢弃清单

| 项 | 处理 | 计数 | 摘要文案（zh） |
|---|---|---|---|
| 非激活分支的后续子树 | 丢弃（兄弟本身保留为 alternatives） | `droppedInactiveBranchMessages` | 「N 条其他分支的消息已丢弃：本仓只保留当前分支的完整链路，分支点在消息内可切换。」 |
| `error` 消息（无可映射内容） | 丢弃，子节点上提 | `droppedErrorMessages` | 「N 条出错的消息已丢弃。」 |
| `data-error` / `data-clear` / 视频 / 压缩摘要 / 知识库范围等块 | 丢弃 | `droppedCustomParts` | 「N 个 Cherry 专有内容块已丢弃。」 |
| `data-code` | 降级文本 | `downgradedCodeBlocks` | 仅计数 |
| 附件实体缺失 | 丢弃 part | `missingAttachments` | 「N 个附件无法还原：备份中不包含文件实体。」 |
| OAuth / 无 key provider | 跳过 | `skippedNoKeyProviders` | 「N 个使用 OAuth 登录的提供商未导入，请重新登录。」 |
| 本地 provider（ollama 等） | 跳过 | `skippedLocalProviders` | 仅计数 |
| 自定义 provider 缺 baseUrl | 跳过 | `skippedNoBaseUrlProviders` | 「N 个自定义提供商缺少接口地址，未导入。」 |
| `paused` 消息 | 保留为未完成 | `incompleteMessages` | 仅计数 |

约定：计数为 0 不显示；不写 Notice；完成弹窗逐项列出。

---

## 8. UI 接入

| 字段 | 值 |
|---|---|
| `mimes` | `application/zip` + `application/json`（后者 = v1 兜底） |
| `needsAssistant` | `true`（B 阶段） |
| `needsRestartDialog` | `true` |
| 描述 | 「导入 Cherry Studio 备份（v7：提供商 / 模型 / 会话）」 |

弹窗两段式，复用 Chatbox 的组件模式：确认（选目标助手 + 预计导入量）→ 结果（成功项 + 丢弃项）。

新增三语字符串（`backup_page_import_cherry_*`）：confirm_title / confirm_message / result_title / inactive_branch / error_messages / custom_parts / missing_attachments / oauth_providers / no_base_url。

---

## 9. 失败与安全

- ZIP 路径校验（`isSafeArchivePath`）；只读 `Data/cherrystudio.sqlite`，忽略 `*.bak-*`、`agents.db`。
- 解压前校验磁盘空间；单文件上限：DB 1GB、附件 64MB。
- 表/列缺失 → 对应分类跳过计数，不整体失败；单 topic 异常 → `failedTopics`，继续。
- 非 v7/v1 → 明确报错，不静默成功。
- 备份含全部明文 API Key：不写日志，不落盘中间态（解析完即丢）。

---

## 10. 实施阶段与测试

### 阶段 A（providers + models）

1. 生成 `assets/cherry_providers.json`（一次性脚本，输出入库）。
2. `CherryStudioV7Importer`：解压 + 只读打开 + provider/model 映射 + 计数。
3. `CherryStudioProviderImporter` 改为入口分发（v7 / v1）。
4. `BackupVM.restoreFromCherryStudio` 返回值扩展；`ImportFormat` mimes/描述。
5. 单测：fixture 生成最小 sqlite（测试侧可用 `org.xerial:sqlite-jdbc`，Android 侧保持 `SQLiteDatabase`）。

### 阶段 B（会话 + 附件）

1. topic/message/parts/file 解析；分支 → alternatives。
2. 确认/结果弹窗、进度、pending 状态。
3. 单测：分支、status、parts、附件缺失、幂等。

### 测试清单（用真实备份裁剪出的 fixture）

1. v7 最小包：1 provider + 1 model（snake_case 列）→ 正确映射。
2. `preset_provider_id=anthropic` 且无 `endpoint_configs` → 从预设目录取 baseUrl + Claude。
3. 自定义 provider（UUID id）有 `endpoint_configs['openai-chat-completions']` → OpenAI 兼容。
4. OAuth / 无 key / 本地 provider → 分别跳过并计数。
5. `metadata.version=8` → 报错；含 `data.json` 的 v1 → 走旧逻辑。
6. 树含 2 个兄弟（实测 `019fb359-…` 结构）→ alternatives 2 条、selectIndex 指向激活者。
7. 非激活子树 → 丢弃计数正确。
8. `paused` → 保留 + `finishIncomplete`；`error` 无内容 → 丢弃且子节点上提。
9. parts：text / reasoning（含 startedAt/thinkingMs）/ tool-*（含 output.content）/ dynamic-tool / step-start / data-clear → 映射与忽略正确。
10. `file` part：md → Document、png → Image；未命中 zip → `missingAttachments`。
11. 重复导入 → 会话全 skipped、provider 不重复。
12. 440MB DB 只读打开耗时与内存占用可接受（真机校验）。

---

## 11. 风险

| 风险 | 缓解 |
|---|---|
| Android SQLite 无法打开含 FTS5 的 v7 DB | 实施前 POC；备选见 §4 |
| 预设目录随版本变化 | 生成资源入库；缺项走 `endpoint_configs` 或跳过计数 |
| 附件普遍缺失（实测 13/14） | 计数提示，不报错 |
| 440MB 解压占用磁盘/耗时 | 空间预检 + IO 线程 + 进度提示 |
| Cherry 表结构小版本漂移 | 显式列名 + 缺列降级 + fixture 回归 |

---

## 12. 已定决策

1. **v1 兜底**：MIME 同时保留 `application/json`，按内容识别（有 `data.json` 走旧逻辑）。
2. **分支**：兄弟消息保留为同一 `MessageNode` 的 alternatives（`selectIndex` 指向激活分支），非激活分支的后续子树丢弃并计数。
3. **助手提示词**：Cherry `assistant.prompt` + system 消息合并进 `Conversation.customSystemPrompt`，不创建本仓助手。
4. **兼容范围**：以 `D:\bacckup\agent\backup` 内 16 份 v7 备份为验收样本（2026-07-03 ~ 2026-11-15）。
