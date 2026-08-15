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

🔗 [GitHub Releases](https://github.com/androidrik/rikkahub-dev/releases)（当前：`v2.4.6`）

> 本仓库为私有测试仓库，Release 提供 **`arm64-v8a` 单架构 APK**。

## 📌 项目简介

RikkaHub Plus 是基于开源项目 [RikkaHub](https://github.com/rikkahub/rikkahub)（`2.4.5`，含已吸收的 `2.4.8` 修复）的 fork，
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
| 💾 **备份恢复兼容** | 兼容上游旧包名（`me.rerere.`）导出的备份：恢复时自动迁移包名（SettingsJsonMigrator V0），S3 / WebDAV / 本地恢复均可用 |
| 📂 **远端备份目录区分** | 远端备份默认目录改为 `rikkahub_plus_backups`，与上游备份互不混淆 |
| 🧹 **移除上游营销功能** | 移除应用内更新检查、赞助入口/捐赠页/启动次数赞助弹窗、导出图片中的 `rikka-ai.com` 品牌水印 |
| 🧮 **TikZ 图表渲染** | **纯 Kotlin TikZ 渲染器**（`TikzRenderer`）→ SVG 字符串 → AndroidSVG 矢量绘制（无 WebView/wasm）：`\node`/`\draw`/`-- cycle` 闭合/`arc` 圆弧/`\fill`·`\filldraw`/`grid` 网格/`plot` 函数图像（MathExpr 表达式求值 + NaN 断开）、rounded corners 几何倒角、tikzcd 交换图、箭头/贝塞尔/极坐标/命名节点/中文文本、深色模式；`$$...$$` 内嵌 + ```tikz 围栏双触发；导出 HTML 内嵌 `<svg>` 矢量；系统提示词引导模型输出闭合 `$$` 格式 |
| 🐛 **turbo bug 审计与修复** | 对照 rikkahub-turbo 48 项 bug 清单全量核查（`docs/references/turbo-bugs-audit.md`），分多批修复：崩溃（深递归 SOE 兜底、base64 容错、TTS 悬浮窗无权限守卫）、数据丢失（压缩覆盖新消息合并、附件延迟清理、设置并发 Mutex）、资源防护（文档/记忆/消息/网络体积上限、QuickJS destroy）、功能正确性（WebDAV 路径编码、分享 EXTRA_STREAM 兼容、MCP 名校验） |
| 💬 **对话分享/导出** | 列表页长按「分享对话」+ 聊天内顶栏导出双入口，Markdown / 图片 PNG 两形态（系统分享 ACTION_SEND），支持版本范围可选 |
| 🔧 **上游吸收** | 已吸收上游 `2.4.8` 的「R8 保留 `@Serializable` 类」修复（防添加部分搜索商时反序列化崩溃）；v2.4.6 起与上游分离，后续按需人工吸收 |

## 🎨 核心功能（继承上游）

- 🎨 Material You 设计 + 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux Agent 运行环境
- 🔄 多 AI 提供商：自定义 API / URL / 模型（全部 OpenAI、Google、Anthropic 兼容接口）
- 🖼️ 多模态输入（图片、文本、PDF、Docx）
- 🖥️ Web 访问，多平台使用
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid、TikZ 图表）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、智谱、LinkUp、Brave、Perplexity 等）
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

- 🧩 **QuickJS 插件系统**：基于 RikkaHub 已内置的 QuickJS 引擎，构建 JS 插件框架（借鉴橘瓣 OrangeChat）——用途界定为「AI 执行 JS 脚本」类工具
- 💎 **液态玻璃主题 / 三色定制**：全局背景 / 用户气泡 / 重点色 三色映射 + 玻璃质感——建议仅局部浮层（全列表 blur 掉帧风险）
- 🎨 **轻量个性化**：气泡透明度、输入框背景、字体包导入
- 🔑 **搜索多 key 故障转移**：搜索服务多 key，遇 401/403/429 自动切换重试
- 👁️ **本地 OCR**：ML Kit 本地识别优先 + AI 兜底（图片不出设备）

> 规划明确**不做**生活感知类能力（定位、短信、App 使用轨迹、健康数据等），保持隐私优先定位。
> 上游 issue 跟踪与其他调研记录见规划文档。以上均为规划，**暂未实现**。

## 🌿 分支结构

| 分支 | 角色 |
|---|---|
| `feat/restore-proxy` | **唯一开发分支**（远程默认），全部定制与发布在此进行 |
| `master` | **上游镜像**，可继续 `ff-only` 同步上游（`git fetch upstream && git checkout master && git merge --ff-only upstream/master && git push origin master`） |

**与上游关系（自 v2.4.6）**：`master` 仍可同步上游，但上游修复/功能**选择性吸收**——不再全部跟进，仅按需人工评估有价值的部分（如关键 bug 修复、低成本高价值功能），评估后移植到 `feat/restore-proxy`。

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
