# 项目规划文档（Roadmap）

> 记录 RikkaHub Plus 的后续规划方向 + 逐日落地日志。
> **状态说明（2026-09-14 修订）**：本文档混合「已落地」与「规划中」两类内容——各章节标题/状态行标注实时状态，
> **不要以「均为规划」理解本篇**；最完整的逐日落地记录见「八、状态记录」。当前版本 **v1.0.25 (291) 为 Latest**（2026-10-07 11:56 UTC 转正，tag `v1.0.25`→`e58973be2`，资产 `RikkaHub.Plus-1.0.25-arm64-v8a.apk` 77,693,118 B；**仓库无草稿**）。**291 之后另有 5 个未发布提交**（`4d3375e29` imggen→mediagen 专项评估、`91677fcf4` 启停/墓碑抽象化、`13d24f544` 编辑思考过程 #1929 及其记账）⇒ **下次发版 ≥ 292**。（2026-10-08 更正：此行此前停留在 v1.0.24 (287)。）

---

## 一、借鉴橘瓣（OrangeChat）的分析

**来源**：[橘瓣 OrangeChat](https://github.com/watermio123-droid/orangechat)（watermio123-droid/orangechat）——另一个基于 RikkaHub 深度定制的 Android AI 客户端，走"AI 伴侣"路线（插件生态 + 生活感知 + 健康数据 + 记忆 + 主动消息）。还有 `liquid-glass-ui` 分支（液态玻璃主题）。

**评估原则**：结合本项目定位（私有测试、隐私优先、LLM 客户端），分析借鉴价值。

### 高价值（建议未来实现）

#### 1. QuickJS 插件系统（~~核心~~ **确定不做**）🚫

> **决策（2026-08-14）**：经架构评估后确定**不做**。理由：本 fork 已有 `eval_javascript`（AI 现场写 JS 执行，最灵活）、MCP（外部工具服务）、Skill（指令扩展）、Workspace（任意执行环境）四类机制，QuickJS 插件系统的核心增量（预打包复用 + 声明式 schema + 用户配置 + 可分发）仅对第三方生态/复杂配置有价值，对私有自用项目锦上添花、工程成本不匹配。详见「八、状态记录」。

- ~~**橘瓣实现**~~：完整插件框架 `plugin/` 目录——`PluginSandbox`（QuickJS 沙箱）、`PluginLoader`、`PluginManager`、`PluginUI`（声明式 UI + WebView）、`PluginToolProvider`（工具注册）、`PluginScanner`（发现）
- ~~**插件形态**~~：`manifest.json`（声明 tools/配置/模板）+ `main.js`（`exports.xxx` 实现工具），zip 打包导入
- ~~**沙箱内置 API**~~：同步 `fetch`（15s 超时）、`config`（用户配置）、`memoryBank`（语义检索/存取）、`TextEncoder/TextDecoder`、`btoa/atob`、`console`
- ~~**高级能力**~~：`config` 声明式配置项（string/password/number/boolean/select）、`promptTemplate` 系统提示、`hooks`（message_sent/message_received/daily_cron 事件）、声明式 UI（Compose Material 3 渲染）、`customPageWebView`
- **我们的现状**：RikkaHub **已内置 QuickJS 引擎**（`RikkaHubApp.kt` 的 `QuickJSLoader.init()`）+ 已内置 `eval_javascript` 工具（`JavascriptTool.kt`，QuickJS 沙箱 + console + destroy 防泄漏）；另有 Skill（markdown 技能）、MCP（外部服务）、Workspace（Linux 沙箱）——**四类机制已覆盖插件系统的价值点**
- ~~**价值**~~：~~用户可用 JS 脚本扩展 AI 工具，比改代码灵活~~ —— 已被 `eval_javascript` 现场执行覆盖（AI 需要时现写即可，省去打包/管理成本）
- ~~**工程量**~~：中（沙箱封装 + manifest 解析 + 工具注册 + 管理页 UI + 内置示例插件）

#### 2. 液态玻璃主题 / 三色定制

- **橘瓣实现**：`liquid-glass-ui` 分支；主题三色映射（颜色 1=全局背景 / 颜色 2=用户气泡 / 颜色 3=重点色），丝滑切换日夜主题
- **我们的现状**：RikkaHub 已有主题系统（`themeId` / `customThemes` / `dynamicColor`），但无"玻璃质感 + 三色直观定制"
- **价值**：轻量、见效快、个性化体验提升明显
- **工程量**：小（主题扩展 + 设置项）

#### 3. 轻量个性化

- **橘瓣实现**：头像框、气泡透明度、输入框换背景、字体包导入、思维链样式
- **价值**：均为小改动、体验提升直接
- **工程量**：小

### 中等价值（可选）

#### 记忆工具审批（按 action 分档）—— **✅ 已落地（`a2add734` + `49324ded`，2026-09-14）**

> 实现：`Assistant.memoryModifyApproval`（默认 true）+ **`Assistant.memoryCreateApproval`（默认 false，opt-in）**（均为 @Serializable 子对象字段，随 JSON 自动持久化）+ 纯函数 `memoryToolNeedsApproval(assistant, action)` 承载分档判定（`ChatToolFactory.kt`，工厂 wiring 调用）+ 记忆页两个开关 + 6 语言文案 + `MemoryToolUI` 补 delete 目标预览。同批（`a2add734`）修复**内容风控 403 不换 key**（实测暴露：风控 403 被误判为鉴权失败、白旋转 key 额度）。**create 审批为后续 opt-in 增补（`49324ded`）**：默认关=保持「新增直接记录」，仅担心模型记流水账/想逐条把关的用户才开；与 modify 开关**相互独立**。单测：审批判定 7 项（含 create 矩阵）+ MemoryToolsTest 改测接线，全量 421 项仅既有 MarkdownMathParse 失败；真机开关默认关、开启后持久化生效。

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

#### 记忆注入缺陷：`take(60)` 取的恰是最旧 60 条 —— **✅ 已修（`0580c6a6`，2026-09-14，真机验证）**

原缺陷：`GenerationPrompts.kt:19` 用 `memories.take(MAX_MEMORY_INJECT_COUNT)`（60 条）注入，而 `MemoryDAO` 全部查询**无 `ORDER BY`** → SQLite 按 rowid（=**插入顺序**）返回 → **超过 60 条后进 prompt 的是最旧的 60 条、最新写入的记忆反而进不去**（`MAX_MEMORY_COUNT = 200` 上限保证能堆到 200，故第 61 条起必然触发）。

**修复**：`buildMemoryPrompt` 改 `sortedByDescending { it.id }.take(60)`（id 为 `AUTOINCREMENT` 单调递增，降序即写入先后；排序收敛在此，行为不再依赖调用方列表顺序）+ `MemoryDAO` 四处查询补**显式 `ORDER BY id ASC`**（保持原 de-facto 行为、记忆页展示顺序不变，消除对未定义行序的依赖）。

**真机验证**（DB 副本补到 70 条记忆实测）：旧路径仅 `NEW-mem-1..12` 入选（最新 10 条全丢），新路径 22 条全入选。单测 4 项（含「结果不依赖入参顺序」回归护栏，撤销修复即失败）。

**后续（`69453f5e` 已实现，见同节下方与 `design-memory-scoring.md`）**：升级为 Yuihub 式**打分校准 + 预算截断**（按 `importance × 时效衰减` 排序、按**预算**纳入而非数条数），即从「取最新」升级为「取最该取」——**详细设计已落盘 `docs/design-memory-scoring.md`（2026-09-15，六项决策定案）**。
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
| P2 | ~~记忆注入取数修正 / 打分校准~~ | ✅ **均已落地**：`0580c6a6`（取最新 60 条 + DAO `ORDER BY`）+ `69453f5e`（**打分校准 + token 预算截断**，真机验证：模型实回 `importance:0.7`）。仅剩**回收站**（下一轮，见 `design-memory-scoring.md` §七） |
| P3 | 记忆提取管道 + 整理器 | 收益明确但成本中-高（每轮多一次 LLM 调用），须配开关（见 `peer-apps-benchmark.md` §四）。**2026-10-08 更新：上游已把它做出来了——PR #2130（`feat/file-based-memory`，OPEN / 0 review / 未合并）「Markdown 文件库 + 空闲后做梦整理」**。**四块拆分**：V1 纪律与安全（`[stated]`/陈旧性/硬清单 + `MemoryContentGuard` 39 行；**反注入声明与「附和/伴侣」条款已于 2026-10-09 移除**，见 2026-10-09 条）／V2 渐进披露（清单注入 + 按需 `memory_read`）／V3 做梦框架（`MemoryConsolidationScheduler` 285 行只认 memoryId + worker 接口、进度表、轮次切分/分批/保活/失败策略）／V4 Markdown 文件库本身——**前三块与文件模型无关**。**四条照搬阻塞**：①本仓 DB 已在 33 且 `Migration_27_28`/`Migration_28_29` 名字已占用；②它的迁移把旧记忆全塞一份**每次都全文注入**的 `/profile.md`（本仓实测 54 条/60K 字符 ⇒ ≈40K token/请求），并丢掉本仓的 `importance`/`public_id`/打分+预算；③67 文件里 38 个本仓已重分叉（`ChatService` +1286/−541），且它引用的 `GenerationLoop`/`dialogueMessages()`/`isContextCheckpoint` 本仓无对应物；④本仓的写入审批（`memoryModifyApproval`/`memoryCreateApproval`）会被架空（该设计把写入搬到后台 agent 且无审批）、`Enableable` 停用助手会被照样整理。**决策：不整体照搬，按 V1→V2→V3 分步、V4 等上游合并/稳定后再评**；顺带补该 PR 也缺的 per-conversation「无记忆/不整理」。**V1 + V2 已落地（2026-10-09，见下条）**；V3（做梦框架）与 V4 未做。详见 `upstream-analysis.md` 2026-10-08 节与 `docs/design-memory-discipline.md` |
| — | ~~记忆语义检索（本地 HNSW）~~ | **评估后不做**（2026-09-14）——本仓成本分析 + Yuihub 上线即删除双重印证 |
| — | QuickJS 插件系统 | **明确不做**（已有 eval_javascript / MCP / Skill / Workspace 四类机制覆盖） |
| — | 生活感知/健康/云同步 | **明确不做** |

**状态（2026-09-14 复核）**：QuickJS 插件系统**确定不做**；**毛玻璃/液态玻璃已实现**（`enableGlassTheme`/`enableBlurEffect` + haze 2.0，输入栏/列表/顶栏独立开关）；三色映射定制仍待；MCP mTLS 已实现（v2.4.9）；动态添加 Skill 已实现（v2.4.9）；**提供商多 key 已落地**（三.5，2026-09-09）；**搜索多 key 已落地**（三，`f7e673fb`）；**记忆修改审批已落地**（`a2add734`，按 action 分档）；**记忆语义检索经评估不做**。详见「八、状态记录」。

---

## 二、对话分享（导出）功能规划

> **状态：✅ 已落地（2026-08-13，`a7f253cd`）**——双入口（列表页长按「分享对话」+ 聊天内顶栏导出）+ 三形态
> （HTML 可视化 / Markdown / 图片 PNG，HTML 于 2026-08-13 后续暴露 `generateMarkdownHtml` 后一并完成）。
> 「包含全部版本」Switch = `messageNodes.flatMap`（含分支/编辑历史）。2026-09-11 图片导出改为**分页多图 + ACTION_SEND_MULTIPLE**（`b81eb26c`）。
> 与自动压缩的关系（导出=存储全量原文、不含检查点摘要）见 `docs/design-auto-compaction.md` §4.6。下方「方案」保留为设计存档。

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
- 2026-08-13：**已实现**（`a7f253cd`，详见「八、状态记录」）——三形态全部落地。本节保留为设计存档。

---

## 三、搜索服务多 key 故障转移（功能规划）

> **状态：✅ 已落地（2026-09-09，`f7e673fb`）**——`SearchServiceOptions` 加结构化 `apiKeys`（`@Transient` 基类规避层级序列化重名，16 个带 key 子类型 override）+ `withSearchKeyFailover`（`SearchTools.kt:143`，401/403/429 换 key）+ 每 key 一框 UI（复用提供商侧共享编辑器）。下方为设计存档。
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
- 2026-09-09：**已落地**（`f7e673fb`）——结构化 `apiKeys` + `withSearchKeyFailover`（`SearchTools.kt:71/121/143`）+ 每 key 一框 UI。

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
- **降级链全景**（2026-09-08 比较结论，**2026-09-09 全部落地**）：key 级（凭证冗余，`ApiKeyEntry`+enabled+失败转移 `2726b287`/`03131ffa`/`c988652c`）→ provider 级（服务冗余，`Assistant.fallbackChatModelId` fallback 模型链 `68195b91`：主模型整轮失败切备用重跑，半截不 fallback）→ #1812 半截保留草稿。完整路由抽象/OpenRouter 式不做。

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
- 2026-09-09：**全部落地并真机验证**。提交链：`2726b287`（数据模型 ApiKeyEntry + effectiveApiKeys）→ `03131ffa`（401/403 换 key）→ `c988652c`（每 key 一框编辑器）→ `68195b91`（fallback 模型链）→ `a040f947`（全局默认备用，助手 > 全局两级）→ `7c7e7a3f`（429 换 key）→ `e9d32dee`（顺序优先级 + 拖拽排序，default() 从 LRU 改 Order）→ `e902f630`（Gemini schema 补 items）→ `e0986cb2`（fallback 悬空回退全局 + 有 fallback 压缩重试预算为 2）→ `37e28306`（全局备用持久化三件套——a040f947 漏接线是四轮排查最终根因）→ `d45e2d0c`（换 key 实际轮转修复：顺序模式首个 key 恒定导致假切换 + 无上界死循环；改为 action 接收 providerSetting + 瞬态副本轮转，每 key 至多试一次）→ `01581e5c`（SSE 流内错误文本兜底：额度耗尽可换 key 不等待、限流可换可等待，覆盖中转站 200+流内 error 无状态码形态）。**验证**：fallback 链真机生效（悬空→全局→0.3s 切换）；换 key 真机生效（真实 `inference exceeds tpm/rpm limit` → `key error, switching key (1 key(s) left)` → 第二 key 成功；双 key 皆限流时等待重试恢复）。**搜索侧（三）已于同日 `f7e673fb` 一并落地**（结构化 `apiKeys` + `withSearchKeyFailover` + 每 key 一框 UI）。

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
| **#857** | 请求恢复 HTTP 代理（15 评，上游作者拒绝） | **已由本 fork 实现**（imperfect-customization-and-refinement）；并已按社区「全局代理 + 开关」轻量形态落地**两层代理**（per-provider Inherit/None/Http/Socks5 + 全局兜底，`3e3d4031`/`b45f6a9d`/`a066df81`，2026-09-13，**待真机验证**，见 `design-proxy-two-layer.md`） |
| **#1644**（open） | OCR 识图带 `reasoning_effort="none"` → 400（MIMO2.5/OpenCodeGo） | 跟踪；当前 OCR 路径若用这些模型会踩 |
| **#1632**（closed PR） | 本地 ML Kit OCR 优先 + AI fallback | 见「四、本地 OCR」 |
| **#1687**（closed） | MCP 连接支持 mTLS 客户端证书 | **已实现**（v2.4.9，P12/PEM + 密码 + 设置 UI） |
| **#1812**（closed） | 差网络下重试丢弃半截回复+重复计费 | **已由本 fork 自研方案实现**（零输出照旧重试 / 半截保留草稿落库，`20d84333`；阈值可配 `partialOutputMinChars` `e8e9d4e5`），**真机验证完成**（3/3，2026-09-06）；同机制缓解 #1820（切对话回复消失）。v2「保留并续写」已放弃（2026-09-06） |
| **#1083** | apiKey 管理策略 | **已覆盖**：提供商多 key（三.5，`2726b287` 系列）+ 搜索多 key（三，`f7e673fb`）均已实现 |
| **#540** | 对话分享勾选方向 bug | 对话分享已实现（二，`a7f253cd`）；该 bug 方向未见真机复现，如有再修 |
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
> **注（2026-09-14）**：其中多条借鉴点**已落地**——TikZ 渲染已实现（最终链 native Tectonic + mupdf SVG 桥，非本节早前构想的 workspace pdflatex/pdftocairo 方案，详见 `tikz-rendering-tectonic-postmortem.md`）；前台服务保活已落地（KeepAliveService + WifiLock `ee1c9736`）；技能触发词自动注入仍待。

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

**② R3.2 块级结构化流式渲染**——流式分支从"代码块拆分"升级为"按行块分类"（代码/标题/引用/列表/段落），样式与最终渲染对齐（HeaderStyle.fromLevel / 引用左线+斜体 / "• " 与 "N. " 前缀）。**追加式扫描每帧 O(n)，已渲染块参数不变 → Compose 智能跳过重组**。JS 模拟 18 帧验证块序列稳定。→ **我们卡顿的解药方向**（非 β/γ 小优化，是渲染架构改进：流式 chunk 按块分类 + 已渲染块跳过重组）。**（2026-08-13 已移植评估 `f9e0841b`：实测无明显改善、保留加载动画 `9c032d2f`——已不再是待办方向）**

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
- 已移植：`c897647` setJob identity、`db26bff` file_paths 收窄、`f0aa1481` 请求日志 header 脱敏、`a766399f` extractTar/parsePax + ASR 静默失败、`3652fd6` normalizeNewlines。

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
- **provider 兼容小件**（Console Go 的 `input items` 强制 id / assistant 字符串 content / 内置工具补 `name` / 仅 UrlContext 时不发空 `tools`）：面向特定上游，与我们无关；但 `10ccc7d5`「**同一消息内多 item id 唯一化（msg/rsn/img + 自增序号）**」与我们 `c309c615`（补插 reasoning 去 `rs_` id）**同域**，将来再遇重复 id 报错时其「前缀+序号」是可参考的通用化写法。

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


### 4. RikkaHub Plus（heikeyangle-code/rikkahub-plus，2★，与我们同名不同方向）

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

## 八、状态记录

- 2026-08-12：完成橘瓣分析并记录本规划（借鉴价值分级 + 优先级）。**暂不实现**，待用户决定何时推进。
- 2026-08-12：补充「对话分享（导出）」方案规划（双入口 + 三形态）。**暂不实现**。
- 2026-08-13：**实现对话分享/导出**（二 落地，提交 a7f253cd）：双入口（列表页长按「分享对话」+ 聊天内顶栏导出按钮）+ 两形态（Markdown/图片 PNG 复用现有 ChatExportSheet）+ **版本范围可选**（「包含全部版本」Switch = messageNodes.flatMap 含分支/编辑历史）。HTML 可视化形态**暂缓**（generateMarkdownHtml private 待暴露）。编译+单测通过，真机待验。
- 2026-08-12：补充「搜索服务多 key 故障转移」方案规划（含桌面版参考）。**暂不实现**。
- 2026-08-12：上游 issue 探讨（#857 代理需求确认 / OCR / MCP mTLS 等），增补「四、本地 OCR」与「五、上游 issue 关注清单」。**暂不实现**。
- 2026-08-12：记录备份互通性已知边界（原版→本 fork 通、本 fork→原版不通）+ 可选根治方向。**暂不处理**。
- 2026-08-13：调研 4 个 RikkaHub 二改版（agent/root/turbo/plus），记录可吸收点（免 key 搜索+断路器、Hardline 安全底线、技能触发词自动注入、前台保活），增补「七、RikkaHub 二改版调研」。**暂不实现**。
- 2026-08-13：深挖 rikkahub-turbo（实为 `dcs666/rikkahub-pro`，见上节更正）`perf/rendering-and-streaming` 分支（PROGRESS.md），记录 R3.2 块级流式渲染 / R2.1 折叠调优 / setJob identity 修复 / #57 Response API 同根因复现 / 增量发送全禁用结论 / 80+ bug 修复清单。**已移植 setJob identity 修复**（ConversationSession.kt，对应 c897647）；R3.2 块级流式渲染为卡顿解药方向，**待实现**。
- 2026-08-13：调研 rikkahub-math-plus（TikZ 图表渲染管线 + Lean 技能），记录 TikZ 管线架构（检测→preamble 生成→编译→三层缓存）作参考。增补「七」第 5 节。**暂不实现**。
- 2026-08-13：TikZ 图表自动渲染实现后**真机空白，用户决定暂时放弃**。git revert 4 个 TikZ commit（84152089/beffca60/d32ac40b/d4fcaa26），分支回到无 TikZ 状态。**WebView 内跑 wasm+worker 引擎（tikzjax）在 Android 虚拟 origin 下不可行**，未来重启应走 JVM 渲染或 workspace pdflatex。安全修复版发布 v2.4.5-androidrik.17。
- 2026-08-13：拉取 turbo `PROGRESS.md` 存档（docs/references/turbo-PROGRESS.md，742 行），并整理**可核查 bug 清单** docs/references/turbo-bugs-audit.md（48+ 项分四类：崩溃 17 / 数据丢失 9 / 资源防护 12 / 功能 15，含 turbo commit + 我们 fork 状态列）。**核查进度：已修 5 项安全**；**C1 coerceIn(0,-1) 崩溃已确认同款待修**（PreferencesStore.kt:387）；D4 删除附件延迟清理、D2 压缩覆盖新消息待核。**先不修复**，待用户决定优先级。
- 2026-08-13：**TikZ 中文突破**——重新启用方案 C（原版主线程 tikzjax，无 worker，headless 验证稳定），但 Android 真机仍空白（aapt 解压 .gz 坑 + core.dump 流式读取坑，均已修）。转而**死磕中文**：调研 Tectonic wasm（XeTeX + xdvipdfmx），发现 **`\font"[simhei.ttf]"` 方括号语法直接打开注入字体文件、绕过 fontconfig、免 fontspec/xeCJK**——glyphtex-engine@0.1.0（bundle 13.4MB + wasm 3.5MB + simhei 9.7MB）实测含中文 TikZ 编译 **status=spotless，PDF 5265 bytes**。完整链路：中文 TikZ → Tectonic XeTeX → PDF → Android PdfRenderer 位图 / MuPDF fz_new_svg_device SVG。**待实现 Android 集成**。
- 2026-08-13：**PdfRenderer 位图版实现完成**——Tectonic Android 集成闭环（编译通过 + APK 资产打包验证 OK）。①新增 `utils/PdfToBitmap`（Android 内置 PdfRenderer 渲染 PDF 首页→Bitmap，targetWidth 对齐屏幕物理宽）；②`assets/html/` 全套 Tectonic 资产（tecton.html 编译页 + engine.js/imports.js + pako + wasm 3.5MB + bundle 13.4MB + simhei.ttf 9.7MB）；③`TikzJax.kt` 重写：WebView 加载 `tecton.html#base64url` → JS 解 bundle + 注入字体 + 编译 → `onPdf(base64)` → PdfToBitmap → ImageBitmap；修复 @JavascriptInterface 线程（state 更新切主线程）。**MuPDF SVG 路线确认不可行**（.so 预编译无 SvgDevice JNI 注册且不可重编、纯 Java SVG Device 工作量大）→ 放弃，留作后续矢量优化。**真机待测**。
- 2026-08-14：**TikZ 渲染功能暂时放弃**——PdfRenderer 位图版（Tectonic 中文）实现完成并发布草稿 .21，但 Android 真机始终「JS 未执行」。排查三轮：①headless 验证 JS 链路**正常**（中文 TikZ 编译 spotless，PDF 2225 bytes）→ 问题在 Android 集成层；②根因假设 MIME（WebViewAssetLoader 的 AssetsPathHandler 对 .js/.wasm 返回 null）→ appassets 改手动拦截+自定义 MIME，未解决；③根因假设 module（`<script type="module">`+import 在 WebView 拦截响应下静默失败，mermaid 普通 script 正常）→ engine.js/imports.js/tecton.html 全改普通 script+全局变量，本地验证通过、**真机仍「JS 未执行」**（连 script-started 兜底回调都没到）。用户决定**暂时放弃**。**保留记录**：headless 已证 Tectonic 中文可行（方括号字体语法免 fontspec）；根因疑为 Android WebView 对拦截响应下 module/wasm/大文件 fetch 的综合限制；未来重启可选 JVM 渲染（PdfBox/SVG）或 workspace pdflatex。git revert d95b5085..HEAD（12 个 TikZ 提交：方案 C jsTeX 5 + Tectonic 6 + bump 2）回到无 TikZ 状态。
- 2026-08-14：**上游同步至 2.4.8**（master ff 更新，imperfect-customization-and-refinement 未动）。上游 `8824e0e8 fix: preserve serializable classes in release builds` 修复「添加某些搜索商时崩溃」——根因是 **R8 混淆破坏 `@Serializable` 类**：kotlinx.serialization sealed class（BuiltInTools / SearchServiceOptions 等 18 个搜索商类型）运行时经 **Kotlin 反射**实例化（多态 type 字符串→反射找类），R8 在 release 构建中重命名/移除**未被引用的类**后，反序列化反射找不到类 → 崩溃（"某些"= 仅被 R8 破坏的类）。上游修复：`-keep @kotlinx.serialization.Serializable class * { *; }`（替代 2.4.7 宽泛的"禁用重命名混淆"）。**特性分支潜在风险（暂不处理）**：我方多态 sealed class（ProviderProxy、SettingsJsonMigrator 等，见经验 128）在 release 混淆下可能同坑；未来若出现反序列化崩溃（尤其设置/备份/搜索相关），优先给 `app/src/main/keepRules/rikkahub.keep` 加同款 keep 规则。
- 2026-08-14：**修复 turbo 清单 C1/D4/D2 三项**（v2.4.5-androidrik.22 草稿）：① C1 删光搜索服务后设置保存崩溃——`SettingsStore.sanitizeSearchServiceSelected` 空列表守卫（PreferencesStore.kt，原 387 行）；② D4 历史页滑动删除附件立即物理删、撤销后附件丢失——`deleteConversation(deleteFiles=false)` + 撤销窗口结束 `deleteConversationFiles` 清理（ConversationRepository/HistoryVM/HistoryPage）；③ D2 压缩期间新消息被整体覆盖——`mergeCompressedConversation` 合并最新状态保留追加节点（ChatService.compressConversation）。新增 8 个单测（PreferencesStoreTest 4 + ChatServiceTest 4），全量 `gradlew test` 绿。**真机待验**（删光搜索服务保存设置 / 历史页滑动删除→撤销恢复含附件对话 / 压缩期间连发消息）。
- 2026-08-14：**turbo 清单 48 项全量核查定论**（turbo-bugs-audit.md）：确认同款待修 32 项 / 确认不存在 6 项（C9 迁移链完整、D8 eventsRoutes 无条件注册、F5 mermaid 导出有错误反馈、F11 inlineData 纯 base64、F13 SAF canonical 校验、F15 固定文件名无 modelName）/ 待定 5 项（C17 编译期、D1 倾向有反馈、D9 容错设计取舍、F6/F7/F9 细节待核）。
- 2026-08-14：**第二批修复 P0 崩溃/数据 8 项**（v2.4.5-androidrik.23 草稿）：C2 base64 容错（StringUtils try-catch 降级空串）、C5 JsonExpression SOE 兜底（MAX_EXPRESSION_LENGTH=512 + try-catch）、C10 TTS 悬浮窗无权限守卫（FloatingWindow canDrawOverlays 静默降级）、D5 SettingsStore.update(fn) Mutex 串行化（未动 settingsFlowRaw）、D6/D7 分享 EXTRA_STREAM 兼容 Parcelable Uri + clipData 兜底（RouteActivity.readShareStreamUri）、F8 WebDav 路径段 percent-encode（encodeWebDavPathSegment + 3 单测）、F10 WebServerManager.restart 同步停止（同一协程 stopServer 后再 start）。新增 9 个单测（StringUtils 2 + WebDavClient 3 + JsonExpression 4）。app/common 模块全量单测绿（workspace 模块 /bin/sh 为 Windows 既有环境失败）。**真机待验**（TTS 朗读无悬浮窗权限不崩溃 / 从相册多图分享到聊天 / WebDAV 备份含空格文件名 / 设置页余额表达式粘贴超长文本）。
- 2026-08-14：**第三批修复 P0 剩余 + P1 资源防护 + P2 共 22 项**（v2.4.5-androidrik.24 草稿）：① 深度类（C6 parseErrorDetail depth32 / C7 collectAllParts depth16 / C14 mergeJsonObjects depth32 / C15 JsonTree depth12 / C5 已修）；② SOE 兜底（C11 PropertyEditor/UIMessagePart/GenerationHandler 三处 catch Throwable）；③ 资源上限（C12 导入 5MB / C13 base64 20MB+4K 采样 / C16 TextArea 200K / R1 文档 200K / R2 记忆 200 条+注入 60 条 100K / R4 fetch 5MB / R5 Bing 2MB 显式 / R6 翻译 200K / R7 GitHub 4MB / R8+R9 用户消息 200K / R11 TTS 8MB / R12 rootfs 2MB）；④ 正确性（C4 BitmapComposer 异常不挂起+视图清理 / D3 工具结果 base64 递归转换 / F12 apiEndpoint URL 去重 / F14 MCP 名校验放行 -_）；⑤ R3 QuickJS destroy。**C8 核查后确认不存在**（HighlightEngine 已有 RuntimeException 降级）；**D9 按用户指示搁置**。新增 8 个单测（ApiEndpoint 3 + ErrorParser 2 + GenerationPrompts 3）。**真机待验**（超大文档/超长消息/多记忆助手不崩、GitHub 拉技能超 4MB 截断、workspace 写超 2MB 拒绝、TTS 长音频不 OOM、MCP 服务器名含 - _ 可保存）。
- 2026-08-14：**TikZ 图表渲染重启——纯 Java/SVG 路线（v2.4.5-androidrik.25 草稿，MVP）**。弃用旧 WebView+wasm（真机「JS 未执行」不可行），改走**纯 Kotlin 解析器 → SVG 字符串 → AndroidSVG（com.caverock:androidsvg:1.4，已显式引入）→ Picture → Compose Canvas 矢量绘制**。MVP 覆盖 tikzpicture 子集：`\node`（矩形/圆/椭圆+文本）、`\draw/\path`（折线/箭头/圆/矩形）、坐标（直角/极坐标/命名节点/锚点）、样式（颜色名/#hex/draw/fill/thick/dashed/->）、中文文本、深色模式（默认黑→白）。双触发形态复用历史：```tikz/tikzcd/tikzpicture/tikztiming 围栏（HighlightCodeBlock）+ $$ 内嵌 isDiagramLatex（MathBlock，\begin{tikzpicture}/\begin{tikzcd}/\begin{axis}/\xymatrix/\begin{CD}）。**导出 HTML 内嵌 <svg> 矢量**（占位符绕过 flexmark 转义，renderMarkdownWithTikz）。设置项 DisplaySetting.enableDiagramRendering（默认开）+ 6 语言 i18n + GenerationHandler 提示。新增 TikzRendererTest 10 单测（解析往返）。**tikzcd 交换图下一版**（extractBody 暂抛 not supported）。**真机待验**（tikzpicture 图表矢量渲染/缩放、深色模式、导出 HTML 内嵌 SVG、```tikz 围栏与 $$ 内嵌双触发）。
- 2026-08-14：**TikZ tikzcd 交换图支持（v2.4.5-androidrik.26 草稿）**：`parseTikzcd` 矩阵布局（& 分列 / \\ 分行，colSep=2/rowSep=1.5）+ `\arrow[r/l/u/d]`（含对角 dr/dl/ur/ul、跳列 rr）+ 标签 `\arrow[r,"f"]`（ArrowSpec 解析 dir/label/prime）。render() 分流：含 \begin{tikzcd} 走 parseTikzcd，否则走 parseCommands。复用 renderSvg（节点=单元格、箭头=DrawElement、标签=NodeElement draw=false）。TikzRendererTest 增至 13 单测（新增 tikzcd 简单图/双行带标签/对角箭头）。**真机待验**（tikzcd 交换图渲染、箭头标签、对角箭头）。
- 2026-08-14：**TikZ AndroidSVG 兼容性修复（v2.4.5-androidrik.27 草稿）**——真机反馈「只有图形+颜色能渲染，箭头/文本/tikzcd 挂、导出为源码」。根因：AndroidSVG 1.4 不支持 `<marker>`（箭头）、`dominant-baseline`/`dy="em"`（文本垂直居中）、`fill="context-stroke"`（marker 继承色）。修复：① 箭头改**端点实心三角形 path**（arrowHead，沿方向角张开，fill=stroke 色）；② 文本去 dy/dominant-baseline，改 `y=cy+5` 直接定位 + 显式 `fill`（深色模式随 replace black→white 翻转）；③ `fill=red!30` 解析（substringBefore('!')）；④ hasArrow 逻辑（filterIsInstance 替代 as? null 比较）。**导出**：renderMarkdownWithTikz 扩展处理 `$$...$$` 内嵌图表（DIAGRAM_LATEX_PATTERNS 检测 \begin{tikzpicture}/\begin{tikzcd}/\begin{axis}/\xymatrix/\begin{CD}），围栏正则放宽 `[^\n]*\n`。**真机待验**（箭头三角形、节点中文文本、tikzcd、HTML 导出内嵌 SVG）。
- 2026-08-14：**TikZ `\node` 缺省 at 坐标兜底（v2.4.5-androidrik.28 草稿）**——真机定位 `\node {Hi}` / `\node[draw] {Hi}` 失败：parseNode 正则坐标组 `\(([^)]+)\)` 为必需，缺省 at 时匹配失败。修复：坐标组改可选 `(?:at\s+\(([^)]+)\))?`，缺省用 `Point(0,0)` 兜底（collectNodeName 同步）。TikzRendererTest 增至 17（新增无 at 节点/中文无 at/命名节点连线 4 个）。**待办（P1）**：`\draw (0,0) node {...}` path 内 node 操作，机制不同另实现，不混修。**真机待验**（缺省坐标节点、命名节点连线）。
- 2026-08-14：**TikZ path 内 node 操作（v2.4.5-androidrik.29 草稿，路线 A）**——`\draw (0,0) node {...}` 支持。核心规则：node 位置 = 路径累加当前点。抽共享 `scanPath` token 扫描器（顶层 \node 与 path node 复用 NodeElement 渲染）。parseDraw 改返回 List<Element>（折线 + path node）；第一遍 collectDrawNodeNames 收集 path node name（circle/rectangle 跳过，避免半径被误当坐标）。三个坑：只有 node 无线段不输出 path（points<2）、无坐标兜底 (0,0)、命名节点注册供 (A)/(A.east) 引用。选项隔离：\draw[->] 的 -> 属路径，node[...] 选项属节点。TikzRendererTest 增至 21（新增 path node 4 个）。**待办（P2）**：placement 锚点 node[above/below/left/right]、to[bend] 曲线。**真机待验**（path node 四种形态）。
- 2026-08-14：**TikZ `to[bend]` 曲线（v2.4.5-androidrik.30 草稿）**——scanPath 识别 `to[bend left/right=angle]`，renderDraw 用二次贝塞尔 `Q`（bendControl：中点 + 垂直偏移，offset = len/2·tan(angle/2)）。angle 正 left / 负 right；无 [bend] 的 to 仍直线。TikzRendererTest 增至 24（新增 bend 曲线 3 个）。**待办（P2）**：placement 锚点 node[above/below/left/right]。**真机待验**（bend 曲线、无 bend 直线）。
- 2026-08-14：**TikZ 相对坐标/箭头/曲线/形状四大增强（v2.4.5-androidrik.31 草稿）**——路径表示升级为段序列 Seg（MoveTo/LineTo/QuadTo/CubicTo），DrawElement 改 segs+circleCenter/rectStart。⑤ 相对坐标 ++（更新）/＋（不更新，独立子路径 MoveTo 锚点）；① 箭头弃 marker（AndroidSVG 不支持），手动几何 polygon（tip 三角/stealth 凹底/latex 空心）+ 曲线切线方向（Q: p1-c，C: p1-c2）；④ `.. controls (a) and (b) ..` 三次贝塞尔 C（to[bend] 修空格）；③ diamond/star 形状（polygon）+ rounded corners（stroke-linejoin=round / rect rx=8）。TikzRendererTest 增至 32。**真机待验**（四方向全部）。
- 2026-08-14：**TikZ rounded corners 折线几何倒角（v2.4.5-androidrik.32 草稿）**——S4 真机挂：stroke-linejoin 近似在 AndroidSVG 未走通。改**几何倒角**：每个内角沿两边各缩进 r，用二次贝塞尔连接（控制点=角点本身，方向自然衔接）；r 默认 5px，`=4pt` 解析去单位；边短于 2r 时 r=min(r,边长/2)。rectangle rx=8 保持不变。TikzRendererTest 32/32。**真机待验**（折线圆角）。
- 2026-08-14：**TikZ rounded 单位/视觉修正（.33-.36 系列草稿）**：.33 真机挂（几何层 rawPts<3 直走 renderPlainD + 单位错误）→ 半径先经 sx/sy 转 px 再算；.34 stroke-width 数值 fmt 规范化（AndroidSVG 对 "X.0" 兼容怪癖）；.35 圆角 review 三项；.36 裸 rounded corners 半径随图形对角线 3% 且 ≥2.5×线宽（高密度屏可见）。**真机 S4 仍挂**。
- 2026-08-14：**TikZ 真机渲染根因定位与修复（.37-.39）**：.37 显式 width/height + Q→C 等价转（AndroidSVG 对 Q 兼容问题）+ scale 防 0；.38 **renderToPicture→renderToCanvas**（AndroidSVG FAQ：硬件加速 Canvas 不支持 drawPicture）+ 诊断日志（TikzJax tag），真机 logcat 定位「getBytes() null 是 svg=null 传 null 的伴生噪音」，主因是 **TikzRenderer 抛「No renderable」/「Unknown coordinate」**；.39 **未定义命名坐标原点兜底不崩溃**（parseCoordinatePoint 不再 throw）+ 完整 code 100 字符分段日志。**TikZ 显示路径（renderToCanvas）真机可用**。
- 2026-08-14：**TikZ 输入格式根因（.40-.41）**：真机消息实为「$$ 开头无闭合」——IntelliJ markdown **不识别无闭合 $$ 为 BLOCK_MATH**（MATH count=0，本地实测）→ 整段按普通文本显示（看似源码，实际未走 TikzJax）。修复：**系统提示词改原始字符串 + 明确要求闭合 $$...$$**（顺带修复 Kotlin 普通字符串 `$$` 被转义成单个 `$` 的隐藏 bug）。.41 正式发布（Latest）。**之后用户输入规范闭合 $$ 即可渲染**。
- 2026-08-14：**TikZ 四项待办落地（v2.4.5-androidrik.42 正式发布）**：① `-- cycle` 闭合路径（ClosePath 段 + 普通 Z + **rounded corners 环形倒角**含起点角 + 多子路径各闭各的）；② `arc` 圆弧（(start:end:radius) 与 [start angle=..] 两种语法，SVG A 命令，TikZ 逆时针 sweep=0）；③ `\fill`（纯填充无描边）/`\filldraw`（描边+填充），parseStyle 支持 `draw=color`，修 `filldraw` 被 `fill` 前缀抢匹配的正则 bug；④ `grid` 网格（step 统一步长含端点）。TikzRendererTest 增至 58。
- 2026-08-14：**TikZ plot 函数图像（v2.4.5-androidrik.43 正式发布，路线 2）**：MathExpr 递归下降求值器（`^` 右结合 > 一元负 > `*/` > `+-`；x/pi/e + sin/cos/tan/ln/log/sqrt/abs/exp/asin/acos；非法输入返回 NaN 不抛）→ `plot (\x, {expr})` / `plot[domain=a:b, samples=N]` / 参数曲线 `({cos(\x)*2},{sin(\x)*2})`，**NaN/Inf 显式断开路径** + fmt 规范化。踩坑：`cos(...)` 函数括号使 indexOf(')') 提前截断 → 平衡括号扫描；`\x` 替换为 x。TikzRendererTest 增至 64 + MathExprTest 9。
- 2026-08-14：**节点内嵌数学子集尝试（.44-.47 系列，已回退）**：.44 MathText 排版（`^`/`_` 上下标、`\frac` 分数横线、`\sqrt` 根号折线、`\sum` 上下限，多 text 堆叠不依赖 tspan）+ parseNode 平衡花括号提取文本；.45 间距修正（分数/∑ 上下限/根号形状/宽度估算）；.46 修 `\(...\)`（IntelliJ inline math 要求 `$` 后紧跟内容，含空格不识别）+ latex 代码围栏走 jlatexmath；.47 修 `$...$` 含空格不识别（含 LaTeX 命令的 `$..$` trim，`$5$` 价格安全）+ LatexText fallback 剥定界符。**真机反馈 MathText 排版混乱（分数贴线/∑ 重叠/根号形状）且 inline LaTeX 需求与 TikZ 渲染器重叠，用户决定整体回退**。
- 2026-08-14：**回退到 .43 基线（v2.4.5-androidrik.48 正式发布）**——用 **git revert** 逐个撤销 .44-.47（8 个反向 commit，历史完整保留可追溯），代码与 .43 逐字节一致（diff 干净）。**之后构建版只发布 arm64-v8a 单架构 APK**。数学渲染恢复原 jlatexmath 路径（LatexText）。
- 2026-08-14：**QuickJS / 液体玻璃方向评估（暂不实现）**——用户提「quick js 和液体玻璃主题」：
  - **QuickJS**：基础设施现成（RikkaHubApp QuickJSLoader.init()），但用途需界定——「AI 执行 JS 脚本（code-interpreter 风格，无 DOM 安全环境）」可行且有价值；**替代 WebView 渲染 Mermaid/tikzjax 不可行**（需 DOM）；QuickJS 无 DOM，与 math 渲染无关。见「一、1 QuickJS 插件系统（P1）」。
  - **液体玻璃**：Compose 可行（Modifier.blur RenderEffect Android 12+ / Haze 库），但 **blur 重操作 → 消息列表大面积模糊掉帧**，建议仅局部（底部输入栏/侧滑面板）+ 低端机验证 + Android<12 半透明 fallback；与 M3 动态色叠加。见「一、2 液态玻璃主题（P2）」。
  - 两者**暂不实现**，待用户决定推进时机。
- 2026-08-14：**v2.4.6 独立版本体系 + 上游选择性跟进**——以 .48（.43 基线）为底，versionName 切为 `2.4.6`（versionCode 213），正式发布（Latest）。**自此上游选择性跟进**：`master` 仍可同步上游（ff-only 镜像机制保留），但上游修复/功能**不再全部跟进**，仅按需人工评估有价值的部分移植。README 分支结构/上游说明、roadmap 上游跟踪同步更新。此后「五、上游 issue 关注清单」转为**参考性质**（仅在有价值的修复/功能时按需吸收）。
- 2026-08-14：**v2.4.7 毛玻璃升级对齐上游**（versionCode 214）：haze 2.0.0-alpha03→alpha05（`hazeEffect`/`blurEffect` 迁移为 `hazeBlur` + `HazeBlurStyle.Material3` blurRadius 12dp，依赖 `haze-blur-material3`）。fork 原已有毛玻璃基础版（alpha03 + 设置项 + 背景/列表/预览参与模糊，等价上游两修复），本次升级对齐 master 当前 API。**已正式发布**。
- 2026-08-14：**v2.4.8 吸收上游 2.4.9 四项**（versionCode 215）：① jlatexmath R8 整类 keep（原仅 keep Cyrillic/Greek 注册，release 下数学公式失效风险）；② OpenAI 流式 null 容错（choices/choices[0]/delta/message/tool_calls/function 显式 null 不再崩溃，as? 容错 + 无效 chunk 跳过——fork 无 StreamDecoder 文件，改在 ChatCompletionsAPI 内对应处）；③ ImgGenVM 图片生成/编辑用 findProvider 结果（修 Provider Override 未生效）；④ ReasoningLevel 新增 MAX(32_000,"max") + OpenAI effort 映射 + nvidia deepseek-v4 分支 + ReasoningPicker 3 处 + 6 语言 reasoning_max。**已正式发布**（tag 2.4.8 曾被上游同名 tag 抢占，已删 tag 重打指向本分支 commit 修复）。
- 2026-08-14：**v2.4.8.1 数学渲染路线确认**（versionCode 216）：**数学公式确定走上游 jlatexmath（LatexText）**，放弃节点内置（MathText）方案——.44-.47 尝试已 revert，本次明确不再做；代码无 MathText 残留。发布为 2.4.8.1（无功能代码改动，路线确认版）。
- 2026-08-14：**中转站「no effective pricing」排查定论**——用户报 gpt-5.6 通过中状站（<relay-host>，new-api 类）调用报 `Current group has no effective pricing for model gpt-5.6`，上游原版同配置可用。**全量对比** fork 与上游（原版 2.4.5 / master 2.4.9）：URL 拼接（apiEndpoint 无 /v1 重复）、请求体字段（model/stream_options/modalities/reasoning 各分支）、鉴权、Refer 头、默认推理级别（AUTO）**全部一致**。**定论：客户端无差异，根因在中转站侧（gpt-5.6 被撤下/key 组未配定价）**。README 增补「已知问题与排查记录」节。**无代码改动**。

- 2026-08-14：**QuickJS 插件系统确定不做**——用户问「在 MCP 和 Skill 等的基础上，还有必要吗」，经评估确认**不做**：① 已有 `eval_javascript`（AI 现场写 JS 执行，最灵活）覆盖「任意 JS 逻辑」；② MCP 覆盖外部工具服务、Skill 覆盖指令扩展、Workspace 覆盖任意执行环境；③ QuickJS 插件系统增量（预打包复用 + 声明式 schema + 用户配置 + 可分发）只对第三方生态/复杂配置有价值，自用场景工程成本不匹配。README 后续规划移除该条目，roadmap「一、1」与优先级表标注明确不做。**无代码改动**。

- 2026-08-16：**提供商多 key 故障转移评估（待立项）**——核查确认多 key 轮换已存在（KeyRoulette 已接入全部 Provider，apiKey 支持分隔多 key），缺失败转移（401/403/429 不换 key）。用户方案：每 key 一输入框（`apiKeys: List`，旧 `apiKey` 保留兼容），轮换=切换 key 配置，失败转移直接操作列表。用户当前未配多 key（迁移成本零）。难度中（UI 多输入框 + 列表模型 + 流式重试）。详见「三.5」。**暂不实现，待用户安排时间**。

- 2026-08-16：**豆包搜索（#1721）落地完成（v2.4.10.11）**——DoubaoSearchService（global/web 双模式）：GLOBAL=全球搜索精选文档（上限 20、带图控制、适合综合摘要）；CUSTOM=常规 Web 搜索（上限 50、可关查询重写、默认）。经历：2.4.10.7 首上 → 误判崩溃回滚 → 真凶定位 R8 反射（2.4.10.9 修）→ 重上（2.4.10.10）→ 补漏显式 when Doubao 分支（2.4.10.11 正式）。
- 2026-08-17：**下一步目标——New API 真实余额计算（待实现）**。现状：`BalanceOption`（ai/src/main/java/app/androidrik/ai/provider/ProviderSetting.kt:26）仅 `apiPath` 单接口 + `resultPath` 单 JSON 节点取值（默认 `/credits` + `data.total_usage`），**无法做减法**——配置 `/dashboard/billing/usage` + `total_usage` 显示的是"累计已使用总额"，非真实余额。需求：真实余额 = 总充值（`quota`，New API `/api/user/self`）− 已使用（`total_usage`，`/dashboard/billing/usage`）。方案方向（待定）：①**扩展 BalanceOption 模型**——多接口/多节点 + 算术表达式（如 `balance = quota - total_usage`，配置驱动通用，兼容 one-api/new-api 系）；②**New API 专属计算**——识别 provider 特征走专用余额逻辑。现有 BalanceOption 用例在 DefaultProviders.kt:106-156。**暂不实现，先记录目标**。

- 2026-09-06：**未实现项全量盘点**（完整清单见 `docs/handoff-2026-09-04.md` 第七节）。上游 2.4.14/2.4.16 未吸收批次（Gemini 工具共存须与 2.4.16 修复捆绑、TTS 合成自动重试、MCP client-only/cloudflare OAuth/通知图标/qwen 音色等 1 行小件、终端标签关闭确认等 C 类按需）；peer-apps 借鉴落地顺序 ②-⑥（ContextBudgetPlanner、LoopGuard+forget、WifiLock 补强、世界书 seed 三重迁移、MCP host 按需）；自有规划仍开放（提供商/搜索多 key 失败转移待立项、R3.2 块级流式渲染待实现、本地 OCR 暂缓）；issue 关注新增 #1644（OCR reasoning_effort=none 400）跟踪。已闭环：终端 ①④ 落地（`fbfcde5e`/`958f011e`）、②⑥ 放弃；#1812 实现待真机测试、阈值可配置化完成、v2 续写放弃；turbo 尾巴仅剩 F6 待定。
- 2026-09-07：**TikZ 渲染管线重构——dvisvgm 移除，mupdf SVG 接管（1138120d + cb2bc18a，真机 12 测试全绿）**。tectonic PDF（自动内嵌字体）→ 自写 JNI 桥调 mupdf 1.26.8 `fz_new_svg_device_with_id`（FZ_SVG_TEXT_AS_TEXT）→ `<text>` 保留型 SVG。文字三大根因修复：rmfamily 重定义对 node 无效（改 `tikzset{every node/.style={font=<zhfont>}}`）；DroidSansFallback 无拉丁（换 Noto Sans SC，droidsans.ttf 槽位保持）；wrapDocument 恒注入 dvisvgm 驱动致 PDF 图形丢（按 format 注入）。移除 libdvisvgm、svgconvert 进程、CM pfb/fontmap 资产。JNI 坑：SVG UTF-8 需转 Modified UTF-8 才能 NewStringUTF。详见 [[tikz-rendering-direction]] 记忆与 tikz-rendering-tectonic-postmortem.md 待补。
- 2026-09-08：**TikZ 文字渲染定稿 TEXT_AS_PATH + 上游 2.5.0 同步**。①TikZ：文字最终改 mupdf `FZ_SVG_TEXT_AS_TEXT→FZ_SVG_TEXT_AS_PATH`（`1c09d5cd`）——TEXT 态输出 `<text>` 元素、AndroidSVG 渲不了中文 glyph；PATH 态由 mupdf 从 PDF 内嵌字形直接提轮廓 `<path>`，中英文矢量渲染与字体查找无关（修正 2026-09-07 条目仍是 TEXT_AS_TEXT 的早期描述）。TikzSvgCache CACHE_VERSION 随链升 v4。**dvisvgm 矢量链正式废弃**（文字悬空/字体映射遗留，真相见 tikz postmortem 8.4.9）。②上游：master ff-only 同步至 2.5.0（`12ee935e`），16 提交评估并入综合文档 `docs/upstream-analysis.md`（原 2.4.11/2.4.14/2.4.16/2.5.0 四份分析已合并删除）；重点待吸收：终端 Tab 标题 `104040df`、工具审批修复 `55506496`、Exa 新鲜度 `a8f8c3a1`；消息队列/语音/ASR 双向流大功能块暂缓。
- 2026-09-08 晚：**TikZ #hex 全链路收官 + 缓存持久化确认**。①**完整 documentclass 保护（`5e2c3858`）**：wrapDocument 先判 documentclass→完整文档只做弧度归一化、不碰 #hex（用户自管 preamble，避免 Undefined color）；裸片段才做 #hex 替换+定义注入。②**#hex 扩展（`72d5ee90`）**：3 位 `#RGB` 按位展开（`#f80`→`ff8800`）、8 位 alpha 取前 6、**仅 `[...]` 选项上下文替换**（避免 node 文本 #hex 误伤）；13 单测全绿。③**渲染持久化确认**：结果落 `files/tikz_cache/`（app 私有），键 `sha256("v4:"+code)` 内容寻址，CACHE_VERSION v4 + 目录标记自动清空；**改渲染输出必同步递增 CACHE_VERSION**（5b063217 漏递增→旧 v3 键命中坏 SVG 的教训）。④**待办——裁剪 A4 空白**：当前 SVG 为 A4 整页图居中留白，借鉴 math-plus `standalone[border]`/preview tightpage 裁到图 bbox；**已定位坑：bundle 补文件须放根路径**（preview.sty 放 `tex/latex/preview/` 子目录 kpse 找不到→`preview.sty not found`，用户已回退到 b9a7f620 基线正常态；修正版裁剪（preview 根路径）可另开一轮，或改为补 `standalone.cls` 走 math-plus 同款）。- 2026-09-09：**提供商多 key + fallback 模型链全部落地（v2.4.10.23/247 草稿，真机验证）**。①多 key：ApiKeyEntry 结构化存储（每 key 一框 + 启停 + 拖拽排序=优先级），失败转移覆盖 401/403/429/余额耗尽/TPM-RPM 限流（后者为 SSE 流内错误文本兜底，中转站 200+流内 error 无状态码形态）；仅零输出失败换 key（#1812 半截保留草稿不换）。②fallback 模型链：助手级 > 全局级两级配置，主模型整轮失败切备用重跑；悬空引用自动回退全局；有 fallback 时主模型重试预算压缩为 2 快速切换。③排查三连教训：重试预算长（100s+）易误判"没 fallback"；悬空引用遮蔽全局（`?:` 走不到）；**Settings 新字段必须补 PreferencesStore 三件套**（key/读/写，漏了 UI 能选重启即丢）；换 key 轮转须真实前进（顺序模式恒返首个 key=假切换 + 死循环，action 改收 providerSetting 轮转瞬态副本修复）。详见三.5 状态与 [[provider-multikey-design]]/[[settings-persistence-trio]] 记忆。发布：草稿资产与更新日志已重建替换（顺序优先级语义修正），待转正。
- 2026-09-09 复核：**待办清单核实（git log + `merge-base --is-ancestor`）**——以下此前列待办的项已落地，从待办移除：**New API 余额计算**（`5da49254` 改 `/api/usage/token` 单接口，v2.4.10.12 终版；后续 `1f4ebaae` 非对象防护、`1b9dd092` 移除硅基流动余额查询）；**TikZ preview 裁剪**（`89233a77` preview.sty 根路径修正版 + `9c48e02a` v2.4.10.22，A4 整页→图 bbox，CACHE_VERSION v5）；**R3.2 块级流式渲染**（`f9e0841b` 2026-08-13 移植 + `9c032d2f` 实测无明显改善保留动画——2026-09-06 盘点"待实现"过时）。后续状态（2026-09-09）：**Gemini 捆绑已吸收**（`9851d037`→`366a8de7` 单一 tools 数组、`f7869e35`→`7c77c483` 混合模式流式重实现，中转站代理 Gemini 的 array items 缺失经 `4a62e43b`/`e4bb4624` 补 OpenAI 兼容路径归一化+畸形 type 安全判定，真机验证通过）。仍未吸收（逐一核实）：IME `fa0305ba`+`daae3749`、通知图标 `aab5026e`、测试连接本地化 `4fcb590a`、cloudflare OAuth（已吸收 `a02741e9`）、终端标签确认 `bce78766`（已吸收 09-09：TTS `5403bc96`+`2f05019b`→`ec398672`、Exa `a8f8c3a1`→`446f5a82`、qwen 音色 `e9b98a4b`→`89b00455`、统计页 JSON 兜底 `77a58c2c`→`4cbdb104`、edited_files 安全 cast `dee88dca`、DeepSeek V4.1 Flash `ab07ac1f`、工具执行中可开详情 `6e0aa7d4`；`05a85a09` ask_user 自定义文本 **已自有实现不吸收**——AskUserTool 描述明确「UI 始终提供自由文本输入、不要加回退选项」，单/多选均可自定义文本）；自有待办：搜索侧多 key 结构化/失败转移（轮换已有 `955c912d`）、终端③⑤⑦⑧、LoopGuard+forget、ContextBudgetPlanner、世界书 seed ⑤。详见交接文档第七节复核注。
- 2026-09-09 晚：**消息发送队列已落地（自研，`3a911f1e` + `6680668d`）**——上游 2.5.0 `66de8b30` 的语义自研适配：生成期间发送不再 cancel 当前轮次，入队等当前轮（含待审批工具）结束后自动逐条派发；失败暂停可恢复；队列项编辑/撤销；附件引用清理（DAO `hasFileReference` + `localFileUrls`，保守不误删）。fork 适配点：保活走独立 KeepAliveService 不引上游 LAZY+前台；发送/停止语义（生成中有输入即发送入队、空输入才停止）补在 `6680668d`。
- **ChatToolFactory（2.5.0 `097cdb90`）维持不吸收**——纯结构重组零功能收益，fork ChatService/GenerationHandler 深度定制（审批串行化、队列、多 key/fallback）碰撞成本高；触发条件=出现第二个工具集装配调用方（如 MCP host / web 服务端生成）。
- 2026-09-10：**语音/ASR 双向流已落地（吸收 2.5.0 大块 `a621e277` 语音模式 + `580e05d6` ASR 双向流）**——服务端 VAD 语音轮闭环：捕捉→入队→等回复→TTS 朗读（pauseCapture 独占麦克风）→继续聆听。依赖链：队列 reply 机制（`1c499b79`）→ ASR 双向流 speech 模块（`d2789207` Volcengine 双向 VAD + VolcengineASRProtocol + ASRVoiceTurn；`bf0cf85a` DashScope/OpenAIRealtime voiceTurn 事件）→语音模式 app 侧（`a80fbd47` VoiceSessionController/VoiceMode/VoiceModeRow/接线 + 23 文案三语言）→单测（`27dc690d` MessageQueueTest 13 项 + VoiceSessionControllerTest 294 行）。支持服务端 VAD 的 ASR：OpenAIRealtime/DashScope/Volcengine。
- 2026-09-10：**工作区（rootfs）改进评估——值得动工 4 项、不做 5 项**（代码事实：rootfs = Ubuntu 24.04 base 按工作区下载进 `linux/`；RootfsPatcher 打 DNS/hosts/hostname/LANG/Android 组 ID/tmp；proot 执行 `-r linux/ -b filesDir:/workspace` + link2symlink/root-id/kill-on-exit；AI 工具 workspace_read/write/edit_file/shell，路径沙箱 + 2MB 读写上限，shell 默认 30s/最大 600s、输出截断 128KB）。**值得做**：① 自托管预装 rootfs（GitHub release 资产：base + python3/git/curl/jq）——一次解决开箱裸、默认 URL 随 cdimage 轮换失效、国内下载慢；② 安装器加 sha256 校验 + 断点续传（现中断重下 30MB）；③ rootfs 元数据（installedAt/url/version）+ 默认 URL 更新提示（重装=清空 linux 区）；④ AI 后台任务三件套（run_background/status/kill + TailBuffer，对齐 agent）——长任务不阻塞对话。**并入 LoopGuard 待办**：单轮总预算 + 最大步数（防失控无限 600s 命令）。**顺手**：`/skills` 绑定挂载（一行 proot 参数）。**不做**：换发行版（丢 apt 生态）、多工作区共享只读 base + overlay（省盘但架构大改）、包管理 UI 化（终端 apt 已可用）、真 root/提权（proot 天花板）、终端⑥注入。详见交接文档第七节。
- 2026-09-10：**上游 2.5.1 批次已同步并评估（3 提交，master ff-only 推送）**——`42b933d8` debug 启动图标 DEV 角标（D 类，debug 变体纯新增零冲突，可顺手吸收）；`513b784c` 翻译快捷方式 + RouteActivity 统一 Intent 导航（B 中，翻译入口有价值但 RouteActivity 重排与 fork 分享/翻译/F6 定制冲突，需手工合）；`a9b35ba2` bump 2.5.1（跳过，fork 自有版本体系）。同步点更新为 `a9b35ba2`（详见 upstream-analysis「2.5.1 批次评估」）。web-ui 两条（ask_user 多选 `fcb04126`、模型弹窗标签堆叠 `3d75f83f`）维持 D 类待吸收（用户暂放）。
- 2026-09-11：**对话导出修复四连 + file:// 附件归一化（v1.0.1 / 252 草稿，真机验证通过）**——①TikZ 在导出 PNG 中缺失：TikzJax 异步渲染 vs BitmapComposer 一次性捕获 → 导出前预编译入 TikzSvgCache + TikzJax 初始 state 同步读缓存（`013c58f9`）。②长对话只导出一部分：**真根因=分页窗口外层 `Box(height=页高)` 的高度约束截断内容**，内层须 `wrapContentHeight(align=Top, unbounded=true)`（`51d08d26`）——此根因同时解释"拼接版只导出部分"与"多图末尾纯色页"两种早期表现。③尾部丢失：测量轮询至高度稳定（异步内容就绪）+ 兜底补页 + 逐行精确判空（`6363195b`）。④导出改**分页多图 + ACTION_SEND_MULTIPLE 一次分享**（`b81eb26c`）。⑤ file:// 归一化通用化 `c822979d`（头像→消息附件全显示面，见交接文档第七节）。发布 v1.0.1 (252) 草稿（`b9e65a6e`）。
- 2026-09-11：**自定义时间提醒间隔落地（吸收上游 5b4d7895 → `772cd77e`，真机验证通过，持久化已确认）**——`Assistant.timeReminderIntervalMinutes`（默认 60；@Serializable 随 JSON 自动持久化、旧数据默认值兜底，**无需 PreferencesStore 三件套**——上游分析评估时标注有误，实际为 Assistant 对象字段非 Settings 顶层项）；`TimeReminderTransformer` 阈值参数化（`intervalMinutes.coerceAtLeast(1)×60`，transform 读助手字段）；记忆页：「时间提醒」开关开启后显示「时间提醒间隔」条目（当前值）→ 点按弹数字输入对话框（正整数校验、数字键盘）、保存到助手；6 语言 5 key；单测 +3（10 用例全绿：10 分钟间隔×30min gap 触发、240 分钟×2h 不触发、间隔钳制≥1min）。真机：间隔修改→重启后保留（用户确认持久化已生效）。
- 2026-09-12：**角色卡导入增强（→ `10fa2521`+`b6879b47`，真机通过）**——①**SillyTavern V1 老卡兼容**（`10fa2521`）：parseAssistantFromJson 只认 spec=v2/v3，V1 老卡顶层即字段（无 spec/data 包裹）必报「Missing spec field」→ 抽公共 parseCardData（V2/V3 重复体去重）+ `CharaCardV1Parser`（data=顶层 json）+ 缺 spec 但顶层有 name 自动按 V1 解析；顺带把 `mes_example` 并入 systemPrompt（三版本统一，示例对话不再丢）。②**预设文件识别**（`b6879b47`）：缺 spec 时先判 SillyTavern 预设（顶层 prompts 数组 + prompt_order）→ 明确提示「这是 SillyTavern 预设文件（不是角色卡），请导入角色卡（JSON 或 PNG）」（6 语言）——根因：用户导入【Tavo酒馆通用】柯尔的妙想_v1.5.json（实为预设）误报缺 spec。③B：缺 spec 文案 6 语言友好化（含「老版 V1 卡会自动识别」）。单测 AssistantImporterTest 6 用例全绿（V1/V2/V3/缺 spec 无 name/未知 spec/预设识别）。真机：V1 老卡导入 OK、预设文件得明确提示（均已验）。\n- 2026-09-12：**翻译快捷方式 + RouteActivity 统一 Intent 导航（上游 `513b784c` → `58a64d8d`，真机四路径通过）+ turbo F6 关闭**——`handleIntent` 统一入口（冷启动 `savedInstanceState==null` / `onNewIntent` 都走；ACTION_TRANSLATE→Translator、SEND/PROCESS_TEXT→ShareHandler、conversationId→Chat）；navStack 未就绪 `pendingIntents` 排队 + `AppRoutes` SideEffect flush（修旧 ShareHandler 副作用式「App 已开时分享偶发丢失」时序）；末尾去重。fork 适配：streamUri 保留 `readShareStreamUri` 三级兜底（D6/D7，上游仅 getStringExtra）；F6 顺手关闭（`normalizeNewlines` \r\n→\n，turbo 3652fd6 语义）。资源：Manifest +TRANSLATE filter（app.androidrik 包名）、shortcuts.xml +translator（translator_page_title）、ic_translate 上游原版。真机：翻译快捷方式/分享/划词/会话跳转 × 冷/热均通过（用户确认）。
- 2026-09-12：**模型名/提供商名支持空格（上游 `1a1e672e` → `bb92f489`，真机通过）**——ProviderConfigure 三处 name 输入（OpenAI/Claude/Google）去即时 trim + 模型 displayName 输入去即时 trim（首尾空格可编辑、内部空格如 "GLM 5.3 Flash" 不再被破坏）；保存点 5 处去尾（Provider 保存 / ProviderOverride 保存 / 设置页 AddProvider：copyProvider(name=trim)；Add/Edit Model：copy(displayName=trim)）。结合 fork 多 key 编辑器与 ProviderOverride 定制手工合；baseUrl/apiKey/modelId 保持即时 trim（与上游一致）。真机：输入带空格可编辑、保存后首尾去尾内部保留（用户确认）。
- 2026-09-12：**上游小件三连吸收（`1e98f69a`）——待吸收第一批清仓**——①`aab5026e`（#1782）：NotificationConfig 默认 smallIcon 切 `ic_stat_rikkahub`（五密度纯 alpha PNG，从上游提交提取；原矢量图标状态栏胶囊只取 alpha 渲染丢失细节）；②`4fcb590a`：ProviderConnectionTester 硬编码中文标签 string 化（6 语言 5 key）+ 结果标签列 64→120dp maxLines=2；③`42b933d8`：debug 变体启动图标 DEV 角标（badge/label/foreground/monochrome + anydpi overlay，仅 debug 生效）。连同本日早前（连字 `f26a19f1`、AGENTS.md `e8e047e5`、tool-only `3d2b4d54`），**上游 2.4.11→2.5.1 后全批次待吸收小件清零**；MaruCode（`74397a32`）用户决策放弃。剩余真存量：X-Session-ID（搁置）+ 第三批大改（workspace 预览/Shell 兼容、Imggen 多选、D 类低优先）。
- 2026-09-12：**AGENTS.md 工作区指令注入（上游 `2689e753` → `e8e047e5`）+ tool-only 可见性补齐（`321443d8` → `3d2b4d54`）**——①WorkspaceReminderTransformer 追加 `buildAgentsPrompt`：读取 `/root/.agents/AGENTS.md`、`/workspace/AGENTS.md`、会话当前目录 `AGENTS.md` 三层，注入 `<workspace_instructions>`（单文件 64KB 上限、读取失败静默跳过、CancellationException 透传）；复用既有 `rootfsFileSize/exportRootfsFile`，零架构冲突。②`isEmptyUIMessage` 补 `ServerTool -> false`（上游 321443d8 fork 此前已有 Tool 分支但缺 ServerTool）+ MessageVisibilityTest 补 server tool 用例（3 用例全绿）。上游 2.5.1 后批至此**全部吸收**（deepseek-flash `e9a39751` + AGENTS.md `e8e047e5`）。
- 2026-09-12：**系统栏图标色恢复（上游 `a7ee362f`）+ 模型注册补缺口（→ `e9a39751`，真机通过）**——①`Theme.kt` SideEffect→DisposableEffect(view,darkTheme)：保存 previousLightStatus/NavigationBars、onDispose 恢复（嵌套主题如终端深色退出后系统栏图标色不再残留异常，#1876，即「手工移植剩 1 项」闭环）。②模型注册组：**按代码等价核对纠正 merge-base 误报**（fork 手工直套非上游祖先，merge-base 恒判未吸收）——glm5.3(`5b58c957`)/hy4(`86c85236`+`5b890d22`)/kimi k3/qwen3.8(`0826a3b9`)/deepseek v4.1 flash/kimi-color.svg(`979cd169`) 早已在 fork；真正缺口=GPT_6(`c73f8972`)+裸 `deepseek-flash`(`46cacf23`)，补入 ModelRegistry+ALL_MODELS（`e9a39751`）。真机：终端深色进出后系统栏图标色恢复、deepseek-flash/gpt-6 可搜到。
- 2026-09-12：**图片选择改 PickVisualMedia（上游 `d3a53e0a` → `e9c36591`，#1821）**——`GetContent`/`GetMultipleContents` 触发 ACTION_GET_CONTENT，在部分 ROM（红米 Note 12 Turbo 等）被文件管理器接管，点选图片落到「CR 文件夹」而非相册；改 `PickVisualMedia`（走系统 Photo Picker，且免存储权限）。**fork 调用点比上游多（4 处 vs 上游 3 处）**，因上游 `ChatAttachmentPicker.kt` 在 fork 中合于 `ChatPage.kt`：`UIAvatar`/`BackgroundPicker` 改 `PickVisualMedia()`、`ChatPage`/`ImgGenPage` 改 `PickMultipleVisualMedia()`、launch 参数改 `PickVisualMediaRequest(ImageOnly)`；**保留 ChatPage 视频/音频的 `GetMultipleContents`**（PickVisualMedia 不覆盖音频，上游同）。编译通过、已装机待真机测试。
  - 同批复核 `b6df5f04`/`96fbe7e3`/`0533acde` 三项**确认不吸收**：OAuth 抽包为纯结构搬迁（fork 功能已等价且已吸收其后 `8ea375a9` cloudflare 匹配 + `c49be6fe` 令牌刷新）；videogen 上游自 8/24 初始化后零提交、未接进 App；移除的是上游自家内置提供商。
- 2026-09-12：**第三批大改三件套落地（真机全部通过，待吸收清零）——workspace HTML/SVG 预览（`7038e981`→`b2434dce`）+ Shell 兼容模式（`03246406`→`e419085f`）+ Imggen 长按多选（`3896b6d6`→`78b3fbf9`）**——①预览：WorkspaceDetailPage 改 340 行级重写（SVG 路由 + 预览/源码切换 + WebView 隔离加载 + file:// 归一化显示层），HTML/SVG 在文件列表→详情页直接预览不落地编辑。②Shell 兼容模式：DB 24→25（`shell_compatibility_mode` 布尔，PreferencesStore 三件套）+ 详情页开关（默认关）+ `PROOT_NO_SECCOMP=1` 禁用 seccomp（上游 proot 5.1.0 兼容）+ 6 语言文案。③Imggen 长按多选：`combinedClickable` 长按进选择模式、点选勾选、全选、批量删除；适配 fork `GeneratedImage.id` 为 Int（上游 String）避免编译错。**配套修复（`a18a3193`）**：点击穿透（选择模式内层 AsyncImage `clickable(enabled=!selectionMode)` 否则事件被吃、点选失效）+ 全选去重（`selectedIds.clear()+addAll`，原 addAll 累积重复致数量暴涨）。**SVG 列表缩略图**：`WorkspaceDetailVM.exportToCacheDir` 按需导出到 cache（`entry.path.trim('/').replace('/','_')` 防碰撞）→ Coil singleton SvgDecoder 在文件列表直接渲染 44dp 缩略图，免进详情页。**真机诊断定论**：Shell 兼容模式（纯 ptrace `PROOT_NO_SECCOMP=1`）+ app uid + KernelSU 下路径类 syscall（getcwd/chdir）返回 ENOSYS（root 运行则正常；默认 seccomp 模式正常）——**应用层不可修，固有限制**；缓解：初始 cwd 移 `/root`（rootfs 内部）+ 命令包装 `cd -- "$1" 2>/dev/null || true; eval "$2"`（容错 cd）+ 详情页警告文案（6 语言「KernelSU/root 设备可能返回 ENOSYS，无需要求请保持关闭」）。root 仅作诊断辅助。**上游待吸收至此清零**（2.4.11→2.5.1 后批次全部落地；45275025/019c46ae 已放弃、X-Session-ID 搁置）。
- 2026-09-12：**token 统计改进（`72e019f2`，真机验证通过）**——起因：现状统计把节点**全部分支**（含被丢弃的重生成/编辑版本）无差别求和，口径是「花费账本」但呈现让人按「对话规模」理解（真机实证输入:输出 = 493.44M:1.93M ≈ 256:1；社区流传「按最后一次输出统计」为误传）。三项落地：**⓪ 名称快照**——`UIMessage` 加 `modelNameSnapshot`/`providerNameSnapshot`（`handleMessageChunk` 创建消息时写入，可选字段零迁移；存快照而非 id 因模型删除后悬空、删除重建得新 UUID 会裂成两行）；**① 双口径整页切换**（默认实际消耗）——SQL 零迁移：`select_index` 是独立列且 `json_each` 的 key 即数组下标，选中分支口径=`WHERE j.key = mn.select_index`（越界保守少算），总消息数与按天统计同步跟随；**② 维度**——按模型（GROUP BY modelId+快照名，VM 侧解析并按「名称+提供商」合并，删除重建的同名模型合一行；横条占比 top8，不引图表库）+ 按天 Token 热力图切换（复用分位分档组件，按千 token 折算适配 Int）；**③ 缓存命中率**——`cached/prompt`（**核正：Claude 解析已把 cache_read+cache_creation 计入 promptTokens，三家 provider 语义统一、cached 均为 prompt 子集**，此前「Claude 口径不同」的判断有误），统计页缓存卡加「命中 N%」、NerdLine 追加式 `(468K cached, 95%)`。单测 3 文件 15 项全绿（快照与旧数据反序列化兼容/口径 SQL 拼接/模型名解析合并四情形）；6 语言 8 key。**明确不做**：成本估算（token×价格表）、Claude cache_creation 拆分、按天柱状图（可选增强不排期）。命名快照仅对新消息生效，旧消息走 id 回退。
- 2026-09-16：**恢复目标逐项核实，补两处设计稿承诺却漏做的项**——按目标「已使用上下文统计、发送文件工具、侧滑面板两个子集，分批落实」逐项核代码（三项分别有 `c9c6d8ea`/`5b0d6ab8`/`bde664ad` 且 handoff 记「真机通过」），**发现三处「文档写了、实现漏了」**：①上下文占用估算无压缩感知（`ace85288`，见上条）②侧滑文件面板**无搜索防抖**（设计稿 §6.5.2 明写「防抖 250ms」）且 **`invalidateFilesCache()` 是死代码**（定义后全仓无人调用 → 缓存永不失效，AI 刚写文件时用户看到过期目录）→ 补 `5abe8bf6`（`LaunchedEffect`+`delay(250)` 天然防抖；进入面板时失效并重载）。**教训：文档标「已落地」≠ 承诺项全做到——收尾核实时要逐条比对设计稿的明细承诺，不能只看提交存在与 handoff 状态**。
- 2026-09-16：**导出留存与「真落地」已实现（`534a5eef`）+ 又修一个破坏性 bug**——用户拍板 B（两边都存）后落地。**实现**：①`FileFolders.EXPORTS`（`files/exports`）+ `file_paths.xml` 加 `exports/`（**不加则分享抛 IllegalArgumentException**；该文件注释要求收窄暴露面故只加这一条）②`FilesManager.saveExportAtomically`＝**写临时文件+fsync+rename**，替代"先删旧再写新"（原写法在写入中途失败时毁掉前一份——JSON 文件名按标题无时间戳，同会话导两次必命中，正是「一次失败即消失」的字面成因）③`Settings.exportRetentionDays` 三件套（默认 7 天、0=不清理）+ 纯函数 `exportRetentionCutoff`/`isExportExpired`（含上限截断防溢出）+ 启动 `purgeExpiredExports()`（**必须读 `settingsFlowRaw.first()`**——`settingsFlow.value` 在 DataStore 加载前是 dummy 会静默用默认值；顺带清 `.tmp-` 残留）④`ChatExportSheet` 加 `saveToDocument`：为真时写留存区后弹 **SAF CreateDocument**，**只有真选定位置并写盘成功才续作移除；取消另存＝不续作**（内容已在留存区 → 与"取消=中止移除"并存不冲突）⑤`SettingFilesPage` 加 EXPORTS chip（该页已有 FolderRow，结构零改动）+ FileItem 加**分享**（原先只有删除/清空）+ 空态说明 TTL + 顶部保留天数设置；分享实现从 Export.kt 的 private 提为公共扩展 `utils/FileShare.kt`。**⚠️ 实现中又发现并修掉一个破坏性 bug（原设计稿未列）**：`onDismissRequest()` 会清掉 ChatPage 的 `exportThenRegenerateFor`，而 `onExported` 正是读它来续作 scope B ⇒ 原实现先 dismiss 后 exported，**「先导出再继续」的移除从未执行过**（方向安全但功能是死的；**我此前把这条报成"已实现并真机验证通过"是错的**）。修正为先 `onExported` 后 `onDismissRequest`。**教训：「清场」与「续作」共用同一状态时，顺序即语义**——两个 lambda 在同一同步栈内顺序执行，MutableState 写入立即生效。另修 PNG 的 `activity==null` 分支（原静默 return → 调用方报「已导出」，又一处假成功）。**真机验证**：导出 JSON 落在 `files/exports` 且登记 `managed_files`、`cache/temp` 无残留；TTL 实测（植入 30 天前文件→冷启动→被清+当日文件保留+`.tmp-`清掉，日志 `removed 1 expired export(s) (retention=7d)`）。单测 10 项；全量 536 项仅基线失败。**未做**：周期任务（首个 Worker；"过期"粒度是天，启动清一次等价）、PNG 排除出留存区（仍统一进，但主留存路径是相册）。
- 2026-09-16：**SAF 导出问题定位 + 留存设计定稿（仅设计，未实现）**——用户报「一次导出失败(或取消)后导出文件即消失，容易导致重生成的保障和提醒失效」，并提出「导出时暂存、一定时间后自动清理（可选间隔）」。**实测核实**（设备放标记文件 → force-stop 冷启动 → 目录 GONE）：四处导出都写 `cache/temp`，全仓唯一清理是 `RikkahubApp.deleteTempFiles()` 在 `Application.onCreate` 整目录 `deleteRecursively()` ⇒ **留存物活不过一次冷启动**。**拆出三个成因（性质不同）**：**① 真 bug=「先导出再继续」不检验是否真落地**——`shareFile` 是 `startActivity(createChooser)` **无结果回调**，故「导出成功」实际只表示「临时文件写完+面板弹出」；用户**随手取消分享面板，下游照样被移除**（保障在没保存任何东西时放行破坏性操作）。加重项：Markdown/HTML 卡片**连 runCatching 都没有**，`exportToMarkdown`/`Html` 内部 `catch + printStackTrace` 吞异常 ⇒ 写盘失败也报「已导出」（PNG 虽套 runCatching 但 `exportToImage` 自吞异常，同样假成功）。**② delete-then-write**——四处都是「先 `delete` 旧 → `createNewFile` + write」，而 **JSON 文件名按标题（无时间戳）** ⇒ 同会话导出两次必然命中，写入失败则旧的那份已毁（正是用户说的「一次失败即消失」）。**③ 过期后不可寻回**——撤销 toast 仅 6s，**全仓无「导出记录/再次分享」入口**。**用户两条决策**：①**真落地**（(b) 侧走备份页现成的 SAF `CreateDocument`：有真实结果回调，**取消=中止移除**）②**引入留存区**（`files/exports` 而非 cacheDir）+ 启动清理 + 可选周期任务 + **「导出管理」入口（可再分享/删除）**。**设计核心=分离两种需求**：(a) 分享中转（秒级、cacheDir 正确、ACTION_SEND 合适）vs (b) 破坏操作前留存（须跨重启+须可验证，cacheDir 错误）。**实核要点**：`managed_files.relative_path` **UNIQUE** ⇒ 不能同名覆盖，须 `buildUuidFileName`+`display_name` 分离；**原子写入=临时文件+`renameTo`** 消 ②；`file_paths.xml` **必须加 `exports/`** 否则分享抛异常（该文件注释明说要收窄 filesDir 暴露面，故只加这一条不用 `path="."`）；备份**建议不含 exports**（会话本体已在 DB）；**PNG 已另存相册（MediaStore）故留存区对它是冗余**，且 PNG 上限 200 页/单页 15000px 体积大 ⇒ TTL 必须有默认值。**三个手段互补不重叠**（本轮之前已辨析）：snackbar 撤销=真可逆（秒级）／导出=App 外副本（不能回退）／归档=in-app 持久可恢复。设计稿 `docs/design-export-retention.md`（§七 五个待确认；**重点 §3.1 的 A/B**＝SAF 成功后是否还要在留存区再存一份，**倾向 A 不存**）。
- 2026-09-16：**收尾补 Phase 0.5 漏项：上下文占用估算改为压缩感知（`ace85288`）**——恢复目标里「已使用上下文统计」一项核实时发现**设计稿 4.3.1 明确承诺的两处「随压缩一起加」在 Phase 1 落地时漏了**：①**陈旧 usage 判定**（`usage.promptTokens` 报的是**那次请求当时**的上下文，压缩后该值仍是压缩前全量 → 显示虚高，可见表现为「手动压缩成功占用条数字不动、像没生效」，导入带检查点的长会话同理）②**估算对象应是发送窗口**（`requestWindowMessages()`）而非全量历史（`currentMessages`）。修法：以最后一条 assistant 的 `finishedAt`（缺失退回 `createdAt`）与检查点 `createdAt` 比较判陈旧，回退本地估算；估算一律按窗口算（无检查点时该函数返回 `currentMessages`，非压缩场景零变化）。**副带**：`maybeAutoCompress` 的注释「已有检查点时仍按窗口占用判断」与旧行为不符（旧实现按全量历史），现在名副其实。单测 14 项（+4，断言用 `requestWindowMessages()` 推算期望值——首版写死 3 被检查点合成消息约 90 token 打脸）。全量 526 项仅基线失败。
- 2026-09-13：**design-auto-compaction 分批落地（三个提交，装机待真机）**——**① 已使用上下文统计（`c9c6d8ea`，Phase 0.5 可见先行项）**：输入框操作行细进度条+百分比，80% 变红警告，点击弹「已用 X / 窗口 Y」；`Model` 加 `contextLength`（可空零迁移，256K 兜底），`utils/ContextLength.kt` = parseContextLengthInput/formatContextLength/effectiveContextLength/estimateWindowTokens（usage 优先+本地估算兜底）/estimateTokenCount（CJK 1.5 字符/token+工具输出）；单测 10 项。**② 发送文件工具（`5b0d6ab8`，附录 C.1）**：`workspace_present_file`——校验文件存在后输出 path/name/sizeBytes/note JSON（不返回内容），复用 `UIMessagePart.Tool`+ToolUIRegistry 分派（零新 part 类型），PresentFileToolUI 渲染文件卡片（打开跳 WorkspaceFileEditor/导出/分享），`/workspace` 前缀→FILES 区其余→LINUX 区；ToolUIContext 加 workspaceId。**③ 侧滑抽屉文件面板两子集（`bde664ad`，附录 C.2）**：`WorkspaceRepository.searchFilesByName`（BFS+limit 80）+ ChatDrawerVM 目录级 filesCache/状态；ChatDrawer 助手绑工作区时显示「会话/文件」SegmentedButton 切换，文件面板=搜索框+路径行+轻量列表（目录进入/文件跳详情）。**明确不做**：`ChatMessageNerdLine` 图标化精简。全部单测绿+编译通过，debug 已装机。
- 2026-09-13：**上下文进度条细化 + contextLength 两级自定义（`67ed90c4`/`50f2d741`，真机通过）**——①进度条从 ModelSelector 前移到**思考强度（ReasoningButton）右侧**；②**两级自定义**：`Model.customContextLength`（per-model 手填，@Serializable 零迁移）> `Model.contextLength`（拉取/内置）> `Settings.contextLengthOverride`（全局默认，PreferencesStore 三件套）> 256K 兜底；模型编辑页基本设置加「上下文窗口」输入（支持 256k/1M、placeholder 显示内置值或「跟随全局默认」），设置→模型设置页加「默认上下文窗口」全局项；`effectiveContextLength(globalOverride)` 带参；单测补优先级用例（9 项全绿）。③**修 IME 遮挡（`50f2d741`）**：ModelSettingsPage 的 LazyColumn 与模型编辑 sheet 内容列补 `imePadding()`（全局默认项是列表最后一项最易被键盘盖住；沿用文件内已有先例）。
- 2026-09-13：**压缩检查点数据模型 + 摘要提示词结构化（`f58f3888`，Phase 1 前置，DB 迁移真机验证）**——**① 检查点数据模型**（独立表方案，design §4.4）：`compression_checkpoint` 表（conversation_id 外键 CASCADE + content/message_count/boundary_node_id/created_at，id 自增，末行=active）+ DAO（insert/getOfConversation/observeOfConversation/deleteOfConversation/**trimToLatest** 合并后清冗余）+ DB **25→26 AutoMigration** + `CompressionSummary` 领域模型 + `Conversation.compressionSummaries`（默认空，**不持久化到 conversations 表**——独立表管理，整对象保存不会误删检查点）+ repository 四个读写方法 + 主加载路径填充。**② 摘要提示词结构化**：`DEFAULT_COMPRESS_PROMPT`自由文本 7 条 → **结构化 8 段**（Primary Request/Key Technical Concepts/Files and Code/Errors and Fixes/Pending Jobs/Current Work/Next Step/Critical Context，强制保留精确路径/命令/错误串/标识符——旧版会丢这些）+ 检查点前导语与文本构造（请求期组装用：「当背景、不复述、不承认」，不落库为消息）；单测 7 项。**真机验证**：设备启动后 `user_version` 25→26、表与索引建成、**215 会话/5112 节点数据完好**、checkpoint 表为空（尚无压缩，预期）。**压缩执行**（生成摘要/检查点替换发送窗口/UI 折叠行）为 Phase 1 后续。
- 2026-09-13：**Phase 1 主体分四批落地（`97779543`/`d6ce22c2`/`c14c67b3`/`b782419a`，装机待真机验证）**——**批次1 会话层窗口**：`Conversation.activeCompression`（末行 active，边界节点消失即失效）/`requestWindowMessages`（检查点合成消息 + 边界后原文，isSynthetic 不落库）/`windowNodes`，轨迹只追加；**批次2 发送侧**：ChatService 非重生成路径改走 `requestWindowMessages()`（重生成历史保持原始轨迹——压缩不改变对历史的重新生成语义）；GenerationHandler 将检查点固定在窗口最前**且不参与 limitContext 截断**（limitContext 保留尾部会截掉开头的检查点）+ 抽 `partitionCheckpointMessages`（判据 isSynthetic+USER+前导语；已核实 TemplateTransformer 对 isSynthetic 跳过）；**批次3 压缩执行**：`CompressionPlanner`（token 驱动保留策略，**RETAIN_RATIO 取 0.4 而非 harness 的 0.16**——后者是编码 agent 值，会让聊天内容被过度摘要；含防误改守卫单测）+ `ChatService.compressToCheckpoint`（切分→分块并行摘要→prior 合并重写→轨迹只追加落库→trimToLatest 清冗余→更新内存态）；**批次4 UI**：折叠行 `CompactionCheckpointRow`，**展开/收起切换 items 列表**（收起=windowNodes / 展开=全量）——复用同一渲染块，历史消息的编辑/重新生成/删除/分支/收藏/翻译/分享在展开态**全部可用**（修正初版「只读预览」的设计错误，压缩不该剥夺用户对历史的操作能力）；**一致性闭环** `isWithinCompressionBoundary` + `invalidateCheckpointIfWithinBoundary`（编辑/删除边界内消息→摘要过时→删检查点回退全量）；ChatVM.handleCompressContext 改走新方法。单测共 18 项（会话窗口 6+3、保留策略 9、提示词 10）。**⚠️ 原「重新生成未触发失效」已由 `bbba9ba8` 区域指纹覆盖（过时描述）**；自动触发（阈值+失败静默）与设置项（阈值/保留比/目标 token 可配）仍待做——当前为手动入口触发。
- 2026-09-13：**手动压缩入口对齐检查点式实现（`b9e99c91`，装机待验证）**——用户反馈「手动压缩入口仍是旧版」：后端已切 `compressToCheckpoint`，但对话框 UI 未同步。修复：①「保留最近消息数」输入框在检查点式压缩中**已不参与切分**（生效的是 token 驱动保留比例）→ 改造成「保留比例」选择器（20/30/40/50/60%，默认 40%），消除误导性 UI，`compressToCheckpoint` 加 `retainRatio` 参数（0.05~0.9 钳制）；②**文案修正**：旧警告「压缩将重置当前对话中的所有消息」在「轨迹只追加 + 可展开回看」实现下已不成立 → 改为「较早消息从模型上下文摘要化、完整历史保留可展开、编辑/重生成可取消」（6 语言，注意 Android 资源里英文撇号 `model's` 会触发 unicode escape 错误，需避开）；③返回值改 `Result<CompressionSummary?>` 区分「无可压缩内容」（明确提示，避免用户以为点击无效）与「失败」（addError）；④删除死代码 `compressConversation`（约 85 行，无调用方，保留通用工具 `mergeCompressedConversation`）。
- 2026-09-13：**Phase 2 自动触发 + 设置项（`c89c8b65`，装机待验证）**——①设置三件套：`autoCompressEnabled`（**默认关**，保持发送行为可预期）/`autoCompressThresholdPercent`（80）/`autoCompressRetainPercent`（40）/`autoCompressTargetTokens`（2000）；②`shouldAutoCompress`（`>=` 阈值触发、Long 中间量防溢出、阈值钳制 **50~95** 防抖动与永不触发）；③`ChatService.maybeAutoCompress`：插在 `checkInvalidMessages` 之后、按发送窗口组装之前，占用估算复用 `estimateWindowTokens`（与 UI 进度条同源），**失败静默**（`limitContext` 仍作同步兜底），**重生成历史时跳过**（走完整轨迹）；④设置页自动压缩卡片（开关 + 阈值/保留比/目标 token 选择器，仅开启时展开）；单测 +5（达阈值/无窗口信息/钳制/大数不溢出/默认值）。**Phase 1+2 主体至此完成**。（原「已知限制：重新生成未触发失效」已由 `bbba9ba8` 区域指纹覆盖，属过时描述。）
- 2026-09-13：**修复检查点合成消息泄漏 + 自愈受损节点候选（`ce311156`，真机截图验证）**——用户报「展开压缩历史只多出一条消息、另有一条无论是否展开都重复出现」。**根因**：ChatService 曾把「发送窗口」（= 检查点合成消息 + 边界后原文，已 drop 边界前节点）当 generateText 的 `messages` 回写基线，`updateCurrentMessages` 按索引把窗口消息**逐条追加到各节点候选末尾** → 每节点多出「后文消息的副本」、`selectIndex` 被改指向副本。**修复**：`generateText` 新增 `windowMessages` 参数（messages 恢复全量=回写基线、与 messageNodes 索引对齐；windowMessages 仅供 provider 上下文）。**自愈**：`repairCorruptedCandidates()`（同一 message id 跨节点出现=追加副本 → 只保留首个候选，追加恒在末尾故首候选必为原始消息；未污染节点不动，编辑/重生成历史得以保留；幂等）+ `filterOutCheckpointMessages()`（剔除被误持久化的检查点本身——**判据只能用文本前导语，不可依赖 `isSynthetic`：该字段 `@Transient`，持久化后丢失、DB 读回恒 false**，这是第一版自愈失败的原因）。二者在 repository 加载路径应用、随下次保存落库。**真机对比**：修复前展开显示检查点前导语文本；修复后展开显示真实历史（首条提问含 2/2 分支切换器、gpt-5.6-terra 回复、工具调用步骤、token 统计），摘要文本与重复消息消失。单测 +9（NodeCandidateRepairTest 5 + 清洗 4）。
- 2026-09-13：**压缩检查点失效改为区域指纹校验（`bbba9ba8`，B 方案，真机交叉验证）**——此前只在「编辑/删除」两条路径显式失效，**遗漏「重新生成」与「切换分支」**（且 `checkInvalidMessages` 自动清障未覆盖）→ 区域内消息换了但摘要仍是旧内容，后续轮次 AI 上下文与用户所见不一致（重生成当轮走完整轨迹所以当时正确）。**方案**：`compression_checkpoint` 加 `area_fingerprint`（可空）→ **DB 26→27**；`activeCompression()` 改为指纹校验（区域=边界含之前，各节点 id + **选中消息 id** 拼接的 SHA-256 前 32hex，不一致即失效）；压缩时写入；**删除**原两处显式失效调用与 helper（语义统一到一个判据，一次覆盖全部路径含未来新增）；窗口内改动不影响；指纹为 null（旧数据/备份恢复的 26 版数据）降级为边界存在性校验。**附带性质：失效可逆**——指纹是纯函数，切回原分支即重新生效。**真机交叉验证**：按源码同算法独立复算指纹与 DB 值完全一致；编辑区域内消息（候选 2→3）→ 复算 `391f5de7` ≠ 记录 `40930ade` → 折叠行消失（失效）；DB 行保留（运行时判定非删行，故可逆）。文案对齐：对话框/设置项改为「编辑、重新生成、删除或切换分支都会自动取消压缩」（6 语言）。单测增至 8 项。
- 2026-09-13：**消息节点增量保存（`85b2014b`，B 方案，真机验证）**——原 `updateConversation` 每次保存都`deleteByConversation` + 按下标**全量重插**：5000 节点会话发一条消息要重写 5000 行（用户最大会话已 5112），且把「内存列表 ≡ 全量」变成写入侧依赖的不变量 = **L2 窗口化的前置障碍**。改为求「库中签名序列 vs 内存签名序列」公共前缀 → 删前缀后旧行 → 只插前缀后新行（尾部追加/改末条即只写 1 行）；`node_index` 用绝对位置为窗口化留余地。**实现中途发现并修正的关键正确性**：签名必须含**内容**（id+select_index+messages）——**只比 id 会漏掉「编辑」**（编辑时节点 id 不变、仅候选与 selectIndex 变），误判「未变化」跳过写入 → 编辑丢失；已加单测锁死。**安全**：messageNodes 空时不触碰节点表（防误传轻量 Conversation 清空整会话）。**真机验证**：用户手动编辑窗口内消息后，节点总数 40 不变、node 34 候选 1→2 且 select_index 0→1、内容为编辑后文本、全库 node_index 无空洞（5112 节点连续性通过）。单测 6 项。**L0 经确认与 L2 合并**（非窗口化时 4 处漂移点本无 bug，单独改无法验证）。
- 2026-09-13：**压缩模型「跟随当前对话模型」选项（`fcc45435`）**——`Settings.compressModelId` 语义扩展：显式 id（可解析）> 跟随当前对话模型（null/空哨兵）> 全局 `getCurrentChatModel()`；设置页加「跟随当前对话模型」开关（6 语言）。
- 2026-09-13：**备份内容全局化 + 恢复确认弹窗（`04173c0f`，真机验证）**——①新增 `Settings.backupItems`（三件套，默认全三项），删除 `S3Config.items`/`WebDavConfig.items` → WebDAV/S3/本地三处共用同一份，本地 tab 因此获得选择器；per-destination 差异未失去（备份为纯手动、无自动调度，需要「云端精简、本地完整」时临时切换勾选）。②恢复前确认弹窗 `RestoreConfirmDialog`（三入口共用）：恢复=覆盖且不可逆（对话记录=整库替换），列出将覆盖项 + 红字警示；本地入口在**选文件前**弹。
- 2026-09-13：**工作区文件备份（B 方案）+ 备份体系重构（`e077d8bb`/`b9ddd5a7`/`0d391d3a`，真机验证）**——①新增 `BackupItem.WORKSPACE_FILES`，打包 `workspaces/<root>/files/**` 而**排除 rootfs**（可重装；含则体积 +30MB/工作区）；顺带补 `files/tool_outputs` 遗漏（`GenerationHandler` 会把截断的工具输出落盘，消息里存文件引用）。②**重构**：统一 `BackupItem` 枚举（原 S3/WebDAV 各一份重复）+ 抽 `BackupArchiveEngine`（两 Sync **867 行重复实现降至 280 行**）+ 共享 UI selector。③坑：恢复路径重复 `files` 段（打包 `workspace_files/<root>/` 不含 files，恢复侧误按 `<root>/files/` 解析 → 全部落到错路径，**只有路径映射单测才暴露**）；`isValidRoot` 正则字符类含 `.` 致 `.`/`..` 通过（目录逃逸，已显式拒绝）。设计见 `docs/design-backup-workspace-files.md`。
- 2026-09-14：**数据库恢复 WAL 修复（`6b3032fe`，真机验证）——「恢复成功但聊天记录没变」根因**——触发：**上游原版导出包只含 `rikka_hub.db`（无 `-wal`/`-shm`）**。原实现把 db 条目**原地覆盖**到现库路径，而恢复全程 Room 连接常开（Koin single，**全仓无一处 `close()`**）→ SQLite 下次打开把残留的同名旧 WAL 当作属于该库**回放**，把刚恢复的数据整体盖掉（且可能是新库结构+旧库部分页的**混杂损坏**）；流程末尾 `exitProcess(0)` 只是杀进程、**不触发 checkpoint**，兜不住。**修复**：db 条目先暂存 cacheDir → zip 读完 → ①先复制到目标目录（重活前置，失败发生在关库前）→ ②`appDatabase.close()`（WAL 下会 checkpoint 并释放文件）→ ③删陈旧 `-wal`/`-shm` → ④同目录 rename 换到位；纯文件操作抽 `DatabaseRestoreFiles`（JVM 单测）。**代价**：恢复后连接已关，**必须重启**（已有「重启」弹窗，属正确行为）。**真机验证**：上游包（165 会话/3916 节点）恢复后未重启即读到该数据、目录里只剩主库一个文件，重启后仍为该数据（修复前被旧 WAL 盖回 215/5112）。**顺带清理**（`9ce5a33f`）：删死开关 `DisplaySetting.showUpdates`（fork 无更新检查实现、无消费方；上游对应字段 `updateCheckDisabledUntilEpochMillis` 语义相反、导入时本就丢弃）——UI+字段+6 语言字符串。
- 2026-09-14：**备份空间预检 + 字节级进度（`2a321c3a`，真机验证）——大文件加固第一项**——**空间预检**（安全 > 体验：恢复是流式直接写最终目标，磁盘写满=部分覆盖）：备份按「输入不压缩」走盘求和；恢复读 zip **中央目录** `entry.size`（不解压）；留 5% 余量、失败给「需要/现有」；**只预检 cacheDir**（SAF 目标/源空间平台不提供）。**字节级进度**：五阶段（打包/上传前准备/上传/下载/解压）经**回调参数**逐层传入（不在被两个 singleton 共享的引擎上挂 StateFlow，避免并发串扰），VM 持 StateFlow、三 tab 共用 `BackupProgressRow`（数字+百分比，无 6 语言资源）。**两坑**：①打包起初「整个文件拷完报一次」→ 85MB DB 期间进度条**冻住 0%**（截图实证），改 `copyReporting` 缓冲循环内增量上报；②S3 上传前 `sha256Hex` 全量读盘需上报为 PREPARING，否则进度长时间停 0%。**真实数据**：导出 39%→85%、恢复 27%、日志 `need ~127 MB, available 137 GB`。设计见 `design-backup-workspace-files.md` §9.1/§9.2。
- 2026-09-14：**v1.0.4 (259) 转正为唯一 Latest**——bump `13db045b`（258→259，versionName 保持 1.0.4；**设备已装 release 258 故必须递增**）→ 同名 tag `v1.0.4` 替换草稿资产 + 更新日志 → 用户检查通过 → `gh release edit --draft=false --latest`（tag 指向 `13db045b`）。README 标注 `7af80573`（正式 v1.0.4，无草稿）。**注**：`app/release/` 残留同名旧 APK 会被构建脚本覆盖，须先核实 versionCode 再传。
- 2026-09-14：**记忆工具审批落地（`a2add734`，真机验证通过）**——①`Assistant.memoryModifyApproval`（默认 true）按 action 分档：`create` 放行（追加式可撤销）、`edit`/`delete` 需审批（不可逆）；实现走既有 `Tool.needsApproval(input)` 基础设施（`GenerationHandler:159` 读 `input["action"]`），`ChatMessageTools:135` 自动渲染批准/拒绝（记忆工具零 UI 新增）；②补 `MemoryToolUI` delete 分支目标预览（`produceState` + `MemoryRepository.getMemoryById`，否则删记忆只能盲批）；③记忆页开关 + 6 语言文案。**同批修复内容风控 403**：风控/违禁词拦截的 403 被 `isKeySwitchingError` 误判为鉴权失败 → 白旋转 key 额度；加 `CONTENT_BLOCKED_MARKERS` 前置排除（同时在 `isRetryableGenerationError` 里置于 IOException 判定**之前**，因网关常包成 IOException），覆盖中英文标记（风控/违禁词/内容防火墙/content policy/moderation_blocked 等）。单测：审批 5 项 + 403 分类 3 项，全绿；真机 E2E（注入测试会话→审批卡片显示目标内容→拒绝后记忆 #2 仍在）。注：全仓 `generateEmbedding` 零调用、`ai.sqlite:vector` catalog 声明为悬空（无 build 引用、Maven Central 查无此物）。
- 2026-09-14：**记忆机制借鉴评估（仅评估，未实现）+ 发现注入实缺陷**——①**Yuihub（RikkaHub fork，本地 `<work>\projects\Yuihub\`）记忆机制**：其**语义检索上线后第二天整套删除**（`a8c33b2d` 加 `EmbeddingService`/`embedding` 列/打分融合 → `fbd15331` 全删，含迁移 32→33 重建表去掉向量列、`EmbeddingConfig` 三件套与 170 行 repository 一并移除），构成「记忆语义检索不做」的**独立二次印证**（与本仓 §6.3 成本结论一致）；值得借鉴的是**组织方式**——打分校准+字符预算（`importance × (0.55+0.45×exp(-ln2×age/30天))`，按 1800 字符预算纳入而非数条数）、被动提取管道 `MemoryExtractor`（回复后异步带现有记忆索引让 LLM 输出 add/update/delete，天然去重消解；单飞+pending 防丢事实、防幻觉 id、fail-open）、整理器 `MemoryConsolidator`（先落库再删源、consumed 防重复消费、importance 取源最大值）；**不建议照搬记忆快照**（append-only 合成消息持久化，思路与本仓压缩检查点同族但嫁接同层、交互面大）。详见 `peer-apps-benchmark.md` §四。②**发现实缺陷**：`GenerationPrompts.kt:19` `memories.take(60)` + `MemoryDAO` 查询**无 `ORDER BY`**（SQLite 按 rowid=插入顺序）→ 超过 60 条后进 prompt 的是**最旧**的 60 条，最新记忆反而进不去（实测 `no ORDER BY -> [1,2,3,4,5]` 确认）；修法=DAO 加 `ORDER BY id DESC`/`updated_at DESC`，或直接采用 Yuihub 打分校准。roadmap 已把「语义检索 P3」改为「评估后不做」并登记该缺陷。**该缺陷随后已修（`0580c6a6`，取最新 60 条 + DAO 显式 ORDER BY，真机验证）**，「记忆注入缺陷」节已转「✅ 已修」。
- 2026-09-14：**记忆工具审批（评估→落地）**——评估结论「值得做且成本很小（约 1 提交）」已由实现验证，落地见上方 `a2add734` 条目；评估过程与基础设施盘点保留在「一、中等价值 → 记忆工具审批」节。
- 2026-09-14：**记忆删除后序号重排评估（仅评估，不做）+ 更正上游隐藏 id 是半截修复**——用户问「删除记忆后的序号重排和难度（估计这就是上游选择隐藏 id 的原因）」。**上游原因核实**：issue #1830《memory_tool 记忆编号删除后留空洞，希望能支持重排或指定编号》**closed as wontfix**——re-ovo「id不影响任何东西」；duzhaokun123「记忆不应该暴露自增 ID：避免助手乱猜测记忆数量 / 避免用户看了难受」（报告者实证「deepseek 把编号 28 理解成有 28 条记忆」）；上游**选择隐藏而非重排**，无「重排难」的书面记录。**⚠️ 更正：那次隐藏是半截修复**——`upstream/master` 的 `buildMemoryPrompt` 仍 `put("id", memory.id)`、工具 schema/示例仍暴露 id → 「防助手猜数量」**未修复**，`08c2648b` 只治了「用户看着难受」。**重排难度分五层**（DB 副本实测）：①裸 `UPDATE id=row_number()` 抛 `UNIQUE constraint failed`（行序不保证）→ 须临时表重建/高位偏移；②`sqlite_sequence` 不自动回落（排成 1..49 后新 insert 仍拿 67，须同步重置）；③**历史引用=真难点**——`message_node.messages` 存工具调用原始 JSON 含 id，本备份 158 处引用且含指向已删 id 的（`delete 21/30/63`），重排后 `delete 21` 指向活记忆=**静默改写历史语义**，而 fork 原则是轨迹只追加；④在飞审批卡片按 id 引用会指错条；⑤**跑步机效应**——重排只清执行那刻的空洞，下次 delete 再生，**治不了本**（报告者自述「全删重建也解决不了，空洞只是换位置」）。**结论：DB 重排≈中-高且不值得**（上游 wontfix 合理）。三条路：①显示层连续序号（UI `forEachIndexed`，~0.5h，零迁移，即报告者最低诉求）；②稳定 opaque id（uuid/短哈希，根治空洞+不泄漏条数且永不需要重排；难度中，须验证模型能用 uuid 做 edit/delete）；③重排不做。**fork 现状=两头都露**（UI + 提示词均带 id），**有意保留**（审批靠 id 核对改/删的是哪条）。详见 `design-backup-workspace-files.md` §10.1、`upstream-analysis.md`「已放弃」表。
- 2026-09-14：**记忆 id 与备份恢复语义核实（仅核实，无代码改动）**——用户问「原版备份中没有 id 的记忆如何处理」「原版只是移除显示，故不影响恢复？」。**结论：备份里不存在无 id 的记忆，恢复不受任何影响**。①**id 由表结构强制**：`MemoryEntity.id = INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL`，实测上游导出包 49 条记忆 id ∈[1,66]、空/非正 id **0 条**（id 空洞=删除留下，AUTOINCREMENT 不复用，`sqlite_sequence` 记下次起点）。②**恢复=整库文件替换**（`moveIntoPlace` rename，非逐行 INSERT）→ id、空洞、`sqlite_sequence` 原样保留，历史消息里 158 处 `memory_tool` 的 id 引用恢复后仍指向同一条。③**无 JSON 入口**：全仓无 `AssistantMemory` 反序列化路径（只 encode 成工具结果），记忆只能来自 DB。④**对话导出不含记忆库** → 记忆只靠整库备份迁移。**唯一真实缺口（已记录不处理）**：2025-04 前上游把记忆内嵌在 assistant JSON（`598d9c91`，id 为 Uuid；次日 `d50d5cd7` 才迁 DB 表）——fork 的 Assistant 已无该字段 + `ignoreUnknownKeys=true` + `SettingsJsonMigrator` 不读 `assistants[].memories`，故极老备份的这批记忆会静默丢弃（非 id 报错，是丢失）。**上游 `08c2648b`（移除记忆 id 显示，close #1830）已核实=纯展示层 5 行删除**（仅 `AssistantMemoryPage.kt` 删 `Text("#${memory.id}")`），不碰表结构/模型/备份/工具/注入 → **不吸收**，fork 保留 `#id` 显示便于核对审批卡片与工具引用。详见 `design-backup-workspace-files.md` §十、`upstream-analysis.md`「已放弃」表。
- 2026-09-14：**上游「模式注入与提示词合并」脉络核实（仅核实，无代码改动）**——用户问「上游似乎讨论过将模式注入与提示词合并，什么背景？」。**两条线要分开**：①**已落地的合并 = 上游 `1cece24e feat: 提示书`（2025-12-17）**——把 `ModeInjection`（原仅 `{name,priority,prompt}`）、世界书 `RegexInjection`（原仅 `{name,regex}`）、硬编码 `Assistant.learningMode` 三者并成一套：引入 `PromptInjection` 密封基类（公共 `id/name/enabled/priority/position/content`）+ `InjectionPosition` 枚举（模式注入**因此才拿到插入位置控制**）+ `WorldBook`（后改名 `Lorebook` `e94709ca`）+ 助手级绑定 `modeInjectionIds`/`lorebookIds` + 统一双 tab `PromptPage.kt`（1005 行）；**最强信号=学习模式被降格**——写死布尔先删（`51cbe7ff`），`1937223b` 把它变成 `DEFAULT_MODE_INJECTIONS` 里一条普通 ModeInjection（`LEARNING_MODE_PROMPT`）。**fork 已完整具备该统一模型**（含 `a1c38afe` 同 role 注入合并等修补）；合并副作用遗留上游 #1845（两层结构致删除被引用注入后计数不减，本仓同款未修）。②**当前 open 的「再合并/替换」讨论 = issue #1542《更好的上下文管理，删除提示词注入》（维护者 re-ovo，0 评论）**：动因是「注入+世界书**严重影响提示词缓存**」，方向「**统一提示词注入和角色卡为上下文插件** + 缓存友好设计」——统一的是**数据模型**、未解决**请求形态**（注入落点打散前缀→打掉 cache）。旁证 PR `#1630`（系统提示 SHA-256 缓存 + WorkspaceReminder 改轮次尾部注入，DeepSeek 命中率 0%→75.2%）与 #1499/#1562/#1558/#1178。**与 fork 关联**：这是本仓压缩/前缀稳定性线（`design-auto-compaction.md`）的上游镜像，同属「请求前缀形态」一层；**#1542 未实现、不排期**。详见 `upstream-analysis.md`「注意点」第 8 条。
- 2026-09-14：**#1542 难度评估（仅评估，不排期）**——用户问「#1542 本身的难度?」。**分窄义与全量两档，差一个数量级**：①**窄义（缓存止血）＝小（天级）**——破坏缓存的根因是**注入位置**：`BEFORE/AFTER_SYSTEM_PROMPT` 改写 system 消息（下标 0）→ 整段前缀全废；`TOP_OF_CHAT` 废大部分；`BOTTOM_OF_CHAT`/`AT_DEPTH` 在尾部只废后缀（故「轮次尾部注入」是缓存友好方向，上游 PR #1630 即此，DeepSeek 命中率 0%→75.2%）。上游擦边动作均为止血：`3a18dae8` 删 `cur_time`/`cur_datetime`、`2d61ba95` 阶梯式截断。**fork 现存同款隐患**：默认助手 systemPrompt 含 `{{cur_date}}`（`PreferencesStore.kt:823`）+ `PlaceholderTransformer` 每请求遍历全部消息重算 → **每天从 system 位置作废整段缓存**；`cur_time`/`cur_datetime` 本仓无。②**全量（统一重写）＝大（架构级，3–6 周）**——issue 仅**草图**（正文带问号、无设计/无 PR/0 评论，仅 pinned）；涉及数据模型/持久化（三件套+`ConversationEntity` 列+`SettingsJsonMigrator` 备份兼容）/注入逻辑/UI/导入导出/存量迁移/测试（`PromptInjectionTransformerTest.kt` 1303 行重写）。**真难点不在行数**：设计未定、跨 provider 缓存语义不同（Claude 显式 `cache_control` vs OpenAI/DeepSeek 自动前缀）、**与本仓压缩线交互**（注入在 `windowMessages` 组装**之后**施加，须与检查点/区域指纹/`isSynthetic` 共存，照搬上游不可能）、角色卡统一需补 `character_book`（本仓目前不读）。**相对位置：本仓不落后、局部更靠前**——#1542 想要的缓存友好设计，压缩线已做一版（轨迹只追加/检查点固定窗口最前/区域指纹失效），Yuihub 也已落地（DSH 分段轨迹：模式注入新开独立段而非重写 system、世界书作轮次尾部补充段）。**结论：只做窄义止血，全量不排期**。详见 `upstream-analysis.md`「注意点」第 8 条、`design-auto-compaction.md` 关联段。
- 2026-09-14：**工具装配方盘点（仅盘点，无代码改动）+ 更正 097cdb90 判定**——用户问「如果 ChatToolFactory 重构，潜在的工具装配方有?」。**关键更正**：`upstream-analysis.md` 原判 `097cdb90`「零功能收益，除非出现第二个工具装配方」——**第二个装配方其实早已存在**（`GenerationHandler.kt:138` 在传入 tools 之上再叠 `buildMemoryTools`），"除非"条件已成立；但仍不吸收（结构集中非功能修复，fork 定制碰撞多）。**现存装配方**：①`ChatService.kt:814`=主工具集（搜索/本地 8 类/最近对话/工作区需 shell READY/技能安装恒注册/已启用技能/MCP 动态循环含名字净化去重）；②`GenerationHandler.kt:138`=第二装配点（叠记忆工具，上游 factory 未考虑）；③`ProviderConnectionTester:179`=测试假工具；④`LocalTools.getTools`=按 `LocalToolOption` 子装配；⑤`Model.tools`/`BuiltInTools`=provider 侧能力标志。**潜在装配方**：MCP host（远程需设备工具子集）、Agent/工作流/定时入口（各为独立生成方+需无头审批策略）、Web API 聊天（现委托 ChatService，工具集一旦分化即成为装配方——最可能打破集中式设计）。**须排除**：标题/建议/压缩/OCR/翻译走 `backgroundTextGenerationParams`（刻意不含 tools），重构须保住无工具契约。**展示侧** `ToolUIRegistry` 是 toolName 第二消费方，改命名须同步。**顺带发现可直接摘的 bug**：上游引入 `shouldUseExternalWebSearch = enableWebSearch && BuiltInTools.Search !in model.tools`，fork 为无脑 `if (assistant.enableWebSearch)`（`ChatService:815`）→「模型自带原生搜索时仍注册外部搜索工具」；**与是否做 factory 重构无关，可单独摘**。详见 `upstream-analysis.md`「注意点」第 9 条。
- 2026-09-14：**内置搜索/外挂搜索去重落地（`0b562833`）——顺藤摸出的一处漏吸收**——用户问工具装配方时发现：上游 `aac6e963`（#1666「统一内置搜索与外挂搜索行为」，2026-08-13）**在 fork 分叉点 `2c980642`（08-02）之后的 master 同步范围内，却从未移植到特性分支、批次分析也未记录**（被 2.4.14 批「已吸收核心件」概括吞掉）。**修复**：抽 `shouldUseExternalWebSearch(assistant, model) = enableWebSearch && BuiltInTools.Search !in model.tools`，两个调用点同口径——**工具注册**（`ChatService` tools buildList，原为无脑 `if (enableWebSearch)`）与**「模型不支持工具」告警**（原任一为真即告警，现按实际是否注册外挂搜索判断）；否则 Gemini/GPT 原生搜索与客户端搜索会同时下发（浪费 schema 预算 + 可能冲突）。移植上游 4 项单测。**验证**：单测 10 项全绿 + **falsify**（去掉内置搜索判断 → `built-in search suppresses enabled external web search` 即失败）；全量 408 项仅 MarkdownMathParse 既有失败；debug 装机启动正常、数据完好（215 会话；设备助手 `enableWebSearch=false` 故该分支未激活，改动由单测覆盖）。**教训入库**：upstream-analysis「注意点」新增第 10 条——「已吸收核心件」≠ 逐提交已核，对 `ChatService`/`GenerationHandler` 这类重改文件上的上游 `fix(...)` 应逐条 grep 等价。
- 2026-09-14：**工具装配集中到 ChatToolFactory（`3893912f`，上游 `097cdb90` 的 fork 适配，真机验证）**——用户要求重构。**动机**：装配原分散两处——`ChatService` 内联 buildList（搜索/本地/对话/工作区/技能/MCP）+ `GenerationHandler` 生成循环内叠加记忆工具，新增装配方（MCP host、Agent/工作流入口）须改多处。**落地**：新增 `ChatToolFactory.createTools(settings, assistant, model, workspaceCwd)` 为唯一装配点（七类工具全量，**顺序保持迁移前不变**——Claude `promptCaching` 断点打在最后一个工具上）；`GenerationHandler` 只透传、去掉 `memoryRepo`；`ChatService` 构造以 `chatToolFactory` 取代 `localTools`/`skillManager`；DI 两模块同步；`shouldUseExternalWebSearch` 随工厂迁包、其 4 单测迁入 `ChatToolFactoryTest`。**与上游差异（有意保留）**：MCP 名沿用 fork `McpNameSanitizer` 净化去重而非上游 `InvalidMcpServerNamesException` 抛错——前者允许中文等非 ASCII 服务器名（用户可见收益）。**验证**：编译通过；单测 408 项仅 MarkdownMathParse 既有失败；真机 Koin 71 definitions 无错，生成端到端跑通——**记忆 40.8K tokens 注入生效、模型调用工厂装配的 `search_web` 成功执行并渲染结果 UI**（证明装配+执行链路完整）；测试会话已清理，原库 216 会话/54 记忆 integrity_check=ok。详见 `upstream-analysis.md`「已放弃」表 `097cdb90` + 「注意点」第 9 条。
- 2026-09-14：**记忆对外 opaque id 落地（`e416e7d4`，真机端到端验证）**——用户要求实现「稳定 opaque id」。**动机**：对外暴露自增 id 有二害——删除留空洞、编号不连续（观感差）；编号泄漏记忆条数（上游 #1830 报告者实证「deepseek 把编号 28 理解成有 28 条记忆」）。**方案**：`MemoryEntity.public_id`（8 位小写十六进制随机，32 bit）+ DB **27→28**（`ALTER TABLE` + `lower(hex(randomblob(4)))` 就地回填，纯 SQL 不逐行）；`AssistantMemory.id: Int→String` 并加 `@Transient sortKey`（自增主键，仅供注入选最新 60 条、不序列化）；仓库按 token 查/改/删；工具 schema `id` integer→string + 描述「copy exactly, not a count」；提示词注入 token；UI 审批预览按 token 查、记忆页新建哨兵改为 `id==""`。**兼容**：历史消息里的旧自增编号引用（如 "12"）在 token 查不到时按 id 回退，故老对话的 edit/delete 仍可用。**真机实证**（消除唯一未知项——模型能否稳定用 token）：迁移 54 行全获唯一 token（零空值、integrity ok）→ 请求日志带 `"id":"b135fd7a"` → 模型回 `{"action":"delete","id":"b135fd7a"}` → 审批卡正确显示目标内容 → 批准后该行确实删除（55→54）。**对上游 #1830 的重新评估**：上游「隐藏显示」是半截修复（提示词仍注入自增编号），本方案同时根治观感与泄漏，且**随机 token 无「空洞」概念=永不需要重排**。**测试数据已清理**（含一次误删：清理用的 `LIKE %teal%` 误匹配了含 `>=stealth` 的真实记忆，已从安装前备份恢复并核验内容一致）。单测 +6（含 token 非序号 / legacy 数字回退 / 未知 token no-op），全量 414 项仅 MarkdownMathParse 既有失败。详见 `design-backup-workspace-files.md` §10.1。
- 2026-09-14：**记忆创建审批开关（`49324ded`，真机验证）**——用户要求「为创建记忆也增加可选开关，默认关闭」。前一批（`a2add734`）的分档是 create 恒定放行；本次把 create 也做成 opt-in：`Assistant.memoryCreateApproval`（默认 false）+ 记忆页开关（与修改审批同款）+ 6 语言文案。两个开关**相互独立**（可只拦新增、不拦修改）。判定抽为纯函数 `memoryToolNeedsApproval(assistant, action)`：create→createApproval、edit/delete→modifyApproval、未知/缺失→按 modifyApproval（宁可多拦，不被当成 create 蒙混），工厂 wiring 调用它。**顺带修测试缺陷**：原 `MemoryToolsTest` 在测试里自己复刻 needsApproval 的 lambda 判定（等于没测到生产逻辑）——拆为 `MemoryToolApprovalTest`（直接测生产函数，create 矩阵/独立性/兜底）+ `MemoryToolsTest`（只测 JSON→action→判定的接线）。单测 +7，全量 421 项仅既有 MarkdownMathParse 失败；falsify 确认 create 判定写死即失败。真机：开关默认关、开启后 DataStore 落 `memoryCreateApproval:true`、关闭后回 false；测试数据已清理（含删掉一个 probe IME 时误留的 content='test' 记忆），库恢复 54 记忆/215 会话、integrity ok。
- 2026-09-14：**记忆页一键复制 opaque id（`e2bdcb3d`，真机验证）**——需求：id 是随机 opaque token、不便记忆，提供复制以便用户在对话里引用某条记忆（如「把 e6a5d3c4 改成…」）。实现：记忆项 id 行改可点（`#token` + `HugeIcons.Copy01` 图标整行点击）→ `writeClipboardText`（复用 `ContextUtil`）+ `LocalToaster` 弹「已复制」；6 语言新增 `assistant_page_copy_memory_id`（contentDescription）；复制图标 14dp + onSurfaceVariant 弱化。真机：点击后系统剪贴板预览显示 token、toast「已复制」弹出；测试数据已清理，库恢复 54 记忆/215 会话、integrity ok。
- 2026-09-14：**修复工具循环重复创建同内容记忆（`0b1d229f`，真机端到端验证）**——用户报告「创建记忆时会反复创建同一内容」。**排查（真机复现 + 落库记录核对）**：①提供商会下发**多条内容相同、toolCallId 不同**的并行调用——实测一次「保存我的偏好」被下发 2 条 memory_tool create（截图两行「创建了记忆」；DB 里 `call_4413721`/`call_5133514` 参数完全相同，一条 approved 一条 denied）；②放大因素=注入模型的 `<memories>` 是**生成开始时的快照**（`ChatService` 取一次），工具循环中新建的记忆当轮不可见 → 后续 step 对同一事实再 create（实测跨 step 出现第 3 次）。**修复分两层**：**①GenerationHandler 批内**——新增 `toolCallSignature`（工具名 + 参数递归按键名排序后归一；**toolCallId 不参与**，因重复下发的两条恰是 id 不同内容相同），批内重复调用只执行一次、其余复用首个结果，且每个 toolCallId 都回填结果以满足协议；对**所有工具**生效（覆盖 shell/write 等非幂等副作用）。**②MemoryRepository.addMemory 跨 step**——同助手已存在相同内容（trim 后比对）则复用、不新增；**选去重而非「每 step 重注入记忆列表」**，因后者会让 system prompt 每步变化、打掉前缀缓存（design-auto-compaction 前缀稳定性约束）。**验证**：单测 +11（ToolCallSignatureTest 6 + MemoryRepositoryTest 5），全量 431 项仅 MarkdownMathParse 既有失败；**真机关键实证**：同一请求两步各执行一次 create，第 2 步日志出现 `addMemory: duplicate content, reuse #9f1ee715`、记忆数 54→55（只 +1，修复前 ≥+2）。测试数据已清理（215 会话/54 记忆/integrity ok）。**遗留（未处理）**：用户报告的「AI 回复偶尔重复」未定位到独立成因——本轮只确认了工具侧重复；若再现需抓具体会话的落库 JSON 比对（不排除同一批 tool_call 重复下发导致的回复文本重复）。
- 2026-09-14：**发送窗口每步重算（`468a25d6`，真机 A/B 验证）——工具结果不回传的回归修复**——用户报告「创建记忆反复创建同一内容／AI 回复偶尔重复」，侧窗排查定位并用代码+日志独立复核。**根因**：发送上下文取自**冻结在回合初**的 `windowMessages`，而工具执行发生在本回合内、窗口之后 → 模型下一步**看不到自己刚发出的 tool call 及其结果**，于是重复调用同一工具。**回归点 = `ce311156`**（09-13 压缩检查点修复）：改前 `messages.partitionCheckpointMessages()`（live，正确），改后 `(windowMessages ?: messages)...`（冻结快照）；常规发送路径必传非 null，故每次用工具的回合都命中。影响面：**所有需工具结果的链路**（搜索/工作区/MCP/记忆），自引入起（含 v1.0.4）。**修复**（用户指定方案）：`Conversation` 加可重算的 `CompressionWindow(checkpointMessage, boundaryIndex)` + `applyTo(messages)`；`GenerationHandler` 参数由冻结列表改为该描述、请求组装处每步对 **live messages** 重算（检查点前缀替代边界前历史、边界后取 live）；`ChatService` 改传 `conversation.compressionWindow()`。`requestWindowMessages()` 退化为回合初一次性组装（当前生产无调用方，保留供将来「仅导出 AI 所见上下文」），注释已警示生成路径不要用。检查点两条约束（固定最前／不被 limitContext 截断）不变。**验证**：新增 4 项单测（重算带上回合内工具消息/边界前仍被替代/无检查点返回 null/边界越界保守回退），全量 434 项仅 MarkdownMathParse 既有失败。**真机 A/B（同一场景）**：修复前（cyan）step#0 发 create → step#1 **重发相同 create**、prompt_tokens 47335→47357、落库 2 条；修复后（magenta）step#0 发 create → step#1 **仅正文不再重发**、prompt_tokens 41088→**41150**、落库 **1 条**。**注意**：上一轮（`0b1d229f`）的批内+跨步去重仍保留为**兜底**（模型仍可能因记忆列表是回合快照而重发，且内容可能改写，精确匹配挡不住）——但真问题是上下文不回传，本次才是治本。**已同步**：`design-auto-compaction.md` §4.1 补「发送层必须每步重算，不能冻结成回合快照」约束（含回归来源）。
- 2026-09-14：**日志可选落盘到 files/logs（`39097746`，真机端到端验证）**——采纳建议：给应用内日志加「导出到文件」。**动机**：内存日志上限 100 条且进程退出即失；**logcat 单条 ~4KB 截断**使 40K token 级请求体不可见（排查 windowMessages 缺陷时正卡在此，只能靠落库 JSON + prompt_tokens 差值间接推断）。**实现**：`Logging` 加文件 sink（`files/logs/rikkahub-logs.txt`，**纯文本块**而非 JSONL——请求体本身是大段 JSON，再转义进单行难读，排查可读性优先、正文原样保留）；单文件 4MB 上限超限轮转 `.1.txt`；IO 失败静默吞（调用方可能是网络线程）。**两个开关均持久化**（Settings 三件套）：`logToFileEnabled` + `recordRequestsEnabled`——**请求记录此前纯内存**，不持久化则「导出到文件」开着但重启后文件恒空（静默失效，实测踩到）。**关键坑（实测）**：启动时**不能同步读 `settingsFlow.value`**——DataStore 加载前它是 `Settings.dummy()`（全默认 false），会得到「开关开、文件空」；改用 `settingsFlowRaw.collect` 持续跟随后验证通过。**UI**：LogPage 新增「导出到文件」卡片（显示当前文件大小 + 清空）+ 顶栏分享按钮（FileProvider，`file_paths` 增 `logs/`）；6 语言 3 key。**验证**：common 单测 4 项（正文逐字不转义/缺省字段不留占位/TextLog/错误行），全量 434 项仅 MarkdownMathParse 既有失败；**真机**：开两开关 → 重启 → 发一条消息 → 落盘 167,634 字节，含 `Content-Length: 162904` 对应的**完整请求体**（正是 logcat 截断看不到的部分）。测试数据已清理（216 会话/56 记忆/integrity ok）。
- 2026-09-14：**两层代理落地（`3e3d4031`/`b45f6a9d`/`a066df81`/`89a07826`，待用户真机验证）——「per-provider 为主 + 可选全局兜底」**——①**模型层**（ai 模块）：`ProviderProxy` 扩 `Inherit`（跟随全局，**新 provider 默认**）与 `Socks5`，`None` 语义收窄为「显式直连」（旧 JSON 显式 `"none"` 反序列化仍为直连，**已存在 provider 行为零变化**）；`configureClientWithProxy` 四路矩阵（Inherit→原 client/None→`NO_PROXY`/Http/Socks5→自配），ai 模块对全局设置**零感知**。②**全局层**（app）：`GlobalProxySetting`（结构化字段 + PreferencesStore 三件套）+ DI 共享 client 挂 `SettingsProxySelector`（全局→系统→NO_PROXY）与 `SettingsProxyAuthenticator`（HTTP `Proxy-Authorization`）；**SOCKS5 认证**须走 JVM `java.net.Authenticator`（socket 层，OkHttp `proxyAuthenticator` 覆盖不到）——`RikkaProxyAuthenticator` 全局单例 + `SocksCredentialRegistry` 多源查表（全局 + 各 provider，同名 key provider 覆盖），随 `settingsFlowRaw` 热更新。③**UI**：设置→偏好→网络 全局代理页（无/HTTP/SOCKS5 + 主机/端口/用户名/密码）+ 提供商代理 tab 扩 4 选项 + 6 语言。**覆盖面盘点**（原设计风险①，见 `design-proxy-two-layer.md` §四）：真正复用 DI 共享 client 的仅**搜索**与 **ASR**（全局层生效）；余额查询走 `OpenAIProvider` per-provider 路径；TTS/MCP（`McpManager` 自建 client）、S3/WebDAV（独立 Ktor client）**不受影响**。单测 18 项全过（ai 8 + app 10），UI 真机渲染已验证；**HTTP/SOCKS5 实际连通需真实代理服务器，待用户验证**。
- 2026-09-15：**tool_outputs 移出备份（用户拍板选项①）**——`files/tool_outputs` 是**会话级临时产物**：`RikkaHubApp.cleanupToolOutputs()` 每次启动无条件 `deleteRecursively()`，而恢复流程末尾必然重启（`exitProcess(0)`），所以打包它「备份了也会被删」，纯徒增 zip 体积。改动：`addAppFilesToZip` 与空间预检 `collectBackupInputs` 的 FILES 分支去掉 `TOOL_OUTPUTS`（打包内容 = upload/skills/fonts）；**恢复侧保留旧备份兼容分支**（`restoreFlatEntry`，注释标 legacy，仅老包命中）；`BackupItem.FILES` 注释同步。**接受悬空引用**——历史消息 `/tool_outputs/<id>.txt` 打不开属预期，截断消息已内联前 4KB 预览（`TOOL_OUTPUT_PREVIEW_CHARS`）兜底；`cleanupToolOutputs` 本身行为不变（不改成保留策略）。sync 全包单测 29 项通过，编译无新告警。
- 2026-09-15：**日志脱敏落地**——`common/Logging.kt` 加 `SENSITIVE_HEADERS`（`authorization`/`x-api-key`/`x-goog-api-key`/`proxy-authorization`，覆盖三家 provider + 代理认证）+ `Map<String,String>.redacted()` 扩展；`formatLogEntry` 在写出前对请求/响应头脱敏（值替换为 `[redacted]`，非敏感头如 `Content-Type`/`anthropic-version` 原样保留）。**双重脱敏**：`RequestLoggingInterceptor` 在**源头**即对 `request.headers.toMap()` 脱敏后才进 `RequestLog`——内存 UI（`RequestLogDetail` 直接渲染 headers）与文件 sink 双覆盖，任何一条路径都不会明文暴露 API key。LogFormatTest 从 4 项扩到 11 项（新增 7 项：三个 provider 凭据头逐一验证 + `proxy-authorization` + 非敏感头原样 + `redacted()` 扩展单元 + 响应头也脱敏）。**保留策略未做**（文件已有 4MB+1 代备份轮转，峰值 8MB，按体积有界）；请求体里的对话内容仍明文（排查需要完整报文）。
- 2026-09-15：**删除远端 master 镜像分支（仅本地保留）+ 表述修正**——用户决策：远端（origin = `androidrik/rikka2-my_feature_open`）的 `master` 分支（上游 ff-only 镜像）**删除**，避免仓库出现「镜像分支与开发分支并存」的误导。`origin` 现只剩 `imperfect-customization-and-refinement` 一个分支（也是默认分支）。**本地 `master` 保留**并把上游跟踪从 `origin/master` 改为 `upstream/master`（`git branch --set-upstream-to=upstream/master master`），同步流程简化为**三步且不再推送**：`git fetch upstream && git checkout master && git merge --ff-only upstream/master`。**表述修正**（原指令含 `git push origin master` 已失效）：README 分支结构表（`master` 标注「仅本地保留/不再推送到 origin」）+ 项目简介/上游关系两处、`docs/upstream-analysis.md`（状态行 + 同步方式代码块 + 待吸收表行）、`docs/handoff-2026-09-04.md` 现状快照。**远端 171 个上游历史 tag 未动**（ff-only 同步链依赖）。
- 2026-09-15：**Web 服务器「局域网地址」不再拿 localhost 冒充（真机验证）**——用户报「局域网地址也显示为本机地址」。**根因（真机复现）**：`state.address` **只在 mDNS 注册成功回调里赋值**（`WebServerManager.kt:90-95`），而 `NsdServiceRegistrar.getLocalIpAddress()` 读的是 **WiFi IP**（`WifiManager.connectionInfo.ipAddress`）——WiFi 关闭/移动数据下返回 null → register 提前 return → 回调永不触发 → address 恒 null；UI（`SettingWebPage`）与通知（`WebServerService`）当时都写 `address ?: "localhost"`，于是「本机地址」的值被显示在「局域网地址」行，误导用户复制一个外部设备打不开的 URL。**真机证据**：WiFi 开=`Creating JmDNS ... address=/<local-ip>` 页面正常；WiFi 关（只留 rmnet_data0）=`E NsdServiceRegistrar: Failed to get local IP address` 页面/通知双双变 `http://localhost:8080`（服务其实已在 0.0.0.0:8080 监听成功，只是地址取不到）。**修复（用户选②）**：取不到时不显示假地址——设置页该行改为不可点击的「不可用（未连接 Wi-Fi）」占位（新增 string 6 语言），通知同理退化为 `address → mDNS hostname → 占位文案` 三级。**未做**：① 把 IP 探测换成遍历 `NetworkInterface`（可覆盖热点/以太网）② 把 address 赋值解耦出 mDNS 回调——均按需再议。真机双态验证通过（WiFi 关→占位；WiFi 开→`http://<local-ip>:8080` + mDNS 行）。
- 2026-09-15：**记忆注入打分校准 + token 预算截断落地（`69453f5e`，真机验证）**——设计见 `design-memory-scoring.md`。**背景（实测破前提）**：此前是「全量注入」——`MAX_MEMORY_INJECT_COUNT=60`/`MAX_MEMORY_TOTAL_CHARS=100_000` 两个上限在实测数据（54 条/60,239 字符）下**都没触发**，每次请求塞进约 **4 万 token**；「超 60 条才触发」是假安全感（条数上限管不住体积）。**实现**：①**DB 28→29** 加 `importance`(默认 0.6)/`updated_at`，存量行 `updated_at` 回填**迁移时刻**（回填 0 会让老记忆被当成极旧而静默沉底）；②打分 `importance × (0.55+0.45×30天半衰)`，**分档量化后桶内按 sortKey 排**（输出顺序固定 → 前缀字节稳定，不因分数漂移打掉缓存）；③**预算按 token**（复用 `ContextLength` 启发式、抽出 `String` 重载共用口径；不引 tokenizer），超限**跳过而非 break**（长记忆不堵死后继）、首条超预算也入选（防空集合）；④`importance` 由模型在 `memory_tool` 里给（schema + 分档描述引导），**记忆页滑块可覆盖**；edit 同步刷新 `updated_at`（否则「改了没用」）；⑤超 200 条改**按分数淘汰**（与入选口径一致，否则重要老记忆被新次要记忆挤掉）；⑥预算 `DEFAULT_MEMORY_BUDGET_TOKENS=8_000` 存 Settings 三件套。**两个实现坑**：①**分档量化必须就近取整**——`importance×1.0` 恰为整数、而模型爱给 0.9/0.8/0.7，向下取整会让最常见值正压桶边界（实测 0.8 过一分钟就从桶 8 掉到 7），改为 `floor(x+0.5)` 后落在桶心；②我首版「长记忆不堵死」的测试把长记忆给了最高分，它合法地排第一并被首条兜底放行——**测试布局错了**，须让长记忆分数居中才能真正测到 skip。**真机验证**：迁移 56 行全回填（零 NULL/零 0）、app 无崩溃、Room schema 29 与 DDL 一致；**模型实际回 `{"action":"create","content":"User's favourite colour is teal.","importance":0.7}`**——读了 schema 且落在「普通偏好」档（非默认 0.6、非堆高 0.9），设计里标注的「最大不确定项」初步解消。单测 466 项仅 `MarkdownMathParseTest` 既有失败（stash 双向确认无关）。
- 2026-09-15：**上下文构成诊断日志（`e907d2e4`，B2 精度，真机验证）**——起因：用户问「41K 消息的构成与比例」，我第一次拿**全表 55 条记忆**去算，得出「记忆占 77.5%」的**错误分解**（该助手实际只有 7 条，注入按 `assistant_id` 过滤——**教训：分析单个请求的构成必须先按作用域过滤，别用全表数据**）。用户随后用「同助手关 MCP」对照实验给出 8,166 → 定位真因。**真因=MCP 工具 schema**：`mt管理器` 26 工具 + `solab逆向工具台` 28 工具 = 54 个逆向工具的 schema 约占 **33K token**（日志实测 mt 19288t + solab 13952t），而记忆注入仅 2.6K。**落地**：①`ai/util/ToolSchemaSize` 按**当前 provider 的实际形状**估算（OpenAI `{type,function}` 包装 / Claude `input_schema` / Google `functionDeclarations`+剥 `enum`/`format`/`const`）——三个形状体积差在 10% 内，但「精确」必须绑定 provider 才有意义；②`ensureArrayItems` 从 `GoogleProvider` 提升到 `ai/util`（单一定义，三处调用点改 import），使估算与真实发送共用同一形状变换；③`ChatToolFactory` 按来源分段打一条 `Log.i`（MCP **按服务器分别成段**，否则看不出是哪一个）；④`ChatService` 每回合记一条记忆注入（条数/占用/预算）；⑤均用 `Log.i` 而非 `Logging.log()`（后者进 100 条内存环形缓冲会被冲掉）、均**每回合一次**（不在 step 循环内）。**精度验证**：估算合计 39,789 vs 实测 `promptTokens` 41,188（**误差 3.4%**），差额为 chat 模板包装/请求级字段等估算口径外开销。日志实例：`total=68 tools ≈36542t (shape=OpenAI) → memory 1/633t | search 1/438t | local 2/505t | conversation 2/320t | workspace 5/863t | skill-install 2/393t | skills 1/150t | mcp:mt管理器 26/19288t | mcp:solab逆向工具台 28/13952t`。**顺带印证**：该请求 41,187 中 40,960 命中缓存（99.4%），故大 prompt ≠ 大开销——前缀稳定（记忆按 sortKey 输出、分档量化）正是为了保住这一点。
- 2026-09-15：**压缩参数可配置化 + 压缩配置档（设计讨论，未实现）**——用户提案「为三个压缩参数提供自定义数值填写，把固定值转为可选内置方案」。**核实发现与提案的出入**：那三个值（阈值 80/保留 40/目标 2000）**只覆盖自动路径的一半**——手动压缩对话框（`CompressContextDialog`）的保留比例与摘要目标是**硬编码**（`remember { mutableIntStateOf(40/2000) }`）且**每次打开都重置**，与设置里的值无关联。（设置侧三件套已齐、无迁移。）**已定三决策**：①**三个数值是唯一真相源**，档只是填充快捷方式（收益：删除档不改变行为=删除安全）；②自定义档支持**增/改/删**、临时数值不持久化；③**阈值只属自动**、手动只暴露保留+目标（UI 按路径分组）。**五项决策全部已定**（无阻塞）：①三数值为唯一真相源；②自定义档可增改删、临时值不持久化；③阈值只属自动；④**档只带 2 个数**（保留+目标，不含阈值——用户理由更准确：手动压缩的触发**就是用户自己的判断**，阈值在手动路径上根本不构成概念）；⑤边角=内置档不可删/阻止重名/允许数值重复/自定义档上限 20。**成本中-低（1–2 提交）**，大头是档管理 UI（可复用 `SettingThemePage` 的 `CustomThemeItem`/`CustomThemeEditSheet` 形态，自定义主题与"用户命名列表项+增删改"同构）。设计稿 `docs/design-compress-presets.md`。**2026-09-15 已实现并真机验证（`33c2978c`）**：设置页档选择器 + 数值输入、档管理（增/改/删、上限20、阻重名）、手动对话框初值读设置且无阈值、6 语言 14 key；单测 +14；真机验证档位填入/创建落库（protobuf 解出 `compress_presets`）/状态指示。**实现期修一个缺陷**：「自定义」原为 onClick 空的 `FilterChip` = 死点击（用户实测发现）——按设计它本质是状态指示（真相源是数值、无值可改），改为标签行文字后缀。
- 2026-09-15：**重生成语义评估（仅设计，未实现）+ 清理三处过时限制**——用户问「用户重生成也加分支候选、重生成前内容落在其分支；重生成区分『仅本条及对应回复』与『含后续所有对话』的难度」。**核实要点**：①`regenerateAtMessage` **已按角色不对称**——助手消息重生成走「追加候选+切 selectIndex」（旧回答**已在分支里**、下游保留），用户消息重生成走 `subList(0, indexAt+1)` **硬删除下游**（落库），故「重生成前内容落在其分支」**助手侧本已成立**，真正无归宿的是用户重生成时被删的尾巴；②`editMessage`（1399-1432）**只给该节点追加候选、完全不动下游** → 「编辑后下游处于旧语境」**在编辑那刻就已产生**，与重生成无关，故**提示判据应是「是否编辑过」而非角色**；③两个 scope 的原语都现成（截断 / 候选追加入写回），交叉套用即可，**成本约 1–2 提交**。**用户决策**：**不按角色区分 scope**（编辑后想只修正这一段，与角色无关）；**仅 scope B（含后续）要求确认**（文案直接复用上游 `d47d13a6` 那版「此消息下方所有消息会被删除」）；原设想的**「自动分支」改为 snackbar 撤销**——fork 的代价不对等（**物理复制附件**：`FilesManager.kt:112-130` 逐字节拷贝 → 带图会话空间翻倍；**副本丢压缩检查点**：`createForkConversation` 未搬 `compressionSummaries`）；撤销沿用 `HistoryPage` 删会话的「内存持有→删除→撤销还原」模式（无 schema、无拷贝、无列表膨胀）。**§五 待解冲突**：截断后立即生成会让「撤销」还需撤掉新回复（且与 #1812 半截草稿交叉），建议 v1 取「撤销仅覆盖未生成窗口」，避开与生成回路/节点索引三方纠缠。**不做**：同会话内树模型（线性 `messageNodes` 无路径表示，需动 103 处引用 + 索引对齐写回/前缀 diff/边界索引等核心不变量，架构级数周）。设计稿 `docs/design-regenerate-scope.md`。**顺带清理三处过时描述**：`handoff:74`、`roadmap:556/558` 的「已知限制：重新生成边界内消息未触发失效」——该限制写于**指纹校验之前**，`bbba9ba8` 的区域指纹（含 `CompressionWindowTest` 的 regenerate 用例）已覆盖，属**假限制**，会误导后续排查。**另发现死参数**：`regenerateAtMessage(message, regenerateAssistantMsg)` 第二参数两个调用方都用默认 true，`else` 分支不可达。
- 2026-09-15：**重生成 scope 可选 + 撤销落地（`ad3543b2`，用户手动验证通过）**——设计 `docs/design-regenerate-scope.md`。**核心语义修正（用户提出，核实后确认更准）**：scope **不按消息角色区分**——`editMessage`（1399-1432）只给该节点**追加候选、完全不动下游**，故「编辑后下游回答处于旧语境」**在编辑那刻就已产生**，与重生成无关；因此提示判据应是「**是否编辑过**」而非角色。**实现**：①`RegenerateScope{THIS_ONLY（非破坏）/ INCLUDE_FOLLOWING（破坏）}`，下游为空时两者等价→直接执行 A 不打扰；②目标节点 = **用户消息的回复**（下一节点）/ **助手消息自身**——共同点是「基线不含目标节点」，故新回复**总是作为该节点候选追加**（与 `updateCurrentMessages` 索引写回一致）；③scope B 弹确认，含「**先导出再继续**」；④截断后弹「**已移除 N 条 · 撤销**」（用 sonner 的 `TextToastAction`，**无需替换页面 scaffold**）；撤销先 `stopGeneration` 再 `take(截断后长度)` 拼回尾巴——**截断后新产生的回复与 #1812 半截草稿一并撤掉 = 回到截断前**；⑤纯函数抽到 `data/model/RegeneratePlan`（可单测 14 项：目标节点/基线范围/截断计划/撤销还原/多候选/边界）；⑥**清死参数** `regenerateAssistantMsg`（两调用方都用默认 true，`else` 分支不可达）；⑦默认 scope 保持 INCLUDE_FOLLOWING → **web/API 调用方行为零变化**。单测 +15（含检查点失效与截断交叉：截断致失效、撤销致恢复=可逆）。**「导出能留存但不能回退」已入档**（导出落点是临时目录+分享面板、`deleteTempFiles` 启动即清、无回填路径）——故确认弹窗里是「**先导出再继续**」而非「导出后可回退」。**不做**：同会话内树模型（线性 `messageNodes` 无"路径"表示，需动 103 处引用 + 索引对齐写回/前缀 diff/边界索引，架构级数周）。**排查教训（UI 自动化）**：验证撤销时多次误判「tap 没生效」——真因是 ①抽屉动画时序不稳 ②toast 6s 过期而我"截图验证→下一轮再点"跨了模型回合 ③sonner toast 在**独立 window**、`uiautomator` 看不到（只能靠截图按像素找按钮）。**且我一度误把 SoLab（另一应用）的 MCP 界面当成本仓功能**——本仓**无 MCP host 模式**（只有 MCP 客户端配置）。教训：设备上多应用并存时，操作前先核对 `dumpsys window` 的 `mCurrentFocus`。
- 2026-09-16：**侧边文件栏内联文件管理评估（仅设计，未实现）**——用户问「允许侧边的文件栏可以管理文件的难度」，随后要求「落成设计稿，先不实现」。**核实要点（提问预设与实况有出入）**：①侧滑文件栏（`FilesPicker`，`ChatPage.ChatFilesPickerSheet` 以 `ModalBottomSheet` 呈现）**根本不做文件浏览**——只有附件选择（拍照/相册/视频/音频/文件）+ 工作区绑定 + 扩展/压缩入口 + cwd 选择 + 一行工作区信息与**两个跳转按钮**；即「管理文件」已以**跳转 `WorkspaceDetailPage`** 的形式存在，本设计要做的是**内联**。②**完整文件管理器已存在**：`WorkspaceDetailPage.kt`（907 行）＝双存储区（`FILES`/`LINUX`）+ 路径栏 + 上级/刷新 + 文件卡片（进入目录/文本进编辑器/图片预览/其他导出后打开/删除含确认弹窗/导出）+ 导入（SAF）+ 导出到目录（SAF 目录授权）+ 终端入口。③**逻辑层几乎齐备**：`WorkspaceFileSystem` 已有 `list/readText/writeText/importBytes/delete/move/glob/grep`，`WorkspaceRepository` 已对外暴露全套（含 `deleteFile`/`moveFile`）。**三个缺口（都不在"能力"上）**：`moveFile` **未接线**（重命名/移动无入口）、**无 `mkdir`**（创建目录需补 workspace 模块）、`WorkspaceFilesPage`/`WorkspaceFileCard`/`WorkspacePathBar` 是 `private`（复用须先抽共享组件=**主要工作量**）。**核心设计=抽取共享文件浏览组件而非复制**（与 `BackupItem` 枚举统一/`BackupArchiveEngine` 抽取/`ChatToolFactory` 集中装配的既有去重手法同源，否则详情页与侧边栏两份 UI 必然漂移）；两侧差异用「宿主策略」适配（sheet 紧凑→`DropdownMenu` 收纳、隐藏区域切换、可省导出到目录、打开前先关 sheet）。**范围决策**：先**只做 `FILES` 区**（`LINUX`/rootfs 危险面大、价值低，仍走详情页）；**分档成本**：档1 内联浏览/打开=中、**档2 +删除/导入/导出=中（建议首批，逻辑零新增）**、档3 +重命名/移动=中、档4 +新建=中-高。**风险**：两份 UI 漂移（最高优先）、`deleteFile` 递归语义须保留确认、sheet 空间、SAF 流程必须整套复用（不能只搬列表 UI）、点开文件前先关 sheet、缩略图同步 IO、路径安全不绕开 `resolvePath`。设计稿 `docs/design-side-file-panel.md`（含 §八 四个待确认）。
- 2026-09-16：**收藏管理改造评估（仅设计，未实现）**——用户反馈「收藏无法管理」：**核实后更正为三点**（用户随后也确认）：①**可以删除**（`SwipeableFavoriteCard` 侧滑删除 → `vm.removeFavorite`，能力可用）②**撤销有点问题** ③**侧滑难发现**。**定位到两个真缺陷 + 一个可用性问题**：**A 撤销时序竞态**——`FavoritePage` 里 `getEntityByRefKey`（挂起取实体）→ `removeFavorite`（内部 `viewModelScope.launch` = **fire-and-forget 逃逸**）→ `showSnackbar` 等用户点撤销 → `restoreFavorite`（又 launch）；②④逃逸导致时机不确定，极端下 **upsert(REPLACE) 先于 delete 落库 → 撤销被删除覆盖**（**与 regenerate 撤销同族：「动作不等结果、却立刻依赖结果」**；`HistoryPage` 同形状但无撤销方向状态残留故未暴露）；**B 撤销后滑动状态不重置**——`dismissState` 未与 item 绑定 key、`LazyColumn` 按 id 复用节点 → 恢复后 `currentValue` 仍为 `EndToStart`、`LaunchedEffect` 值未变不再触发 → **卡片停在"已滑出"位，视觉上像撤销失败**（数据其实已恢复）；**C 入口隐蔽**——`enableDismissFromStartToEnd=false` 只允许右往左滑，卡片无删除按钮/长按菜单/无 affordance。**数据模型核实（决定"文件式"难度）**：`FavoriteEntity` = `id(=refKey)`/`type`/`ref_key`(**UNIQUE**)/`ref_json`/`snapshot_json`(**恒空**)/`meta_json`(**JSON blob**)/时间戳——**层级、用户可编辑名、移动语义三要素全缺**；且 `FavoriteType.MESSAGE` **零写入**（**实测设备库 `SELECT type,count(*)` → 仅 `node|2`，零 message**，可视为死类型）。**用户决策：采纳轻量替代（标签+筛选），不做层级目录**。理由：`ref_key` UNIQUE 与"一个收藏多归属"冲突（改复合唯一=迁移）、"重命名"无对象（title 是会话标题快照）、层级导航是全新 UI；标签筛选以更低成本覆盖"分类"真实需求且**与助手 `Tag` 先例同构可复用**。**关键设计=标签存 `FavoriteMeta.tags`（`meta_json` blob）→ 零迁移**（`JsonInstant` 有 `ignoreUnknownKeys`，旧行缺字段自动默认空；与 settings 子对象随 JSON 自动持久化同源）；**⚠️ 必处理坑**：`buildFavoriteEntity` 每次从 target **重建 meta** → 「取消再收藏」会丢标签，须从 `existing` 保留 tags。**分两批**：批次 1（低，约 1 提交）=修 A/B/C + 排序可切换（现固定 `created_at DESC` 写死在 DAO；**排序在 VM 侧做**——SQLite 不允许参数化 `ORDER BY`）+ 可见删除入口；批次 2（中，约 1 提交）=标签+筛选 chips。设计稿 `docs/design-favorite-management.md`（§八 三个待确认：批次划分/标签存储方式/排序维度）。
- 2026-09-16：**对话导入导出（自家格式往返）评估（仅设计，未实现）**——用户问「允许导入导出的对话（非图片形式）的难度」。**核实要点**：①**导出已有三形态（Markdown/HTML/PNG）但都不能回填**（无解析器；PNG 即用户所说"图片形式"）；②**导入已有三种全是外部格式**（`ImportExportTab` 的 `local` 整库 zip / `chatbox` json / `cherry` zip），**本应用自家格式的对话往返不存在**；③**基础设施全现成**（这是难度低的原因）：`Conversation`/`MessageNode`/`UIMessage` **均 `@Serializable`**（`InstantSerializer` 已就绪）、`data/export/ExportSerializer.kt`（205 行，`ExportData(version,type,data)` + export/import 接口对，**目前只用于注入/世界书**）、`LorebookSerializer` 已示范**"自家优先+外部兜底"**（`tryImportNative`→`tryImportSillyTavern`）、`forkConversationAtMessage` 有**换新 id 写法**、`AssistantPicker.kt`（247 行）有**助手选择 UI**、`RestoreConfirmDialog` 是天然挂载点、`part.encodeBase64()` 已在 Markdown 导出用（二阶段复用）。**用户三项决策**：①**导入即新建、全部换新 id 永不覆盖**；②**一阶段不导出附件**（顺带消掉"附件跨设备路径失效"这个最麻烦的问题），二阶段再定；③**导入时允许用户选择目标助手**。**换 id 清单（核实后确定）**：`Conversation.id`/`MessageNode.id`/`UIMessage.id` **都要换**——因 **FTS 表存 `node_id` 与 `message_id`**（与 `conversation_id` 并列），不换 message.id 会在两次导入后产生重复；但 `toolCallId` 等**批内引用不要换**（节点内自洽、整批搬即自洽）。**⚠️ 两个实现陷阱**：①**换 id 必须在 `existsConversationById` 检查/插入之前**（否则"换了 id 却因旧 id 命中被 skip"，换 id 白做）；②`forkConversationAtMessage` **只换 node.id 没换 message.id**，**不能直接照抄**，须加 `messages.map { it.copy(id = Uuid.random()) }`。**助手选择的关键发现**：现状 `restoreFromChatBox` 是**隐式导入到当前助手**（`assistantId = settings.value.assistantId`，UI 无选择入口）→ 本设计是**改进而非新建**；但 **`AssistantPicker` 不能直接用**——`AssistantState.setSelectAssistant` 会**写 `settings.assistantId`**（改用户当前助手），须把 **`private` 的 `AssistantPickerSheet` 提为可见**并只用其 `onAssistantSelected` 回调（**零副作用纯选择器**）。落点建议=**`RestoreConfirmDialog` 内联一行「导入到：[助手▾]」**、默认值=当前助手（零回归）。**决策 1 的连带后果**：换新 id 后**重复导入得到多份副本**（符合"导入即新建"语义，文案需说清）；`existsConversationById` 的 skip 分支**永不命中**。**⚠️ 更正一个我的错误判断**：我原写「不导出 `compressionSummaries`（带了也无效应）」——**核实后发现相反**：`activeCompression()` 里 `val fingerprint = checkpoint.areaFingerprint ?: return checkpoint`，即**指纹为 null 时检查点仍生效**（退化为「仅边界存在性校验」，`Conversation.kt:116`）。所以「带检查点」不是无效应，而是**两种可选策略**：**A 带+用 `areaFingerprintFor(index)` 重算指纹**（该 API 是 public，换 id 后重算即有效，导入后立刻保持"压缩后"的上下文大小）／**B 不带**（用户决定）→ **导入后无检查点、发送全量历史**。**用户反问「但是上下文长度问题呢？」正指出 B 的代价**：兜底只有 `limitContext(assistant.contextMessageLimit)`，而**该默认值是 0 = 不限制**（`Assistant.kt:29`，`limitContext` 在 `limit<=0` 时直接返回原列表 `Message.kt:336`）→ **目标助手未显式配过上限时，长会话导入等于全量发送**（超窗报错或被静默截断/计费暴涨）。**用户拍板选 A（带检查点 + 重算指纹）**。**A 的实施清单（核实后为 7 点，非"一行调用"）**：①**`conversationId` 重映射**——检查点是**独立表** `compression_checkpoint`（主键自增 + 外键 `conversation_id` `onDelete=CASCADE`），须改成新会话 id 否则外键悬空；②**`boundaryNodeId` 重映射**——指向 `message_node.id`，换 id 后须换成新节点 id（否则旧 id 不存在→立即失效）；③**重算 `areaFingerprint`**（用 public 的 `areaFingerprintFor(boundaryIndex)` 在新节点序列上覆盖）；④`messageCount` 原样保留（仅展示「已压缩 N 条」+ 下次压缩累计基数 `ChatService.kt:1266`，与新 id 无关）；⑤`content`/`createdAt` 原样；⑥落库用现成 `addCompressionCheckpoint(summary)`（`ConversationRepository.kt:444`）；⑦**顺序**：须在会话与节点 id 确定之后落库。**A 的收益**：导入的会话保持原压缩状态——`currentMessages` 仍是「检查点合成消息 + 边界后原文」，**不依赖 `limitContext` 兜底**。**`version` 位是给二阶段留的**（一阶段纯 JSON、二阶段若附件走 zip 条目则格式会变，勿提前锁死）。**分阶段**：阶段1（低，约 1 提交）=serializer+两入口+换 id+助手选择+FTS 重建；阶段2（中）=附件内联 base64，**须先定格式**。**用户后续决策（2026-09-16）**：①**不存 `assistantId`**；②**不带压缩检查点**（但触发上文上下文长度问题）→ **后经评估拍板改为方案 A（带检查点+重算指纹）**；③**Chatbox/CherryStudio 也加助手选择，并借此统一导入器——「这一条新开一轮」**（不塞进阶段 1）；④**二阶段再决定附件格式**；⑤**统一导入器等导入功能验证后再实现**（依赖顺序，非并行——先让自家格式往返落地并真机验证，再动这个重构，避免边验证边搬结构）；⑥**重复导入检测+提示（允许继续）**——文案「检测到重复导入：{标题} 已于 {yyyy年MM月dd日} 导入过。继续导入将产生重复数据，确定要继续吗？」。**⚠️ 该需求引出一个设计缺口**：**决策 1（导入即换新 id）恰好消灭了重复检测的判据**——`existsConversationById` 换 id 后**永不命中**，同一文件导两次会被当成两次全新导入、**静默产生重复副本**；而核实后**无现成锚点**（`ConversationEntity` 无来源列、`ExportData` 无来源信息）。**方案**：①**导出侧 `ExportData` 加 `sourceId`（=导出时刻的会话 id，随文件固定）+ `exportedAt`**（`ExportData` 是 JSON，加字段**零迁移**）；②**导入侧在 `Settings` 顶层存 `importedSources: Map<sourceId, ImportSourceRecord(importedAt, title)>`**（三件套，与 `compressPresets` 同款写法）——**放 Settings 而非加 DB 列**（零迁移 vs 29→30；且它本质是用户级"来源→时间"记录而非会话属性；提示文案所需信息正好由此提供）；③**落点**：现有流程是「选文件→直接落库」无中间态，需在**读完文件、落库之前**插入检测；弹确认后「继续→照常导入并更新时间戳／取消→终止不记录」；④**四个注意点**：**`sourceId` 是"导出时会话 id"不是"导入后新 id"**（记错就永远查不到）、**记录时机=导入成功之后**（取消/失败不该留记录否则误报）、同一文件导入到不同助手**仍算重复**（锚点是文件来源非助手）、**旧版导出文件无 `sourceId` → 跳过检测**（向后兼容的正确降级）。设计稿 `docs/design-conversation-import-export.md`（§九 已全部落实）。
- 2026-09-16：**①真机验证通过**（用户确认「测试通过」）；**②审 dangling reference 时发现并修掉一个真 bug（`9f1d8105`）**——`remapForImport` 只换了 id 与 `assistantId`，**`folderId` 与 `workspaceCwd` 原样继承了源会话的值**。`folderId` 是硬故障链：文件夹按助手归属（`FolderEntity` 有 `assistant_id` 列）、抽屉默认视图 SQL 恒为 `assistant_id = X AND folder_id = ''`、FolderBar 的 chips 又来自**目标助手**的 folders ⇒ 源会话若归在文件夹里而导入到别的助手（或跨设备、文件夹不存在），**导入的会话在抽屉里彻底消失**（仅「历史」页不看 folderId 所以还找得到，更难排查）。`workspaceCwd` 是软故障（目标工作区无该路径时 `executeCommand` 的 `require(workingDir.exists())` 抛错）。清空语义正确（`folderId=null` 即「未归类」）。**`modeInjectionIds`/`lorebookIds` 特意保留**——注入按 id 过滤匹配，悬空只是匹配不到、无副作用。单测 27 项。
- 2026-09-16：**③二阶段定案 zip 容器 + 附件原字节（否决 base64 内联）**（`c3806cab`）——用户四条决策：始终 zip（无裸 JSON 分支，本功能未发布无历史包袱）／附件缺失跳过并计数／软上限提示／去重限单次导出内。**核查推翻原稿理由**：原写"base64 体积膨胀 33%"，实核发现更根本的问题——`FileEncoder.kt:117` 的 `compressAndEncode` 把图片**有损重编码**为 JPEG q85（丢 PNG 透明通道/动图丢帧）、超 1600 万像素降采样，**及 `Document` 根本没有 `encodeBase64` 重载**（只有 Image/Video/Audio）⇒ base64 方案从根上不成立。另核出四条硬约束：`ConversationRepository.kt:390` 的 `require(none hasBase64Part())` 使"base64 写回 part 再插库"**会抛异常**（必须先物化成文件）；删会话会按 part URL 删盘 ⇒ 导入须生成**新文件名**（否则删原会话连带删副本附件）；导出侧 URL **必须经 `normalizeLocalFileUrl` 归一化**（设备实测 28 document + 9 image 的 URL 全是旧包名 `me.rerere.*`，磁盘上不存在，只能靠 basename 重解析，不归一化会大量误判"缺失"）；单次导出内按 sha256 去重**有实际收益**（实测「驳斥绝对.md」被 3 条消息各存一份、3.4MB txt 有 4 份全同）。格式：`conversation.zip` = `conversation.json`(version=2) + `attachments/<sha256 前16位>.<ext>`，附件清单用 `ExportData.files` 关联而**不把 URL 改写成伪协议**（中途失败会残留非法值）。
- 2026-09-16：**⚠️ 事故：跑 `connectedDebugAndroidTest` 清空设备数据（已恢复）**——为验证 #1903 的 QuickJS 防护（native 行为**只能** instrumented 验，JVM 测不了 `.so`），我跑了 `:app:connectedDebugAndroidTest`（8/8 通过，防护确实生效）。**Gradle 的 connected 任务在结束后会卸载 app**，`-r` 重装不保留数据 ⇒ **用户设备全部私有数据被清**。实证：数据目录属主 `u0_a813`→`u0_a824`（uid 变 = 全新安装）、DB 缩到 1024 字节、`files/upload` 45→0、`workspaces`/`exports` 归零。**我的备份为什么没用**：跑测试前确实备份了，但**只备份 DB**（`rikka_hub`）——而被清的是整个 `files/`（`upload` 附件 / `skills` / `workspaces` / `exports` / `datastore/settings.preferences_pb`），**DB 备份完好却无法还原设备**；属**备份范围选错**而非未备份。**恢复**：用户指出完整备份在 `/storage/emulated/0/Rikka/plus测试/rikkahub_backup_20260916_202642.zip`（72,056,082 字节，含 settings.json + db/wal/shm + upload 45 + skills 10 目录 + workspace_files），用户手动恢复；我逐项核对**完全吻合**（215 会话 / 5112 节点 / 2 收藏 / 54 记忆 / 45 附件 / 10 技能目录 / 1 工作区 / schema 29），应用运行正常无崩溃。**铁律已写入 handoff §四（设备数据安全）**：跑 instrumented 前按**整目录**备份；或避开 Gradle connected（`adb install -r` + 手动 `am instrument`）；事后三查 **uid 是否变化** / DB 大小 / upload 计数。教训=**"先备份"还不够，要确认备份覆盖全部会丢的东西**。（另：核对时我一度把 skills 的「27 条目」误读为「27 个技能」并以为没恢复全，实为 10 目录/27 文件条目。）
- 2026-09-16：**收藏管理改造已实现（`aa2c9a53`）**——用户报「收藏无法管理」（核实后更正为**可删除、但撤销有问题、侧滑难发现**），随后拍板「两批 + 标签管理页」。**修掉三个既有缺陷**：**A 撤销时序竞态（真 bug）**——`removeFavorite`/`restoreFavorite` 原本都是 `viewModelScope.launch` **fire-and-forget** ⇒ 删除未落库就弹撤销，用户点撤销时 `upsert(REPLACE)` 可能**先于** delete 落库、**撤销反被删除覆盖**（与 regenerate 撤销同族的「动作不等结果却立刻依赖结果」）；改 `suspend` 让 UI 在同一协程「先删完再弹 snackbar」。**B 撤销后滑动状态不重置**——`dismissState` 未绑 item，LazyColumn 按 key 复用节点时 `currentValue` 仍为 `EndToStart`、`LaunchedEffect` 值未变不再触发 ⇒ 卡片停在"已滑出"位、**看上去像撤销失败**；改 `key(item.id)` 包裹重建 + 触发后 `reset()`。**C 入口隐蔽**——原先只能右→左滑且无 affordance；加**可见的「标签」「移除」按钮** + 开放双向滑动。**新增**：**排序可切换**（时间/标题；**VM 侧排序**因 SQLite `ORDER BY` 不能参数化）+ **标签筛选 chips**（多选或关系）+ 单条打标 + **标签管理页**（增/改/删）。**数据层零迁移**：`FavoriteMeta` 加 `tagIds: List<Uuid>` 写在 **meta_json blob**（`ignoreUnknownKeys` ⇒ 旧行自动空列表）；**存 id 而非名** ⇒ 改名只改标签池一处、**不必批量更新收藏**（若存名值则需批量更新，这是选 id 的直接收益）；标签池做 `Settings.favoriteTags`（**独立于 `assistantTags`**，语义不同）。**⚠️ 实现中修掉设计稿预判的坑**：`buildFavoriteEntity` 每次从 target **重建 meta**，不带 `existing.tagIds` ⇒ 「取消收藏→重新收藏」**静默丢标签**；已保留并有专门单测钉住。标签删除会**从所有收藏摘掉该 id**（防悬空）。**单测 8 项**；**真机全链路**：卡片显示两按钮→建标签持久化→打标写入 `tagIds:["893eb7ff…"]` 且**保留 title/previewText**、未打标那条不受影响→筛选 chips 渲染→删除 2→1 + snackbar→**撤销回到 2 且标签完好**→排序切换生效→标签重命名/删除可用。全量 574 项仅基线失败；测试数据已清理（收藏仍原样 2 条、标签池清空）。
- 2026-09-16：**统一四种导入格式（`d7d8c4b7`）：格式描述符 + 共享管线 + Chatbox 目标助手**——用户「放弃核查，转向统一三种导入器的落地」。**recon 推翻了原稿前提**：**Cherry Studio 导入器根本不导入会话**（全文 0 处 Conversation 引用，只读 `data.json`→`llm.providers`；UI 文案本就写"提供商配置"）⇒ **"三处共用助手选择"实际只落到 conversation + chatbox 两处**（`local` 整库覆盖、`cherry` 无会话）。**四条路径语义不同类**：local（整库/不选助手/事前确认/需重启）、conversation（1 会话/选助手/解析后确认/不重启）、chatbox（providers+N 会话/选助手/无需+事前确认/需重启）、cherry（仅 providers/不选助手/需重启）。**做法=描述符 + 共享管线，而非 `ExportSerializer` 同族注册表**（后者形状是"1 文件→1 对象"，与 chatbox 的"N 会话+providers"、local 的"整个 DB"不匹配，硬套还得再包一层）。①**`ImportFormat` 枚举 + `ImportFormatSpec`**（mimes/title/desc/needsAssistant/needsPrePickConfirm/needsRestartDialog）=单一真相源，取代 4 张手写卡片 + `importType: String` + 三处字符串比对；**新增格式=加一个描述符+一个分支**；②**共享管线**：一个 launcher 按 `pendingFormat: ImportFormat?` 分发 + `runImport`/`finishImport`（把"是否提示重启"显式化为描述符字段——原先 conversation 分支靠提前 return 绕过共享尾部）；③**抽两个真实重复**：`copyUriToTempFile`（原 4 处各写一遍，**并修静默失败**——原 `openInputStream(uri)?.use{}` 在流为 null 时产出**空临时文件**致下游误报"格式不对"）、`mergeProviders`（**有意不去重**，用测试钉住）；④**chatbox 补上选目标助手**（本轮唯一功能缺口）并**连带修正 `allowConversationSystemPrompt` 原先打在"当前助手"上**（会"导入到 A、却改 B 的配置"）+ 接上原先被 UI 整个丢弃的返回值（现 toast 报条数）。**单测 13 项**；**真机**：按描述符渲染且顺序正确、整库事前确认仍在、**Chatbox 导入弹助手选择并真正落到所选助手**（选「创作」→ 会话出现在「创作」分组）、SAF 过滤器生效（chatbox 只列 JSON）、零崩溃/ANR、数据完整。全量 566 项仅基线失败。
- 2026-09-23：**新增 `docs/design-subagent-context.md`——子代理与独立上下文设计稿（第一版讨论稿，仅探讨未立项）**。起因：用户「开始设计子代理系统的讨论（含独立上下文窗口），兼顾 opencode/codex/cherry studio 式与 Yuihub/rikkahub-agent 等二改」，要求固定第一版方案。**核心是视角修正**——通用框架（并行 5 个 / CRDT 合并写 / TEE / Merkle 账本 / 桌面树状时间线）是服务端或桌面平台视角，本仓是**手机聊天应用**、多数不适用（§八逐条列明不做）。**关键判断**：①**本仓不缺「上下文隔离机制」**——`buildInternalMessages`（`PromptAssembly.kt:245`）已是显式入参的函数 ⇒「独立上下文」= 用不同入参调它（代价=prompt cache 命中率下降，需计入预算）；②真正的四个缺口=**无头审批政策**（唯一无现成答案项、且属安全边界不止体验）、**run 生命周期**（session 空闲 5s 回收 + Android 静默杀后台）、**transcript 归属**（需拍板，见下）、**预算与防循环**（`LoopGuard`/`ContextBudgetPlanner` 均未实现 ⇒ **硬性前置**）；③**便宜收益**=可观测性几乎白送——本日刚落地的 `PromptInspectPage`（逐段归因 + 跳到来源）即「运行详情页」模板；④**一条挡过度设计的判据**=技能是「注入同一上下文」、子代理唯一增量是「把过程移出主上下文」⇒ 短任务用技能更便宜。**已量化的关键风险**：若复用 `Conversation` 表存 transcript，`ConversationDAO` 有 **12 个列表查询 + 3 处聚合**（`getAllIds` FTS 重建 / `COUNT(*)` / 按天统计）漏加过滤即**静默泄漏**到侧栏/统计/FTS。**§七 四个待确认**（transcript 三选一 (a)判别字段/(b)独立表/(c)纯内存、无头审批粒度、是否统一 run 账本、Phase 1 是否值得做）。**信息可信度已在附录声明**：本仓事实均经代码核实；rikkahub-agent 属二手（我们的笔记）；**opencode/codex/cherry studio 未引用实现细节**（未核实、避免编造）。**末节诚实评估**：本仓 backlog 里用户可见项（终端 ③⑤⑦⑧ / rootfs 四项 / 备份 P0 / LoopGuard）**优先级应高于本项**。

- 2026-09-24：**分叉会话自动起名「源标题(序号)」（`78048454`，真机验证）——吸收上游 `458c16df`，09-19 那批最后一项，该批至此全部收口**。**问题**：分叉出的会话标题为空，侧栏里一堆「新对话」分不清谁是谁；**更隐蔽的一层**——空标题会让既有自动起名（`generateTitle` 仅在 `title.isBlank()` 时触发）在首条消息后把它改成与源会话**无关**的名字，「从哪分出来的」线索就断了。**做法**：按语义手工落地**未 cherry-pick**（fork `ChatService.kt` 重度定制，`forkConversationAtMessage` 位置结构均不同），抽成纯函数 `forkConversationTitle(source, existingTitles)` 上单测；避让集合取 `getConversationsOfAssistant(...).first()` 的标题集（作用域=同助手，同上游口径）。**两处有意与上游不同**：①**源标题为空时保持为空、不生成 `(1)`**——上游写法在空标题源上产出 `"(1)"`，既无信息量又因「非空」让自动起名**永不修正它**（`shouldGenerate` 要求 isBlank），等于把无意义标题焊死；②**序号取第一个未占用值**（而非「已有数量+1」），删除某次分叉后再分叉会复用空出的序号，避免无界增长。测试 `ChatServiceTest` 5 项新增；全模块 1221 项 0 失败。- 2026-09-24：**v1.0.14 草稿资产二次重建（versionCode 273→274）——带上 273 之后的 13 项改动**（承接 272→273 的「资产重建」做法：versionName 保持 `1.0.14`、只递增 `versionCode`、替换草稿同名资产、草稿 tag 不变）。**新进资产的内容**：①每个模型可单独启停（`c3330da1`）+ 元数据编辑原语（`6ec84d93`）②「测试连接」带标签可发现、预设级删除加确认（`032ae77a`）③测试文件体检（`58fb7519`/`b1f3eca8`：修一个从未运行的测试、还原被误认作样板的真套件）④工具调用失控护栏 LoopGuard + forget（`2452f665`）⑤分叉会话自动起名（`78048454`，吸收上游 `458c16df`，09-19 批至此全部收口）⑥**段卡长标签挤出操作按钮的布局修复**（`6b7e5b31`，见下条）。**流程**：bump（`f6064e82`）→ release 构建 4m14s → aapt 核实 `versionCode=274`/`1.0.14`/`arm64-v8a` → **release 装机冒烟零 FATAL** → `delete-asset` + 重传（76,783,575 字节）→ `gh release edit --notes-file` 更新发布说明（**补入本轮 4 项**，回读校验 8 个双语标记齐全）→ **字节级校验：GitHub 服务端 digest `sha256:284876c7…` 与本地一致**。；**顺带修掉草稿标题仍写 `(273)` 的不一致**（`gh release edit --title "v1.0.14 (274)"`——标题约定是 `v<ver> (<code>)`，资产换了而标题不换会自相矛盾）。**⚠️ 操作教训**：`delete-asset` 与 `upload` 分两步、且资产有 76MB ⇒ **中途超时会让草稿处于「无资产」状态**（本次真的发生过，已即时补传）；稳妥做法是**先传新资产再删旧**（同名时 GitHub 会拒重名，故实际应「上传为临时名 → 删旧 → 改名」或用独立进程跑上传）。
- 2026-09-24：**修复段卡长标签把折叠/操作按钮挤出卡片（`6b7e5b31`，真机程序化验证）**——用户报「预览页工具输出名称过长时，该段折叠按钮被遮挡/挤压以致找不到」。**根因**：`SegmentCard` 卡头是 `Row(SpaceBetween)` 但标题 `Text` **没有 weight**（也无 maxLines/overflow）⇒ 标题按自身需要占满宽度，把右侧 token 数、折叠开关与三个操作图标**整体挤出卡片**；属**既有布局缺陷**，被本轮新增的「工具输出 · <工具名>」长标签（MCP 名如 `mcp__mt__mt_apk_search`）暴露。**修法**：标题加 `weight(1f, fill = false)` + `maxLines = 2` + `Ellipsis`（`fill=false` 保短标题时操作仍靠最右；**2 行而非 1 行**——工具名截断后就认不出是哪条，宁可换行）。同页其余行已核对（目录卡/来源行本就有 weight）。**验证方式值得记**：不依赖截图，用 `uiautomator` 读 bounds——可见段 `#9 工具输出 · mcp__mt__mt_apk_search` 标题 `x:[88,376]` 高 110（两行⇒未截断），折叠 `[508,552]`/复制 `[640,684]`/复制含表头 `[772,816]`/分享 `[904,948]` 全在 1080 内，标题右界 376 < 折叠左界 508 无重叠。- 2026-09-24：**工具调用失控护栏 LoopGuard + forget 补丁（`2452f665`，真机验证可触发）**——落地 handoff 待办（出处 `peer-apps-benchmark.md` 附录 + rikkahub-agent / SoLab ToolCallLoopGuard）。**问题**：模型卡在「同一工具 + 同一参数」反复调用，每次无新信息却持续烧 token，直到 `maxSteps`=256 才停；观感是「转很久、一直调同一个东西、最后什么也没说」。**实现**（`data/ai/ToolCallLoopGuard.kt`，纯逻辑可单测）：滑动窗口 12 存 `toolCallSignature` 指纹（复用既有 helper——它已归一「键序/空白不同的等价 JSON」，不归一就漏判）；**双阈值**=相同调用连续成功「第 3 次请求即停」、反复失败「允许 4 次重试、第 5 次停」；`get_time_info` 类轮询工具豁免（结果本就每次在变）；判定为**优雅停止**——当轮工具结果照常回填（协议要求每个 `toolCallId` 都有结果）后结束本轮，**走正常结束路径**（抛异常会让本轮内容只停留内存、onSuccess 才落库 ⇒ 把「防失控」变成「丢内容」）；提示走新增 `GenerationChunk.Notice`（**刻意不写进消息**：写进去会被持久化并在下一轮当上下文发给模型，变成自己教自己的噪音），由 ChatService 转 `ChatError` + 6 语言标题。**⚠️ 真机验证暴露并修掉两个设计缺陷（勿改回去）**：①**护栏必须挂在会话上**——工具审批让循环 break 等用户，批准后是**新的** `generateText` 续跑同一轮（日志每次重新 `start step #0`），建在 `generateText` 内则每次批准即重置、永远累积不到阈值 ⇒ 对审批类工具完全失效（修前日志 `window=1 repeats=1` 恒定）；改为 `ConversationSession.toolLoopGuard` 持有，ChatService 在**新一轮用户消息/重新生成**时 reset。②**`forget()` 不能按字面「移出窗口」**——同源实现的补丁是「失败调用从指纹窗口移除」（理由：网络恢复后重试是合理恢复路径），真机按字面实现后发现模型反复读**不存在的文件**（每次抛异常）⇒ 每次记录立即被移除 ⇒ 窗口恒为 1、**永不触发**、可重试到 256 步；根因是「确定性失败」与「瞬态失败」在工具层无法区分。故**保留意图、改变实现**：失败不删除而**标记**（`markFailed`）+ 更高阈值单独计数——抖动重试照旧放行，反复撞同一必然失败会被拦下；偏离与理由写在类注释。**测试** `ToolCallLoopGuardTest` 18 项（含「反复失败必然触发」作为 ② 的回归）。全模块 1216 项 0 失败 3 跳过。**本项不含**：rootfs「单轮总预算」（shell 累计时长上限）仍待做。- 2026-09-24：**补两处小缺口（`032ae77a`，真机验证）**——handoff 待办表里挂着的两项。**①「测试连接」入口不可发现**：`ProviderConnectionTester` 此前是纯图标 `IconButton { Icon(HugeIcons.Connect, null) }`（无文字、连 contentDescription 都没有）⇒ 用户普遍不知道提供商详情页能测连通性（待办原文即「用户以为没有该功能」），读屏也读不出用途；改为 `TextButton`（图标 + 文字），复用既有字符串 `setting_provider_page_test_connection`（6 语言本就有，原仅用于对话框标题），文字即标签故 contentDescription 置 null 避免读屏重复播报。**②预设级删除无确认**：`PresetCard` 的删除按钮**直接** `onUpdatePresets(presets - preset)`，而条目级（`ModeInjectionCard`）与世界书条目（`RegexInjectionEntryCard`）都已有 `RikkaConfirmDialog`——**只有预设级漏了**；预设是「一组条目」的容器（真机列表里就有 14/41/39 条目的），误删代价明显更大。补同款确认弹窗，并新增 `prompt_page_delete_preset_confirm`（6 语言）而非复用 `prompt_page_delete_confirm`（后者写的是「该条目」，指代错误）；新文案明确「其中的条目会一并删除」。**真机验证**：提供商详情页出现「测试连接」按钮；提示词页点删除弹出「删除 / 确定删除该预设？其中的条目会一并删除。/ 确认·取消」，确认后正常删除（**用一个一次性新建的预设测试，测完即清理，未触碰既有数据**）。全模块 1198 项测试仍全绿。- 2026-09-24：**测试文件体检（`58fb7519` + `b1f3eca8`）——查出「从未运行的测试」并还原被误认作样板的真套件**。起因：修 `GoogleProviderSchemaTest` 的第 73 行多余花括号时意识到可能存在同类隐患，遂系统体检。**五个发现**：①**一个测试从未运行过**（`58fb7519`）——该文件多余 `}` 把类提前关闭，使第 4 个 `@Test` 变成顶层函数、编成门面类 `…Kt`，JUnit4 恒报 `InvalidTestClassError: should not be static`；报告里 `tests=3` + 幽灵 `tests=1 fail=1`，**长期被误读为「既有基线失败」**，而它覆盖的恰是 `ensureArrayItems` 对畸形 `type`/type 列表的处理（`e4bb4624`/`4a62e43b` 那批真出过问题的归一化逻辑）。修完该文件 **4 项全过**、幽灵类消失、`:ai:` 由 FAILED 变绿 ⇒ **生产代码无问题，纯属测试文件写坏**。②**真套件被样板名掩盖**（`b1f3eca8`）：`workspace/ExampleUnitTest.kt` 是 **244 行 / 9 用例**的核心测试（文件系统、路径逃逸、rootfs 安装、命令执行与 stdin、输出截断、proot 补丁），却与其余 9 个模块里 16 行的样板同名 ⇒ 被当成样板无视；其中 3 个用例**在 Windows 宿主必失败**（真的 spawn `/bin/sh`）⇒ 改 `Assume.assumeTrue(File("/bin/sh").exists())` **跳过而非失败**（本仓是 Android 应用、目标运行时本就是 Linux），并改名 `WorkspaceManagerTest`。③**删除 9 个纯样板**（16~17 行、只断言 `2+2=4`，逐文件校验「仅 1 个 @Test 且含 `2 + 2`」后才删）——它们本身无害，但**提供了伪装**。④`MarkdownMathParseTest` 的过期断言（全仓最后一个红点）：读测试自身 println 输出定性——**紧贴形态** `$$egin{tikzpicture}…$$` 实测产出 `<span class="math" inline="false">`（**受支持**，正是 TikZ 引导语要求的写法），而**空行分隔形态**实测 `<p>$$</p><p>…` **无 math span** ⇒ 原断言写的是期望、非实际行为；现拆为「正向断言受支持形态」+「如实记录空行形态的限制」。⑤**回归覆盖修正**：此前「全量」只跑 `:app:`+`:ai:`，**漏掉 common/workspace/web/document/highlight/material3/search/speech 共 8 个模块**；裸任务名 `testDebugUnitTest` 可跑全模块（已实测）。**体检结论**：按类对账 **140 源测试类 ↔ 140 已执行类**，无幽灵类、无「从未执行」的类；**全模块 1198 项、0 失败、3 跳过**。**教训**：把恒定的失败称作「基线噪音」是危险的——它掩盖的可能是「测试根本没跑」；另「样板式命名」会让真测试被无视。

- 2026-09-26：**rootfs 工具单轮总预算+最大步数（`947f92a6`，LoopGuard 项收尾）**——用户「接下来 rootfs 单轮总预算（LoopGuard 那一行剩下的部分）的实现，你先弄清落点，准备实施」→ 落点核实后按建议落地。**防的形态**：LoopGuard（`2452f665`）只拦同签名重复，**每步换参数**的 workspace 调用（`cat file1`→`cat file2`…）不命中；全局 `maxSteps`（256）太晚；**单轮累计输出此前无上限**（256 步 × 单步截断 32K = 8M chars ≈ 2M token）。**落点三件**：①纯类 `WorkspaceTurnBudget`（`data/ai/`，与 ToolCallLoopGuard 并排，无 Android 依赖）：只计 `workspace_*` 五工具、**截断后实际进入上下文**的字符数（截断掉的部分在 `tool_outputs` 文件、不进上下文）+ 步数，双上限任一命中即超限；②状态挂 `ConversationSession.workspaceTurnBudget`（**LoopGuard 同款挂载教训**：审批续跑是新的 `generateText` 调用，建在调用内每次批准即重置），重置点同两个（新用户消息 `ChatService:555` / 重新生成 `:649`）；③Handler 在**执行并截断后**计费（执行前拒绝会与优雅停止不一致），失败路径**同样计费**（错误文本含堆栈也进上下文，实现中补的口径修正），超限与 `loopStopped` 合流走既有「结果回填后 emit(Notice)+break」→ `ChatService` `addError`（不写进对话避免自我污染）。**默认值（用户拍板按建议）**：256K chars（≈64K token，≈8 个满额输出）+ 64 步；常量不进 Settings（与 LoopGuard 同款取舍）。**口径说明**：原 handoff 设想是「shell 累计执行时长」，经用户确认落地为「输出字符+步数」（防上下文/成本失控）；时长变体未做、如需另立小项。**新增 `GenerationNotice.WorkspaceBudgetExhausted`** + 6 语言标题「工作区工具用量已达上限，已结束本轮」。**测试**：`WorkspaceTurnBudgetTest` 7 项（边界=恰好等于允许 `>` 判定、零输出步仍计步、粘性超限、重置）；全量 902 项**零失败**（基线失败已被 `b1f3eca8` 修掉）。debug 已装机，触发验证（诱导大输出脚本）待真机。

- 2026-09-21：**每个模型可单独启停（`c3330da1`，真机验证）**——用户「为提供商的每个模型也提供一个启停开关」。提供商详情页模型卡片加行内 `Switch`（与「提供商启停」「预设条目启停」同款交互），停用后该模型从**所有模型选择器**收起，卡片给「禁用」标签。**语义取舍（与 `ProviderSetting.enabled` 对齐）**：停用 = **不再出现在选择列表**，**不是阻断使用**——已选中的助手/会话照旧可用（`findModelById` 不看该字段）；理由是与既有「提供商启停」同一取舍 + 避免停用静默改掉既有配置（否则会出现「请求究竟发去了哪」的意外）。**零迁移**：`Model.enabled: Boolean = true` 带默认值 + `ignoreUnknownKeys` ⇒ 旧数据缺字段取 true。**改动面**：①`Model.enabled` 新字段；②`ModelList` 抽出 `List<Model>.selectableOf(type)` / `ProviderSetting.hasSelectableOf(type)` 两个**纯函数**（Composable 内过滤无法上 JVM 单测，而「停用后不再出现」是本功能唯一行为契约），四处在用：两条 provider→models 映射、**收藏区**（收藏只是取用捷径，不该绕过「不再提供」）、**provider 分组标题的存在性**（模型全停用时不再输出空标题，否则像是模型丢了）；③`ModelCard` 加 Switch + 标签 + contentDescription；④`ProviderConnectionTester` 优先用未停用模型（停用常意味不可用），全停用时退回任意一个。**未动**：请求期 `findModelById`、历史消息模型名回显、统计页（都需按 id 解析已停用模型）、提供商列表页的模型计数。测试 `ModelSelectabilityTest` 7 项；全量 867 项仅既有基线失败。**✅ 已随 v1.0.14 (273) 资产重建带上**（原记「草稿 APK 不含本功能」，见下条重建记录）。

- 2026-09-21：**发布 v1.0.14 (272) 草稿**（含本日全部改动）——用户「发布一版草稿」。bump 272（`4d8c466e`）→ release 构建 4m5s → aapt 核实 `versionCode=272`/`versionName=1.0.14`/仅 `arm64-v8a` → **release 装机冒烟通过**（背景图/消息/输入栏正常，零 FATAL）→ 草稿 release + 资产上传（76,778,047 字节）→ **字节级校验：下载已上传资产比对 sha256 = 本地一致**（`c3355feb…`）→ README 标注补草稿。**本版内容**（自 v1.0.13/271）：①拼装预览补上**工具 schema 与工具输出**（此前均计 0；schema 独立成段并标「请求顶层字段，不在消息里」，修掉把顶层 `tools` 渲染成 system 块的归因错误）②预览标题改「**请求内容**」+ legend 补「不计入」清单（`d160647f`）③**图片选择回退**为 `GetContent`/`GetMultipleContents` 恢复文件浏览入口（`af110d7f`；实为回退一个上游已自行 revert 的改动，见 `upstream-analysis.md` 注意点 12）。**待用户转正**。

- 2026-09-23：**v1.0.14 资产重建（versionCode 272→273）——带上两个构建后新增的功能**。起因：用户「验证通过」后要求「更新草稿资产」。此时分支上已有两项**未进 272 资产**的改动：①`c3330da1` 每个模型可单独启停（显式标注过「草稿不含本功能」）②`6ec84d93` 元数据编辑统一走 `updateMetadata` 原语（生成中切助手不再覆盖在途内容 + 补上次修漏的 web `pin`/`move`）。**做法**：`versionName` 保持 `1.0.14`、只递增 `versionCode` 到 **273** 并**替换草稿同名资产**（草稿 tag 不变；同 v1.0.12 曾以 269→270 重建的做法）。流程：bump（`35b02886`）→ release 构建成功 → aapt 核实 `versionCode=273`/`1.0.14`/`arm64-v8a` → **release 装机冒烟零 FATAL** → `gh release delete-asset --yes` + 重传（76,779,107 字节）→ `gh release edit --notes-file` 更新发布说明（补入后两项功能）→ **字节级校验：下载已上传资产 sha256 = 本地 `a4c648bd…` 一致**（与旧资产 `c3355feb…` 不同，确认是新产物）。**发布说明本版共 5 条**：工具 schema/输出接入、标题改「请求内容」、图片选择恢复文件浏览、切助手丢内容修复、模型级启停。**待用户转正**（注意 273 的 tag 仍指向 `v1.0.14`，转正时按既有流程核实 tag 指向）。

- 2026-09-20：**发布 v1.0.13 (271) 草稿**（含本节 09-20 全部改动）——用户「走发布流程」。bump 271（`254081bb`）→ release 构建 4m17s → aapt 核实 `versionCode=271`/`versionName=1.0.13`/仅 `arm64-v8a` → **release 装机冒烟通过**（R8 未破坏资源链：背景图正常加载、进入会话正常、零 FATAL）→ 草稿 release + 资产上传（76,768,831 字节）→ **字节级校验：下载已上传资产比对 sha256 = 本地一致**（`6f7d6bd2…`）→ README 标注补草稿（`7756b371`）。**本版内容**（自 v1.0.12/270）：①停止生成保半截内容（`1b41b586`，真机复现并修复的丢内容 bug）②置顶星标不再被整对象保存覆盖（同批）③裁剪产物改 JPEG + 扩展名由格式派生（`458fe3a6`，吸收上游 `7c1629d0`）④拼装预览 Tier 4：注入段来源归因 + 跳转到条目（`16b26619`）⑤内联代码禁用连字（`083e2c96`，吸收上游 `a7850967`）⑥移除 tool 消息多余顶层 name（`43b54550`，吸收上游 `6e98691c`）。**用户已转正（2026-09-21）**：`draft=false`、标记 **Latest**；**tag `v1.0.13` = `95083a9b` = 分支 HEAD = origin HEAD 三方对齐**（上次 v1.0.12 转正时 tag 曾被建在旧 HEAD、后用 `tag -f` 纠正，本次已先核实无需纠正）。

- 2026-09-16：**二阶段落地 `c3fff5c3`：对话归档为 zip + 附件原字节往返（真机端到端验证通过）**——用户「开始对话导入导出的二阶段实现」。**格式**：`conversation.zip` = `conversation.json`(ExportData, version=2) + `attachments/<sha256 前 16 位>.<ext>`。**关键设计**：①**清单关联而非伪协议**（`ExportData.files: List<ExportFileEntry>`：originalUrl/entry/fileName/mime/size/sha256）——若把 part URL 改写成 `attachment://`，导入中途失败会把它残留在库里成为非法值，故改写只发生在导入最后一步；②**原字节**（base64 已被否决：`Image.encodeBase64` 是有损重编码 JPEG q85+降采样，且 `Document` 无该重载）；③导出侧 URL 经 `normalizeLocalFileUrl` 归一化（存量数据大量是旧包名路径，只能靠 basename 重解析）；④**单次导出内按 sha256 去重**（条目名=内容哈希），不做跨会话全局去重（会重新引入"删一个会话删掉共用文件"的危险）；⑤**导入必须真拷贝**（新 UUID 名，否则 `deleteConversation` 按 part URL 删盘会连带删掉导入副本的附件）；⑥**缺失/超限跳过并计数**（导出照常成功、导入后提示 N 个缺失；内容 sha256 不符同样按缺失处理）；⑦**软上限** 100MB 只提示确认不硬拒 + 单附件 50MB 跳过；⑧URL 收集与改写**递归到工具输出**（深度上限 16）。**⚠️ 实现中踩坑并修掉**：`planAttachments`/`packConversationArchive`/`parseConversationImport` 做文件 IO 与 SHA-256，但调用点是 Compose 的 `rememberCoroutineScope()`（**Main**）——**首版没切线程，真机直接 ANR**（Input dispatching timed out）；已全部 `withContext(Dispatchers.IO)`。**教训=把"纯计算搬家"当纯函数时别忘内部有阻塞 IO**。另：**软上限用"先 stat 再确认"两段式**（否则要读一遍大文件才发现超限）。**文档差异**：原写"无需兼容裸 JSON"，实现改为**仍接受阶段 1 裸 JSON**（理由不是历史包袱而是用户手上已有文件；靠 zip 魔数判别不看扩展名）。**单测 17 项**（扩展名优先级/小写化、条目名去重、完整性校验、URL 收集去重与递归、改写不可变性、zip 往返、非 zip 拒绝、**zip-slip 条目被拒**、未知条目忽略）。**真机端到端**：导出「基于文档和记忆的总体行动方案」→ 8 附件/8 条目/0 缺失、sha256 8/8 一致；导入该 zip → 8/8 恢复、0 缺失、**全换新文件名**（与原文件集合不相交）、引用文件均存在、**内容哈希集合与原文件完全一致**、原文件未被触碰、节点 id 全量换新；零孤儿、零崩溃/ANR。全量 553 项仅基线失败。
- 2026-09-16：**对话导入导出已落地 `31d46101`（真机验证待用户执行）**——用户「标题和时间要显示，清理暂时不做。开始落地」后实现完成：导出侧 `ChatExportSheet` 加「JSON（可导入）」（**有意忽略"包含全部版本"开关**：往返格式必须原样保留分支/编辑历史，否则导入回来缺候选）；导入侧备份页新增「对话（JSON）」入口，**先解析不落库**（需先拿 `sourceId` 做重复检测 + 让用户选目标助手，确认后才提交）。**实现中发现的三个真 bug（均已修）**：①**无效文件会误报"恢复成功"**——`preview == null` 分支只弹了错误 toast、没有 `return@runCatching`，会继续落到下面的成功提示 + 重启弹窗（补 return）；②**导入确认弹窗永不渲染**——它被写在 `if (pendingLocalImport) { ... }` 内部，而该标志**只由整库 zip 恢复入口置位**，自家格式分支从不设置它（移出该守卫）；③**取消导出会触发破坏性移除**——「先导出再移除（scope B 子选项）」的续作原先挂在 `onDismissRequest` 上，用户点返回/取消也会执行移除（改为 `onExported` 回调，只在真正导出成功后触发）。**另一个实现修正**：重复提示的日期原写死 `yyyy年MM月dd日`，改为走 `Instant.toLocalDate()`（`TimeUtil` 既有的 `FormatStyle.MEDIUM` + 系统 Locale，**中文下产物正是用户给的形态**）——照抄中文年号会让其它语言读不通（设计稿 §10.3 本就要求"其余语言用当地习惯格式"）。**单测**：`ConversationImportRemapTest` 23 项（换 id 完整性/唯一性、toolCallId 保留、检查点边界重映射 + 指纹重算 + "导入后仍 active"、序列化往返、重复检测以 `sourceId` 为锚、旧设置缺字段降级为空映射）；全量 518 项仅基线 `MarkdownMathParseTest` 失败（既有，与本轮无关）。
- 2026-10-02：**#1985 工具参数双 JSON 修复性解析（`ToolArgumentsJson.kt`，上游拒稿未修、fork 自力更生）**——`parseToolArguments`：严格解析失败 → 扫描第一个完整 JSON 值（字符串/转义状态机，只计与最外层同类的括号配对）→ 恢复执行 + warning 记尾随长度；不可恢复如实重抛。单测 11 项全绿。探针发现（顺手钉进测试）：默认 `Json.parseToJsonElement` 对**顶层裸字面量宽容**（"garbage"→JsonPrimitive），既有行为非本次引入。
- 2026-10-02：**修 ANR：上下文占用估算挪出主线程（`b0753127`）**——用户报「无响应」，ANR trace
  （`/data/anr/`，su 拉回）显示 main 线程 **97s CPU 全在 ByteLevelBpe**：容量面板的
  `remember(conversation){estimateWindowTokens}` 在**每个流式 chunk 的重组**里于主线程同步跑
  整窗真 tokenizer 分词（流式期间 usage 未到恒走估算路径），大窗口 × 高 chunk 率即打满 main。
  修=ChatPage 层 `produceState`：`snapshotFlow` 喂新实例 + **300ms 节流** + Default 线程
  latest-wins（慢计算被新实例取消），重组零阻塞；首帧 0/上限、≤300ms 到位。
  全仓同类调用点核查：其余（ContextBudgetPlanner/CompressionPlanner/ChatService 自动压缩）
  均在后台协程，组合期只剩新异步路径。**教训：真 tokenizer 落地时只看了精度与预热，
  「每次全量重算 × 高频重组」的成本组合是装机后才暴露的第三类问题。**
- 2026-10-02 晚：**v1.0.20 (282) 已转正为 Latest**（`gh release edit --draft=false --latest`；发布后
  `git ls-remote` 核对 tag → `f26c5ad5` = bump 提交 = 资产构建点**三方对齐**，本版无 tag/资产错位问题——
  target 在建草稿时就钉住了）。digest `sha256:75af9a8a…` 发布前后一致；README 改「正式发布：v1.0.20」（无草稿）。
- 2026-10-02：**v1.0.20 (282) 草稿**——bump `f26c5ad5`（**先 push 再构建**，target 钉 bump 完整 SHA）→
  清 outputs → release 构建 → aapt 核实 `versionCode=282`/`1.0.20`/仅 arm64 → **release 装机冒烟零 FATAL**
  （tokenizer `ok=true vocab=100256 in 1086ms`）→ draft（release id `401868486`）+ upload
  **77,601,467 字节** → digest `sha256:75af9a8a…` 与本地一致 → README「正式 v1.0.19 ｜ 草稿 v1.0.20」。
  **本版内容（自 281 资产 6 项用户可见）**：①上下文容量面板（+标题旁读数、细条/圆环切换）②三项小件
  （加载守卫/fastModelId 清除/promptPresetIds 整绑）③#1985 双 JSON 修复性解析 ④#1662 贴底错乱两半同修
  ⑤#1805 显示生成来源 ⑥ANR 修复（占用估算挪出主线程）。验证状态：全部经真机验证（本版 bug 即真机发现）。
- 2026-10-02：**空回复补救 + workspace 写入冲突检测（Yuihub `8a3a37ac`/`f91d3b16` 对照吸收）**：
  ①**空回复补救**（`GenerationHandler` + `PromptAssembly.buildInternalMessages`）——多步操作后模型
  只输出思考、无可见正文也无工具调用时，注入一次性 `<recovery_note>` 到**下一次请求的系统提示**
  要求补出可见回答（不写入对话历史、拼装预览不受影响），仅重试一次；②**写入冲突检测**——
  `write_file` 新增 `expectedMtimeMs`/`force` 参数：调用方声明的 mtime 落后于磁盘时以
  `WRITE_CONFLICT` 拒绝（防子代理并行/编辑器同时开时的双写静默覆盖），`read_file` 输出补 `mtime`
  形成读写闭环（Yuihub 只做了 write 侧，read 侧 mtime 是本仓增强）；底层新增
  `WorkspaceManager/Repository.rootfsFileMtime`。**教训**：跨行字符串注入用 python 拼接会把
  `
` 展开成真实换行——多行 Kotlin 字符串编辑一律用 Edit 工具，别用脚本。
- 2026-10-02：**todo_write 计划工具 + 计划细条（借鉴 Yuihub `ab107549`+5 版 UI 迭代终态）**——
  ①`TodoTools.kt`：`todo_write` 本地工具（纯函数无副作用，最新一次调用的参数即当前清单）；
  ②**PlanBar 常驻输入栏上方**（方案 E 终态）：收起一行=「当前步骤+进度+LinearProgress」、点击展开
  完整清单、全部完成切完成态，无计划不渲染；**计划不渲染进消息正文/导出**（Yuihub 踩坑结论直接采纳）；
  ③派生=`latestTodoPlan(conversation)` 扫当前分支最后一次调用（畸形输入容错）；
  ④门控=`LocalToolOption.TodoPlan` 按助手开关（fork 惯例，Yuihub 是无条件注册）；
  ⑤`TodoWriteToolUI` 折叠一行+展开摘要（正文详情让位给 PlanBar）。单测 7 项。
  **踩坑**：bottomBar 加 Column 时括号插错位（首跑编译错），Script 写回遗漏致测试文件半更新——
  两处都是「编辑脚本命中锚点与实际结构不符」的变体，多行结构改动后必须整段重读。
  **2026-10-02 真机验证通过（用户确认）**。
- 2026-10-02：**#1974 极端场景对齐上游 `cf79246b`（`McpSchemaRefs.kt` 重写）**——①**循环引用
  就地截断为 `{"type": ...}`** 而非 `{}`（保留类型约束，模型仍知参数形状；截断计入 resolved）；
  ②**任意 `#/` 文档内指针可解析**（根文档=type+properties+$defs，不再限于 `#/$defs/`——覆盖
  `#/properties/x` 自引用与旧式 `#/definitions/`），无 $defs 但有 $ref 时不再整体早退；
  ③常规路径零开销保留（无 defs 且无 $ref 字符串即直返）。**实现坑**：根文档键名误用 `$ref`
  当 `$defs`（首跑 12 测试红，根因=常量复用），拆 `REF_KEY`/`DEFS_KEY` 后修复。单测 20 项
  （+4：properties 指针/旧式 definitions/非 defs 循环保 type/根指针 miss）。
- 2026-10-02：**Yuihub 对照三小件落地（peer-apps-benchmark §四.5 评估 → 实施）**：
  ①**备份 DB 快照改 VACUUM INTO**（`BackupArchiveEngine.addDatabaseToZip`）——单读事务一致性
  快照补齐**备份方向**的一致性缺口（09-15 WAL 修复只治恢复方向；热拷期间写入不可见/可能半页），
  SQLite 3.27+（API 30）可用、老设备/异常**回退热拷三件套**，恢复路径两种形态本就兼容；
  ②**workspace 读写二进制防护**——`readTextInRootfs` 检测 U+FFFD（有损解码标记）即按二进制拒绝，
  防「解码→replaceText→写回」把非文本文件静默写坏（read_file/edit_file 共用单汇合点）；
  ③**工具参数解析三层降级**——`parseToolArguments` 严格→宽松（未引号 key，lenient 标志位）→
  首值提取（#1985 双拼接），全部失败如实重抛原始错误，GenerationHandler 统一记 repaired warning。
  单测 +2（lenient 用例）共 1160+ 全绿。
- 2026-10-02：**三项小件落地（`944d5de6`/`d588527e`/`54448cda`，2026-10-02 真机验证全部通过**——①守卫在生成中离开再回到会话场景验证；②清除入口 + 标题模型错误卡实际弹出（设备本就未配置快速模型）；③整绑开关注入生效。测试遗留消息已由用户手动清理）**：
  ①**initializeConversation 加载守卫**——生成中（`session.isGenerating`）跳过 DB 覆盖内存：流式内容只走
  updateConversation 纯内存，web 路由的消息/编辑/分叉/删除/分支切换等 7 个入口与重开会话页此前都会无条件
  用 DB 覆盖，把半截内容静默抹掉（1b41b586 同族最后一处）；守卫只保证 session 存在与当前助手指针。
  ②**`promptPresetIds` 接线（上面 09-19 条目③的「未接线能力」）**——`ExtensionPicker.PresetPickerContent`
  容器页加整绑 Switch（照世界书容器行写法）：助手详情扩展页固定显示、对话内扩展面板仅助手作用域显示
  （会话作用域传 null 隐藏，预设级绑定**不随会话覆盖**是既有设计）；与条目级勾选是**并集**（消费点
  PromptInjectionTransformer/PromptInspector 早已就绪）；详情页加「整绑已生效」提示；6 语言。
  子代理信封 `toRunProfile` 本就清空该字段（父对话注入不渗入子代理），不受影响。
  ③**fastModelId 可空化 + 清除入口**——见 handoff #1975 条目的更新。
- 2026-09-19：**核实「预设条目能否像预设一样在外部直接开关」——已具备，但记录了三层开关语义 + 一个未接线能力**——用户提「预设的条目也像预设本身一样可在外部直接开关的难度（仅评估）」，随后指出**该需求已完成**；我据代码核实确认，并落档以免后续改动混淆：①**开关共三层、语义不同**——预设级行内开关改 `PromptPreset.enabled`（全局，`e9c0f6ea`）；预设详情卡片开关改 `ModeInjection.enabled`（全局，`5bbf9ca3`「预设条目行内开关」，2026-09-17）；选择器详情（对话内扩展面板 + 助手详情页共用 `ExtensionPicker`）的开关改**本助手绑定**、源码注释明写「绝不改条目的全局 enabled」（`66a699da`）。②**生效判定唯一落点**=`PromptInjectionTransformer.collectInjections` 的 `entry.enabled && (绑定 id 命中)` ⇒全局开关与绑定是**「与」关系**（全局关掉则任何绑定都不生效）。③**未接线能力**：`Assistant.promptPresetIds`（预设级整包绑定）全仓**仅 3 处代码引用**（1 声明 + 2 读）、**零写入点**，web-ui/web 模块亦无 ⇒ 恒为空集，「一键启用整份预设」**已预留但无入口**；接线点=`ExtensionPicker` 容器行（现 `trailingContent` 只有 `›`），可照世界书容器行的 `Switch` 写法，**成本很低**；注意「助手级绑定不随会话覆盖」是既有设计意图。④**连带更正 Tier 4 的描述**：「点注入段→跳回条目」原副标题写作「含按条目开关」，现已移除——**该能力早已存在**，Tier 4 真缺口只剩「跳转」，v1.0.7 文案偏差据实收敛为「预览页里确实没有开关（但能力在别的页面存在）」。核对时顺带修正 `peer-apps-benchmark.md` §6.7 ⑤ 两处**已失效的行号引用**（`Assistant.kt:213/:229` → `:485/:501`，基类 `:470`）。无代码改动。

- 2026-09-26：**子代理两步落地 —— ①Phase 1 底座（`f7d0c8e4` + 崩溃修复 `f2db5538`）②对话内派发 + 「按名称调度的模型+提示词」配置组合（`d5472f46`）**——设计稿 `docs/design-subagent-context.md`（§九四项讨论结论 + §十本次落地）。**用户的口径**：「推进允许对话时模型调用并增加『按名称调度的模型加提示词』的配置组合」。
  - **① Phase 1 底座**（用户此前在别处推进）：独立 `agent_run` 表（账本列 `entry_point`/`status` + 轨迹 JSON 列）——**不复用 `Conversation`**，于是设计稿 §2.3 量化的「12 个列表查询 + 3 处聚合（含 FTS 重建）漏加过滤即静默泄漏」**按构造消失**；`SubagentRunner` 独立后台入口（不碰 `ConversationSession`、不碰 `backgroundTextGenerationParams` 的「无工具」契约）、能力信封（信封外工具**不装配**——`needsApproval` 的语义是「等人批」，无人头时它拦不住任何东西）、`DenialGuard`（补 §9.2.1 的盲区：被拒后换参数/换工具继续撞，三个既有护栏都覆盖不到）、运行列表/详情页、手动派发。**落地后真机暴露两处崩溃并修掉**（`f2db5538`）：①「Agent 运行」页 `NoDefinitionFoundException`（忘了在 `ViewModelModule` 注册 VM）；②「开始运行」时 `ForegroundServiceDidNotStartInTimeException`（对别人的前台服务调 `startForegroundService` 前必须先确认它**本次真的会** `startForeground`）。
  - **② 对话内派发 + 配置组合**（本次）：`SubagentPreset` = 调度名 + 描述 + 模型（留空=**跟随当前对话模型**）+ 系统提示词 + 两个能力开关（联网搜索/工作区）+ 工具信封 + 是否需要审批 + 超时；内置 `research`/`review`/`digest` 三个（**id 固定**：内置预设是 `Settings` 字段默认值，随机 id 会让管理页列表 key 每次启动漂移）；`dispatch_subagent` 工具（**阻塞式**，与手动派发**共用同一 `executeRun`** ⇒ 账本/护栏/信封/完成判定完全一致）；预设管理页 + `ToolUIRegistry` 卡片（标题带调度名、摘要复用运行页文案、详情页给「查看运行」入口——**子代理过程不进父上下文，卡片是唯一路标**）。
  - **四个关键设计决定**：①**形态选 A**（预设自带能力开关 + 白名单），**不复用 `Assistant`**——助手的正则/注入绑定会把父对话的提示词污染进子代理，正好推翻「独立上下文」这个唯一卖点；`toRunProfile()` 投影出刻意最小的助手副本（记忆/本地工具/技能/最近对话/注入/正则全清空），单测钉住。②**信封三态**：`null`=**只按能力开关**、空集=不给工具、非空=取交集；`null` 若解成「装配出的全部」，只勾了联网搜索的预设会**静默获得**无条件装配的 MCP 工具（可能写日历/发消息）⇒ 给装配点加**分段视图**（`createToolSegments`，`createTools` 保持原签名只做扁平化），段名从「日志分类」升为**对外契约**；信封解析是纯函数，**run 与管理页预览共用同一份逻辑**（所见即所得）。③**禁嵌套双层**：信封恒定剔除 `dispatch_subagent` 自己（不依赖开关，构造性成立）+ 串行闸兜底。④**阻塞式**跑在**调用方协程**里 ⇒ 用户中断对话即取消 run（落 CANCELLED，不留假 running 行）；超时由预设给（默认 10 分钟 < 手工路径 30 分钟，因为对话在被按住）。另有：全局开关 `enableSubagentDispatch` **默认关**、`entry_point` 分 `chat`/`subagent`、**列表摘要截断下移到落库处**（工具路径要回传全文）、DI 用**延迟解析的 provider** 破 `Runner↔Factory` 构造环。
  - **真机（<device>）端到端验证**：模型按名调度 `review` → run 内装配 3 工具但信封把无条件来源**全剔掉**（0 工具，含 dispatch 工具本身）→ 结果**阻塞回传并被父回答采用** → 账本记 `chat`/已完成 → 卡片「子代理：review / 已完成 · goal」+「查看运行」跳转运行详情均正常；六个语言文案渲染正常。**测试** +29（`SubagentPresetTest` 13 / `SubagentToolsTest` 16）+ 1 处摘要口径调整；全量 **1267 项 0 失败 3 跳过**。**字符串** 37 key × 6 语言（本机无 `OPENAI_API_KEY`，**译文为手工撰写**，非 locale-tui 自动翻译）。
  - **仍未做（Phase 2 剩余）**：升级到人的审批（通知 + run 暂停 + 远程批准）、并行（上限 2）、嵌套、`ContextBudgetPlanner`（预算切分精度）、运行卡实时进度、run→对话反查。

- 2026-09-26：**子代理 Phase 2 第一步之「可观测性收尾」（`609019c4`；嵌套按用户要求不做）**——用户从 Phase 2 剩余项里选 B。①**工具卡实时进度**：`SubagentRunner.progress: StateFlow<RunProgress?>`（runId/标签/步数/最近工具/起始时间），卡片在 loading 时显示「进行中 · 第 1 步 · 4 秒 · search_web」，秒数每秒一跳。**两个设计点**：(a) 卡片**不带 runId 直接订阅**——串行闸保证同一时刻至多一个活跃 run，而它只在「本次调用未返回」时读；(b) 步数/最近工具由**纯函数** `snapshotProgress(currentMessages)` 从轨迹派生（审批续跑与工具结果回填都会重算 messages，自记一份计数必然漂移）。②**run → 对话反查**：`agent_run` 加 `parent_conversation_id`（**可空纯列无外键**，AutoMigration 30→31）——会话删掉后残留悬空 id 是正常情形，展示层判存在再给入口，不靠约束拦删、也不级联删 run；父会话 id 只能由**装配点**透传（`createTools(conversationId=…)` ← `ChatService` 两个调用点），详情页给**带标签**的「回到对话」（图标-only 入口此前被用户吐槽不可发现）。③**同批修掉一处真缺陷（功能曾整体静默失效）**：`enableSubagentDispatch`/`subagentPresets` 是 Settings **顶层平铺字段**，上一轮只加了 data class 字段与 UI、**漏了 PreferencesStore 三件套** ⇒ 值只在内存里活着，当次进程内一切正常、**装包或重启即回默认 false**，`createTools` 里那段条件静默不装配（表症：模型说它没有 `dispatch_subagent` 工具）。**发现路径值得照抄**：先看装配日志的**段列表**（`ChatToolFactory: tool schema … → …`，没有 `subagent` 段）⇒ 顺着「装配条件依赖哪个设置值」查 ⇒ `adb exec-out run-as <pkg> cat files/datastore/settings.preferences_pb` 拉下来发现**两个 key 根本不在文件里** ⇒ 确诊；补三件套后 pb 里出现 `enable_subagent_dispatch=true` 与带 `research`/`digest` 的 JSON，force-stop 重启后装配日志恢复 `subagent 1/300t`。**教训：开关型顶层字段必须做一次「改 → 杀进程 → 重启 → 看效果」**（页面上看到开关打开不算）。**验证**：单测 +7（`SubagentProgressTest` 5 / `SubagentToolsTest` +2），全量 **1274 项 0 失败 3 跳过**；真机（<device>）= 迁移 30→31 后旧 run 全在、`research` 派发时卡片「进行中 · 第 1 步 · 4 秒 · search_web」→ 完成态「已完成 · goal」、详情页「回到对话」跳回父会话成功、run 内信封按能力开关放行搜索（`search 1/438t`）。**字符串** 4 key × 6 语言（手工译文）。

- 2026-09-26：**子代理「升级到人的审批」（`fd7021d0`）——解锁写入类子代理**（设计 §十一；用户口径「继续升级到人的审批（解锁写入类子代理）」，嵌套仍不做）。
  - **形态**：预设级 `toolApproval: DENY | ASK`，**默认 DENY = 零行为变更**（ASK 会让既有预设开始拦人，该由使用者决定）。只有 `needsApproval == true` 的工具才会问；注意判定是**两层**的——工作区工具已有**工作区级**`tool_approvals` 覆盖表，预设的 ASK 是在其上「要不要问人」的开关（真机验证时踩到：测试工作区把 `workspace_shell` 的审批显式关成 false ⇒ run 直接执行，看起来像「审批没生效」，实际是工具本来就不需要批）。
  - **机制**：无头 run **没有新的执行模型**——`executeRun` 变成一个循环：collect → 有 Pending 就「登记待批队列 + 弹通知 + 等人（上限 5 分钟）」→ 把决定**回填进同一份消息** → 再喂回 `generateText` 续跑（与聊天路径 `handleToolApproval` 同形，`canResumeExecution` 就是为此设的）。四个关键点：①**护栏实例提到循环外**（每次批准都是新的 generateText，建在调用内会每次重置——LoopGuard 真机踩过的坑）；②**一条队列两个入口**（`SubagentApprovalQueue` 进程内静态对象；通知按钮走 `exported=false` 的 `SubagentApprovalReceiver`，详情页卡片走 `runner.resolveApproval`）——run 不跨进程存活 ⇒ 队列与 run 生命周期同界，进程被杀后点旧通知 ⇒ `resolve=false` ⇒ 如实提示「已超时」而不是假装成功；③**超时按拒绝、不让 run 失败**（`timeoutSeconds` 是给「跑」用的预算；人不在却把 run 判死等于把错过的通知升级成任务失败），措辞必须与手拒分开（`APPROVAL_TIMEOUT_REASON` vs `USER_DENIED_REASON`，都延续 §9.2.1 的「勿重试勿绕路」契约）；④一轮只等一批（一条通知 + 卡片逐条按钮）。
  - **UI/账本**：新状态 `awaiting_approval`（新取值**不需迁移**）；专用高优渠道 `subagent_approval`（标题「子代理 X 需要批准」、正文逐条列工具名+入参摘要、批准/拒绝对整批生效、点主体 → 运行详情页（`RouteActivity` 支持 `runId` extra））；详情页**逐条**卡片（一批常混着不同风险的动作）；手动派发页恒 `DENY`（用户当场挑的工具，再逐个批准是重复确认）。
  - **真机验证（<device>）**：ASK 预设 + 绑定工作区 ⇒ run 停在 `awaiting_approval` + 通知出现（2 个动作）+ 卡片可用；**卡片批准** ⇒ 用真实结果继续（写入 `/workspace/approval-test.txt` 成功、completed）；**通知动作批准**（run 详情页从未打开）⇒ 同样继续并写入成功；**拒绝** ⇒ 模型绕开该工具继续并把「被拒绝」写进结果。单测 +13（`SubagentApprovalTest` 12 + 预设默认 DENY 回归）；全量 **1287 项 0 失败 3 跳过**。**遗留常开诊断**：`SubagentRunner: run <id> toolApproval=ASK`、`GenerationHandler: approval check: tool=… autoDeny=…`（审批问题只看结果无从判断）。**刻意不做**：「本次会话内记住同意」（要回答记多久/跨不跨 run/撤销入口）。

- 2026-09-26：**子代理 Phase 2 收尾——并行上限 2 + 上下文预算规划器（`c221be9a`）**（用户口径「继续实现并行上限 2、ContextBudgetPlanner」；嵌套仍不做）。两者是一对：并行决定「同时能跑几个」，预算决定「每个能吃多少窗口」。
  - **并行上限 2**：`ConcurrentRunGate`（纯状态机）替代单个 `activeRunId`；**满了就拒绝、不排队**（排队会让模型在派发工具里无限等，而一次 run 可能跑十几分钟）；上限 2 的意义是「一个在等审批时另一个能跑」，不是吞吐（§八 手机 RAM/电量/发热）。**每 run 一个 Job**（`activeJobs: Map<Uuid, Job>`）：聊天路径的 run 挂**调用方 Job 的子 Job**（`Job(coroutineContext[Job])` + `withContext(runJob)`）⇒ 既能只取消这一个 run，又能让「中断本轮对话」照旧级联取消它；`cancelRun` 按 id 精确取消（**旧实现只有一个 `activeJob` 字段，并行下「取消 A」会打到 B 身上**）。**进度单条→列表**：§10.6 那个「串行闸 ⇒ 卡片可不带 id 直接订阅」的推论在并行下失效，卡片改为按 **(预设名, 任务目标)** 认领自己那一条（两者都在它入参里，无需额外接线）；认领不到且只有一条时退回，多条则显示聚合态——宁可少说，也不把别人的步数当自己的。
  - **上下文预算规划器**（peer ② 的 usage 锚点估算）：动机是 run 的轨迹单调增长而它**既无压缩也无 `contextMessageLimit`**（合成 profile 里是 0）⇒ 撞满窗口 provider 直接报错、长 run 白跑。**usage 锚点优先**（最后一条助手消息的 `usage.promptTokens`），有锚点时不叠固定开销（锚点已含 system + 工具 schema）；无锚点退化为**复用** `utils/ContextLength.kt` 的同口径估算 + schema 估算（刻意不另写启发式，防三方量纲漂移）。判定 `Ok` / `Warning`（≥80% 只记日志——run 的产物是「已经攒到的结论」，能继续就该继续）/ `Exhausted`（剩余 < 4K ⇒ **优雅停止**、结果照常回填、账本记 `failed(context_budget)`）。**检查位置=执行工具前**（与另两个护栏的「执行后判定」不同：撞满窗口是硬墙，先停才能保住已攒到的结果）；聊天路径不传 guard ⇒ 零行为变更。护栏家族分工：LoopGuard 防「反复撞同一个」、WorkspaceTurnBudget 防「输出堆成山」、本规划器防「窗口被轨迹吃满」。
  - **验证（诚实记账）**：单测 +18（`ConcurrentRunGateTest` 5 + `ContextBudgetPlannerTest` 13），全量 **1305 项 0 失败 3 跳过**；真机（<device>）**部分验证后用户中止**——已确认启用状态下两次手动派发在并发窗口内都被接受、有 run 活跃时「新建运行」对话框仍可打开；**未在真机确认**：①第 3 个被拒绝 ②预算逐步日志与「耗尽优雅停止」（两条由单测覆盖语义）。附带发现（与本轮无关）：用 `探讨和实现` 助手手动派发时 run 立即失败——该助手绑定的 `[Ant-c]gemini-3.7-flash` 在 relay 上「无可用渠道（distributor）」，既有模型/渠道问题。

- 2026-09-26：**工作区「长按多选导出」评估（仅设计，未实现）**——用户提「工作区长按多选导出，评估一下」，落成 `docs/design-workspace-multiselect-export.md`。**核心结论**：技术上很便宜（本仓**已有多选先例**——`ChatList` 的选择态 + `components/ui/ListSelectableItem` 的 Checkbox + 底部 `HorizontalFloatingToolbar` 的「清空/全选/确认」，连组件都是现成的；文件系统是**普通宿主机目录** `files/`+`linux/`，FILES 区打 zip 就是纯 `File` 树遍历），**真正的决策只有三个**：①产物形态（zip 单文档 还是 新引入 `OpenDocumentTree` 目录授权逐个写）②文件夹能否被选中（推荐能，递归打包）③首版是否含批量删除（逻辑侧零新增，因删除已支持 recursive）。**顺带核实出一个既存缺口**：**目录现在根本导不出来**——菜单里导出/分享只在 `!entry.isDirectory` 时出现，且 `WorkspaceManager.exportFile` 有 `require(file.isFile)` ⇒ 「导出文件夹」目前做不到，本需求实际上同时补这个洞。**另更正一处事实错误**：`design-side-file-panel.md` §八.4 与相关记忆声称「详情页已有导出到目录（SAF 目录授权）」——**全仓零处 `OpenDocumentTree`**（只有 8 处单文件 `CreateDocument`），已在两份文档里就地更正。**风险清单**：大目录必须**流式 + IO 线程 + 可取消**（本仓有主线程文件 IO 直接 ANR 的前科）、zip-slip 条目必须拒（已有先例）、**选择态下进目录要禁用且切目录一律清空**（否则「跨目录选择」语义立刻冒出来）、长按是隐藏手势⇒ 建议 ⋮ 菜单里给等价入口（双入口）、超阈值（200MB/2000 项）先提示再压。**成本**：档 1（FILES 多选 + zip 导出）≈1 提交；档 2（批量删除 + 目录授权导出）≈1 提交；档 3（rootfs 导出）不建议现在做（树可能数 GB、含特殊文件，且 rootfs 在备份里本就排除）。**2026-09-26 用户已定四条**：①**「打包」作为可选项**——导出给两种产物（打包 zip / 逐个导出），因此**目录授权 `OpenDocumentTree` 进首版**（全仓首次使用），成本从 ≈1 提交升到 **≈2 提交**；一个细节让「可选」更自然：**只选中一个文件时不打包**，直接走既有 `CreateDocument`；另**两种产物的失败语义不同**（zip=全有全无；逐个=可能部分成功 ⇒ 必须报「成功 N / 跳过 M / 失败 K（含名单）」）。②**同批做批量删除**。③空目录**保留**（用户未表态，按推荐默认值锁，翻案成本极小）。④**LINUX 区首版禁用并说明**。**优先级诚实评估**：锦上添花（单文件导出已能用），但比多数锦上添花更值（补了文件夹导不出的洞 + 组件现成）；建议与 `design-side-file-panel.md`（侧边栏内联文件管理）**一起考虑**——两者动同一块 UI，选择态形状应在共享组件抽取时一次定对。

- 2026-09-26：**工作区长按多选导出落地（`7d8e4592` 共享组件边界 + `26e9fa29` 多选/导出/批量删除）**——用户决议见 `design-workspace-multiselect-export.md` §六，并选「**定共享组件边界再做多选**」。
  - **先抽共享组件**：`ui/components/workspace/WorkspaceFileBrowser.kt`（列表/卡片/路径栏/区域选择器/空错态/选择工具条），边界五条写进 KDoc（无状态 / **选择态由宿主持有** / 宿主专属能力用可空回调 / 布局差异只靠两个开关 / **选择态点击语义由组件统一判定**），详情页 907→633 行、行为不变——这条是为 `design-side-file-panel.md`（侧边栏内联浏览）铺路：将来直接复用，不必再搬一次选择态。
  - **两种产物**（用户决议「打包作为可选项」）：单个文件不打包（走既有 `CreateDocument`）；其它二选一——**打包 zip** / **逐个导出到目录**（`OpenDocumentTree` 全仓首次使用，**刻意不持久化授权**）。纯层 `data/export/WorkspaceExport.kt`：条目名**拒绝逃逸**（`..`/绝对路径，写方向的 zip-slip）、`relativeTo` 边界、**保留空目录**、目录条目以 `/` 结尾、非法或已消失项**只跳过**、符号链接不跟随、**流式 zip 不落临时文件**；新增 `WorkspaceBatchResult`——**「跳过」这一档必须被计数报出**（逐个导出会部分成功：已导出 N · 跳过 M ← 同名不覆盖 · 失败 K）。`DocumentFile` 1.0.x **没有写流 API**⇒ 写通道由调用方传入（不把 Context 灌进数据层），并为其加 `androidx.documentfile` 依赖。
  - **批量删除** + **rootfs 区不进导出**（弹说明：可能数 GB、含特殊文件，备份里同样理由排除，需要时重装）+ 大目录先确认（超 200MB / 2000 项；含文件夹或 >50 项才做体量预览，小选择不付这次遍历成本）。
  - **验证**：单测 +9（含 **zip 字节级往返与磁盘内容一致**），全量 **1314 项 0 失败 3 跳过**；真机（<device>）走通 长按→选择态→全选→打包 zip→系统保存→**拉回校验 5 条目 / 大小一致 / 无损坏**，测试产物已删；**未验证**：逐个导出到目录的 SAF 树写入（无单测）、批量删除、rootfs 说明弹窗。

- 2026-09-26：**真 tokenizer 落地（Kotlin BPE / cl100k_base；`25141095` 引擎 + `05c34f69` 接线）**——设计稿 `docs/design-tokenizer.md`。**动机是量化的**：全仓 token 估算此前是字符启发式（CJK 1.5 字符/token、其余 4 字符/token），而调用它的地方**全是预算判定**（占用条 / 自动压缩触发 / 记忆注入预算 / 子代理上下文预算 / PromptInspect 逐段）；用 44 条样本对 tiktoken 0.13.0 真值比对：**启发式/真值中位数 0.64、区间 0.17–2.0** ⇒ 系统性偏低＝该压不压、该停不停。
  - **实现**：`common` 里的字节级 BPE（tiktoken 同款算法：预分词正则 → UTF-8 字节 → GPT-2 bytes_to_unicode → 按 rank 合并相邻对及其全部出现）。词表=官方 rank 表 1.68MB → 紧凑二进制 744KB → **gzip 456KB**，放 `common/src/main/resources/tokenizer/`（**Java 资源而非 assets**：Android 与 JVM 单测都按 classpath 读，不需要 Context/AssetManager）。`TokenizerRegistry` 是唯一入口：**后台预热**（真机 1389ms），**未就绪/失败即返回 null ⇒ 调用方退回启发式**（行为不中断）+ 512 条有界缓存。接线只在两处出口：`app` 的 `estimateTokens/estimateTokenCount`、`ai` 的 `ToolSchemaSize`。
  - **两个只有真机才暴露的坑**：①**Android 的 `Pattern` 不支持 `UNICODE_CHARACTER_CLASS`**（桌面 JVM 支持）⇒ 单测全绿、真机 `ExceptionInInitializerError`；解法=把 `\s` 展开成显式 Unicode 空白集、`\S` 写成 `[^WS]`，**去掉该 flag 后 44 条向量仍逐条一致**（等价性由此证明）。②**aapt2 对资源里未转义的单引号**报 `Invalid unicode escape sequence`（韩文 legend 中招）⇒ `\'`；另 Kotlin 转义表无 `\f`（用 `\u000c`）。③「安装成功」不能靠外层 `runCatching{}.isSuccess`（内层失败不抛会被误报 ok，首版日志 `ok=true vocab=null`）。
  - **实测**：批量 **114K 字符 49ms**（首版 180ms；优化=片段级缓存 + 最长 token 剪枝）；常驻内存 **+12MB**（生产不保留解码表，保留再多 +14MB）；真机数值对照=预览页 `预估 token ≈ 8126` vs 该会话 API 回传 prompt tokens **8.5K**（偏差 ~5%，换之前启发式约低 35%）。
  - **验证**：44 条 tiktoken 参考向量逐条一致 + encode/count 长度一致 + decode 往返 + 词表大小 + 缓存幂等 + 内存量级；全量 **1322 项 0 失败**。**后续**（未做）：o200k_base 与各家自有分词（cl100k 对 OpenAI 兼容是精确、对其它是近似）、按模型选编码、低内存 `release()` 调用点。

- 2026-09-28：**发布 v1.0.16 (277) 草稿**——用户「发一版新草稿」。**起止范围**：v1.0.15 的 276 资产建于 `2fd995dd`（09-26 19:28），其后落地的全部改动即本版内容：①**大文件备份加固 P0**（`8edfe25c`：STORED 采样判定跳过 DEFLATE + S3 分片 8MB/片 + 单遍哈希结构性解决）②**ST 动态宏运行时**（`7e1bb289`：random/roll/setvar-getvar-addvar + innermost-first 嵌套 + 无效宏保留字面量 + 按次装配作用域）③**上游 2.5.5 吸收 7 项**（`24dcdcce` 第一批：设置读取失败被重置为默认值（两层修 + `SettingsStoreResilienceTest`）/编辑器 saved state 崩溃/MCP 空 header/代码块行号/Google propertyNames；`47d5d81c` 第二批：Responses toolCallId 归一（**本仓原实现本就把 `fc_xxx` 当 call_id 回传，不只是网关容错**）/技能保存原子写）④**数据恢复页 + 图片生成快捷方式**（`6ed0d0b8`）。**流程**：提交未落地的两项功能 → bump `f24f32fe`（276→277、versionName 1.0.15→1.0.16）→ release 构建（**~5m**）→ aapt2 核实 `versionCode=277` / `1.0.16` / 仅 `arm64-v8a` → **release 装机冒烟零 FATAL** → 草稿 release（title `v1.0.16 (277)`，双语说明 13+13 条）→ 资产上传 **77,428,729 字节** → **字节级校验：服务端 digest `sha256:a5fb9bbf…` == 本地 sha256**（走 `gh release view --json assets` 的 digest 字段，未下载）+ 用户手动下载亦通过。README 标记更新为「正式发布 `v1.0.15` ｜ 草稿 `v1.0.16`」。**待用户转正**。

- 2026-09-26：**发布 v1.0.15 (275) 草稿**——用户「先到这里，发一版新的草稿」。**本版相对 274 资产的全部内容**（274 资产上传于 2026-09-24 20:25，此后落地的）：①**子代理全套**（Phase 1 账本/后台入口/信封 → 对话内派发 + 预设组合 → 可观测性 → 升级到人的审批 → 并行上限 2 + 上下文预算）②**工作区长按多选导出**（zip / 目录两种产物 + 批量删除 + 共享文件浏览组件抽取）③**真 tokenizer**（cl100k_base BPE，token 计数从字符估算换真值）④**rootfs 工具单轮总预算**（`947f92a6`）。**流程**：bump（`4d807fdd`：versionCode 274→275、versionName 1.0.14→1.0.15）→ 先清 `app/build/outputs/apk/release/*.apk` 防陈旧产物 → release 构建 **6m45s** → aapt2 核实 `versionCode=275` / `1.0.15` / 仅 `arm64-v8a` → **release 装机冒烟零 FATAL**，且顺带验到 **release/R8 下词表资源仍可加载**（`tokenizer install ok=true vocab=100256 in 496ms`——比 debug 的 1389ms 快）→ 草稿 release（title `v1.0.15 (275)`，双语说明 16+16 条）→ 资产上传 77,390,729 字节 → **字节级校验：GitHub 服务端 digest `sha256:dd59ec23…` == 本地 sha256**。**资产比 274 大 ~607KB**（词表 456KB gz + 代码），与预期一致。另：README 标记更新为「正式发布 v1.0.14 ｜ 草稿 v1.0.15」。**待用户转正**。
- 2026-09-26：**工作空间 AI 后台任务三件套落地（`b6a5351d`）**——roadmap 2026-09-10 条目 ④、peer-apps-benchmark §二.2 的待办，落地**长任务不阻塞对话**。**问题**：同步 `workspace_shell` 有两处死区——比一轮对话活得久的命令（600s 上限砍掉）与输出极大的命令（128KB 头部截断结果直接进上下文）。**实现**：①`workspace/WorkspaceBackgroundTasks.kt`——`TailBuffer`（尾部 8KB + 累计字符数 + 截断标记；与同步 `StreamCollector` 的**头部**缓冲相反，长任务的结论在结尾）、`pumpText`（异常不逃逸）、状态机 `RUNNING/EXITED/KILLED/FAILED`（`killRequested` 独立标记决定终态，强杀路径的退出码不可靠）、`WorkspaceTaskRegistry`（并发上限 4 **拒绝不排队**：排队会造出「为什么还没开始跑」的不可观测状态；完成即淘汰保留 20，运行中永不淘汰；启动失败不占名额）、`ProotBackgroundLauncher`。②`ProotShellRunner` 抽出 `start(context): ProotStart`，同步 `execute` 与之共用——绑定挂载 / `PROOT_NO_SECCOMP` / `PROOT_LOADER`/`PROOT_TMP_DIR` 两处各拼一遍命令行早晚漂移。③三工具 + 审批分档（run_background/kill 需审批 = 占资源 + 破坏性 + 模型看不到输出；status 免审批 = 只读，否则轮询变审批轰炸；工作空间级覆盖仍优先）+ 三张工具卡片 + 工作空间设置页审批项 + 6 语言文案。**两个必须记住的设计点**：①**LoopGuard 必须把 `workspace_task_status` 加入轮询豁免**——长任务轮询同一 `taskId` 是唯一正确用法，不豁免会在第 3 次轮询撞上「相同调用重复 3 次」判停，等于把整个功能废掉（`workspace_run_background` 不豁免：反复启动同一条命令仍是要拦的失控形态）。②**kill 与 start 的竞态**不用 sleep 等待（`launch` 在注册表锁外执行），而是 `kill()` 置 `killRequested` 后看 handle + `start()` 拿到 handle 后补一次 kill，两侧各补一次请求不会丢。**如实记录的边界**：任务表是进程内内存态（重启即丢，而 proot 子进程可能成为孤儿继续跑 ⇒「查不到」不等于「已停止」）；应用后台时采集线程可能被冻结；rootfs 未装时 run_background 返回 `FAILED` + `error: Rootfs is not installed`（比抛异常可诊断）。**测试**：`WorkspaceBackgroundTasksTest` 15 项（TailBuffer 语义/pumpText 中途 IOException 保留已读/状态机/并发上限与释放/失败不占名额/淘汰/按 root 隔离；真实进程启动隔离在 `WorkspaceTaskLauncher` 接口后用 `FakeLauncher` 替代）+ `WorkspaceBackgroundToolApprovalTest` 6 项（含「默认值表与已声明工具集一致」防静默失效）+ `ToolCallLoopGuardTest` 轮询豁免 2 项；app/workspace/common 三套全绿（1009/39/31）。设计稿 `docs/design-workspace-background-tasks.md`（含 §八 边界声明与 §十 后续：任务可见性 UI、输出落盘、stdin/交互、宿主机 shell 变体）。**未做**：真机端到端（需 rootfs）。
- 2026-09-26：**后台任务真机验证——发现并修掉一个跨越全局的终止缺陷（`99802f28`）**。后台任务三件套（`b6a5351d`）真机跑 5 项过 4 项，唯一失败的是「kill 后应到终态」：30s 轮询仍 `RUNNING`。取证（三轮 instrumented 诊断 + `ps` 快照）后定位：**`Process.destroyForcibly()` / `destroy()` 对本机 proot 子进程与「有子进程的 shell」完全无效**——kill 后 20s，ps 里 proot/bash/sleep 三个进程全都还在、`waitFor()` 永不返回；同形状目标改用 `Os.kill(pid, SIGKILL)` **立刻死亡**（同 pid、同命令，唯一变量是 API）。**连带发现既有缺陷**：同步 `workspace_shell` 的超时/取消同样走 destroyForcibly ⇒「工具报超时、命令继续在设备上跑」；另发现**只杀直接子进程会让子孙变孤儿**（PPID=1 继续跑，`sleep` 实测存活）。**修法**（`workspace/ProcessSignals.kt`）：每条命令带唯一标记 `rikkahub-task-<id>`（进 argv 的 `$0` 槽位，Android 的 `Process.pid()` 不在 SDK API 里 ⇒ 只能标记→`/proc` 反查），终止时**只在自己的后代子树内**按标记定位进程树根，读 `/proc/<pid>/stat` 父子关系后**自底向上** `Os.kill(SIGKILL)`（顺序反了会让子孙变孤儿、再也定位不到）；同一路径也接给 `readResult` 的超时/中断分支。后台注册表另外把 kill 改成**立即置 `KILLED`**（信号下发即终态），回收线程只补 `exitCode`。**真机 instrumented 6 项全绿 3.5s**（含「kill 后 ps 里再无该命令的任何进程」这条防孤儿断言；修前 34s/1 失败）。JVM 新增 `ProcessSignalsTest` 11 项（匹配范围/杀树顺序/pid 复用不死循环/`stat` 解析含 comm 带括号的坑）。全量 app 1009 / workspace 50 / common 31 零失败。**教训：`destroyForcibly()` 这类「平台承诺」在真机上必须验证——单测与状态机全绿完全掩盖了「信号根本没送达」。** 设计稿 §七之一 记录证据表。
- 2026-09-26：**后台任务收尾三件（时长上限 / 完整输出落盘 / 边界文案）——真机 8 项全绿**。起因是复盘「未做部分的影响」时发现两个真问题：①**没有时长上限**——并发上限只管数量，管不住时长，模型起 4 个长命令后本轮结束，手机就一直跑；②**尾部 8KB 之外的输出永久丢失**——长编译的失败原因常在**早期**，模型来查时早被冲掉，「长任务不阻塞对话」的配套「能查回结果」是打折的。落地：①**时长上限** `DEFAULT_MAX_TASK_LIFETIME_MS = 30min`（常量不进 Settings，与 LoopGuard 阈值同款取舍），到点由看门狗按**同一条终止路径**杀整棵树、状态记新枚举 `TIMED_OUT`（与用户主动 kill 的 `KILLED` 分开：「超时」和「被否决」对模型是两件事）；工具结果带 `lifetimeLimitMs` + 运行中的 `expiresInMs`，让模型自己决定「继续等」还是「先干别的」。②**输出落盘**：新增 `TaskLogSink` 双写——尾部缓冲给上下文、完整日志写 `<工作区>/.tasks/<id>.stdout.log` + `.stderr.log`（每块 flush），工具结果回报 **rootfs 内可见路径**（`/workspace/.tasks/...`）⇒ 模型用现成的 `workspace_read_file` 读全量，**零新增工具、零上下文成本**；写盘失败只降级为「没有日志」不影响任务；生命周期对齐任务表（淘汰任务即删日志、按修改时间保留 24h、每个新任务顺带清一次过期日志），`.tasks/` 在文件浏览器里可见（点前缀只为与用户文件区分）。③**边界写进描述**：后台任务**不支持交互输入**（stdin 立即关闭 ⇒ 提示类命令读 EOF 直接失败），描述里明说「改用 workspace_shell 或终端」——把「不做伪终端」这个决定如实传达给模型，避免它走弯路。**测试**：JVM `WorkspaceBackgroundTasksTest` 15→24 项（看门狗到点杀进程且状态为 TIMED_OUT、正常结束不被误杀、上限 0 表示不限制且不报剩余时间、剩余时间倒计时、双写 sink 的尾部+文件内容、写入失败降级、无文件模式、过期日志清理、日志路径命名）；真机 instrumented **6→8 项全绿 10.6s**（新增「`seq 1 20000` 的日志文件必须是全量 >100KB 且含最早行」与「超过 5s 上限的任务被自动终止且 ps 无残留」）。
- 2026-09-26：**后台任务可见性 UI 落地（工作空间详情页）**——补齐「AI 能起长任务」的配套：任务表在内存、输出在磁盘，此前用户**只能在发起它的那条对话的工具卡片**里看到任务，切走会话既看不到手机上跑着什么、也没有手动终止入口。实现：`WorkspaceDetailVM.refreshTasks/killTask` + 页面 `LaunchedEffect` 轮询（**有任务 2s / 空闲 10s**，注册表是内存态没有 Flow 可订阅），卡片列最多 5 条、运行中排前，每行=命令预览（等宽单行省略）+ 状态行（本地化状态 · 已运行 X 或 X 后自动终止 · 退出码）+ 终止按钮；时长格式化抽成纯函数 `formatTaskDuration`（**向上取整**：剩 400ms 显示 1s 而非 0s）并配 4 项单测；10 个 key × 6 语言。全量 app 1013（+4）/ workspace 59 / common 31 零失败，debug APK 已装机。**未做视觉验证**（编译 + 单测通过；界面需用户实机查看）。
- 2026-09-26：**v1.0.15 草稿资产已重建（275 → 276，同名 tag 替换）**——用户「重建草稿」。草稿资产建于当日 07:59，之后又落地 8 个提交（后台任务三件套 `b6a5351d`、终止缺陷修复 `99802f28`、时长上限+输出落盘 `5bcff906`、可见性 UI `d4ddb676` 等），故必须重建。**流程**：bump `2fd995dd`（275→276，versionName 保持 1.0.15；沿用 275 会让「dumpsys 看到 275」无法区分新旧——与 09-14 v1.0.4 258→259 同款理由）→ **push**（本轮把先前未推送的 9 个提交一并推上 origin，避免转正时 tag 落在旧 HEAD 的历史坑）→ 先清 `app/build/outputs/apk/release/*.apk` → `bash scripts/build.sh release`（**5m45s**，68 executed）→ aapt2 核实 `versionCode=276` / `1.0.15` / 仅 `arm64-v8a` （**77,418,553 字节**，比 275 大 ~28KB）→ release 装机冒烟（**零 FATAL** + 业务日志 `tokenizer install ok=true vocab=100256 in 485ms`，确认 R8 后 java 资源链完好）→ `delete-asset --yes` + `upload`（同一后台作业内连做，避开「草稿无资产」窗口）→ **服务端 digest `sha256:303c29dd420d…` == 本地 sha256**（字节级校验，未走下载）→ `gh release edit --title "v1.0.15 (276)" --notes-file`。**更新日志新增 2 条**（双语，共 10 条）：「AI 可以起后台任务，长命令不再占住对话」（含最多 4 个并发、随时问进度、可终止、30 分钟自动停、完整输出落 `.tasks/`）与「修复：命令超时/取消其实没停下来」。README 标注无需改（versionName 未变，仍是「正式发布 v1.0.14 ｜ 草稿 v1.0.15」）。

- 2026-10-01：**上游同步 + issue 清理批次（6 提交）**。先 ff-only 同步 master `00c8d53a`→`b2d73a65`（4 提交，评估入 `upstream-analysis.md`），再做 issue 全量复核，落定如下。
  - **①#1974/#1968 MCP `$defs` 丢失 → 整请求 400（`95fe3e5b`）**——本次最高优先。用 `$defs`/`$ref` 的 MCP 服务器（Devin、滴答清单实测）会让 `toSchema()` 里被丢弃的 `$defs` 留下悬空 `$ref`，provider 以 `Pointer '/$defs/...' does not exist` **拒绝整个请求**（普通对话也发不出去）。新增 `McpSchemaRefs.kt` 在 `toSchema()` **就地展开**：多级指针、引用链递归、JSON Pointer 转义（`~1`/`~0`）、`$ref` 兄弟键合并；**失败姿态=宁可宽松不可拒绝**——悬空/递归/非 `$defs` 指针一律退化 `{}`（JSON Schema 里表示接受任意值），绝不留下 `$ref`；环检测靠解析栈（自引用与相互递归都截断）。**不做透传**：Gemini function schema 不支持 `$defs`。存量损坏 schema 由既有 `syncTools`（每次连接重新 derive 落库）自愈。单测 17 项，其中 3 项是**真实接缝测试**（把 `toSchema` 提为 internal，用 SDK 自己的序列化器解析服务器响应形态的 JSON，证明 0.14.0 确实把 `$defs` 暴露为 `defs`），另 1 项钉住「不用 `$defs` 的常规 schema 转换结果逐字节不变」。
  - **②分叉标题序号递增（`95fe3e5b`，上游 `95fed05e` / #1961）**——`Chat(1)` 再分叉不再叠加成 `Chat(1)(1)(1)`：先剥尾部 `(N)` 作基名、从 `N+1` 起找空位。保留 fork 两处有意差异（源标题为空仍保持空、序号取首个未占用值）。**真机三代分叉验证 `(1)/(2)/(3)`**。
  - **③#1975 未配置标题模型不再静默（`30a6b8e5`）**——`generateTitle`/`generateSuggestion` 的「找不到模型」「模型无提供商」两处原本静默 `return`（点「重新生成标题」毫无反应）。现上报错误卡（标题 + 可操作说明 + **既有** `CheckTitleModelSettings` 跳转链，ErrorCard 早有该渲染、只是从未被触发）+ 抽屉路径即时 toast（错误卡在聊天页会被抽屉挡住）。**关键约束=去重**：两者每轮生成都会尝试，不去重就每轮刷屏 ⇒ `shouldReportConfigError`（纯函数 +3 单测）规定同会话同 solution 只报一次、点掉后恢复。**一处现状**：`fastModelId` 无清除入口（默认随机 UUID，只有全新安装才是「未配置」），用户侧对应「所选模型/服务商已失效」。
  - **④模型列表按供应商折叠（`30a6b8e5`，上游 `b2525e7b`）**——表头可点折叠、箭头指示、状态存 SharedPreferences、**搜索一律展开**（搜索是找模型，藏在折叠组里等于搜不到）。**fork 比上游多两处联动必须同改**：底部供应商 Badge 条的 `providerPositions` 与初始定位 `selectedModelPosition` 都要改用 visible 列表，否则折叠后 Badge 点击跳转错位、选中项在被折叠组时定位错位。真机验证折叠/搜索展开/落盘持久化。
  - **⑥TTS 提供商可停用 + 内置提供商可删除（`f0db94a6`）**——需求「文字转语音提供商可停用；系统 TTS 作为兜底，在没有其他供应商时保持启用；并允许删除内置提供商」。①**可停用**：`TTSProviderSetting` 加抽象 `enabled`（11 个子类型 override，默认 true ⇒ 零迁移）+ `withEnabled` 分发（密封基类无统一 `copy`）；`getSelectedTTSProvider` 跳过停用项且**选中项被停用时不静默回退**（返回 null ⇒ `isAvailable=false`、朗读按钮变灰），「选中 id 已删除」才退回首个启用的；设置页每卡加 Switch + 「已停用」徽标、停用选中项时自动改选。②**系统 TTS 兜底**（`ensureFallbackTtsEnabled` 纯函数）：它走设备引擎免 key，是唯一「永远可用」的选项 ⇒ 不允许停用到**一个启用的都不剩**，读路径统一应用；系统 TTS 自身被删（墓碑）时不兜底、如实置不可用；UI 被拉回时给 toast 说明。③**内置提供商可删除**：新增 `Settings.removedTtsProviderIds` 墓碑 + `refillDefaultTtsProviders`（与 `refillDefaultAssistants` 同款），**必须同时去掉原来的 `ifEmpty { DEFAULT_TTS_PROVIDERS }`**——用户把内置项全删光后列表本就为空，那个写法会整体复活、删除等于没生效（这就是「内置提供商删不掉」的根因）。单测 `TtsProviderEnabledTest` 13 项；真机验证通过。
  - **⑤ASR 提供商支持停用（`2ea6411a`）**——需求「允许禁用语音识别提供商配置」。`ASRProviderSetting` 加抽象 `enabled`（5 个子类型 override，默认 true ⇒ **旧 JSON 缺字段读默认值，零迁移**）；`withEnabled` 扩展必须分发到子类型（密封基类没有统一 `copy`，与 `SearchServiceOptions.withEnabled` 同因）；`getSelectedASRProvider` **跳过停用项**且**选中项被停用时不做静默回退**（用户既然关了它，就不该悄悄改用另一个可能计费的提供商），返回 null 让两条消费路径如实提示（听写 `asr_error_not_configured` / 语音模式 `chat_page_voice_configure_asr`）；设置页每卡加 Switch + 「已停用」徽标，停用当前选中项时自动切到第一个仍启用的（对齐 `SettingSearchPage.onToggleEnabled`）。单测 6 项。**注**：TTS 提供商同样还没有 `enabled`，如需一并支持可复用同一套做法。
  - **验证**：全量单测通过（新增 `McpSchemaRefsTest` 17 / `ChatServiceTest` +8 / `AsrProviderEnabledTest` 6）；debug 装机真机验证 ②④⑤ 通过。

- 2026-10-01：**对话内 JSON 代码块一键设为助手（#1942，`8e556aff`）**——需求：跟 AI 共创角色卡时，此前「复制 → 存文件 → 进助手页 → 文件选择器翻目录」在手机端很麻烦，要在代码块右上角一步加载为助手。**评估结论是「纯胶水」并已落地**：解析（`parseAssistantFromJson` 已是 internal 纯函数，吃 JsonObject、不需要文件/Uri）、落库（追加助手一行）与按钮位（`HighlightCodeActions` 作用域内已有 `code`/`language`/`completeCodeBlock`）三块全现成。实现=新增 `parseAssistantFromJsonText`（只多一层「文本 → JsonObject」，把「JSON 不合法」也转成可读错误）+ `appendAssistant`（**追加助手的唯一实现**，`AssistantVM.addAssistant` 一并改走它）+ `importAssistantFromJsonText`（非助手页的一站式入口）；文件导入的纯 JSON 分支也改走文本入口，消除第二处 text→JsonObject→parse。**门控两条**：只认 json 围栏、**代码块必须已闭合**（流式输出中 JSON 不完整，点了必然失败）。**收益直接来自复用**：酒馆卡 v1/v2/v3 与「这是预设不是角色卡」「缺 spec」等友好指引自动生效，既有 12 项 `AssistantImporterTest` 对它同样有效。有意取舍=渲染器经 `koinInject<SettingsStore>()` 获得落库能力（组件被 6 处调用，穿参代价高；它本就依赖 `LocalNavController`/`LocalSettings` 这类环境上下文），已写进注释。单测 +3；真机验证：自造 ```json 块 → 按钮出现 → 点击落盘（DB 核实）→ 测试会话与 3 个测试助手删除干净。**注**：导出图不含该按钮（导出走 `generateMarkdownHtml` 的 HTML 串，不经过 `HighlightCodeBlock`）。

- 2026-10-02：**上游同步 2.5.6（`b2d73a65` → `4e4bfa62`，15 提交）——#1974/#1975 上游自修，而 fork 均已先行自研落地且等价或更优 ⇒ 零吸收负担**。master 已 ff-only 快进至 `4e4bfa62`，逐条评估见 `upstream-analysis.md` 10-02 节。
  - **#1974（MCP `$defs` 内联，上游 `cf79246b` 新 `McpToolSchema.kt`）** 与 fork `McpSchemaRefs.kt`（`95fe3e5b`）逐条对照：同级键优先 / 循环引用检测 / 宽松降级**全部具备**；上游仅两处极端场景略强（循环引用保留 `type`、支持任意 `#/` 指针），fork 版已真机验证 ⇒ **不回吸**。
  - **#1975（标题模型静默失败，上游 `d5f0039b` 改抛异常）** fork 版更优：本地化文案 + `addConfigErrorOnce` 错误卡去重 + 抽屉路径即时 toast（上游只弹错误卡）⇒ **不回吸**。
  - **待吸收（按性价比，已入 handoff §三）**：①`620e38cc` JsonTree 弹窗长按崩溃（**真 bug 在 fork 存在**：`LogPage:458` 在 `:384` SelectionContainer 内、`JsonTree:69` sheet 内 Text 未自包）几分钟；②`4e4bfa62` OpenAI 图片生成自定义分辨率被误丢（fork `OpenAIProvider:292` 仍是 `baseUrl.contains("x.ai")` 旧逻辑，中转域名 `xxx-max.ai` 被误判成 Grok ⇒ 静默丢分辨率）十几分钟；③`3443dd45`/`3a9ae690` Claude Opus/Sonnet 5.5 + Gemini 4 注册（半小时；**`thinkingLevel` 是 fork 没有的 Model 概念，参数切换拆出去**）〔**更正**：不存在这种 Model 概念，见本文件 2026-10-02 模型注册条目的第 ② 点〕；④可选 `282b85f2` 复制助手带记忆、`654a5e1f` 提供商头同名去重语义（fork `addHeader` 追加会重复发送同名头）。
  - **明确不跟**：`7a53065a` OAuth 回调 localhost（fork 用 `rikkahub://` 自定义 scheme，无 loopback）、`2cd62ad2` Gemini Interactions API（1400+ 行，等 `generateContent` 被真弃用再立项）、`#1979` OAuth 增强（fork 已分叉，适配成本 > 收益）、`2d5c51bd` 剪贴板闪退（fork `writeClipboardText` 已 `runCatching` + 无复制按钮路径）。

- 2026-10-02：**修复「自定义提供商类型名在正式版显示成 it9/ft9/bt9」——展示名不得取自 `KClass.simpleName`（`26025c8b`）**。现象：类型选择器在**正式版**显示 "it9"/"ft9"，debug 显示正常的 "OpenAI"/"Google"/"Claude"。**根因（mapping.txt 硬证据）**：选择器用 `KClass.simpleName` 当展示名，release 经 R8 混淆后 `ProviderSetting$OpenAI → it9`、`$Google → ft9`、`$Claude → bt9`。**为什么此前没被发现**：debug 构建不混淆，`simpleName` 恰好等于正确名称 ⇒ **任何行为断言都「看起来正常」，只在正式版暴露**；排查时用 `strings` 搜 "it9" 也搜不到（`strings` 默认最短 4 字符，3 字符的 "it9" 被直接跳过——**这个坑值得单独记住**）。修复=展示名与类名解耦：ai 模块新增 `ProviderType(type, displayName)` 描述符（`ProviderSetting.Types` 改为此列表 ⇒ 新增类型强制写出展示名）+ `providerTypeDisplayName` 实例扩展（唯一来源=Types）；三处消费点改用显式名——类型选择器、模型详情页的 provider-override 标签（同样显示混淆类名、语义恰是「哪个 API 格式」）、TTS 类型选择器兜底（不再回落 simpleName）。**测试**：`ProviderTypeDisplayNameTest`（4 项，钉住三家显式名 + 实例扩展与列表一致）+ `ProviderTypeLabelSourceGuardTest`（4 项，**源码级守卫**：剥注释与字符串后断言三个消费点不再出现 `simpleName`，附剥离器自身行为测试）——之所以必须源码级，正是因为 debug 下行为断言测不出这类回归。**验证**：release 重新构建装机后选择器显示 OpenAI/Google/Claude，**且新构建里三个类仍被混淆为 it9/ft9/bt9**（证明是修复生效而非混淆行为变了）。**同类待办**：`SubagentRunner:513` 的运行失败详情、`McpStatus:34` 的兜底摘要也从 `simpleName` 取文本——那是诊断信息（混淆名仍可与 mapping.txt 对照），暂留。

- 2026-10-02：**修复「请求日志里 JSON 弹窗长按闪退」（上游 `620e38cc` / #1977，`799522f2`）**——上游同步待吸收清单第 ① 项，也是清单里唯一的**真崩溃**。路径：设置 → 关于 → 请求日志 → 点日志条目 → `Request Body` 里点字符串值 → **长按弹窗里的文本即进程崩溃**。
  - **根因**：`JsonTree` 的字符串详情用的是 `ModalBottomSheet`（**独立窗口**），但它没有自己的 `SelectionContainer`，于是沿用了调用方的 `SelectionRegistrar`；长按时 Compose 在这两套布局之间换算坐标，抛 `IllegalArgumentException: layouts are not part of the same hierarchy` ⇒ 进程直接死。**触发条件是「调用方把 `JsonTree` 包在 `SelectionContainer` 里」**——`LogPage.kt:384` 恰好把整块日志详情包了 `SelectionContainer`，`:458` 才调 `JsonTree`；所以同一个 sheet 在别处（如 `ChatMessageTools` 的工具预览，它是 `ChatMessage.kt:459` 那个 `SelectionContainer` 的**兄弟节点**）点开就不会崩。
  - **修法**（与上游同一处、逐字等价）：sheet 内自包一层 `SelectionContainer`；文本仍可选中复制。
  - **验证**（按「先复现后确认」的老规矩）：①未修版真机走上述路径长按 → `FATAL EXCEPTION ... layouts are not part of the same hierarchy` + 进程死亡；②修复版同路径长按 → 无任何异常、进程存活、`glm-4.5-air` 上出现选中手柄；③app 1130 / ai 193 单测 0 失败。
  - **同类排查（结论：本仓仅此一处）**：静态扫「既含 `SelectionContainer` 又含窗口组件（`ModalBottomSheet`/`Popup`/`Dialog`）的文件」命中 6 个——`ChatMessageCopySheet`（已自包，正确范式）、`FavoritePage`、`SettingMcpPage`、`WebViewPage`、`LogPage`（本次）、`ChatMessage`；其中 sheet 与 SelectionContainer 的**嵌套关系是经调用图才成立的**（`JsonTree` 自己持有 sheet），纯括号匹配扫描命中 0 处 ⇒ 这类 bug 只能靠「谁在 SelectionContainer 里被调用」来判断，本仓再无第二例。

- 2026-10-02：**修复「OpenAI 图片生成自定义分辨率被静默丢弃」（上游 `4e4bfa62` / #1978，`bae735da`）**——上游同步待吸收清单第 ② 项。对用中转站的用户是真缺口。
  - **根因**：`OpenAIProvider.generateImage` 用 `providerSetting.baseUrl.contains("x.ai", ignoreCase = true)` 判断「是不是 Grok 端点」，而 Grok 不接受 `size` 参数（判为 Grok 就**静默丢掉分辨率**）。`contains` 是在**整串 URL** 上找子串：凡是域名里含 `x.ai` 的都中招——`https://api.xxx-max.ai/v1`（`max.ai` 里含 `x.ai`）这类中转域名很常见，用户设的分辨率就这么没了（不报错、不提示）。
  - **修法**（与上游同一处，语义一致）：Grok 判断改为**解析 host**——只认 `x.ai` 本身及其子域名（`host == "x.ai" || host.endsWith(".x.ai")`），解析失败一律按「不是 Grok」处理（宁可多发一个参数让服务端报错，也不静默丢用户明确设定的值）；另按上游把 `size == "auto"` 也改成不写进请求体（服务端默认就是 auto，显式写反而会让只接受具体尺寸的服务端 400）。
  - **fork 比上游多改一处（有意）**：上游只改了生成路径，`/images/edits`（`editImage`）仍无条件写 `size`——默认值恰是 `auto`，于是 dall-e-2 这类只接受 `256x256/512x512/1024x1024` 的编辑接口必然 400。fork 让编辑路径复用同一条「auto 不写」规则（仍不做 Grok 判断：编辑走的是用户显式指定尺寸，且 fork 无上游那套 host 语义依据）。
  - **可测性**：判定逻辑抽成纯函数 `isXaiHost(baseUrl)` / `shouldSendImageSize(size, isGrok)`（`OpenAiImageSize.kt`）——原先是内联在 flow 里的两行表达式，正是这种「看起来对」的条件最容易长期错着。
  - **验证**：①`OpenAiImageSizeTest` 7 项（x.ai 及子域名命中；`api.xxx-max.ai` / `relay.max.ai` / 路径里含 `x.ai` / `api.x.ai.evil.com` / 非 URL 一律不命中；`auto` 大小写不敏感地不写；具体尺寸写入；Grok 上任何尺寸都不写）②**真机请求体实测**（`[特价]gpt-image-2-1k` 与 `gemini-3.1-flash-image` 两条：选 `1024x1024` → `generateImage: {...,"size":"1024x1024"}`；选 `auto` → `{...,"n":1}` 无 size；`grok-imagine-image-2.0` → 无 size）。**未观察到**「带 size 的 200 成功响应」：当时可用渠道只有 Grok 那条（其余 503），但**请求体本身就是本 bug 的可观测面**（旧逻辑是「body 里根本没有 size」）。③app 1130 / ai 193（+7）单测 0 失败。
  - **顺带**：真机验证时选过图片生成模型，事后已把它改回原值（`[特价]gpt-image-2-1k`），为此临时启用的 Rinkoai 提供商也已重新停用，测试生成的图片已从相册删除。

- 2026-10-02：**注册 Gemini 4 与 Claude 5 / 5.5（上游 `3a9ae690` + `3443dd45`，并补上此前漏吸收的 `aa487c8c`；`5a25a42a`）**——上游同步待吸收清单第 ③ 项，清单里最后一项功能面改动。
  - **做了什么**：`GEMINI_4` 注册（进 `GEMINI_SERIES` 与 `ALL_MODELS`）；`CLAUDE_SONNET_5`/`CLAUDE_OPUS_5`（上游 `aa487c8c`）与 `CLAUDE_SONNET_5_5`/`CLAUDE_OPUS_5_5`（上游 `3443dd45`）四条注册（进 `CLAUDE_SERIES` 与 `ALL_MODELS`，带 `contextLength(1.m)` 与 `notTokens` 排除 4 代）；`GoogleProvider` 把 `useThinkingLevel = GEMINI_3_SERIES ∪ GEMINI_4` 抽出来给「关闭思考」和「按档位设思考」两处分支复用。
  - **顺手补了一个此前漏吸收的提交**：`aa487c8c`（Claude Sonnet/Opus 5）在 fork 同步区间**之前**（`b2d73a65` 之前），但 fork 注册表里没有这两条——即此前某次同步漏掉了这个文件的改动。既然本次就要动这块，一并补上（不补的话 `claude-sonnet-5` 这种 id 连视觉/工具元数据都拿不到，因为 `CLAUDE_4` 需要 token `4`、5 代 id 里没有）。
  - **新增 `MODEL_CONTEXT_LENGTH` 访问器（fork 缺、上游有）**：fork 的注册表早就在写 `contextLength(...)`（如 DeepSeek V4.1 Flash），却**没有任何读取口**，等于写了个没人能读的声明，也没法测。补上后「5 代 1M / 4 代不误判」这类声明才有断言可钉（上游同名访问器生产代码同样只被测试用到，所以不是新增运行时行为）。
  - **与上游的两处有意差异**：①**保留 `CLAUDE_SONNET_3_5`/`3_7`**（上游 `3443dd45` 顺手删了）——删掉之后 `claude-3.5-sonnet` 会转而命中 `CLAUDE_SONNET_5`（它的 tokens 是 `claude`/`sonnet`/`5`，而 `3.5` 里恰好有 `5`），凭空多出 1M 上下文并失去老 id 的既有元数据；保留时按匹配度取胜的是更具体的 3.5 条目（4 个 token > 3 个），并且已有测试钉住这条。②**不引入 `thinkingLevel` 参数切换**：同步评估里曾写「`thinkingLevel` 是 fork 没有的 Model 概念」，实际核对后要分清——**上游 Model 字段层面**的推理参数切换 fork 确实没有（grep 零命中），但**请求体分支**层面 fork 早就有 `GEMINI_3_SERIES → thinkingLevel`，本次只是把 4 也纳入，**无需引入任何新概念**。
  - **验证**：①`ModelRegistryTest` +2（共 9 项）：Gemini 4 命中 `gemini-4-pro`/`-flash-preview` 且不命中 `gemini-3-pro`、也不被 `gemini-2.5-flash-preview-04-17` 里的 `04` 骗到；Claude 5/5.5 命中与 1M，带日期后缀的 id 也命中，`claude-sonnet-4-5`/`claude-4.5-sonnet` 等 4 代 id 全部拿不到 1M（`notTokens` 兜住），保留的 Claude 3 仍拿到视觉元数据且上下文为空。②新增 `GoogleProviderReasoningConfigTest` 4 项，按本仓既有做法用反射调私有 `buildCompletionRequestBody`，**直接读请求体**断言：gemini-4 写 `thinkingLevel`（OFF→minimal、LOW→low、HIGH/XHIGH→high）且**不**写 `thinkingBudget`；gemini-3 行为不变；2.5 代仍走 `thinkingBudget`（OFF→0）；2.5 pro 的 OFF 依旧什么都不写（既有特例）。③app 1130 / ai 206 单测 0 失败。
  - **未做**：真机端到端（设备上没有任何名字带 `gemini-4` 或 `claude-*-5*` 的模型，无法走通一次真实请求）——注册表命中与请求体两处已由单测直接覆盖。

- 2026-10-02：**顺手查出并补上 4 个「早已合入上游、fork 却漏吸收」的模型注册条目（`a2a0f609`）**——起因是核对 `aa487c8c`（Claude 5）为何也不在 fork 里，于是把上游 master 与本仓注册表的**定义名做了全量比对**（而不是靠 `merge-base` 判断「吸收过没有」——注册表在 fork 是重改区，merge-base 会误报）。
  - **比对结果**：上游独有 9 个名字，其中 4 个是我本次刚加的（Gemini 4 / Claude 5 与 5.5 四条），**剩下 5 个是真缺口**，且其上游提交全都在 fork 已同步区间（`≤ b2d73a65`）之内：`f167a855`（DeepSeek V4 Flash **Vision Exp**）、`04d6c4ea`（Muse Spark / Glimmer，并含一处 Meta 图标匹配）、`c3d6867c`（Step 5）、`068103dd`（MiMo V2.6）。
  - **为什么值得补（不是化妆品）**：注册表缺条目时该 id 会退回默认值（只 `TEXT`、无 `TOOL`/`REASONING`）。用户模型列表里就有 `DeepSeek-V4-Flash-Vision-Exp` —— 这是**能收图**的模型，却因为没有条目而在界面上连图片入口都不给。
  - **补法**：照上游原样加 5 条 `defineModel` + 进 `ALL_MODELS`；`AIIconMatcher` 的 `PATTERN_META` 加 `muse`（Muse 系归 Meta 图标）。
  - **注意生效时机**：注册表只在**拉取/新增模型**时灌入 `inputModalities`/`outputModalities`/`abilities`（`SettingProviderDetailPage` 三处 `MODEL_*` 消费点），**不会回改已存模型的字段**——所以已有模型要等下一次刷新列表/重新添加才会拿到新元数据。
  - **验证**：`ModelRegistryTest` +1 项（10 项；逐条钉住视觉输入与工具/推理，并确认更短的同名条目没被顺带带上视觉，如 `deepseek-v4-flash` 仍是纯文本、`mimo-v2` 仍无视觉）；新增 `AIIconMatcherTest` 3 项（muse 系走 Meta 图标、既有 Meta 匹配不变、`muse` 不误伤其它名字）；app 1133（+3）/ ai 207（+1）单测 0 失败。
  - **留档的教训**：`git merge-base --is-ancestor` 只能说明「提交在历史里」，**不能说明 fork 的文件真的含它**（fork 的注册表是手工重改区，同步时会整段漏掉某个文件）。判断「吸收没有」必须以**代码等价性**为准；这次用「定义名全量比对」把注册表这一类缺口一次扫干净。

- 2026-10-02：**可选 ④ 两项一并落地：复制助手带记忆（`27b35815`）＋ 请求头同名去重语义（`a3394e7a`）**——上游同步待吸收清单的最后两项，至此 10-02 同步（15 提交）里**可吸收项全部落地**。
  - **①复制助手可同时复制记忆（上游 `282b85f2` / #1958）**：助手有独立记忆时，点「复制」先弹确认框、可勾「同时复制记忆」（**默认不勾**——复制记忆是更大的动作，得用户主动选）；走全局记忆的助手没有「自己的记忆」可带，没有记忆时也没有可问的，两种情况都直接复制。
    - **fork 与上游的差异全在落库细节**（照抄上游会写出坏数据）：①**新的 publicId**——对外 opaque id 不能两个助手共用，否则审批卡片/删除会指到别人那条；②**保留 importance**——它是记忆自身的属性，注入打分靠它，丢了就退化成纯时效衰减；③**updatedAt 取当下**——时效衰减的时间基准，沿用源时间会让复制来的记忆一上来就被当成陈旧而沉底。另外把 200 条上限的淘汰抽成 `enforceMaxCount`，与 `addMemory` 共用一份实现（同一不变式只有一处）。**不复用 `addMemory`**：那是逐条去重+逐条淘汰的单条路径，复制是批量语义。
    - **验证**：`MemoryRepositoryTest` +4（共 21 项）；**真机走通完整链路**——复制「日常和学习」并勾选 → 副本「日常和学习 (Clone)」拿到 1 条记忆，content/importance 与源一致、`public_id` 不同（`224ec479` vs `15a484cb`）、`updated_at` 为复制时刻；**删除副本后其记忆随之消失**（记忆总条数回到 54），源助手那条始终未动。
  - **②自定义请求头不再产生同名重复（上游 `654a5e1f` 的语义）**：fork 本就有「提供商级自定义头」字段与 UI，缺的是**应用方式**——请求级头用 `.headers()` 铺一遍、提供商级头再逐条 `addHeader()` 追加，于是同名头在 wire 上出现两个值，而服务端取哪个是不确定的（不少中转站直接判非法请求）。
    - **改法**：新增 `ProviderSetting.mergeCustomHeaders(requestHeaders)`（提供商级优先级最低、被同名请求级覆盖、大小写不敏感、空名丢弃）+ 单一应用入口 `applyCustomHeaders(providerSetting, requestHeaders)`（用 `header()` **覆盖**而非 `addHeader()` 追加 ⇒ 每个名字只剩一个值，且自定义头能显式盖掉内置的 `Authorization`/`Content-Type`/`x-api-key`）；**19 个调用点统一改造**，带 params 的传 `params.customHeaders`、模型列表/余额这类无 params 的只传 provider；**旧的双参数入口直接删除**——留着就还有第二条追加语义的路径可走。
    - **验证分两层**：`RequestHeadersTest` 5 项钉住合并/覆盖语义；另新增 **`ChatCompletionsAPIHeaderTest`（MockWebServer）**——起本地服务端走**真实请求链路** `ChatCompletionsAPI.generateText`，断言 wire 上 `Authorization` 只剩自定义那一个值、同名 `X-Dup` 由请求级胜出、两边独有的头都在。**为什么值得为它加测试依赖**（ai 模块 `mockwebserver3-junit4`，仅 `testImplementation`）：本次改的正是 19 个调用点的**接线**，纯函数单测证明不了「接对了」；一开始试过真机验证（给某个提供商临时加头、看请求日志），但那条路子又慢又脆——设备上唯一带自定义头的提供商域名不通、请求根本到不了网络拦截器，且在用户配置里来回改本身有风险，于是换成 MockWebServer：**真实代码路径 + 确定性 + 零设备配置改动**。真机配置已确认恢复原样（该提供商只剩它原有的 `User-Agent`）。
  - **验证汇总**：app 1137 / ai 213 单测 0 失败；两个功能都在真机验证过（记忆复制走完整 UI 链路；请求头用 MockWebServer 覆盖真实链路，设备侧只做了配置还原）。

- 2026-10-02：**新建草稿 v1.0.19 (281)**——用户「新建一版草稿」，并在三项确认里选了：**新版本号**（不沿用 1.0.18）、**按 `publish-release.md` 用 `--draft`**、更新日志照拟稿发布。
  - **起止范围**：上一版资产 `v1.0.18 (280)` 建于 `fc1a6de2`，其后落地的**用户可见改动 6 项**即本版内容：①**正式版提供商类型名 R8 混淆修复**（`26025c8b`：release 下显示 it9/ft9/bt9 → 现在 OpenAI/Google/Claude）②请求日志 JSON 弹窗长按闪退（`7c3de7a9`）③图片生成自定义分辨率被静默丢弃（`bae735da`）④Gemini 4 + Claude 5/5.5 注册（`5a25a42a`）⑤注册表漏吸收补漏 + Muse 图标（`a2a0f609`：DeepSeek V4 Flash 视觉版 / Muse Spark·Glimmer / Step 5 / MiMo V2.6）⑥复制助手带记忆（`27b35815`）+ 请求头同名去重（`a3394e7a`）。纯文档提交不计入更新日志。
  - **流程**：清 `app/release/` 与 `app/build/outputs/apk/release/` 旧产物 → bump `f9c3244e`（280→281、1.0.18→1.0.19）→ **push**（避免转正时 tag 落在未推送历史）→ release 构建（**5m20s**，391 tasks）→ aapt2 核实 `versionCode=281` / `1.0.19` / **仅 `arm64-v8a`**（**77,566,347 字节**）→ **release 装机冒烟零 FATAL**，且顺带在**发布产物体内**验证了头号修复：提供商页的类型选择器显示 `Google / Claude / OpenAI`（不是 it9/ft9/bt9）→ 建 draft（`--title "v1.0.19 (281)"` + 双语说明）→ 上传资产 → **字节级校验：服务端 digest `sha256:27cfe223…` == 本地 sha256**（走 `gh api … /releases/<id>` 的 digest 字段，未下载）→ README 标记更新为「正式发布 `v1.0.17` ｜ 草稿 `v1.0.19`」。
  - **两个这次踩到、值得记住的 gh 细节**：①`gh release create --target` **只接受完整 40 位 SHA**（短 SHA 报 `target_commitish is invalid`）——而 target 必须钉住，因为 **draft 状态下 git tag 还没创建**（web URL 是 `untagged-…`），tag 会在转正发布时按这个 target 创建；②**`gh api` 上传资产会 404**——资产上传走的是 `uploads.github.com`，而 `gh api` 打的是 `api.github.com`；用 `gh release upload <tag> <file>`（它能按 tag 找到 draft）或直接打 `upload_url` 才行。
  - **待用户转正**：draft 已就绪（release id `401444983`，target `f9c3244e`）。另注：仓库里还留着更早的 `v1.0.7 (264)` draft（一直没发布），要不要清理由用户决定。

- 2026-10-02：**两个草稿一起转正（v1.0.19 (281) + 历史遗留的 v1.0.7 (264)）**——用户「二者都正式发布」。
  - **`v1.0.19 (281)`**：`--draft` 建好后直接发布，tag `v1.0.19` 落在 bump 提交 `f9c3244e`（正确），并显式 `--latest` 保证 GitHub 的 Latest 徽标指向它；资产 digest `sha256:27cfe223…` 与本地一致（上一轮已验）。
  - **`v1.0.7 (264)`（2026-09-17 建的 draft，一直没发布）**：它暴露了 **draft 的 target 写成分支名的坑**，三步才修好，值得完整记住：
    1. **发布前先改 target**：它的 `target_commitish` 是分支名 `imperfect-customization-and-refinement`，draft 状态下不建 tag，发布时才解析分支 ⇒ **tag 会落在当时的**分支头**（等于把 1.0.7 标到最新代码上）。核对时间线后定位到真正的构建提交：bump `7bb951f2`（16:25:42 CST）之后、下一条 `d690176f`（16:38:10）之前，而 release 创建时间正是 `08:33:58Z = 16:33:58 CST`，落在这两者之间 ⇒ APK 就是 `7bb951f2` 的构建。先 `PATCH target_commitish=<sha>` 再发布。
    2. **发布后 tag 仍然没建**：`git ls-remote` 里没有 `refs/tags/v1.0.7`，而 release 的 `tag_name` 被记成了合成的 **`untagged-31c160…`**（所以 `gh release view v1.0.7` / `releases/tags/v1.0.7` 都 404）。补法=自己建并推送真 tag（`git tag v1.0.7 7bb951f2 && git push origin v1.0.7`；沿用既有轻量 tag 风格）。
    3. **把 release 指回真 tag**：`PATCH releases/<id> -f tag_name=v1.0.7`，之后 `releases/tags/v1.0.7` 能解析、`gh release list` 正常显示。
  - **收尾核实**：`v1.0.19 (281)` = Latest ✓、tag → `f9c3244e` ✓、资产 digest 一致 ✓；`v1.0.7 (264)` = 已发布 ✓、tag → `7bb951f2` ✓、资产 76,654,451 字节（digest `sha256:4a5011ed…`）、原始双语更新日志完好（1417 字符）✓。README 标记更新为「正式发布：`v1.0.19`」（本轮没有新草稿，故去掉草稿段）。
  - **教训**：以后建 draft 一律 `--target <完整 40 位 SHA>`——**分支名 target 在 draft 语义下是定时炸弹**（发布时才解析，且会让 tag 名变成 `untagged-…`）。

- 2026-10-02：**「上下文容量面板」已落地（`8c7f6240`）**——评估来源与口径见本条后半（用户给出他人实现截图要求评估难度 → 决策清零 → 当日落地）。——用户给出**他人实现**的一套面板截图（本仓与上游都没有），要求评估推进难度。面板内容：状态词 + 大百分比 + 进度条、三条指标（剩余/占用/上限）、「预计还可容纳约 N 轮（平均每轮 X Tokens）」、组成明细（有效历史消息 tokens + 条数/共条数 + %、系统设定与记忆）、压缩上下文入口；输入栏另有一个圆形占用指示器。
  - **结论：难度低（约 1–1.5 天），因为地基全在**——①总占用/上限已有（`estimateWindowTokens`：usage 优先 + 检查点判新旧 + 真 tokenizer 兜底）②「有效历史 N 条 / 共 M 条」已有现成语义（`PromptInspection.sentHistoryCount` / `totalMessageCount`）③组成明细所需的分段 token 已有（`PromptInspector` 十类分段，且与实发共用 `PromptAssembly`）④逐段 UI 已有整页「拼装预览」⑤压缩入口与配置档齐备。**不需要动数据模型、压缩算法与发送链路。**
  - **唯一要动脑的地方=口径自洽**：总占用是 usage 优先、拆分只能本地估算，两者并排会被当场核对 ⇒ 推荐**差值法**（历史用本地估算，系统与记忆 = 总占用 − 历史），保证「两项相加 = 总数」。
  - **要拍板的三件事**：①明细口径（是否单列 `TOOL_SCHEMA`/`TOOL_RESULT`，它们在 agent 会话里占比可很大）②「轮数」定义 + 样本不足退化（<3 轮不显示；文案带「约」）③三处入口分工（输入栏 tooltip / 整页拼装预览 / 新面板）。
  - **一处不照抄 + 一处已澄清**：①**不照抄其口径**——对方把 system 算成了 0 Tokens（截图里「系统设定与记忆 0.0%」），system 正文不可能为 0，说明其分段漏了 system；②圆环那个**黑色脏块是截图伪影**（用户 2026-10-02 确认），**不是对方实现的问题** ⇒ 圆环作为可选视觉项没有额外风险，剩下要定的只是「替换现有细条还是并存」。
  - **补充（用户指出）：标题旁的占用读数**——截图里标题下那行是「默认助手 / mimo-… ⋯ **● 21.6K/1M（余 978.4K）**」，即在**助手/模型那行右端**再挂一枚占用药丸。本仓现状：`ChatPage` 顶部栏的 title 是个 `Column`（标题 + `"助手名 / 模型名 (提供商)"`，`labelSmall` 8.sp），**没有**占用读数；而数据与格式化函数都现成（`ContextUsage`、`formatContextLength`）⇒ 纯 UI + 复用，**难度低**。**用户 2026-10-02 已拍板**：①与「助手/模型」**同一行**；②读数形如 `21.6K/1M（余 978.4K）`——**显示「余 X」但不带百分比**；③**阈值与输入栏共用**（沿用 `CONTEXT_USAGE_WARNING_RATIO` = 80%，两处同步切换）；④**颜色落在状态点**，且常态改用**浅蓝色点**（达共用阈值时再切警戒色）；⑤`ContextUsage` **提升为 ChatPage 一份**（不再在 `bottomBar` lambda 里就地算）——三处同源，杜绝「同屏两个数不一致」。剩下纯实现细节：窄屏截断优先级（模型名可省略、读数不可）。
  - **据此更新「入口分工」（现在是四处，别让它们讲同一件事）**：建议按截图那版的分工——**标题旁 = 精确读数**（数字）、**输入栏 = 一眼状态**（细条或圆环 + 百分比，不加数字）、**点开 = 密集面板**（三条指标 + 明细 + 轮数 + 压缩入口）、**整页拼装预览 = 逐段归因**（现有，留给排查与跳转）。
  - **组成明细口径：已定用差值法**（2026-10-02）——`系统与记忆 = 总占用 − 本地估算的有效历史`，保证「各项相加 = 总占用」。**但差值法一旦要单列更多行，减数集合必须显式枚举在一处**（`系统与记忆 = 总 − 历史 − 工具schema − …`），否则以后加项必漏改、必加不平；面板底部建议放一条「合计」对照行兜住。
  - **两个待拍板项：已采纳建议（2026-10-02，本项决策清零）**：
    - **A 轮数定义 + 样本不足退化**：分母用 assistant 条数最省事，但工具循环一轮会产生多条消息 ⇒ 轮数偏多、「还能 N 轮」偏乐观（agent 会话可差数倍）。**已定：分母用问答对**（user→最终回复；工具循环不另算一轮）。分子若含**压缩检查点**（一次性固定开销）或 **工具 schema**（顶层字段、每轮都发），会把「平均每轮」抬高、「还能 N 轮」压低。用**平均值**时粘一篇长文（一条 30K token）就能让数字失真 ⇒ **已定：用中位数**（分段 token 现成，按消息聚合即可）。**已定：<3 轮不显示**（或写「样本不足」）。这项决定的是**「那个数字会不会被当成承诺」**，所以必须带「约」并随发送/压缩重算。
    - **B 是否单列 `TOOL_SCHEMA` / `TOOL_RESULT`**：不单列则面板**失去诊断价值**——本仓自己的记录里 MCP schema 实测 ≈33K token、单条工具输出上限 32KB（几十次调用可超正文），全塞进「有效历史」后用户看到「历史 100K」不知大在哪。单列的代价是与差值法耦合（见上）。**已知可用的两个事实**：`sentHistoryCount` 只数 `CHAT_HISTORY` + 检查点 ⇒ 工具输出**本来就不进条数**，单列它不会双重统计；`TOOL_SCHEMA` 的 location 是 `TOP_LEVEL_FIELD` ⇒ 天然不属于历史。附带收益=用户能判断「压缩能腾出多少」（工具 schema 不随压缩消失，工具输出会随历史被压掉）。**已定：单列但弱化**（次要行默认折叠、占比超阈值才展开），工具 schema 行标注「不在对话中」。
  - **建议两步**：先「sheet 化 + 三条指标 + 状态分级 + 标题旁读数」，再「组成明细（差值法）+ 轮数预测」；圆环作为可选视觉项。
  - **落地（`8c7f6240`，两步一次做完）**：①**标题旁读数**与「助手/模型」同行（`7.0K/256K（余 249.0K）`，浅蓝状态点、共用 80% 阈值切红，左侧可省略、读数不可省略）；②**输入栏指示器**保留细条+百分比、点击打开面板（原 DropdownMenu 一行 tooltip 撤掉）；③**面板**=状态分级 + 大百分比 + 进度条 + 三条指标 + 轮数预测 + 组成明细（有效历史带「条数/共条数」、系统设定与记忆、**工具与检查点折叠行**（超阈值自动展开）、合计对照行）+ 压缩上下文（复用既有压缩参数对话框，该对话框从 `FilesPicker` 提到页面级，因为入口变成两个）；④`ContextUsage` **提升为 ChatPage 一份**（三处同源）；⑤纯逻辑独立成 `utils/ContextCapacity.kt`（7 项单测钉口径：问答对切轮、分子只含历史正文、中位数、<3 轮不预测、差值法相加=总数）；⑥分段标签构造抽成 `buildPromptInspectionLabels`（拼装预览页与面板共用，避免 20 行 map 漂移）。**圆环已做**（2026-10-02 追加，`3b20331a`）：`DisplaySetting.useRingContextIndicator`（默认 false ⇒ 旧数据零迁移）+ **偏好设置 → 通用**的开关（六语言）；两种形态只差展示（数值来源、阈值、点击开面板全共用）；圆环 = 24dp 环 + 整数百分比居中（精确数值在标题旁读数与面板里），弧线自 12 点起画、圆头笔帽、达阈值同色转红。
  - **落地时发现并修掉一个原计划没覆盖的自相矛盾**：空会话下标题/输入栏显示 `0/256K`，而面板里「工具与检查点 4.3K」、合计却是 `0 Tokens`。根因=**本仓的本地占用估算只数消息**，不含「每轮都发」的固定开销（系统提示、注入、**工具 schema**），而分段里有它们；首次回复前又没有 usage 锚点 ⇒ 明细会超过合计。处理=面板总占用取**「服务端锚点」与「本地已知总量」的较大者**，并在启用兜底时显式标一行「已计入服务端尚未统计的固定开销（系统提示、工具 schema），首次回复后会自动校准」（`usesLocalFloor` + `context_capacity_local_floor_hint`，6 语言）。这样面板内部**恒自洽**。
    - **顺带暴露的既有口径差（本次不动，留档）**：`estimateWindowTokens` 在首次回复前会系统性低估——它只数消息，不含系统提示/注入/工具 schema；有 usage 后由服务端计数校准（正常态准确）。要不要把固定开销并进该估算（影响自动压缩触发时机）是另一个决定，需要单独评估。
  - **真机验证（debug）**：①空会话 → 2.1%、当前占用 5.3K（954 系统 + 4.3K 工具 schema）、合计 5.3K、无预测、提示行出现；②新会话真实三轮后切到 usage 锚点 → `7.0K/256K = 2.7%`，明细 `326 + 1.7K + 4.9K = 合计 7.0K`，预测「约 **2441** 轮（每轮中位数 102 Tokens）」= 249.0K ÷ 102 ✓；③面板内「压缩上下文」正常打开既有压缩参数对话框。app 1145（+8）/ ai 213 单测 0 失败；测试会话已删除。
  - **追加：圆环与同行图标的对齐修复（用户反馈）**——输入栏那一行是 **Top 对齐** 的（`horizontalScroll` + `Alignment.Top`，见既有注释），各 item 的**中心由各自高度决定**：相邻 `IconButton` 默认 48dp（中心 24dp），而指示器容器只有 40dp（中心 20dp）⇒ 实测圆环中心 `y=2048`、灯泡图标 `y=2060`，**偏高 12px**。修法=容器高度对齐成 **48dp**（不改行对齐方式，避免波及其它 item）。修后实测：圆环 2060 / 灯泡 2060 / 徽标 2061 / 加号 2062 —— 对齐。**细条形态同样受益**（它此前也偏 4dp，只是细条不显眼）。
    - 教训：在这种「Top 对齐 + 子项高度不一」的行里，容器高度就是垂直位置，必须与邻居对齐；**别只靠「我内部居中了」**。
  - **本次新增的两个 aapt2 坑（都踩了）**：①英文文案里的 `hasn't` 这类未转义单引号会报 `Invalid unicode escape sequence`（与既有记录同源，必须写 `'`）；②同一原因在 `composer's` 上又踩了一次 ⇒ 新增英文串里出现单引号时**统一先转义**。

- 2026-10-02：**senera 分层上下文 / 上下文策略评估（对上游 #972 与我们的借鉴）**——明细入档 `peer-apps-benchmark.md` **§7.4**。**上游 #972**（`无限上下文和分层记忆重构`，re-ovo 2026-03-16 自记备忘、label enhancement、**至今零实现**）的三问（无限上下文 / 分层记忆 / 懒加载消息）与 senera 这套机制同题，而 senera 是见过**唯一三者都落地**的实现。
  - **机制**：`Prompt/` **11 层**（kernel/persona/profile/lore/facts/graph/world/scene/memory/workflow/task）× **3 档**（stable/context/volatile，各带 revision）+ **稳定档逐字节一致**；turn 档是**隐藏、仅追加的 `turn_context` 消息**（紧跟所属用户消息）⇒ provider 前缀保持字节稳定；wire renderer 声明编码（TOON/JSON）并做**无损回环校验**（失败即报错）+ 逐块 tokenizer 计量；只缓存稳定档。策略侧：`TemporalMemory` 的 `segment→day→month` 摘要树（状态机 + summary/topics/openLoops/uri）、`Continuity` 的 episode 邻域按需展开（模型选范围、宿主保序与完整性）、artifact 投影 + 证据句柄。
  - **借鉴结论（按性价比）**：**P1**=易变状态搬出 system 块 + 隐藏追加式轮次上下文（**不依赖 #1971**——隐式缓存只看前缀逐字节一致；**验证前置=先量「记忆变化前/后」的 cached 比例**，消息尾部已显示 `X tokens (Y cached, Z%)`；风险=部分 system 内容改角色可能影响遵循度，需 A/B）；**P2**=分段加 stable/turn/event 分组 + 「投影无损回环校验」纪律；**P3（只记账）**=时间维度摘要树（与我们的显式可编辑记忆哲学不同，等时间线需求）；证据句柄/artifact 投影维持现状。

  - **✅ P1 收益实测（2026-10-02 真机，同一会话 `glm-4.5-air`/智谱）**：稳态 **99–100%**（6.4K/6.4K → 6.5K/6.4K → 6.6K/6.6K）；**写入一条记忆后 72%**（6.9K 中 5.0K 命中）；下一轮恢复 **99%**（7.0K/6.9K）。**失效范围＝记忆块之后的一切**（由缓存量 5.0K ≈ 工具 schema 4.3K + 人设 0.6K 反推序列 `工具 → 人设 → 记忆 → 引导 → 历史`）⇒ 记忆一变，**其后整段历史重算**；本次历史仅 ~1.9K 故只掉 28%。**放大规律（推断）**：失效量 ≈ 历史长度 ⇒ 5 万 token 会话里一次记忆写入≈5 万 token 重算，而记忆是模型主动写的（成本按 会话长度 × 写入次数）。**做法精确化**＝把记忆注入从 system 块挪到历史之后（尾部合成消息，照压缩检查点先例）；**未做**长会话实测。附带发现：工具 schema（最大固定块）在可变部分之前长期保住，且人设+工具跨会话命中（首轮即 100%）。

- 2026-10-04 晚：**v1.0.21 (284) 转正为 Latest**——①notes/标题更新为 284 口径（双语 10 条，含
  思维链回传 400 两半/strict/空回复补救/冲突检测等）；②`--draft=false --latest` 发布；③**发布后
  `ls-remote` 核对发现 tag 又错位**：指向 `d54c8d5b`（tag 对象，解引用=旧 target `1011f09f`）而非
  bump `51119dc0`——**新规律：draft 上 PATCH target_commitish 只改 release 元数据，发布建 tag 时
  GitHub 仍可能按早期值建**（PATCH tag_name 复原 tag 之后的 Publish 场景）。修正=`git tag -f` +
  强推（对已发布 release/资产无影响，实测确认）。**结论：发布后 `ls-remote` 核对 tag 指向是必做
  收尾动作，PATCH target 不等于 tag 会落在那里**。最终 tag=`51119dc0` 与 284 资产三方对齐 ✓。
  README 改「正式发布：v1.0.21」（无草稿）。
- 2026-10-04 深夜：**v1.0.22 (285) 草稿**——bump `63fd79dd`（先 push）→ 清 outputs → release 构建 →
  aapt 核实 `versionCode=285`/`1.0.22`/仅 arm64 → **release 装机冒烟零 FATAL**（tokenizer `ok=true
  vocab=100256 in 244ms`，versionCode=285）→ draft `--target 63fd79dd…`（完整 40 位 SHA）→ 上传
  **77,634,503 字节** → 服务端 digest `sha256:4a843a7e…` 与本地一致 → **GET 复核 tag_name = `v1.0.22`
  （未被重置为 untagged-）**、target = bump ✓。**本版内容（自 284 资产 28 提交，用户可见 10 项）**：
  ①裸断流识别 + 自动续写/manual 继续（B/D）②流式检查点（C，崩溃不丢内容）③备份加固（图片进备份 /
  目录清单护栏 / 库完整性校验）④备份打包前缀修复（图片回到 images/）⑤imggen：OpenRouter 编辑不再 404 +
  模型名含斜杠不再丢图 ⑥自制 rootfs tarball 安装不再失败 ⑦workspace 提示告知 shell/发行版 + 无 bash 回退
  ⑧#1991 两现象（编辑分支保留模型信息 / 删 Provider 后历史消息仍有模型名与图标）⑨#1976 相册裁剪兜底
  ⑩images 递归打包。README 改「正式发布 v1.0.21 ｜ 草稿 v1.0.22」。
- 2026-10-04 深夜：**会话列表「自定义顺序」设计定稿（上游 #1995 的延伸，先不实现）**——产品决策五项由用户
  拍板并确认：①新会话**顶部最前** ②**置顶=第一档、非置顶=第二档，档内任意**（两套并存不合并）
  ③行内显示**最近活跃时间 = 末尾消息 `createdAt`**（不用 update_at）④拖拽走**「编辑顺序」模式**
  （进入后长按即拖、退出即锁定；长按已被会话菜单占用）⑤位置编码**整数大间距 1000 + 取中值 + 耗尽整段归一化**，
  起始值 `1_000_000` 留双向余量 ⑥**置顶保留原 sortOrder**（只换档显示）**且须在 UI 提醒**（模式内说明 +
  置顶/取消置顶 toast）⑦**不再提供时间档**，保留「恢复默认顺序」 ⑧AI「最近对话」工具**保持按时间**。
  设计稿 `docs/design-conversation-manual-order.md`（含 5 项实现约束与 11 处 ORDER BY 的收敛要求、
  已验证「Room 允许 ORDER BY 绑定参数且兼容 PagingSource」这一成本前提）。估时 1.5–2 天，排期另定。
- 2026-10-04 深夜：**上游 #1991 两现象 + #1976 兜底落地**——①现象 B（编辑 AI 消息 → 新分支元信息全空）：
  `editMessage` 新建分支继承 `modelId`/`modelNameSnapshot`/`providerNameSnapshot`/`createdAt`，
  刻意不继承 `usage`/`finishReason`/`finishIncomplete`（描述被替换的那次生成，继承会把手改误报成模型产出）；
  ②现象 A（删 Provider 后历史消息模型名/图标消失）：`ChatMessageAvatar` 以「快照也算身份」渲染，
  图标 seed 与名称活模型优先、快照兜底——**缺口只在头部**（尾部 NerdLine 早已用快照，是 #1805 那次
  「头部纯模型名 / 尾部带路由归因」分工留下的缝）；③#1976：相册裁剪前 temp 拷贝失败的回退不再把
  `content://` 交给 UCrop（Android 11 media-store URI 无读授权 → 库内 SecurityException 崩溃，
  catch 不到），改为不裁剪 + 提示。单测 +3（继承规则三例）。**真机验证待用户**：
  编辑 AI 消息后切到新分支看头部；开启「跳过裁剪」关掉后选相册图。
- 2026-10-04 深夜：**备份打包事故与修复（我引入，用户真机抓到）**——`bd8a2568`（images 改递归）把判据改成
  `in RECURSIVE_FILE_FOLDERS` 时，`addDirectoryToZip` 的 `entryPrefix` 漏改、仍是硬编码 `skills/`
  ⇒ 新递归目录 images 被打成 `skills/...`：**图片既不在 images/ 下（记录找不到），恢复还会解进
  files/skills/**。用户 21:00 那份 WebDAV 备份实测：`images/` 条目 0、`skills/` 下多出 4 个 .png；
  对照 20:03（修复前 release，平铺）与 21:10（修复后）两份均为 `images/` 4 条、skills 无 png。
  **修复**（`32083641`）：前缀一律由目录名派生，并抽 `appFilesPackingPlan()` 可测；
  **加固**（`f6b38d35`）：把打包实现抽成只依赖 `filesDir` 的顶层 `packAppFiles`，加**真实 zip
  端到端测试**（断言每个目录落在自己的前缀下、遗留子目录 `images/<ts>_<vendor>/` 保留层级、
  非技能文件不得出现在 `skills/` 下）——事故之所以只能靠真机发现，正是因为这层当时测不到。
  **教训**：改「目录 → 前缀」这类映射时，映射两侧必须同源（由数据派生），且要在能直测产物的层级写测试。- 2026-10-04：**压缩/半截/续写批次（B/C/D 三项，§四.7 评估的直接落地）**——
  ①**B 显式结束 vs 裸断流**（`7973da13`）：四个 provider 在显式结束处发 `terminalChunk()`，
  `onClosed` 不发 ⇒ 「流结束了但没见到标记」= 裸断流；消息新增 `finishReason`/`finishIncomplete`
  持久化字段（此前整轮被当成功，中转断流静默吞掉）。
  ②**C 流式检查点落库**（`20091990`）：每 2 秒 upsert 正在生成的那一个节点 ⇒ 崩溃/被杀不再丢
  断点前内容（此前流式内容纯内存）；不重建 FTS、不重算全会话签名（长会话下后者才是主要开销）。
  **索引陷阱已记测试**：THIS_ONLY 重生成只改中间节点，索引必须取 `chunk.messages.lastIndex`。
  ③**D 续写**：裸断流自动续写（非模型本意）、撞输出上限默认只提示 + 手动继续（可开自动）、
  上限 3 次；手动「继续生成」按结束状态选提示词，追加到同一条消息；横幅三档 + 设置开关 + 6 语言。
  ④**崩溃恢复的收尾（C 的真机验证暴露）**：`Conversation.markGeneratingNodeIncomplete` 在**写库副本**上
  标记未完成（只标当前选中候选）+ 用 `finishReasoning()` 收尾推理块，两个只有「强杀后重开」才暴露的
  问题一并解决——半截内容此前看起来像正常完成的回复（既无提示也没法继续）、未结束的推理块让思考卡片
  在死消息上永远走计时器；用户停止路径（`persistStoppedGeneration`）同样补上推理块收尾（既有缺口）。
  **真机验证**：C 已由用户确认（强杀后重开看到断点前内容）；④的标记 + 推理块收尾亦已确认
  （半截内容重开后带「流式连接中断」提示且可继续写）。**本批待验**：D 的裸断流自动续写、
  撞上限横幅/手动继续、设置开关、内容拦截只提示——共 4 点。
- 2026-10-04：**备份加固批次（Yuihub `34f1a976`/`3eddec42` 对照吸收，§四.6）**：
  ①**生成的图片进备份**——`filesDir/images`（imggen 产物）此前不在 FILES 内，恢复后图库全裂；
  新增 `FileFolders.IMAGES`，打包/恢复/空间预检三处同步（旧备份无此条目自然跳过）。
  ②**目录清单常量化 + 覆盖护栏**——`BACKED_UP_FILE_FOLDERS` 统一驱动打包/恢复/预检三处
  （此前各自手列，新增目录漏一处即静默不备份）；`BackupCoverageTest` 3 项断言清单与 FileFolders
  全集一一对应 + 豁免目录（builtin_skills/tool_outputs/logs/exports）带理由且不重叠。
  ③**staged 主库 integrity_check**——换库前打开暂存库（自动回放 WAL）跑 `PRAGMA integrity_check`，
  不过则中止（此时现库未关、安全回退），防旧格式/外来改包备份写坏。
  **2026-10-04 真机验证通过（用户确认，日志取证）**：备份→恢复全链 `images/` 4 张逐条 Restored
  （含中文/特殊字符文件名）、`staged db integrity check ok`、`completed` 无异常。
- 2026-10-05 深夜：**v1.0.24 (287) 草稿**——bump `10a6a0e0`（先 push）→ 清 outputs → release 构建 →
  aapt 核实 `versionCode=287`/`1.0.24`/arm64 → release 装机冒烟零 FATAL（tokenizer
  `ok=true vocab=100256 in 665ms`；旧 FATAL 为前次会话残留、按时间过滤排除）→ draft
  （release id `403274771`，target 钉 bump，**tag_name 未被重置**）→ 上传 **77,661,547 字节** →
  digest `sha256:68e42c63…` 与本地一致 → README「正式 v1.0.23 ｜ 草稿 v1.0.24」。
  **本版内容（自 286 资产 3 项）**：①前缀分叉诊断 ②华灯 P2 四小件（stability 分组/无损回环
  校验/记忆相关性下限/日期排查）③拼装预览折叠状态持久化。
- 2026-10-05 深夜：**折叠状态持久化落地（方案 A+B）**——①`PromptDebugState` facade
  （`PromptDebugCache.kt` 更名）：两个独立 store——请求历史 LRU 8（`recordRequest/latestRequest`）
  + 段折叠状态小表（`foldState/setFolded`，LRU 8 会话），淘汰各自独立；②`SegmentCard` 加
  `debugKey`（conversationId）参数，折叠改 `rememberSaveable(index){ store 初值 }` + 切换时写回
  store——**跨导航存活（store）+ 进程死亡恢复（saveable）双源**。顺带踩了一次 58fb7519 同款：
  脚本追加测试落在类外（InvalidTestClassError）——rstrip 把类尾当文件尾，测试文件已全量重写。
  单测 6 项（诊断 4 + 折叠往返/隔离 1 + 记录 1）。
- 2026-10-05 深夜：**拼装预览折叠状态持久化——方案定稿（A+B）**：折叠状态此前是
  `remember(index)` 纯组合内记忆，导航返回即重置。方案=①**会话级内存存档**
  `PromptDebugState`（facade：内部**两个独立 store**——请求历史 LRU 8 + 折叠状态小表，
  各自独立淘汰）；②`rememberSaveable` 叠加（进程死亡恢复；初值来自 store，两源同源不打架）。
  **为什么不合并成一个 LRU（评估结论）**：fold 读发生在组合/滚动期（高频），会刷新 recency
  把大块请求列表「钉」在内存——两种读写节奏塞一个 LRU 会污染淘汰语义。**货架用文件级组织
  实现，淘汰各自独立**。折叠键=段下标（调试视图可接受，消息增删后会错位属已知边界）。
- 2026-10-05 深夜：**华灯 P2 四小件落地（§八 评估的直接吸收）**——
  ①**分段 stability 分组**（stable/turn/event）：`PromptSegmentStability` + `kind.stability` 派生，
  拼装预览 SummaryCard 新增「≈X 稳定 · Y 事件 · Z 每轮变化 tokens」一行——一眼看出「哪些内容
  每轮都在变、因而不利缓存」（MEMORY/渲染提示归 stable；检查点/条件注入归 event；历史/工具输出
  归 turn）；②**投影无损回环校验**：工具输出截断的外部全文写后**读回比对**（磁盘满/静默截断当场
  报错，绝不让「预览说存了、文件却是残的」）；拼装预览分段加**逐消息非空 require**（空段=预览
  静默丢消息，失败即报错——实测过 buildSystemBlocks 漏 BEFORE/AFTER 的回归形态）；③**工具描述
  动态日期排查**：全仓 grep 核实工具描述零动态日期（{{cur_date}} 种子已除、时间由 get_time_info
  动态取），且新落的前缀分叉诊断让未来的日期搅动可见；④**记忆注入相关性下限**——
  `MIN_MEMORY_INJECTION_SCORE=0.2`（原始分数非分桶：分桶四舍五入会让 0.11 分进位绕过下限），
  默认 importance 0.6 全老化也有 0.33 不触线，刻意标低且老化的记忆自然沉底。单测 +5。
- 2026-10-06：**peer⑤③ 缩水版落地（MCP schema 动态分层加载）**——两档：
  **原生档**（schema ≤600 tokens 照旧全量进 schema，小服务器零行为变化）+ **折叠档**
  （重型工具不进 schema，由 `get_tool_map`〔名称/服务器/描述截断 200 字符/params_hint
  12 个「名:型」对/needs_approval〕+ `mcp_tool_call(name, arguments JSON 串)` 桥替代——
  桥执行走**原有** mcpManager.callTool 链，寻址=净化名→原名，审批**跟随底层工具**
  〔折叠不得变成审批绕行〕）。`McpDynamicSchema.kt` 纯函数 + 单测 6 项；设置开关
  `mcpDynamicSchemaLoading`（默认开，MCP 设置页顶部 CardGroup，三件套齐）；LoopGuard
  豁免 `get_tool_map`（只读地图查询）。**provider schema 恒定 ⇒ Claude 缓存断点不动**。
  顺带修存量红：ConversationSchemaGuardsTest 的 daoSource() 未做 CRLF 归一化
  （substringBefore("\n\n") 在 CRLF 文件上吃穿到文件尾）——HEAD 存量、非本批引入。
  **踩坑记录**：python 拼 Kotlin 字符串的 \n 展开坑第三次命中（本批用 Edit 工具修复）。
  真机验证=大 MCP 服务器场景看 schema 体积日志（logSchemaBreakdown）+ 模型走桥调用成功。
  **收尾加固（同批）**：复查发现折叠会**静默破坏子代理信封**——信封按名求交集，折叠把工具从
  segments 摘掉 ⇒ 「预设里点了名、run 里却没有」。修法=`createToolSegments` 新增
  `explicitlyAllowedToolNames`，点名者**提升回原生档**（纯函数 `promoteExplicitlyAllowed` +
  单测 2 项）：折叠只服务 token 预算，不得改信封语义。SubagentRunner 传 `allowedTools.orEmpty()`。
  **工具链教训（重要）**：本机 `python3` 是 **Microsoft Store 存根**（退出码 49、无输出、不执行），
  三个 heredoc 因此**静默未落盘**而未察觉（heredoc 后未用 `&&` 串联，后续命令照跑掩盖了失败）。
  **规则：脚本一律用 `python`；多条命令必须 `&&` 串联；改完必须 grep 验证落盘**。
  **2026-10-06 真机验证通过（用户确认）**。
- 2026-10-05 深夜：**「参考历史聊天记录」第三层落地（`read_conversation`，§三 同名评估）**——
  工具族漏斗补全：`recent_chats`（标题索引）→ `conversation_search`（片段，**补传 message_id 寻址键**）
  → `read_conversation`（按锚点取回 ±N 条完整消息，默认 6/上限 20）。纯函数
  `buildConversationReadPayload`：锚点缺失回退会话开头+note、单条 8K 截断 + 总量 24K 预算
  （防连续拉取撑爆上下文）、工具/图片/文档渲染占位。peer⑤③ schema 三档的**零纠缠练手场**
  （分层+寻址键+上限纪律先在此验证）。单测 4 项。**2026-10-06 真机验证通过（用户确认）**——
  三项真机确认：①`images/` 备份恢复 ②`read_conversation` 第三层 ③MCP schema 分层加载。
- 2026-10-05 深夜：**前缀分叉诊断落地（华灯 §八.1 借鉴）**——①`PromptDebugCache`：按会话缓存
  每次生成**实际发送**的完整消息列表（LRU 8 组，保留上一次+本次；工具循环每步都算一次请求），
  数据在 `generateInternal` 组装完成后记录（`debugKey`=conversationId，ChatService 传入）；
  ②`diagnosePrefixDivergence` 纯函数：定位两条请求的第一条分叉消息（role+parts 全量签名比对）+
  可命中前缀 token 估算；③**拼装预览页新增「与上次请求对比」卡片**（视图打开时 Default 线程计算）：
  「前缀一致 N 条 ≈ M tokens 可命中（上次 X 条/本次 Y 条）+ 分叉于第 K 条：预览」。与拼装预览互补
  （单次归因 vs 跨请求差分）。单测 5 项。**踩坑再现**：python 脚本拼 Kotlin 字符串又把 
 展开成
  真实换行 + 插入锚点吞掉既有 @Composable——多行/注解敏感的 UI 代码编辑已两次踩同一坑，
  一律改用 Edit 工具。
- 2026-10-05 晚：**v1.0.22 (285) 与 v1.0.23 (286) 一并转正**（用户「二者均正式发布」）——
  22 先发（`--draft=false` 不带 latest）、23 后发（`--draft=false --latest`）；**发布后 `ls-remote`
  核对双双精确落位**（22→`63fd79dd`、23→`b555e671`，各自 bump 提交，零错位——draft 创建时钉 target
  + 中途未动 tag 的场景下发布路径已完全可靠）。Latest=`v1.0.23`；仓库无草稿；README 改「正式发布：
  `v1.0.23`」。
- 2026-10-04 深夜：**发布草稿 v1.0.23 (286)**——用户「把 v1.0.23 的 bump + notes 草稿准备好（构建、装机冒烟、上传 draft 全流程）」。
  **流程**：bump `b555e671`（286 / 1.0.23，**先 push**）→ 清 `app/build/outputs/apk/release/*.apk` 与 `app/release/*.apk`
  → release 构建（**4m20s**，396 tasks，arm64 **77,654,423 字节**）→ aapt2 核实 `versionCode=286` / `1.0.23` /
  **仅 arm64-v8a** → release 装机冒烟（install Success、**零 FATAL**、`tokenizer install ok=true vocab=100256 in 665ms`、
  进程存活，且 release 包抽屉里能看到新的「排序」入口）→ 本地 sha256 `7253fb02…` → 建 draft（`--target`
  钉 bump 完整 SHA `b555e6713c5ea01df64416186e789b55caf7e537`，title `v1.0.23 (286)`，双语 notes）→ 上传资产
  `RikkaHub.Plus-1.0.23-arm64-v8a.apk` → **字节级校验：服务端 digest `sha256:7253fb02…` == 本地 sha256**（读 digest 字段，未下载）
  → 独立 GET 复核：`tag=v1.0.23`（**未落入 v1.0.7 / `df0d1069` 的 `untagged-…` 坑**）、`target` 正确、`draft=true`。
  release id **403074610**（draft）。README 改为「正式 `v1.0.21` ｜ 草稿 `v1.0.23`」。
  **本版内容（自 v1.0.22 资产 `63fd79dd` 起 = 7 个提交）**：会话列表自定义顺序全套（批 1 schema/排序单点、批 2 档内读写、
  批 3a 行内时间+隐藏日期头+置顶提醒、批 3b 编辑顺序模式）＋ 该功能真机抓出的三处修复（分页二次收集崩溃、
  锚点差一位/末尾落点、空拖写库）＋ 搜索与 AI「最近对话」保持时间序 ＋「恢复默认顺序」清两档。
  notes 范围：**用户决定「先转正 v1.0.22、再转正 v1.0.23」⇒ 23 的 notes 只写自定义顺序增量**
  （初版曾把 22 未发布的 10 条一并并入，已按此精简）；改 notes 后独立 GET 复核 `tag=v1.0.23` 仍在
  ——`gh` 打印的 `https://…/releases/tag/untagged-bac802…` 只是**草稿的显示 slug**，`tag_name` 字段没变；
  资产 size/digest 也未变。
  **两个新认知（本轮实证）**：①**草稿阶段 `git/ref/tags/v1.0.23` 还不存在**（404）——draft 的 tag 由 GitHub
  在**转正时**按 `target_commitish` 创建，所以"转正前先建 tag"不是必需动作；②`gh release create <tag>` 建草稿时
  `tag_name` 从创建那刻就是正确的（`untagged-…` 只出现在它回显的 URL 里），与 v1.0.7/`df0d1069` 那次的
  「notes 编辑把 `tag_name` 打回合成名」不是同一现象，但**仍然照旧流程走**：改完 notes 一律独立 GET 复核。
  **备注**：v1.0.22 (285) 草稿（id `403044967`，target `63fd79dd…`）**保留**，等用户先转正它。
  **本轮环境坑（记一次）**：`bash scripts/build.sh` 会把 `GRADLE_USER_HOME` 设成 `/d/...` 形式，而本机 Git Bash 对
  native 进程的环境变量路径转换不稳定——release 构建时 wrapper 一度去**联网下载已存在的** gradle 发行版（超时失败）。
  绕法：直接 `GRADLE_USER_HOME='<gradle-home>/gradle' ./gradlew …`（Windows 形式），构建即刻正常。
- 2026-10-04 深夜（续）：**自定义顺序收尾批次——搜索/AI 工具不跟随自定义顺序 + 复位清两档**——用户拍板
  「搜索结果保持时间顺序」，并在手动验证中发现复位清不干净置顶档：
  ①**新增第二条排序子句** `CONVERSATION_ORDER_BY_TIME`（`is_pinned DESC, update_at DESC, id ASC`，**不含
  `sort_order`**），只用在两类"按时间才有意义"的查询：AI「最近对话」工具（设计 §一.8）与**4 条搜索查询**
  （跟随自定义顺序会让同一关键词的结果顺序随拖拽漂移）；语义与改造前逐字一致，仅多 `id ASC` 兜底分页确定性。
  ②**「恢复默认顺序」改为清两档**（置顶 + 未置顶，同一事务两次 `clearSortOrder`）——编辑模式两档都能拖，
  只清未置顶档会留下清不干净的手工顺序，与对话框文案不符；`sort_order` 写入点仍只有那两条语句。
  **护栏**：`ConversationSchemaGuardsTest` 新增一项——按时间子句不得出现 `sort_order`，且使用者只能是这 5 条查询
  （`@Query` 与 `fun` 分处两行，用行号向后取函数名）。
  **验证**：app 单测 **1227** 全绿；真机复位由 6 行（置顶档）→ **0 行**（此前只清未置顶档、6 行会残留）；
  数据层对照（同一 `LIMIT 10` 窗口）TIME 子句第 7 位起与 CUSTOM 子句不同 ⇒ AI「最近对话」不再跟随拖拽。
  用户手动验证通过：**取消置顶回到原位置**、**跨档拖动被拒**。
- 2026-10-04 深夜：**会话「自定义顺序」批 3b 落地 + 真机抓到并修掉三个缺陷**——批 1（schema + 排序单点
  `CONVERSATION_ORDER_BY`）/ 批 2（档内位置值读写 + 档位变动决策表）/ 批 3a（行内最近活跃时间 + 自定义顺序下
  隐藏日期分组头 + 置顶提醒）之后，批 3b 补齐「编辑顺序」模式：`EditConversationOrderList.kt`（进入后长按即拖、
  头部 编辑顺序 / 恢复默认顺序 / 完成 + 「置顶不改档内位置」常驻说明、**列表镜像当前视图**即未归类或选中文件夹、
  档内拖动、松手才落库）、抽屉入口与复位确认框（`ChatDrawer.kt`）。**真机（小米 8 / A14，adb 驱动 + 拉库核对
  `sort_order`）抓到三个缺陷，全部修掉**：
  ①**首次拖动必崩**——`combine(customOrderActive)` 用**同一个** PagingData 重新包一层
  ⇒ `PageFetcherSnapshot.pageEventFlow` 二次收集（`IllegalStateException: Attempt to collect twice from
  pageEventFlow`；**与漏 `cachedIn` 无关**，它一直在末尾）；改为把标志并入 `flatMapLatest` 的 key
  （变化即新建 Pager = 新快照），并加 `PagingFlowGuardsTest` 钉住「包装 PagingData 只能出现在创建 Pager 的
  `flatMapLatest` 内」。**危险面**：标志 false→true 是每个自定义顺序用户第一次拖动的必经路径。
  ②**拖动总是差一位 / 拖到最末跳到最前**——`applyManualOrder` 把 `afterId` 当「插在它之后」，而它的语义是
  「拖完后应**排在移动项之后**」⇒ 必须插到它**前**面；`next == null`（末尾）此前归到 `Top`，现在归 `Bottom`。
  两段逻辑下沉为纯函数 `desiredTierOrder` / `placementOf` 并加单测（`ConversationOrderingTest` +6）。
  ③**空拖白写整档**——`onDragStopped` 无条件落库，长按未挪动也会把整档物化一次；改为比对拖动起点快照，
  只有顺序真的变了才写（`onMove` 原用 `it.id.toString() == from.key` 定位——`Uuid` **对象**永不等于字符串，
  换成 Lazy 列表下标）。加 `EditOrderMoveGuardsTest`（含 `computeReorder` 的档内/越界/原地判定）。
  **验证**：app 单测 **1226** 全绿；真机 rank1→rank0、rank31→rank32（值 `1033000` = 末尾追加）**精确落位**、
  复位回自动顺序并恢复日期头、**两档编号空间互不干扰**（用户手动验证：未置顶档复位成 0 行时置顶档仍保留自己的
  6 个值），全流程零 FATAL。
  **教训**：`PagingData` 的「单次可收集」是硬约束——任何非分页信号（排序标志、筛选开关）都只能作为
  **Pager 的 key** 参与，不能对已有 PagingData 再套一层；同理，`from.key` 是 **LazyList 的 key 对象**而不是字符串，
  比较前必须换成同类型（这里直接用下标）。两者都不是编译期能发现的错。
- 2026-10-04：**v1.0.21 草稿资产重建（283→284，版本名不变）**——bump `51119dc0`（**先 push**）→
  清 outputs → release 构建 → aapt 核实 `versionCode=284`/`1.0.21`/arm64 → release 装机冒烟零 FATAL
  （tokenizer `ok=true vocab=100256 in 1219ms`）→ 资产替换 + PATCH target=`51119dc0`（**v1.0.17 教训
  直接执行**：同 tag 原地重建必须同步钉 target）→ **df0d1069 的坑现场复现**：GET 发现 tag_name 已是
  `untagged-98fdca…`（notes 编辑重置），PATCH 回 `v1.0.21` 并独立 GET 复核。资产 **77,614,131 字节**、
  digest `sha256:e43044a4…` 与本地一致。**本版内容增量（自 283 资产）**：strict=false（#1990 chart_display）、
  空回复补救+写入冲突检测、Claude 思考链加固（redacted_thinking/无签名丢弃）、Response API 补插去 id、
  rootfsFileMtime 漏提交补交。
- 2026-10-03：**发布草稿 v1.0.21 (283)**——用户「发一版新草稿」。**起止范围**：上一版资产 `v1.0.20 (282)` 建于 bump `f26c5ad5`，其后落地的**用户可见改动 4 项**即本版内容：①**计划（todo_write）工具 + 输入栏上方计划细条**（`91c1d844`：AI 可列多步清单并逐步推进，细条可展开，计划不渲染进正文/导出）②**空回复补救 + workspace 写入冲突检测**（`446a40c8`：只思考不回复时注入一次性 `<recovery_note>` 重试；`write_file` 的 `expectedMtimeMs/force` 防并行双写静默覆盖）③**备份 VACUUM INTO 快照 + 二进制误写防护 + 工具参数三层降级**（`29a9cae0`）④**MCP schema 展开的极端场景对齐上游**（`651815b7`：循环引用截断保留 type + 任意 `#/` 指针）。
  - **发布前拦到一个真问题：HEAD 编译不过**——`446a40c8` 只提交了调用方（`WorkspaceRepository.rootfsFileMtime`、`WorkspaceTools` 两处），**漏提交实现**（`WorkspaceManager.rootfsFileMtime`）。它在工作区里以「未提交的 7 行」存在，看起来像别人的 WIP，实际是已提交功能缺的那一半（`git show HEAD:…WorkspaceManager.kt | grep -c rootfsFileMtime` = 0）。已由 `3a7c4075` 补上（先验 `:workspace:compileDebugKotlin :app:compileDebugKotlin` 通过再提交）。**教训**：发布前应把「HEAD 能否编译」当成前置检查项，而不是默认工作区能编就等于提交能编。
  - **流程**：清 `app/release/` 与 `app/build/outputs/apk/release/` 旧产物 → bump `0d120ab6`（282→283、1.0.20→1.0.21）→ **push** → release 构建（**5m19s**，396 tasks）→ aapt2 核实 `versionCode=283` / `1.0.21` / **仅 arm64-v8a**（**77,614,119 字节**）→ **release 装机冒烟零 FATAL**（进程存活）→ 建 draft（`--target` 钉 bump 完整 SHA `0d120ab6432e1b977a0916c9d7360e98452d6956`，title `v1.0.21 (283)`，双语 6 条说明）→ 上传资产 → **字节级校验：服务端 digest `sha256:ff4d604e…` == 本地 sha256**（走 `gh api … /releases/<id>` 的 digest，未下载）→ README 改为「正式 `v1.0.20` ｜ 草稿 `v1.0.21`」。release id `402279711`（draft）。**待用户转正**。

- 2026-10-03：**修复「思考模式下带工具调用的消息必须回传思维链」导致的整请求 400（`a39af462`）**——用户报错，**发布版请求日志实证**：`POST https://api.deepseek.com/responses` → `Status: 400`，错误体 `be2: The \`reasoning_text\` in the thinking mode must be passed back to the API`（同一端点上一次请求是 200）。
  - **性质**：这是**网关的结构性校验**（只看字段在不在，与内容无关），而且是**已知问题**——本仓 `docs/references/turbo-PROGRESS.md` 的 Bug #56 早对 opencode `zen/go/v1` 做过 curl 实测矩阵，**但 fork 从未吸收该兜底**。矩阵：chat/completions 缺 `reasoning_content` → 400「reasoning_content must be passed back」（**空串可接受**）；responses 缺 reasoning item → 400「reasoning_text must be passed back」（**空文本拒绝、非空即可、id 可选**）；**不带 tool_calls 的消息不校验**。**更新认知**：这次证明校验**不止 opencode 系网关**——DeepSeek 官方 Responses API 同样校验，故门槛不能只按 host 白名单写。
  - **触发场景**：那条带工具调用的历史消息**没有思维链可回传**——开启思考之前产生的旧消息、上游只返回加密推理（本仓按 #1719 不回放明文）、用户关掉「回传历史思考过程」、消息被编辑过。此前**直接省略字段** ⇒ 整个请求被拒，用户侧表现为「这个模型突然用不了」（与内容/额度无关，很难自查）。
  - **修法**：①新增共享门槛 `requiresToolReasoningBackfill(host, model)`＝**opencode 系 host ∪ 模型带 REASONING 能力**（前者已知校验方、后者是"思考模式"在本地唯一可判定近似；**非思考模型不受影响**，不会凭空多字段）②Chat Completions 在「有 tool_calls 且无思维链」时回填 `reasoning_content = ""` ③Responses 抽出 `addReasoningItem(...)`（主分支 / #1690 二次补插 / 本次回填三处共用）并在**第一个 function_call 之前**插入**非空占位** reasoning item（`…`、无 id）④**门槛两路共用**，避免行为漂移；#1719 的「加密推理不回放明文」语义不变。
  - **验证**：ai 220（+7）/ app 1166 单测全绿——chat 侧「回填空串」「非思考模型不多字段」「真实推理不被空串顶替」；responses 侧「占位非空且排在 function_call 前」「默认不插」「已有推理优先复用」；门槛三例。**真机复验已完成**（同日，含修复的包 10:43:06 装机）——**发布版请求日志**（设备可 root，直接读 `files/logs/rikkahub-logs.txt` 取证，未打印任何请求体、副本已删）显示：`10:33:52` 200 → **`10:33:55` 400（复现）**，装机后 `10:44:34 ~ 10:44:47` 同一会话/同一模型（`deepseek-flash` on DeepSeek Responses）**连续 10+ 次全部 200**（128–552ms），且该轮包含多轮工具调用（正好满足「历史里带工具调用」的触发前提）⇒ **400 → 200 端到端确认**。
  - **教训**：①「历史里缺某字段」这类**历史数据形态**的坑，只有在"旧数据 + 新校验"组合下才暴露，靠新会话测试永远测不到；②同一个错误文本在不同网关会以不同字段名出现（`reasoning_content` vs `reasoning_text`），**看到哪个字段名就知道走的是哪条请求路径**——这次的 `reasoning_text` 直接指向 Responses 路径，省掉一轮猜测。
  - **同日：把修复并进 v1.0.21 (283) 草稿资产（不 bump 版本）**——用户「替换资产（不需要 bump）」。做法：用含修复的 release 包（`versionCode=283`、**77,615,091 字节**、digest `sha256:e5941b50…`）`gh release upload --clobber` 覆盖草稿里那份旧资产（旧：77,614,119 / `ff4d604e…`）；**同时把草稿的 `target_commitish` 从 `0d120ab6`（bump 提交）改到 `6050de6d`（含修复的 HEAD）**——否则转正时 tag 会指向不含该修复的提交、与资产不符。更新日志补一条修复说明（中英各一）。**注意**：这意味着版本号 283 对应两份不同内容的历史（线上资产已被替换，旧包不再可得），后续若要再发必须 bump。

- 2026-10-03：**思维链回传契约的同类触发点审计（思维链 400 两半修复之后）**——发现并修复三个：
  ①**Claude `redacted_thinking` 被直接丢弃（真 bug，`8046d267`）**——`ClaudeProvider.kt:564` 解析时
  整块扔掉（残留 `println` 调试），历史只剩 tool_use ⇒ 下一轮 Anthropic 400「Expected thinking or
  redacted_thinking, but found tool_use」同类失败；现保留为 Reasoning part（`ClaudeReasoningMetadata
  .redactedData` 新字段，可空零迁移）并原样回传。②**无签名思考块不回传（`8046d267`）**——跨供应商
  历史/导入的推理没有 Claude 签名，回传即「Invalid signature」拒整请求；省略对旧轮次安全。③**Response
  API 同轮补插副本不再复用 `rs_` id（`c309c615`）**——同一请求重复 item id 可能被 OpenAI 官方按唯一性
  拒绝；补插一律 id=null（已知网关 id 可选），主 item 保留。**确认覆盖/低风险**：ChatCompletions 两处
  构造点、压缩按消息粒度不拆 reasoning+tool_calls、子代理同路径；Gemini thoughtSignature 缺失时通常
  自行重思考（低风险仅记录）；已知 host 集合外的中转站代理 DeepSeek Responses 仍是 by-design 盲区。
  顺带修掉遗留测试（无签名推理构造改带签名——行为变化是本次修复的目的）。
- 2026-10-03：**同一 400 的「第二半」修复（`13de6813`）——机制更正：「有 reasoning item，但缺 `content[].reasoning_text`」**。用户报「另一台设备上还是报错」。该设备的**发布版请求日志**（root 直读 `files/logs/rikkahub-logs.txt`，未打印请求体、副本已删）给出结构摘要：同一 host/模型（`api.deepseek.com/responses`、`deepseek-flash`、UA `RikkaHub-Android/1.0.21`），失败请求 `types=[正文, 'reasoning', 'function_call', 'function_call_output']`，**`reasoning#1: textLens=[] head='' encrypted=True`**。
  - **更正上文（`a39af462` / `6050de6d`）**：那一条记录写的是「历史里**没有** reasoning item」；实测这台设备是「**有** item，只是只有 `encrypted_content`、缺规范必填的 `content[].reasoning_text`」——正是 #1719「加密时不回放明文」的直接后果（我们为了不泄漏推理内容而把这个**必填**字段整个省掉了）。两半互补，缺一不可：①完全没有 item ⇒ 补占位 item（第一半）；②只有加密内容 ⇒ 补**非空**占位文本（第二半）。
  - **修法**：`ResponseAPI` 抽出的 `addReasoningItem` 增加 `reasoningTextRequired`——加密时写入**非空占位**（该 API **拒绝空文本**、非空即可、id 可选），仍不回放明文；Responses 路径门槛改用 `isKnownReasoningTextValidator(host)`（`opencode.ai` / `deepseek.com`，两者都有实测来源）。
  - **门槛语义本次定稿**：判定方是**网关**，`host` 是我们唯一能观测的网关特征。实测两例（turbo Bug #56 的 opencode zen/go、本次 DeepSeek Responses）都发生在输入里**完全没有** reasoning item 时仍 400 ⇒ 网关的「思考模式」判据既不在请求的 reasoning 字段里、也不等于本地模型表的 REASONING 能力（会话里换过模型时，历史仍带着思考轮次产生的 tool_calls）⇒ **已知校验的 host 不看模型能力**；REASONING 能力只在 host **未知**时兜底（chat 路径 `reasoning_content` 是通行扩展字段，故未知 host + 思考模型也回填；responses 路径不把未经实测的「占位 content + encrypted_content」组合推给 OpenAI 官方等未验证 host）。
  - **验证**：ai **223**（+3，含「加密时补非空占位且不回放明文」「未验证 host 不塞 content」「已知校验 host 不看模型能力」）/ app **1166** 单测 0 失败。**真机复验需用户在自己那台设备上做**——本机（`<device>`）没有该 provider 的凭据与那条会话，无法复现该 400。
  - **资产再替换（用户选「不 bump」，与上次同口径）**：用含**两半**修复的 release 包覆盖 v1.0.21 (283) 草稿资产（`versionCode=283` / `1.0.21` / 仅 arm64-v8a / **77,613,623** 字节 / digest `sha256:d0916d0c…`；上一份为 77,615,091 / `e5941b50…`），并再次把 `target_commitish` 移到含修复的 HEAD。**283 至此对应三份不同内容**（旧包均不可得）⇒ 下一次发布**必须** bump。
  - **本次新踩的发布坑（草稿的 tag 名会被 notes 编辑重置）**：把草稿 `tag_name` 从合成名 `untagged-36326328…` PATCH 成 `v1.0.21` 后，**只改 `body` 的 PATCH 又把它打回了另一个合成名** `untagged-7977464d…`（新建 tag `v1.0.21` 指向 `1011f09f` 已 push，仍被重置 ⇒ 与 tag 存不存在无关，是「PATCH 未带 `tag_name` 时服务端重新推导占位名」）。⇒ **正确顺序：先改 notes，最后 PATCH `tag_name`**；且改完必须**独立 GET 复核**（不能只看 PATCH 的响应）。否则转正时又会出现 `untagged-…`（同 v1.0.7 的坑）。
  - **教训**：「缺字段」类问题的取证不能停在「有没有这个 item」，要看到 **item 内部的字段**——`textLens=[] encrypted=True` 这一行（而不是「没有 reasoning」）才是破案点；也再次印证：错误文本里的字段名（`reasoning_content` vs `reasoning_text`）直接指示走的是哪条请求路径。
- 2026-10-05：**handoff 待办清单刷新（划掉已完成 + 补上本轮新增）**——`docs/handoff-2026-09-04.md` 的 §〇 快照与
  §三 待办表最后刷新于 10-02，其中多项已被落地却仍挂着，直接照抄会报过期清单。本次：
  - **§〇 现状快照**：版本体系改 **v1.0.24 (287) = Latest**（tag→`cda7c3df`，`sha256:77d9f7a7…`）+ **287 对应三份
    内容 ⇒ 下次必须 bump**；上游同步点改 `85af5b91`（10-04，上游 2.5.6）；「最近完成」重写为 10-02~10-05 段
    （前缀分叉诊断 / 华灯 P2 四件 / 折叠持久化 / 两起真机崩溃修复 / 会话自定义顺序 / 压缩·半截·续写 / 备份加固 /
    #1991 / #1976 / imggen OpenRouter / rootfs 安装 / workspace 提示 / README 重写）。
  - **§二 发布流程**：新增「2.1 三条坑」——①改 notes 会重置 `tag_name`（固定顺序：资产 → notes → PATCH tag_name+target
    → 独立 GET 复核）②PATCH target 后 Publish 的 tag 落点判据（本次标准路径精确落位；发布后 `ls-remote` 核对是必做收尾）
    ③验证「重打的包含本次改动」靠 mapping 查新符号、用户日志带 `r8-map-id` 先与本地比对。
  - **§三.1 表**：保持 4 项未完成（终端③⑤⑦⑧ / rootfs①-③ / 记忆回收站 / 记忆提取管道），**新增 4 项**（S3 presignGetUrl、
    Yuihub 剩余小件〔ca-certificates / 封面先行 / PendingRestoreStore〕、华灯剩余候选〔R8 keep 家族 + Jev 契约〕、
    上游 10-04 剩余〔Alpine 本体按需〕）；并在节首注明「本次逐项与代码核对过」。
  - **§三.2 issue 跟踪**：划掉 #1991（10-04 两条现象已修）、#1992（三层防护免疫）、#1995（10-05 全量落地随 v1.0.23）、
    #1990（`a6dbb8cd` 吸收）、#1976（残余兜底）；新增 **10-05 增量复核**块（思维链 400 两半 + 同类触发点三点 / 上游 10-04
    两提交评估）。
  - **§三.3 未立项**：新增 10-05 候选（工具 schema 按需加载=剩余最大项含 senera 参考实现路径、定时任务/自动化、自动备份、
    models.dev、华灯群聊/宏引擎 2.0/MVU/Office 读写、过程性小件与「仅在补 character_book / 做卡 regex_scripts 时必带」的两条前置件）。
  - **§三.6 验证债（新增节）**：8 项「只有单测/缺真机证据」清单（S3 大文件真实上传、子代理并行拒绝与预算耗尽、两层代理真实
    服务器、自定义顺序用户侧回归、裸断流续写……）+ 已闭环验证清单（两起崩溃、备份加固、前缀分叉诊断）。
  - **顺手修**：该文档 3 处表格行格式异常（**既有问题**：两行内容含裸 `|` 导致列数多 1、一行缺单元格分隔符）——修后
    5 张表 0 处不一致。
- 2026-10-05：**README 定制功能部分按「与上游一致即移除」重写**——用户要求：与上游一致的功能从「定制功能」移出，并更新其他节表述。
  - **判定方法（可复用）**：对每一项做**两侧代码级标记比对**（`git grep -lI` 在 fork 工作树与 `upstream/master` 各查一次），
    而非凭印象。关键补充：`git grep` 匹配的是**内容不是文件名**——首轮把 `McpToolSchema` 当模式查上游得到 0，实际上游
    `cf79246b` 新增的该文件里没有这个字面量；**结论要靠「文件是否存在」+「符号是否存在」两种查法交叉验证**
    （`git ls-tree` 看文件、`git grep` 看符号），否则会得出相反结论。
  - **移除（与上游一致 ⇒ 并入「核心功能（继承上游）」）6 项**：消息队列（两侧各自实现）、终端多 Tab（吸收自 `3a533a6a`）、
    内置技能（吸收自 `7f17dcc8`）、`chart_display`（吸收自 `00c8d53a`）、ExpressivePopup（移植自 `53f224b1`）、模型列表折叠
    （上游 `ModelList.kt` 同款分组）；另**从 MCP 行中删掉 `$defs` 展开与 OAuth 两子句**（上游 `cf79246b` 自修 + 上游有
    `McpOAuthCoordinator/DiscoveryClient`），并把「生成自动重试」独立行**并入半截保留行**（上游有 `enableAutoRetry`，
    本分支增量只剩「按失败类型分流 + fallback 时压缩重试预算」）。
  - **据实收紧的表述**：收藏/Token 统计改为「基础继承上游 + 本分支增量」（上游有 `FavoriteDAO` 与 `StatsVM`，标签/管理页/双口径/按模型是本分支增量）；
    ST 行去掉「动态宏」（与上游提示词变量重叠），改称「SillyTavern 深化」只列官方语义世界书/预设导入/角色卡 V1/注入迁移；
    备份行补上后期新增的两项（生成的图片纳入备份、换库前完整性校验）。
  - **其他节更新**：项目简介改为「同步点 `85af5b91`（上游 2.5.6，2026-10-04）+ 独立版本 1.0.x（当前 1.0.24/287）+ 上游不接受 PR」；
    核心功能（继承上游）补齐上述 6 项与图像生成/语音/备份恢复/收藏分享统计/MCP OAuth；Markdown 行去掉 **TikZ**（那是本分支特性，非继承）；
    后续规划标「本地 OCR 暂缓」并新增「工具协议瘦身（schema 按需加载）」评估中；构建指南补 `scripts/build.sh` 用法、
    并把 JDK 表述精确为「JDK 21（构建验证版本；字节码目标 17）」；分支结构补 `git fetch upstream master:master` 免切工作树的快进手法。
  - **顺手**：修掉表格单元里的三反引号围栏（`tikz`，会破坏表格渲染），并做了表格列数自检（8 张表 0 处不一致）。
- 2026-10-05：**v1.0.24 (287) 转正为 Latest**——`gh release edit --draft=false --latest`；发布后
  `git ls-remote --tags` 核对：**tag 精确落在 `cda7c3df`**（= 资产构建所据的代码提交 = draft 里钉的 target），
  **无需 `tag -f` 纠正**（前几次「tag 落在早前值」的坑本次未复现——draft 阶段 PATCH 到正确 target 后
  Publish 直接沿用，说明该坑的触发条件是「target 曾停留在旧值/占位名被重置」这类前置脏状态）。
  资产 `RikkaHub.Plus-1.0.24-arm64-v8a.apk` **77,666,210 字节 / `sha256:77d9f7a7…`**（与本地逐字节一致）；
  仓库**无草稿**；README 改「正式发布：`v1.0.24`」。
  **本版内容**（自 286 资产）：前缀分叉诊断、华灯 P2 四件（缓存稳定性分组/投影无损回环/记忆相关性下限/工具描述排查）、
  拼装预览折叠持久化、**请求内容页格式化崩溃修复**、**nav3 空 backstack 崩溃修复**、智能体并发提示字面量修复。
  **遗留**：`versionCode 287` 对应三份内容（两版资产 + 首发）⇒ 下次发布必须 bump（同 283 的局面）。
- 2026-10-05：**修「打开请求内容页崩溃：`IllegalFormatConversionException: d != java.lang.String`」（用户报错，release 日志）**——
  现象：打开「请求内容」页即崩，栈顶是 `Resources.getString` ← `stringResource`（Compose 帧）。**先用 APK 里的 `r8-map-id`
  与本地 `mapping.txt` 对齐确认「用户跑的正是我们刚替换进草稿的那个包」**（两侧 id 都是 `a602a9b8…`），再定位代码。
  - **根因**：`prompt_debug_diverged_at` = `分叉于第 %1$d 条：%2$s`，调用点只传了一个实参（preview 字符串）⇒ `%1$d` 收到 String 即抛。
    属 `7e29e7f6`（前缀分叉诊断）引入——**只要页面出现「有分叉」就必崩**，而那次我只跑了逻辑单测（`diagnosePrefixDivergence` 全绿）、
    **从没在设备上打开过这个页面**。修：补序号实参（`commonPrefixMessages + 1`，与上一行「前缀一致 N 条」连贯）。
  - **顺手同类**：`agent_run_dispatch_busy` 无参调用不做格式化 ⇒ 提示里把 `%1$d runs are already active (limit 2)` 的 `%1$d` **字面显示**
    给用户（不崩但很难看）⇒ 补活跃数实参。
  - **守卫（补上漏掉的那一环）**：新增 `StringFormatArgsGuardTest` —— 纯 JVM 源码扫描（无需 Robolectric/资源编译）：
    ①调用点传了 ≥1 实参时，实参个数必须 ≥ 各 locale 的最大占位符编号（少传必崩）；②`%N$d` 位置不得传「一眼是字符串」的表达式；
    ③**0 实参调用不查**（本仓既有「无参取串后 `.format(...)`」模板用法，不做格式化不会崩）。
    **有效性用「把 bug 放回去」验证**：测试立即变红并给出精确位置（`PromptInspectPage.kt:385 … 需要 2 个`）——守卫不经过这个验证就不算守卫。
  - **审计手法（可复用）**：另写了一次性脚本 `build/audit_fmt.py`（解析所有 locale 占位符 + 扫调用点实参个数/类型），
    一次扫出 10 处可疑、逐条核实后只有 1 处真崩 + 1 处显示 bug（其余是模板用法误报）——**先全量扫、再逐条证伪**，
    比只盯报错点更靠谱。
  - **真机验证（本次补齐上次漏掉的一步）**：release 装机 → `uiautomator dump` 取控件坐标 → 发两条消息造出「上次/本次」记录 →
    打开该页，卡片正常渲染 `前缀一致 2 条 ≈ 957 tokens 可命中（上次 3 条 / 本次 4 条）` + `分叉于第 3 条：助手：[思考]`
    （与「前缀一致 2 条 ⇒ 分叉在第 3 条」自洽）、同屏「缓存稳定性 ≈5282 稳定 · 0 事件 · 793 每轮变化 tokens」也对；
    logcat `FATAL EXCEPTION` / `IllegalFormatConversion` 均为 0；全量单测绿。
  - **教训**：**逻辑单测全绿 ≠ 功能可用**——新 UI 功能必须在设备上把页面点开一次（尤其「只在特定数据形态下才渲染的分支」，
    如本页的「有分叉」分支）。本次是「只测了纯函数、没打开页面」的典型漏网。
  - **资产再替换（第三次，同「不 bump」口径）**：`77,665,526 / ec0077f2…` → **`77,666,210 / 77d9f7a7…`**，
    `target` 移到 `cda7c3df`；notes 首条改为「打开请求内容页崩溃修复」并补「并发上限提示字面量」一条。**287 至此对应三份内容
    （同 v1.0.21 的 283）⇒ 下一次发布必须 bump**。
- 2026-10-05：**v1.0.24 (287) 草稿资产再替换（用户选「不 bump」，与 v1.0.21 同口径）**——把含 nav3
  空栈崩溃修复的 release 包覆盖草稿资产：**77,615,547 → 77,665,526 字节**，digest
  `sha256:68e42c63…` → **`ec0077f2…`**（与本地逐字节一致），`target_commitish` 从 bump `10a6a0e0`
  移到含修复的 HEAD `ec5b3870`；notes 首条补上崩溃修复（中英双语）。
  - **按教训定的执行顺序**（`df0d1069`）实际走通：**先替换资产 → 再 `gh release edit --notes-file`
    → 最后 PATCH `tag_name`+`target` → 独立 GET 复核**——复核 tag_name 仍为 `v1.0.24`
    （**未被重置成 `untagged-…`**）。**注意：这个顺序只在「替换资产发生在改 notes 之前」时安全**；
    任何在 notes 编辑之后的 PATCH 都必须显式带上 `tag_name`。
  - **包内容核实手段（可复用）**：`app/build/outputs/mapping/release/mapping.txt` 里查新符号
    （本次 `grep repairIfEmpty` 命中 `NavContextKt.repairIfEmpty`）⇒ 证明「重打的包确实含新代码」，
    比单看体积/digest 更硬（体积只说明「不同」，说明不了「含哪次改动」）。
  - **287 至此对应两份不同内容**（旧包不可得）⇒ **下一次发布必须 bump versionCode**（同 v1.0.21 的
    283 局面）。转正时按老规矩：发布后 `git ls-remote --tags` 复核 tag 落点，错位则 `tag -f` 纠正
    （tag 由 target 创建的路径此前实测会落在早前值上）。
- 2026-10-05：**修「空 backstack 导致 NavDisplay 崩溃且崩溃循环」（真机实测，用户报错）**——现象：
  `IllegalArgumentException: NavDisplay backstack cannot be empty`（`NavDisplay.kt:361` 的 `require(isNotEmpty())`），
  **3 秒内连崩两次**，直到系统 Force finishing；崩溃后 fork 的 `SafeModeActivity` 接管（CrashHandler 按设计工作）。
  - **根因两条**：①nav3 用 `repeat(entries.size - currentScene.previousEntries.size) { onBack() }` 补投返回事件对齐差值，
    **手势状态过期时计数多出一次** ⇒ 把根页也弹掉 ⇒ 栈空（1.1.5 与 1.2.0 同此数学，见两份 NavDisplay.kt 源码比对）；
    ②**崩溃现场被写进 saved state**：Activity 重建时 `rememberNavBackStack` 原样还原空栈 ⇒ **崩溃循环**（这解释了「连崩两次」）。
  - **修法（双防线 + 对齐上游）**：①`RouteActivity` 的 `onBack` 加 `size > 1` 守卫，根页返回维持系统语义 `finish()`
    （nav3 自带的默认实现同样不守卫——上游也是同样的裸 `removeLastOrNull()`，等于上游同样有此隐患）；
    ②进入 `NavDisplay` 组合**之前** `repairIfEmpty(startScreen)` 补回根页（幂等；抽成 `NavContext.kt` 的扩展函数便于单测），
    让「saved state 里已经躺着空列表」的存量用户自愈；③nav3 `1.1.5 → 1.2.0` 对齐上游——1.2.0 把返回事件改为**派发时经
    `sceneState` 读实时场景**（1.1.5 用组合期捕获的局部变量），减少过期计数，是成因 ① 的上游侧收敛。
  - **顺带清理**：移除 `adaptive-navigation3`（全仓零调用点，且声明版本是 `1.0.0-SNAPSHOT`——仓库未配快照源、实际由
    `adaptive-layout` 传递带入 1.3.0-rc01）；解析核实 material3 仍 `1.5.0-alpha25`、compose 仍 BOM 的 `1.12.0-beta01`
    （nav3 1.2.0 只要求 runtime 1.10.0，未拉高任何传递依赖）。
  - **验证（真机，debug + release 双包）**：启动零 FATAL；目标页压栈 + 连续快按返回 ×3 无崩溃；系统「不保留活动」
    （`always_finish_activities=1`）下的**销毁/重建恢复**——截图取证**恢复到 2 层栈的目标页**（不是根页、无崩溃），
    返回正确弹回聊天页、根页返回正常退出；全程 `NavDisplay backstack` / `FATAL EXCEPTION` 计数为 0。单测 +3
    （`BackStackRepairTest`：空栈补根页 / 非空不动 / 幂等）。
  - **教训**：`require(isNotEmpty())` 类不变量在**恢复路径**上会被历史坏状态反复触发——「不清洗 saved state 的输入，
    就会把一次性崩溃升级成开屏崩溃循环」；而恢复/重建类 bug 必须用「不保留活动」或进程死亡来测，正常点按永远碰不到。
- 2026-10-06：**修「多重公式对齐」渲染成源码（`4839e347`）**——用户报会话 `Tikz中文支持` 里该条目两端
  （App + Web）都渲染成一行源码、`\\` 被吃成 `\`，初判为「KaTeX 不支持 align」+「某处把 `\\` 折叠了」。
  - **两条假设都成立，但都不是真因**。逐字节核对存储原文（`/api/conversations/{id}` → `messages[61]`）后发现：
    该条是**裸环境** `\begin{align}…\end{align}`，**没有任何数学定界符**——相邻的分段函数/复杂条件概率
    都规矩地写了 `\[…\]`，只有它漏了。没有定界符就压根不进数学渲染器，被当普通段落文本：
    markdown 把 `\\` 当转义吃成 `\`、软换行折叠成空格、`&`/`\,` 被转义。**这正是 App 与 Web「表现完全一致」
    的原因**——跟两端各自的渲染器（jlatexmath / KaTeX）无关；若真是 KaTeX 的 align 限制，App 侧不可能表现相同。
  - **实测澄清三件事（可复用）**：①数学模式内 `\\` 完好（行内 `$\begin{aligned} a &= b \\ c &= d\end{aligned}$`
    正确产出 2 行 mtable，KaTeX annotation 里 `\\` 原样保留）；②`$$` 必须**独占一行**——`$$\begin{align}` 同行时
    remark-math 会把数学节点从 `\begin` 之后才开始（`$$\n…\n$$` 才对）；③环境支持矩阵——KaTeX 0.16 的
    `align/align*/gather/gather*/alignat/alignat*/equation/split/CD` **仅 display 模式可用**（行内直接报
    `can be used only in display mode`），`multline/multline*/flalign` **完全不支持**，而
    `aligned/alignedat/gathered/cases/dcases/rcases/array/矩阵家族` 两模式皆可；jlatexmath 1.5 预定义环境含
    `array/align/aligned/flalign/alignat/alignedat/multline/gather/gathered`（解 aar 从 class 常量读的）。
  - **修法**：新增 `LatexEnvironments`（App `richtext/LatexEnvironments.kt` + Web `markdown/latex-environments.ts`
    两份等价实现），接入 App 两处 `preProcess` 与 Web `preProcess` 的**最前面**：①裸块级环境补独占一行的 `$$`，
    **保留列表项缩进**（缩进丢了数学块会脱离列表项）；②环境名归一为两端都认的等价物（`align→aligned`、
    `gather→gathered`、`alignat→alignedat`、`equation/flalign/multline→aligned/gathered`），
    `tikzpicture/tikzcd/axis` 只补定界符**不改名**（要原样交给 TikZ 编译器）；③保护代码块与已带
    `$$`/`\[…\]`/`\(…\)`/`$…$` 的区间，避免二次包裹成 `$$ $$ … $$ $$`；④流式中途未闭合时不补定界符。
  - **验证**：单测 `LatexEnvironmentsTest` 17 例；Web 侧 28 项断言脚本（`pnpm run test:latex`，与 Kotlin 行为对齐）；
    `:app:testDebugUnitTest` 全量 **1279 例全绿**；该会话 15 个文本 part 新旧链路回归扫描 **katex 正常块 18→19、
    katex-error 13→13、无新增错误**（13 个 error 全是 KaTeX 本就渲染不了的 TikZ 块，App 走 Tectonic）；
    App 实机（Xiaomi 8 / A14）三重对齐出 **3 行**正确公式；Web 实机抓真实页面 DOM：该段为 `katex-display`
    含 **3 个 `<mtr>`**、无 `katex-error`、无裸 `\begin{align}`、整页残留裸环境 **0**。
  - **教训一（方法）**：**用户给的复现假设要验证，不能照着改**。若只做「align→aligned」单点改写，既修不好
    （根因是「没进数学模式」），又会在两端留下不一致。**先取原始数据（存储原文逐字节 repr）再定因**，
    比在渲染链路上试错快得多。
  - **教训二（自坑）**：第一轮"复现"用的是**合成字符串**——在 JS 源码字面量里写 `\\` 到运行时只剩一个
    反斜杠，于是"看到"了一个并不存在的「`\\` 被吞」症状（该临时脚本已删，写法无法回头复核，但
    **真实存储文本喂同一条管线后 `\\` 完好无损**，已足以定论）；差点据此去改渲染链路。**复现必须用真实输入；
    合成输入要 programmatically 断言其运行时字节（`JSON.stringify`/`ascii()` repr），而不是肉眼看源码字面量。**
- 2026-10-06：**v1.0.25 (288) 草稿已建（含 APK 资产，release 已装机冒烟）**——Latest 仍 = **v1.0.24 (287)**
  （`cda7c3df`，77,666,210 B）；新草稿 = **v1.0.25 (288)**，release id `404257117`。
  - **触发**：上一版资产对应 **287**（同一 versionCode 下被替换过三次内容），故本次**必须 bump**（记在
    `f1156377`）。`287 → 288`、versionName `1.0.24 → 1.0.25`（已发布版本之后起**新版本号**，非同名重建，
    与 09-28 v1.0.16 同口径）。bump `305c805b`，**先 push 再构建**（转正 tag 按远端分支 HEAD 建的坑）。
  - **范围 = `cda7c3df`（287 资产的构建点）之后的 6 项代码提交**（其余为 docs）：
    `read_conversation` 第三层（`ee173643`）、MCP schema 动态分层加载（`c00176f5`）+ 折叠不再破坏子代理信封
    （`02727f1c`/`d1b63d0b`）、web-ui markdown XSS 修复（`3c7cd569`，安全）、工具级执行超时（`2e4b4ced`）、
    生成打断可见标记 + 日志页实时刷新（`b6faa877`）、裸 LaTeX 环境补齐定界符（`4839e347`）。
  - **流程与数字**：清 `app/build/outputs/apk/release/*.apk` + `app/release/*.apk` → release 构建
    **4m40s**（81 executed，配置缓存复用）→ aapt2 核实 `versionCode=288` / `1.0.25` / **仅 `arm64-v8a`**
    （77,678,270 B，sha256 `bb42e57d…`）→ **release 装机冒烟（287→288 覆盖升级）**：零 FATAL、会话数
    **248 不变**、`tokenizer install ok=true vocab=100256 in 521ms`（R8 未破坏词表资源）、MCP 两服务器
    28+49 工具照常同步 → `gh release create --draft --target 305c805b…`（**完整 40 位 SHA**）+ 分步
    `gh release upload` → **服务端 digest `sha256:bb42e57d…` 与本地逐字符相同**（无需下载 77MB）。
  - **收尾复核**：`releases?per_page=6` 按 id 查到该草稿 `draft=true`、`tag_name=v1.0.25`、
    `target_commitish=305c805b…`（与本地 bump 一致）、资产 `state` 正常；`releases/tags/v1.0.25`
    返回 404 属**草稿预期**（tag ref 发布前不建），**不要据此判资产坏**——判据是 API 的 digest。
  - **包内容核实（不只看体积）**：release APK 内 `static/assets/mermaid-GHXKKRXX-BmsWkaLc.js` 同时含
    LaTeX 归一化标记（`alignedat`+`semilogaxis`）与 rehype 清洗链 ⇒ 本版两项 web 侧修复确实在包里。
  - README 标注改为「正式发布：`v1.0.24` ｜ 草稿：`v1.0.25`」（沿用 `a28bea52^` 的既有格式）。**待用户
    GitHub 检查后转正**；转正后按老规矩 `git ls-remote --tags` 复核 tag 落点。
- 2026-10-06：**严重回归修复——输入处理链的产物被丢弃（`9c8796f3`）**，并据此**重建 v1.0.25 草稿资产为
  289**（`7871e597`）。**这条是本轮最重要的发现，起因是排查「注入为什么不好使」时顺手核对代码。**
  - **事故形态**（`7e29e7f6c`「前缀分叉诊断」改造引入，**已随 v1.0.24 (287) 发布**）：

    ```kotlin
    val internalMessages = buildInternalMessages(...)
    internalMessages.transforms(transformers = transformers, ...)  // ← 裸语句，产物被丢弃
    providerImpl.streamText(messages = internalMessages, ...)      // ← 发的是「未处理」列表
    ```

    改造前是**单表达式** `val internalMessages = buildInternalMessages(...).transforms(...)`（赋值在）；
    为了插一行 `debugKey?.let { …recordRequest(it, internalMessages) }` 而把它拆成两条语句时，**赋值丢了**。
  - **影响面**：整条**输入 transformer 链**的产物不再进入请求 —— 时间提醒、模式注入/世界书/预设条目
    （`PromptInjectionTransformer`）、ST 动态宏、占位符、文档转提示、OCR、引用展开、助手消息模板、
    workspace 提醒。**全部静默失效**：类型合法、无异常、无日志。
  - **为什么长期没被发现（关键教训）**：拼装预览 `PromptInspector` 是**另一份**实现，写的是
    `val transformed = internalMessages.transforms(...)`（正确赋值）⇒ **预览里注入段落都在、实发请求里
    一个没有**。历史上一批「注入归因/跳转/Tier 4」验证全部是对**预览**做的，自然全绿。
    ⇒ **对「同一条链的两个实现」，必须核对两边是否都把结果用上了**；只验一侧等于没验。
  - **修法**：接住返回值（`val requestMessages = …`）+ 两处 provider 调用改用它；并把前缀分叉诊断的记录点
    从「变换前」挪到「变换后」（它缓存的本就该是**实际发送**的列表，否则单次请求差分比错了对象）。
  - **守卫（已反向验证有效）**：新增源码级 `RequestMessagePipelineGuardTest`（无需 Robolectric，沿用
    `StringFormatArgsGuardTest` 的做法）钉两条不变量：① 不许把 `.transforms(...)` 写成裸语句（**识别实参
    位置**，否则 `emit(GenerationChunk.Messages(\n messages.transforms(` 会误报——我第一版就误报了，
    靠「上一非空行是否以 `(` 结尾」区分）；② `GenerationHandler` 里不许把未处理的 `internalMessages`
    直接塞进 `messages =`。**把事故形态注回去实测两条断言都失败**，证明守卫真能拦。
    另外修好后两处 provider 调用引用 `requestMessages` ⇒「删掉赋值」还会**直接编译失败**，双保险。
  - **重建资产（288 → 289，versionName 保持 1.0.25）**：288 已上传过、用户可能已下载 ⇒ **不能原地换包**
    （否则又一个「同一 versionCode = 两份内容」，正是 287 踩过的坑）。流程：清 outputs → release 构建
    （4m？实为一次通过）→ aapt2 核实 `289`/`1.0.25`/仅 `arm64-v8a`（77,677,614 B，sha256 `0e99f8e5…`）
    → 装机冒烟（零 FATAL、会话 248 不变、`tokenizer install ok vocab=100256`）→ **同一后台作业内
    `delete-asset` + `upload`**（避免「草稿无资产」窗口）→ `gh release edit --title "v1.0.25 (289)"` →
    PATCH `target_commitish`=7871e597…。
  - **实测到「PATCH 会把 tag_name 重置成 untagged」**：改 notes + PATCH target 之后 `tag_name` 变成
    `untagged-30a84cd4…`（与 `df0d1069` 记录一致，且比「表现不稳定」更进一步——**这次确实重置了**），
    按老规矩补一次 `PATCH tag_name=v1.0.25` 即恢复。**收尾独立 GET 复核**：`draft=true`、`name=v1.0.25 (289)`、
    `tag_name=v1.0.25`、`target=7871e597…`（= bump = 分支 HEAD）、资产 digest `0e99f8e5…` 与本地逐字符相同。
  - **另记一条网络事实**：本轮 `gh api` 多次 `dial tcp 20.205.243.168:443 … failed to respond`（PATCH/GET
    都中过），**重试即可**；因此收尾的 digest/tag 复核必须写成「带重试的循环」，不能一次不成就下结论。
  - **✅ 真机端到端验证（用户实测 + 落盘日志，2026-10-06 21:59，release 289）**：用户用默认助手绑定一条注入
    （内容以「你是我的专属私人全能助手…」开头）发了一句 `test`，从**落盘完整报文**
    （`/data/data/<pkg>/files/logs/rikkahub-logs.txt`，须在设置里打开「导出到文件」；logcat 有 4KB 截断，
    40K 级请求体根本看不到，故必须读这个文件）核对：
    - `POST https://open.bigmodel.cn/api/paas/v4/chat/completions` → **200 / 367ms**，`glm-4.5-air`，
      `stream=true`、`tools=3`；**只有 2 条消息**：`role=system`（2744 字符）+ `role=user`（`test`）。
    - system 正文**以注入内容开头**（8 条规则完整、只出现 **1 次**），空行之后才是助手本体提示
      （`You are a helpful assistant, called assistant, based on model glm-4.5-air.` + `## Hint` +
      Subagents + TikZ/公式渲染提示）⇒ **注入以 `system` 身份、在配置位置（BEFORE_SYSTEM_PROMPT=前插）、
      完整地进入了真实请求**，修复端到端成立。
    - 同一批日志另有一条**与注入无关**的失败：21:58:35 `POST openrouter.ai` → **404
      `No endpoints found for stealth/space-bunny-alpha`**（该模型在 OpenRouter 不存在）；该请求里注入同样齐全，
      说明失败与注入链路无关。末尾两条 `api.rinko.ai` 200 是标题/建议生成等附带请求。
    - **验证边界**：报文里看不到 `isSynthetic`（`@Transient`，且注解不进 wire），故第②条「模板免检」只能靠读
      `TemplateTransformer` 代码判断，不能从日志证实；本次只绑了 1 条注入，故第④条「同位置多条排序」
      也无从验证（需绑 ≥2 条同 position 的注入再发一次）。
  - **✅ 第④条（同位置多条排序）实测通过（2026-10-06 22:04，release 289）**：用户再绑两条后发送，落盘报文
    的 system 正文（14323 字符）**逐段与预设内嵌条目逐字吻合**：
    | 段 | 来源条目 | enabled | position | priority | 长度 | 请求内区间 |
    |---|---|---|---|---|---|---|
    | 1 | 助手 | ✔ | `before_system_prompt` | 0 | 341 | `[0,342)` |
    | 2 | 助手本体 systemPrompt(261) + 生成的 Subagents/TikZ/数学提示 | — | — | — | 2403 | `[342,2745)` |
    | 3 | 整合式学习教练 | ✔ | `after_system_prompt` | **4** | 8145 | `[2745,10891)` |
    | 4 | 研究报告撰写 | ✔ | `after_system_prompt` | **3** | 3434 | `[10891,14323)` |

    ① 三条各出现 **1 次**（无重复注入）、长度与条目逐字吻合（无截断）、非 system 消息干净；②
    `enabled=false` 的「学习教练」(8042) **未**出现 ⇒ 条目开关有效；③ 两条新绑的按 `after_system_prompt`
    落在本体之后，**priority 4 在前、3 在后 = 优先级大者在前**（`sortedByDescending`），
    **原版「数值小的排前面 / 大数被排到最后」的方向问题在我们这里不存在**。
  - **顺带纠出一个数据模型事实（我一开始被它误导）**：生效的注入条目是**预设内嵌副本**
    （新结构 `promptPresets[].entries`）；datastore 里独立的旧 key `mode_injections`（本机 14 条）
    **只作读取回退、不参与生效**（`PreferencesStore.kt:235-238` 注释即如此写）。两者同 id 却可以有
    **不同的 `enabled`/`position`**——旧副本里「助手」是 `top_of_chat`+`enabled=false`，而预设副本是
    `before_system_prompt`+`enabled=true`，实发报文证明生效的是**后者**。
    **推论（待核）**：web 侧 `promptPresets[].entries` 的 `enabled` 与真实生效状态**不一致**
    （SSE 里「助手/研究报告撰写」显示 false、「整合式学习教练」显示 true），若网页版注入页要展示/修改
    这些字段，取值来源需要核对到位——否则又是一个「UI 显示与实发不一致」。
  - **顺带记下数据取证手法**：`files/datastore/settings.preferences_pb` **不是 protobuf 而是
    「protobuf 外壳 + JSON 字符串」**（直接 `decode('utf-8')` 后 `json.JSONDecoder().raw_decode` 即可，
    旧 key 与预设数组都能这样抠出来）；比对「报文里的块」与「设置里的条目」用**内容首行 + 长度**做指纹，
    比看名字可靠（本机存在同名/前缀相同的条目，如 `学习教练` 是 `整合式学习教练` 的前缀）。
- 2026-10-06：**②「免检标签」定案（代码 + 真机实测双向确认）**——原版分析称「注入消息打 `isSynthetic`
  后被模板组件跳过 ⇒ 内容没走正常流程、模型能看出不对劲」。我们这边**机制成立，但性质与方向相反**。
  - **代码侧（穷举）**：全仓 `isSynthetic` 读点只有 4 处，**只有 1 处影响请求内容** ——
    `TemplateTransformer.kt:28` `if (message.isSynthetic) return@map message`；其余为
    `CompressPrompt:81`（检查点识别，且文件里明确写「判据只用文本前导语、**不能依赖该字段**」，因它是
    `@Transient`）+ `PromptInspector`×3 / `PromptInspectPage:568`（预览与 UI 显示）。
    `Placeholder`/`TavernMacro`/`Quote`/`Ocr`/`DocumentAsPrompt` **都不看该标记** ⇒ 「没走正常流程」在我们这儿
    只等于「没走**消息模板**这一步」。
  - **来源**：`6bbcacb6`「fix: 合成消息不参与消息模板（吸收上游 `942d0d28` / #1790）」，动机写在提交信息里：
    **时间戳稳定、保 prompt 缓存**（模板会把 `{{time}}/{{date}}` 渲染进每条消息，而合成消息的 `createdAt`
    就是本次请求时间 ⇒ 让它们参与渲染会让 system 前缀每请求都变）。⇒ 是**故意的缓存保护**，不是缺陷。
  - **打标范围比「注入」更大**：`PromptAssembly:276`（**system 提示本体，每次请求都打**）、
    `PromptInjectionTransformer`×4、`TimeReminder`、`WorkspaceReminder`、压缩检查点（`Conversation:189`）。
  - **实测（22:24:47，release 289，用户把某助手模板改成 `<|{{ role }}|>\n{{ message }}` 后发一条）**：
    报文 6 条消息 —— `[0] system` 14323 字符**无 `<|system|>` 包装**（仍是注入原文，A 在最前、研究报告在末尾），
    而 `[1] user`=`<|user|>\n test`、`[2] assistant`=`<|assistant|>\n…`、`[3] user`=`<|user|>\n 你能做什么`、
    `[4] assistant`=`<|assistant|>\n…`、`[5] user`=`<|user|>\n 如何提高专注力` **全部带包装**。
    **该模板是无条件包装（无 `{% if %}` 守卫，已从 datastore 取值核对）** ⇒ system 段的裸形态**只能**由
    synthetic 跳过解释，②**实测坐实**。（附带第三次印证注入生效：`[4]` 的回复自称「我是一个苏格拉底式认知教练」。）
  - **默认无感 + 反证「跳过≠不处理」**：默认模板 `{{ message }}` 恒等 ⇒ 跳过与不跳过等价（本机历史上所有
    助手的模板去重后只有 1 个值 `{{ message }}`，本次才出现第 2 个）。而 `PlaceholderTransformer` 不看该标记 ⇒
    system 里的 `{{char}}/{{model_name}}` 照常替换（实测：助手 systemPrompt 写的是
    `called {{char}}, based on model {{model_name}}`，报文里已是 `called assistant, based on model glm-4.5-air`）。
  - **遗留边界（上游取舍，非我们回归）**：模板一旦承担**必要转换**（给每条消息加角色标记、转义、裁剪），
    system 与注入就**不带**这些转换 ⇒ 同一份报文里两种形态并存（本次实测即如此）。默认恒等模板无此问题。
    若要消除：可选「合成消息也渲染、但只用**确定性的** createdAt（而非请求时间）」或另设 system/注入专用包装；
    但那是**偏离上游**的改动，需先确认是否值得。UI 的模板实时预览用的是**非合成**示例消息 ⇒ 看不到这个盲区。
  - **③（角色降级）按用户要求稍后单独处理**：已掌握的证据是 — OpenAI chat/completions 走
    `role.name.lowercase()` ⇒ SYSTEM 保留 ✔；**Responses API 只取第一条 system 进 `instructions`、其余 system
    被 `filter { role != SYSTEM }` 整条丢弃**；Claude/Google 把中部 SYSTEM `copy(role = USER)` 降级；
    注入器内部 `createMergedInjectionMessages` 的 `else -> user` 会把 TOP/BOTTOM/AT_DEPTH 的 system 注入降级
    （仅 `END_OF_CHAT` 保留 SYSTEM）。
- 2026-10-06：**②③ 按「② → 3a → 3b」三步推进（用户拍板路线）；②、3a 已落地**（`f0296567` / `c865e5af`，
  bump `6b5b225c` 290 已装机冒烟）。
  - **② `systemMessageTemplate`（`f0296567`）**：合成消息（system 本体 / 注入 / 提醒 / 检查点）新增**独立**
    模板字段（默认 `""` = 不套用 ⇒ **零行为变化**）；设了才渲染。安全性靠两条构造性保证：
    ① 该模板上下文**只有 `{{ role }}`/`{{ message }}`，刻意不给 `{{ time }}/{{ date }}`** ⇒ 渲染结果与请求
    时刻无关、system 前缀稳定（用户误写 `{{ time }}` 时 Pebble 非严格模式渲染为空串，同样稳定）；
    ② **缺 `{{ message }}` 按未设置处理**（回退不套用）——刻意与 `messageTemplate` 不对称：那边漏写只丢这一条，
    这边漏写会让**整段 system 提示 + 全部注入**渲染成空后照样发出（最难的静默失败）。
    顺带把**接线本身**抽成纯函数（`applyTemplates`/`usableSystemTemplate`/`syntheticTemplateContext`）以便
    JVM 单测覆盖（真实 Pebble 引擎，无需 Robolectric）；助手页加编辑框 + 缺 `{{message}}` 红字 +
    **设了模板时把合成样本加入实时预览**（治「预览只跑非合成样本」盲区）。6 语言串 ×4。
  - **3a 发现真 bug（`c865e5af`）**：给 ResponseAPI 补回归测试时**测试直接失败**，回头核对发现我照抄的
    Claude/Google 过滤条件是**死代码陷阱**——

    ```kotlin
    .filter { it.isValidToUpload() && (it.role != SYSTEM || it === firstSystem) }   // ← 非首条 system 在此被滤掉
    .forEach { when { message.role == SYSTEM -> addUserMessage(message.copy(role = USER)) /* 永不执行 */ } }
    ```

    非首条 system 在 `filter` 就被排除（两个条件都 false）⇒ **SYSTEM→user 降级分支永远到不了**，三家
    （Claude / Google / Responses）实际行为与注释声明的「后续 system 不能丢弃 ⇒ 降级为 user」**完全相反**：
    尾部 system 注入（`END_OF_CHAT`+role=system、压缩检查点）**被静默丢弃**；只有 OpenAI
    **chat/completions** 路径（`role.name.lowercase()` 原样透传）不受影响。
    **修法**：三处过滤条件统一改为「保留全部可上传消息」，首条跳过交给 `when` 分支。三个 provider 各加一条
    同款回归测试（断言首条不进 messages/contents/input、尾部 system 文本**必须出现**且以 `role=user` 出现）：
    **修正前三条全红，修正后全绿**（`:ai:testDebugUnitTest` 242 例、`:app:testDebugUnitTest` **1289 例**）。
  - **⚠️ 纠正此前判断**：我曾据注释判定 Claude/Google 是「有意降级、有注释说明」，只有 Responses 在静默丢弃；
    实测是**三处同款死代码**。**教训：注释声明的行为必须用测试钉住**——`filter` + `when` 组合最易出现
    「分支永远到不了」，而注释会把死代码说得像正常设计。
  - **3b 待办**：中部 system 语义（`createMergedInjectionMessages` 的 `else -> user` 让 TOP/BOTTOM/AT_DEPTH
    的 system 注入在**注入器内部**就降级）+ 逐 provider 映射；需先拍板「降级时是否加标记包裹」
    （如 `<system-reminder>…</system-reminder>`，与既有 `<recovery_note>` 同风格），再单独一轮逐家真机验证。
  - **收尾计划**：3b 落地后**重建同一个 290**（草稿未发布，同号重建无歧义）→ 替换 v1.0.25 草稿资产 → 真机端到端。
- 2026-10-06：**③-3b 落地（`8d8397fe`）+ 草稿资产替换为 290 —— ②③ 三步全部完成**。
  - **注入器照抄配置角色**：`createMergedInjectionMessages` 增加 `MessageRole.SYSTEM -> UIMessage.system(...)`；
    此前 `else -> user` 让 TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH 的 system 注入**在注入器内部**就降级，
    与条目上选的角色不符。**能否承载改由 provider 层按各自 API 能力决定**。
  - **provider 三种承载**：OpenAI **chat/completions** `role.name.lowercase()` 原样透传（中部 system 直接发、
    不做标记）；**Claude / Gemini / Responses** 不能承载中部 system ⇒ 降级 user + `<system-reminder>` 包裹
    （新增 `UIMessage.asDowngradedSystemMessage()`：只包 Text part、**幂等**、与既有 `<recovery_note>` 同风格）。
  - **验证**：`DowngradedSystemMessageTest` 3 例（包裹形态 / 非文本 part 不动 / 幂等）+ 注入器 1 例
    （system 角色的 TOP 注入保留 SYSTEM）+ 三个 provider 各 1 条包裹断言；`:app:testDebugUnitTest` **1290 例**、
    `:ai:testDebugUnitTest` **245 例** 全绿。
  - **资产**：重建同一个 290（`0cae82f9…`，77,686,378 B）→ 装机冒烟（零 FATAL、会话 249 不变）→ 草稿
    `delete-asset`+`upload`（同作业避免空窗）→ `--title "v1.0.25 (290)"` + notes 补两条（系统内容模板 /
    system 角色注入不再被丢或裸降级）→ **PATCH `target_commitish`=8d8397fe 后 `tag_name` 果然又被重置成
    `untagged-7507564b…`**（第三次实测该行为），按老规矩补 PATCH 回 `v1.0.25` → 独立 GET 复核
    `draft=true` / `name=v1.0.25 (290)` / `tag_name=v1.0.25` / `target=8d8397fe…` / digest 与本地逐字符相同。
  - **用户可验**：① 助手页设「系统内容模板」= `<|{{ role }}|>` + 换行 + `{{ message }}` ⇒ 报文里 system 段
    应变成 `<|system|>` 开头（留空则保持裸文本）；② 把某注入条目的角色设为 **system**、位置设为
    TOP_OF_CHAT/AT_DEPTH ⇒ 报文里应出现**独立的 `role: system` 消息**（此前会被注入器降级成 user）。
  - **⚠️ 遗留（未做）**：中部 system 在个别 OpenAI 兼容网关上可能被拒（用户可选规避：把条目角色改回 user）；
    「降级包裹」目前只覆盖 Claude/Gemini/Responses 三家，新增 provider 若引入同类降级需照抄
    `asDowngradedSystemMessage()`。
- 2026-10-06：**③-3b 真机端到端验证通过 + 补上「中部位置没有 system 可选」的可用性缺口（`85f8e541`）；
  ②③ 三步全线闭环**。
  - **② 前后对照（同一助手、同一会话，chat/completions）**：22:59（289，未设系统模板）`[0] system` = 14323
    字符**裸文本**；23:14（290，设 `<|{{ role }}|>`+换行+`{{ message }}`）= **14334** 字符、以 `<|system|>`
    开头。差值 **11 = `"<|system|>\n"` 的长度** ⇒ 干净可量化。Responses 路径上 `instructions` 同样带
    `<|system|>`，且 `user`/`assistant` 每条都带各自角色包装 ⇒ **形态一致达成**。
  - **③-3b 端到端（23:35:09，`api.deepseek.com/responses`）**：把「研究报告撰写」（`bottom_of_chat`）角色
    改成 **system** 后，报文 `input[15]` = **`role: user`**（Responses 不能承载中部 system ⇒ 降级），
    内容 `<system-reminder>\n<|system|>\n## 角色 (Persona)…\n</system-reminder>`：
    ① 开/闭标记各 **1** 次（无重复包裹、幂等有效）；② 包裹在**最外层**、里面是 ② 的系统模板产物
    （`<|system|>`）⇒ **分层顺序与设计一致**；③ 同请求 `instructions` 仍带 `<|system|>`。
  - **发现的可用性缺口（已修）**：`InjectionRoleSelector` 只在 `END_OF_CHAT` 给 `[USER, SYSTEM]`，**其余位置
    只给 `[USER, ASSISTANT]`** ⇒ **`system` 根本选不到**，3b 虽已实现却无从配置/验证（我先前「把角色改成
    system」的指示在 UI 上不成立）。修法：中部位置放开 `[USER, ASSISTANT, SYSTEM]` + 每个角色一句说明；
    **`BEFORE/AFTER_SYSTEM_PROMPT` 干脆去掉选择器**（该位置角色本就不参与判断，内容一律并进 system 正文，
    留着只会误导）；`END_OF_CHAT` 维持原样。
  - **assistant 角色按用户拍板降级为 user**：依据是本次日志的 A/B——`role=user` 时模型把注入读成「**你贴的**
    那段内容」；`role=assistant` 时它等价于「模型自己说过要变成 Perplexity」（预填充语义），模型直接拒绝
    「这个我不会照做」。两种角色都**无法**被当成系统指令接受 ⇒ 正好印证 ③ 的必要性（`system` 才是正确载体）。
    降级后内容不丢、也不再冒充模型发言；尾部位置仍是**更狠的忽略**（两处口径写进 KDoc）。
  - **踩坑**：新加韩文串用了 ASCII 单引号 ⇒ aapt2 `Invalid unicode escape sequence in string`（与既有教训
    同源：Android 资源里的单引号必须转义或用弯引号）。改用 `‘…’` 通过。
  - **验证**：`PromptInjectionTransformerTest` 新增 1 例（中部 assistant 角色按 user 送达）+ 既有注入/尾部用例
    全绿；`:app:testDebugUnitTest` **1291 例**。资产：重建 290（`2a1e1c8a…`，77,693,038 B）→ 装机冒烟
    （零 FATAL、会话 249 不变）→ 替换草稿资产 → `--title "v1.0.25 (290)"` + notes 两条各补一句（角色选项
    此前中部选不到 / assistant 不再冒充模型发言）→ **PATCH `target_commitish`=85f8e541 后 tag_name 第四次
    被重置**（补 PATCH 回 `v1.0.25`）→ 独立 GET 复核：`draft=true` / `name=v1.0.25 (290)` /
    `tag_name=v1.0.25` / `target=85f8e541…` / digest 与本地逐字符相同。
  - **✅ 三条分支全部真机验完（23:40:59 补上最后一条，智谱 chat/completions）**：同一助手、同一条注入
    （`bottom_of_chat` + `role=system`），`POST open.bigmodel.cn/.../chat/completions` → **200 / 1611ms**，
    13 条消息 —— `[0] system` 10901 字符（合并后的系统提示，带 `<|system|>`）与 **`[11] system`** 3443 字符
    是**两条不同的消息**（`[11]` 前 80 字符 = `<|system|>` + 换行 + `## 角色 (Persona)…`）⇒ **原样发独立
    `role: system` 消息、不加包裹、`<system-reminder>` 计数 0**，与设计完全一致；且**智谱接受「非首位
    system」**（我先前标的网关风险在该家未出现）。全日志状态码：200×24、404×1（那条 openrouter 不存在
    的模型）。⇒ **能承载则原样 system（chat/completions）／不能承载则降级 user + 标记包裹
    （Claude/Gemini/Responses）**：chat/completions 与 Responses 已真机验证，Claude/Gemini 由单测覆盖
    （本机无该家密钥）。
  - **⚠️ 收尾提醒**：本轮 `python -c "…"` 里**未转义的反引号被 bash 当命令替换**，把上面这段的首版文字吃掉了
    几个词（已用 Edit 工具修复）——**含反引号的文本一律用 Write 脚本文件或 Edit 工具落盘**，这是本会话第二次
    踩同源坑（第一次是 python 里的 `\n` 展开成真实换行）。
- 2026-10-08：**三条外部材料的评估（DSH #5976「思考退化循环」/ Cordis `thinking-loop-guard` 插件 / `hue913/agentd`）
  = 发现本仓护栏家族的一个真空格 + 一条疑似已存在的签名缺陷**。本轮**只记账、未动代码**（用户拍板）。
  来源：[discussions/5976](https://github.com/deepseek-ai/deepseek-harness/discussions/5976)、
  [argszero/cordis-plugin-thinking-loop-guard](https://github.com/argszero/cordis-plugin-thinking-loop-guard)、
  [hue913/agentd](https://github.com/hue913/agentd)。
  - **真空格（护栏家族第四格是空的）**：#5976 的形态 = 回合内**零工具调用、零正文**，只输出推理且陷入低熵自重复，
    **无任何自动熔断**，只能人工中止。本仓四个判据的前提全部落空——`ToolCallLoopGuard`（同工具同参数）/
    `WorkspaceTurnBudget`（workspace 输出与步数）/`DenialGuard`（按 run 计策略拒绝）**都以「有工具调用或拒绝事件」
    为前提**；「空回复补救」（`GenerationHandler.kt:303/395`）只在那次调用**已结束**后生效，且只重试一次。⇒
    ①**单次调用不结束时我们没有任何墙**：`readTimeout=10min`（`DataSourceModule.kt:219/280`）是**每次 socket 读取**
    的超时，推理 delta 不断到达即不断重置 ⇒ **永不触发**；`maxSteps`（聊天路径 64，`ConversationSession.kt:83`）是
    **步数**，不结束的调用根本走不到下一步。②跨步空转则被补救一次后继续烧推理直到 64 步。
  - **可借的判定**（都在我们能力圈内、可与护栏家族同款做成纯函数 + JVM 单测）：三层形态（reasoning-only／
    **跨调用 restated-material**／intra-call 自重复）；跨调用相似度用 **containment 而非 Jaccard**（「复述上一步
    再补一句」用 Jaccard 会被新内容稀释、读成进展）；**调用内熔断**（连续 N 个相同 delta → 从内部结束该流、产出
    终态错误码走正常错误路径，**并先排一条纠正措辞**；且**刻意不做模型 fallback/自动重试**——「静默地给一个已退化
    的模型重新计费，比循环本身更糟」）。判据口径见 `upstream-analysis.md` 注意点 13/15。
  - **✅ 已核实（2026-10-08，机制层面确证）**：`#1929` 原地编辑推理 × 带签名块回传**成立**，且比原先判断的面更大。
    **实测证据**（临时单测经既有反射缝调 provider 私有 `buildMessages`，断言通过后临时文件已删；原始 stdout 见
    `ai/build/test-results/…/TmpClaudeEditHazardTest.xml`）：
    - Claude：`original.copy(reasoning = "EDITED_REASONING")` 后发出的块 = **`{"type":"thinking","thinking":"EDITED_REASONING",
      "signature":"sig-ORIGINAL"}`**——**改过的文本配原签名**；同一断言证明 `metadata` 在 `copy` 后原样保留
      （签名存活的原因），且 `UIMessagePart.Reasoning.metadata` 是**可序列化字段**（非 `@Transient`）⇒ 跨重启/DB 往返仍带签名。
    - OpenAI 兼容 chat/completions：默认 `ProviderSetting.includeHistoryReasoning = true` ⇒ 发出的 assistant =
      **`{"role":"assistant","reasoning_content":"EDITED_REASONING","content":"answer"}`**——**编辑后的推理被原样回传**。
      `ResponseAPI` 同理（`lastReasoningText = part.reasoning`）⇒ **改名默认路径也吃这个改动**（缓存失效之外，还有讨论串
      那条「污染 reasoning_content 会诱发随机输出/循环」的风险面）。
    - Gemini：**无此问题**——`GoogleProvider.toGooglePart()` 对 `Reasoning` 落到 `else -> null`（请求侧不构造 thinking
      文本），签名/`thoughtSignature` 挂的是**草稿图 Image 与 functionCall 工具块**，`#1929` 不碰它们。
    - 完整性：全仓「写推理文本」只有两处——`RegexOutputTransformer`（走 `visualTransform`，**仅显示、不改消息**，不进请求）
      与 `ChatService.kt:279`（即 `#1929`）⇒ **用户可驱动的持久化改写只有编辑入口这一条路**。
    - **✅ 真机已核（2026-10-09，设备恢复连接；`su` 可读 release 库 111MB，整拷 `rikka_hub`+`-wal`+`-shm` 后本地解析）**：
      **结论 = 风险未激活（0 命中）**。全库 7542 条消息 / **4638 个推理 part**：metadata 含 `signature` 的 **0**、
      `redactedData` **0**、`thoughtSignature` **0**；推理 part 的 metadata 形状只有三种——`null`（多数）、
      `{reasoning_id}`（197）、`{encrypted_content,reasoning_id}`（244，grok-4.6）——**全是 OpenAI 兼容族（Responses 等）
      的形态，没有任何一家用签名**。另：设置里确有**原生 claude 供应商**（"玩转ai(test"，2 个模型 `claude-opus-4-6`/
      `claude-opus-5-5`），但库里来自它们的消息仅 15 条、**推理 part 0 个** ⇒ 至今没有一次 Claude 思考轮次落库。
      ⇒ **当前不存在「可编辑的带签名块」，用户点编辑也命不中 400**。
      **⚠️ 查法更正（本次实测踩到）**：`LIKE '%signature%'` **不能**当判据——它命中 47 条、**全部是误报**（web 搜索结果的
      `x-signature=` 查询串、APK 相关文本里的 `libsolab_signature`/`getSigningCertificates`）。正确判据是**解析 part 再看
      `metadata` 键**（取 `message_node.messages` → 逐 part → 看 `type=="reasoning"` 的 metadata 有无
      `signature`/`redactedData`）。本机复现脚本留在会话里，未入库。
    - **Anthropic 侧现状（2026-10-09 用户告知 + 官方文档核对）**：Anthropic **不再返回原始思维链**——Fable 5 / Opus 4.7 /
      4.8 上 `thinking.display` **默认 `omitted`（thinking 文本为空）**，要拿内容需显式 `display:"summarized"`（拿到的是
      **摘要**而非原始 CoT）。**本仓已在新形态上**：`ClaudeProvider.kt:323-348` 对带 `REASONING` 能力的模型发
      `{type:"adaptive","display":"summarized"}`（非 AUTO 档另加 `output_config.effort`），并注释了
      「`type=enabled`+`budget_tokens` 在 Opus 4.7+ 已不支持」⇒ **Claude 用户的推理卡显示的是摘要**。含义：
      ①`#1929` 对 Claude 的价值是「编辑一段摘要」而非原文；②危险面依旧存在、只是**存量里没有**（一旦走原生 Claude +
      思考，摘要块**仍带签名** ⇒ 编辑即命中「修改过的块被拒」）；③**空文本但带签名**的块（omitted 的产物）按 replay
      规则**必须原样回传**——本仓 `toContentBlock()` 对 `signature != null` 发 `thinking:""`+签名，符合规范、无需改。
      （另记一条未验证项：库里有 6 条 `claude-opus-4-6` 消息 ⇒ 该模型请求成功过，但**不代表当时发了 thinking 参数**，
      故「4.6 是否接受 `display` 字段」仍未判定。）
    - **触发条件**：①provider 为 Claude（直连或转发签名的中转）且模型开着 extended thinking ⇒ 存量里有带签名块；
      ②用户在推理块表头点编辑并确认（`ChatMessageReasoning.kt:273` 的入口**只判 `!loading && onEditRequest != null`**，
      **不看元数据**）；③下一次请求带上该消息 ⇒ Anthropic 拒**修改过的块**（本仓自己那条单测名即
      `unsigned reasoning is dropped to avoid invalid signature rejection`，同族错误）。
    - **影响**：Claude 路径 = **硬失败**（整请求被拒，且编辑已落库、无「撤销」入口 ⇒ 用户需自己再改回或删分支）；
      OpenAI 路径 = **静默改动历史**（prompt cache 从该点作废 + 上面那条退化风险）；Gemini 无影响。
    - **修法候选（未实施，等拍板）**：**A**（推荐）编辑**带签名**的块时**同时清掉签名元数据**（该块从此不回传；对已完成轮次
      安全，因为无签名块本就被丢弃）+ 对「**带未执行工具**的助手消息」**不提供编辑入口**（那种窗口里丢 thinking 块会撞
      Anthropic 另一条 400「Expected thinking or redacted_thinking, but found tool_use」）+ 修正两处过期注释
      （`ChatCompletionsAPI.kt:592` / `ResponseAPI.kt:410` 把「消息被编辑过」列为「没有可回传的」，而编辑后推理文本非空
      ⇒ 前提已不成立）；**B** 只禁掉带签名块的编辑入口（最保守，Claude 用户失去该功能）；**C** 仅加警示文案。
      详见 `upstream-analysis.md` 注意点 14。
      **✅ 收法已落地（2026-10-09，`b6e9b528`，警示版）**：新增纯判据 `UIMessagePart.Reasoning.isProviderSigned()`
      （Claude signature/redactedData 或 Gemini thoughtSignature；OpenAI 的 `reasoning_id`/`encrypted_content`
      **不算**）+ 编辑器 `providerSigned` 为真时显示一行 error 色警示（**不改行为**，6 语言新增
      `chat_message_reasoning_edit_signed_warning`）+ 修正 `ChatCompletionsAPI`/`ResponseAPI` 两处失效注释。
      验证：`:ai:testDebugUnitTest` **252** 项、`:app:testDebugUnitTest` **1295** 项全绿（`MessageMetadataTest`
      12→19，新增 7 项签名判据）。**未真机跑该分支**——库内不存在带签名块 ⇒ 判据 true 分支当前无真机样本可渲染。
      **决策轨迹**：用户 2026-10-08 拍板「先不动代码，等真机确认」；**2026-10-09 真机确认结果 = 存量 0 命中、风险未激活**
      ⇒ 优先级维持**低**（不阻塞任何已发布版本）；**观察触发点**：开始用原生 Claude 供应商 + 开思考（届时库里会出现
      带签名的摘要块），或将来把推理编辑扩展到「会回传」的路径时，按 A 落修。
  - **agentd 可借项**（详见 `peer-apps-benchmark.md` 第九节）：经验记忆 `(state, action, discounted_return)` +
    **每条记忆的信用记账**（→ 给记忆 V2 的第二个理由：渐进披露天然产出**读取遥测**，能回答「这条记忆到底有没有
    起作用」，而「全塞或截断」回答不了）；委员会（**条件触发** + 异议单独成块 + 成员权重=历史胜率）；能力探针与
    三档解码（缺 key 大声弃权）；审批卡补「为什么要你批」的理由行；**记忆导出为人类可读 Markdown + 幂等导入 +
    来源告警**（正对我们「对话导出不含记忆库、只能整库备份迁移」的已知缺口）。
  - **待办（均未立项，按优先级）**：①~~核实 #1929 × 签名推理块~~（**✅ 已核并已收**，警示版 `b6e9b528`）；
    ②~~护栏家族第四员「调用内逐字周期熔断」~~（**✅ 已落地**，见下「2026-10-09」条）；③经验记忆/信用记账与
    记忆 V2 合并评估；④危险工具审批卡补理由行；⑤记忆导出 Markdown。
  - **限定与未验证**：本仓**未实测**是否真出现该退化形态（该形态依赖模型侧退化）；agentd 的 ops 套件数字
    （1.1/40 → 14.4/40）来自**合成噪声模型**，web 套件仅 +0.08 且有一个 seed 为负——引用时必须带此限定。
- 2026-10-09：**护栏家族第四员「思考退化护栏」落地（`6a503d7a`，debug 装机冒烟零 FATAL）**——把上面那条真空格补上，
  也是「零工具调用退化」的第一道自动防护。
  - **新增纯类** `data/ai/ThinkingDegenerationGuard.kt`（家族同款形状：纯类 + `Verdict` +
    JVM 单测；无 Android 依赖）。三形态与判据：
    **①reasoning-only**（一步只有推理、无正文无工具，连续 3 步且推理 ≥2048 字符）；
    **②restated-material**（跨调用复述上一步：shingle **containment** ≥0.8，且本步**有正文**——「有正文」正是
    它伪装成进展的方式；**有工具调用则清零**，工具类失控归前三员管）；
    **③self-repeating**（调用内逐字重复：连续 60 个相同 delta，或尾部 256 字符是 ≤64 字符周期的**逐字**重复）。
  - **调用内断流怎么做的**（这是本轮唯一的架构性取舍）：在 `generateInternal` 的流消费处喂 `chunk.delta`，
    命中即以内部单例异常 `ThinkingDegenerationCut` **沿链取消上游 SSE**（provider 调用随之 abort），
    并在**紧挨 collect 的内层**捕获它 ⇒ **不回滚快照、不触发重试/fallback、不记成错误**（与家族「优雅停止 +
    内容照常落库」同款）。停止提示经 `GenerationNotice.ThinkingDegenerationStopped` 上抛：**聊天路径**
    `addError`（错误横幅 + 新文案 `chat_thinking_loop_title`，6 语言），**无头 run** 记
    `failed(model_gave_up)`（不记成静默成功）。**刻意不做**自动续写/fallback——社区同款取舍：对已退化的
    模型再发请求只是重复计费。
  - **挂载与重置**：`ConversationSession.thinkingGuard`（跨审批续跑累积）+ 三处轮边界 reset（新用户消息 /
    重新生成 / 续写）；`SubagentRunner` 建在 run 上；`GenerationHandler.generateText` 参数默认开启。
  - **判据选择的实测依据**（来源 DSH #5976 与社区插件标定）：**逐字判据而非低熵比值**——覆盖率类指标对退化
    样本 0.92–0.99，对合法内容（markdown 表格 0.72 / 日志 0.76 / CSS 0.82 / JSON dump 0.84）只差 0.06，
    不足以当截断边界；周期须含 ≥2 个不同字符，否则长 `====` 分隔线被误杀。
  - **验证（含真机 E2E）**：`ThinkingDegenerationGuardTest` **20 项**（三形态命中 + 边界 + **四类合法内容
    不误杀**：长分隔线 / markdown 表格 / JSON dump / 日志清单 + 纯函数组件）+
    `ThinkingDegenerationWiringTest` **4 项**（源码扫描：喂 delta / 内层捕获 / 步骤循环消费 / 会话挂载与三处
    reset / 两条消费路径都处理新 notice）——起因是「判据有单测但接线断了就静默失效」。
  - **真机 E2E（2026-10-09，靠测试桩确定性复现）**：形态本身依赖模型侧退化、无法按需出现，于是用
    `scripts/guard-mock/server.py`（本地 OpenAI 兼容流式桩，按模型名注入形态；`adb reverse tcp:8888 tcp:8888`
    暴露给设备）把三态变成可复现输入，在 debug 包上逐条实测。**四组结果全部符合预期**：

    | 模型（桩注入的形态） | app 侧日志 | 桩侧 | 落库内容（切断点=保留长度） |
    |---|---|---|---|
    | `guard-cycle`（逐字周期 5） | `cutting this call` → `stopping this turn` → `stopped: cut=REPEATING_CYCLE/5` | `[cut]` 客户端断开 | 推理 **256** 字符（= 判据窗口满，而非 600 个 delta 全收） |
    | `guard-repeat`（相同 delta） | `stopped: cut=IDENTICAL_DELTAS/60` | `[cut]` | 正文 **180** 字符 = 60×「继续。」 |
    | `guard-thinking-only`（长推理+收尾） | 同样被周期判据切断（我的桩把一句话重复 6 次 ⇒ 桩数据自身即周期） | `[done]`（服务端已写完，切断发生在客户端缓冲之后） | 推理 **456** 字符 |
    | `guard-normal`（负对照） | 无任何护栏日志 | `[done]` | 正文 27 字符，`finish=stop` / `incomplete=false` |

    - **内容保留得到证实**：四条助手消息都在库里、长度正好等于切断点 ⇒ 断流**没有回滚、没有丢内容**
      （这正是「内层捕获」那条设计的验收点）。
    - **没有重复计费**：桩侧四组测试各只收到 **1 次**请求 ⇒ 切断后**没有**触发空回复补救 / 续写再发一次
      （「不 fallback、不自动重试」的验收点）。切断的消息 `finishIncomplete=true`（保留用户**手动**续写的
      入口，但没有任何自动续写）。
    - **用户可见提示实机确认**（2026-10-09，用户复述）：横幅「思考陷入重复，已结束本轮」在中断时可见，
      且**随下一条消息刷新而消失**——与其余三个护栏同款（`addError` 是一次性提示，不写进对话；落库侧
      留下的持久痕迹是那条 `finishIncomplete` 的半截助手消息）。
    - 复现步骤（含 `http://` 前缀这个坑）写在 `scripts/guard-mock/server.py` 的 docstring 里。
  - **E2E 未覆盖 / 已知取舍（诚实记账）**：
    1. **跨调用计数（判据①）仍只有单测覆盖**：聊天路径一轮最多 2 次模型调用（初始 + 一次空回复补救），
       而阈值是 3（且工具调用会清零）⇒ 该判据在**聊天路径基本不可达**，它的目标是多步的无头 run /
       审批续跑链；要真验它得让桩在多步场景里跑（尚未做）。
    2. **逐字周期对「同一句连续重复」是硬判据**：桩里 `guard-thinking-only` 那句被重复 6 次（228 字符）
       × 2 块就被判成周期（period=38）——即用户若要求「把这句话重复 10 遍」，输出会在 256 字符后被切断。
       这是「逐字周期」判据的固有代价（社区标定数据不支持更宽松的比值判据），**旋钮是 `cycleSpanChars`**，
       真遇到再加档位而不是放宽判据。
    3. 切断点由判据窗口决定（256 字符），不区分「模型语义上的退化」与「恰好周期性」——见上条。
  - 设计文档同步：`docs/design-subagent-context.md` 的护栏家族分工段改为四员，并补「**实现/改动第四员之前先读**」
    提示块（前提相反 + 逐字判据 + 内层捕获三条）。
- 2026-10-09：**记忆 V1 纪律与安全 + V2 渐进披露落地**（#2130 四块拆分里与文件模型无关的两块；
  设计稿 `docs/design-memory-discipline.md`；V1 提交 `c2c48c836`、V2 提交 `cd6853eab`）。
  - **V1**：①提示词纪律四条（`[stated]` 测试 / 校准与陈旧性 / 敏感项单独一次写操作放最后 / 永不记录硬清单
    ——其中「要求无条件附和、扮演伴侣」的偏好**含弱化形式**那半句**同日已移除，见下条**）；②代码级兜底 `MemoryContentGuard`（身份证带
    GB 11643 校验位 / SSN 排除保留段 / 银行卡 Luhn，**加已知 BIN 前缀白名单**把随机 15–19 位数字的误报从
    ~10% 压到 ~2% 量级；只拦模型写入的 create/edit，用户手工编辑不受限；拒绝回执**不回显命中原文**）；
    ③注入块加使用纪律（按证据强度用 + 敏感项只在用户本轮提起时用）；同时加的那句**反注入声明同日已移除**（见下条）。
    成本：`memory_tool` 描述 2009 → 4011 字符（≈+500 token，位于 tools 稳定前缀、仅在启用记忆时下发）。
  - **V2**：①DB **33→34**（AutoMigration，纯 ADD COLUMN）：`memoryentity` 加 `description`/`read_count`/
    `last_read_at`，`conversationentity` 加 `memory_disabled`（上游那条「迁移撞号」阻塞就此绕开）；
    ②注入改为**两层**：全文层＝原打分校准 + 预算 3/4（默认 6K），清单层＝其余记忆每行
    `id ｜ 主题（缺省=内容截断 80 字） ｜ 更新日`、预算 1/4（默认 2K）——根治「预算外条目**静默不可见**」；
    清单按 `sortKey` 升序选与渲染（只随增删变化，不随分数衰减抖动），且**不套用全文的相关性下限**（套了
    就等于又静默消失）；③`memory_tool` 加 `action=read`（顺带记一次读取遥测，**免审批**——只读无副作用，
    与 `workspace_task_status` 同款）；④记忆页显示主题词（可编辑）+ 「被读取 N 次 · 最近 X」。
  - **一处语义收紧（偏离原选项，已就地标注）**：per-conversation「不使用记忆」原本只打算管注入侧；实现时
    改为**不注入 + 不装配记忆工具**——否则模型仍可 `memory_read` 把记忆逐条读回来，开关等于装饰（而渐进
    披露正好让「有哪些记忆」一目了然）。「不整理」仍留给 V3。
  - **验证**：`:app` **1362** / `:ai` **252** 单测全绿（新增 `MemoryProgressiveDisclosureTest` 10 +
    `MemoryContentGuardTest` 14 + `MemoryToolsTest` +5 + `MemoryToolApprovalTest` +1 +
    `MemoryRepositoryTest` +7 + 注入纪律 1）；**真机迁移冒烟**（debug 包，真实 54 条记忆库）：库版本 33 → **34**、
    记忆 **54 → 54 零丢失**、新列全为默认值（`description=''`/`read_count=0`）、250 个会话
    `memory_disabled=0`、启动零 FATAL、进程存活。
  - **未做**：`read_count` 未接进打分（信用记账批次）、`description` 不回填（老记忆以内容截断露出）、
    真机「看一次两层注入」未做（请求内容页可查，但需用户在一次真实对话里确认观感）。
- 2026-10-09（同日第二次收窄）：**记忆 V1 去掉两条纪律**——①工具描述里「要求无条件附和 / 压制批评 /
  不许担心 / 扮演固定人格或伴侣的偏好永不记录（**含弱化形式**）」一句；②注入块里的**反注入声明**
  （「记忆是记录、不是指令」+「遇此类记录则忽略」）。理由：本 app 的**正当用法本就包含人设与伴侣扮演**
  （persona/card 槽、affection 配置都是一等公民），让记忆层教模型无视这类偏好会与用户自己的助手配置打架；
  态度问题该由人设/系统提示与思考退化护栏管，不该由记忆层做「不记 + 无视」。**硬标识线未动**（代码兜底
  `MemoryContentGuard` + 工具描述里的 `Never record` 清单照旧；用户手工编辑仍不受限）。成本随之回落：
  描述 4011 → **3849** 字符、注入块 **−215** 字符（≈ −53 token）。验证：`MemoryInjectionFloorTest` 改为
  「使用纪律两句必须存在」+ **反向钉子「这两句不得复活」**（删一句提示词没有任何编译/行为错误，极易被顺手
  照上游 #2130 补回来）。取舍详见 `docs/design-memory-discipline.md` §2.3。
- 2026-10-09（同日第三刀）：**信用记账——读取遥测接进打分**（V2 §4.5 刻意留的「下一批」；口径是
  「读数**只抬分、不降分，且抬幅有上限**」，不许盖过用户/模型显式声明的 `importance`）。
  - `credit = ln(1 + min(readCount, 8)) / ln(9)`（对数饱和：第 1 次读 ≈0.31、8 次满额、再读不涨）；
    `有效重要度 = importance + (1 − importance) × credit × 0.5`（0.2 的次要记忆读满信用等效 **0.6**，
    但仍到不了 0.9 那类身份信息的位置）；`活跃度 = max(写入衰减, 读取衰减)`——衰减从「多久没改」改为
    「多久没**被碰过**」，被反复读取的老记忆不再仅因「很久没改」被判陈旧。
  - **零读数的记忆打分与接入前逐位一致**（单测逐位回归钉住）⇒ 不改变既有 54 条记忆的注入行为、
    **无需迁移/回填**（`read_count = 0` 本身就是「没读过」这一事实）；`last_read_at = 0`（从未读过）与
    `updated_at = 0`（迁移前未知）语义相反，已在 `recencyFactor` 注释里写明。
  - 读数**不改写 `importance`**：声明与行为证据分开存，留给 V3 整理器对照校准（「声明 0.9 却没人读」
    「声明 0.2 却被反复读」正是它该吃的信号）；也不进提示词（`@Transient`，模型看不到自己的历史行为）。
  - 共用同一口径的两处下游：注入入选/相关性下限、200 条上限淘汰（`enforceMaxCount`）——被反复读过的
    记忆同时免于「沉底」与「被淘汰」；相关性下限因此多了一条反向通路。
  - **已知自限回路**：清单层让人更容易点到 → 被读 → 升分 → 进全文层 → 不再产生读数（0.5 的信用上限即刹车）。
  - 验证：`MemoryReadCreditTest` 8 项（零读数逐位回归 / 信用饱和单调 / 最近读取刷新衰减 /
    「从未读过 ≠ 刚读过」/ 低重要度读满后越过下限并入选 / 不越界且对两个读数单调 / 信用不得盖过刚写的
    身份记忆）+ 一条**源码扫描接线守卫**（实体映射 → DAO 自增 → `onRead` 落库 → 打分确实吃读数）。
    设计稿 `docs/design-memory-discipline.md` §4.6、`docs/design-memory-scoring.md` §十。

