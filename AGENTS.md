# AGENTS.md

This file provides guidance to person when working with code in this repository.
---
#### Mandatory Constraint：Document Submission/Push Authorization Rule
**Priority**: Highest level, overrides all document operation logic and cannot be bypassed
**Applicable Scenarios**: All operations involving external submission, push, or release of documents

   ##### Core Execution Rules
1.  **Pre-authorization Requirement**: An authorization request must be proactively submitted to the user before executing any submission or push action. No submission/push operation shall be triggered before valid authorization is obtained.
2.  **Unauthorized Fallback Mechanism**: If valid user authorization is not obtained, the current document content must be saved as a draft. No external submission or push action shall be performed.
3.  **Secondary Confirmation for Partial Authorization**: If only partial content of the document is authorized by the user, the full document shall not be submitted directly. The final complete version with all changes integrated must be sent to the user for confirmation prior to submission. The submission operation may only be executed after the user explicitly approves the final version.

   #####Boundary Definitions
- Valid Authorization: A clear consent statement from the user (e.g. "agree", "confirm submission", "okay to push") shall be deemed as valid authorization obtained.
- Unauthorized Determination: No reply from the user, ambiguous expression, or only approval of partial content shall all be judged as failure to obtain full submission authorization.
---

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Architecture Overview

### Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.
- **terminal**: Terminal emulator core taken from termux-app's `terminal-emulator` (package `com.termux.terminal`, kept
  binary compatible with the `terminal-view` aar). `TerminalSession` runs over a `TerminalTransport`: local PRoot
  terminals use `LocalPtyTransport`, SSH workspaces use the app's `SshTerminalTransport`. Origin and local changes are
  listed in terminal/README.md.

### Key Technologies

- **Jetpack Compose**: Modern UI toolkit
- **Koin**: Dependency injection
- **Room**: Database ORM
- **DataStore**: Preferences storage
- **OkHttp**: HTTP client with SSE support
- **Navigation 3**: App navigation
- **Kotlinx Serialization**: JSON handling

### Core Packages (app module)

- `data/`: Data layer with repositories, database entities, and API clients
- `ui/pages/`: Screen implementations and ViewModels
- `ui/components/`: Reusable UI components
- `di/`: Dependency injection modules
- `utils/`: Utility functions and extensions

### Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT, SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables users to regenerate responses and switch between different conversation branches, creating a tree-like conversation structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Development Guidelines

### TextField Host Rules（IME 输入陷阱，历史四连击的防回归规范）

> 本仓已四次踩同一个坑族（`4d2938da6` item key 内容哈希 / `d50d7aeaf` AlertDialog 表单抖动 /
> `9bc2d58f4` LazyColumn item 视口裁剪 / compress 对话框反复返工）。TextField 的宿主必须是
> **滚动容器里常驻的稳定节点**，规则：
>
> 1. Lazy item 的 `key` 必须用**稳定 id**（持久化字段），禁止 `content.hashCode()`——内容每变
>    一次 key 就变，该行被销毁重建：焦点丢失（IME 收起）+ animateItem 抖动。
> 2. **长表单/多字段编辑禁止内联在 Lazy item 里**。键盘弹起收缩视口，焦点字段被裁出列表 →
>    item 销毁重建 → 输入状态丢失、IME 收起再弹（逐字符回退、光标漂移）。一律改用
>    ModalBottomSheet（`Column+verticalScroll` 子项常驻）或整页。注意：本仓 `CardGroup` 是
>    **Column 实现**（`item(headlineContent=…)` 是自家 DSL，非 LazyListScope），天然免疫此坑——
>    表单优先用 CardGroup。
> 3. AlertDialog 放多字段表单：对话框窗口不与 IME insets 协调，内容高于可见区会被遮挡且
>    「滚动到光标」引发整体抖动。内容必须 `verticalScroll + imePadding`；字段多到需要滚动
>    就升级为 sheet 或整页。
> 4. **状态流向单向**（借鉴 BiliPai `0125561e6`，台账 bilipai.md 2026-11-08 增量候选 2；
>    与上面 1–3 的宿主拓扑规范互补，管的是状态流不是宿主）：`TextFieldState`/编辑器内部
>    状态是**编辑态唯一权威**，VM/数据层字段只是它的投影——`snapshotFlow { text }` 单向
>    同步出去；程序化回填必须**先比较、后 `setTextAndPlaceCursorAtEnd`（或等价 API）**，
>    绝不向活跃 IME 会话回写。本仓历史坑「input text 丢字符」「整对象写丢更新」即同族病。
>
>    **2026-11-12 第五次定案（assistant 设置页输入框抖动的真身）**：设置类文本域若把
>    `value =` 直接绑**异步持久态投影**（`SettingsStore.settingsFlow` / Room Flow / VM StateFlow
>    的解码结果），又在 `onValueChange` 里每字符 `copy` 写回，就会「落盘后旧值再回灌」活跃
>    编辑框——Compose 会重置内部编辑态（组合打断 / 光标回跳），中文 IME 尤其明显。真机 A/B
>    （PJX110，120ms/字符注入 `a..j`）：写穿回灌的助手名称框得到 `bcdefghija`（25 字符整串
>    注入只剩 `aabbxec`）；同设备改用 `rememberTextFieldState` 的提示词框、本地 `EditState` 的
>    新建 sheet 均完整。规范做法 = **本地权威 + 单向去抖写出 + 离开页面补写**，统一用
>    `ui/hooks/PersistedTextFieldState.kt` 的
>    `rememberPersistedTextFieldState(value, onCommit, resetKey = 实体 id)`；`onCommit` 必须走
>    取消安全的 scope（页面级 `AppScope`），数据层用字段级 `settingsStore.update { }` transform，
>    禁止「读 `settings.value` → 改整对象 → 写回」的锁外快照写。VM 侧提交通道也要用与进程同寿命的
>    `AppScope`（`AssistantDetailVM`/`SettingVM`/`BackupVM`/`KnowledgeBaseDetailVM` 均已注入），
>    否则离页补写会被 `viewModelScope.onCleared` 取消，最后几个字符静默丢失。残留清单与迁移记录：
>    `.tmp/audit-ime/stateflow-audit.md` 附录 C 的 48 处高风险已于 2026-11-12/13 分两批全部迁移，
>    未清的存量项见 `docs/design/verification/ime-stateflow-migration-2026-11-13.md`。
>
> 回归审计：`python scripts/audit_ime_trap.py`（A/A' 类命中即失败；B/C/D/E 仅告警——E 是
> 写穿回灌启发式，D 是 `imePadding` 必须在 `verticalScroll` 之前/视口侧）。A' 存量豁免在
> `scripts/ime_trap_exceptions.json`，**新增违规禁止加豁免**。自测：
> `python -B scripts/test_audit_ime_trap.py`。

### ListItem supportingContent Slot（条件内容静默丢失陷阱）

> `CardGroup` 的 `item(supportingContent = …)` 最终进 M3 `ListItem`，该槽位**只渲染第一个直接
> 子节点**：在 `FlowRow`/`SegmentedPill` 之后同级再写 `when`/`if` 条件行，条件行会被静默丢弃
> （标题与第一子节点正常、状态更新正常，唯独条件内容永不出现——`d3212c913` 的头像框/思维链
> 「编辑参数」入口即此坑，真机排查良久）。
>
> 规则：支持内容有多块时一律 `Column { … }` 包一层；条件行不要与主控件平级直接挂在槽位上。

### UI Development

- Follow Material Design 3 principles
- Use existing UI components from `ui/components/`
- Reference `SettingProviderPage.kt` for page layout patterns
- Use `FormItem` for consistent form layouts
- Implement proper state management with ViewModels
- Use `Lucide.XXX` for icons, and import `import com.composables.icons.lucide.XXX` for each icon
- Use `LocalToaster.current` for toast messages

### Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- If the user explicitly requests localization, all languages should be supported.
- English(en) is the default language. Chinese(zh) and Traditional Chinese(zh-rTW) are supported.
- When localization is needed, use the `locale-tui-localization` skill for managing string resources.
