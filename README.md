<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Neomix</h1>

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**非官方**定制版 —— 原生 Android LLM 聊天客户端（多提供商 / Agent 工作区 / TikZ 渲染 / 三类记忆 / 实验性插件）

[English (上游原版 README)](README-en-origin.md)
</div>

> ⚠️ **非官方 fork**：与上游 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) **无从属关系**，上游不处理本项目的问题；
> 本项目由个人维护，代理、S3、MLTS等部分功能未经测试，**不承诺及时响应**。AGPL-3.0 许可，保留上游 `LICENSE` 与出处。
> **本仓有意不接 issue**，反馈请走 [💬 Discussions](https://github.com/Fantasycvh/RikkaHub-Neomix/discussions)（详见下方「与上游的关系 → 反馈渠道」）。
> 应用显示名 **Neomix**（仓库名 `RikkaHub-Neomix`），包名 `app.androidrik.rikkahub`。
> **发布**：正式版 **`v1.0.27`**（`1.0.27 (294)`）；预发布 **`1.0.28-beta.9`**（`1.0.28-beta.9 (311)`，2026-10-24，**插件系统标注为实验性**）。**自 `1.0.28-beta.7` 起随 release 提供预编译 APK**（仅 arm64-v8a，见下方「下载与安装」）；更早的 `1.0.28-beta.1`…`beta.6` 与 `v1.0.27` 仅 tag、无资产。
> **tag 前缀**：`v1.0.0`–`v1.0.27` 带 `v`，**自 `1.0.28-beta.1` 起不再带 `v`**。

## 📌 与上游的关系

- **基线**：上游 `2c980642`（`2.4.5` 一线）。本仓历史 = **上游真实提交（哈希未变）** + 本项目的定制提交。
- **跟进方式**：不整体合并上游；按需人工评估后**选择性吸收**（关键修复 / 低成本高价值功能），提交信息中标注来源。
  逐条评估记录与「不吸收」的理由见 [`docs/upstream-analysis.md`](docs/upstream-analysis.md)。
- **发布前重写**：本项目在私有仓迭代，公开前对**包名**与**提交身份**做了重写，因此历史短哈希与私有仓不同——
  文档中出现的旧短哈希无法在本仓定位，请用提交信息关键词检索（见 [`docs/README.md`](docs/README.md)）。
- **不回推上游**：上游明确不接受 PR，故本项目改动不回推。
- **预编译包**：**自 `1.0.28-beta.7` 起**随 release 提供 arm64-v8a APK（见下方「下载与安装」与 [Releases](https://github.com/Fantasycvh/RikkaHub-Neomix/releases)）；更早版本只有 tag，需自行构建。签名为自签密钥，**换密钥会导致无法覆盖升级**。
- **反馈渠道**：本仓**有意不接 issue**（GitHub 里 issue 功能是关的）。使用问题、功能想法、以及「上游是否也有该问题」的确认，请到
  [💬 Discussions](https://github.com/Fantasycvh/RikkaHub-Neomix/discussions) 讨论。
  **属于上游 RikkaHub 本身的 bug / 功能，请提到 [上游仓库](https://github.com/rikkahub/rikkahub/issues)**——本项目是非官方 fork，与上游无从属关系，上游也不处理本仓的问题。
- **Pull Request**：本仓**不整体合并上游、也不回推上游**，PR 只接受「**小、可验证、带测试**」的改动。
  动手前请先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)，PR 模板会自动带出自查清单。

## ✨ 本项目定制功能

在继承上游全部能力的基础上，本项目做了以下定制。

> 不在下表的两类：①**吸收自上游**的修复与功能；②**上游同样具备**、只是实现来源不同的能力。两者都归入下面「核心功能（继承上游）」。

**对话与生成**

| 功能 | 说明 |
|---|---|
| 🩹 **半截回复保留与重试分流** | 弱网/断流失败时保留已有内容为可编辑草稿（阈值可配）；按失败类型分流（仅零输出失败换 key / 重试），有 fallback 时压缩主模型重试预算以快速切换 |
| 🧠 **自动上下文压缩** | harness 式检查点压缩：轨迹只追加、可展开回看可编辑、编辑自动失效、参数可配置档 |
| 📊 **上下文容量面板** | 剩余/占用/上限 + 预计可容纳轮数 + 上下文组成明细 + 一键压缩；标题旁占用读数；输入栏指示器细条/圆环可切换 |
| 🧮 **真 tokenizer** | cl100k BPE 精确 token 计数（估算、统计、预算判定同源），后台预热 + 启发式兜底 |
| 🧷 **消息引用** | QQ 式引用（快照 + ID 双存、输入框预览卡、点击跳转、队列携带）——对应上游 issue #1906，**上游 2026-10-01 以「AI 看不到引用、本质都是文本」为由 closed as wontfix，未实现** |
| 🖼️ **会话内生图工具** | 模型可在对话里直接生图（`generate_image`），**结果渲染在工具卡里**、不进消息上下文（下一轮不会被当输入图再发一遍）；默认零审批 + 单次 ≤4 张 / 每轮 ≤2 次硬上限（失败不计「花钱次数」但计入尝试上限），助手级审批可选；没配生图模型就不注册该工具——对应上游 issue #2123，本仓自行实现 |
| 🏷️ **生成来源标注** | 每条回复末尾可选显示实际「路由 · 模型」（默认关，多中转核对/成本归因）——对应上游 issue #1805，上游尚未实现 |
| 🔍 **拼装预览与缓存诊断** | 发送前提示词逐段归因（Tier 1–4，可点注入段跳到产出条目、工具 schema 独立成段）；缓存稳定性三档分组；**前缀分叉诊断**（对比上次/本次请求，定位缓存断点并估算可命中前缀） |

**Agent / 工作区**

| 功能 | 说明 |
|---|---|
| 🤖 **子代理系统** | 独立上下文（信封隔离父对话注入）+ 对话内派发 + 升级到人审批 + 并行上限 + 上下文预算 |
| ⚙️ **工作区后台任务** | `run_background / status / kill` 三工具 + 完整输出落盘 `.tasks/` + 30 分钟时长上限 + 可见性 UI |
| 🧯 **护栏与预算** | LoopGuard 三档制（严格/标准/宽松）+ rootfs 单轮字符/步数预算 + 子代理并行上限——全部可配 |

**知识与提示词**

| 功能 | 说明 |
|---|---|
| 📚 **RAG 知识库** | 跨库向量检索工具 `search_knowledge_base`（per-KB 嵌入模型、导入即解析入库） |
| 🎭 **SillyTavern 深化** | 官方语义的世界书（多源扫描 / token 预算 / 次要关键词）、ST 预设导入、角色卡 V1 兼容、注入位置批量迁移（缓存友好）、世界书种子三重迁移 |
| 🧬 **三类记忆（画像 / 话题 / 事务）** | **借鉴**业界「按用途给记忆分型」的思路（Claude 式文件记忆的分层做法；本仓不采用其文件形态，只借「按用途分三类」这个维度），把它作为记录上的第二维度加在现有单表与四层注入框架上：`PROFILE` / `TOPIC` / `TASK` 分型，画像与话题按打分注入，事务有 open / done / dropped 生命周期；整理器按类型校准并支持 `complete` / `drop` 提案，记忆页可按类型筛选、查看容量与恢复元数据；助手级「整理时可调整类型」默认关，开启后仅凭新轮次用户证据改类，未完成事务仍不可改 |

**扩展（实验性）**

| 功能 | 说明 |
|---|---|
| 🧩 **插件系统（实验性）** | 借鉴 BiliPai 的能力声明 + 授权模型：**JSON 规则插件**（宿主规则引擎解释执行，按条件做输入/输出改写）与 **JS 单文件插件**（QuickJS 沙箱，单次调用超时、日志截断、跑完即销毁）；导入支持「链接」或「粘贴源码」，安装前逐项确认能力（网络 / 历史读取 / 会话写入 / 注册工具 / 输入改写 / 输出改写 / 插件存储），装后可在详情页撤销；JS 出站网络只走受控网关——清单声明 `networkHosts` 作正向白名单，内网/环回/保留地址一律硬拦截；插件页可启用/停用/卸载，插件可注册自己的工具调用 UI 渲染器。**实验性**：插件 API 与 manifest 字段可能变动，不保证向后兼容 |

**网络与提供商**

| 功能 | 说明 |
|---|---|
| 🔑 **提供商多 Key** | 每 key 独立输入框 + 启用开关 + 拖拽排序（列表顺序=优先级，首个=首选）；失败转移覆盖 401/403/429、余额耗尽、TPM/RPM 限流（含中转站 SSE 流内错误无状态码形态）——仅零输出失败换 key（半截保留草稿不换）；搜索侧同套结构 |
| 🛟 **备用模型链（fallback）** | 主模型整轮失败自动切备用重跑；助手级 > 全局默认两级，引用失效自动回退 |
| 🏷️ **启停与真删除** | 提供商/模型/助手/ASR/TTS 均可停用（不阻断既有使用）与真删除（悬空引用保护 + 迁移兼容） |
| 🔄 **模型拉取反馈增强** | 自动拉取模型列表、失败显示错误卡 + 重试、候选模型搜索/全选添加 |
| 🗑️ **提供商模型批量管理** | 模型列表支持多选批量删除与一键清空（含确认弹窗，保护悬空引用同单删） |

**备份与数据**

| 功能 | 说明 |
|---|---|
| 💾 **备份加固与互通** | 兼容上游原版包名（`me.rerere.`）备份自动迁移；大文件加固（STORED 采样 + S3 分片 8MB + 单遍哈希 + 空间预检/字节级进度 + 换库前完整性校验）；生成的图片纳入备份 |
| 📦 **对话导入导出** | 自家格式 zip + 附件原字节往返（导入即换新 id）+ 统一导入器 + 导出留存区（TTL 自动清理） |
| ⭐ **收藏管理增强** | 基础收藏继承上游，本项目增量：标签筛选/管理页、排序切换、双向侧滑撤销 |
| 📂 **文件管理批量操作** | 文件管理与工作区文件支持多选：批量导入（SAF 多选）/ 批量删除 / 批量重命名（自动编号预览、祖先目录折叠）；导入分块复制可取消 + 临时文件原子落盘；删除磁盘失败不误删 DB 记录；失败逐项报告。WebDAV 默认远端路径改为 `neomix_backups` |

**稳定性与安全**

| 功能 | 说明 |
|---|---|
| 📝 **日志落盘** | 请求日志可落盘 `files/logs`，敏感请求头双重脱敏 |
| 📐 **TikZ 图表渲染** | mupdf SVG 路线（`$$...$$` 内嵌 + `tikz` 围栏双触发；导出 HTML 内嵌矢量）；三层崩溃防线（尺寸校验/生成端上限/采样上限 + HeightClamp） |

**界面**

| 功能 | 说明 |
|---|---|
| 🧩 **代码块一键设为助手** | 会话内 JSON 助手卡即点即用 |
| 🔎 **设置全局搜索** | 全量设置项索引 + 独立搜索页、双入口；入口为「设置」大标题下的**正文搜索框**。匹配逻辑（含别名/关键词）抽成纯函数并单测覆盖 |
| 🧹 **移除上游营销** | 移除应用内更新检查、赞助入口/捐赠页/启动次数赞助弹窗、导出图片中的 `rikka-ai.com` 品牌水印 |

## 🧪 未在设备上验证（从上方清单移出）

以下功能**已实现**，但在本项目的开发记录里**没有真机 / 端到端验证**（或明确标注待验证），故不计入上方「定制功能」。
补验清单与逐条状态见 [`docs/handoff-2026-09-04.md`](docs/handoff-2026-09-04.md) §三.6「验证债」（**该节最后刷新 2026-10-12**）。

| 功能 | 状态 | 为什么单列 |
|---|---|---|
| 🛟 **会话加载守卫** | 已实现 | 属防护性改动，真机未复现触发场景，无验证条目 |
| 🌐 **两层代理** | 已实现 | 设计文档明确「**待用户真机验证**」（需真实 SOCKS5 / HTTP 代理） |
| 🛡️ **MCP 加固**（mTLS 客户端证书） | 已实现 | 无验证条目（连接错误三分类另有真机通过） |
| 🐕 **后台保活**（KeepAlive / WifiLock） | 已实现 | 开发记录中无验证条目 |

**部分未验证**（主体已真机验证、个别子项有缺口）：

- **子代理系统**：Phase 1 真机验证通过；**并行上限拒绝、预算耗尽优雅停仅单测**，真机待验。
- **备份加固与互通**：备份体系重构 / 空间预检已真机验证；**S3 远端同步部分未经验证**（含大文件 8MB 分片）。
- **TTS 朗读浮窗**：权限声明与非 Activity 宿主崩溃已修，**实播未测**。

## 🎨 核心功能（继承上游）

- 🎨 Material You 设计 + 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux Agent 运行环境
- 🖥️ 终端多 Tab（会话管理 + PTY 动态标题）
- 🔄 多 AI 提供商：自定义 API / URL / 模型（OpenAI、Google、Anthropic 兼容接口）
- 🖼️ 多模态输入（图片、文本、PDF、Docx）
- 🖼️ 输入区图片预览：输入框里的图片点开即可全屏查看，多图左右滑动切换，预览内可进画板在当前图上绘制（2026-10-20 吸收上游 `bc4a6f47e`）
- 🎨 图像生成（Imggen）
- 🎙️ 语音（TTS / ASR）
- ⏯️ 消息队列（生成中连发自动入队）
- 🖥️ Web 访问，多平台使用
- 🛠️ MCP 支持（含 OAuth 授权、工具 schema `$defs` 内联展开）
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 📊 图表工具（`chart_display`：折线 / 柱状 / 散点）
- ✨ ExpressivePopup（Material Expressive 动效弹层）
- 🗂️ 模型列表折叠（按供应商分组）
- 🪾 消息分支 / 🔍 搜索能力（Exa、Tavily、智谱、豆包、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量 / 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能 / 📝 AI 翻译 / 🌐 自定义 HTTP 请求头与请求体
- 📦 内置技能（skill-creator 等随包内置）
- ☁️ 备份与恢复（S3 / WebDAV / 本地）/ ⭐ 消息收藏 / 📤 分享 / 📊 统计
- 💌 SillyTavern 角色卡导入

## 🙏 借鉴与参考的应用 / 项目

本仓的功能大量吸收了同类优秀项目的做法。**只在「核实过源码或文档、并确实据其改进自己实现」时才列在这里**；
每条都注明「借鉴了什么」，评估过程与逐条结论见 [`docs/peer-apps-benchmark.md`](docs/peer-apps-benchmark.md)。
致谢以下项目及其作者（排名大致按借鉴程度）。

| 项目 | 来源 | 借鉴了什么 |
|---|---|---|
| **RikkaHub** | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | 本仓的上游基线；上游修复与功能按需**人工逐条评估后选择性吸收**（不做 ff-only 同步，改动不回推上游） |
| **Yuihub** | [xiaoyuili/Yuihub](https://github.com/xiaoyuili/Yuihub) | 借鉴最多的一家：记忆打分校准 + 字符预算、被动提取管道的组织方式、整理器（先落库再删源 / 幂等）、工具结果投影无损回环校验。**其「记忆语义检索上线一天即整套删除」也被本仓引为「不做语义检索」的独立佐证** |
| **SillyTavern** | [SillyTavern/SillyTavern](https://github.com/SillyTavern/SillyTavern) | 世界书/提示词注入与预设体系（Tier A/B 系列落地）、ST 宏运行时移植；其余按需 |
| **bigeye**（Aria-EQ） | [suanrongqieqiezi/bigeye](https://github.com/suanrongqieqiezi/bigeye) | **记忆分层注入的原型**：`_anchors` 的「身份锚点、极小配额、恒在场」是 **L1 的原型**，`recall()` 的五因子工作集（relevance 作乘数）是 **L2 的由来**——本仓把「天生恒在场 / 天生每轮变」这套分层**显式映射到注入位置**；`recall_guaranteed` 的**确定性通道 + 禁 AI 自填**被照抄为锚点的入选与提拔纪律（`MemoryRepository` / `MemoryEntity` 内注明了出处行号）；免衰减免淘汰对应其 `IMPORTANCE_PIN_THRESHOLD`。**反面教训**：其 `MAX_OBS_PER_PAGE` 配置项零引用、与注释矛盾，被本仓引为「配置项必须有测试钉住」的对照。另有一条**已落地**（2026-10-16）：其「探索/落地二分」的循环守卫 —— 补的正是本仓 `ToolCallLoopGuard` 抓不到的那个维度（「重复 ≠ 无进展」）。落成「规则提醒」：三条规则（打转自查 / 缺计划催规划 / 预算 ≥80% 催收尾）在**对话尾部**注入提醒（默认开、三档可配、不落库）。设计与取舍见 [`docs/design/design-rule-engine.md`](docs/design/design-rule-engine.md) |
| **华灯**（rikkahub-sillytavern-android） | [MiaoWuNYA/rikkahub-sillytavern-android](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android) | 工具输出「投影无损回环校验」原则、R8 序列化 keep 纪律等（另有一项 Jev 契约仍待评估） |
| **SoLab** | [MT 论坛开源帖](https://bbs.binmt.cc/forum.php?mod=viewthread&tid=171659)（基于 Kelivo 二次开发；仓库地址见该帖） | 世界书 seed 三重迁移、工具调用失控护栏（LoopGuard + forget）的理念 |
| **senera** | [yuanplussfive/senera](https://github.com/yuanplussfive/senera) | MCP 工具 schema 动态分层加载（元工具桥、内容寻址缓存）的成熟参考实现 |
| **MineChat** | [Mr060805/MineChat](https://github.com/Mr060805/MineChat) | RAG-as-tool 知识库检索移植、输入侧正则与动态宏（st 宏运行时） |
| **agentd** | [hue913/agentd](https://github.com/hue913/agentd) | 经验记忆 + 信用记账（已接进记忆打分） |
| **rikkahub-pro**（Turbo） | [dcs666/rikkahub-pro](https://github.com/dcs666/rikkahub-pro) | R3.2 块级流式渲染、`setJob` 修复；其 80+ bug 清单被本仓用作 bug 审计对照表 |
| **RikkaHub Math+** | [test27818/rikkahub-math-plus](https://github.com/test27818/rikkahub-math-plus) | TikZ 渲染管线架构（检测 → preamble 生成 → 编译 → 三层缓存）与 `standalone[border]` 裁剪留白的作法 |
| **rikkahub-agent** | [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) | Agent 化的同类探索（与上游 `3a533a6a` 一并做过终端/工具链分析） |

**仅作设计参考、未采用其实现细节的**：`opencode` / `codex` / `Cherry Studio` —— 子代理与独立上下文设计稿只借其**思路**，
其实现细节本仓**未核实、也未抄**（见 [`docs/design/design-subagent-context.md`](docs/design/design-subagent-context.md)）。
**Claude 式文件记忆（Anthropic）** 同样是只借思路的一类：三类记忆只借「按用途分型」这个**维度**，
不借其文件形态（设计口径见 [`docs/design/design-memory-kinds.md`](docs/design/design-memory-kinds.md) §0.3）。
**BiliPai** ([jay3-yy/BiliPai](https://github.com/jay3-yy/BiliPai)) 只借插件体系的**思路**：能力声明 + 授权
（声明 ∩ 用户批准、记录绑定插件 id）、「只做会真跑的东西」的取舍；本仓不做其「能解析能授权但不执行」的外部 Dex 形态
（见 [`docs/PLUGIN_SYSTEM_DESIGN_DRAFT.md`](docs/PLUGIN_SYSTEM_DESIGN_DRAFT.md)）。

> **备注**：`SoLab` 的源码在 **MT 论坛**开源（上表已给帖链接），**没有 GitHub 仓库**，故不伪造 GitHub 链接。
> 若作者愿意提供正式仓库地址，欢迎提 issue 或 PR 补上。
>
> 以上项目各有其许可与版权；本仓仅在**思路与实现参考**层面借鉴并自行重写，未直接复制其代码；对上游 RikkaHub 则保留原
> `LICENSE` 与出处（见下方 License）。

## 🧱 原生库

分两类：**Gradle 参与编译的**（`workspace` 模块，CMake + NDK）与**预编译签入的**（其余全部，手工拷进 `jniLibs/`）。

### 1. 构建时编译（不进版本库）

`workspace/src/main/cpp/`（`CMakeLists.txt` + `workspace.cpp` + `termux_pty.cpp`）→ CMake **3.22.1** + NDK 产出 `libworkspace.so` / `libtermux.so`。
这两个是唯一由 Gradle 编译的原生库；`app` 打包时对 `libtermux.so` 用 `pickFirsts` 去重。

### 2. 预编译签入（Gradle 不参与编译）

| 库 | 位置 / ABI | 体积 | 用途 | 来源与版本 | 依赖 |
|---|---|---|---|---|---|
| `libtex_poc.so` | `app/src/main/jniLibs/`，**仅 arm64-v8a** | 13.5 MB | TikZ 的 TeX 引擎（Tectonic 编译 TeX → PDF），跑在**独立进程 `:texcompile`** 里 | [`tools/tex-poc/`](tools/tex-poc/)（Rust + **Tectonic 0.17**，NDK r27c 交叉编译） | `libicuuc78` / `libgraphite2` / `libc++_shared` / `libz` |
| `libpdf2svg_bridge.so` | `app/src/main/jniLibs/`，仅 arm64 | 13 KB | 调 mupdf 的 C API 把 PDF 转 SVG（`FZ_SVG_TEXT_AS_PATH`，文字转轮廓） | `app/src/main/cpp/pdf2svg_bridge.c`（自包含 C，自带 mupdf 原型声明） | `libmupdf_java` |
| `libmupdf_java.so` | `document/src/main/jniLibs/`，arm64 + x86_64 | 9.4 / 9.8 MB | PDF 解析；TikZ 链路里被上一条调用 | Artifex **MuPDF 1.26.8**（上游 RikkaHub 引入的官方 Android SDK 产物） | `libz` / `libstdc++` / `libjnigraphics` |
| `libsimple.so` | `app/src/main/jniLibs/`，arm64 + x86_64 | 7.6 / 7.3 MB | SQLite **FTS5 `simple` 分词扩展**（消息全文搜索：中文单字 `NEAR`、英文/数字走 `simple_query`），经 Requery `customExtensions` 注册 | 上游按 [wangfenjin/simple](https://github.com/wangfenjin/simple) **v0.6.1** 的 Android 参数加 `-DSIMPLE_WITH_JIEBA=OFF` 重编（NDK 29 / android-21 / 静态 libc++）：不含 jieba 代码，`simple` 分词器与 `simple_query` 不变，**已有索引无需重建**；14MB `simple_dict` 已不再使用 | 仅系统库 |
| `libproot_exec.so` | `workspace/src/main/jniLibs/`，arm64 + x86_64 | 274 / 290 KB | 工作区沙箱的 proot 可执行体（`--link2symlink` + root-id），静态链接 | 上游静态链接二进制，**proot 5.1.107.96**，含 issue #2158 的 `AT_EXECFN` 修复（Ubuntu 26.04 类 rootfs `apt upgrade` 后 `ls`/`basename` 报 Security violation 的问题已真机对照修好） | 仅 `libc` / `liblog`（无 talloc/shmem 动态依赖） |
| `libproot_loader.so` | 同上 | 18 KB | proot 的 loader 垫片（与上一条成对使用，`PROOT_LOADER` 指向它） | 同上 | — |
| `libicuuc78.so` / `libicudata78.so` | `app/src/main/jniLibs/`，仅 arm64 | 2.2 MB / 33 MB | Tectonic 的 ICU 运行时（**ICU 78**） | Termux libicu 交叉版 | 互相依赖 |
| `libgraphite2.so` | 同上，仅 arm64 | 1.5 MB | 字体整形（Tectonic 排版） | 交叉编译版 | `libc++_shared` |
| `libc++_shared.so` | 同上，仅 arm64 | 1.8 MB | NDK C++ 运行时 | NDK 自带 | — |

打包体积（`assembleRelease` 后的实际 APK，arm64-v8a）：AGP 会对签入的 `.so` 做 **strip**，所以仓库里的大小 ≠ APK 里的大小——

| 库 | 仓库内 | APK 内（未压缩 → 压缩后） |
|---|---|---|
| `libicudata78.so` | 33.1 MB | 31.6 MB → **12.2 MB** |
| `libtex_poc.so` | 13.5 MB | 9.9 MB → 3.5 MB |
| `libmupdf_java.so` | 9.4 MB | 9.0 MB → 4.4 MB |
| `libsimple.so` | 7.6 MB | 1.5 MB → 0.5 MB |

arm64 原生库合计约 **62 MB**（未压缩），占 APK 的大头；beta.7 的 arm64 APK 为 **59.7 MiB**。
另：`libquickjs-android-wrapper.so`（1.3 MB）来自 JS 插件沙箱的 Maven 依赖 `wang.harlon.quickjs`，不属签入库。

注意事项：

- **JNI 符号名与包名绑定**（`Java_app_androidrik_rikkahub_tikz_*`）：改包名必须按新符号重编 `libtex_poc.so` / `libpdf2svg_bridge.so`，否则运行时 `UnsatisfiedLinkError`。
- 签入库**只做 arm64 交叉编译**（`libtex_poc` / `pdf2svg_bridge` / icu / graphite2 / `c++_shared` 都没有 x86_64 版本）⇒ x86_64 模拟器上 TikZ 引擎不可用，会走既有回退链（纯 Kotlin 子集渲染 → PDF 位图）。x86_64 的 `libproot_*` / `libmupdf_java` / `libsimple` 虽然在包里，但只做过 ELF 解析，**没有 x86_64 设备实跑记录**。
- icu 的 **SONAME 必须是 `libicuuc78.so`**（不带 `.78`）：AGP 的 jniLibs 不接受版本化文件名，重编时用 `patchelf --replace-needed libicuuc.so.78 libicuuc78.so` 改写（见 `tools/tex-poc/README.md`）。
- 这些库在构建中**不需要你重编**：仓库已带产物，`assembleRelease` 只会打包。仅当你改包名、换 NDK、或要升级 Tectonic/MuPDF 时才需要按对应源码目录重编。
- 许可证各自沿上游：**MuPDF 为 AGPL-3.0（或商业授权）**、proot 为 GPLv2+、ICU/Graphite2 为各自开源许可；本仓整体 AGPL-3.0，随包分发时请自行核对。

## 📦 下载与安装（预编译 APK）

自 **`1.0.28-beta.7`** 起，[Releases](https://github.com/Fantasycvh/RikkaHub-Neomix/releases) 附带 arm64-v8a APK（**只有这一个架构**）；此前的 `v1.0.27` 与 `1.0.28-beta.1`…`beta.6` 仅 tag、无资产。

| 项 | 值 |
|---|---|
| 文件 | `Neomix-<versionName>-arm64-v8a.apk`（如 `Neomix-1.0.28-beta.7-arm64-v8a.apk`） |
| 架构 | **仅 arm64-v8a**（真机；x86_64 模拟器请自编） |
| 签名 | 自签密钥 `CN=neomix`，证书 SHA-256 `D1:5B:69:3A:20:10:B2:EE:5F:78:F3:A6:EE:D0:A1:57:E0:74:B4:B2:0F:47:6E:C3:1F:5C:91:74:D9:4E:EC:78` |
| 安装 | `adb install -r Neomix-<version>.apk`；或手机端打开安装（需允许「安装未知来源」） |
| 校验 | `sha256sum <apk>`（Windows：`certutil -hashfile <apk> SHA256`）；每次的 SHA-256 写在对应 release 说明里 |
| 升级 | 与 `beta.1`…`beta.6` 同一把密钥 ⇒ 可直接覆盖升级、本地数据保留；**换密钥或装其他签名的包必须先卸载（会丢数据）** |

> 本仓不提供 Google Play / 上游官方签名，安装前请核对签名与 SHA-256。发布说明原文存档在 [`docs/releases/`](docs/releases/)。
> 只想自己构建：见下方「构建指南」；构建产物包含 `arm64-v8a` / `universal` / `x86_64` 三个 APK。

## 🛠️ 构建指南

环境要求：

- **JDK 17+**（本地验证 **21**；CI 用 17。`sourceCompatibility` / `jvmTarget` 为 **17**）
- **AGP 9.3.1** / **Gradle 9.5**（wrapper 已入库，`gradlew` 自带）
- **Android SDK Platform 37** + **Build-Tools 37.0.0**（`compileSdk`/`targetSdk` 均为 37；平台 37 需在 `sdkmanager` 里勾选，可能与稳定通道不同步）
- **Android NDK + CMake 3.22.1**（`workspace` 模块用 CMake 编 `termux_pty` / `workspace` 原生库；NDK 未指定版本，用 AGP 默认版本即可）
- Android SDK（release 走 R8 混淆，**必须配签名**，见步骤 1）
- `web` 模块在 `preBuild` 阶段构建 `web-ui/`，需要 **Node 22** + **pnpm**（CI 用 22）

步骤：

0. **初始化子模块**（首次克隆必须）：`git submodule update --init --recursive`
   —— `material3` 模块依赖 `material-color-utilities`，不初始化会报 `Unresolved reference 'dynamiccolor'`
0b. **安装 web-ui 依赖**（首次）：`cd web-ui && pnpm install`
   —— 构建时 `:web:buildWebUi` 会执行 `pnpm run build` 并把产物写进 `web/src/main/resources/static`（约 13 MB，已 gitignore）。
   只想快速验证编译可加 `-x :web:buildWebUi` 跳过；此时 APK 内不含 Web 访问静态资源。
1. `local.properties`（**仓库根目录**，不入版本控制）：
   ```properties
   sdk.dir=<Android SDK 路径>
   # 仅 assembleRelease 需要（assembleDebug 不需要）：
   #   不配会直接失败:Keystore file not set for signing config release
   storeFile=<keystore 路径>
   storePassword=***
   keyAlias=***
   keyPassword=***
   ```
   没有 keystore 就先自签一个（自签包**无法覆盖安装**官方签名版本，需先卸载、会丢本地数据）：
   ```bash
   keytool -genkeypair -v -keystore neomix-release.jks -alias neomix \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -storepass <密码> -keypass <密码> -dname "CN=Neomix"
   ```
2. `app/google-services.json`（**必需**，仓库里没有、已 gitignore）：
   ```bash
   cp app/google-services.example.json app/google-services.json
   ```
   —— **缺失会让构建直接失败**（`processDebug/ReleaseGoogleServices` 报 `File google-services.json is missing`，不是"只是运行时崩"；
   只有真实 Crashlytics/Analytics 才需要替换成自己的 Firebase 配置）。仓库自带的示例是占位值，package 覆盖 `app.androidrik.rikkahub`
   与 `app.androidrik.rikkahub.debug` 两个变体；新增变体（如自建 `applicationIdSuffix`）需要再加一个同 package 的 client，否则同样构建失败。
3. Debug：`bash scripts/build.sh :app:assembleDebug`；Release：`bash scripts/build.sh release`
4. 产物：`app/build/outputs/apk/release/app-{arm64-v8a,universal,x86_64}-release.apk`（本项目只发 arm64-v8a）
5. 测试：`bash scripts/build.sh :app:testDebugUnitTest`

> `scripts/build.sh` 会在 `app/google-services.json` 缺失时自动从示例复制一份（省掉手抄，含义同步骤 2）。
> CI（`.github/workflows/daily-build.yml`）目前**只能手动触发**：签名与 `GOOGLE_SERVICES_JSON` 两个 repo secrets 尚未配置。
> 只想快速验证编译：`./gradlew :app:compileDebugKotlin -x :web:buildWebUi -q`（跳过 web-ui 构建）。
> Gradle wrapper 下载可能超时，可用本地缓存 `~/.gradle/wrapper/dists/gradle-9.5.0-all/.../gradle-9.5.0/bin/gradle`。

## ⚠️ 已知问题与排查记录

### 中转站模型报「no effective pricing」

- **现象**：通过中转站（new-api / one-api 类网关）调用部分模型时返回 `Current group has no effective pricing for model <模型名>`
- **根因**：该模型在中转站已被撤下/失效，或当前 API key 所属**用户组**未为该模型配置**有效定价**——**与客户端无关**
- **处理**：在中转站后台确认模型是否仍上架；换用仍有效的模型 id；或为该 key 组绑定模型定价

## 🧱 技术栈

- [Kotlin](https://kotlinlang.org/)（2.4）/ [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Koin](https://insert-koin.io/) / [Room](https://developer.android.com/training/data-storage/room) / [OkHttp](https://square.github.io/okhttp/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) / [Material You](https://m3.material.io/) / [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)

## 📄 License

[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。
本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0），上游原版 README 见 [`README-en-origin.md`](README-en-origin.md)。
