# 项目规划文档（Roadmap）

> 记录 RikkaHub Plus 的后续规划方向。**当前状态：均为调研/规划，暂未实现。**
> 每个条目标注价值、现状基础、可行性，作为未来实现的依据。

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

#### 记忆系统增强（HNSW 语义检索）

- **橘瓣实现**：HNSW 向量索引 + 记忆银行 + 每日总结（自动生成生活总结）
- **我们的现状**：RikkaHub 有 assistant 记忆功能（基于消息），但无语义向量检索
- **价值**：记忆从"关键词/消息"升级为"语义检索"
- **注意**：橘瓣的 Supabase 云同步部分依赖第三方云，与隐私导向冲突，**仅借鉴本地索引部分**（如需要可本地跑 HNSW，项目 Rust 引擎 tantivy 已含向量能力）

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
| P1 | 三色定制（背景/气泡/重点色映射） | 毛玻璃已实现（输入栏/列表/顶栏三开关），仅剩三色映射定制 |
| P2 | 轻量个性化（输入框背景/字体包/头像框） | 气泡透明度已有，其余小改动零风险 |
| P3 | 记忆语义检索（本地 HNSW） | 价值中等，工程较大 |
| — | QuickJS 插件系统 | **明确不做**（已有 eval_javascript / MCP / Skill / Workspace 四类机制覆盖） |
| — | 生活感知/健康/云同步 | **明确不做** |

**状态（2026-08-16）**：QuickJS 插件系统**确定不做**（已有 eval_javascript/MCP/Skill/Workspace 覆盖）；**毛玻璃已实现**（v2.4.10.5.2 三开关：输入栏/列表/顶栏独立），三色映射定制仍待；**MCP mTLS 已实现**（v2.4.9）；**动态添加 Skill 已实现**（v2.4.9）；**提供商多 key 已落地**（2026-09-09，见三.5）。详见「八、状态记录」。

---

## 二、对话分享（导出）功能规划

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

- 2026-08-12：方案已设计（列表页 + 聊天内双入口、HTML/Markdown/图片三形态），已批准计划存档。**暂不实现**。

---

## 三、搜索服务多 key 故障转移（功能规划）

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

- 2026-08-12：桌面版实现已调研确认可参考，思路与规划一致，两端分隔符兼容已验证。**暂不实现**。
- 2026-09-06：明确本节是「三.5 提供商多 key」的**低风险试验田**——先在搜索侧跑通 key 错误分类 + 转移 + 逐 key 测试三件套（非流式短请求），提供商侧复用同一套判定基础设施。借鉴关系详见三.5。

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
- 2026-09-09：**全部落地并真机验证**。提交链：`2726b287`（数据模型 ApiKeyEntry + effectiveApiKeys）→ `03131ffa`（401/403 换 key）→ `c988652c`（每 key 一框编辑器）→ `68195b91`（fallback 模型链）→ `a040f947`（全局默认备用，助手 > 全局两级）→ `7c7e7a3f`（429 换 key）→ `e9d32dee`（顺序优先级 + 拖拽排序，default() 从 LRU 改 Order）→ `e902f630`（Gemini schema 补 items）→ `e0986cb2`（fallback 悬空回退全局 + 有 fallback 压缩重试预算为 2）→ `37e28306`（全局备用持久化三件套——a040f947 漏接线是四轮排查最终根因）→ `d45e2d0c`（换 key 实际轮转修复：顺序模式首个 key 恒定导致假切换 + 无上界死循环；改为 action 接收 providerSetting + 瞬态副本轮转，每 key 至多试一次）→ `01581e5c`（SSE 流内错误文本兜底：额度耗尽可换 key 不等待、限流可换可等待，覆盖中转站 200+流内 error 无状态码形态）。**验证**：fallback 链真机生效（悬空→全局→0.3s 切换）；换 key 真机生效（真实 `inference exceeds tpm/rpm limit` → `key error, switching key (1 key(s) left)` → 第二 key 成功；双 key 皆限流时等待重试恢复）。搜索侧（三）的结构化多 key 仍待做。

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
| **#857** | 请求恢复 HTTP 代理（15 评，上游作者拒绝） | **已由本 fork 实现**（imperfect-customization-and-refinement）；社区提议「全局代理 + 开关」轻量形态 = 代理功能可选增强方向 |
| **#1644**（open） | OCR 识图带 `reasoning_effort="none"` → 400（MIMO2.5/OpenCodeGo） | 跟踪；当前 OCR 路径若用这些模型会踩 |
| **#1632**（closed PR） | 本地 ML Kit OCR 优先 + AI fallback | 见「四、本地 OCR」 |
| **#1687**（closed） | MCP 连接支持 mTLS 客户端证书 | **已实现**（v2.4.9，P12/PEM + 密码 + 设置 UI） |
| **#1812**（closed） | 差网络下重试丢弃半截回复+重复计费 | **已由本 fork 自研方案实现**（零输出照旧重试 / 半截保留草稿落库，`20d84333`；阈值可配 `partialOutputMinChars` `e8e9d4e5`），**待真机测试**；同机制缓解 #1820（切对话回复消失）。v2「保留并续写」已放弃（2026-09-06） |
| **#1083** | apiKey 管理策略 | 与「搜索多 key 故障转移」同主题 |
| **#540** | 对话分享勾选方向 bug | 对话分享实现时规避 |
| **#1069** | 压缩上下文前自动导出聊天 | 对话分享「全量导出」形态互补 |

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

> 调研市面上基于 RikkaHub 的衍生 fork，评估可吸收点。**均为调研记录，暂不实现。**

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

### 3. RikkaHub Turbo（dcs666/rikkahub-turbo，2★）

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
- 2026-08-13：深挖 rikkahub-turbo `perf/rendering-and-streaming` 分支（PROGRESS.md），记录 R3.2 块级流式渲染 / R2.1 折叠调优 / setJob identity 修复 / #57 Response API 同根因复现 / 增量发送全禁用结论 / 80+ bug 修复清单。**已移植 setJob identity 修复**（ConversationSession.kt，对应 c897647）；R3.2 块级流式渲染为卡顿解药方向，**待实现**。
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
- 2026-09-13：**design-auto-compaction 分批落地（三个提交，装机待真机）**——**① 已使用上下文统计（`c9c6d8ea`，Phase 0.5 可见先行项）**：输入框操作行细进度条+百分比，80% 变红警告，点击弹「已用 X / 窗口 Y」；`Model` 加 `contextLength`（可空零迁移，256K 兜底），`utils/ContextLength.kt` = parseContextLengthInput/formatContextLength/effectiveContextLength/estimateWindowTokens（usage 优先+本地估算兜底）/estimateTokenCount（CJK 1.5 字符/token+工具输出）；单测 10 项。**② 发送文件工具（`5b0d6ab8`，附录 C.1）**：`workspace_present_file`——校验文件存在后输出 path/name/sizeBytes/note JSON（不返回内容），复用 `UIMessagePart.Tool`+ToolUIRegistry 分派（零新 part 类型），PresentFileToolUI 渲染文件卡片（打开跳 WorkspaceFileEditor/导出/分享），`/workspace` 前缀→FILES 区其余→LINUX 区；ToolUIContext 加 workspaceId。**③ 侧滑抽屉文件面板两子集（`bde664ad`，附录 C.2）**：`WorkspaceRepository.searchFilesByName`（BFS+limit 80）+ ChatDrawerVM 目录级 filesCache/状态；ChatDrawer 助手绑工作区时显示「会话/文件」SegmentedButton 切换，文件面板=搜索框+路径行+轻量列表（目录进入/文件跳详情）。**明确不做**：`ChatMessageNerdLine` 图标化精简。全部单测绿+编译通过，debug 已装机。
- 2026-09-13：**上下文进度条细化 + contextLength 两级自定义（`67ed90c4`/`50f2d741`，真机通过）**——①进度条从 ModelSelector 前移到**思考强度（ReasoningButton）右侧**；②**两级自定义**：`Model.customContextLength`（per-model 手填，@Serializable 零迁移）> `Model.contextLength`（拉取/内置）> `Settings.contextLengthOverride`（全局默认，PreferencesStore 三件套）> 256K 兜底；模型编辑页基本设置加「上下文窗口」输入（支持 256k/1M、placeholder 显示内置值或「跟随全局默认」），设置→模型设置页加「默认上下文窗口」全局项；`effectiveContextLength(globalOverride)` 带参；单测补优先级用例（9 项全绿）。③**修 IME 遮挡（`50f2d741`）**：ModelSettingsPage 的 LazyColumn 与模型编辑 sheet 内容列补 `imePadding()`（全局默认项是列表最后一项最易被键盘盖住；沿用文件内已有先例）。
- 2026-09-13：**压缩检查点数据模型 + 摘要提示词结构化（`f58f3888`，Phase 1 前置，DB 迁移真机验证）**——**① 检查点数据模型**（独立表方案，design §4.4）：`compression_checkpoint` 表（conversation_id 外键 CASCADE + content/message_count/boundary_node_id/created_at，id 自增，末行=active）+ DAO（insert/getOfConversation/observeOfConversation/deleteOfConversation/**trimToLatest** 合并后清冗余）+ DB **25→26 AutoMigration** + `CompressionSummary` 领域模型 + `Conversation.compressionSummaries`（默认空，**不持久化到 conversations 表**——独立表管理，整对象保存不会误删检查点）+ repository 四个读写方法 + 主加载路径填充。**② 摘要提示词结构化**：`DEFAULT_COMPRESS_PROMPT`自由文本 7 条 → **结构化 8 段**（Primary Request/Key Technical Concepts/Files and Code/Errors and Fixes/Pending Jobs/Current Work/Next Step/Critical Context，强制保留精确路径/命令/错误串/标识符——旧版会丢这些）+ 检查点前导语与文本构造（请求期组装用：「当背景、不复述、不承认」，不落库为消息）；单测 7 项。**真机验证**：设备启动后 `user_version` 25→26、表与索引建成、**215 会话/5112 节点数据完好**、checkpoint 表为空（尚无压缩，预期）。**压缩执行**（生成摘要/检查点替换发送窗口/UI 折叠行）为 Phase 1 后续。
- 2026-09-13：**Phase 1 主体分四批落地（`97779543`/`d6ce22c2`/`c14c67b3`/`b782419a`，装机待真机验证）**——**批次1 会话层窗口**：`Conversation.activeCompression`（末行 active，边界节点消失即失效）/`requestWindowMessages`（检查点合成消息 + 边界后原文，isSynthetic 不落库）/`windowNodes`，轨迹只追加；**批次2 发送侧**：ChatService 非重生成路径改走 `requestWindowMessages()`（重生成历史保持原始轨迹——压缩不改变对历史的重新生成语义）；GenerationHandler 将检查点固定在窗口最前**且不参与 limitContext 截断**（limitContext 保留尾部会截掉开头的检查点）+ 抽 `partitionCheckpointMessages`（判据 isSynthetic+USER+前导语；已核实 TemplateTransformer 对 isSynthetic 跳过）；**批次3 压缩执行**：`CompressionPlanner`（token 驱动保留策略，**RETAIN_RATIO 取 0.4 而非 harness 的 0.16**——后者是编码 agent 值，会让聊天内容被过度摘要；含防误改守卫单测）+ `ChatService.compressToCheckpoint`（切分→分块并行摘要→prior 合并重写→轨迹只追加落库→trimToLatest 清冗余→更新内存态）；**批次4 UI**：折叠行 `CompactionCheckpointRow`，**展开/收起切换 items 列表**（收起=windowNodes / 展开=全量）——复用同一渲染块，历史消息的编辑/重新生成/删除/分支/收藏/翻译/分享在展开态**全部可用**（修正初版「只读预览」的设计错误，压缩不该剥夺用户对历史的操作能力）；**一致性闭环** `isWithinCompressionBoundary` + `invalidateCheckpointIfWithinBoundary`（编辑/删除边界内消息→摘要过时→删检查点回退全量）；ChatVM.handleCompressContext 改走新方法。单测共 18 项（会话窗口 6+3、保留策略 9、提示词 10）。**已知限制**：重新生成边界内消息未触发失效（影响较微妙，且重生成走完整轨迹）；自动触发（阈值+失败静默）与设置项（阈值/保留比/目标 token 可配）仍待做——当前为手动入口触发。
- 2026-09-13：**手动压缩入口对齐检查点式实现（`b9e99c91`，装机待验证）**——用户反馈「手动压缩入口仍是旧版」：后端已切 `compressToCheckpoint`，但对话框 UI 未同步。修复：①「保留最近消息数」输入框在检查点式压缩中**已不参与切分**（生效的是 token 驱动保留比例）→ 改造成「保留比例」选择器（20/30/40/50/60%，默认 40%），消除误导性 UI，`compressToCheckpoint` 加 `retainRatio` 参数（0.05~0.9 钳制）；②**文案修正**：旧警告「压缩将重置当前对话中的所有消息」在「轨迹只追加 + 可展开回看」实现下已不成立 → 改为「较早消息从模型上下文摘要化、完整历史保留可展开、编辑/重生成可取消」（6 语言，注意 Android 资源里英文撇号 `model's` 会触发 unicode escape 错误，需避开）；③返回值改 `Result<CompressionSummary?>` 区分「无可压缩内容」（明确提示，避免用户以为点击无效）与「失败」（addError）；④删除死代码 `compressConversation`（约 85 行，无调用方，保留通用工具 `mergeCompressedConversation`）。
- 2026-09-13：**Phase 2 自动触发 + 设置项（`c89c8b65`，装机待验证）**——①设置三件套：`autoCompressEnabled`（**默认关**，保持发送行为可预期）/`autoCompressThresholdPercent`（80）/`autoCompressRetainPercent`（40）/`autoCompressTargetTokens`（2000）；②`shouldAutoCompress`（`>=` 阈值触发、Long 中间量防溢出、阈值钳制 **50~95** 防抖动与永不触发）；③`ChatService.maybeAutoCompress`：插在 `checkInvalidMessages` 之后、按发送窗口组装之前，占用估算复用 `estimateWindowTokens`（与 UI 进度条同源），**失败静默**（`limitContext` 仍作同步兜底），**重生成历史时跳过**（走完整轨迹）；④设置页自动压缩卡片（开关 + 阈值/保留比/目标 token 选择器，仅开启时展开）；单测 +5（达阈值/无窗口信息/钳制/大数不溢出/默认值）。**Phase 1+2 主体至此完成**；仅保留已知限制：重新生成边界内消息未触发失效。
- 2026-09-13：**修复检查点合成消息泄漏 + 自愈受损节点候选（`ce311156`，真机截图验证）**——用户报「展开压缩历史只多出一条消息、另有一条无论是否展开都重复出现」。**根因**：ChatService 曾把「发送窗口」（= 检查点合成消息 + 边界后原文，已 drop 边界前节点）当 generateText 的 `messages` 回写基线，`updateCurrentMessages` 按索引把窗口消息**逐条追加到各节点候选末尾** → 每节点多出「后文消息的副本」、`selectIndex` 被改指向副本。**修复**：`generateText` 新增 `windowMessages` 参数（messages 恢复全量=回写基线、与 messageNodes 索引对齐；windowMessages 仅供 provider 上下文）。**自愈**：`repairCorruptedCandidates()`（同一 message id 跨节点出现=追加副本 → 只保留首个候选，追加恒在末尾故首候选必为原始消息；未污染节点不动，编辑/重生成历史得以保留；幂等）+ `filterOutCheckpointMessages()`（剔除被误持久化的检查点本身——**判据只能用文本前导语，不可依赖 `isSynthetic`：该字段 `@Transient`，持久化后丢失、DB 读回恒 false**，这是第一版自愈失败的原因）。二者在 repository 加载路径应用、随下次保存落库。**真机对比**：修复前展开显示检查点前导语文本；修复后展开显示真实历史（首条提问含 2/2 分支切换器、gpt-5.6-terra 回复、工具调用步骤、token 统计），摘要文本与重复消息消失。单测 +9（NodeCandidateRepairTest 5 + 清洗 4）。
- 2026-09-13：**压缩检查点失效改为区域指纹校验（`bbba9ba8`，B 方案，真机交叉验证）**——此前只在「编辑/删除」两条路径显式失效，**遗漏「重新生成」与「切换分支」**（且 `checkInvalidMessages` 自动清障未覆盖）→ 区域内消息换了但摘要仍是旧内容，后续轮次 AI 上下文与用户所见不一致（重生成当轮走完整轨迹所以当时正确）。**方案**：`compression_checkpoint` 加 `area_fingerprint`（可空）→ **DB 26→27**；`activeCompression()` 改为指纹校验（区域=边界含之前，各节点 id + **选中消息 id** 拼接的 SHA-256 前 32hex，不一致即失效）；压缩时写入；**删除**原两处显式失效调用与 helper（语义统一到一个判据，一次覆盖全部路径含未来新增）；窗口内改动不影响；指纹为 null（旧数据/备份恢复的 26 版数据）降级为边界存在性校验。**附带性质：失效可逆**——指纹是纯函数，切回原分支即重新生效。**真机交叉验证**：按源码同算法独立复算指纹与 DB 值完全一致；编辑区域内消息（候选 2→3）→ 复算 `391f5de7` ≠ 记录 `40930ade` → 折叠行消失（失效）；DB 行保留（运行时判定非删行，故可逆）。文案对齐：对话框/设置项改为「编辑、重新生成、删除或切换分支都会自动取消压缩」（6 语言）。单测增至 8 项。
