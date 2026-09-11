<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub Plus</h1>

RikkaHub 2.4.5 的私有定制版 —— 原生 Android LLM 聊天客户端，支持多提供商切换 🤖💬

[English (原版 README)](README-en-origin.md)
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="聊天界面" width="150" />
  <img src="docs/img/desktop.png" alt="模型选择" width="450" />
</div>

## 📥 下载

🔗 [GitHub Releases](https://github.com/androidrik/rikka2-my_feature_open/releases)（正式发布：`v1.0.2`）

> 本仓库为私有测试仓库，Release 提供 **`arm64-v8a` 单架构 APK**。

## 📌 项目简介

RikkaHub Plus 是基于开源项目 [RikkaHub](https://github.com/rikkahub/rikkahub)（`2.4.5` 基线，master 已 ff-only 同步至上游 `2.5.1`，`2.4.8`-`2.5.1` 有选择性吸收）的 fork，
包名 `app.androidrik.rikkahub`，应用名 **RikkaHub Plus**。

- **自 v2.4.6 起上游选择性跟进**：`master` 仍可同步上游（ff-only 镜像机制保留），但上游修复/功能**选择性吸收**——不再全部跟进，仅按需人工评估有价值的部分
- **独立版本体系**：`2.4.6` 为独立维护起点，此前定制积累完整保留
- **私有测试用途**：本仓库为个人/团队测试发布，不面向公网分发。

## ✨ 本项目定制功能

在继承上游全部能力的基础上，本分支做了以下定制：

| 功能 | 说明 |
|---|---|
| 🌐 **网络代理支持** | 恢复自上游 1.6.15 的 HTTP 代理功能：支持 HTTP 代理 + 基本认证，OpenAI / Claude / Google 三种 Provider 的请求均走代理；设置页提供独立「网络代理」tab |
| 🔄 **模型拉取反馈增强** | 借鉴 Cherry Studio：填写 Base URL / API Key 后自动拉取提供商模型列表；拉取失败不再静默，显示错误卡片（含原因）+ 重试按钮；底部工具栏新增「获取模型列表」显式刷新；候选模型经搜索/过滤/全选勾选添加 |
| 🔑 **提供商多 Key** | 每 key 独立输入框 + 启用开关 + 拖拽排序（列表顺序=优先级，首个=首选）；失败转移覆盖 401/403/429、余额耗尽、TPM/RPM 限流（含中转站 SSE 流内错误无状态码形态）——账户级故障自动切下一个 key，仅零输出失败换 key（半截保留草稿不换）；兼容旧单 key 与分隔符多 key |
| 🛟 **备用模型链（fallback）** | 助手可配置备用模型：主模型整轮失败自动切备用模型重跑；助手级 > 全局默认两级配置，引用失效（模型被删）自动回退全局；有 fallback 时压缩主模型重试预算快速切换 |
| 💾 **备份恢复兼容** | 兼容上游旧包名（`me.rerere.`）导出的备份：恢复时自动迁移包名（SettingsJsonMigrator V0），S3 / WebDAV / 本地恢复均可用 |
| 📂 **远端备份目录区分** | 远端备份默认目录改为 `rikkahub_plus_backups`，与上游备份互不混淆 |
| 🧹 **移除上游营销功能** | 移除应用内更新检查、赞助入口/捐赠页/启动次数赞助弹窗、导出图片中的 `rikka-ai.com` 品牌水印 |
| 🧮 **TikZ 图表渲染** | **mupdf路线`$$...$$` 内嵌 + ```tikz 围栏双触发；导出 HTML 内嵌 `<svg>` 矢量；系统提示词引导模型输出闭合 `$$` 格式 |
| 🛡️ **TikZ 崩溃防线** | 三层防线（TikzJax 渲染前尺寸校验 / renderSvg 生成端上限 / plot 采样值上限）+ 高度哨兵 HeightClamp + 文本按码点截断——杜绝巨型 SVG（height 4.58e11）导致 Compose 布局溢出崩溃 |
| 💬 **对话分享/导出** | 列表页长按「分享对话」+ 聊天内顶栏导出双入口，Markdown / 图片 PNG 两形态（系统分享 ACTION_SEND），支持版本范围可选 |
| 🗑️ **允许删除内置提供商** | 系统内置 AI 服务提供商可真删除（墓碑 + 悬空引用保护 + 迁移兼容），删除前提示受影响助手计数 |
| 🔧 **上游吸收** | 已吸收上游 `2.4.8`-`2.4.10` 批次：R8 保留 `@Serializable` / jlatexmath keep、流式 null tool_calls 容错、imggen Provider Override、reasoning max effort、加密推理不回放明文（#1719）、正则拖拽排序（#1700）、`.agc` 文件（#1717）、workspace SAF 修复、英文句首大写、豆包搜索（#1721）；以及 `2.4.11`-`2.5.1` 批次精选：终端多 Tab 后台运行（`3a533a6a`）、空 tool schema 归一化（#1781）、workpace stdin EOF（#1605）、合成消息不参与模板（#1790）、fork 会话继承 folder/cwd（#1778）、代码块导出截断模式（#1841）；2026-09-08 批次：工具审批丢失修复（`55506496`）、think 标签误判锚定（`85402745`/#1725 系）、Skill frontmatter SnakeYAML 正规解析、纯图片/纯附件消息、模型注册组补充、自定义 Response API 路径、provider 阻塞工作移 IO 调度、R8 JWT keep 规则；2026-09-09/09-12 批次：搜索侧多 key 结构化与失败转移、Gemini 捆绑（单数组 tools + ServerTool 流式重实现 + OpenAI 兼容 items 归一化）、终端 Tab 动态标题、cloudflare MCP OAuth、TTS 合成自动重试、Exa 新鲜度、qwen 音色（Serena）、统计页 JSON 兜底、edited_files 安全 cast、DeepSeek V4.1 Flash、工具执行中展开详情、消息队列与语音/ASR 双向流（2.5.0 大块自研适配）、翻译快捷方式与统一 Intent 导航、系统栏图标色恢复、自定义时间提醒间隔、SillyTavern V1 老卡与预设识别、模型名空格支持、通知小图标与测试连接本地化、代码块连字禁用、workspace HTML/SVG 预览与 Shell 兼容模式、图片生成页长按多选、图片选择改用系统相册（#1821）。master 已 ff-only 同步至 `2.5.1`；详细评估见 `docs/upstream-analysis.md`。TikZ 渲染已定稿 mupdf SVG 桥（TEXT_AS_PATH 轮廓，中英文字形矢量，dvisvgm 链移除） |

## 🎨 核心功能（继承上游）

- 🎨 Material You 设计 + 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux Agent 运行环境
- 🔄 多 AI 提供商：自定义 API / URL / 模型（全部 OpenAI、Google、Anthropic 兼容接口）
- 🖼️ 多模态输入（图片、文本、PDF、Docx）
- 🖥️ Web 访问，多平台使用
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid、TikZ 图表）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、智谱、豆包、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量（模型名、时间等）
- 🤳 提供商配置二维码导出 / 导入
- 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头与请求体
- 💌 SillyTavern 角色卡导入

## ⚠️ 已知问题与排查记录

### 中转站模型报「no effective pricing」

- **现象**：通过中转站（new-api / one-api 类网关，如 `<relay-host>`）调用部分模型时，返回
  `Current group has no effective pricing for model <模型名>`
- **根因**：该模型在中转站已被**撤下/失效**，或当前 API key 所属**用户组**未为该模型配置**有效定价**——**与客户端无关**（已验证 fork 与上游原版在 URL 拼接、请求体、鉴权、模型名发送上完全一致）
- **处理**：在中转站后台确认该模型是否仍上架；换用仍有效的模型 id；或联系中转站为该 key 组绑定模型定价
- **记录**：2026-08-14 —— `gpt-5.6` 于 `<relay-host>` 撤下导致此报错，客户端无需任何改动

## 🔭 后续规划

本项目维护一份[规划文档](docs/roadmap.md)，记录已调研的未来方向与可行性分析。当前方向：

- 💎 **三色定制**：毛玻璃已实现（输入栏/列表/顶栏独立开关），「全局背景 / 用户气泡 / 重点色 三色映射」定制仍未做
- 🎨 **轻量个性化**：气泡透明度已有，输入框背景 / 字体包导入 / 头像框 未做
- ~~🔑 搜索 / 提供商多 key 故障转移~~ **✅ 已落地（2026-09-09/09-10）**：提供商侧多 key（每 key 启停 + 拖拽排序）+ 备用模型链（roadmap 三.5 / 四）、搜索侧 ApiKeyEntry 结构化与失败转移（`f7e673fb`）——401/403/429/余额耗尽/TPM-RPM 限流自动换 key，仅零输出失败换 key（半截保留草稿不换）
- 👁️ **本地 OCR**：ML Kit 本地识别优先 + AI 兜底（图片不出设备）

> 规划明确**不做**：生活感知类能力（定位、短信、App 使用轨迹、健康数据等）、QuickJS 插件系统（已有 `eval_javascript` / MCP / Skill / Workspace 四类机制覆盖），保持隐私优先定位。
> 上游 issue 跟踪与其他调研记录见规划文档。以上均为规划，**暂未实现**。

## 🌿 分支结构

| 分支 | 角色 |
|---|---|
| `imperfect-customization-and-refinement` | **唯一开发分支**（远程默认），全部定制与发布在此进行 |
| `master` | **上游镜像**，可继续 `ff-only` 同步上游（`git fetch upstream && git checkout master && git merge --ff-only upstream/master && git push origin master`） |

**与上游关系（自 v2.4.6）**：`master` 仍可同步上游，但上游修复/功能**选择性吸收**——不再全部跟进，仅按需人工评估有价值的部分（如关键 bug 修复、低成本高价值功能），评估后移植到 `imperfect-customization-and-refinement`。

## 🛠️ 构建指南

环境要求：

- **JDK 21** / **AGP 9.3.1** / **Gradle 9.5**
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
3. 构建 Debug：`gradle :app:assembleDebug`
4. 构建 Release：`gradle :app:assembleRelease`
5. 产物：`app/build/outputs/apk/release/app-{arm64-v8a,universal,x86_64}-release.apk`（**发布仅上传 arm64-v8a**）

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
