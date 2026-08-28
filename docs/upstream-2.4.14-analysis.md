# 上游 2.4.12–2.4.14 变更分析（待吸收评估）

> 生成时间：2026-08-28
> 上游起始：`1e3351f7`（我们 master 旧位置）→ `7da69770`（当前 master，38 提交）
> 上游版本：2.4.12 / 2.4.13 / 2.4.14 三个 tag；
> 状态：**仅同步分析，未吸收**（master 已 ff-only 同步到 `7da69770`，已推 origin；特性分支 `feat/restore-proxy` 未动）
>
> 决策：继续观察。本批次最大的信号是上游用「生成期临时 dataSync 前台服务 + 固定 3 次重试」解决了后台断流问题，与我们自有的「常驻保活 + 可配置重试序列」方案正面重叠，需明确取舍后再吸收其余内容。

## 同步方式

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master   # 1e3351f7..7da69770
git push origin master
git checkout feat/restore-proxy
```

## 变更规模

103 文件，+4842/-2196（含 videogen/oauth 两个新模块的大体积新增）。

注意：我们特性分支已完成包名迁移（`me.rerere.rikkahub` → `app.androidrik.rikkahub`，728 文件），**所有 cherry-pick 均需包名适配**；`GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt` 是我们改动最重的三个文件，恰好也是本批次上游改动最集中的文件。

## 分类评估

### A. ⚠️ 与自有实现正面重叠（设计分歧，暂保留自有版本）

#### A1. `c62f1eb1` 使用前台服务避免后台生成断开

| | 上游方案 | 我们的方案（`9d82610d`） |
|---|---|---|
| 形态 | 生成期间 acquire/release 的**临时** FGS（`ChatGenerationForegroundService`，dataSync 类型，引用计数，全部结束后 stop） | **常驻** KeepAliveService（specialUse 类型，START_STICKY 被杀自启 + onTaskRemoved 重新断言） |
| 生命周期 | 精确跟随每次生成 | App 启动即起，开关在设置页 |
| 抗杀能力 | 生成期间强，平时无保护 | 全程保护 + 电池优化白名单引导 |
| 政策/功耗 | 更符合 Google 政策，更省电 | specialUse 类型有审核风险，常驻耗电 |

两个方案解决同一问题但互补。**当前保留自有实现**；上游方案可作为「按需模式」后续吸收（其 `activeGenerations` 引用计数 + `onTimeout` 主动停生成的处理写得较完善，值得参考）。

#### A2. `c16fe44f` 支持自动重试（close #1707）

上游：固定 3 次重试、1s 起步延迟，只处理流式路径，预先创建助手消息让重试复用同一消息 ID（ChatService 覆盖当前分支而非新建候选）。
我们（`c460d1c4`）：RetrySetting 间隔序列（默认 2,2,2,2,2,5,10,20,60,120，长度可调）、全局开关 + per-provider 三态覆盖、更细的错误分类（IOException/HTML 错误页/408/425/429/5xx 可重试，401/403/400/404 与取消直抛）、流式与非流式 + 工具循环单步 + translateText 全覆盖。

我们的实现是超集，**保留自有**。唯一值得借鉴：上游「重试复用同一助手消息 ID → 覆盖当前分支」的思路，如果我们的回滚快照方案在分支 UI 上表现不同，可以对比一次。

### B. 新修复（推荐后续吸收，按优先级排序）

| # | 提交 | 内容 | 价值 | 整合成本 | 说明 |
|---|---|---|---|---|---|
| B1 | `e8293d35` | Chat Completions 空 tool schema 归一化 (#1781) | 高 | 极低（1 行 + 77 行测试） | `tool.parameters()` 为 null 时补空 Obj schema，避免严格 OpenAI 兼容端点 400。我们分支同款代码，直接适配包名 |
| B2 | `9851d037` | Gemini 服务端工具与客户端工具共存 | 中高 | 小（GoogleProvider 单文件） | 原实现中 `model.tools` 非空会整体覆盖 functionDeclarations，导致 Gemini 搜索等内置工具与 function calling 互斥；现在合并进同一 tools 数组 |
| B3 | `b62d29d1` | workspace 非交互命令 stdin 立即 EOF (#1605) | 中高 | 小（32 行） | ProcessBuilder stdin 管道不写不关导致 cat/gh 等 CLI 永久挂起；我们 workspace 模块已迁移包名，适配即可 |
| B4 | `942d0d28` | 合成消息不参与消息模版 (#1790) | 中 | 中 | UIMessage 加 `@Transient isSynthetic`，TemplateTransformer/PromptInjection/TimeReminder/WorkspaceReminder 全部排除；触及 GenerationHandler（我们重改文件），需手工合入 |
| B5 | `fa0305ba` + `daae3749` | 模型搜索 IME 自动关闭修复 + ChatInput IME 动画稳定性 | 中 | 中 | 注意：配套的输入栏折叠功能 `ca31612d` 已被上游自己在 `7aa909b8` 回滚，净效果只剩 IME 修复；我们的 ChatInput 有定制，需逐段确认 |
| B6 | `6c6a8458` | fork 会话继承 folder id 和 workspace cwd (#1778) | 中 | 小 | 39 行 |
| B7 | `321443d8` | tool-only 消息保持操作按钮可见 (#1689) | 中 | 小 | 客户端/服务端工具视为可见 UI 内容 |
| B8 | `aab5026e` | Live Update 焦点通知胶囊图标不显示 (#1782) | 低中 | 极低（1 行） | `small_icon` → `ic_stat_rikkahub`，资源名不受包名迁移影响 |
| B9 | `e9b98a4b` | qwen tts 预制音色列表修复 (#1793) | 低 | 极低（1 行） | |
| B10 | `4fcb590a` | 供应商测试连接对话框本地化补全 | 低 | 小 | 7 个 values 目录 |

### C. 功能增强（按需吸收）

| # | 提交 | 内容 | 说明 |
|---|---|---|---|
| C1 | `03534d14` + `6e26affe` | qwen audio 3.0 TTS 适配 + mimo tts 快速音色选择 | TTS 侧我们改动少，成本小-中 |
| C2 | `7da69770` | 设置页文件清理改 bottom sheet（按时间范围 + 本地化） | UX 改进，FilesManager + SettingFilesPage |
| C3 | `7714f2bc` | chatbox v2 备份导入 (#1799) | ChatboxImporter 重写 964 行 + 137 行测试；仅当有用户需要时吸收 |
| C4 | `bce78766` | workspace 终端标签关闭前确认 | 小 |
| C5 | 模型注册组：`5b58c957` glm 5.3 / `86c85236` hy4 / `0826a3b9` qwen 3.8 / `5b890d22` hy4 移除 vision / `979cd169` kimi icon | 极低成本，建议随下一批一并吸收 | 默认模型列表数据，不涉及逻辑 |

### D. 大型重构/新模块（暂缓，等上游稳定）

| # | 提交 | 内容 | 说明 |
|---|---|---|---|
| D1 | `b6df5f04` | OAuth 流程重构（+1440/-550，30 文件） | 抽出独立 `oauth` 模块：loopback HTTP 回调服务器 + 前台服务回调，替代深链 McpOAuthCallbackActivity，兼容性更好。等 MCP OAuth 有实际需求再整体评估 |
| D2 | `8f4f1286` | TranslationHandler 从 GenerationHandler 抽离 | 纯重构；触及我们改动最重的文件，建议跟随 B4 等修复一并处理，不单独 cherry-pick |
| D3 | `96fbe7e3` | videogen 模块完善（视频生成 API 层） | Aliyun/MiniMax/Volcengine 三家 provider + Manager，app 已依赖但**尚无 UI 入口**。等上游出 UI 后整体吸收，API 层单独吸收无意义 |
| D4 | `53dc38b8` | 移除无用 MCP transport 代码（-570 行） | 清理性质；确认我们未定制这两个文件后可顺手吸收 |
| D5 | `0533acde` | 移除默认 rikkahub 提供商 | 品牌相关；我们 fork 的默认提供商策略需要单独决策，**不要盲目跟随** |

### E. 无需处理

`eba2e96c`（依赖更新）、`9687f97a`（CI 触发时间）、`8a9f4f0f`（抖音群信息）、`0c056d08`/`170a612e`/`da5fd77b`（版本 bump）、`2dc50126`（贡献指南）、`ecc6d910`（claude api skill 简化）、`ca31612d` + `7aa909b8`（输入栏折叠：已加已回滚，净效果为零）。

## 结论

1. master 已同步到 `7da69770` 并推送 origin；特性分支未动。
2. 本批核心主题「后台生成稳定性」与我们自有方案正面重叠，**先做 A1/A2 取舍，再谈吸收**——如果决定保留自有方案，B 系列修复可独立 cherry-pick（包名适配后）。
3. 快速可做：B1/B8/B9（接近零成本）、C5 模型注册组、B2/B3/B6/B7（小成本单文件）。
4. 暂缓：D1 oauth 模块、D3 videogen UI（上游还没出 UI）、D5 默认提供商决策。
