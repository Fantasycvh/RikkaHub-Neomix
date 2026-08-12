# 项目规划文档（Roadmap）

> 记录 RikkaHub Plus 的后续规划方向。**当前状态：均为调研/规划，暂未实现。**
> 每个条目标注价值、现状基础、可行性，作为未来实现的依据。

---

## 一、借鉴橘瓣（OrangeChat）的分析

**来源**：[橘瓣 OrangeChat](https://github.com/watermio123-droid/orangechat)（watermio123-droid/orangechat）——另一个基于 RikkaHub 深度定制的 Android AI 客户端，走"AI 伴侣"路线（插件生态 + 生活感知 + 健康数据 + 记忆 + 主动消息）。还有 `liquid-glass-ui` 分支（液态玻璃主题）。

**评估原则**：结合本项目定位（私有测试、隐私优先、LLM 客户端），分析借鉴价值。

### 高价值（建议未来实现）

#### 1. QuickJS 插件系统（核心）⭐

- **橘瓣实现**：完整插件框架 `plugin/` 目录——`PluginSandbox`（QuickJS 沙箱）、`PluginLoader`、`PluginManager`、`PluginUI`（声明式 UI + WebView）、`PluginToolProvider`（工具注册）、`PluginScanner`（发现）
- **插件形态**：`manifest.json`（声明 tools/配置/模板）+ `main.js`（`exports.xxx` 实现工具），zip 打包导入
- **沙箱内置 API**：同步 `fetch`（15s 超时）、`config`（用户配置）、`memoryBank`（语义检索/存取）、`TextEncoder/TextDecoder`、`btoa/atob`、`console`
- **高级能力**：`config` 声明式配置项（string/password/number/boolean/select）、`promptTemplate` 系统提示、`hooks`（message_sent/message_received/daily_cron 事件）、声明式 UI（Compose Material 3 渲染）、`customPageWebView`
- **我们的现状**：RikkaHub **已内置 QuickJS 引擎**（`RikkaHubApp.kt` 的 `QuickJSLoader.init()`），基础设施现成；现有扩展只有 Skill（markdown 技能）与 MCP，**没有"JS 插件 + 宿主工具注册"层**
- **价值**：用户可用 JS 脚本扩展 AI 工具，比改代码灵活；与 Skill/MCP 互补（Skill=文档技能、MCP=外部服务、插件=任意 JS 逻辑）
- **工程量**：中（沙箱封装 + manifest 解析 + 工具注册 + 管理页 UI + 内置示例插件）

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
| P1 | QuickJS 插件系统 | 核心扩展能力，基础设施现成（QuickJS 已内置） |
| P2 | 液态玻璃主题 / 三色定制 | 轻量、体验提升明显 |
| P3 | 轻量个性化（气泡/输入框/字体） | 小改动零风险 |
| P4 | 记忆语义检索（本地 HNSW） | 价值中等，工程较大 |
| — | 生活感知/健康/云同步 | **明确不做** |

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
| **#857** | 请求恢复 HTTP 代理（15 评，上游作者拒绝） | **已由本 fork 实现**（feat/restore-proxy）；社区提议「全局代理 + 开关」轻量形态 = 代理功能可选增强方向 |
| **#1644**（open） | OCR 识图带 `reasoning_effort="none"` → 400（MIMO2.5/OpenCodeGo） | 跟踪；当前 OCR 路径若用这些模型会踩 |
| **#1632**（closed PR） | 本地 ML Kit OCR 优先 + AI fallback | 见「四、本地 OCR」 |
| **#1687**（open） | MCP 连接支持 mTLS 客户端证书 | 备选；自托管 mTLS 场景，与隐私导向契合，OkHttp SSLContext 技术可行（参照代理定制模式） |
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

#1380 聊天记录同步 · #1399 Shizuku adb shell · #1031 动态添加 Skill · #826 定时任务 · #121 划词翻译 · #577 图片生成提取 URL

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

**② R3.2 块级结构化流式渲染**——流式分支从"代码块拆分"升级为"按行块分类"（代码/标题/引用/列表/段落），样式与最终渲染对齐（HeaderStyle.fromLevel / 引用左线+斜体 / "• " 与 "N. " 前缀）。**追加式扫描每帧 O(n)，已渲染块参数不变 → Compose 智能跳过重组**。JS 模拟 18 帧验证块序列稳定。→ **我们卡顿的解药方向**（非 β/γ 小优化，是渲染架构改进：流式 chunk 按块分类 + 已渲染块跳过重组）。

**③ R2.1 折叠调优**——阈值 5000、整区可点展开、**`!loading` 排除生成中消息**、USER 不折叠。生成中消息不做折叠动画 = 减少重组。

**④ MarkdownParseCache LRU 上限 30**——流式每帧解析缓存有界无泄漏。

#### 与「Response API 多工具 400」相关（我们已修 #1690）

**⑤ v1.8.5 #57 同根因独立复现**：网关按 function_call 逐个校验 reasoning——1 个 reasoning 只服务其后第 1 个 fc，同消息第 2 个 fc 起缺 reasoning → 400。方案与我们一致（`fcSinceReasoning` 计数器）。**验证了我们的修复正确性**。

**⑥ 增量发送实验（v1.8.7/8.8 → v1.9.2 全禁用）**：`previous_response_id` + store 增量（只发新增），实测**opencode.ai 网关静默丢弃 previous_response_id**（200 但失忆）→ **全量发送为唯一正确路径**。我们没做增量是对的。另修复 P0-3（store=false host 禁用增量）。

#### 其他高价值发现（可参考）
- **#56 工具消息 reasoning 兜底**（c0253dc）：thinking 开启时工具消息缺 reasoning → 400，chat/completions 补 `reasoning_content:""` + responses 补占位 item——与我们 #1690 互补的边界
- **大文件拆分**（Strangler Fig）：>800 行文件全拆 <600，ChatService 1291→746、PreferencesStore 801→519 等 33 个——长期可维护方向
- **修复 80+ bug 清单**（PROGRESS.md）：超长消息保存静默失败、压缩覆盖新消息、zip-slip 路径穿越、SettingsStore update 并发 lost update（Mutex 串行化）、webhook HMAC 恒失败、Room AutoMigration 缺口、QuickJS 原生内存泄漏、JSON 深嵌套 SOE、文件名路径逃逸等——**是上游 2.4.5 已知 bug 的完整清单，可逐条对照我们的 fork 检查**

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

---

## 八、状态记录

- 2026-08-12：完成橘瓣分析并记录本规划（借鉴价值分级 + 优先级）。**暂不实现**，待用户决定何时推进。
- 2026-08-12：补充「对话分享（导出）」方案规划（双入口 + 三形态）。**暂不实现**。
- 2026-08-12：补充「搜索服务多 key 故障转移」方案规划（含桌面版参考）。**暂不实现**。
- 2026-08-12：上游 issue 探讨（#857 代理需求确认 / OCR / MCP mTLS 等），增补「四、本地 OCR」与「五、上游 issue 关注清单」。**暂不实现**。
- 2026-08-12：记录备份互通性已知边界（原版→本 fork 通、本 fork→原版不通）+ 可选根治方向。**暂不处理**。
- 2026-08-13：调研 4 个 RikkaHub 二改版（agent/root/turbo/plus），记录可吸收点（免 key 搜索+断路器、Hardline 安全底线、技能触发词自动注入、前台保活），增补「七、RikkaHub 二改版调研」。**暂不实现**。
- 2026-08-13：深挖 rikkahub-turbo `perf/rendering-and-streaming` 分支（PROGRESS.md），记录 R3.2 块级流式渲染 / R2.1 折叠调优 / setJob identity 修复 / #57 Response API 同根因复现 / 增量发送全禁用结论 / 80+ bug 修复清单。**已移植 setJob identity 修复**（ConversationSession.kt，对应 c897647）；R3.2 块级流式渲染为卡顿解药方向，**待实现**。
