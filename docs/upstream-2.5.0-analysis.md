# 上游 2.5.0 变更分析（2.4.17 → 2.5.0）

> 同步：master 已 ff-only 更新至 `12ee935e`（tag 2.5.0），2026-09-08。
> 范围：`b153028d`(2.4.17)..`12ee935e`，共 16 个提交。

## 分类评估

### A. 与 fork 直接相关（重点）

| # | 提交 | 内容 | 我们状态 | 建议 |
|---|---|---|---|---|
| A1 | `104040df` | 终端多 Tab 改进：Tab 栏并入导航 + **Tab 标题动态更新**（`updateTitle`，terminal title 变化实时同步）；对 `WorkspaceTerminalSessionClient` 构造函数签名有改动（onTitleUpdated 回调） | fork 已吸收 3a533a6a 多 Tab（fbfcde5e），但以我们 fork 的简化签名（onFinished 单回调）为基础结构一致 | **值得吸收**：Tab 标题是 3a533a6a 之后上游自己做的新增量，让多 Tab 可用性完整（当前我们在 `WorkspaceTerminalPage` 用数字/序号做标题）。需注意 `WorkspaceTerminalSessionClient` 构造签名差异（上游改成 context+onTitleUpdated+onFinished 三参），我们 fork 是单回调——需手工适配 |
| A2 | `1d86b3c1` | 终端键盘手势 + 尺寸调整修复（继承自 104040df 的后续修复） | 同上 | 随 A1 一起吸收 |
| A3 | `55506496` | 连续工具审批丢失 + 取消状态误标修复（UIMessagePart.kt/ChatService/ConversationSession） | fork 无此修复（我们的审批流程是自研） | **值得评估**：与我们 tool approval 交互相关，需对照我们的 `WorkspaceTools.needsApproval`/ChatService 审批路径 |
| A4 | `5902feab` | 允许纯图片消息（无空文本 part） | 小修，见 C1 | - |

### B. 功能增强（按需评估）

| # | 提交 | 内容 | 建议 |
|---|---|---|---|
| B1 | `66de8b30` + `a621e277` + `580e05d6` | 消息发送队列（MessageQueue，连发/排队清附件）+ 语音模式（queued input + 可选 TTS）+ ASR server VAD / Volcengine 双向流 | 大功能块（+1000 行）。与我们 F9（ASR 静默）修复互补但方向不同；**暂缓**，与 roadmap 的语音增强合并评估 |
| B2 | `097cdb90` | ChatToolFactory：聊天工具组装集中（ChatService 瘦身 109 行） | 重构性质，我们 ChatService 也重改过（#1812）；重构方向好但**与我们的重改文件冲突大，手工合入成本高**，暂缓 |
| B3 | `a8f8c3a1` | Exa 搜索新鲜度证据保留（#1854）：搜索结果带 freshness evidence 展示 | search 模块小改，**可吸收**（我们 search 模块改动少，包名适配后直接合） |
| B4 | `a61d116d` | 自定义 response API 路径 | ProviderSetting +1 字段 + 设置页；小，**可吸收** |
| B5 | `74397a32` | 新增 MaruCode 提供商 | 注册表条目，极低，可吸收 |
| B6 | `f2f687f0` | web chat 排版/选择器/消息对齐细化 | web-ui 改动，我们 web-ui 独立构建，**低优先** |

### C. 修复类（合并类，建议吸收）

| # | 提交 | 内容 | 建议 |
|---|---|---|---|
| C1 | `5902feab` | 允许纯图片消息（无空文本 part） | 小修 11 行（ChatInputState.kt），**可吸收** |
| C2 | `7b76847f` | 移动会话时键盘闪烁修复 | 小修，可评估 |
| C3 | `ab8b6cc8` | provider 阻塞工作移到 IO 调度器（Claude/Google/OpenAI 4 文件） | **值得吸收**：阻塞 provider 调用不应占主线程；我们 provider 层同构 |
| C4 | `e5247be0` | 依赖更新 | 版本梳理后按需 |

### D. 无需处理

- `12ee935e`/`b153028d`（bump）、`4309fdfe` 等版本号提交
- 涉及上游已放弃的功能（见既往 2.4.16 分析）

## 与本 fork 的差异提醒

1. **`WorkspaceTerminalSessionClient` 构造签名**：上游 `104040df` 后为 `(context, onTitleUpdated, onFinished)`；我们 fork 是 `(context, onFinished)`——吸收 A1 时须先适配 signature（涉及 `WorkspaceTerminalSessionManager.createTab` 调用处）。
2. **终端 Tab 标题**：上游已从"序号 Tab"演进为"动态标题 Tab"（shell 修改提示符后 Tab 名跟着变）。我们当前是 `Terminal %d` 序号 + `updateTitle` 缺失。
3. 上游 `ChatService` 已被 `097cdb90` 大幅重构——我们 fork 的 ChatService 有 #1812 等重改，**任何涉及 ChatService 的上游补丁都要手工合并，不能直接 cherry-pick**。

## 落地优先级建议

1. **A1+A2**：终端 Tab 标题 + 手势修复（依赖我们的多 Tab 底座，直接增量，收益明显）
2. **C1/C3/B4/B5**：小件批（图片消息/IO 调度/response 路径/MaruCode）
3. **B3**：Exa 新鲜度证据（search 模块独立）
4. **A3**：工具审批修复（需先核对我们的审批路径）
5. **B1/B2**：大功能块暂缓（与 roadmap 语音/重构方向合并评估）

关联：[upstream-2.4.16-analysis.md](upstream-2.4.16-analysis.md)、[handoff-2026-09-04.md](handoff-2026-09-04.md)
