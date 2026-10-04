# 设计：生成失败时保留半截回复为可见草稿（#1812 对策）

> 状态：**已实现，真机验证通过（3/3，2026-09-06 闭环）**（2026-09-04 实现；同批吸收 B1 空 tool schema `e8293d35` / B3 workspace stdin EOF `b62d29d1`）。半截阈值已可配置化：`RetrySetting.partialOutputMinChars`（默认 50，`e8e9d4e5`）。真机确认：①切对话回来生成内容不丢（#1820 缓解生效）；②断网/飞行模式零输出自动重试照旧；③弱网断流后「生成中断」横幅可见、半截内容保留。验证主体完成，项状态从『待真机测试』转为『已验证』
> **2026-10-04 扩展（半截/中断主题的第二批，见 `peer-apps-benchmark.md` §四.7）**：本设计只覆盖**异常路径**（失败重试耗尽），
> 三处相邻缺口当时未治，现已补齐：①**裸断流**（网关提前关流、无异常）此前被当正常收尾 ⇒ 现
> `MessageChunk.explicitEnd` 区分显式结束，`UIMessage.finishIncomplete/finishReason` 落地（`7973da13`）；
> ②**崩溃丢失**（流式内容纯内存，也是本设计「问题解剖」第 1 条的直接后果）⇒ 流式检查点每 2 秒落库
> （`20091990`）；③**自动续写与手动继续**（撞上限默认只提示、裸断流恒自动，`38f72acb`）+ 恢复内容的
> 未完成标记与推理块收尾（`f800cb65`）。真机验证：检查点与强杀重开后的标记/继续均已确认。
> 背景：上游 issue [#1812](https://github.com/rikkahub/rikkahub/issues/1812)——差网络下生成被截断，自动重试反复重发、一次都没保存，白烧多次额度。上游的解法是加自动重试开关（`41353567`，2.4.16）；本方案是我们 fork 的备选设计：让"保留已有内容"和"自动重试"按失败类型分流。
> 现状行为：`GenerationHandler.kt`（我们的 `withGenerationRetry` + 两处 `catch (e: Throwable)`，流式 :519 / 非流式 :552）——流中途失败时 `messages = snapshot` 回滚、重试从头重发；重试耗尽后上抛 ChatService 走 addError。**半截内容在每个环节都被丢弃**，与上游同款。

## 问题解剖：为什么"连一次生成都没留下"（2026-09-03 核实代码后确认）

"什么都没留下"不是单一 bug，而是三个环节环环相扣的结果：

1. **流式过程只写内存，从不落库**：`GenerationHandler` 每收一个 chunk 调 `onUpdateMessages`（GenerationHandler.kt:517）→ ChatService `updateCurrentMessages`（ChatService.kt:683）→ `updateConversation` 只做 `session.state.value = conversation`（ChatService.kt:997-1002），**纯内存**。落库唯一入口 `saveConversation`（ChatService.kt:1058）在生成期间只在 `onSuccess` 调用（ChatService.kt:706）。半截内容在数据库里从不存在，进程一死就没了。
2. **重试 = 每次清空半截，从头再来**：`withGenerationRetry` 捕获异常后延迟重跑 `action()`（GenerationHandler.kt:375-400）；action 内 catch 先 `messages = snapshot; onUpdateMessages(messages)`（:519-524），snapshot 是生成前干净状态（:500）。N 次重试 = N 次清空 + N 次从头烧 token。
3. **重试耗尽后，半截内容已不在内存，只剩错误卡片**：:380 耗尽后 `throw e`，此时 messages 已被回滚成 snapshot；ChatService `onFailure` 只调 `addError`（ChatService.kt:696-703），而 `addError`（:189-199）只往内存 `_errors` 列表加一条，**不保存消息内容也不落库**。

**推论（对设计的影响）**："立即持久化"不是保险，而是**给一条完全不落库的失败路径补上第一个写库动作**——它同时缓解 #1820（切走回来消失，根因同为"生成中不落库、内存态在切换/重建时丢失"）。

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
  - **判定必须放在流式 catch 块内**（GenerationHandler.kt:519），因为 `messages` 的当前状态只有 action 内部可见，`withGenerationRetry` 只能看到异常对象；
- `withGenerationRetry` 收到"半截输出失败"信号后**直接上抛**，不进重试循环——**"半截信号"以异常为载体**：`withGenerationRetry` 在异常判定处（:380 之前）识别该类型直接 rethrow，而不是靠共享变量传状态；
  ```kotlin
  class GenerationPartialFailureException(
      cause: Throwable,
      val partialMessages: List<UIMessage>,
  ) : Exception(cause)
  ```
  （实现上可借上游版重试的 `StreamChunkHandlingException` 包裹思路）
- "半截"判定阈值：过滤 reasoning 后可见文本 ≥ `RetrySetting.partialOutputMinChars`（默认 50，可配，UI 暂不暴露与 keepPartialOnFailure 同口径）；低于阈值按零输出处理，照常自动重试。**判定实现零成本**：`messages.lastOrNull()` 即正在生成的 assistant 消息，过滤 `UIMessagePart.Text` 即可，无需额外状态；
- 非流式路径无半截概念，维持现状；translateText 等旁路不动。

### 第 2 层 — ChatService（落草稿）

catch `GenerationPartialFailureException` 时：

- 半截 assistant 消息提交进当前 MessageNode——**复用现有再生成分支机制**（它就是候选消息 #0；用户点"重试"走现有 regenerate 管道，成功后自然成为候选 #1，无需新 UI 分支）；
- 打标记（见第 3 层）并**立即持久化会话**（防杀进程再丢一次，#1812 的核心抱怨；见上文"问题解剖"——这是失败路径的第一个写库动作，`saveConversation` 为 suspend 且走 repository，在 `onFailure` 块（ChatService.kt:696）内可直接调用）；
- 不发 error 卡片，改发软提示"生成失败，已保留部分内容"；
- 非此类异常：现有 addError 路径不变。

### 第 3 层 — 标记与 UI（二选一，倾向 A）

- **A. UIMessageAnnotation（推荐）**：`{type: "partial_failure"}` 消息级注解；UI 在消息上方渲染警示横幅 + 两个操作（重试 / 保留）。注解是客户端元数据，不参与 provider 请求构建，**天然不进 API 上行**。
  - 验证点：确认 `UIMessageAnnotation` 仅用于持久化/展示，request payload 构建只取 parts。
- **B. 文本标记 + 上传过滤（备选，有现成参考）**：rikkahub-agent `2945c612`「生成打断警示——半截回复追加可见标记 + 上传过滤」：`isUiNotice` 文本追加到半截回复，请求构建器统一过滤。若将来吸收其 ResponseMessageBuilder 系改动可顺势采用。

## 边界与注意点

1. **工具循环中的半截**：半截消息可能含未闭环的 tool call/result。限定**仅当失败发生在用户可见的最终回答流（非工具循环中途）才落草稿**——简单且安全；或退一步只保留 text/reasoning、丢弃未闭环 tool 部分。
2. **usage 记账**：半截流可能已收到 usage chunk，且已被 `merge` 进最后一条消息（GenerationHandler.kt:511）。草稿随 `partialMessages` 携带 usage 即可保留；重试成功后新候选 #1 有独立 usage，两者按消息各自记账，不重复累计——实现上零成本，随消息走。
3. **reasoning 部分**：保留但默认折叠——半截思考对用户定位断点有用。
4. **"保留并续写"（可选 v2）**：Claude 系支持 assistant prefill，可从截断点续写而非整段重生成，是真正省 token 的彻底方案；OpenAI Chat Completions 不支持预填，需 provider 能力检测 + 仅 Claude 可用，成本高，v2 评估或不做。**已放弃（2026-09-06）**：仅对长输出场景有净收益（output 差额 vs 前缀 input 重复费），普通聊天无感；中转链路 assistant 结尾不可靠、必须真实 API 测试。协议层零改动（ClaudeProvider 构建器本就支持 assistant 结尾），搁置成本低，若真机测试显示长输出断流高频可随时重启。
   - **2026-09-09 探讨修正（跨协议续写机制）**：生态里续写分三层——①协议原生：Anthropic assistant 结尾、Gemini `MAX_TOKENS` 候选续接；②**通用 hack（跨协议，关键）**：OpenAI 兼容端点（含 DeepSeek/中转/ZCode 类多 provider 工具底层）把半截 text 合并回历史 assistant 消息 + 追一条 user（空串/continue/继续），模型接着风格续写——**无需任何 prefill 能力**，OpenRouter 更有 `model:continue` 中间件语法免实现；③agent 工具自身节奏：输出停在 tool_calls 边界 + 工具结果回填 = 天然断点续写。**修正结论：「仅 Claude 可用」是错的**——通用 hack 覆盖面更大成本更低，代价是衔接精度略差（hack 靠提示引导，prefill 靠协议保证）。若重启 v2，不必限定 Claude。5. **与 #1820 的交集**：切对话回复消失的痛感会被"尽早持久化半截状态"同一机制缓解——一个机制治两个病。

## 落地顺序（实现时）

1. 第 1 层：失败分流 + 类型化异常（纯 GenerationHandler；单测模拟流式中途断流、零输出断流两类）；
2. 第 2 层：ChatService 落草稿 + 立即持久化（复用 MessageNode 候选机制）；
3. 第 3 层 A：注解横幅 UI（重试走现有 regenerate）。

关联：[upstream-analysis.md](upstream-analysis.md)（上游综合分析，含 `41353567` 关闭开关评估）、[peer-apps-benchmark.md](peer-apps-benchmark.md)（rikkahub-agent isUiNotice 参考）。
