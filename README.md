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

🔗 [GitHub Releases](https://github.com/androidrik/rikkahub-dev/releases)（当前：`v2.4.5-androidrik.8`）

> 本仓库为私有测试仓库，Release 提供 `arm64-v8a` / `x86_64` / `universal` 三种架构 APK。

## 📌 项目简介

RikkaHub Plus 是基于开源项目 [RikkaHub](https://github.com/rikkahub/rikkahub)（`2.4.5`）的 fork，
包名 `app.androidrik.rikkahub`，应用名 **RikkaHub Plus**。

- **开发者分支不跟随上游**：所有定制集中在 `feat/restore-proxy`，`master` 仅作为上游镜像（`ff-only` 同步），两者互不干扰。
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

## 🎨 核心功能（继承上游）

- 🎨 Material You 设计 + 🌙 深色模式
- 📦 Workspace：基于 proot 的 Linux Agent 运行环境
- 🔄 多 AI 提供商：自定义 API / URL / 模型（全部 OpenAI、Google、Anthropic 兼容接口）
- 🖼️ 多模态输入（图片、文本、PDF、Docx）
- 🖥️ Web 访问，多平台使用
- 🛠️ MCP 支持
- 📝 Markdown 渲染（代码高亮、LaTeX 公式、表格、Mermaid）
- 🪾 消息分支
- 🔍 搜索能力（Exa、Tavily、智谱、LinkUp、Brave、Perplexity 等）
- 🧩 提示词变量（模型名、时间等）
- 🤳 提供商配置二维码导出 / 导入
- 🤖 Agent 自定义
- 🧠 类 ChatGPT 记忆功能
- 📝 AI 翻译
- 🌐 自定义 HTTP 请求头与请求体
- 💌 SillyTavern 角色卡导入

## 🌿 分支结构

| 分支 | 角色 |
|---|---|
| `master` | **上游跟踪镜像**，仅 `ff-only` 同步 `upstream/master`，不参与开发 |
| `feat/restore-proxy` | **开发分支**（远程默认），全部定制与发布在此进行，不跟随上游 |

上游同步命令（仅动 `master`）：

```bash
git fetch upstream && git checkout master && git merge --ff-only upstream/master && git push origin master
```

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
5. 产物：`app/build/outputs/apk/release/app-{arm64-v8a,universal,x86_64}-release.apk`

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
