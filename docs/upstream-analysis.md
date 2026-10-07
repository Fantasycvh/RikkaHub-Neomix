# 上游变更分析（综合版，2.4.11 → 2.5.6+，2026-09-12 重写，2026-10-02 复核）

> 本文档合并原 `upstream-2.4.11/2.4.14/2.4.16/2.5.0-analysis.md` 四份分析（已删除）。
> 当前 master 同步点：**`b64beeef6`（2026-10-06 ff-only，16 提交：画板/Expressive 大批/压缩语义/imggen 废弃）**；此前 `645a5d88`（10-06）、`85af5b91`（10-04）、`a6dbb8cd`（10-03）、`2267943a`（10-02 二次）、`4e4bfa62`（10-02）、`b2d73a65`（09-30）、`00c8d53a`（2.5.5）、`458c16df`（2.5.2+）。此前 `85af5b91`（10-04）、`a6dbb8cd`（10-03）、`2267943a`（10-02 二次，mediagen/S3 两提交）、`4e4bfa62`（10-02，2.5.6+）、`b2d73a65`（09-30）、`00c8d53a`（2.5.5，09-28）、`458c16df`（2.5.2+，09-19）。此前 `a6dbb8cd`（10-03）、`2267943a`（10-02 二次，mediagen/S3 两提交）、`4e4bfa62`（10-02，2.5.6+）、`b2d73a65`（09-30）、`00c8d53a`（2.5.5，09-28）、`458c16df`（2.5.2+，09-19）。此前 `2267943a`（10-02 二次，mediagen/S3 两提交）、`4e4bfa62`（10-02，2.5.6+）、`b2d73a65`（09-30）、`00c8d53a`（2.5.5，09-28）、`458c16df`（2.5.2+，09-19）。此前 `4e4bfa62`（10-02，2.5.6+）、`b2d73a65`（09-30）、`00c8d53a`（2.5.5，09-28）、`458c16df`（2.5.2+，09-19）。**注：origin 上的 master 镜像已于 2026-09-15 删除，此镜像仅本地保留**。`a9b35ba2` 之后上游共 33 提交（至 `458c16df`），逐条评估见下方「2026-09-16」「2026-09-19」两节；评估结论均为**暂不吸收、按需取用**。
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
| `d3a53e0a` | 图片选择改 PickVisualMedia (#1821 选图落 CR 文件夹而非相册) | ⚠️ **已于 2026-09-21 回退（`af110d7f`）——吸收时漏看了上游两天前的 revert**。原记录：`e9c36591`（fork 4 处图片入口；ChatPage 视频/音频保留 GetMultipleContents），2026-09-12。**事实更正**：上游 `4b36640a`（2026-09-02）已**自行 revert** 该改动，commit 名即「恢复图片选择器的文件浏览入口」、close #1821；且 `merge-base --is-ancestor 4b36640a a9b35ba2` = **YES** ⇒ 上游的最终决定在 09-09 同步点就已进入 fork master，09-12 评估时**只看到这条「fix」标题、没查它是否已被推翻**。回退理由：系统 Photo Picker 只给相册视图、无文件浏览入口，照片多时翻找很慢（用户实证反馈）。**注：此处是来回拉锯的取舍**——#1821 的诉求恰相反（某些 ROM 上 GetContent 落到文件管理器而非相册），故代码里已留注释标明「有意与上游 `d3a53e0a` 相反」，下次同步勿无脑吸收 |
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
| `097cdb90` | ChatToolFactory 重构 | ~~不跟~~ **2026-09-14 已落地（`3893912f`，fork 适配）**：原判「零功能收益，除非出现第二个工具装配方」——**第二个装配方早已存在**（`GenerationHandler` 叠记忆工具），且用户明确要求重构，故实现。新增 `ChatToolFactory.createTools(settings, assistant, model, workspaceCwd)` 为**唯一装配点**（记忆+搜索+本地+对话+工作区+技能+MCP，顺序保持迁移前不变）；`GenerationHandler` 只透传 tools、去掉 `memoryRepo`；`ChatService` 以 `chatToolFactory` 取代 `localTools`/`skillManager`。**与上游差异（有意保留）**：MCP 名沿用 fork `McpNameSanitizer` 净化去重，而非上游 `InvalidMcpServerNamesException` 抛错——前者允许中文等非 ASCII 服务器名。同时把此前单独摘的 `shouldUseExternalWebSearch`（`0b562833`）迁入工厂包。**装配方盘点见下「注意点」第 9 条** |
| `b6df5f04` / `96fbe7e3` / `0533acde` | OAuth 重构 / videogen / 移除默认提供商 | 无实益：OAuth 抽包是纯结构搬迁（fork 功能已等价，且已吸收其后的 `8ea375a9` cloudflare 匹配 + `c49be6fe` 令牌刷新）；videogen 上游未接进 App（模块自 8/24 初始化后零提交，吸进来是死代码）；移除的是上游自家 rikka-ai.com 内置提供商 |
| `ef94834a` | MCP kotlin-sdk 换 client-only | 完整包 server 侧是 MCP host（⑥）依赖，待 ⑥ 立项时处理 |
| `7714f2bc` | chatbox v2 导入 | 按需 |
| `08c2648b` | 移除记忆 id 显示（close #1830） | **仍不吸收，但动机已被更好的方案取代（2026-09-14 `e416e7d4`）**：该提交是单文件删 5 行的纯展示层改动，且对其自身目的是**半截修复**——`buildMemoryPrompt` 仍 `put("id", memory.id)`、工具 schema/示例仍暴露**自增编号**，故 duzhaokun123 的第一条理由「避免助手乱猜测记忆数量」未被修复（只治了「用户看着难受」）。**fork 改走 opaque id**：`MemoryEntity.public_id`（8 位十六进制随机）+ `AssistantMemory.id` 改 String + DB 27→28 + 工具 schema/提示词/UI 同步，**同时根治「空洞难看」与「泄漏条数」且永不需要重排**，并兼容历史消息里的旧编号引用。**本仓仍显示 `#` 前缀 id**（现为 token）——保留可见性以支撑审批核对「改/删的是哪条」。详见 `design-backup-workspace-files.md` §10.1 |

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
| `2689e753` 起算的同步动作 | — | 同步方式仍是 ff-only（仅本地）：`git fetch upstream && git checkout master && git merge --ff-only upstream/master`（**不再 push origin**，origin 上的 master 镜像已删除） |

### 2026-09-16 新发现：上游已发 **2.5.2**（同步点后 6 提交）

`3428c60b` bump to 2.5.2。同步点 `a9b35ba2`（2.5.1）之后的全部提交：

| 提交 | 内容 | 评估 |
|---|---|---|
| `c8853531` | **fix(quickjs): 迁移到 quickjs-kt 并修复资源泄露**（close #1904；含依赖切换 + `search` 模块同改 + 两个新测试） | **部分吸收（2026-09-16，`641b46fe`）——只取防护，不迁引擎**。issue #1903 三点：fork ① 死循环挂起 ❌、② 无内存上限 ❌、③ context 未 destroy ✅（早已有）。**关键发现：fork 现用的 `wang.harlon.quickjs:3.2.3` 本身就提供 `setMemoryLimit`/`setMaxStackSize`** ⇒ ② 两行即可，无需迁依赖；① 该库无中断接口 ⇒ 独立线程 + `join(10s)`（超时路径**不 destroy**，否则 use-after-free）。顺带加固 `search` 的 `CustomJsSearchService`（同一库执行用户手写脚本，同样会拖崩 app）。**真机 instrumented 8/8 验证**（死循环返超时/大分配被拦且进程存活/深递归转 JS 异常）；纯 JVM 单测 10 项 |
| `5078ce19` | Refresh MiniMax regional and TTS defaults（#1911） | **真缺口（2026-09-19 核对）**：fork `TTSProviderSetting.kt:85` 默认仍是 `speech-2.6-turbo`，上游改 `speech-2.8-hd`（+配置页 placeholder 两行）。难度：小；注意只改默认值，已存 provider 不受影响 |
| `7ee13f2a` + `9a35e3f2` | **feat: 支持 blur/glass 2 种输入栏效果** + haze 版本升级（13 文件 108+） | **暂不吸收（2026-09-19 核对）**：fork 输入栏（`ChatInput.kt`）已重度自研定制（haze/glassHazeState 已用），上游效果是叠在新 haze API 上的外观选项，且捆绑音效资源（gangguan.mp3）。移植需在 fork 输入栏上重做效果分层，属外观增强非缺陷修复；若用户想要再评估 |
| `d47d13a6` | **fix: 完善所有语言的重新生成消息确认提示**（6 语言各 1 行） | **不吸收（2026-09-19 定评）**：fork 重生成已改为 **scope 对话框**（`ad3543b2`，含「先导出再继续」等 fork 特有选项），上游文案对应的是其单步确认弹窗，语境不匹配；fork 文案自有一套 |
| `8e304bb1` | **feat: 火山引擎 TTS 支持**（+120 行 provider + 配置 UI + 测试） | **可吸收（2026-09-19 定评）**：fork `speech` 模块 provider 结构同构（`TTSProviderSetting` 密封类 + provider 实现 + 配置 UI），纯新增不碰既有面。难度：小–中（包名适配 + 配置页接线）。价值取决于用户是否用火山 TTS |
| `d6ba728e`/`288a034c`/`bc9d2582` | 依赖更新 + 一次 `lib.versions.toml` 误删事故回滚 | 不吸收（同前述判断） |
| `bd936caa` | 非聊天请求 session id 头 | 不吸收（fork 无 sessionId 概念，见上） |

### 2026-09-30 同步：2.5.5 后 4 提交（`00c8d53a` → `b2d73a65`）

master 已 ff-only 快进至 `b2d73a65`（同步于 09-30）。逐条评估：

| 提交 | 内容 | 结论 |
|---|---|---|
| `95fed05e` fork 对话标题序号递增 | fork 出的会话标题 `Chat(1)` 再 fork 会变成 `Chat(1)(1)`——解析已有 `(N)` 后缀递增而不是叠加 | ✅ **2026-10-01 已吸收（`95fe3e5b`），真机验证通过**（三代连续分叉得 `rag测试(1)/(2)/(3)`，无 `(1)(1)`）。fork 版保留两处有意差异（空标题仍保持空、序号取首个未占用值）；单测 +5 项 |
| `d21265cb` MCP SDK 切 rikkahub fork `0.15.0-rikka.2` | `io.modelcontextprotocol:kotlin-sdk` → `com.github.rikkahub.mcp-kotlin-sdk:kotlin-sdk-client`（上游自己的 fork 版） | **暂缓**。fork 还在 `0.14.0` 原版（artifact 名也不同），跨 `0.14→0.15-fork` 两级跳；上游 fork SDK 的变更内容未核（需看 rikkahub/mcp-kotlin-sdk 仓库），且 fork 的 `McpManager`/`McpStatus` 是重改区。等有具体 SDK 层 bug 或功能需求时再一次性升级 |
| `b2525e7b` 模型列表按供应商折叠 | 点供应商标题折叠/展开，状态存 SharedPreferences，搜索时自动全展开；定位/偏移计算跟着 visible 列表走 | ✅ **2026-10-01 已吸收（`30a6b8e5`），真机验证通过**（折叠/展开、搜索自动展开、落盘持久化）。**fork 多出两处上游没有的联动必须一起改**：底部供应商 Badge 条的 `providerPositions` 与初始定位 `selectedModelPosition` 都要改用 visible 列表，否则折叠后 Badge 点击跳转错位 |
| `b2d73a65` 导出对话支持不含思考过程 | `ExportOptions.includeReasoning` 开关（Markdown/图片生效）+ 修 Markdown 导出思考过程多行拼一行 + 修图片导出无论成败都提示成功 + 修 `indexOf` O(n²)；**Export.kt 改名 ConversationExport.kt** | **建议吸收功能、不跟改名（手工适配，中）**。fork 的 `Export.kt` 是重改区（导出留存/SAF 真落地/原子写，534a5eef），逐块移植；改名不跟（fork 无同构历史，churn 无收益）。上游顺手修的三个 bug 需先核对 fork 是否同样存在（多行拼接疑似同样存在，fork :610 直接 `lines()` 处理路径需复核） |

**吸收优先级**：`95fed05e`（顺手修真 bug）→ `b2525e7b`（好功能）→ `b2d73a65`（三修一功能）；`d21265cb` 暂缓。

### 2026-10-02 同步：2.5.6 后 15 提交（`b2d73a65` → `4e4bfa62`）

master 已 ff-only 快进至 `4e4bfa62`（同步于 10-02）。**核心事件：上游自己修了 #1974 与 #1975——两项 fork 均已先行自研落地，且与上游实现等价或更优，零吸收负担**。逐条评估：

| 提交 | 内容 | 结论 |
|---|---|---|
| `cf79246b` **MCP `$defs`/`$ref` 内联展开（#1974）** | 新 `McpToolSchema.kt`（89 行）+ 153 行测试：任意 `#/` JSON Pointer（含 `#/definitions`）、循环引用回自身处只保留 `type`、同级键覆盖、只沿 schema 关键字遍历（literal 数据不动） | **已先行落地（`95fe3e5b`），极端场景已于 2026-10-02 回吸收敛（`651815b7`）**：循环引用截断保留 `type`、任意 `#/` 文档内指针解析（根文档=type+properties+$defs）、无 $defs 有 $ref 不再早退；常规路径零开销早退保留。单测 20 项 |
| `d5f0039b` **生成标题找不到快速模型给错误提示（#1975）** | 两处静默 `return@runCatching` → `throw IllegalStateException`（新文案） | **已先行落地且更优（`95fe3e5b` 同批）**：fork 版本地化文案 + `addConfigErrorOnce` 错误卡 + 返回 `started` 让调用方补 toast（上游只弹错误卡） |
| `654a5e1f` 提供商自定义请求头（#1952） | `ProviderSetting.customHeaders` + 高级设置 tab + `Request.kt` `mergeCustomHeaders`（**提供商头优先级最低，被助手/模型同名头覆盖**）+ 49 行测试 | ✅ **已吸收（2026-10-02，`a3394e7a`）**：fork 本就有字段与 UI，本次只吸收**去重语义**——新增 `mergeCustomHeaders` + 单一应用入口 `applyCustomHeaders(providerSetting, requestHeaders)`（覆盖语义），19 个调用点统一改造，旧的追加式入口删除。另加 MockWebServer 真实链路测试 |
| `620e38cc` **JsonTree 弹窗长按崩溃（#1977）** | BottomSheet 在独立窗口，沿用外层 `SelectionRegistrar` 长按抛 hierarchy 异常 → sheet 内自包一层 `SelectionContainer` | ✅ **已吸收 `799522f2`（2026-10-02）**：fork 同样中招（`LogPage.kt:458` 在 `:384` 的 `SelectionContainer` 内），改法与上游逐字等价；未修版真机复现 FATAL、修复版长按正常 |
| `2d5c51bd` 复制过大请求体剪贴板闪退（#1980） | 复制按钮 try/catch `TransactionTooLargeException` + toast | **fork 已覆盖**：fork `LogPage` 请求体走 `JsonTree` 展示（无复制按钮，原崩溃路径不存在）；fork 的 `ContextUtil.writeClipboardText` 本就 `runCatching` + toast（RuntimeException 子类全兜住） |
| `4e4bfa62` **OpenAI 图片生成自定义分辨率被误丢弃（#1978）** | Grok 判断改解析 host（`x.ai` 及子域名，防中转域名 `xxx-max.ai` 误判）+ `size=auto` 不写入请求体 | ✅ **已吸收（2026-10-02）**：判定抽成纯函数 `isXaiHost`/`shouldSendImageSize`（+7 单测），fork 另把「auto 不写」同规则用于 `/images/edits`（上游未改）；真机请求体实测三例 |
| `3443dd45` 注册 Claude Opus/Sonnet 5.5，移除 Claude 3 | 5.5 两条注册（`notTokens` 排除 4 代、1M context）+ 删 3.5/3.7 | ✅ **已吸收（2026-10-02，`5a25a42a`）**：5.5 两条已注册（IN/ABILITY/1M context 三层断言）；**有意保留** 3.5/3.7——删掉会让 `claude-3.5-sonnet` 转投 `CLAUDE_SONNET_5` 并凭空拿到 1M 上下文，保留时更具体的 3.5 条目按匹配度取胜。另顺带补上此前漏吸收的 `aa487c8c`（Claude 5 两条） |
| `3a9ae690` 注册 Gemini 4，推理参数用 `thinkingLevel` | GEMINI_4 注册 + GoogleProvider 推理参数按模型切换 `thinkingLevel`/`thinkingBudget` | ✅ **已吸收（2026-10-02，`5a25a42a`）**：GEMINI_4 进 GEMINI_SERIES 与 ALL_MODELS；GoogleProvider 抽出 `useThinkingLevel = 3 系列 ∪ GEMINI_4` 后两处分支改用它——**这里 fork 与上游本就同形**（fork 早已有 `GEMINI_3_SERIES` 的 thinkingLevel 分支，只是没有 GEMINI_4），所以「thinkingLevel 是 fork 没有的 Model 概念」一说**完全不成立**（2026-10-02 复核明确）：上游全仓 grep 只有两类 `thinkingLevel` —— generateContent 请求体字段（fork 早已有）与 Interactions 请求体的 `thinking_level`（属 `2cd62ad2`，随它一起来），**上游从来就没有 Model 层面的这个概念**，本项无需引入任何新东西。`tokens("gemini","4")` 的吞噬面已用测试钉住（`04` 之类两位数 token 不算 4） |
| `7a53065a` OAuth 回调改 `localhost`（#1972，WAF 拦 IPv4 403） | redirect URI 主机 127.0.0.1 → localhost，回调服务器仍绑 127.0.0.1 | **不适用**：fork MCP OAuth 用自定义 scheme `rikkahub://mcp-oauth-callback`（无 loopback 服务器），请求体不含 IPv4 主机 |
| `4ba5d79f` + `b8e0fec4` MCP OAuth 增强（#1979） | 受保护资源元数据缺失回退 origin + 首次 401 触发 OAuth；CIMD 文档作 client_id | **暂缓（中）**：fork `McpOAuthCoordinator:138` 已有 protectedResource 发现，回退逻辑与 CIMD 是增量增强；fork OAuth 实现已与上游分叉（自定义 scheme），手工适配成本 > 收益，等有真实服务器撞上再说 |
| `2cd62ad2` **Gemini Interactions API (Beta)** | 新 API 面（576+289 行 API/解码器 + 499 行测试）：`store=false` 无状态、thought/function_call 签名按**新 key** 回传、支持搜索/URL context/图片输出、`thinking_level`（无 budget） | **暂缓（大）——但触发条件已明确（2026-10-02 复核）**。**为什么不现在做**：①上游自己是**开关 + 默认关闭 + Beta**（`useInteractionsApi`，Vertex 下不生效）；②fork 的 Gemini 流量基本走 OpenAI 兼容中转 ⇒ 中转不转发 `/interactions` 时该开关对本仓用户等于不可用，**前置条件是「有直连 Google 的 key」**；③fork `GoogleProvider`（855 行）是重定制区，新路径等于**同一套语义维护两份**，且上游首次接入就漏了三处本仓已有/必需的东西——**提供商级自定义头没带**（只 `.headers(params.customHeaders)`）、`sessionId`/`configureSessionHeaders` fork 根本没有这个字段、`ensureArrayItems` 是 fork 独有的 schema 净化（不加则带数组无 items 的工具 schema 在新路径上直接 400）；另有 `safety_settings` 在新 API 上不可用（上游 commit 明说，而 generateContent 路径两边都发）。**什么时候值得立项（命中任一）**：T1 需要**只有新 API 才有**的能力（Deep Research 的 `background=true` + 轮询、托管 Agent/沙箱、视频生成、Google 侧 file search/maps grounding/flex-priority）；T2 拿到直连 Gemini Developer API key 并确实要用；T3 `generateContent` 被弃用或目标模型只在 `/interactions` 提供（Google 已把新文档集中到 `/interactions` 且 2.5 及更早标为 legacy，这是「不得不做」信号）；T4 Beta 摘帽、breaking change 进入平稳期后再接（返工风险最低）。**立项时顺手要做的三件事**：把我们统一的 `applyCustomHeaders(providerSetting, params.customHeaders)` 用在 `buildRequest`、把 `ensureArrayItems()` 补进函数工具的 parameters、并决定 `safety_settings` 的处置（要么在开关打开时提示「安全设置仅 generateContent 生效」，要么隐藏该项）。 |
| `282b85f2` 复制助手可同时复制记忆 | `copyAssistant(assistant, copyMemories)` + `MemoryRepository.copyMemories` + AssistantPage UI | ✅ **已吸收（2026-10-02，`27b35815`）**：fork 照做了 UI 与接口；落库细节按 fork 的记忆表改造（**新的 publicId、保留 importance、updatedAt 取当下**，上游只搬 content 会写出坏数据：共用 token 会让审批指错行、丢重要度会让打分退化成纯时效）。+4 单测 + 真机验证 |
| `9435b8a2` / `447bb7e8` | skills 文档更新 / 版本 bump 2.5.6 | 不跟（master 同步已带入 .agents 文档；版本号体系无关） |

**吸收优先级**：~~`620e38cc`~~（已吸收 `799522f2`）→ ~~`4e4bfa62`~~（**已吸收**）→ ~~`3443dd45`/`3a9ae690`~~（**已吸收**）→ ~~`282b85f2`~~（**已吸收**）→ ~~`654a5e1f` 头去重语义~~（**已吸收**）→ `2cd62ad2`/`#1979`（暂缓）。

### 2026-10-02 二次同步：2.5.6 后 2 提交（`4e4bfa62` → `2267943a`，#1984 媒体生成）

master 已 ff-only 快进至 `2267943a`（同步于 10-02）。**⚠️ 更正（2026-10-03 发现）：当时的 ff-only 实际漏执行——本节记账时 master 仍停在 `4e4bfa62`，直到 10-03 同步 `a6dbb8cd` 时才一并快进。教训：报「已同步」前必须看 merge 命令的输出，不能只凭评估流程写到哪一步**。两个提交是一套：videogen 重构为 mediagen + 为其配套的 S3 presign。另发现上游新分支 `feat/media-gen`，核对后与 master 无差异（S3 提交已随 `2267943a` 合入，分支上是同内容的独立提交）。

| 提交 | 内容 | 结论 |
|---|---|---|
| `a26d1d55` **videogen → mediagen 重构（图像+视频统一）** | 模块改名 + 统一 `MediaGeneration*` 请求/任务/供应商接口；setting 改**厂商级**（只存凭据+models，模型带 kind 图像/视频）；新增 OpenAI 图像生成/编辑、火山 Seedream、阿里万相/千问适配器（同步接口直返终态任务）；阿里换带业务空间 ID 的新域名（新增 workspaceId）；`MediaGenerationManager.generate` 统一同步/异步轮询两链路；按厂商分包 + ~900 行单测 | **暂不吸收（维持 `96fbe7e3` 判定，且上游仍未接线）**。fork 无 videogen 无存量冲突，但**上游 app 至今零消费点**（`git grep mediagen 2267943a -- app/src` 只有 build.gradle 模块引用；4e4bfa62 同样无 videogen 消费）⇒ 现阶段吸收=死代码。fork 图像生成走 `OpenAIProvider.generateImages`（imggen 页），视频生成无功能。**前提已失效（2026-10-07 注）**：上游已由 `0450c4c1` 正式接线进 app，本条「零消费点=死代码」论据作废——专项迁移评估见 10-06 二次同步节末尾的 2026-10-07 块。原「重新立项的条件」：想要「厂商级媒体生成」（火山 Seedream/阿里万相这类 chat provider 之外的渠道）或上游把 mediagen 接进 UI 时再评估；届时注意 fork 的 per-provider 代理/key 轮换体系与其厂商级 setting 的关系。阿里的新域名/workspaceId 变更随模块走，fork 无直连阿里媒体生成，单独不适用 |
| `2267943a` 的 S3 部分：**presignGetUrl 带签名临时下载地址** | `AwsSignatureV4.presignGetUrl`（查询参数签名、只签 host、1s–7 天）+ `S3Client.presignGetUrl(key, expires)` + 顺手把主机/路径计算抽成 `requestHost`/`canonicalUri` 公用函数 + 106 行测试（AWS 文档示例值校验签名） | **可吸收（小，~80 行 + 测试），消费点暂缺**。fork 的 `data/sync/s3/` 三文件与上游同源；逐行 diff 核实：fork 内联的 host/canonicalUri 计算（含 pathStyle 与 host 已含 bucket 两分支）与上游新抽的 helper **功能等价**，fork 的 `S3Client` 另有分片上传/进度回调等扩展（上游无）⇒ 吸收 = ①先做无行为变化的 host/路径提取重构 ②加 presignGetUrl（fork 签名实现支持 queryParams，改造小）③测试直套。**现实用途**：备份文件生成临时分享链接（桶保持私有）；mediagen 的产物分享场景 fork 不存在。可选吸收，等有 UI 入口再做也行 |

### 2026-10-06 二次同步：`645a5d88` → `b64beeef6`（16 提交）+ 新 issue（#2122–#2125、#1997–#1998）

**按域分组评估**：

| 域 | 提交 | 结论 |
|---|---|---|
| **画板（新功能域）** | `9be5e5aa6` 新增画板（聊天+媒体创作手绘/圈画）+ `d7f240d7e` androidx.ink 压感/低延迟/多笔刷 + `19a5e2310` 文字/选择工具 | **大，方向级**：新依赖 androidx.ink + 新功能域。等用户需求 |
| **MD3 Expressive 大批** | `77d37c7d6` 聊天消息 + `baa4f8364` 加号面板（MCP 与扩展面板内切换）+ `8c1b7d949` 扩展选择面板 + `2b0d882d5` 紧凑布局 | **维持不跟**（需 BOM 2026.09.00 + material3 alpha29，同 db1ce811 结论） |
| **压缩语义** | `bf614d89a` 压缩改为插入摘要检查点、不再删除历史消息 | **fork 领先**：fork 09-13 的 harness 式压缩本就是「轨迹只追加 + 检查点合成消息 + 可展开回看原文」——上游现在才收敛到同形态。零动作 |
| **配置固定会话** | `b64beeef6` 会话开始后把模型/思考级别/搜索固定在会话上 | 产品语义决策（防 #1995 列表跳动同族的「配置漂移」）；与 fork「每次装配取当前设置」不同。**维持观察，语义分析已记录（2026-10-07，见下）** |

**`b64beeef6` 语义分析（2026-10-07，维持观察）**：批次内语义最重的一条（53 文件 +1824/-655，DB 26→27 + 设置迁移 V4 + web 配套）。改动前模型/思考级别/搜索/MCP/工作区/技能全存**助手**上——会话 A 里换模型=改助手本身=所有同助手会话（含后台生成中/历史会话）跟着变（「配置漂移」）。改动后：新会话无快照、聊天页切换仍直改助手（保留「新会话当助手编辑器」习惯）；**会话首次落库时**从助手拍 `ConversationConfig` 快照存会话表新列（`chatModelId`/`reasoningLevel`/`enableWebSearch`/`builtInSearch`/`mcpServers`/`workspaceId`/`enabledSkills`），此后切换只写会话自己；温度/TopP/最大token/上下文条数/记忆/正则等不影响请求形状的仍跟随助手；模式注入/Lorebook 本就在 Conversation 上，顺势归入固定范围；**拆掉助手「独立对话提示词注入」开关**（注入随会话固定后冗余，V4 迁移清空开着开关的助手绑定）。边界全处理：固定模型被删→退回助手当前模型并固定；MCP/注入/Lorebook 引用被删→读取过滤；换模型→内置搜索跟新模型开关；换工作区→清 workspaceCwd。**核心收益=前缀缓存稳定性**：固定范围每项都改变请求形状（换模型=缓存全废；MCP/技能/注入=tools/system 块变=前缀断点失效），fork 的前缀分叉诊断里「配置漂移」正是一大类分叉来源，上游此举=从源头消灭一整类分叉、与 fork 缓存线同向。**吸收成本=中型项目非小件**：fork DB 版本号已与上游完全分叉、ChatService 是 fork 改动最重的文件、web 路由要配套 POST /config、设置迁移 V4、「拆独立注入开关」与 fork 刚接线的 promptPresetIds 注入体系有取舍。**观察期触发点**：①用户确认接受语义反转（旧会话不再跟随助手改动）+ 单独排期立项；②fork 实测出现配置漂移导致的缓存分叉（诊断工具归因到 config 变化）时优先级上调。

**imggen → mediagen 迁移专项评估（2026-10-07，用户问「能带来什么」，结论=触发点立项不预设）**：
- **fork 现状**：imggen 复用 **chat provider 体系**（`ProviderManager` + `ImageGenerationParams`/`ImageEditParams`，OpenAI 兼容 images 接口），一个中转站 key 即用、同步等待返回、产物存 `GenMediaEntity`；已吸收 `85af5b91` 的 OpenRouter 修复（文件名清洗/旧路径回退）。上游 imggen 页仍在但已挂废弃警告（`1c8c3fd37`）。
- **mediagen 增量**（五家适配器：OpenAI/阿里万相/火山 Seedream/MiniMax/OpenRouter）：①**视频生成**（fork 完全没有）：时长/分辨率档位/配音频/首尾帧/图生视频，分钟级异步任务轮询——fork 同步 imggen 架构装不下；②**能力声明矩阵** `MediaGenerationCapabilities`：UI 按接口实际支持动态展示参数（数量/像素或档位分辨率/宽高比含 adaptive/种子/水印/厂商提示词改写），fork 现有 size/quality 硬编码粗得多；③**产品面**：媒体创作页+会话记录+封面+S3 素材库+前台服务（`0450c4c1`）；④上游 imggen 已废弃，留在旧轨道=后续相关修复无法吸收（#2123「Agent 工具唤起图片生成」也是与 mediagen 配套的）。
- **关键取舍**：mediagen 是**独立厂商级 provider setting**（单独 key/模型列表），不复用 chat provider ⇒ **中转站用户要多维护一套厂商凭据**；视频接口各家差异大、New API 类中转基本不代理 ⇒ 想要视频只能厂商直连。若只做图像，现有 imggen+中转站已覆盖主路径，mediagen 图像增量（种子/水印/宽高比）价值有限。
- **成本**：中型项目（新模块+UI 迁移+DB 落点+fork imggen 修补带走），2–4 天量级。
- **决策**：**触发点立项**——①用户需要视频生成（Seedance/万相/海螺任一）或用上厂商直连 key ⇒ 迁移一步到位（顺带解决 imggen 废弃）；②继续以中转站图像为主 ⇒ 维持现状用到上游真删。上游 `feat/media-gen` 与 master 同内容，短期无再大动迹象，不必怕「等一等过时」。
| **imggen 废弃** | `1c8c3fd37` 图片生成页显示废弃警告，推荐媒体创作 | 官方路线确认：imggen → 媒体创作（mediagen）。**专项迁移评估见下方 2026-10-07 块** |
| **主题** | `db81bb6c0` 自定义主题用 2025 色彩规范 + 更新 material-color-utilities | 我们 submodule 同仓（fetch 已见 main 5b3618b 更新）；主题域按需 |
| **重构/性能** | `7c0113173` 通用组件下沉 ui 模块 + `d1c81bf99` LocalSettings 改 compositionLocalOf | **`d1c81bf99` fork 同款实锤**（我们也是 `staticCompositionLocalOf` ⇒ 设置一变整树重组）——性能修复小而实，**已吸收 `c1e700968`**；组件下沉是结构重构不跟 |
| **修复** | `c211c0d44` **搜索结果不再被工具输出截断（#2000）** + `97060dd51` 输入栏渐变遮罩 | **`c211c0d44` fork 同款实锤**：fork `maybeTruncateToolOutput` 对所有工具一刀切，`search_web` 结果超 32K 同样截断成非法 JSON（结果列表 {}、引用失效、模型只见前几条）。**已吸收 `d9c429dec`**：豁免集合 `TOOLS_WITHOUT_OUTPUT_TRUNCATION` + 详情渲染回退；渐变遮罩小 UI 按需 |

**新 issue**：#2125 手动编辑聊天标题——**双方都有**（fork TopBar onUpdateTitle；上游 master 同样有完整编辑流含「先聊天再编辑」守卫，报告人是没发现入口）——平级，非 fork 领先（2026-10-06 更正：初评误判为 fork 领先，未对照上游代码）；#2124 透字（导航栏/输入法间隙，UI 细节，fork 待复现）；#2123 Agent toolcall 唤起图片生成（与上游 mediagen 接线配套的本地工具——fork 若走 mediagen 路线时同类需求=「imggen 工具化」，灵感级）；#1997 向量化归档+按需召回（closed；与压缩新语义相关，观察）；#1998 思考强度 UI（被 `80c6b07d` 落地）。

**吸收优先级**：`c211c0d44` 搜索截断 ✅ `d9c429dec` → `d1c81bf99` LocalSettings 性能 ✅ `c1e700968` → 其余按需。

### 2026-10-06 同步：`85af5b91` → `645a5d88`（3 提交）+ issue 灌水事件

**⚠️ #2001–#2116（35 条，同日）为机器人批量灌水**：用户名全随机（`star-bot-*`/`KernelSU-User*`）、
同一 bug 标题变体（「定时任务 Busy 重试时间漂移」）、内容引用 `backend/src/schedules.rs`（**Rust 后端**
——RikkaHub 是纯 Kotlin 应用，内容是从别的项目复制的），全部被 github-actions bot 按无模板自动关闭。
零信号，无评估对象。

| 提交 | 内容 | 结论 |
|---|---|---|
| `0450c4c1` **媒体生成功能接线（#1996，大）** | **mediagen 正式接入 app**：DB 迁移（MediaCreationDAO/Entity）+ `MediaCreationService`（566 行）+ 前台服务 + **S3 素材上传**（RemoteFileStore 232 行 + S3Config 扩展）+ 完整 UI（Composer 1049 / RecordItem 658 / Sessions 265 / SettingMediaPage 505 / ChatDrawer 入口 / VideoPlayerDialog）+ 备份集成（BackupManager）+ ChatDrawer 改动 | **mediagen「app 零消费」的前提已失效——结论从「暂缓（死代码）」更新为「已接线、按需立项」**：完整功能面 = 视频/图片生成会话 + S3 素材库 + 前台服务 + 记录管理。fork 吸收工程量大（DB 迁移适配 fork 版本线、S3/FilesManager/ChatDrawer 均重改区），且 fork 已有 imggen（图片生成）——mediagen 增量=视频 + 会话管理 + S3 素材库。触发条件=用户需要视频生成或素材会话管理。**注意其 BackupManager 改动**：上游备份格式又将变化，互通测试时留意 |
| `80c6b07d` + `645a5d88` **MD3 Expressive ×2**（思考深度选择器 / 搜索选择器） | 依赖 composeBom 2026.09.00 + material3 1.5.0-alpha29（fork: 2026.06.01 + alpha25）；MaterialShapes 等 Expressive API | **维持不跟（理由同 db1ce811 评估）**：需先升 BOM/material3（全 app 回归面）。与既有的「ExpressivePopup 已吸收（alpha25 可实现）」不冲突——那件用的是 alpha25 已有 API。等 material3 升级顺路再评 |

本批干净：未触及 ChatService/GenerationHandler。

### 2026-10-04 同步：`a6dbb8cd` → `85af5b91`（2 提交，先不吸收）

| 提交 | 内容 | 结论 |
|---|---|---|
| `85af5b91` **imggen：OpenRouter 图片编辑 404 + 模型名含斜杠图片丢失（#1993）** | ①OpenRouter 无 `/images/edits`，编辑改走 `/images/generations` + `input_references`（base64 data URL）；②模型名含 `/`（OpenRouter `vendor/model`）拼进文件名变子目录而记录只存最后一段 → 新增 `toFileNamePart()` 清洗 + 旧记录兼容读取（+100 行测试） | **建议吸收（小，fork 两处同款核实）**：fork `OpenAIProvider:373` 同样打 `/images/edits`（OpenRouter 用户编辑必 404）；fork `ImgGenVM:293/:308` 文件名构造同款（`${modelName}` 裸拼）——OpenRouter 模型名的 imggen 产物已裂（落 images/ 子目录、记录找不到）。吸收=三处：编辑分支 + `toFileNamePart` 清洗 + 旧记录兼容 |
| `298e2771` **Alpine rootfs 支持** | tar 根目录 `./` 规范化空串跳过；`/bin/sh` 软链 busybox 也算 rootfs 可用；无 bash 回退 `/bin/sh`（工具执行与终端共用）；系统提示告知实际 shell 与发行版（os-release） | **暂缓/按需**。fork rootfs 路线=ubuntu 预装（RootfsPatcher 补字体/数学）；Alpine 是新发行版能力，与 rootfs 改进待办①「自托管预装 rootfs」方向重叠。**其中「系统提示告知实际 shell/发行版」对 fork 独立成立**（fork 的提示同样基于 bash/apt 假设），可单独摘 |
| 分支 `feat/mediagen` | 新增 `4f25be81`（适配器能力声明 + 视频接口 count 只收 1） | mediagen 仍在演进（app 仍零消费），维持暂缓观察 |

**吸收优先级**：`85af5b91`（小，OpenRouter 图片用户真故障）→ `298e2771` 的提示告知小件 → Alpine 本体按需。

**✅ 2026-10-04 落地（按上述审计顺序，四批）**：

| 批 | 提交 | 内容 |
|---|---|---|
| ③ | `17440542` | tar 根条目 `./` 不再让整次 rootfs 安装失败（**与发行版无关**：`tar -C dir -czf x.tar.gz .` 默认带它，而安装对话框允许用户粘任意 URL）|
| ① | `cfbaa0aa` | OpenRouter 编辑走 `/images/generations` + `input_references`（host 精确相等判定）；模型名含 `/` 的产物文件名清洗 + 旧记录读取兼容（`toFileNamePart`，覆盖临时预览与相册落盘两处）|
| ② | `bd8a2568` | `images/` 改**递归**打包（`RECURSIVE_FILE_FOLDERS`），让遗留子目录里的老图换设备恢复后也能找回 |
| ④ | `83965968` | 系统提示告知实际 shell（新增 `rootfsShellFor`，无 bash 回退 `/bin/sh`，工具执行与终端页共用）+ 发行版（`os-release`，`parseOsReleaseName` 净化控制字符/截断/容错键空格）|

**仍未吸收（按需）**：Alpine 本体（`hasRootfs` 对绝对软链 `bin/sh` 的判定、Alpine 专属解包细节）——与「自托管预装 rootfs」同批；届时 `hasRootfs`/`hasUsableRootfs` 的两处同名判定可与 `rootfsShellFor` 一并收敛。

#### 2026-10-04 独立复核（覆盖审计，两条更正 + 两条新增）

逐条代码级复核上述两提交在 fork 的覆盖情况，结论有三处需要修正/补充：

1. **①两处确认存在**（与上述一致）：`OpenAIProvider.kt:373` 无条件打 `/images/edits`；`ImgGenVM.kt:293`（临时预览）与 `:308`（相册落盘）都裸拼 `modelName`，而记录写的是 `"images/${imageFile.name}"`（只剩最后一段）⇒ 含 `/` 的模型名（OpenRouter `vendor/model`）产物落在 `images/<ts>_<vendor>/` 子目录、记录指向不存在的 `images/<model>_<i>.png`。吸收可行性：fork 已有 `mergeCustomBody`（`ai/util/Request.kt:79`）、`imageMediaType()`（`OpenAIProvider:434`）、`parseImageResponse`（`:391`）、`ImageEditParams.images` ⇒ 机械移植，含上游的旧记录读取兼容。
2. **新增（备份交互，上游没这层）**：我们的 `images/` **是平铺打包**（`addAppFilesToZip` 只对 `skills` 递归）⇒ 那些 legacy 子目录文件**根本不进备份**。故「补上旧记录读取兼容」之后还要决定：**递归打包 `images/`**（跨设备恢复能救回老图）还是**明确丢弃**（记录已裂、救回也无入口）。不递归就必须在 `BackupCoverageTest` 旁记一笔「已知不备份形态」，避免下次又当成漏打包。
3. **更正（把 ② 里一项从「Alpine」降级为「与发行版无关」）**：tar 根条目 `./` 的处理**不是** Alpine 专属。fork 的 `RootfsInstaller.normalizeTarPath` 对 `./` 会走到 `require(isNotBlank)` **抛异常**，且它是在 **`readTarHeader` 内部**被调用的——调用点那句 `if (header.name.isBlank()) { continue }` 位于其后，属**死代码**（异常先抛出）⇒ 任何含 `./` 条目的 tarball 安装直接失败（`Rootfs entry path is blank` → 状态 BROKEN）。而 fork 的 rootfs 安装对话框**允许用户粘贴任意 URL**（`WorkspaceDetailPage:1077` 可编辑，默认值只是预置 ubuntu），用 `tar -C dir -czf out.tar.gz .` 自制的 tarball **默认带 `./`** ⇒ 与待办「自托管预装 rootfs」正是同一条路。上游修法（返回空串、调用方跳过）与发行版无关，**建议独立吸收（极小，改 1 个函数 + 调用点断言）**；注意它有测试影响面：`RootfsInstallerTest` 目前无该行为断言，吸收时补一条「根条目被跳过而非报错」。
4. **更正（并行评估的一处措辞）**：「fork 的 workspace 提示同样基于 bash/apt 假设」**不准确**——fork 的 `buildWorkspacePrompt` 与 `workspace_shell` 工具描述**都没有** bash/apt 字样（grep 为空），只是**不告知**环境细节；真正硬编码 bash 的是**代码两处**：`ProotShellRunner.kt:141` 与 `WorkspaceTerminalSession.kt:77-78`。故上游提示件对 fork 的价值是「**补充信息**（模型少猜）」，而非「修正错误假设」；而只要将来支持非 ubuntu rootfs（用户自制/Alpine），那两处硬编码 + `hasRootfs` 的 `bin/sh` **isFile** 判定（绝对软链在宿主机视角解析不到 ⇒ 误判未安装，上游 `hasRootfsEntry` 已修）就会一起显形，宜与 rootfs 支持同批处理。

### 2026-10-03 同步：`2267943a` → `a6dbb8cd`（1 提交，#1990 图表工具）

| 提交 | 内容 | 结论 |
|---|---|---|
| `a6dbb8cd` **Response API 函数工具显式 `strict: false`** | 不传 strict 时 Response API 默认按严格模式处理，schema 可选字段全变必填 ⇒ 模型硬填默认值（0/""/[]）⇒ 工具参数校验失败（#1990 报的正是 `chart_display`：GPT 调折线图时 `x_axis.format=""`、`min/max=0` 被校验拒绝；DeepSeek 不走该路径故正常） | ✅ **已吸收（`ResponseAPI.kt` + ResponseAPIMessageTest 单测）**。fork 的 `chart_display` 走 Response API 提供商（GPT 系列 + useResponseApi）时同样受影响，吸收即修 |

### 2026-09-28 同步：2.5.2 → 2.5.5（`458c16df` → `00c8d53a`，36 提交）

master 已 ff-only 快进至 `00c8d53a`。按价值分类（吸收进度见各节，A 类前两批 `24dcdcce`+`47d5d81c`、C 类 chart_display 已吸收）：

#### A. 真缺口，建议吸收（按价值）

| 提交 | 内容 | fork 现状 / 吸收要点 |
|---|---|---|
| ~~`70b382f5`~~ | **fix(settings): 读取失败时设置被意外重置为默认值** | ✅ **2026-09-28 已吸收（`24dcdcce`）**。fork `PreferencesStore.kt:234` 正是 `catch { if (IOException) emit(emptyPreferences()) }` ⇒ **读失败即拿空 Preferences 当用户数据**，随后任何 update 会把默认值写回、抹掉全部设置。上游改三件：①`retryWhen`（IO 错指数退避重试 N 次，仍失败**向上抛**，绝不回退空值）②`ReplaceFileCorruptionHandler`（真损坏才重建，且**先把原文件备份为 `.corrupt-<ts>`**）③`incrementLaunchCount` 改**单 key 原子 edit**（原为读整份快照再写回）。注意 fork 的 `settingsFlowRaw` 有既有注释「勿动」（MCP 并发丢工具根因相关），改前需看那段上下文 |
| ~~`5ac149b9`~~ | **fix(openai): Responses API 工具参数事件缺 `item_id` 时回退到 `call_id`/`output_index`**（#1948） | ✅ **2026-09-28 已吸收（`47d5d81c`，按 fork 内联结构重写为 `ResponseToolCallIdResolver`）**。**并发现本仓原先就是错的**：`buildMessages` 回传用 `put("call_id", tool.toolCallId)`，而流式路径用 `item.id`（`fc_xxx`）⇒ 回传的 call_id 不符协议。原评估：**中转站场景真缺口**。fork `ResponseAPI.kt` 内联解码处仍是 `error("item_id not found")` **硬失败**（`:697`、`:766` 等）。上游抽出独立 `ResponseApiStreamDecoder` 并加：`itemIdOrCallId()` 回退 + `outputIndex` 映射表 + `resolveToolCallId`。**fork 是内联实现、结构不同 ⇒ 须手工适配**（不能 cherry-pick） |
| ~~`ed3569c7`~~ | **fix(workspace): 文件编辑器不再把全文存进 saved state，修大文件切后台崩溃**（#1953） | ✅ **2026-09-28 已吸收（`24dcdcce`）**。 fork `WorkspaceFileEditorPage` 用 `rememberTextFieldState()`（全文进 Bundle）⇒ 大文件切后台 `TransactionTooLargeException`。改 `remember(id, area, path) { TextFieldState() }`（内容本就由 LaunchedEffect 从磁盘加载）。**同提交也修了 `SkillDetailPage` 的 `rememberSaveable`→`remember`** |
| ~~`9f02586d`~~ | **fix(skills): 保存失败不再崩溃 + GitHub 导入按字节保存（防二进制损坏）** | ✅ **2026-09-28 已吸收（`47d5d81c`）**：`saveSkillFile` 改临时文件+rename（失败返 false 不抛）。**上游另一半本仓已具备**——`SkillsVM` 导入路径早就是字节下载（`b4e82baf` 自研）。要点：保存失败返回 Result 而非抛；导入走字节流而非 String（避免二进制被当文本解码损坏） |
| ~~`8b696c0c`~~ | **fix(mcp): 忽略名称为空的自定义请求头**（#1949，报 "name is empty"） | ✅ **2026-09-28 已吸收（`24dcdcce`）**。 fork `McpSessionRegistry.resolvedHeaders()` 无过滤 ⇒ 设置页留空 header 会让 MCP 连接报错。一行：`filter { it.first.isNotBlank() }` |
| ~~`54aa8114`~~ | **fix(favorite): 收藏页侧滑删除失效** | ❌ **2026-09-28 决定不做**（用户）：已有可点击的删除按钮（`FavoritePage:355` `IconButton(onClick = onDelete)`），侧滑只是冗余手势，失效不影响功能。 fork `FavoritePage:281` 用 `dismissState.currentValue`；上游指出**该值动画中途会翻到最近锚点 → LaunchedEffect 重启并在 onDelete 前被取消**，改 `settledValue`。fork 有 `key(item.id)`+`reset()`（`aa2c9a53`）但未改此值 ⇒ **很可能仍失效，建议真机先复现** |
| ~~`324b337b`~~ | **fix: 避免代码块行号被复制** | ✅ **2026-09-28 已吸收（`24dcdcce`，两处行号渲染都包 `DisableSelection`）**。 fork `HighlightCodeBlock` 行号未包 `DisableSelection` ⇒ 选中代码会带上行号。小改 |
| ~~`4391d5a5`~~ | **fix(google): 过滤工具参数中不兼容的 `propertyNames`** | ✅ **2026-09-28 已吸收（`24dcdcce`，两处同源清单一起加）**。 fork `GoogleProvider` 的剥离清单缺 `propertyNames`（Gemini 会拒）。**连带**：`ai/util/ToolSchemaSize.GOOGLE_STRIPPED_SCHEMA_KEYS` 是同一清单的副本，两处都要加 |

#### B. 已重叠 / 无此问题（不吸收）

- `21448350` 工作区长按多选导出——**fork 已自研**（`7d8e4592`/`26e9fa29`，两种产物+批量删除，比上游更完整）
- `f099cd40` 技能页按名称/描述搜索——**fork 已做**（`b4e82baf`，#1901）
- `2cf09d2a` 思考步骤图标黑底（#1946）——fork `ChainOfThought` **未用** `CompositingStrategy.Offscreen`+`BlendMode.Clear`（上游根因），结构上无此 bug
- `42517943` docs「不再接受 PR」——仓库策略变化，与本仓无关（**但对 fork 有影响：今后同步只剩维护者直推**）

#### C. 新功能（按需，非缺陷）

| 提交 | 规模 | 备注 |
|---|---|---|
| `00c8d53a` chart_display 本地工具 | 大（工具 252 + ChartCard/Plot/Scale ~760 + 渲染） | 在聊天中画折线/柱/散点图；纯新增。**与 fork 的 TikZ/Mermaid 渲染链是并列能力**，需评估是否重复投入 → **已于 2026-09-29 吸收**（工具+ChartSpec/Scale/Plot/Card/Table 五组件+ChatMessageCot `ChartBlock` 分组+Export 渲染+设置页开关，单测 11 项；**适配点=hugeicons 1.3 无 `Table` 图标，用 `GridTable` 代替**） |
| `3b346748`+`7f17dcc8` 内置技能 + skill-creator | 中（BuiltinSkills 87 + SkillManager 改造 + 内置 skill-creator 技能） | 内置技能与 fork 的 `enabledSkills` 体系需对齐；元数据转义/限长是防御性修补 → **已于 2026-09-30 吸收（`7f649e00`），真机验证通过**（启动解压/列表徽标/详情只读；单测转义+合并+assets 合法性；上游 SkillFile 重构同步吸收；fork 保留 category/save_skill 原子写等自研增强） |
| `233e095a`+`7508eee9`+`598b4efa` 阿拉伯语 + RTL | 大（6→7 语言 + 全仓方向图标 + locale-tui 术语表） | fork 目前 6 语言；RTL 涉及全仓 UI 方向性。**若非阿语用户则不值** |
| `b9c0d3b7` 数据恢复页（从聊天记录恢复助手） | 中 | fork 已有助手墓碑（`5de1ffa9`），此页是另一种恢复路径 |
| `eaa003ad` 图片生成快捷方式 / `53f224b1` ExpressivePopup / `db1ce811` 搜索选择器 MD3 分段 | 小–中 | 体验增强 |
| `068103dd` mimo v2.6 注册 / `c3d6867c` step-5 | 小 | fork 有 mimo v2/v2.5，缺 v2.6 与 step-5；按需补注册 |

#### D. chore / 环境（不跟）

版本 bump（2.5.3/2.5.4/2.5.5，版本号体系与本仓无关）、依赖升级（`426ede84`/`44236d7b`）、`280a039c` 统一 LF 换行、混元配置、DashScope reasoning 参数、chat completions 关闭思考传 none。

#### 吸收进度（2026-09-28）

- **第一批 5 项已吸收**（`24dcdcce`）：`70b382f5` 设置重置（配 `SettingsStoreResilienceTest` 5 项：
  重试策略纯函数 + 真损坏⇒备份原文件且库可用 + 正常写入不产备份）、`ed3569c7` 编辑器 saved state、
  `8b696c0c` MCP 空 header、`324b337b` 行号不可选、`4391d5a5` propertyNames（两处清单）。
  真机：连续启动 `incrementLaunchCount: 1250 → 1251`，设置文件未重建、界面配置完好。
- **第二批 2 项已吸收**（`47d5d81c`）：`5ac149b9` Responses toolCallId 归一（新
  `ResponseToolCallIdResolver` 按流创建；测试 6 项，且验证过去掉修复即失败）、
  `9f02586d` 技能保存（`writeFileAtomically` 顶层函数；测试 5 项）。
- **不做 1 项**：`54aa8114` 收藏侧滑（用户决定，已有可点击删除按钮）。
- **剩余**：C 类新增功能（~~chart_display~~ 已于 2026-09-29 吸收 / 内置技能 skill-creator / 阿语 RTL / 数据恢复页 /
  图片生成快捷方式 / mimo v2.6+step-5 注册），按需另排。

### 2026-09-19 新发现：2.5.2 后再 8 提交（`c8853531`..`458c16df`，已 ff-only 同步 master；**其中 2 项已于 09-20 吸收**）

> 剩余 3 项结论仍为「暂不吸收、按需取用」（`64319122` 小马算力=产品决策、`51a33198` haze、`8e304bb1` Volcano TTS 可选）；~~`458c16df` 待决定~~ → **已于 2026-09-24 吸收（`78048454`）**，本批**全部收口**。

| 提交 | 内容 | 评估 |
|---|---|---|
| `a7850967` | **fix: 修复内联代码连字**（close #1920；`Markdown.kt`/`MarkdownNew.kt`/`SimpleHtmlBlock.kt` 三处加 `fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"`） | ✅ **已于 2026-09-20 吸收（`083e2c96`），真机验证通过**。当时判定：fork 此前只吸收了 highlight 模块的代码块连字修复（`f26a19f1`，对齐上游 7b92f89e），富文本**内联 code span** 三处均无 fontFeatureSettings。**吸收时略作扩展**：①多覆盖 fork 特有的 `MarkdownStreaming.kt` 流式行内代码（该文件明确要求与最终渲染对齐，不加会「流式有连字、定格后消失」）；②常量收成 `RichTextStyle.CODE_FONT_FEATURES` 单一定义（原 6 处内联字面量正是 #1920 漏一处的成因）；③`highlight` 模块的 `Highlighter` 因独立 Gradle 模块、共享需加依赖边，保留自身副本 |
| `6e98691c` | **fix: 移除 ChatCompletionsAPI tool 消息多出来的 name 属性**（1 行 + 测试） | ✅ **已于 2026-09-20 吸收（`43b54550`）**。当时判定：fork `ChatCompletionsAPI.kt:538` tool 角色消息仍带 `put("name", tool.toolName)`（`:629` 那处是 tool_calls 内的 `function.name`，合法，不动），部分 OpenAI 兼容端点会拒收顶层 name。**吸收时核对**：其余 provider（Google/Claude/ResponseAPI）的 `name` 站点均为合法位置，不动；fork 测试文件已更名 `ChatCompletionsAPIMessageTest.kt:277`，断言反转为 `containsKey("name")` 为假。ai 模块 30 项全过 |
| `7c1629d0` | **fix: speed up image crop confirmation**（#1892，`CropLauncher.kt` PNG→JPEG(90)） | ✅ **已于 2026-09-20 吸收（`458fe3a6`）**。当时判定：`CropLauncher.kt:71` 仍是 `CompressFormat.PNG`——`withMaxResultSize(4096,4096)` 允许到 4096²，PNG 编码既慢又占内存即卡顿成因；**反编译 ucrop 2.2.11 确认 JPEG/q90 本就是库默认值**（`UCropActivity.DEFAULT_COMPRESS_FORMAT`/`DEFAULT_COMPRESS_QUALITY`），此前的 PNG 才是在覆盖默认。**吸收时另修一个附带缺陷**：文件名写死 `.jpg` 而编码用 PNG ⇒ 产物错标，**真机实证 `files/upload/` 下 5 个 `.jpg` 全部以 PNG magic 开头**（请求侧不受影响——ai 模块 `encodeBase64` 嗅探文件头、再统一压 JPEG q85）；引入 `CropOutput` 单一真相源使扩展名由格式**派生**，两者不可能再分叉。**已知取舍**：JPEG 无 alpha，透明像素合成成黑色，仅影响本地显示。测试 `CropOutputTest` 6 项 |
| `458c16df` | **feat: fork 对话标题自动使用原会话标题加序号**（「fork」指分叉会话功能，非仓库 fork；`createForkConversation` +10 行） | ✅ **已于 2026-09-24 吸收（`78048454`，真机验证）**。当时判定为真缺口：fork 的 `createForkConversation` 不带 title ⇒ 分叉会话标题为空，侧栏里分不清谁是谁；**更隐蔽的一层**——空标题会让既有自动起名（`generateTitle` 仅在 `title.isBlank()` 时触发）在首条消息后把它改成与源会话**无关**的名字，「从哪分出来的」线索就断了。**按语义手工落地未 cherry-pick**（fork `ChatService.kt` 重度定制，`forkConversationAtMessage` 位置结构均不同），并抽成纯函数 `forkConversationTitle(...)` 上单测。**两处有意与上游不同**：①**源标题为空时保持为空、不生成 `(1)`**——上游写法在空标题源上产出 `"(1)"`，既无信息量又因「非空」让自动起名**永不修正它**；②**序号取第一个未占用值**（而非「已有数量+1」），删除某次分叉后再分叉会复用空出的序号，避免无界增长。避让作用域=同助手（同上游口径）。测试 5 项新增（取空位/复用序号/忽略无关标题/空标题保持/工厂一致） |
| `445341e9` | **fix: restore favorites correctly after undoing deletion**（Fixes #1915） | **不吸收（2026-09-19 核对）：fork 已修（`aa2c9a53`），等价**。fork 侧 `FavoriteVM.removeFavorite/restoreFavorite` 均已改 suspend 保证落库时序、`FavoritePage.kt:284` 已有 `dismissState.reset()`（上游注释「Reset before removal」与 fork 同思路）。此提交反向印证 fork 对 #1915 的诊断正确 |
| `4a3eefc1` | **fix: preserve conversation state across session switches**（抽取 `ConversationSessionManager` + 失败/取消时持久化部分生成 + 元数据同步，10 文件 +722/-200 + 428 行测试） | **不整包吸收，但已按面拆取两处真 bug（2026-09-20，`1b41b586`）**。整包移植仍不值得——与 fork 自研三件套（后台保活前台 Service、生成失败自动重试、半截回复 `20d84333`）正面重叠，且 `ChatService.kt` 已重度定制。**但它是三件事捆在一起，其中两件是 fork 真缺、用户可见**：<br>**①「停止生成后内容静默消失」——真机复现并修复**：流式内容只走 `updateConversation`（纯内存），落库点仅 `onSuccess` 与 #1812 的 `GenerationPartialFailureException`（重试耗尽且配置门控）；取消走 `onFailure`，`CancellationException` 被 `addError` 直接 return（`:232`）⇒ **全程零写库**，而 session 离开页面 5s 即回收、`initializeConversation` 会无条件用 DB 覆盖内存。**真机证据**：屏幕显示新半截内容、DB 仍是旧完整内容（logcat 见 `stream reset: CANCEL` + `JobCancellationException`）；退到桌面重进即回到旧内容。修法=`stopGeneration` 末尾用 `NonCancellable` 收尾落库（须在 `finishInterruptedPendingTools` **之后**，否则未执行工具被原样持久化）；修复后 node 候选数 1→2、`select_index` 指向新候选。<br>**②「置顶星标静默丢失」——同族于 fork 已修过的 folderId**：`togglePinStatus` 只改 DB 单列，内存仍旧值，后续整对象保存（`conversationToConversationEntity:403`）把它覆盖回去；`moveConversationToFolder` 的注释（`:1400`）早已写明同一机制，本次推广到漏掉的 `isPinned`。<br>**③④「生成中切助手覆盖在途内容」+「web pin/move 同病」——2026-09-23 已修（`6ec84d93`）**：`ChatVM.moveConversationToAssistant` 传 `saveConversation` 的是**从 DB 读的整对象**，而其内部首件事即无条件覆盖内存态 ⇒ 给正在生成的会话切助手，在途 messageNodes 被旧快照换掉（与 ① **同族**：都是「用旧整对象覆盖活跃 session 的内存态」）。**web 路由 `POST /{id}/pin`、`POST /{id}/move` 走同一问题路径**——`1b41b586` 修 pin 时只改了 UI 两处、**漏了 web**（本项目自查发现）。做法=引入统一原语 `ConversationSession.updateMetadata(update, persist)`（mutex 串行化；`update` 作用在**当前内存值**、`persist` 只写对应列）+ `ChatService.updateConversationMetadata`（无 session 时从 DB 读一次作基线）；四个入口（ChatVM×2、web×2）改走它；DAO 加 `updateAssistantId`（`assistant_id`+`folder_id` 一次更新避免中间态）。**仍未移植上游 114 行 `ConversationSessionManager`**（纯重构、与 fork 手写版等价，`ChatService.kt` 是 fork 改动最重的文件）。测试 `ConversationSessionMetadataTest` 5 项（核心=元数据编辑后内存 messageNodes 一个都不变）。<br>**⑤仍未做**：`initializeConversation` 无条件覆盖内存态，上游的 load-once 守卫**照搬会破坏 web API**（`ConversationRoutes` 7 处依赖它「改 DB→读回」，如 `POST /{id}/injections`）——上游能安全加是因为**同时**把那些路由改成 `updateMetadata` 直改内存，两者配套、不能只取一半。本次已把 pin/move 两处改掉，剩余 5 处属独立议题 |
| `64319122` | chore: 移除小马算力默认提供商（`DefaultProviders.kt` -16 行） | **产品决策项（2026-09-19 核对）**：fork `DefaultProviders.kt:164` 仍有小马算力。fork 已有「内置提供商删除墓碑」（`removedBuiltInProviderIds`），删默认项技术上安全（老用户存量不受影响，仅回填跳过）。是否跟进看用户是否想精简默认列表 |
| `51a33198` | chore: bump haze version | **暂不吸收**：配套 `7ee13f2a` blur/glass 效果；fork 不吸收该特性则无需动 haze |

### 2026-09-19 同步动作记录

- `git fetch upstream` → `c8853531..458c16df`；本地 master 自 `d47d13a6` **ff-only 快进至 `458c16df`**（补上了 09-16 已评估未同步的 5 提交）。
- 上述 8 提交中**四个已吸收**：`a7850967` 连字（`083e2c96`）、`6e98691c` tool name（`43b54550`）、`7c1629d0` 裁剪 JPEG（`458fe3a6`，09-20）、`4a3eefc1` 按面拆取两处真 bug（`1b41b586`，09-20）。**仅剩 `458c16df` 分叉标题**（须手工适配 `ChatService.kt`）等用户决定。


### 2026-09-16 上游 issue 巡检（值得注意的）

| issue | 内容 | 与 fork 的关系 |
|---|---|---|
| **#1915** | bug: 收藏页删除时撤销失效（OPEN，0 评论，报于 2.5.2） | **fork 已修**（`aa2c9a53`）：这正是 fork 设计稿 §1.4 缺陷 A —— `removeFavorite`/`restoreFavorite` 是 fire-and-forget ⇒ `upsert` 可能先于 `delete` 落库、撤销被覆盖。**该 issue 的「补充」段引用的就是 fork 的分析**。**可考虑回馈上游**（需先比对两侧 `FavoriteVM` 结构） |
| **#1918** | bug: 助手消息预填充不生效（OPEN；用户带完整抓包） | **已评估（2026-09-16）：不是 bug，是「注入模型不含末尾位置」的结构限制**。详见下节 |
| #1903/#1904 | eval_javascript 缺资源防护 | **已修**（`641b46fe`） |
| #1912 | 工具审批无法关闭（OPEN） | **fork 不受影响**：前置修复（`55506496` 连续审批丢失、`1f04d2db` /tmp 免审批）**代码等价核对均在 fork**；且上游 2.5.2 也未修此 issue |
| #1889 | 切换对话时思考过程/消息丢失 | **fork 结构上不受影响**：`ConversationSession` 是引用计数 + idle check，切页只 `release()` 不中断生成 |
| **#1906** | feat: 聊天消息支持「引用」（QQ 式） | **已评估（2026-09-16 初评，2026-09-17 复核修订，未实现）：体量「小–中」，是本次巡检里性价比最高的一项**。见下节 |
| **#1901** | feat: Skills 分类 / 标签 / 搜索 | **已评估（2026-09-16 初评，2026-09-17 复核修正，未实现）：搜索最便宜、分类次之、标签暂缓；issue 正文给的 `tags: [word, document]` 写法在本仓读不出来**（实测）。见下节 |
| #1910/#1899/#1905 | OAuth 账号登录 / 远程服务器模式 / 屏幕常亮 | 功能请求，未评估（体量见 issue 本身） |

### #1918 助手预填充评估（2026-09-16，仅评估未实现）

**结论：用户观察属实、诊断精确，但这不是 bug 而是「不想实现」——fork 与上游一致，属注入模型的
结构限制。**

**用户的诊断已被代码逐条印证**：

1. 「注入的 assistant 消息**不在数组末尾**」——✅ 属实且**结构上不可能**在末尾：
   `PromptInjectionTransformer` 只有四个插入位置，**没有一个能插到最后一条消息之后**：
   `TOP_OF_CHAT`（第一条 USER 前）、`BOTTOM_OF_CHAT`（`insertIndex = result.size - 1` → **最后一条之前**）、
   `AT_DEPTH`（`depth=0` 被 `coerceAtLeast(1)` → 同样 `size-1`）、system 前后两处。
   fork 自己的单测 `mode injection with BOTTOM_OF_CHAT should insert before last message`
   **把这个行为钉住了**（注释即「在最后一条消息之前插入」）。
2. 「需要 `prefix: true`」——✅ 属实：全仓**无 `prefix` 字段**（唯一命中是 S3 的 prefix）。
3. 「界面应显示预填充+续写」——需生成回路改动（预填充文本要进落库消息）。

**历史脉络**（查 issue 得到的，很关键）：**#723**（请求预填充）→ **PR #733 已合并**，
但实现的是「**给注入加 USER/ASSISTANT 角色**」（`InjectionRole`）+ UI 选择器
（`prompt_page_role_assistant` = "助手（预填充）"）——**它只是让注入消息的 role 可以是
assistant，并没有加「末尾位置」，也没加 `prefix`**。**#763** 用户再次要求，maintainer 回
「`助手设置>提示词>预设聊天消息` 不就是吗？」并指其 issue 是 AI 生成的；用户解释了
「开头注入=伪造已完成回复 vs 末尾注入=正在进行的回复」的区别，随后 PR #733 合并。

⇒ **所以 upsteam 在 2026-01 就以「加 role 选项」的形式关掉了这个需求，但那个实现解决不了
用户真正要的「末尾预填充」**。#1918 是同一需求的第二次出现（用户这次带了抓包证据）。

**要真做，需要三件事**（都不是小改）：

| # | 改动 | 说明 |
|---|---|---|
| 1 | **新增「末尾」注入位置** | 现有四个位置都在末尾之前；且 `findSafeInsertIndex` 会**主动前移**（避免插进 `USER → ASSISTANT(含Tool)` 之间）——末尾注入必须绕开它，否则又会被推回去 |
| 2 | **provider 侧支持 prefill** | Chat Completions 需在末尾 assistant 消息带 `prefix: true`（DeepSeek 且可能要 `/beta` 路径）；Claude 原生 API 的 assistant 末尾消息**本身就是预填充**（无需 prefix）；Gemini 用 `model` role。**三家语义与字段各不相同**（与本仓记录的「跨 provider 缓存语义不同」同类问题） |
| 3 | **生成回路与落库** | 预填充文本要不要进 `messageNodes`？要则需处理「模型续写接在其后」的合并；不要则 UI 上会出现一条看不见的上下文（用户第 3 点期望是**要显示**） |

**阻塞点**：与 fork 的**压缩线**交互——注入在 `windowMessages` 组装**之后**由 transformer 施加，
新增"末尾"位置须与检查点合成消息/区域指纹/`isSynthetic` 共存；且预填充若落库还会触发
区域指纹与 FTS 索引。**评估：可行但属独立课题，不建议顺手做**。

### #1906 消息引用评估（2026-09-16，仅评估未实现）

**结论：值得做，且应落在 `UIMessageAnnotation` 上——不要新加 `UIMessagePart` 子类。**

#### 决定性事实：新 part 会被三家 provider 静默丢弃

issue 建议「消息体扩展一个 quote 字段，对已有消息结构兼容性好」——**方向对，但选点若落在
part 上会得到一个「本地看得见、模型看不见」的假功能**。核实：三家 provider 的 part→API
转换**都是「白名单遍历 + `else -> null`」**：

| provider | 位置 | 形状 |
|---|---|---|
| Claude | `ClaudeProvider.kt:484` | `UIMessagePart.toContentBlock(): JsonObject?` … `else -> null` |
| Google | `GoogleProvider.kt:705` | 同上，`else -> null`（`:746`） |
| OpenAI 系 | `ChatCompletionsAPI.kt:565` 等 5 处、`ResponseAPI.kt:325/493` | 同样 `else -> {}` / `else -> null` |

调用方一律是 `message.parts.mapNotNull { it.toContentBlock() }`（Claude `:479`/`:441`）。**后果**：
新增 part 类型**能编译过、也会被静默过滤掉**——UI 上引用卡片正常渲染，但发给模型的消息里
什么都不剩。这正是本仓已知的「静默丢弃」类陷阱（同类：`ChatMessage.kt:333` 的
`else -> { /* Skip unknown part types */ }`）。

⇒ **正确选点 = `UIMessageAnnotation`**（`ai/src/main/java/app/androidrik/ai/ui/UIMessageAnnotation.kt`），
加 `@SerialName("quoted_message") data class QuotedMessage(...)`。理由：

1. **注解不在 provider 白名单路径上** ⇒ 不会被 `mapNotNull` 意外吞掉，语义明确。
2. **已有先例**：`UrlCitation`、`PartialFailure`（#1812）都是注解，且
   `ChatMessage.kt:145`/`:631` 已按注解渲染 UI（`PartialFailureBanner` 就是现成模板）。
3. **无需数据库表迁移**：`MessageNodeEntity.messages` 是 JSON 字符串，新版序列化器可读取旧消息。
   但新增 sealed 注解类型不等于旧版兼容：`ignoreUnknownKeys` 不能保证旧版识别未知类型判别值，
   因此含引用的新数据不承诺能被旧版应用读取。

**但要「让模型看到引用」仍需一步显式转换**：注解本身也不进请求。最小做法是在
`preprocessUserInputParts`（`ChatService.kt:535`）或一个 `InputMessageTransformer` 里，把引用
**展开成文本前缀**（`<quoted author="user">…</quoted>\n\n新内容`）。这与
`DocumentAsPromptTransformer` 把文档转 `<UploadFile>` 提示词是同一手法，**有现成范式可抄**。

#### 数据形态：messageId + 内容快照**双存**（2026-09-17 修正：推翻初评「只存 id 不存快照」）

初评建议「存 nodeId+messageId、渲染时实时解析、不存快照」，理由是防幽灵。复核发现两个硬缺口：

1. **发送侧展开发生在压缩窗口组装之后**（`GenerationHandler.kt:540-609`
   `buildInternalMessages().transforms()`）——不能假设引用源还在请求窗口里；
2. 原消息被删/被截断后纯 ID 解析不到 ⇒ 模型什么都看不到。

⇒ **快照必须存**（悬空降级与发送展开的依据）。数据形态：
`QuotedMessage(messageId, role, content, createdAt)`。ID 仅在所属会话内解释，留给后续定位；
**固定快照**用于显示与发送，不随原消息编辑而漂移。只采集 Text，不递归引用、不采集思考或工具输出；
快照上限 8000 个 UTF-16 单元，截断避开代理对，UI 三行摘要与请求展开使用同一份快照。
快照随消息 JSON 落库但**不进 FTS**（`MessageFtsManager.kt:100` 只索引 parts 里的 Text）。
A 不提供跨会话选择原文的入口；但**整会话导出再导入仍须保留内部引用**，通过 ID 重映射实现，
不能把「跨设备」笼统说成必然悬空。源已缺失时保留快照，不伪造引用目标。

#### 真难点（都是本仓特有的，不是 UI 活；2026-09-17 修订）

| # | 难点 | 说明 |
|---|---|---|
| 1 | **消息队列不携带注解** | `QueuedMessage(parts, answer, isEditing, reply)`（`MessageQueue.kt:12`）**只有 parts、无注解位**。生成期间点发送走的是入队路径（`sendMessage` → `enqueue`），引用信息会在入队这步**丢掉**。**必须 A 批修，不能放 B**——排队场景全程丢引用（初评排 B 是漏了排队场景） |
| 2 | **送达模型要显式展开** | 注解不进请求，须 InputMessageTransformer 展开成文本前缀（`DocumentAsPromptTransformer` 同手法）。展开只发生在请求副本上 ⇒ **不进 FTS**（初评「会进 FTS」有误）；token 统计仍计入（usage 由 provider 报告）。快照自足，不依赖源消息在窗口内 |
| 3 | **跨分支/截断引用会悬空** | 引用的是「另一分支」或被 `limitContext`/压缩检查点截断掉的消息时，解析不到。降级语义：照常展开快照文本，仅跳转失效 |
| 4 | **跳转不能直接复用 onJumpToMessage（2026-09-17 新发现）** | 它按**完整节点列表 index** 滚动（`ChatPage.kt:200-211`），压缩折叠时列表顶部多一行、索引空间不一致（`ChatList.kt:291/357`），且非选中候选定位不到。B 批需先展开被折叠目标再按可见列表算位置 |

#### 建议范围（2026-09-17 修订）

- **A（推荐先做）**：注解模型（`QuotedMessage(messageId, role, content)`）+ 长按菜单「引用」入口 +
  输入框上方引用预览卡（可取消）+ **QueuedMessage 携带** + 发送展开 transformer +
  消息上方引用块渲染（不跳转）+ **导入 remap 覆盖**。fork 复制**无需改**
  （`ChatService.kt:1556` 保留 message id，前缀内引用自动存活）；悬空靠快照降级。
- **B（中）**：引用块点击跳转原消息——需处理压缩偏移与候选定位，不能直接复用 `onJumpToMessage`。
- **C（不做）**：跨会话选择原文、附件内容引用。整会话导出导入不在此排除范围，内部引用须重映射。

**导入 remap 细节**：`ConversationImportRemap.kt:38` 目前只有 `nodeIdMap`，message id 就地随机生成、
**无映射表**——须预建 messageId 映射并重写全部候选里的引用；与 `9f1d8105`（remap 漏 folderId）
同一教训：换 id 的操作要成体系过一遍所有指向外部对象的引用字段。

**估**：A 约 2 个提交（模型+队列+展开 1 个，UI+导入 remap 1 个）；B 再加 1 个。
**比 #1901 便宜，比 #1918 便宜得多。**

#### A 实现记录（2026-09-17）

已落地 A 的范围（19 项新单测 + 两模块编译通过）：

| 面 | 落点 |
|---|---|
| 模型 | `UIMessageAnnotation.QuotedMessage`（ai/ui/UIMessageAnnotation.kt）+ 采集/截断/展开（新 ai/ui/MessageQuote.kt） |
| 队列 | `QueuedMessage.annotations`（默认空）、`enqueue`/`finishEdit` 均可携带；`ChatService.sendMessage`/`finishEditQueuedMessage` 透传；派发时落到 user message |
| 请求展开 | 新 `QuoteTransformer`（app data/ai/transformers），已注册进 `ChatService.inputTransformers`——与拼装预览**同一条链**（PromptInspector 也用 `inputTransformers`） |
| UI | 长按菜单「引用」入口（仅对含文本的消息显示）、输入框上方预览卡（可取消）、消息上方引用块；共享组件 `MessageQuoteCard` 防两处样式漂移 |
| 导入 | `remapForImport` 预建 messageId 映射并重写引用（覆盖未选中候选与前向引用；悬空保留原 id 与快照） |

**实现中确认的三个判断**：
1. **编辑保留引用**（`ChatService.editMessage` 取被编辑消息自身的注解）——改文本不等于撤回引用；
   取 `node.messages.find { it.id == messageId }` 而非 `node.currentMessage`，避免编辑候选时串分支。
2. **引用入口进入即退出编辑态**（ChatPage `onQuoteMessage`）——否则发送走编辑分支、引用被丢弃。
3. **待发引用挂 `ChatInputState`，VM 按会话 id 作用域** ⇒ 切会话不串引用。

**未做（留 B）**：引用块点击跳转（需处理压缩偏移与候选定位）；ST 导入不映射 `jailbreak`。

#### B 实现记录（2026-09-17）

引用块点击跳转已落地（`QuoteJumpTargetIndexTest` 5 项单测）：

- **按 messageId 而非下标定位**：压缩折叠时渲染的是 `windowNodes()`（边界之后的尾巴），
  与「完整节点列表下标」不是一个空间。
- **两段式跳转**：折叠态找不到目标 ⇒ 先 `showCompressedHistory = true` 展开完整轨迹，
  由 `visibleNodes` 变化触发 effect 重跑再滚动。
- **检查点行偏移**：折叠/展开态下检查点行都恒为列表首项，命中下标 +1
  （抽成纯函数 `List<MessageNode>.quoteJumpTargetIndex(messageId, hasCheckpointRow)` 便于单测）。
- **未选中候选**：只滚到所在节点，**不自动切 selectIndex**——那会静默改变会话展示分支并
  影响后续发送，代价远大于收益。
- 目标已被删除时放弃，不报错。

#### 顺带：助手页扩展管理改为二级（2026-09-17）

用户指出「助手页的扩展管理里预设和世界书仍是平铺」。核实：助手详情页走的是
`ModeInjectionsContent` / `LorebooksContent`（拍平后的 `Settings.modeInjections`），
**与对话内扩展面板的两级结构不一致**。

改法：把选择器抽成共享文件 `ui/components/ai/ExtensionPicker.kt`（`ExtensionPickerNavigation`
+ `PresetPickerContent` + `WorldbookPickerContent`），对话面板与助手页**共用同一实现**——
早前 `ExtensionPickerNavigation` 就放在 `components/ui/ExtensionSelector.kt` 里，是两个调用方
各写一份的开始，抽出来防漂移（同 [[backup-workspace-files]] 抽共享引擎的理由）。

同时清掉两处技术债：
1. **硬编码中文改字符串资源**（8 个键 × 6 语言）：原选择器里「已选 / 预设已全局禁用 /
   世界书按整本选用 / 全局已启用…」都是内联中文字面量。
2. **删除死代码 `LorebooksContent`**：世界书列表改两级后它不再被任何地方引用。

### #1901 Skills 分类 / 标签 / 搜索评估（2026-09-16，仅评估未实现）

**结论：搜索最便宜（几乎零风险），分类中等，标签最贵且会引入一个跨模块的数据一致性问题。
建议只做「搜索 + 分类」，先把标签放掉。**

#### ⚠️ 首要发现：issue 建议的 YAML 写法本仓读不出来（实测）

issue 正文给的 frontmatter 是：

```yaml
tags:
  - word
  - document
```

**实测结论：这个 `tags` 在当前代码里解析为 `null`。** 我写了临时探针单测跑出：

```
name       = word-documents
category   = office          ← 标量正常
tags(block)= null            ← 块式列表 → null
tagsInline = null            ← [aa, bb] → null
tagsComma  = x, y            ← 字符串正常
```

**根因**：`SkillFrontmatter.get`（`SkillFrontmatterParser.kt:52`）是
`values[key] as? String`——snakeyaml 把列表解析成 `ArrayList`，`as? String` 直接给 `null`。
**所以无论块式还是内联，任何 `List` 都被静默吞掉。**

⇒ **要做标签，第一步必须扩 `SkillFrontmatter`**（加 `fun getList(key): List<String>?`，兼容
`List` 与逗号分隔字符串两种写法）。**这是 issue 作者没意识到的前置工作**，也是「必须
`.getList` 而非 `.get`」这种「接口存在但取不到值」的典型静默失败。（上游同一解析器，
**必然同样读不出**。）

#### 各处改动成本（按面）

| 面 | 位置 | 成本 |
|---|---|---|
| 解析 | `SkillFrontmatterParser`（`getList`）+ `SkillMetadata` 加 `category`/`tags` | 小，但**是标签的前提** |
| 列表页 | `SkillsPage.kt`（460 行，`items(skills)` 平铺）| 中：搜索框 + 分类筛选 chips + 分组 |
| 填充 | `SkillManager.parseSkillFile`（`:189`，唯一构造 `SkillMetadata` 处）| 小 |
| **AI 侧** | `SkillsTools.createSkillTools`（`systemPrompt` 把**所有已启用技能**的名字+描述**全量注入**）| ⚠️ 见下 |
| **AI 写入** | `SkillInstallTools` → `SkillInstaller.saveFromContent` → `buildSkillMarkdown`（`:66`）**硬编码只写 name+description 两行** | ⚠️ 见下 |
| 另两处 UI | `ExtensionSelector.kt:203`（扩展面板技能 tab）、`AssistantExtensionsPage.kt:195` | 共享 `SkillsContent` 组件，**加参数即可** |
| 详情页 | `SkillDetailPage.kt`（含 `EditFileDialog` 直接编辑 SKILL.md 原文）| 小 |

#### 两个真陷阱

**陷阱 1（数据丢失）：AI 装技能会抹掉 category/tags。**
`buildSkillMarkdown`（`SkillInstaller.kt:66`）是**字符串模板硬编码**：

```kotlin
append("---\n"); append("name: $name\n"); append("description: $description\n"); append("---\n\n")
```

`validateSkillContent` 也只收 name/description/content。⇒ **AI 用 `install_skill` 覆盖已有技能
时，用户手写的 `category`/`tags` 会被整段丢掉**（`saveSkillFileBytesAtomically` 是**整体替换**
目录，不是合并）。这不是新 bug（`compatibility` 今天就有同样问题），但**加了标签后会立刻变得
显眼**。修法：`saveFromContent` 走「读原文件 → 保留未知 frontmatter 键 → 重写」而不是模板拼串。

**陷阱 2（injection 成本）：分类注入 AI 会让「分类」变成 token 负担。**
`SkillsTools` 的 `systemPrompt` 把已启用技能全量列出（本仓已实测：MCP 工具 schema 就能占 ~33K
token，见 [[prompt-size-composition]]）。若把 `category`/`tags` 也塞进这段注入，**每个已启用
技能都多几个 token，且这些字段对模型决策无用**。⇒ **建议：分类/标签只做「管理页的组织维度」，
不进 systemPrompt**（`use_skill` 的检索靠 name+description 已足够）。这与 issue 的诉求
（「数量变多后列表难以管理」）完全一致——**要解决的是人的浏览问题，不是模型的检索问题**。

#### 建议范围

- **A（小，推荐）**：**搜索框**（按 name + description 过滤，纯前端，`remember(query)` 即可，
  参考 `ChatList.kt:664` 的现成写法）。**零数据模型改动、零迁移、零 AI 侧影响。**
- **B（中，推荐）**：**单 `category` 标量 + 分类筛选 chips + 可选分组**。需扩
  `SkillFrontmatter.get`→标量已有、只加 `SkillMetadata` 字段 + `parseSkillFile` 填充 + UI。
  **顺带修陷阱 1**（否则 AI 装技能会抹掉分类）。
- **C（暂不做）**：**多标签**。要新增 `getList` 解析、多标签筛选 UI、标签池管理
  （可参考 fork 已落地的 [[favorite-management]] 标签设计：**存 id 不存名**）。
  收益相对 B 小很多，且撞上陷阱 1/2。

**估**：A 约半天（1 提交）；B 约 1–1.5 天（含陷阱 1 修复与 6 语言字符串）。
**注意：分类是 SKILL.md 里的**手写**字段**——与 fork 的<b>收藏标签</b>（存在 `meta_json`、有独立标签池）
**不是同一形态**：技能没有可放标签池的 DB 表，标签只能进 frontmatter 文本，
**所以「重命名标签」在技能上就是要批量改文件**（收藏那边存 id 才免掉这一步）。
这也是建议把 C 放掉的一个理由。

#### 2026-09-17 复核修正（三处）

1. **标签成本高估**：初评拿收藏标签（存 `meta_json`、有独立标签池）做对照，得出「标签需要
   标签池所以贵」。**不成立**——技能标签就是 frontmatter 手写字段 + 文本匹配，不需要标签池。
   放掉 C 的真实理由修正为：**重命名标签 = 批量改文件**（技能没有可放标签池的 DB 表，标签只能
   进 frontmatter 文本）+ 收益相对 B 小。
2. **陷阱 1 加重一层**：`saveSkill` 走 `saveSkillFilesAtomically`（staging → **整目录替换**，
   `SkillManager.kt:48-55/119-146`）——AI 覆盖同名技能不只抹 frontmatter 元数据，
   **同名技能的附属文件也会一起丢**；`saveFromContent` 正是复用它。GitHub 导入反而安全
   （下载原始文件、不重建 frontmatter）。修法同前：`saveFromContent` 改「读原文件 → 保留未知
   frontmatter 键 → 重写」，或单文件编辑走已有 `saveSkillFile`（只写指定文件）。
3. **覆盖面精确化**：搜索/筛选的共享组件是 `SkillsContent`（`ExtensionContent.kt:135-166`），
   聊天扩展面板（`ExtensionSelector.kt:387-401`）和助手扩展页（`AssistantExtensionsPage.kt:175-195`）
   共用该组件，管理页 `SkillsPage.kt` 是独立列表。应抽共享筛选逻辑并分别接入管理列表和选择组件；
   只改 `SkillsPage.kt` 会漏掉两个选择入口，只改 `SkillsContent` 也不能覆盖管理页。

#### A+B 实现记录（2026-09-17）

按「只做搜索 + 单分类」落地（新增 16 项单测）：

| 面 | 落点 |
|---|---|
| 数据 | `SkillMetadata.category`（`SkillManager.parseSkillFile` 填充；标量字段，`.get` 即可读） |
| 纯函数 | 新 `data/files/SkillQuery.kt`：`filterSkills(query, category)` + `distinctCategories()` + `SkillCategoryFilter` 密封接口 |
| frontmatter 保留 | 新 `splitFrontmatterEntries` / `otherFrontmatterEntryLines`（**行级**，保注释与块式列表） |
| UI | 共享 `SkillsContent.header` 参数 + 新 `SkillFilterBar`（搜索框 + 分类 chips）；管理页、聊天面板、助手页三处接入 |
| 陷阱 1 修复 | `SkillInstaller.saveFromContent` 覆盖同名技能时保留其它 frontmatter 键**与目录内既有附属文件** |

**实现中三个非显然决定**：
1. **注释归属下一个键**（YAML 惯例）。这不是细节：若归属上一个键，位于 `category:` 上方的
   `# 说明` 会被算进上一个键（常是被排除的 `description`）⇒ 覆盖保存时注释连同分类一起消失。
2. **frontmatter 保留走行级而非 snakeyaml 重序列化**：后者会丢注释、重排键序、改写块式列表——
   用户手写内容被「顺手格式化」是静默失真。只做行分组、原样搬运。
3. **分类不进 systemPrompt**（`SkillsTools` 仍只注入 name+description）：要解决的是人的浏览问题，
   不是模型的检索问题；塞进去是纯 token 负担（见陷阱 2）。

**未做（C，维持暂缓）**：多标签——需要 `getList`、多标签筛选与标签池，「重命名标签 = 批量改文件」，
收益相对小。**分类筛选是自动从现有技能派生的**（`distinctCategories()`），没有分类管理 UI，
也不需要——分类就是 SKILL.md 里的手写字段。

## 同步方式（ff-only，仅本地）

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master
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
   - **2026-09-19 复核修订（注入生态大改后重估：预设容器/目录/尾部注入/预算/拼装预览均已落地）——三层定案**：
     - **A. 缓存止血（本日落地，见下）**：种子条目 Learning Mode 默认位置 `AFTER_SYSTEM_PROMPT`（**实测确认该位置会改写 system 消息本体**，`applyInjections` 的 `result[systemIndex] = copy(...)`）→ 新装用户开箱即破缓存；改 `BOTTOM_OF_CHAT`。默认助手种子含 `{{cur_date}}` → 移除。新增**一键迁移**：管理页检测到 `BEFORE/AFTER_SYSTEM_PROMPT` 条目时显示提示卡，一键迁到 `BOTTOM_OF_CHAT`（保留 role；覆盖存量 Learning Mode 与 ST 导入的 after 条目）。
     - **B. 角色卡并入插件（缓行，未排期）**：CardSlot 6 槽转为注入条目进统一排序/归因，是 Tier 4（预览条目归因）的前提之一；约 1 周，需重写 `buildCardSystemPrompt` 逐字节特征测试并迁移 `cardSlotOrder`/`disabledCardSlots`。
     - **C. 「删除注入、重写插件架构」＝正式放弃**：fork 的 `PromptInjection` **已经就是** #1542 想要的「上下文插件」接口（content+enabled+priority+position，容器化后预设/世界书条目同构）；「删除」= 丢弃近一个月建成的 PromptPage(1944行)/两级选择器/ST双格式导入/预算/排序/预览生态，换来的模型与现状几乎同构。缓存诉求与「删除注入」无关——改写 system 的是 `AFTER_SYSTEM_PROMPT` 默认位置而非注入模型本身，A 已解。上游该 issue 仍 OPEN、零评论、无关联 PR（查至 2.5.2 后）。**#1542 在 fork 语境下视为已被 A+模型统一实质性回应，议题关闭（C 部分不再评估）。**
     - A 的落地提交：见 git log「缓存止血」相关（种子位置/cur_date/一键迁移三件）。

9. **工具装配方盘点**（2026-09-14；**同日已重构落地 `3893912f`**，见「已放弃」表 `097cdb90`）：**重构后 = 单一装配点 `ChatToolFactory.createTools`**（原先的两处——`ChatService` 内联 buildList + `GenerationHandler` 内层叠记忆工具——已合并）。以下盘点保留为**新增装配方的接入指南**：
   - **重构后的装配方**：①**`ChatToolFactory.createTools`（唯一装配点）**＝主工具集——搜索（`enableWebSearch` 条件）/ 本地工具 8 类 / 最近对话（条件）/ 工作区（`createWorkspaceToolsIfReady`，需 shellStatus READY）/ 技能安装（恒注册）/ 已启用技能（条件）/ **MCP 动态循环**（含 `McpNameSanitizer.buildUniqueFunctionName` 去重净化）；②**`GenerationHandler.generateInternal`（`GenerationHandler.kt:138`）＝第二个装配点**——在传入 `tools` 之上再叠 `buildMemoryTools`（含 `needsApproval` 按 action 分档回调），这是 fork 特有、上游 factory 设计时未考虑的形态；③`ProviderConnectionTester`（`:179`）独立假工具 `get_current_time`（连通性测试）；④`LocalTools.getTools`＝按 `LocalToolOption` 的**子装配**（js/time/clipboard/tts/askUser/screenTime/calendar×2）；⑤`Model.tools`/`BuiltInTools`（`ai/.../Model.kt:47`）＝**provider 侧**能力标志（Search/UrlContext/ImageGeneration），非 Kotlin `Tool` 但与装配逻辑耦合（见上条 web search 条件）。
   - **潜在的装配方（未来立项会变成第 4/5 个入口）**：**MCP host 模式**（远程调用者需设备工具**子集**、复用审批/loop guard，见 `peer-apps-benchmark.md` SoLab ⑥）；**Agent/工作流/定时任务入口**（借鉴清单 Cron/workflow/subagent/Telegram，每入口都是独立生成发起方 + 需"无头会话"审批策略）；**Web API 聊天**（`ConversationRoutes` 现委托 ChatService、**尚不是装配方**，但 web 端一旦需要与手机不同的工具集即成为装配方——这是最可能打破"集中式"设计的压力点）。
   - **必须明确排除在 factory 之外的后台旁路**：标题 / 建议 / 压缩 / OCR / 翻译全部走 `backgroundTextGenerationParams`（`ChatService.kt:139`，**刻意不含 tools**）↔ 重构须保住这个"无工具"契约。
   - **展示侧另有一方**：`ToolUIRegistry`（`ToolUI.kt:93`）+ `ChatMessageTools.kt:79` 按 `toolName` 解析渲染器——非装配方，但是 **toolName 的第二个消费方**，改命名（MCP 前缀/技能名）须同步。
   - **签名含义**：上游 `createTools(settings, assistant, model, workspaceCwd)` 只覆盖主链路；若要容纳潜在方，需再开维度——**调用者类型**（device-local vs remote/headless，决定是否含设备工具）、**记忆工具的归属**（现由 `GenerationHandler` 装因它持 `memoryRepo`；收进 factory 则 factory 需依赖 `MemoryRepository` 或回调注入）、**统一审批解析层**（`needsApproval` 现每处自定义：记忆按 action 分档 / MCP 透传 / 工作区有 override；不抽层只是把散乱搬家）。

10. **批次「已吸收核心件」不等于零缺口**（2026-09-14 教训）：`aac6e963`（#1666 统一内置/外挂搜索，2026-08-13）落在 fork 分叉点 `2c980642`（2026-08-02）之后的 master 同步范围内，但**特性分支从未移植、批次分析也未记录**——它被 2.4.14 批次的「已吸收核心件」概括吞掉了。**修正做法**：`已吸收核心件` 只表示"该批主线已处理"，不代表逐提交已核；对**`ChatService.kt`/`GenerationHandler.kt` 这类重改文件上的上游修复**（尤其带 `fix(...)` 且关 issue 的）应逐条 grep fork 是否等价，而非按批次概括。本次即由「工具装配方盘点」顺藤摸到。

12. **吸收前先查「这条 fix 是否已被上游自己推翻」**（2026-09-21 教训，`d3a53e0a`/`4b36640a`）：fork 于 2026-09-12 吸收了 `d3a53e0a`（图片选择改 `PickVisualMedia`），而**上游已于 2026-09-02 用 `4b36640a` 自行 revert**（commit 名即「恢复图片选择器的文件浏览入口」，close 同一个 #1821）——且该 revert 在 09-09 的同步点 `a9b35ba2` 里就已是 fork master 的祖先，即**信息一直在手边，只是没查**。判定手法：对候选吸收的提交 `S`，先跑 `git log --oneline <S>..master -- <S改过的文件>` 看其后是否被改动/撤销，或直接 `git log --oneline -S "<特征串>" -- <file>` 看该改动是否被反向提交（`revert:` 前缀或同名 `-` 号）。**注意本仓禁止用 merge-base 判「已吸收」**（手工直套非祖先，恒误报，见注意点 1），但**判「已被上游推翻」正是 merge-base 的合法用法**——`merge-base --is-ancestor <revert提交> <同步点>` 为真即说明推翻早于吸收。同类：`6ba71f89`(03-06 加)→`c03f9d5e`(03-14 撤) 也是一对。

11. **可拖拽列表一律按 key 定位，不要用 lazy 列表下标**（2026-09-19 用户报「拖拽排序异常」）：`rememberReorderableLazyListState` 回调里的 `from.index/to.index` 是 **LazyColumn 的绝对下标，含头部等非数据行**（如预设列表的「迁移卡」、注入列表的「顺序提示 orderHint」）⇒ 用下标映射到数据列表会**整体错位**（拖 A 实际移动了 B）。本仓已有正确工具 `reorderForDragByKey(from.key, to.key) { it.id }`（`CollectionUtils.kt`，其注释与 `ReorderForDragTest` 的「世界书条目拖拽」区都写明了此陷阱）。**规则：新写/改动可拖拽列表一律用 key 版**；另注意 `ModelList`/`SettingProviderDetailPage`/`SettingSearchPage` 三处仍在**手工补偿偏移**（历史写法），改动这些列表时优先一并转为 key 版。

## 关联

[peer-apps-benchmark.md](peer-apps-benchmark.md)（A1 前台服务取舍/落地顺序基准）、[handoff-2026-09-04.md](handoff-2026-09-04.md)、[design-partial-failure-draft.md](design-partial-failure-draft.md)
