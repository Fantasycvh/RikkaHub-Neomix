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

## 二、实现优先级建议（供未来参考）

| 优先级 | 项 | 理由 |
|---|---|---|
| P1 | QuickJS 插件系统 | 核心扩展能力，基础设施现成（QuickJS 已内置） |
| P2 | 液态玻璃主题 / 三色定制 | 轻量、体验提升明显 |
| P3 | 轻量个性化（气泡/输入框/字体） | 小改动零风险 |
| P4 | 记忆语义检索（本地 HNSW） | 价值中等，工程较大 |
| — | 生活感知/健康/云同步 | **明确不做** |

## 状态记录

- 2026-08-12：完成橘瓣分析并记录本规划（借鉴价值分级 + 优先级）。**暂不实现**，待用户决定何时推进。
