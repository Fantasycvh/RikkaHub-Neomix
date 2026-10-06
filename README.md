<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Plus</h1>

RikkaHub 2.4.5为基线的个人定制版 —— 原生 Android LLM 聊天客户端，支持多提供商切换 🤖💬

个人二改版，发到 GitHub 只是为了备份方便和git拉取的便利，没有维护承诺。

[English (原版 README)](README-en-origin.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择" width="450" />
</div>

## 📥 下载

🔗 [GitHub Releases](https://github.com/androidrik/rikka2-my_feature_open/releases)（正式发布：`v1.0.24` ｜ 草稿：`v1.0.25`）

> 本仓库为个人测试仓库，Release 提供 **`arm64-v8a` 单架构 APK**。

## 📌 项目简介

RikkaHub Plus 是基于开源项目 [RikkaHub](https://github.com/rikkahub/rikkahub)（`2.4.5` 基线）的 fork，
包名 `app.androidrik.rikkahub`，应用名 **RikkaHub Plus**。

- **上游持续跟进**：本地 `master` 以 `ff-only` 方式同步上游，**当前同步点 `85af5b91`（上游 `2.5.6`，2026-10-04）**；其间各版本（`2.4.8` → `2.5.6`）的修复/功能**选择性吸收**——不再全部跟进，仅按需人工评估有价值的部分，逐条评估记录见 [docs/upstream-analysis.md](docs/upstream-analysis.md)
- **独立版本体系**：应用版本自 `1.0.x` 独立编号（当前 `1.0.24` / `versionCode 287`），与上游的 `2.5.x` / `versionCode 19x` 相互独立；`2.4.6` 为独立维护起点，此前的定制积累完整保留
- **上游 PR 政策**：上游明确不接受 PR，故本分支的改动不回推上游；上游已跟进（吸收/同款）的能力不再计入下方「定制功能」

## ✨ 本项目定制功能

在继承上游全部能力的基础上，本分支做了以下定制：

> **不在此列的两类内容**：①**吸收自上游**的修复与功能（如 MCP `$defs` 展开 / OAuth、终端多 Tab、内置技能、`chart_display`、ExpressivePopup、模型列表折叠、生成自动重试基础形态等）；②**上游同样具备**、只是实现来源不同的能力（如消息队列——双方各自实现）。这两类都计入下方「核心功能（继承上游）」，判定与评估过程见 [docs/upstream-analysis.md](docs/upstream-analysis.md)。

**对话与生成**

| 功能 | 说明 |
|---|---|
| 🩹 **半截回复保留与重试分流** | 弱网/断流失败时保留已有内容为可编辑草稿（阈值可配）；按失败类型分流（仅零输出失败换 key / 重试），有 fallback 时压缩主模型重试预算以快速切换（#1812 自研方案） |
| 🧠 **自动上下文压缩** | harness 式检查点压缩：轨迹只追加、可展开回看可编辑、编辑自动失效、参数可配置档 |
| 📊 **上下文容量面板** | 剩余/占用/上限 + 预计可容纳轮数 + 上下文组成明细 + 一键压缩；标题旁占用读数；输入栏指示器细条/圆环可切换 |
| 🧮 **真 tokenizer** | cl100k BPE 精确 token 计数（估算、统计、预算判定同源），后台预热 + 启发式兜底 |
| 🧷 **消息引用** | QQ 式引用（快照 + ID 双存、输入框预览卡、点击跳转、队列携带）——对应上游 issue #1906，上游尚未实现 |
| 🏷️ **生成来源标注** | 每条回复末尾可选显示实际「路由 · 模型」（默认关，多中转核对/成本归因）——对应上游 issue #1805，上游尚未实现 |
| 🛟 **会话加载守卫** | 生成中的会话被重开或 web 请求触碰不会覆盖内存中的半截内容 |
| 🔍 **拼装预览与缓存诊断** | 发送前提示词逐段归因（Tier 1–4，可点注入段跳到产出条目、工具 schema 独立成段）；缓存稳定性三档分组；**前缀分叉诊断**（对比上次/本次请求，定位缓存断点并估算可命中前缀） |

**Agent / 工作区**

| 功能 | 说明 |
|---|---|
| 🤖 **子代理系统** | 独立上下文（信封隔离父对话注入）+ 对话内派发 + 升级到人审批 + 并行上限 + 上下文预算 |
| ⚙️ **工作区后台任务** | `run_background / status / kill` 三工具 + 完整输出落盘 `.tasks/` + 30 分钟时长上限 + 可见性 UI |
| 🧯 **护栏与预算** | LoopGuard 三档制（严格/标准/宽松）+ rootfs 单轮字符/步数预算 + 子代理并行上限——全部可配 |
| 📦 **工作区增强** | 长按多选导出（zip/目录两种产物 + 批量删除）、HTML/SVG 预览、Shell 兼容模式 |

**知识与提示词**

| 功能 | 说明 |
|---|---|
| 📚 **RAG 知识库** | 跨库向量检索工具 `search_knowledge_base`（per-KB 嵌入模型、导入即解析入库） |
| 🎭 **SillyTavern 深化** | 官方语义的世界书（多源扫描 / token 预算 / 次要关键词）、ST 预设导入、角色卡 V1 兼容、注入位置批量迁移（缓存友好）、世界书种子三重迁移 |

**网络与提供商**

| 功能 | 说明 |
|---|---|
| 🌐 **两层代理** | per-provider（Inherit/None/HTTP/SOCKS5）+ 全局兜底，ai 模块零依赖 Settings |
| 🔑 **提供商多 Key** | 每 key 独立输入框 + 启用开关 + 拖拽排序（列表顺序=优先级，首个=首选）；失败转移覆盖 401/403/429、余额耗尽、TPM/RPM 限流（含中转站 SSE 流内错误无状态码形态）——仅零输出失败换 key（半截保留草稿不换）；搜索侧同套结构 |
| 🛟 **备用模型链（fallback）** | 主模型整轮失败自动切备用重跑；助手级 > 全局默认两级，引用失效自动回退 |
| 🛡️ **MCP 加固** | mTLS 客户端证书；连接错误三分类展示；服务器名支持非 ASCII |
| 🏷️ **启停与真删除** | 提供商/模型/助手/ASR/TTS 均可停用（不阻断既有使用）与真删除（悬空引用保护 + 迁移兼容） |
| 🔄 **模型拉取反馈增强** | 借鉴 Cherry Studio：自动拉取模型列表、失败显示错误卡 + 重试、候选模型搜索/全选添加 |

**备份与数据**

| 功能 | 说明 |
|---|---|
| 💾 **备份加固与互通** | 兼容上游原版包名（`me.rerere.`）备份自动迁移；大文件加固（STORED 采样 + S3 分片 8MB + 单遍哈希 + 空间预检/字节级进度 + 换库前完整性校验）；生成的图片纳入备份；远端目录 `rikkahub_plus_backups` 与上游互不混淆 |
| 📦 **对话导入导出** | 自家格式 zip + 附件原字节往返（导入即换新 id）+ 统一导入器 + 导出留存区（TTL 自动清理） |
| ⭐ **收藏管理增强** | 基础收藏继承上游，本分支增量：标签筛选/管理页、排序切换、双向侧滑撤销 |
| 🧾 **Token 统计增强** | 上游已有总量/按天统计，本分支增量：实际消耗与当前对话双口径、按模型（名称快照防悬空）、按天热力图、缓存命中率 |

**稳定性与安全**

| 功能 | 说明 |
|---|---|
| 🐕 **后台保活** | KeepAlive 前台服务（specialUse）+ WifiLock，生成/语音后台不被杀 |
| 📝 **日志落盘** | 请求日志可落盘 `files/logs`，敏感请求头双重脱敏 |
| 📐 **TikZ 图表渲染** | mupdf SVG 路线（`$$...$$` 内嵌 + `tikz` 围栏双触发；导出 HTML 内嵌矢量）；三层崩溃防线（尺寸校验/生成端上限/采样上限 + HeightClamp） |

**界面**

| 功能 | 说明 |
|---|---|
| 🧩 **代码块一键设为助手** | 会话内 JSON 助手卡即点即用 |
| 🧹 **移除上游营销** | 移除应用内更新检查、赞助入口/捐赠页/启动次数赞助弹窗、导出图片中的 `rikka-ai.com` 品牌水印 |

## 🎨 核心功能（继承上游）

以下能力随上游跟进，属于**继承能力**（含本分支早先吸收的部分）：

- 🎨 Material You 设计 + 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux Agent 运行环境
- 🖥️ 终端多 Tab（会话管理 + PTY 动态标题）
- 🔄 多 AI 提供商：自定义 API / URL / 模型（全部 OpenAI、Google、Anthropic 兼容接口）
- 🖼️ 多模态输入（图片、文本、PDF、Docx）
- 🎨 图像生成（Imggen）
- 🎙️ 语音（TTS / ASR）
- ⏯️ 消息队列（生成中连发自动入队）
- 🖥️ Web 访问，多平台使用
- 🛠️ MCP 支持（含 OAuth 授权、工具 schema `$defs` 内联展开）
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 📊 图表工具（`chart_display`：折线 / 柱状 / 散点）
- ✨ ExpressivePopup（Material Expressive 动效弹层）
- 🗂️ 模型列表折叠（按供应商分组）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、智谱、豆包、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量（模型名、时间等）
- 🤳 提供商配置二维码导出 / 导入
- 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头与请求体
- 📦 内置技能（skill-creator 等随包内置）
- ☁️ 备份与恢复（S3 / WebDAV / 本地）
- ⭐ 消息收藏 / 📤 分享 / 📊 统计
- 💌 SillyTavern 角色卡导入

## ⚠️ 已知问题与排查记录

### 中转站模型报「no effective pricing」

- **现象**：通过中转站（new-api / one-api 类网关，如 `<relay-host>`）调用部分模型时，返回
  `Current group has no effective pricing for model <模型名>`
- **根因**：该模型在中转站已被**撤下/失效**，或当前 API key 所属**用户组**未为该模型配置**有效定价**——**与客户端无关**（已验证 fork 与上游原版在 URL 拼接、请求体、鉴权、模型名发送上完全一致）
- **处理**：在中转站后台确认该模型是否仍上架；换用仍有效的模型 id；或联系中转站为该 key 组绑定模型定价

## 🔭 后续规划

本项目维护一份[规划文档](docs/roadmap.md)，记录已调研的未来方向与可行性分析。当前方向：

- 💎 **三色定制**：毛玻璃已实现（输入栏/列表/顶栏独立开关），「全局背景 / 用户气泡 / 重点色 三色映射」定制仍未做
- 🎨 **轻量个性化**：气泡透明度已有，输入框背景 / 字体包导入 / 头像框 未做
- 👁️ **本地 OCR**：ML Kit 本地识别优先 + AI 兜底（图片不出设备）——**暂缓**（规划文档记录）
- 🧰 **工具协议瘦身**：MCP 工具 schema 按需加载/元工具检索（应对大型 MCP 的 schema token 开销），评估中

> 已落地（保留记录）：~~🔑 搜索 / 提供商多 key 故障转移~~ **✅ 2026-09-09/09-10**：提供商侧多 key（每 key 启停 + 拖拽排序）+ 备用模型链、搜索侧 ApiKeyEntry 结构化与失败转移——401/403/429/余额耗尽/TPM-RPM 限流自动换 key，仅零输出失败换 key（半截保留草稿不换）。
>
> 规划明确**不做**：生活感知类能力（定位、短信、App 使用轨迹、健康数据等）、QuickJS 插件系统（已有 `eval_javascript` / MCP / Skill / Workspace 四类机制覆盖），保持隐私优先定位。
> 上游 issue 跟踪与其他调研记录见规划文档。

## 🌿 分支结构

| 分支 | 角色 |
|---|---|
| `imperfect-customization-and-refinement` | **唯一开发分支**（远程默认），全部定制与发布在此进行 |
| `master`（本地） | **上游镜像**（仅本地保留），`ff-only` 同步上游（`git fetch upstream master:master` 可直接快进本地分支、无需切工作树）；**不再推送到 origin** |

**与上游关系**：`master` 持续跟踪上游（当前同步点 `85af5b91`，上游 `2.5.6`，2026-10-04），上游修复/功能**选择性吸收**——仅按需人工评估有价值的部分（关键 bug 修复、低成本高价值功能），评估与移植记录见 [docs/upstream-analysis.md](docs/upstream-analysis.md)。

## 🛠️ 构建指南

环境要求：

- **JDK 21**（本仓库构建验证版本；`sourceCompatibility` / `jvmTarget` 为 **17**）
- **AGP 9.3.1** / **Gradle 9.5**
- Android SDK（R8 混淆，release 需签名）

构建步骤：

1. 准备 `app/local.properties`（签名 + SDK 路径）：
   ```properties
   sdk.dir=<Android SDK 路径>
   storeFile=<work>/keystore.jks
   storePassword=***
   keyAlias=***
   keyPassword=***
   ```
2. 准备 `app/google-services.json`（**占位即可**，仅需 package 匹配 `app.androidrik.rikkahub`；debug 变体需额外 `.debug` client）——缺失时 release 编译可过但运行时 Firebase 初始化崩溃
3. 构建 Debug：`bash scripts/build.sh :app:assembleDebug`（等价于 `gradle :app:assembleDebug`；脚本统一注入网络参数，国内环境更稳）
4. 构建 Release：`bash scripts/build.sh release`
5. 产物：`app/build/outputs/apk/release/app-{arm64-v8a,universal,x86_64}-release.apk`（**发布仅上传 arm64-v8a**）
6. 测试：`bash scripts/build.sh :app:testDebugUnitTest`（含资源串格式化实参守卫等源码级检查）

> [!NOTE]
> Gradle wrapper 下载可能超时，可用本地缓存（`~/.gradle/wrapper/dists/gradle-9.5.0-all/.../gradle-9.5.0/bin/gradle`）。

## 🧱 技术栈

- [Kotlin](https://kotlinlang.org/)（开发语言，2.4）
- [Jetpack Compose](https://developer.android.com/jetpack/compose)（UI 框架）
- [Koin](https://insert-koin.io/)（依赖注入）
- [Room](https://developer.android.com/training/data-storage/room)（数据库）
- [OkHttp](https://square.github.io/okhttp/)（HTTP 客户端）
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)（JSON 序列化）
- [Material You](https://m3.material.io/)（UI 设计）
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)（导航）

## 🔗 上游

本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0），
上游原版 README 见 [`README-en-origin.md`](README-en-origin.md)。

## 📄 License

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0)
