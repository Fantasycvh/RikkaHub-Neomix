# 设计：生成失败时保留半截回复为可见草稿（#1812 对策）

> 状态：**设计稿，未实现**（2026-08-31）
> 背景：上游 issue [#1812](https://github.com/rikkahub/rikkahub/issues/1812)——差网络下生成被截断，自动重试反复重发、一次都没保存，白烧多次额度。上游的解法是加自动重试开关（`41353567`，2.4.16）；本方案是我们 fork 的备选设计：让"保留已有内容"和"自动重试"按失败类型分流。
> 现状行为：`GenerationHandler.kt`（我们的 `withGenerationRetry` + 两处 `catch (e: Throwable)`，流式 :519 / 非流式 :552）——流中途失败时 `messages = snapshot` 回滚、重试从头重发；重试耗尽后上抛 ChatService 走 addError。**半截内容在每个环节都被丢弃**，与上游同款。

## 核心思路：按"是否已收到输出"切分两种失败

| 失败类型 | 特征 | 策略 |
|---|---|---|
| 零输出失败 | 连接失败、开局即断、未收到任何 chunk | **自动重试照旧**（无输出 token，零计费损失） |
| 半截输出失败 | 已流式合并了可见内容后断流 | **不再自动重试；保留为草稿，用户手动决定** |

比上游"加开关"更优：保留了自动重试在零成本场景的价值，只在"重试必然有损失"（丢弃已付费 token）时降级为手动。

配置项（Settings.retrySetting 附近）：
- `失败时保留半截回复`（默认开）
- 可选 `半截后仍自动重试`（默认关，给想要全自动的用户）

## 分层设计

### 第 1 层 — GenerationHandler（捕获与分流）

- 流式 catch 块回滚前保存 `val partial = messages`（已合并的半截消息列表）到 `lastPartial`；
- `withGenerationRetry` 收到"半截输出失败"信号后**直接上抛**，不进重试循环；抛出携带上下文的类型化异常：
  ```kotlin
  class GenerationPartialFailureException(
      cause: Throwable,
      val partialMessages: List<UIMessage>,
  ) : Exception(cause)
  ```
  （实现上可借上游版重试的 `StreamChunkHandlingException` 包裹思路）
- "半截"判定阈值：过滤 reasoning 后可见文本 ≥ 阈值（建议 50~100 字符，可配）；低于阈值按零输出处理，照常自动重试；
- 非流式路径无半截概念，维持现状；translateText 等旁路不动。

### 第 2 层 — ChatService（落草稿）

catch `GenerationPartialFailureException` 时：

- 半截 assistant 消息提交进当前 MessageNode——**复用现有再生成分支机制**（它就是候选消息 #0；用户点"重试"走现有 regenerate 管道，成功后自然成为候选 #1，无需新 UI 分支）；
- 打标记（见第 3 层）并**立即持久化会话**（防杀进程再丢一次，#1812 的核心抱怨）；
- 不发 error 卡片，改发软提示"生成失败，已保留部分内容"；
- 非此类异常：现有 addError 路径不变。

### 第 3 层 — 标记与 UI（二选一，倾向 A）

- **A. UIMessageAnnotation（推荐）**：`{type: "partial_failure"}` 消息级注解；UI 在消息上方渲染警示横幅 + 两个操作（重试 / 保留）。注解是客户端元数据，不参与 provider 请求构建，**天然不进 API 上行**。
  - 验证点：确认 `UIMessageAnnotation` 仅用于持久化/展示，request payload 构建只取 parts。
- **B. 文本标记 + 上传过滤（备选，有现成参考）**：rikkahub-agent `2945c612`「生成打断警示——半截回复追加可见标记 + 上传过滤」：`isUiNotice` 文本追加到半截回复，请求构建器统一过滤。若将来吸收其 ResponseMessageBuilder 系改动可顺势采用。

## 边界与注意点

1. **工具循环中的半截**：半截消息可能含未闭环的 tool call/result。限定**仅当失败发生在用户可见的最终回答流（非工具循环中途）才落草稿**——简单且安全；或退一步只保留 text/reasoning、丢弃未闭环 tool 部分。
2. **usage 记账**：半截流可能已收到 usage chunk，草稿保留 usage 无妨；确认重试路径不重复累计（现有 merge 按消息走）。
3. **reasoning 部分**：保留但默认折叠——半截思考对用户定位断点有用。
4. **"保留并续写"（可选 v2）**：Claude 系支持 assistant prefill，可从截断点续写而非整段重生成，是真正省 token 的彻底方案；OpenAI Chat Completions 不支持预填，需 provider 能力检测 + 仅 Claude 可用，成本高，v2 评估或不做。
5. **与 #1820 的交集**：切对话回复消失的痛感会被"尽早持久化半截状态"同一机制缓解——一个机制治两个病。

## 落地顺序（实现时）

1. 第 1 层：失败分流 + 类型化异常（纯 GenerationHandler；单测模拟流式中途断流、零输出断流两类）；
2. 第 2 层：ChatService 落草稿 + 立即持久化（复用 MessageNode 候选机制）；
3. 第 3 层 A：注解横幅 UI（重试走现有 regenerate）。

关联：[upstream-2.4.16-analysis.md](upstream-2.4.16-analysis.md)（上游 `41353567` 关闭开关）、[peer-apps-benchmark.md](peer-apps-benchmark.md)（rikkahub-agent isUiNotice 参考）。
