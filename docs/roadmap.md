# 项目规划文档（Roadmap）

> **本仓说明**：本文写于私有开发仓的开发过程中，恢复时已按本仓实际改写——提交短哈希已重映射到本仓历史中的对应提交（映射不上的说明该提交未随本仓发布）；`<work>` / `<device>` / `<tmp>` 等尖括号串是路径与设备信息的占位符。本仓基线 = 上游 `2c980642`（RikkaHub `2.4.5`），此后对上游修复/功能按需**人工评估吸收**（不做 ff-only 同步、改动不回推上游）；包名 `app.androidrik.rikkahub`、显示名 **Neomix**、发布分支 `master`、**已发首个正式版 `v1.0.27`**（当前源码 `1.0.28-beta.12 (314)`（预发布线）；**自 `beta.7` 起随 release 提供 arm64-v8a APK 资产**，更早版本仅 tag、无资产，CI 自动发版仍禁用）。

> 记录 RikkaHub Neomix 的后续规划方向 + 逐日落地日志。
> **状态说明**：本文档混合「已落地」与「规划中」两类内容——各章节标题/状态行标注实时状态，
> **不要以「均为规划」理解本篇**；最完整的逐日落地日志见 [`changelog/`](changelog/)（§八 只保留索引与追加约定）。
>
> **本仓现状（RikkaHub Neomix）**：当前版本 `1.0.28-beta.12 (314)`（见 `app/build.gradle.kts`，数据库版本 48），稳定版仍是 `v1.0.27`；
> 预发布 tag `1.0.28-beta.1`…`beta.12` 均已发行（自 `beta.1` 起不带 `v`），其中 `beta.1`…`beta.4` 在 GitHub 上标记为
> prerelease，`beta.5` 按用户要求改为**正式 release**，`beta.6`…`beta.12` 沿用（因此 `releases/latest` 现指向 `beta.12`）。
> **`1.0.28-beta.7` 是首个带预编译资产的版本**（`Neomix-1.0.28-beta.7-arm64-v8a.apk`，仅 arm64-v8a）；此前各版均仅 tag、无资产。
> 下文中出现的 `v1.0.x` / `Latest` / release / tag / versionCode 递增等内容，均为**私有开发仓时期的发布记录**，
> 作为开发历史保留，**不代表本仓的发布状态**。
---

## 一、借鉴橘瓣（OrangeChat）的分析

**来源**：[橘瓣 OrangeChat](https://github.com/watermio123-droid/orangechat)（watermio123-droid/orangechat）——另一个基于 RikkaHub 深度定制的 Android AI 客户端，走"AI 伴侣"路线（插件生态 + 生活感知 + 健康数据 + 记忆 + 主动消息）。还有 `liquid-glass-ui` 分支（液态玻璃主题）。

**评估原则**：结合本项目定位（私有测试、隐私优先、LLM 客户端），分析借鉴价值。

### 高价值（建议未来实现）

#### 1. QuickJS 插件系统（~~核心~~ **确定不做**）🚫

> **决策（2026-08-14）**：经架构评估后确定**不做**。理由：本 fork 已有 `eval_javascript`（AI 现场写 JS 执行，最灵活）、MCP（外部工具服务）、Skill（指令扩展）、Workspace（任意执行环境）四类机制，QuickJS 插件系统的核心增量（预打包复用 + 声明式 schema + 用户配置 + 可分发）仅对第三方生态/复杂配置有价值，对私有自用项目锦上添花、工程成本不匹配。详见 `changelog/`（原 §八）。

- ~~**橘瓣实现**~~：完整插件框架 `plugin/` 目录——`PluginSandbox`（QuickJS 沙箱）、`PluginLoader`、`PluginManager`、`PluginUI`（声明式 UI + WebView）、`PluginToolProvider`（工具注册）、`PluginScanner`（发现）
- ~~**插件形态**~~：`manifest.json`（声明 tools/配置/模板）+ `main.js`（`exports.xxx` 实现工具），zip 打包导入
- ~~**沙箱内置 API**~~：同步 `fetch`（15s 超时）、`config`（用户配置）、`memoryBank`（语义检索/存取）、`TextEncoder/TextDecoder`、`btoa/atob`、`console`
- ~~**高级能力**~~：`config` 声明式配置项（string/password/number/boolean/select）、`promptTemplate` 系统提示、`hooks`（message_sent/message_received/daily_cron 事件）、声明式 UI（Compose Material 3 渲染）、`customPageWebView`
- **我们的现状**：RikkaHub **已内置 QuickJS 引擎**（`RikkaHubApp.kt` 的 `QuickJSLoader.init()`）+ 已内置 `eval_javascript` 工具（`JavascriptTool.kt`，QuickJS 沙箱 + console + destroy 防泄漏）；另有 Skill（markdown 技能）、MCP（外部服务）、Workspace（Linux 沙箱）——**四类机制已覆盖插件系统的价值点**
- ~~**价值**~~：~~用户可用 JS 脚本扩展 AI 工具，比改代码灵活~~ —— 已被 `eval_javascript` 现场执行覆盖（AI 需要时现写即可，省去打包/管理成本）
- ~~**工程量**~~：中（沙箱封装 + manifest 解析 + 工具注册 + 管理页 UI + 内置示例插件）

#### 2. 液态玻璃主题 / 三色定制

- **橘瓣实现**：`liquid-glass-ui` 分支；主题三色映射（颜色 1=全局背景 / 颜色 2=用户气泡 / 颜色 3=重点色），丝滑切换日夜主题
- **我们的现状（2026-10-12 更正）**：RikkaHub 已有主题系统（`themeId` / `customThemes` / `dynamicColor`），且**「玻璃质感」部分已具备**——`DisplaySetting.enableGlassTheme`（`PreferencesStore.kt:1039`，默认关）+ 偏好设置→通用页开关 + `ChatList.kt:398` 消费点；**仍缺的是「三色直观定制」**（把「全局背景 / 用户气泡 / 重点色」三个色位直接暴露给用户，而不必写完整自定义主题）。
- **价值**：轻量、见效快、个性化体验提升明显
- **工程量**：小（三色映射 = 现有 `customThemes` 之上的一个简化入口 + 设置项）

#### 3. 轻量个性化

- **橘瓣实现**：头像框、气泡透明度、输入框换背景、字体包导入、思维链样式
- **价值**：均为小改动、体验提升直接
- **工程量**：小

### 中等价值（可选）

#### 记忆工具审批（按 action 分档）—— **✅ 已落地（`1853b349` + `c06c69cd`，2026-09-14）**

> 实现：`Assistant.memoryModifyApproval`（默认 true）+ **`Assistant.memoryCreateApproval`（默认 false，opt-in）**（均为 @Serializable 子对象字段，随 JSON 自动持久化）+ 纯函数 `memoryToolNeedsApproval(assistant, action)` 承载分档判定（`ChatToolFactory.kt`，工厂 wiring 调用）+ 记忆页两个开关 + 6 语言文案 + `MemoryToolUI` 补 delete 目标预览。同批（`1853b349`）修复**内容风控 403 不换 key**（实测暴露：风控 403 被误判为鉴权失败、白旋转 key 额度）。**create 审批为后续 opt-in 增补（`c06c69cd`）**：默认关=保持「新增直接记录」，仅担心模型记流水账/想逐条把关的用户才开；与 modify 开关**相互独立**。单测：审批判定 7 项（含 create 矩阵）+ MemoryToolsTest 改测接线，全量 421 项仅既有 MarkdownMathParse 失败；真机开关默认关、开启后持久化生效。

**需求**：AI 通过 `memory_tool` 可改/删长期记忆，用户无感知。加审批防止**未经许可的修改与删除**。

**评估结论（后经验证成立）：值得做且成本很小（约 1 个提交）——基础设施已就绪，只需接线。**

**为什么便宜：fork 已有完整的工具审批基础设施**（`workspace_*` 工具在用，逐处已核实）：

| 环节 | 现状 | 记忆审批需要做什么 |
|---|---|---|
| 判定入口 | `Tool.needsApproval: (JsonElement) -> Boolean = { false }`（`ai/core/Tool.kt:17`） | `buildMemoryTools` 加一个 `needsApproval` 参数并返回 true |
| 运行时 | `GenerationHandler` 每步循环（`238-277`）：`needsApproval=true` 且状态 `Auto` → 打 `Pending` + break 等用户；批准后从 `pendingTools` 恢复 | **零改动** |
| 拒绝回灌 | `Denied(reason)` 作为工具结果回给模型（`290-307`），模型能感知被拒 | **零改动** |
| 持久化 | `UIMessagePart.Tool.approvalState`（auto/pending/approved/denied/answered）随消息落库 | **零改动**（离开再回来仍见待审批卡片） |
| **审批 UI** | `ChatMessageTools.kt:135` 在 `isPending` 时自动渲染批准/拒绝按钮 | **零改动**——任何 `needsApproval=true` 的工具自动获得，记忆工具不用写 UI |
| 内容预览 | `MemoryToolUI.Summary` 已显示 `content`（`BuiltinToolUIs.kt:118`） | **零改动**（用户能看到「将写入什么」再决定，这是审批有意义的前提） |
| 配置模式可参考 | 工作区 per-tool 覆盖：`WorkspaceEntity.toolApprovals`（JSON map）+ `resolveWorkspaceToolApproval(name, overrides) = overrides[name] ?: defaults[name] ?: false` + 详情页 `WorkspaceToolApprovalCard` | 照抄模式（或更简，见下） |

**核心设计：按 action 分档，不要一刀切**

`memory_tool` 是**一个工具带三个 action**（create/edit/delete）。若整个工具都需审批，**连「新增记忆」也要每次点确认**——会显著伤害体验（记忆本意是自动积累），用户很可能干脆关掉记忆功能。

| action | 建议默认 | 理由 |
|---|---|---|
| `create` | **放行（不审批）**，但提供 `memoryCreateApproval` 开关（默认关） | 追加式、不改动已有数据、记忆页可删（可撤销）——故默认放行；开关留给想逐条把关的用户 |
| `edit` | **审批** | 覆盖已有内容 = **不可逆修改**，真正的风险点 |
| `delete` | **审批** | 数据丢失，最该拦 |

实现即在 `needsApproval` 回调里读 `input["action"]` 判断（`GenerationHandler` 传的是 `tool.inputAsJson()`），**基础设施完全不动**。默认值可做成助手级配置（`Assistant` 加 `@Serializable` 字段，随 JSON 自动持久化，**无需 PreferencesStore 三件套**——与 Settings 顶层字段不同）；记忆页加一个开关（落点：`AssistantMemoryPage` 已有 `enableMemory`/`useGlobalMemory` 开关列表，`217-246`）。若接受"仅 edit/delete 拦截"硬编码，连开关都可省。

**三个注意点**：

1. **审批是打断式的**：遇 `Pending` 会 break 等用户（`274-277`），生成暂停。状态持久化故不丢，但打断本身是成本——这是建议 `create` 放行的主因。
2. **看不到「原内容」**：`edit` 覆盖前的旧值不在 `arguments` 里，审批卡片只能显示新值（+ 记忆页可查）。要更严格可补旧值对比，不做也可接受。
3. **拒绝后模型可能重试**：`Denied` 原因回灌后模型可能再试同一操作。工作区审批已有此性质，非新问题。

**规模**：小（`MemoryTools.kt` 签名 + `GenerationHandler.kt:146` 调用点 + 助手字段 + 记忆页开关；UI 零新增）。**主要风险不在代码而在默认值**——默认全审批会因打扰致用户关闭记忆功能。

#### 记忆系统增强（检索与写入组织）—— **评估完成（2026-09-14）**

- **橘瓣实现**：HNSW 向量索引 + 记忆银行 + 每日总结（自动生成生活总结）
- **我们的现状**：assistant 记忆为「模型自觉调 `memory_tool` 写入 + 无脑取前 N 条注入」，无检索、无整理、无被动提取
- **语义检索结论：不做**（三方独立一致）——①本仓成本分析（`design-auto-compaction.md` §6.3：需新建原生/模型依赖 + DB 迁移 + 存量回填，属中大型功能；短文本正是嵌入区分度最差处，中文还需多语言模型）；②橘瓣 Supabase 云同步依赖第三方云，与隐私导向冲突，仅本地部分可参考；③**最有力证据 = Yuihub（RikkaHub fork）把语义检索上线后第二天整套删除**（`a8c33b2d` 加 `EmbeddingService`+`embedding` 列+打分融合 → `fbd15331` 全删，详 `peer-apps-benchmark.md` §四）
- **改走的方向（Yuihub 借鉴，见 `peer-apps-benchmark.md` §四）**：①**打分校准 + 字符预算**替掉取前 N 条（`importance × (0.55 + 0.45 × exp(-ln2 × age/30天))`，按字符预算而非条数纳入）——成本低、无需新依赖、**直接治下面的实缺陷**，推荐先做；②**被动提取管道**（回复后异步提取事实，带现有记忆索引让 LLM 输出 add/update/delete，天然去重消解；成本=每轮多一次 LLM 调用，须配开关/阈值）；③**整理器**（合并重复、纠类、控体积；随提取管道一起上）
- 与「记忆工具审批」是两件独立的事（一个是**写入管控**，一个是**检索能力**）

#### 记忆注入缺陷：`take(60)` 取的恰是最旧 60 条 —— **✅ 已修（`0c3a6bbc`，2026-09-14，真机验证）**

原缺陷：`GenerationPrompts.kt:19` 用 `memories.take(MAX_MEMORY_INJECT_COUNT)`（60 条）注入，而 `MemoryDAO` 全部查询**无 `ORDER BY`** → SQLite 按 rowid（=**插入顺序**）返回 → **超过 60 条后进 prompt 的是最旧的 60 条、最新写入的记忆反而进不去**（`MAX_MEMORY_COUNT = 200` 上限保证能堆到 200，故第 61 条起必然触发）。

**修复**：`buildMemoryPrompt` 改 `sortedByDescending { it.id }.take(60)`（id 为 `AUTOINCREMENT` 单调递增，降序即写入先后；排序收敛在此，行为不再依赖调用方列表顺序）+ `MemoryDAO` 四处查询补**显式 `ORDER BY id ASC`**（保持原 de-facto 行为、记忆页展示顺序不变，消除对未定义行序的依赖）。

**真机验证**（DB 副本补到 70 条记忆实测）：旧路径仅 `NEW-mem-1..12` 入选（最新 10 条全丢），新路径 22 条全入选。单测 4 项（含「结果不依赖入参顺序」回归护栏，撤销修复即失败）。

**后续（`91d88551` 已实现，见同节下方与 `design-memory-scoring.md`）**：升级为 Yuihub 式**打分校准 + 预算截断**（按 `importance × 时效衰减` 排序、按**预算**纳入而非数条数），即从「取最新」升级为「取最该取」——**详细设计已落盘 `design/design-memory-scoring.md`（2026-09-15，六项决策定案）**。
**配套更正**：「超 60 条才触发」是**假安全感**——60 是**条数**上限，管不住 token 量（本仓最长记忆 3646 字符 = 中位的 4 倍，60 条可达十几万字符）。实测设备（54 条/60K 字符）两个上限**都没触发** → 当前是**全量注入**（约 4 万 token/请求），故真正要做的不是换排序而是**砍到预算**。

### 不建议（隐私冲突，与项目定位相反）

> 这些是"AI 伴侣"路线的核心能力，依赖大量敏感权限，与本项目隐私优先定位冲突，**明确不借鉴**。

| 功能 | 依赖权限 | 冲突点 |
|---|---|---|
| 定位 / 附近搜索 | 高德 SDK + 位置 | 常驻定位追踪 |
| App 使用轨迹 | UsageStats | 读取数字生活习惯 |
| 短信读取 | READ_SMS | 高度敏感 |
| 拍照 | Camera | 摄像头随时调用 |
| 健康数据 | Gadgetbridge | 需穿戴设备 + 健康数据 |
| 主动消息 | 后台 Service + 通知 | 通知滥用风险 |
| Supabase 云同步 | 第三方云 | 数据出本机 |

---

### 实现优先级建议（供未来参考）

| 优先级 | 项 | 理由 |
|---|---|---|
| P1 | 三色定制（背景/气泡/重点色映射） | 毛玻璃已实现（输入栏/列表/顶栏三开关，依赖 haze 2.0），仅剩三色映射定制 |
| P1 | **记忆工具审批（按 action 分档）** | **基础设施已就绪（约 1 提交）**：复用现有 `needsApproval` + 通用审批 UI，只差接线；防未许可的改/删记忆（详见「中等价值」节） |
| P2 | 轻量个性化（输入框背景/字体包/头像框） | 气泡透明度已有，其余小改动零风险 |
| P2 | ~~记忆注入取数修正 / 打分校准~~ | ✅ **均已落地**：`0c3a6bbc`（取最新 60 条 + DAO `ORDER BY`）+ `91d88551`（**打分校准 + token 预算截断**，真机验证：模型实回 `importance:0.7`）。仅剩**回收站**（下一轮，见 `design-memory-scoring.md` §七） |
| P3 | 记忆提取管道 + 整理器 | 收益明确但成本中-高（每轮多一次 LLM 调用），须配开关（见 `peer-apps-benchmark.md` §四）。**2026-10-08 更新：上游已把它做出来了——PR #2130（`feat/file-based-memory`，OPEN / 0 review / 未合并）「Markdown 文件库 + 空闲后做梦整理」**。**四块拆分**：V1 纪律与安全（`[stated]`/陈旧性/硬清单 + `MemoryContentGuard` 39 行；**反注入声明与「附和/伴侣」条款已于 2026-10-09 移除**，见 2026-10-09 条）／V2 渐进披露（清单注入 + 按需 `memory_read`）／V3 做梦框架（`MemoryConsolidationScheduler` 285 行只认 memoryId + worker 接口、进度表、轮次切分/分批/保活/失败策略）／V4 Markdown 文件库本身——**前三块与文件模型无关**。**四条照搬阻塞**：①本仓 DB 已在 33 且 `Migration_27_28`/`Migration_28_29` 名字已占用；②它的迁移把旧记忆全塞一份**每次都全文注入**的 `/profile.md`（本仓实测 54 条/60K 字符 ⇒ ≈40K token/请求），并丢掉本仓的 `importance`/`public_id`/打分+预算；③67 文件里 38 个本仓已重分叉（`ChatService` +1286/−541），且它引用的 `GenerationLoop`/`dialogueMessages()`/`isContextCheckpoint` 本仓无对应物；④本仓的写入审批（`memoryModifyApproval`/`memoryCreateApproval`）会被架空（该设计把写入搬到后台 agent 且无审批）、`Enableable` 停用助手会被照样整理。**决策：不整体照搬，按 V1→V2→V3 分步、V4 等上游合并/稳定后再评**；顺带补该 PR 也缺的 per-conversation「无记忆/不整理」。**V1 + V2 + 信用记账已落地；V3 的框架（调度策略 + worker 接口，2026-10-09）也已落地，V3 的 worker / 进度表 / 接线与 V4 未做**（见 2026-10-09 各条）。详见 `upstream-analysis.md` 2026-10-08 节、`design/design-memory-discipline.md` 与 `design/design-memory-consolidation.md` |
| — | ~~记忆语义检索（本地 HNSW）~~ | **评估后不做**（2026-09-14）——本仓成本分析 + Yuihub 上线即删除双重印证 |
| — | QuickJS 插件系统 | **明确不做**（已有 eval_javascript / MCP / Skill / Workspace 四类机制覆盖） |
| — | 生活感知/健康/云同步 | **明确不做** |

**状态（2026-09-14 复核）**：QuickJS 插件系统**确定不做**；**毛玻璃/液态玻璃已实现**（`enableGlassTheme`/`enableBlurEffect` + haze 2.0，输入栏/列表/顶栏独立开关）；三色映射定制仍待；MCP mTLS 已实现（v2.4.9）；动态添加 Skill 已实现（v2.4.9）；**提供商多 key 已落地**（三.5，2026-09-09）；**搜索多 key 已落地**（三，`22f10675`）；**记忆修改审批已落地**（`1853b349`，按 action 分档）；**记忆语义检索经评估不做**。详见 `changelog/`（原 §八）。

---

## 二、对话分享（导出）功能规划

> **状态：✅ 已落地（2026-08-13，`1f4ad688`）**——双入口（列表页长按「分享对话」+ 聊天内顶栏导出）+ 三形态
> （HTML 可视化 / Markdown / 图片 PNG，HTML 于 2026-08-13 后续暴露 `generateMarkdownHtml` 后一并完成）。
> 「包含全部版本」Switch = `messageNodes.flatMap`（含分支/编辑历史）。2026-09-11 图片导出改为**分页多图 + ACTION_SEND_MULTIPLE**（`e4fe83b4`）。
> 与自动压缩的关系（导出=存储全量原文、不含检查点摘要）见 `design/design-auto-compaction.md` §4.6。下方「方案」保留为设计存档。

**需求**：将完整对话以可读形态分享/导出给他人。**两种入口 + 三种形态**，导出即走系统分享（ACTION_SEND）。

### 入口

| 入口 | 位置 | 说明 |
|---|---|---|
| **列表页分享**（不点进聊天） | `ConversationList.kt` 的 `ConversationItem` 长按菜单 | 加「分享对话」菜单项 → ChatDrawer 弹分享 sheet |
| **聊天内全量分享/导出**（点进对话） | 聊天页（ChatPage）顶栏/菜单 | 一键全量导出，不经"消息多选→全选"流程 |

### 形态

| 形态 | 说明 | 复用基础 |
|---|---|---|
| **HTML 可视化对话** ⭐ | 自包含 HTML：内联 CSS 气泡分色（用户/助手）、标题+时间、markdown 渲染、图片 base64 内嵌、思考过程 `<details>` 折叠、工具调用展示 | `generateMarkdownHtml`（`MarkdownNew.kt` L115，离线 JVM 转换）、`part.encodeBase64()`、`escapeHtml`（`StringUtils.kt`） |
| **Markdown** | 全量对话转 markdown 文件 | `exportToMarkdown`（`Export.kt` L240，现 private） |
| **图片 PNG** | 全量对话渲染长图 | `exportToImage`（`Export.kt` L379，现 private） |

### 复用基础（已确认）

- `ChatExportSheet`（`Export.kt` L110，public，接受 conversation + 消息列表，可独立调用，无需多选模式）
- `shareFile`（`Export.kt` L768，ACTION_SEND + FileProvider）——FileProvider 已在 AndroidManifest 配置
- `Conversation.currentMessages`（全量消息）
- 导出函数目前 private：分享 sheet 与其同文件，可直接调用（无需改可见性）

### 桌面版参考（Rikkahub Desktop 1.4.0，2026-08-12 源码调研）

- 桌面版（`yuh-G/rikkahub-desktop`，TypeScript/WebView2，Windows）已实现「重做对话分享」：**仅 Markdown（图片 base64 内联）+ 图片 PNG（DOM 截图）**，**无 HTML 形态** —— 我们的 HTML 可视化是增量
- 细节大多与我们 Android 版**等价**：图片 base64 内联、Tool input JSON 美化、`expandReasoning` 开关，且桌面版注释明确"对齐 APP `Export.kt` 的 encodeBase64 内联策略" → 两端导出策略一致
- 交互差异：桌面版是"浏览器下载文件"，我们是 ACTION_SEND 系统分享

### 状态

- 2026-08-12：方案已设计（列表页 + 聊天内双入口、HTML/Markdown/图片三形态），已批准计划存档。
- 2026-08-13：**已实现**（`1f4ad688`，详见 `changelog/`（原 §八））——三形态全部落地。本节保留为设计存档。

---

## 三、搜索服务多 key 故障转移（功能规划）

> **状态：✅ 已落地（2026-09-09，`22f10675`）**——`SearchServiceOptions` 加结构化 `apiKeys`（`@Transient` 基类规避层级序列化重名，16 个带 key 子类型 override）+ `withSearchKeyFailover`（`SearchTools.kt:143`，401/403/429 换 key）+ 每 key 一框 UI（复用提供商侧共享编辑器）。下方为设计存档。
> 注意与「三.5 提供商多 key」的历史误标：本条曾被标为「暂不实现」，实际已在提供商侧之后落地。

**需求**：搜索服务配置多 key（空白/逗号分隔），请求遇 key 失效自动切换下一个重试。

### 现状（Android 版）

- **多 key 轮换已支持**：`KeyRoulette.next(keys, providerId)`（`ai/.../util/KeyRoulette.kt`）内部按 `[\s,]+` 拆分多 key，`Default`=随机、`Lru`=LRU 轮换 —— 填多 key 即可轮换，但**不追踪失败**（请求失败不自动换 key）
- 搜索服务已接入 `keyRoulette`

### 桌面版参考（Rikkahub Desktop，源码已调研）

`pc-server/search/index.ts` 实现（思路可直接吸收）：
- `splitSearchApiKeys`：空白/逗号分隔、去重保序 —— **分隔符与 Android `KeyRoulette.splitKey` 一字一致**（注释明确：PC 写入的多 key 配置导出到 APP 必须能拆分，字段结构不变、备份兼容）
- `isSearchKeyError(status)`：**仅 401/403/429** 判定为"换 key 可解决"
- `withSearchKeyFailover`：逐个 key 执行，抛 `SearchKeyError`（401/403/429）且还有 key → 换下一个重试；网络/解析/5xx 或最后 key 失败 → 直接抛；单 key 等价直接执行
- `testAllSearchKeys`：逐个 key 测试（ok/fail + failCode + 脱敏 key），供设置页诊断

### 实现要点（吸收到 Android 版）

- 在 `SearchTools.kt` 搜索调用处包装一层失败重试（等价 `withSearchKeyFailover`），各搜索服务实现不动
- 错误判定沿用 401/403/429；超时/网络/5xx 不触发换 key
- 可选项：设置页"测试所有 key"（对齐桌面版 `testAllSearchKeys`）

### 状态

- 2026-08-12：桌面版实现已调研确认可参考，思路与规划一致，两端分隔符兼容已验证。
- 2026-09-06：明确本节是「三.5 提供商多 key」的**低风险试验田**——先在搜索侧跑通 key 错误分类 + 转移 + 逐 key 测试三件套（非流式短请求），提供商侧复用同一套判定基础设施。借鉴关系详见三.5。
- 2026-09-09：**已落地**（`22f10675`）——结构化 `apiKeys` + `withSearchKeyFailover`（`SearchTools.kt:71/121/143`）+ 每 key 一框 UI。

---

## 三.5、提供商多 key 故障转移（✅ 已落地 2026-09-09，真机验证）

**需求**：LLM 提供商（OpenAI/Claude/Google 等）配置多个 API key，请求遇 key 失效（401/403/429）自动切换下一个重试。

### 现状（2026-08-16 核查）

- **多 key 轮换已存在**：`KeyRoulette.next(apiKey, providerId)` 已接入全部 Provider（OpenAI/Claude/Google 请求头 `Bearer ${keyRoulette.next(...)}`），`apiKey` 文本框支持填分隔多 key（`[\s,]+` 拆分，随机/LRU）
- **无失败转移**：401/403/429 不会换 key 重试
- **用户实际未配置多 key**：日常提供商均为单 key，无现有多 key 数据 → 迁移成本几乎为零

### 方案（用户倾向：每 key 一输入框）

- **数据模型**：新增 `apiKeys: List<String> = emptyList()`，旧 `apiKey: String` 保留；读取 `keys = if (apiKeys.isNotEmpty()) apiKeys else listOf(apiKey)`（单 key 无缝）
- **UI**：配置页 apiKey 单框改为「多 key 输入区」——每个 key 一个输入框，支持添加/删除；多 key 才显示完整列表
- **轮换/失败转移**：直接操作 `apiKeys` 列表（`keys[i]` 切换），无需分隔符解析；401/403/429 换下一个 key 重试，网络/5xx 不换（复用搜索 `withSearchKeyFailover` 思路）
- **不做迁移脚本**：无现有多 key 数据
- **难度**：中（UI 多输入框 + 列表模型 + 失败转移，重点在流式重试）

### 数据模型与 UI 设计（2026-09-08 增补，比搜索侧更进一步）

```kotlin
@Serializable
data class ApiKeyEntry(
    val key: String,
    val enabled: Boolean = true,   // 可单独启停（临时熔断/调试），不删 key
)
// ProviderSetting 各家：apiKey（旧字段保留，备份兼容）+ apiKeys: List<ApiKeyEntry>
// 读取：apiKeys.filter { it.enabled }.map { it.key }.ifEmpty { listOf(apiKey) }
```

- **UI**：每 key 一框 + 启用开关 + 添加/删除（参照 customHeaders 动态列表交互）；key 值脱敏显示；单 key 用户默认一行无感知；旧 apiKey 值首入显示为第一行（写回迁入列表）。
- **enabled 三价值**：临时熔断（限流 key 禁用不删）；调试单 key；**自动熔断铺路**（失败转移落地后可演进：连续 401/403 N 次内存态禁用 + 冷却恢复，LoopGuard 语义在 key 层的应用）。
- **与搜索侧（三）的关系**：搜索桌面版参考是字符串一框分隔（无启停）——实现搜索侧时**直接采用同款结构化模型**，两端统一，搜索不停留在字符串形态。
- **降级链全景**（2026-09-08 比较结论，**2026-09-09 全部落地**）：key 级（凭证冗余，`ApiKeyEntry`+enabled+失败转移 `bcfa1f11`/`82af86f8`/`96f75ed0`）→ provider 级（服务冗余，`Assistant.fallbackChatModelId` fallback 模型链 `dd3c475b`：主模型整轮失败切备用重跑，半截不 fallback）→ #1812 半截保留草稿。完整路由抽象/OpenRouter 式不做。

### 范围建议（若立项）

1. UI 多 key 输入区（每 key 一框 + enabled 开关，见上增补设计）
2. 统一 key 错误异常（401/403/429 → `ProviderKeyException`）
3. 失败转移：非流式 `generateText` 低难度；主聊天流式 `streamText` 中难度（`retryWhen` 重跑 SSE）；**仅零输出失败换 key**（#1812 分流约束：半截 429 保留草稿不自动换）
4. 标题/建议/压缩等非流式生成一并接入

### 与搜索多 key（三）的借鉴关系（2026-09-06 分析）

**直接搬**（三件）：
- **错误分类语义**：仅 401/403/429 判定「换 key 可解决」，网络/解析/5xx 不换（桌面版 `isSearchKeyError` 同款边界）；provider 侧复用现成的 `isRetryableGenerationError` + `findHttpStatusCode` 基础设施，新增 KeyError 类别几乎零成本。
- **诊断模式**：桌面版 `testAllSearchKeys` 逐 key 测试（ok/fail + failCode + 脱敏）——扩展现有「测试连接」为「测试所有 key」，是多 key 配置体验的一半。
- **克制原则**：单 key 等价直通、最后一个 key 失败直接抛，不吞错误不做无限重试。

**必须改造**（两边最大架构差异）：
- 搜索的 `withSearchKeyFailover` 是调用处**外包一层**（请求-响应模型）；聊天侧已有 `withGenerationRetry`（RetrySetting 延迟序列 + per-provider 覆盖 + #1812 半截分流），再外包一层会导致**双层重试相乘**、延迟策略失效。
- 正确整合：把「换 key」作为 `withGenerationRetry` 重试循环内的**一种重试原因**——catch 错误 → 判定 key 错误且还有下一个 key → 立即换 key 重试（不等延迟或复用延迟首项）→ 否则走原逻辑。重试预算统一管理。

**与 #1812 分流的交互**（显式规则）：**换 key 转移只发生在零输出失败时**。401/403 天然零输出（鉴权在流开始前拒绝）→ 换 key 零成本照常转移；429 若出现在流中途（半截）→ 按 #1812 既定决策保留草稿交用户，**不自动换 key**。两条机制无缝拼接互不破坏。

**数据模型不借鉴**：搜索是字符串分隔多 key（分隔符与桌面版一字一致是备份互通约束，搜索特有）；提供商侧按已定方案走结构化 `apiKeys: List`（每 key 一输入框），`KeyRoulette` 兼容路径见上「方案」。

### 状态

- 2026-08-16：评估完成（可行性高，难度中；轮换已有，仅缺失败转移 + UI 多输入框）。**用户待时间安排后立项，暂不实现**。
- 2026-09-06：补充与搜索多 key 的借鉴关系分析（见上节）：错误分类/诊断/克制原则直接搬，包装器模式须改造为 `withGenerationRetry` 内的重试原因，换 key 仅限零输出失败（与 #1812 分流兼容）。实施顺序：先搜索侧（三）跑通三件套再上提供商侧。
- 2026-09-09：**全部落地并真机验证**。提交链：`bcfa1f11`（数据模型 ApiKeyEntry + effectiveApiKeys）→ `82af86f8`（401/403 换 key）→ `96f75ed0`（每 key 一框编辑器）→ `dd3c475b`（fallback 模型链）→ `0ee43d38`（全局默认备用，助手 > 全局两级）→ `4d7cb5da`（429 换 key）→ `51cce040`（顺序优先级 + 拖拽排序，default() 从 LRU 改 Order）→ `7f47b15b`（Gemini schema 补 items）→ `285b3be1`（fallback 悬空回退全局 + 有 fallback 压缩重试预算为 2）→ `23cfa613`（全局备用持久化三件套——0ee43d38 漏接线是四轮排查最终根因）→ `a732620d`（换 key 实际轮转修复：顺序模式首个 key 恒定导致假切换 + 无上界死循环；改为 action 接收 providerSetting + 瞬态副本轮转，每 key 至多试一次）→ `d154bea2`（SSE 流内错误文本兜底：额度耗尽可换 key 不等待、限流可换可等待，覆盖中转站 200+流内 error 无状态码形态）。**验证**：fallback 链真机生效（悬空→全局→0.3s 切换）；换 key 真机生效（真实 `inference exceeds tpm/rpm limit` → `key error, switching key (1 key(s) left)` → 第二 key 成功；双 key 皆限流时等待重试恢复）。**搜索侧（三）已于同日 `22f10675` 一并落地**（结构化 `apiKeys` + `withSearchKeyFailover` + 每 key 一框 UI）。

---

## 四、本地 OCR（本地优先 + AI 兜底）

**需求**：图片发给非视觉模型时由 AI OCR 识图（`OcrTransformer`）；纯文本模型或网络失败时 OCR 会**静默出错/空结果**，且图片出设备（隐私敏感）。

### 方案（来源：上游 PR #1632 + Cherry Studio 本地 OCR 先例）

`OcrTransformer` 改为 **本地 ML Kit OCR 优先 + AI fallback**：
- ML Kit（中文 + Latin，~20MB 首次下载缓存）设备端识别，**结果非空直接用**（免费/离线/隐私）
- 空/失败 → 回退配置的 AI OCR 模型（保精度）
- 视觉模型图片直传不变

### 精度 / 代价权衡（关键）

| 维度 | ML Kit（轻量方案） | 更重方案（PaddleOCR 系） |
|---|---|---|
| 模型体积 | ~20MB（首次下载缓存） | 几十 MB 起 |
| 常规印刷体（截图/文档） | 够用 | 更高 |
| 复杂版面/手写 | 一般 | 一般，仍需 AI 兜底 |
| 移动端集成 | 低（Google SDK） | 重（JNI/ONNX） |
| 离线/隐私 | ✅ 图片不出设备 | ✅ |

**设计合理性**：local-first 覆盖常规场景（隐私/免费/离线），AI 兜底高难度——移动端资源约束下最划算的平衡；ML Kit 是移动端合理定位，重精度留给 AI fallback。

### 前置

- #1632 已 closed（PR），但**不在基线（2.4.5 无 ML Kit）**——若上游不发版，可自行移植（`OcrTransformer` + ML Kit 依赖，改动集中）

### 状态

- 2026-08-12：探讨本地 OCR 精度/代价权衡（Cherry 先例 + #1632 方案），确认 local-first + AI fallback 是合理权衡。**暂不实现**。

---

## 五、上游 issue 关注清单

> 跟踪上游 rikkahub/rikkahub 的 issue。**bug 类**等上游修复后随 master 跟踪吸收；**功能类**按需评估。

### 与 fork 直接相关

| # | Issue | 处置 |
|---|---|---|
| **#857** | 请求恢复 HTTP 代理（15 评，上游作者拒绝） | **已由本 fork 实现**（master）；并已按社区「全局代理 + 开关」轻量形态落地**两层代理**（per-provider Inherit/None/Http/Socks5 + 全局兜底，`ff9a572c`/`7de9a814`/`30e69e20`，2026-09-13，**待真机验证**，见 `design-proxy-two-layer.md`） |
| **#1644**（open） | OCR 识图带 `reasoning_effort="none"` → 400（MIMO2.5/OpenCodeGo） | 跟踪；当前 OCR 路径若用这些模型会踩 |
| **#1632**（closed PR） | 本地 ML Kit OCR 优先 + AI fallback | 见「四、本地 OCR」 |
| **#1687**（closed） | MCP 连接支持 mTLS 客户端证书 | **已实现**（v2.4.9，P12/PEM + 密码 + 设置 UI） |
| **#1812**（closed） | 差网络下重试丢弃半截回复+重复计费 | **已由本 fork 自研方案实现**（零输出照旧重试 / 半截保留草稿落库，`bf562d9e`；阈值可配 `partialOutputMinChars` `57a2fe7c`），**真机验证完成**（3/3，2026-09-06）；同机制缓解 #1820（切对话回复消失）。v2「保留并续写」已放弃（2026-09-06） |
| **#1083** | apiKey 管理策略 | **已覆盖**：提供商多 key（三.5，`bcfa1f11` 系列）+ 搜索多 key（三，`22f10675`）均已实现 |
| **#540** | 对话分享勾选方向 bug | 对话分享已实现（二，`1f4ad688`）；该 bug 方向未见真机复现，如有再修 |
| **#1069** | 压缩上下文前自动导出聊天 | **诉求已由自动压缩的设计解决**（v1.0.4）：fork 压缩**不改 `message_node`（轨迹只追加）**，原文永不丢失，摘要存独立表且折叠行**可展开回看**——正是 issue 评论区的共识方案（「保留前文 + 总结可点开」）。**字面的「压缩前自动导出」不采纳**（前提不成立 + 全量导出随时可得）；边界（导出=存储全量 ≠ AI 所见窗口）见 `design-auto-compaction.md` §4.6 |

### bug 类（等上游修复随 master 吸收）

| # | 主题 | | # | 主题 |
|---|---|---|---|---|
| #1166 | Gemini 生成中途截断无报错 | | #1557 | Fish Audio 无输出 |
| #1314 | 切屏/切助手丢失生成内容 | | #465 | TTS 播放漏内容 |
| #925 | 长对话 MCP tool_use JSON 解析错误 | | #629 | 上传音频报错 |
| #1530 | calendar_create 虚假成功 | | #1114 | markdown 表格未渲染 |
| #552 | 通知进入聊天消息消失 | | #1063 | 显示排版异常 |

### 功能灵感（可选）

#1380 聊天记录同步 · #1399 Shizuku adb shell · ~~#1031 动态添加 Skill~~（**已实现** v2.4.9）· #826 定时任务 · #121 划词翻译 · #577 图片生成提取 URL

---

## 六、附录：备份互通性（已知边界）

**机制**：RikkaHub 备份核心 = Settings JSON；多态 sealed class 的 `type` 字段存**完整类名**（如 `Avatar.Dummy`）。两端 Json 均 `ignoreUnknownKeys=true`（新增字段不致命），但**多态 type 硬匹配**。

| 方向 | 是否互通 | 原因 |
|---|---|---|
| **原版 → 本 fork** | ✅ **通** | `SettingsJsonMigrator V0`（`me.rerere.` → `app.androidrik.` 前缀替换，覆盖多态字段）；原版缺的新字段（proxy 等）用默认值、多余字段被忽略 |
| **本 fork → 原版** | ❌ **不通** | 导出的 type = `app.androidrik.xxx`，原版 Serializer 只认 `me.rerere.xxx` → "Serializer for subclass ... not found"；原版无包名迁移器 |

- **例外**：上游 V1/V2 已短名化的多态类（McpConfig 的 `sse`/`text`、UIMessagePart 的 `text`/`image`）两端一致**可通**；仍用 FQN 的类（如 `Avatar.Dummy`）不通
- 云端备份目录已区分（`rikkahub_plus_backups` vs 上游 `rikkahub_backups`）——路径问题，非格式问题

**可选根治方向**（未来）：
1. 多态子类加显式短 `@SerialName` → type 短名两端一致，**双向彻底互通**（需改所有多态 sealed 子类，规模大、一劳永逸）
2. 导出"兼容版"备份：导出时反向替换 `app.androidrik.` → `me.rerere.`（逆向 V0）→ 原版可读（轻量，仅单向）

**状态**：2026-08-12 记录已知边界。**暂不处理**。

---

## 七、RikkaHub 二改版调研（2026-08-13）

> 调研市面上基于 RikkaHub 的衍生 fork，评估可吸收点。原始调研记录存档于此。
> **注（2026-09-14）**：其中多条借鉴点**已落地**——TikZ 渲染已实现（最终链 native Tectonic + mupdf SVG 桥，非本节早前构想的 workspace pdflatex/pdftocairo 方案，详见 `tikz-rendering-tectonic-postmortem.md`）；前台服务保活已落地（KeepAliveService + WifiLock `935f8b97`）；技能触发词自动注入仍待。

### 1. RikkaHub Agent（ExTV/rikkahub-agent，179★）

**定位**：RikkaHub fork + 完整 Agent 模式——"设备控制型"（80+ 设备工具、Shizuku 提权、Workflows 自动化、Telegram Bot、应用内浏览器、端侧 LLM、Linux workspace、SSH、屏幕自动化、语音转录）。**全部 opt-in + HARDLINE 安全底线**。

**可参考点**：

#### ⭐ 免 key DuckDuckGo 搜索 + 断路器（`search/` 独立模块）
- **19 引擎抽象**（Tavily/Exa/Brave/Serper/Jina/Firecrawl/SearXNG/Bing/Perplexity/Ollama/自定义 JS…）
- **DuckDuckGo 免 key**：HTML 端点解析 + `DdgOutcome`（Results/Empty/Blocked）分类，反爬检测返回 `SearchRateLimitedException`（明确可重试错误，非静默"无结果"）
- **`SearchCircuitBreaker`**：内存断路器，CLOSED→OPEN（3 次连续失败）→HALF_OPEN（60s 冷却后放一个探针）→CLOSED/OPEN。线程安全（`@Synchronized`）
- **`SearchEgressGuard`**：出站防护
- **与我们 roadmap 的关联**：「搜索服务多 key 故障转移」可直接吸收引擎抽象 + 断路器状态机

#### ⭐ HardlineCommandGuard（无条件命令黑名单）
- **分层安全模型**：hardline（不可恢复破坏命令，**批准前无条件拦截**）→ 常规批准流
- 拦截：`rm -rf /` 及根目录系、`dd of=/dev/sd*` 块设备覆盖、`reboot/halt`、fork bomb、`kill -1`、`base64 -d|sh` 编码管道、`bash -c "…"` 内嵌逃逸
- **命令位置锚定正则**（`^|[;&|\n`]|$(` + `sudo`/`env VAR=`/`exec`/`nohup`/`setsid` 包装 + `bash -c` 变体）——防 `echo reboot` 误伤
- 明确威胁模型边界：**防粗心不防对抗**（变量间接引用/混淆链正则挡不住，交给 per-tool 批准）
- **与我们 fork 的关联**：我们有 workspace_shell，缺这样一条"即使 always-allow 也不可绕过"的安全底线

#### 中价值：WebExtractor（jsoup 正文提取）+ web_fetch 长文档分页
- 去导航只留正文、长文档分页防爆上下文。与「对话分享」HTML 形态部分相关

#### 明确不参考：80+ 设备工具 / Shizuku / Workflows（19 触发+14 条件）/ Telegram Bot / 屏幕自动化
- 即我们 roadmap 判定的「生活感知」方向（定位/通知/短信/App 使用轨迹），隐私冲突

### 2. RikkaHub Root（a1494190052-glitch/rikkahub-root，2★）

**定位**：RikkaHub 2.4.3 + **Root 权限增强 + Agent 子代理系统**。
- Root：root_shell / pty_exec / pty_session、root_screenshot / ui_tree、工作区 Root 模式
- 子代理：5 内置 profile（explore/coder/reviewer/researcher/coordinator）+ 嵌套（coordinator→explore）+ 并行派发 + 进度可视化
- MCP Server（Streamable HTTP 8081，暴露本地工具给 Claude Desktop，Bearer token + mDNS）+ Web Server（8080）

**评估**：Root 强依赖 + 仅 2★，受众窄。子代理 profile 系统思路可留意，但方向未规划。**暂不参考**。

### 3. RikkaHub Turbo（`dcs666/rikkahub-pro`，1★）

> **仓库名更正（2026-09-19）**：本节旧称 `dcs666/rikkahub-turbo`——**该 URL 不存在**（API 与网页均硬 404、无重定向；对照已知改名仓 `home-assistant/home-assistant` → `core`、`facebookincubator/create-react-app` → `react/create-react-app` 均保留 301）。实际仓库是 **`dcs666/rikkahub-pro`**：`rikkahub/rikkahub` 的 fork，建于 2026-07-24，含 `perf/rendering-and-streaming` 分支与 `v1.9.x-turbo` 系列 tag（同用户名下另有**非 fork** 的 `rikkahub-pro-old`，07-28 建、08-06 停更）。「turbo」是该二改版的**自称/绰号**（发行 tag 即 `-turbo` 后缀），从未是仓库名——**仓库可达**，下文及 `references/turbo-bugs-audit.md` 中「源仓不可达、diff 无法复核」的表述据此作废。

**定位**：RikkaHub 激进性能实验场——pro 完整镜像 + **渲染/流式极限优化**（带 trade-off）。默认分支 `perf/rendering-and-streaming`。实际是"全量加固 + 性能优化"实验场：**548 commits / 300 文件 / 修复 80+ 真实 bug**（PROGRESS.md 730 行完整记录）。

**深挖结论（2026-08-13，PROGRESS.md 逐段阅读）**：

#### 与「批准卡顿」直接相关

**① c897647 setJob identity 修复（已移植 ✅）**——`ConversationSession.setJob` 旧 job 完成回调无条件 `_generationJob.value = null`，连续 sendMessage / approve 重启生成时旧回调晚于新 job 执行 → 误清新 job 引用 → `isGenerating` 假 false、`stopGeneration` 失效、空闲检查误清理。**我们 fork 有同款 bug，已移植修复**（identity `=== job` 比较）。

**② R3.2 块级结构化流式渲染**——流式分支从"代码块拆分"升级为"按行块分类"（代码/标题/引用/列表/段落），样式与最终渲染对齐（HeaderStyle.fromLevel / 引用左线+斜体 / "• " 与 "N. " 前缀）。**追加式扫描每帧 O(n)，已渲染块参数不变 → Compose 智能跳过重组**。JS 模拟 18 帧验证块序列稳定。→ **我们卡顿的解药方向**（非 β/γ 小优化，是渲染架构改进：流式 chunk 按块分类 + 已渲染块跳过重组）。**（2026-08-13 已移植评估 `d4c0f5e5`：实测无明显改善、保留加载动画 `98829097`——已不再是待办方向）**

**③ R2.1 折叠调优**——阈值 5000、整区可点展开、**`!loading` 排除生成中消息**、USER 不折叠。生成中消息不做折叠动画 = 减少重组。

**④ MarkdownParseCache LRU 上限 30**——流式每帧解析缓存有界无泄漏。

#### 与「Response API 多工具 400」相关（我们已修 #1690）

**⑤ v1.8.5 #57 同根因独立复现**：网关按 function_call 逐个校验 reasoning——1 个 reasoning 只服务其后第 1 个 fc，同消息第 2 个 fc 起缺 reasoning → 400。方案与我们一致（`fcSinceReasoning` 计数器）。**验证了我们的修复正确性**。

**⑥ 增量发送实验（v1.8.7/8.8 → v1.9.2 全禁用）**：`previous_response_id` + store 增量（只发新增），实测**opencode.ai 网关静默丢弃 previous_response_id**（200 但失忆）→ **全量发送为唯一正确路径**。我们没做增量是对的。另修复 P0-3（store=false host 禁用增量）。

#### 其他高价值发现（可参考）
- **#56 工具消息 reasoning 兜底**（c0253dc）：thinking 开启时工具消息缺 reasoning → 400，chat/completions 补 `reasoning_content:""` + responses 补占位 item——与我们 #1690 互补的边界
- **大文件拆分**（Strangler Fig）：>800 行文件全拆 <600，ChatService 1291→746、PreferencesStore 801→519 等 33 个——长期可维护方向
- **修复 80+ bug 清单**（PROGRESS.md）：超长消息保存静默失败、压缩覆盖新消息、zip-slip 路径穿越、SettingsStore update 并发 lost update（Mutex 串行化）、webhook HMAC 恒失败、Room AutoMigration 缺口、QuickJS 原生内存泄漏、JSON 深嵌套 SOE、文件名路径逃逸等——**是上游 2.4.5 已知 bug 的完整清单，可逐条对照我们的 fork 检查**。⚠️ 已整理为**可核查清单**：`docs/references/turbo-bugs-audit.md`（48+ 项分四类 + fork 状态列），原始存档 `docs/references/turbo-PROGRESS.md`

**评估**：turbo 的 **R3.2 块级流式渲染** + **c897647 setJob（已移植）** 最值得吸收；修复清单可作我们 fork 的 bug 审计对照表。

#### 2026-10-06 增量评估（仓库已停更；本轮深挖 `perf/rendering-and-streaming` 分支）

> **仓库现状**：`master` 停在 2026-08-12，但**真正的工作分支 `perf/rendering-and-streaming` 领先 master 78 提交**（tip `63da3f0d`，2026-08-13，v1.9.20-turbo）。**该仓库已停更约两个月**。本轮把该分支上早于/晚于我们 08-13 存档的内容全部过了一遍——**遗留可吸收项 6 条，其中 1 条是 P0 安全缺口**。

**已吸收 / 已确认无需动作**（避免重复评估）：
- **R3.x 块级结构化流式渲染——我们已有**（`Markdown.kt` 的 `splitStreamingBlocks` + 行内格式闭合可见 + 后台预热解析；我们比 turbo 更进一层——块计算本身也移出主线程）。**陈旧记账更正**：本文件上文与 handoff 中「R3.2 待实现」的表述已过时。
- **48 项 bug 审计**（`references/turbo-bugs-audit.md`）：32 项已修，仅剩 C17（编译期无法对照）+ D1（待复核）。
- 已移植：`c897647` setJob identity、`db26bff` file_paths 收窄、`f0aa1481` 请求日志 header 脱敏、`2ed15a3c` extractTar/parsePax + ASR 静默失败、`3652fd6` normalizeNewlines。

**P0 —— web-ui markdown XSS（真缺口）——✅ 2026-10-06 已修（见下「落地记录」）**：
- **现状（本轮实证）**：`web-ui/app/components/markdown/markdown.tsx:116` 用 **`rehypeRaw` 渲染 LLM 原始 HTML 但未接 `rehype-sanitize`**；同一行的 `rehypeKatex` 还开着 **`trust: true`**（KaTeX 的 `\href` 等命令因此不再受协议白名单限制）。而 `web-ui/app/services/api.ts:39/109` 把 **JWT 存在 `localStorage`**。
- **攻击链**：模型读到不可信内容（我们有 `scrape_web`/`search_web` ⇒ 提示词注入面）或用户导入含 HTML 的会话/备份 ⇒ 回复里的 `<img src=x onerror=…>` 在 web UI 执行 ⇒ **读走 localStorage 里的 JWT ⇒ 拿到内嵌 web 服务器（`web` 模块）的完整 API 权限**；`<a href="javascript:…">` 点击亦可执行。
- **turbo 修法（可照抄）**：`37ca61b7` 接入 `rehype-sanitize` 默认白名单（禁 script/iframe/object/form/meta/link + `javascript:` 协议），**并定制保留 `className`（katex 体系 / citation-badge / `language-*`）与 `style`（katex strut 行高）**——否则公式与代码高亮会被一起洗掉。
- **配套教训（他们踩过）**：`1c44fe92`——`package.json` 声明了依赖但 `pnpm-lock.yaml` 的 **importers 段**未注册 ⇒ CI `ERR_PNPM_OUTDATED_LOCKFILE` 挂（packages 段有传递依赖不算）。我们仓 `rehype-sanitize` 已在 lock 的 packages 段（传递依赖），**仍须补 importers 段**。
- **成本**：小（一个依赖 + 插件配置 + 白名单定制）；**风险**：低（只动 web-ui 渲染，Android 端不受影响）。

**P1 —— 工具级执行超时缺失（我们没有；turbo `116a160`）——✅ 2026-10-06 已落地**：
- **现状**：`GenerationHandler.kt:623` 的 `toolDef.execute(args)` 是**裸调用，无任何超时包装**。MCP 服务器卡住、网络工具悬挂时整轮一直停着（用户仍可点停止取消协程 ⇒ 属「可恢复」而非死锁，但体验与失败语义都差）。
- **turbo 修法**：`executeToolSafely()` 统一包装——`workspace_shell` 用「命令 timeout + 15s 缓冲」、其余 60s；`TimeoutCancellationException` 转成**工具错误输出**（模型可据此自我纠正），`CancellationException` 照旧传播（用户停止仍取消全部在飞工具）。
- **我们的约束（不能照抄「60s 一刀切」）**：① `dispatch_subagent` 是**有意阻塞**的（run 可跑到 `timeoutSeconds`，Tier-1 配置化后上限 **7200s**）——一刀切会把子代理派发全部杀掉；② `workspace_shell` 自带命令超时；③ `workspace_run_background` 立即返回（长跑在后台任务里，不受影响）。**结论：须做成「工具自带超时声明 + 默认值」**。
- **成本**：小–中；**价值**：中–高。

  **落地记录（2026-10-06）**：`Tool.executionTimeoutMillis`（默认 60s，`null`=工具自管）+
  `executeToolWithTimeout`（`GenerationHandler.kt` 顶层 internal，可单测）。**关键实现点**：
  `TimeoutCancellationException` **是** `CancellationException` 子类，而工具执行失败的
  `onFailure` 对 `CancellationException` 一律原样上抛（那是「用户停止生成」的语义，必须传播）
  ⇒ 包装层必须把它换成 `IllegalStateException`，否则超时会被误认成用户停止（既无错误输出给
  模型、也不被 LoopGuard 记成失败）。**三类显式声明**：`workspace_shell` = 命令上限 600s + 15s
  缓冲（**必须大于命令上限**，否则正常长命令被误杀）；`dispatch_subagent` = `null`（阻塞等整轮
  子代理，预设可达 7200s，runner 自带看门狗）；MCP 原生工具 + `mcp_tool_call` 桥 = 180s
  （服务端可能是 LLM 驱动的重型服务）。单测 4 项（超时转普通失败 / 限内通过 / null 不介入 /
  外层取消仍传播）。**注**：`eval_javascript` 本就有 10s 工作线程超时（自管），无需声明。

**P1 —— 每 workspace 常驻 shell 会话池（我们没有；turbo `6077ce4`）**：
- **现状**：`workspace/ProotShellRunner.kt`（172 行）**无会话复用**——每条 `workspace_shell` 重新起 proot + bash，冷启动约 1s；多步会话 20 条命令 ≈ 白花 20s。
- **turbo 修法**：按 `linuxDir` 的 **LRU 会话池（上限 3）**——切工作区不再销毁会话；**锁粒度从一把全局锁降为「每工作区一把」**（跨工作区命令并行、同工作区仍串行）；淘汰只挑空闲会话。
- **他们踩过的坑（必须带上）**：`removeEldestEntry` 内同步 `destroy()`，若被淘汰会话有命令在跑，会**阻塞所有工作区的 shell**（全局头阻塞最长 600s）⇒ 改为手动扫描、只淘汰空闲、全忙时允许池暂时超限。
- **与我们机制的交互（风险点）**：我们的 `ProcessSignals` 按**唯一标记 + `/proc` 反查进程树**做超时/取消杀进程，后台任务另有一套进程生命周期——常驻会话会让「杀进程」语义变复杂（杀哪棵子树、会话是否重建）。**中风险，建议单独设计后再动**。
- **价值**：中；**成本**：中。

  **待实施的设计约束（2026-10-06 勘察后记录，勿草率动手）**：现执行路径是「一次性进程」——
  `ProotShellRunner.execute` 每命令 `start()` 一个新 proot 并 `readResult(timeout, stdin, marker)`；
  改成常驻会话需要同时处理五件事：
  1. **命令与输出的定界**：常驻 `proot bash` 只给一对 stdin/stdout，必须自己下发哨兵标记 +
     解析到哨兵为止；而**现状是 stdout/stderr 分离管道**（`redirectErrorStream(false)`），
     会话化后两流会在同一管道里交错 ⇒ 要么给 stderr 单独重定向到文件再读，要么接受合并
     （会改变 `WorkspaceCommandResult.stderr` 的既有语义与错误文案）。
  2. **stdin 一次性路径会冲突**：`writeTextInRootfs` 等把内容从 stdin 灌给 `cat > file`
     （二进制安全）；常驻会话的 stdin 已用于下命令 ⇒ 这些调用必须走**独立进程**（即会话池
     只服务「纯文本命令」这一子集，其余保持一次性进程）。
  3. **与 `ProcessSignals` 杀进程语义耦合**：超时/取消靠「唯一标记 + `/proc` 反查进程树 +
     自底向上 SIGKILL」。常驻会话意味着**杀一棵会话子树后该会话必须作废并重建**，且
     `readResult` 的 `marker` 现在是每命令唯一 —— 会话化后标记要挂在**会话**上，杀完
     还要把池里那条记录摘掉（否则下一条命令会写进已死的 stdin）。
  4. **淘汰只能挑空闲会话**：turbo 踩过的坑（`removeEldestEntry` 内同步 `destroy()` 会阻塞
     所有工作区最长 600s）必须复现规避——手动扫描 + 只淘汰空闲 + 全忙时允许池暂时超限。
  5. **后台任务不受影响**：`ProotBackgroundLauncher` 直接复用 `start()` 走独立进程，与池无关
     （确认过路径），但 `workspace_kill_task` 的「找进程树」逻辑要与会话池共存。
  另需验证：会话内 `cwd` 的维持（现状每命令显式传 `cwd`，会话化后要 `cd` 或继续显式前置）、
  工作区被删除/重装 rootfs 时会话失效、以及冷启动收益的实测（turbo 报 ~1s/命令）。
  **收益**：多步会话里 N 条命令省 N×~1s；**风险**：中（上述 5 点任一处理不当代价都高于收益）。

**P2 —— 同轮多工具并行执行（turbo `115ca09`）**：
- **turbo 做法**：同轮 Auto/Approved 工具 `coroutineScope + async`（Semaphore 4），结果按调用顺序挂回；超时与错误消化统一进 `executeToolSafely`。
- **为什么不能直接抄（本轮最重要的判断）**：我们工具循环里**护栏是有序且有状态的**——`loopGuard.record()` 按出现顺序累积窗口（失败时 `forget`）、`executedSignature` 去重表批内共享、`contextGuard.evaluate()` 是**逐调用、执行前**判定、`WorkspaceTurnBudget` 按「实际进入上下文」的字符累加、`denialGuard` 按序计数。并行化会打乱这五者的顺序语义（预算判定与结果累加不再一一对应、LoopGuard 窗口顺序失去意义）。**turbo 的分支早于我们这些护栏**，所以他们能直接并行。
- **若要做**：需重设计护栏交互（「先按序一次性记录护栏 → 再并行执行 → 结果按序回填 → 预算统一结算」），并接受他们如实记录的取舍——**同轮写同一文件的 lost update**（我们额外有 `expectedMtimeMs` 冲突检测，正好兜住这条）。
- **价值**：中；**成本/风险**：中–高（护栏语义）。

**P3 小件**：
- **生成打断的可见标记（`2945c612`）**：我们 `persistStoppedGeneration` 保留半截内容（#1812），但**用户看不到「这条被打断了」**；turbo 追加可见标记 + **上传/导出时过滤该标记**（防标记污染数据）。小、用户可见。（**✅ 2026-10-06 已落地**：`appendInterruptionNotice` 幂等追加可见提示；零输出不打标、末尾非助手不打标。**两处比 turbo 更省**：① 提示带 `uiNotice` 元数据、在 `buildInternalMessages` **唯一装配点**过滤（四 provider 都从这里取消息）——turbo 改了 4 个 provider builder；② 落库**保留**提示 ⇒ 导出/备份能看到当时发生了什么（turbo 上传时过滤掉）。另：替换**选中分支**而非 push 新消息，避免凭空多一个候选分支。）
- **日志页实时刷新（`71f78ef2`）**：turbo 给 `Logging` 加**监听器订阅**（`CopyOnWriteArrayList`、锁外通知；common 无 coroutines 依赖故不用 Flow），页面 `DisposableEffect` 注册/注销。我们的 `LogPage` 是进入时一次性快照——看新日志得退出重进。小。（**✅ 2026-10-06 已落地**：`Logging.addListener` + `LogPage` 的 `DisposableEffect` 订阅/反注册。**锁外通知**是要点——回调方自身 `getRecentLogs()` 要加锁，锁内回调会互等；common 无 coroutines 依赖故用回调而非 Flow。）
- **webhook 强制签名认证（`6137b3a7`）**：未配 token 直接 403（turbo 原先未配就不校验）。我们**目前没有 webhook**；将来做任务/定时 webhook 时带上这条默认安全。
- **provider 兼容小件**（Console Go 的 `input items` 强制 id / assistant 字符串 content / 内置工具补 `name` / 仅 UrlContext 时不发空 `tools`）：面向特定上游，与我们无关；但 `10ccc7d5`「**同一消息内多 item id 唯一化（msg/rsn/img + 自增序号）**」与我们 `1766776d`（补插 reasoning 去 `rs_` id）**同域**，将来再遇重复 id 报错时其「前缀+序号」是可参考的通用化写法。

**P0 落地记录（2026-10-06）**：
- **根因比评估时更深一层**：不只是「`rehypeRaw` 没接 sanitize」——Streamdown 内部**默认**就带
  `[raw, sanitize, harden]` 三件套（`gn = Object.values(xt)`），但它的默认值**只在未传
  `rehypePlugins` prop 时生效**；我们传了自定义列表 ⇒ **整个默认三件套被替换掉**，等于亲手关掉了
  它自带的防护。教训：**覆盖第三方组件的插件/中间件 prop 时，必须确认默认链是否被整体替换**。
- 修法：显式 `[rehypeKatex, …, rehypeRaw, [rehypeSanitize, schema]]`（sanitize 必须**排在最后**，
  清的是 raw 解析出的真实元素）；白名单抽为 `web-ui/app/components/markdown/sanitize-schema.ts`
  便于复用与审阅。
- **两处比 turbo 版本更严**：① `style` 只放开 `span`/`td`/`th`（KaTeX 实际用到的那三类），
  而非 `"*"` 全放开——少掉整页遮罩式钓鱼（`position:fixed;inset:0`）的注入面；
  ② 显式补 **MathML 标签**（默认 schema 不含 ⇒ KaTeX 的 `<math>` 子树会被展开成裸文本，
  原始 TeX 变成可见内容）。刻意**不加 SVG**。
- **验证方式（真实流水线，非静态推断）**：临时脚本用与组件完全相同的 unified 链跑
  **8 类 XSS 载荷 + 5 项功能回归**，全 PASS——img onerror / `a javascript:` / raw a javascript: /
  script / iframe / svg onload / form / `data:` img src 全部中和（并打印「清洗前 `onerror` 确实
  存活」以证明漏洞真实存在）；KaTeX `.katex` 类 + 内联 style + MathML 存活、`language-kotlin`
  存活（CodeBlock 依赖）、`<details>` 等既有 raw HTML 能力保留。脚本验证后删除。
- 依赖：`pnpm install --lockfile-only` 让 pnpm 自动写 **importers 段**（避开 turbo `1c44fe92`
  的 `ERR_PNPM_OUTDATED_LOCKFILE` 坑）；`pnpm run typecheck` + `pnpm run build` 通过。

**方法论（非代码，可复用）**：
- **极致优化专项**：死代码清理两轮（`-763` 行、删 3 文件）+ 两个大函数拆分（ChatDrawerContent 577→220、SettingTasksPage 551→160），手法是 **Strangler Fig**（先建新文件、逐块搬移、旧壳变薄）。
- **「提取块 7 项自查标准」**（机械拆分/搬移代码后必查）：① 注释平衡（`/**` : `*/`）② 尾部孤儿注解/`@Composable` ③ 括号平衡（剥离模板字符串后）④ `export` 前缀（python 提取必丢）⑤ 重复声明（header 与 block 边界不得重叠）⑥ **`@Composable` 注解完整性（从函数名开始提取会丢上一行注解）** ⑦ `private` 顶层跨文件可见性（提取后必须 `internal`）。做类似拆分时可当 checklist。
- **CI 纪律**：他们连续多轮 CI 红的教训与我们**已独立收敛同一批规则**（编辑后 grep 核对新增符号、同文件编辑严格串行、CI 失败要拉**全量** `e:` 行而非只看头部）——互证有效。


### 4. RikkaHub Neomix（heikeyangle-code/rikkahub-plus，2★，与我们同名不同方向）

**定位**：酒馆（SillyTavern）增强 + 命理排盘。角色卡 22 字段无损、世界书官方语义全对齐、宏引擎 2.0、21 斜杠命令、群聊、前台服务保活、技能系统。

**可参考点**：

#### ⭐ 技能触发词自动注入（`SkillAutoTriggerTransformer`）—— Claude Code 风格
- SKILL.md frontmatter 支持 `triggers`（触发词）+ `inject_position`（`before_system` / `after_system` / `in_chat` 三档）
- 扫描对话文本，`context.contains(trigger, ignoreCase=true)` 命中 → 自动注入 SKILL.md body 为 system 消息
- 无触发词的技能由 `use_skill` 工具处理（systemPrompt 列出全部技能）
- 实现独立轻量：一个 `InputMessageTransformer` + frontmatter 字段扩展，**可直接移植到我们 fork**
- **与我们 roadmap 的关联**：现有 Skill 是"模型手动调用"，触发词自动注入是体验升级

#### 中价值：前台服务保活（切后台生成不中断）
- 通用体验提升，用户关注的后台生成问题。可参考

#### 不参考：命理/群聊/斜杠命令/宏引擎 2.0 —— 垂直场景

### 技能系统实现细节（heikeyangle plus）

- **SkillManager**：应用外部目录（getExternalFilesDir）+ 公共目录（`/storage/emulated/0/Rikkahub/skills/`）双扫描；原子写入（staging+rename）
- **SkillFrontmatterParser**：纯文本 YAML 子集解析（`---` 分隔 + `: ` 分割 + 去引号，非完整 YAML）；`allowed_tools` 支持逗号/JSON 数组/`Bash(gh issue view:*)` 带参三种格式
- **SkillAutoTriggerTransformer**：按 inject_position 分类后，在 system 前后/in_chat 插入 `[Skill: name]\nbody` 消息

### 5. RikkaHub Math+（test27818/rikkahub-math-plus，7★）

**定位**：RikkaHub 2.4.3 数学增强——**TikZ 图表自动渲染 + ai4math-lean 数学形式化技能**。

**① TikZ 图表渲染管线**（核心，新增 3 文件 + 改 4 文件）
- 检测 AI 输出的 TikZ 代码（tikzpicture / tikzcd / tikztiming 全家族）
- 动态生成 preamble（**自动提取用户代码里的 `\usepackage` / `\usetikzlibrary`**）
- 工作区 pdflatex 编译 → pdftocairo 转 SVG → **三层缓存**（内存 LRU → 磁盘 → 重编译）
- 深色模式 LaTeX 源头注入 `\color{white}`（告别黑字隐形）
- **缺包诊断**：编译失败读 `.log` 红字提示"缺少宏包: xxx.sty"；**失败可视化**而非静默乱码
- 智能尺寸：小图原尺寸、大图限宽 320dp

**② ai4math-lean 技能**：proot 工作区部署 Lean（elan + mathlib），`#check` API 探测、退出码验证；成果 47 定理机器验证。**垂直场景，不参考**。

**价值评估**：
- TikZ 渲染**管线架构**（检测→preamble 生成→编译→三层缓存）可作参考——RikkaHub jlatexmath 只渲染公式不渲染图表
- **动态 preamble 提取** + **缺包诊断 UX** 设计巧妙
- 依赖工作区 pdflatex（我们 fork 有 workspace，技术栈可通）；**与我们 roadmap 无关但值得记录**

---

## 八、状态记录（逐日日志已按月迁至 `changelog/`）

> **2026-10-30 拆分**：本节原有的 **337 条**逐日落地日志（2026-08-12 起）已按月份拆成独立文档（见下表），正文逐字未改；本节只保留**索引 + 追加约定**。
>
> **为什么拆**：日志「只追加」、线性增长到 3300+ 行，和「规划方向」挤在同一篇会让读者把已完成的记录误读成现状；拆开后规划篇只讲方向与状态，日志按月查阅，diff 也不再互相污染。

| 月份 | 日期跨度 | 条目数 | 文件 |
|---|---|---|---|
| 2026-08 | 2026-08-12 ～ 2026-08-17 | 44 | [`changelog/2026-08.md`](changelog/2026-08.md) |
| 2026-09 | 2026-09-06 ～ 2026-09-28 | 97 | [`changelog/2026-09.md`](changelog/2026-09.md) |
| 2026-10 | 2026-10-01 ～ 2026-10-28 | 196 | [`changelog/2026-10.md`](changelog/2026-10.md) |

**新增日志的约定**

1. 追加到**当月**文件末尾（`docs/changelog/YYYY-MM.md`），格式沿用 `- YYYY-MM-DD：…`；
2. 跨月新建文件，并在上表按月升序登记一行；
3. 每条写清四件事：**做了什么 / 提交短哈希 / 验证状态（编译 · 单测 · 真机待验）/ 暂缓或不做的理由**；
4. 只追加、不改旧条目：结论变化就新写一条（「上一次说 X，现更正为 Y」），保持日志的可审计性。

**检索约定**：日志按时间线性排列、不做主题归类，找某个功能的全过程用关键词全文搜：

```bash
grep -rn "TikZ" docs/changelog/ | cut -c1-160
grep -rn "记忆" docs/changelog/2026-10.md | head
```

> 旧引用里的「roadmap §八」= 本节索引 + 对应月份文件；`handoff` 与 `docs/README.md` 的指向已同步。
