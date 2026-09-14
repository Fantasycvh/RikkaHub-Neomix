# 上游变更分析（综合版，2.4.11 → 2.5.1+，2026-09-12 重写，2026-09-14 复核）

> 本文档合并原 `upstream-2.4.11/2.4.14/2.4.16/2.5.0-analysis.md` 四份分析（已删除）。
> 当前 master 同步点：`a9b35ba2`（2.5.1，2026-09-10 ff-only 推送）。上游其后 **6 提交未同步**（2026-09-14 `git fetch` 发现，见「待吸收」）：`46cacf23`（deepseek-flash，已手工吸收 `e9a39751`）、`2689e753`（AGENTS.md 支持，已手工吸收 `e8e047e5`）、`288a034c`/`bc9d2582`/`d6ba728e`（依赖更新）、`bd936caa`（非聊天请求 session-id 头修复）。
> 决策原则：**等上游稳定后按需吸收**；触及 `GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`（fork 重改最重的文件）一律手工适配，不直接 cherry-pick；cherry-pick 需包名适配（`me.rerere.*` → `app.androidrik.*`）。
> **判定方法（2026-09-12 纠正）**：fork 的"已吸收"以**代码等价核对**为准，**不用 `git merge-base --is-ancestor`**——fork 走手工直套/移植，上游 commit 不是 fork 分支祖先，merge-base 对已吸收项恒判"未吸收"（误报 glm5.3/hy4/kimi k3/deepseek v4.1 flash 等）。核对方式：grep fork 代码中对应定义/注释（如「对齐上游 xxx」）或 diff 等价。

## 批次概览

| 批次 | 范围 | 规模 | 核心主题 | 状态 |
|---|---|---|---|---|
| 2.4.11（36 提交） | `8824e0e8`..`1e3351f7` | 78 文件 +2691/-649 | 全局代理页（放弃）、终端多 Tab、R8/JWT、think 标签 | 已吸收核心件 |
| 2.4.12–2.4.14（38 提交） | `1e3351f7`..`7da69770` | 103 文件 +4842/-2196 | 后台生成稳定性（与自有方案重叠）、Gemini 工具共存、多 key/模型注册 | 已吸收核心件 |
| 2.4.16（17 提交） | `5662945c`..`08c2648b` | 54 文件 +839/-301 | TTS 重试、Gemini 混合工具后续 | 已吸收核心件 |
| 2.5.0（16 提交） | `b153028d`..`12ee935e` | — | 终端 Tab 标题、审批修复、**消息队列/语音/ASR（fork 自研落地）** | 核心功能已吸收/自研 |
| 2.5.0 后批（20 提交） | `12ee935e`..`6e0aa7d4` | 2026-09-09 | workspace 预览/Shell 兼容、时间提醒间隔、多选、工具详情/回执、模型注册 | 大半已吸收（见清单） |
| 2.5.1（3 提交） | `6e0aa7d4`..`a9b35ba2` | 2026-09-10 | DEV badge、翻译快捷方式 + RouteActivity 统一导航 | 已吸收（见清单） |
| 2.5.1 后（2 提交） | `a9b35ba2`..`2689e753` | 2026-09-11+ | deepseek-flash 注册、AGENTS.md 支持 | 均已吸收（`e9a39751`/`e8e047e5`） |
| **2.5.1 后再 4 提交** | `2689e753`..`bd936caa` | 2026-09-13 | 依赖更新（Gradle/lib.versions）、非聊天请求 session-id 头修复 | **未同步**（2026-09-14 fetch 发现，见「待吸收」） |

## 已吸收（合并清单，**含 2026-09 全量**）

| 提交 | 内容 | 吸收提交 | 备注 |
|---|---|---|---|
| 2.4.8-2.4.10 批（约 13 个） | R8 keep/jlatexmath、Imggen override、流式 null 容错、reasoning max、加密推理、DashScope ASR、SAF、.agc、豆包搜索、句首大写、正则排序 | 分批 | 2026-08 |
| `e8293d35` / `b62d29d1` | 空 tool schema 归一化 / workspace stdin EOF | `758a252d` | 2026-09-04 |
| `942d0d28` / `6c6a8458` | 合成消息不参与模板 / fork 会话继承 folder+cwd | `6bbcacb6` / `77abbd89` | 2026-09-06 |
| `3a533a6a` | 终端多 Tab + 后台底座 | `fbfcde5e` | 2026-09-03 |
| `104040df` + `1d86b3c1` | 终端 Tab 动态标题 + 手势/尺寸修复 | fork 移植 | 2026-09 |
| `5736c05e` | 代码块导出截断模式 | `e2f6b836` | 2026-09-05 |
| 1c09d5cd 等 5 提交 | TikZ mupdf SVG 桥（TEXT_AS_PATH）+ 缓存 | fork 会话 | 2026-09-08 |
| `85402745` / `54030ec4` | think 标签锚定 / Skill SnakeYAML | `c3385e63` / `3b482036` | 2026-09-08 |
| `55506496` | 连续工具审批丢失 + 取消状态修复 | 09-08 批 | 2026-09-08 |
| `5902feab` / `ab8b6cc8` / `a61d116d` | 纯图片消息 / provider IO 调度 / 自定义 response API | 09-08 批 | 2026-09-08 |
| `e9b98a4b` | qwen tts 音色列表 | `89b00455` | 2026-09-09 |
| `a8f8c3a1` | Exa 新鲜度证据 | `446f5a82` | 2026-09-09 |
| `5403bc96` + `2f05019b` | TTS 合成自动重试 + 错误分类 | `ec398672` | 2026-09-09 |
| `9851d037` + `f7869e35` | Gemini 工具共存 + 混合错误修复（捆绑） | `366a8de7` + `7c77c483`（fork 内联架构重实现） | 2026-09-09 |
| `77a58c2c` / `dee88dca` / `ab07ac1f` / `6e0aa7d4` | 统计 JSON 兜底 / edit_file 安全 cast / DeepSeek V4.1 Flash / 工具详情前置可开 | `4cbdb104`（B 批直套） | 2026-09-09 |
| 模型注册组（glm5.3 `5b58c957` / hy4 `86c85236`+`5b890d22` / kimi k3 / qwen3.8 `0826a3b9` / deepseek v4.1 flash / kimi icon `979cd169`） | 模型数据 | 早于 fork（直套）+ 本次缺口 `c73f8972`/`46cacf23`→`e9a39751` | 代码等价核对（2026-09-12） |
| `fcb04126` / `3d75f83f` | web ask_user 多选 / 模型弹窗标签堆叠 | `c468f0ba` | 2026-09-11 |
| `5b4d7895` | 自定义时间提醒间隔 | `772cd77e` | 2026-09-11，真机通过 |
| `a7ee362f` | 系统栏图标色退出恢复 | `e9a39751` | 2026-09-12，真机通过 |
| `1a1e672e` | 模型/提供商名支持空格（保存去尾） | `bb92f489` | 2026-09-12，真机通过 |
| `513b784c` | 翻译快捷方式 + RouteActivity 统一 Intent 导航 | `58a64d8d` | 2026-09-12，真机通过（四路径冷/热） |
| `321443d8` | tool-only 消息操作按钮可见 (#1689) | `321443d8` 补齐（`isEmptyUIMessage` 补 `ServerTool -> false` + 单测） | 2026-09-12 |
| `2689e753` | Workspace 读取 AGENTS.md 注入工作区指令 | `e8e047e5` | 2026-09-12 |
| `7b92f89e` | 禁用代码块连字 (#1623) | `f26a19f1`（fork 核对为真缺口，grep 零命中 fontFeatureSettings） | 2026-09-12 |
| `aab5026e` | 通知小图标（#1782 状态栏胶囊无图标） | `1e98f69a`（ic_stat_rikkahub 五密度 PNG + NotificationUtil 默认切换） | 2026-09-12 |
| `4fcb590a` | 测试连接对话框本地化 | `1e98f69a`（标签 string 化 6 语言 5 key + 列宽 64→120dp） | 2026-09-12 |
| `42b933d8` | debug 启动图标 DEV 角标 | `1e98f69a`（四 drawable + anydpi overlay，debug 变体） | 2026-09-12 |
| `d3a53e0a` | 图片选择改 PickVisualMedia (#1821 选图落 CR 文件夹而非相册) | `e9c36591`（fork 4 处图片入口；ChatPage 视频/音频保留 GetMultipleContents） | 2026-09-12 |
| `aac6e963` | 统一内置搜索与外挂搜索行为 (#1666)：模型自带 `BuiltInTools.Search` 时不再注册外挂搜索 | `0b562833`（漏吸收补齐，工具注册 + 告警同口径 + 4 单测） | 2026-09-14 |
| 队列 `66de8b30` / 语音 `a621e277` / ASR `580e05d6` | 消息队列/语音模式/ASR 双向流 | **fork 自研落地**（`3a911f1e`/`6680668d` / `1c499b79`..`27dc690d`） | 2026-09-09/10 |

## 已放弃 / 不跟

| 提交 | 内容 | 原因 |
|---|---|---|
| `986b9c39`+`3509406b` | 全局代理页 | 与 per-provider ProviderProxy 设计冲突（2026-09-08 决策） |
| `9365c297` | 移除独立标题/建议模型配置 | 保留独立配置（`710ba2c3`） |
| `c62f1eb1` | 生成期临时 FGS | 与自有 KeepAliveService 重叠 |
| `c16fe44f`+`41353567` | 固定 3 次重试 + 开关 | 自有 RetrySetting 是超集 |
| `fa0305ba`+`daae3749` | IME 修复 | UX 动画优化非 bug，ChatInput 定制深 |
| `05a85a09` | ask_user 自定义文本 | **已自有**（AskUserTool 描述明示支持自由文本，`c1924f13` 复核） |
| `097cdb90` | ChatToolFactory 重构 | 不跟（2026-09-14 复核并更正）：原判「零功能收益，除非出现第二个工具装配方」——**第二个装配方其实早已存在**（`GenerationHandler.kt:138` 在传入 tools 之上再叠记忆工具），故"除非"条件已成立；但仍不吸收，因它是**结构集中**而非功能修复，fork 定制碰撞点多。**装配方盘点见下「注意点」第 9 条**。**唯一行为差异值得单独摘取**：上游工厂引入 `shouldUseExternalWebSearch(assistant, model) = assistant.enableWebSearch && BuiltInTools.Search !in model.tools`，避免「模型自带原生搜索时仍注册外部搜索工具」；fork 现为 `if (assistant.enableWebSearch)` 无脑注册（`ChatService:815`），**这是可直接摘的 bug 修复，与是否做 factory 重构无关**——**已于 2026-09-14 摘取（`0b562833`：`shouldUseExternalWebSearch` + 工具注册/告警同口径 + 4 单测）** |
| `b6df5f04` / `96fbe7e3` / `0533acde` | OAuth 重构 / videogen / 移除默认提供商 | 无实益：OAuth 抽包是纯结构搬迁（fork 功能已等价，且已吸收其后的 `8ea375a9` cloudflare 匹配 + `c49be6fe` 令牌刷新）；videogen 上游未接进 App（模块自 8/24 初始化后零提交，吸进来是死代码）；移除的是上游自家 rikka-ai.com 内置提供商 |
| `ef94834a` | MCP kotlin-sdk 换 client-only | 完整包 server 侧是 MCP host（⑥）依赖，待 ⑥ 立项时处理 |
| `7714f2bc` | chatbox v2 导入 | 按需 |
| `08c2648b` | 移除记忆 id 显示（close #1830） | **不吸收（2026-09-14 核实）：纯展示层微调**——全量 diff = `AssistantMemoryPage.kt` 单文件删 5 行（`Text("#${memory.id}")` 块 + 一空行），**不碰表结构 / `AssistantMemory` 模型（仍 `val id: Int`）/ 备份恢复 / 工具执行**。fork 保留 `#id` 显示（`AssistantMemoryPage:407`），便于核对审批卡片与 `memory_tool` 引用的是哪条。**重要更正：对其自身目的而言这次隐藏是半截修复**——`buildMemoryPrompt` 仍 `put("id", memory.id)`（已核 `upstream/master`），`memory_tool` schema 与示例仍暴露 id，故 duzhaokun123 的第一条理由「避免助手乱猜测记忆数量」**未被修复**（只治了「用户看着难受」）。#1830 以 **wontfix** 关闭（re-ovo「id不影响任何东西」；重排/自定义 id 均不采纳）。**重排难度评估见 `design-backup-workspace-files.md` §十** |

## 待吸收（现状真存量，按优先级）

> **2026-09-12 清仓**：第一批全部清空（`aab5026e`/`4fcb590a` 吸收、`74397a32` 放弃）；第二批仅剩搁置项；**第三批三个大改项也全部落地并真机验证通过**（`7038e981`→b2434dce、`03246406`→e419085f、`3896b6d6`→78b3fbf9，详见 roadmap）。待吸收剩余零。

### 第二批（值得吸收，需适配/手工合）

| 提交 | 内容 | 备注 |
|---|---|---|
| `20e552f9` | X-Session-ID 会话头 | 搁置（fork 无 sessionId 概念，无 opencode 端点）；**2026-09-14 核查**：上游随后 `bd936caa` 修的同机制后台请求缺头问题亦**不吸收**（见下「新发现」）——引入它才会需要后续修复，而 fork 无此功能故两者皆不做 |

### 第三批（大改/暂缓）

| 提交 | 内容 | 备注 |
|---|---|---|
| ~~`7038e981` / `03246406`~~ | workspace HTML 预览 / Shell 兼容模式+DB 迁移 | **已吸收（2026-09-12）**：b2434dce（SVG 路由+预览/源码切换+WebView 隔离加载）、e419085f（DB 24→25 + shell_compatibility_mode + PROOT_NO_SECCOMP + 详情页开关 + 6 语言）；Shell 兼容模式在 KernelSU/proot 5.1.0 下有 getcwd/chdir ENOSYS 固有限制（默认 seccomp 模式正常，详见 roadmap） |
| ~~`3896b6d6`~~ | Imggen 长按多选 | **已吸收（2026-09-12）**：78b3fbf9（长按多选+批量删除，适配 fork `GeneratedImage.id` 为 Int；另有 a18a3193 修点击穿透/全选去重，SVG 列表缩略图见 roadmap） |
| ~~`45275025` / `019c46ae`~~ 上下文限制文案 / /skills 免审批 | **已放弃（2026-09-12）**：用户决策——fork 该页/该白名单自有定制，不跟 |

### 2026-09-14 新发现：2.5.1 后再 4 提交（未同步）

`git fetch upstream` 后 `master..upstream/master` = 6 提交，其中前 2 个（`46cacf23`/`2689e753`）此前已手工吸收，**后 4 个为本次新发现**：

| 提交 | 内容 | 评估 |
|---|---|---|
| `bd936caa` | **fix: 修复部分非聊天请求不带 session id 头的问题**（`Provider.kt`/`GenerationLoop.kt`/`ChatService.kt` + 测试，close #1902） | **不吸收（2026-09-14 核查）**：它是 `20e552f9`（引入 `X-Session-ID` + opencode session 头，2026-09-08，**在 fork 同步点之后**）的后续修复——给 `sessionId` 默认 `Uuid.random()` + `backgroundTextGenerationParams` 补 `conversationId`，让标题/建议/压缩/OCR/翻译/连通性测试等后台请求也带上头。**fork 工作分支根本没有 `sessionId` 概念**（`TextGenerationParams` 无该字段、全仓无 `configureSessionHeaders`/`X-Session-ID`/`x-opencode-session`），属「从未引入」而非「漏改」，修的是 fork 里不存在的机制。**影响为零**：该头仅 `opencode.ai` 网关校验，fork 内置提供商是 `api.rikka-ai.com`。**理论窗口**：fork 保留了 opencode 的 reasoning 适配（`ChatCompletionsAPI.kt:430`）却无 session 头 → 用户若自定义 `opencode.ai` 端点，主聊天可用但后台请求会 `400 MissingSessionID`。**当前不处理**（低频 + 要修须连带整条 session-id 链）；若将来做 X-Session-ID 一并评估 |
| `d6ba728e` | 依赖更新（`FloatingWindow.kt`/`Theme.kt`/`common/build.gradle.kts`/lib.versions） | 低优先：`FloatingWindow`/`Theme` 是 fork 定制/曾对齐过的文件，需看具体依赖版本与改动 |
| `288a034c` + `bc9d2582` | 依赖更新 + **修复误删 `lib.versions.toml`**（后者是前者的事故回滚） | **不吸收**：纯依赖版本变动（含一次事故与修复），合并净效果有限；若需某依赖版本单独评估 |
| `2689e753` 起算的同步动作 | — | 同步方式仍是 ff-only：`git fetch upstream && git checkout master && git merge --ff-only upstream/master && git push origin master`（见下） |

## 同步方式（ff-only）

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master
git push origin master
git checkout imperfect-customization-and-refinement
```

## 注意点（合并历史教训）

1. **判定方法**：已吸收用**代码等价核对**（勿用 merge-base `--is-ancestor`——fork 手工直套，上游 commit 非 fork 祖先会恒误报；2026-09-12 已纠正）。
2. **包名迁移**：上游 `me.rerere.*` → fork `app.androidrik.*`；所有 cherry-pick/移植后改 import。
3. **重改文件**：`GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`——上游改动集中在这三文件时手工适配成本最高。
4. **R8 混淆**：上游 `b1effc` 修过 `@Serializable` 反射崩溃；fork release 潜在同款风险（设置/备份/搜索反序列化），异常时优先加同款 keep 规则。
5. **两个 B 编号体系**：2.4.14 批与 2.4.16 批各有 B1/B3（含义不同），引用注明批次。
6. **TikZ 路线**：mupdf SVG 桥（TEXT_AS_PATH），dvisvgm 链已废弃。
7. **fork 自研优先**：队列/语音/ASR/保活/审批串行化/多 key/fallback 均为 fork 自有实现——上游同类只是语义参考，不整体吸收。
8. **提示词注入的「先合并、又想推翻」脉络**（2026-09-14 核实，回答「上游为何讨论将模式注入与提示词合并」）：
   - **已落地的合并 = 上游 `1cece24e feat: 提示书`（2025-12-17）**，把三样各自独立的东西并成一套：①`ModeInjection` 原只有 `{name, priority, prompt}` 窄字段、②世界书 `RegexInjection` 只有 `{name, regex}`、③学习模式是硬编码布尔 `Assistant.learningMode`。该提交引入 `PromptInjection` 密封基类（公共字段 `id/name/enabled/priority/position/content`）+ `InjectionPosition` 枚举（模式注入**因此才获得插入位置控制**）+ `WorldBook`（后改名 `Lorebook`，`e94709ca`）+ 助手级绑定 `modeInjectionIds`/`lorebookIds` + 统一双 tab 页面 `ui/pages/prompts/PromptPage.kt`（1005 行）。**最强信号**：写死的 `learningMode` 布尔先删（`51cbe7ff`），随后 `1937223b` 把「学习模式」降格为 `DEFAULT_MODE_INJECTIONS` 里的一条普通 `ModeInjection`（内容 `LEARNING_MODE_PROMPT`）——特例被吸收成统一模型里的一行数据。命名演进：提示书 → 世界书更名 Lorebook → 现名「提示词注入」。**fork 已完整具备这套统一模型**（含后续修补 `a1c38afe` 同 role 注入合并、`885517b4`/`0c47d72b`/`390f4c5f` 注入破坏思维链/工具连续性的修复）。合并副作用遗留 open bug = 上游 #1845（两层结构「全局定义 + 助手引用」导致删除被引用的注入后，助手显示「已开启注入数量」不减；本仓 PromptPage 同款结构，未修）。
   - **#1542 的难度评估（2026-09-14，「#1542 本身的难度?」）——分窄义与全量两档，差一个数量级**：
     - **窄义（缓存止血）＝小（天级）**：缓存被谁破坏由**位置**决定，非注入本身——`BEFORE/AFTER_SYSTEM_PROMPT` 走**改写 system 消息（下标 0）**（`applyInjections` 把 before+原文+after 拼成一条新 system）→ system 一变**整个请求从第 0 token 起缓存全废**；`TOP_OF_CHAT` 废大部分前缀；`BOTTOM_OF_CHAT`/`AT_DEPTH` 在尾部**只影响后缀、前缀仍命中**（故「轮次尾部注入 / turn-tail」是缓存友好方向，上游 PR #1630 即此）。上游已有的擦边动作均为止血而非 #1542：`3a18dae8` 删 `cur_time`/`cur_datetime`、`2d61ba95` 阶梯式截断保缓存。**fork 同款隐患**：默认助手 systemPrompt 仍含 `{{cur_date}}`（`PreferencesStore.kt:823`）+ `PlaceholderTransformer` 每请求遍历全部消息重算 → **每天从 system（位置 0）作废整段缓存**；`cur_time`/`cur_datetime` 本仓无（上游删时未引入）。**建议仅做窄义版即可拿到大部分收益，且落在本仓既有架构内。**
     - **全量 #1542（统一重写）＝大（架构级，周级起）**：issue 本身只是**草图**（正文带问号「统一…为上下文插件?」、无设计、无 PR、0 评论，仅被 **pinned**）。涉及面：数据模型（`PromptInjection`/`Lorebook`/助手与会话的 `modeInjectionIds`/`lorebookIds`/角色卡字段）、持久化（Settings 顶层三件套 + `ConversationEntity` 列 + `SettingsJsonMigrator` 备份兼容）、注入逻辑（`PromptInjectionTransformer` 整体重想，含 `findSafeInsertIndex` 避让 USER→ASSISTANT(tool)）、UI（`PromptPage.kt`、`AssistantPromptPage`、角色卡导入页）、导入导出（两个 Serializer + ST 格式 + 角色卡 V1/V2/V3）、存量迁移、测试（`PromptInjectionTransformerTest.kt` **1303 行**重写）。**真难点不在行数**：①**设计未定**（须先出设计文档）；②**跨 provider 缓存语义不同**（Claude 显式 `cache_control` 断点 vs OpenAI/DeepSeek 自动前缀匹配），一个设计未必通吃，须逐家实测；③**与本仓压缩线交互**——注入在 `windowMessages` 组装**之后**由 transformer 施加，新设计须与检查点合成消息/区域指纹/`isSynthetic` 共存，照搬上游几乎不可能；④**角色卡统一自带一个子项目**（V2/V3 字段 + `character_book` 独立规范，本仓**目前不读 `character_book`**，是已知缺口）。估：设计落定后 1–2 周专注实现，边设计边做（现实）3–6 周 + 多 provider 真机验证；与自动压缩 Phase 0.5/1/2 总量**同级或更大**。
     - **相对位置判断：本仓在此议题上不落后、局部更靠前**——上游 #1542 想要的「缓存友好设计」，我们已在压缩线做了一版（轨迹只追加 / 检查点固定窗口最前 / 区域指纹失效 / `isSynthetic` 不进模板不落库）；且 **Yuihub 已把该方向落地**（`fbd15331` DSH 分段轨迹：记忆快照 append-only、模式注入**在 system 之后新开独立段**而非重写 system、世界书触发作为**当前轮次尾部补充段**、压缩只记边界不改写节点 + SHA-256 前缀指纹埋点）。**结论：全量 #1542 不排期**（大改 + 破坏存量 + 与压缩线冲突，边际收益低）；仅在其判据下（前缀稳定性）做窄义止血，需要时对照 Yuihub 分段轨迹实现。

9. **工具装配方盘点**（2026-09-14，回答「如果 ChatToolFactory 重构，潜在的工具装配方有?」）：
   - **现存的装配方（3 处 + 2 个子装配）**：①**`ChatService.handleMessageComplete`（`ChatService.kt:814`）＝主工具集**——搜索（`enableWebSearch` 条件）/ 本地工具 8 类 / 最近对话（条件）/ 工作区（`createWorkspaceToolsIfReady`，需 shellStatus READY）/ 技能安装（恒注册）/ 已启用技能（条件）/ **MCP 动态循环**（含 `McpNameSanitizer.buildUniqueFunctionName` 去重净化）；②**`GenerationHandler.generateInternal`（`GenerationHandler.kt:138`）＝第二个装配点**——在传入 `tools` 之上再叠 `buildMemoryTools`（含 `needsApproval` 按 action 分档回调），这是 fork 特有、上游 factory 设计时未考虑的形态；③`ProviderConnectionTester`（`:179`）独立假工具 `get_current_time`（连通性测试）；④`LocalTools.getTools`＝按 `LocalToolOption` 的**子装配**（js/time/clipboard/tts/askUser/screenTime/calendar×2）；⑤`Model.tools`/`BuiltInTools`（`ai/.../Model.kt:47`）＝**provider 侧**能力标志（Search/UrlContext/ImageGeneration），非 Kotlin `Tool` 但与装配逻辑耦合（见上条 web search 条件）。
   - **潜在的装配方（未来立项会变成第 4/5 个入口）**：**MCP host 模式**（远程调用者需设备工具**子集**、复用审批/loop guard，见 `peer-apps-benchmark.md` SoLab ⑥）；**Agent/工作流/定时任务入口**（借鉴清单 Cron/workflow/subagent/Telegram，每入口都是独立生成发起方 + 需"无头会话"审批策略）；**Web API 聊天**（`ConversationRoutes` 现委托 ChatService、**尚不是装配方**，但 web 端一旦需要与手机不同的工具集即成为装配方——这是最可能打破"集中式"设计的压力点）。
   - **必须明确排除在 factory 之外的后台旁路**：标题 / 建议 / 压缩 / OCR / 翻译全部走 `backgroundTextGenerationParams`（`ChatService.kt:139`，**刻意不含 tools**）↔ 重构须保住这个"无工具"契约。
   - **展示侧另有一方**：`ToolUIRegistry`（`ToolUI.kt:93`）+ `ChatMessageTools.kt:79` 按 `toolName` 解析渲染器——非装配方，但是 **toolName 的第二个消费方**，改命名（MCP 前缀/技能名）须同步。
   - **签名含义**：上游 `createTools(settings, assistant, model, workspaceCwd)` 只覆盖主链路；若要容纳潜在方，需再开维度——**调用者类型**（device-local vs remote/headless，决定是否含设备工具）、**记忆工具的归属**（现由 `GenerationHandler` 装因它持 `memoryRepo`；收进 factory 则 factory 需依赖 `MemoryRepository` 或回调注入）、**统一审批解析层**（`needsApproval` 现每处自定义：记忆按 action 分档 / MCP 透传 / 工作区有 override；不抽层只是把散乱搬家）。

10. **批次「已吸收核心件」不等于零缺口**（2026-09-14 教训）：`aac6e963`（#1666 统一内置/外挂搜索，2026-08-13）落在 fork 分叉点 `2c980642`（2026-08-02）之后的 master 同步范围内，但**特性分支从未移植、批次分析也未记录**——它被 2.4.14 批次的「已吸收核心件」概括吞掉了。**修正做法**：`已吸收核心件` 只表示"该批主线已处理"，不代表逐提交已核；对**`ChatService.kt`/`GenerationHandler.kt` 这类重改文件上的上游修复**（尤其带 `fix(...)` 且关 issue 的）应逐条 grep fork 是否等价，而非按批次概括。本次即由「工具装配方盘点」顺藤摸到。

## 关联

[peer-apps-benchmark.md](peer-apps-benchmark.md)（A1 前台服务取舍/落地顺序基准）、[handoff-2026-09-04.md](handoff-2026-09-04.md)、[design-partial-failure-draft.md](design-partial-failure-draft.md)
