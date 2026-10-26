# 工具循环上下文闸 + MediaEncodingCache（设计稿，批次 1）

> 状态：**已落地（2026-10-26，四批提交 `4d32eea84`/`abb2b0bc8`/`d5ef5a3be`/`7396efbc4`）**。
> 来源：Yuihub 3.0.0（`6dcc7224`）吸收项，评估见 `peer-apps-benchmark.md` §四.8 B1/B2、§五表 9/10。
> 与设计的偏差：无（阈值按用户决策取 85/90，非 Yuihub 的 90/97）。真机验收项（§9）待批次验收。

## 1. 要解决什么

两个独立缺口，同批落地（都在 ai/工具循环域，验证基建共用）：

1. **工具循环无上下文闸**：超大工具输出可以无截断直进上下文，长工具链可把窗口撞满。
2. **多图会话每轮重复编码**：图片 base64 编码（解码→EXIF→缩放→JPEG→Base64）无缓存，
   首字延迟随轮次线性恶化。

## 2. 现状盘点（带证据）

### 2.1 截断侧

- `GenerationHandler.maybeTruncateToolOutput`（`GenerationHandler.kt:1387-1431`）：
  固定阈值 `MAX_TOOL_OUTPUT_CHARS = 32K`（:76）、预览 `TOOL_OUTPUT_PREVIEW_CHARS = 4K`（:77）；
  **`:1399` 有 `|| !hasShellAccess` 短路**——无 shell 时超大输出原样进历史
  （理由：截断后的「`cat /tool_outputs/...`」恢复指引依赖 shell，无 shell 则不可恢复）；
  豁免表 `TOOLS_WITHOUT_OUTPUT_TRUNCATION = {search_web}`（:91）完全跳过截断。
- 恢复指引写死 `Use shell to read: cat ...`（:1424-1425），不区分 workspace 是否绑定
  （本仓 `workspace_read_file` 支持 offset/limit 分页，比 shell cat 更省）。
- `WorkspaceTurnBudget`（单轮字符+步数）只覆盖 `workspace_` 前缀工具（:920-938）；
  MCP / 搜索等非 workspace 工具**无任何单步/累计限制**。
- `design-tool-output-budget.md` §5 阶段 2 的「两级限幅（A1）」即本稿 per-call 分级的
  既有记账，本稿是其落地载体。

### 2.2 上下文预算侧

- `ContextBudgetGuard` / `planContextBudget`（`ContextBudgetPlanner.kt:26-124`）已存在：
  有 usage 锚点用锚点，否则 `estimateTokenCount` 本地估算；`Verdict.Exhausted` =
  剩余余量 < 4K。
- **聊天路径不传 `contextGuard`**（`GenerationHandler.kt:395-399`，明确注释：
  「聊天有自动压缩 + contextMessageLimit 兜底，再叠一层硬停会改变既有行为」）；
  唯一传入点 `SubagentRunner.kt:399`（无头）。
- 工具循环里已有 evaluate 挂点（`:872-886`，仅 `contextGuard != null` 时生效），
  判定时机=**执行前**（撞满窗口是硬墙，先停保住已攒结果）。
- `ChatService` 已消费 `GenerationNotice.ContextBudgetExhausted`（`ChatService.kt:1821`，
  子代理路径），聊天接线可复用同一通知族。
- 自动压缩（CompressionPlanner / 检查点）在**轮间**起作用；工具循环**内部**的窗口增长
  （20 个大输出连发）没有防线。

### 2.3 编码侧

- `ai/src/main/java/app/androidrik/ai/util/FileEncoder.kt`：`encodeBase64`（Image :52-81 /
  Video :83-98 / Audio :100-115）每次调用完整重跑 `compressAndEncode`
  （decode + inSampleSize + EXIF 归一 + JPEG q85 + Base64，:117-159）或
  `encodeToBase64Streaming`。历史消息里的图片**每轮请求都重新编码**。
- Yuihub 方案（`FileEncoder.kt`，3.0.0）：`MediaEncodingCache` ——
  `LinkedHashMap(accessOrder=true)` 字节预算 LRU（总 8MB / 单条 2MB，`@Synchronized`，
  超限拒存+逐出），key = `img|video|audio:withPrefix:绝对路径:length:lastModified`，
  另有 `clearMediaEncodingCache()`。含 48 行单测（字节逐出 / 超限拒存 / LRU 顺序 / clear）。

## 3. 与既有决策的关系（两条被有意识修订）

1. **「聊天路径不传 contextGuard」**（`GenerationHandler.kt:395-399`）：本稿修订为
   「聊天也传，但职责收窄为 90% 硬限的**优雅停止**」。理由：该决策的前提是
   「自动压缩兜底已足够」，但压缩在轮间起作用，工具循环内部的增长它看不见；
   且 `Exhausted` 判定=剩余 <4K，此时压缩已显然失败/来不及，硬停不改变正常路径行为。
   自动压缩仍是第一道线（本稿不触碰压缩触发逻辑）。
2. **「无 shell 不截断」**（`:1399`）：本稿废除。理由：截断的目的是保窗口，恢复指引
   应按能力分支（workspace_read_file 分页 / 缩窄参数重跑），而不是用「不可恢复」
   换「不设防」。30K+ 不可恢复的输出进历史，代价（窗口+缓存抖动）高于收益。

不修订的既有决策：`search_web` 豁免的**存在**保留（搜索层已有自己的预算与清理，
见 design-tool-output-budget §A），但语义改为「仅豁免基础档 32K 的文件外落」，
分级收缩档（16K/8K）对豁免表同样生效（高级别时窗口优先级高于豁免）。

## 4. 设计 A：per-call 动态限幅（工具输出，Yuihub `toolOutputBudget`）

```
ratio = estimateTokenCount(messages) / model.effectiveContextLength()
maxChars = when {
    ratio >= 0.80f -> 8 * 1024
    ratio >= 0.65f -> 16 * 1024
    else           -> MAX_TOOL_OUTPUT_CHARS   // 32K，现值不变
}
```

- 挂点：工具循环内 `maybeTruncateToolOutput(tool, result, hasShellAccess)`（`:912`）
  改为传入 `maxChars = toolOutputBudget(messages, model)`；`hasShellAccess` 参数删除。
- 截断恒生效（删 `:1399` 的 `|| !hasShellAccess` 短路）；豁免表仅跳过基础档。
- **提示文案按能力分支**（替换写死的 shell 指引，`:1420-1428`）：
  - 绑定了 workspace：`workspace_read_file(path="/tool_outputs/x.txt", offset=..., limit=200)`
    分页读（比整段 cat 省且带游标）；
  - 有 shell 无 workspace：保留现有 `cat / grep` 指引；
  - 两者皆无：`Output too large to include. Re-run with narrower arguments`
    （无恢复通道时诚实告知，不给假指引）。
  能力判定复用既有事实：workspace 绑定与否调用方已知（`workspaceCwd` 通路）；
  shell 有无 = `toolsInternal.any { it.name == "workspace_shell" }`（现值，算一次传参）。
- 失败调用的错误文本（`:951-957`，含堆栈）**同样受 maxChars 限幅**（现状只对
  workspace 前缀记账不限幅；堆栈可以非常长）。

## 5. 设计 B：85% 紧急卸载 + 90% 硬限（`enforceContextBudget`）

Yuihub 在每次请求前做「把旧工具结果卸载到文件、留 2K 预览」。本仓改造为
**请求期投影**（与华灯投影原则一致：改的是「发给 provider 的列表」，不动 DB/UI 全文）：

- **挂点**：`generateInternal` 内 `buildInternalMessagesWithPlan` 产出
  `internalMessages` 之后、输入 transformer 链之前。此处列表即实发载荷
  （含压缩窗口投影与 memory plan），在其上做估算与改写最准；且 internalMessages
  天然是每请求临时物，改写零持久化副作用。
- **算法**（对齐 Yuihub `GenerationHandler.kt:1066-1132`，阈值收紧为 85/90）：
  1. `estimated = estimateTokenCount(internalMessages)`；`≤ 85% window` → 原样返回（快速路径，每请求一次估算的成本）。
  2. 超 85%：从最旧向新扫描（**保留最近 4 条消息**不动），把消息里
     `UIMessagePart.Tool.output` 的全文（>2K 且未带卸载标记/截断标记的）写
     `tool_outputs/guard-<safeId>.txt`，part 替换为
     `[Context guard: output offloaded] + resultId + 恢复指引 + 2K 预览`；
     每卸载一条重算 estimate，达标即停。
  3. 卸载完仍 > `90% window` → **优雅停止**：置 `stopNotice = ContextBudgetExhausted`
     （复用现有通知族与 ChatService 消费点），不 throw——本仓护栏家族一律
     「设提示、本批结果回填后停本轮」，与 LoopGuard/WorkspaceBudget 一致；
     Yuihub 的 throw IllegalStateException 不采。
- **幂等与成本**：guard 文件按 toolCallId 命名，内容为该结果全文（不可变）；
  文件已存在即跳过写入。快速路径下每请求只多一次 token 估算（本地，微秒级）。
- **卸载标记识别**：预览头部 `[Context guard: output offloaded]` 与既有
  `[Tool output truncated:`（`:1422`）都视为「已是投影」不再处理。
- **不适用范围**：`generateInternal` 也服务标题/翻译（无工具）——无 Tool part 时
  算法自然空转，无需特判。

## 6. 设计 C：聊天路径接入 ContextBudgetGuard

- 调用方（ChatService 聊天入口）构造 `ContextBudgetGuard` 传入（对齐
  `SubagentRunner.kt:399` 的构造方式）；`overheadTokens` 逻辑沿用 `:475-483`
  （无锚点时 system+schema 估算）。
- 挂点不变（`:872-886` 执行前 evaluate，先到先得）；
  `GenerationNotice.ContextBudgetExhausted` 聊天 UI 的呈现与子代理共用户型
  （落地时核对 ChatPage 的 notice→提示条通路，若聊天侧尚无该 notice 的渲染
  分支则补一个，复用 WorkspaceBudgetExhausted 的样式）。
- 锚点：聊天侧暂用本地估算（保守），usage 锚点接入留待后续（不阻塞本批）。
- §2.2 修订声明的落点：更新 `:395-399` 参数注释，写明新契约（自动压缩第一道线，
  guard 只管 90% 硬限的优雅停止）。

## 7. 设计 D：MediaEncodingCache 移植（即插即用）

- `MediaEncodingCache` 类整体移植进 `app.androidrik.ai.util.FileEncoder.kt`
  （私有单例 + `clearMediaEncodingCache()`），参数不动：总 8MB / 单条 2MB /
  accessOrder LRU / `@Synchronized` / 超条拒存。
- 三个 `encodeBase64`（Image/Video/Audio）各加：
  `key = "img|video|audio:$withPrefix:${path}:${length}:${lastModified}"` →
  命中即返回；未命中编码后 put。Image 的 withPrefix 两个形态分别缓存
  （key 含 withPrefix，Yuihub 同款）；Video/Audio 缓存 `EncodedImage(base64, mime)`、
  返回值仍为 String。
- key 含 `length:lastModified` ⇒ 文件更新自动失效，无需失效钩子；
  `data:` / `http` 分支不缓存（前者本就是编码结果，后者未下载）。
- 单测：移植 Yuihub `MediaEncodingCacheTest`（字节逐出/超限拒存/LRU 顺序/clear 四组），
  包名适配。
- 不做：内存水位联动清理、跨进程、磁盘二级缓存（8MB 上界已封顶风险）。

## 8. 常量与参数

| 常量 | 值 | 说明 |
|---|---|---|
| `MAX_TOOL_OUTPUT_CHARS` | 32K（不变） | 基础档 |
| `TOOL_OUTPUT_MID_CHARS` | 16K | ratio ≥ 0.65 |
| `TOOL_OUTPUT_MIN_CHARS` | 8K | ratio ≥ 0.80 |
| `CONTEXT_EMERGENCY_RATIO` | 0.85 | 卸载触发（比 Yuihub 的 0.90 收紧，硬限前置到 90% 留更多余量） |
| `CONTEXT_HARD_RATIO` | 0.90 | 优雅停止 |
| `EMERGENCY_TOOL_PREVIEW_CHARS` | 2K | 卸载预览 |
| `CONTEXT_GUARD_KEEP_RECENT` | 4 | 卸载保护最近消息数 |
| `MAX_TOOL_OUTPUT_CHARS`(cache) | 8MB / 2MB | 编码缓存总/单条 |

## 9. 验证计划

- 单测（`app/src/test`）：
  - `toolOutputBudget` 三档比率边界（64.9/65/79.9/80%）、豁免表在收缩档失效；
  - 截断恒生效：无 shell + 40K 输出 → 进上下文 ≤ maxChars 且文件落盘、回环校验过；
  - 提示文案三分支（workspace / shell-only / 皆无）；
  - `enforceContextBudget`：85% 触发、保留最近 4 条、幂等（二次调用不再写文件）、
    90% 停止通知、已投影标记不重复处理；
  - `MediaEncodingCache` 移植用例四组。
- 编译：`:ai:compileDebugKotlin`、`:app:compileDebugKotlin`、`:app:testDebugUnitTest`
  （注意 baseline 有 2 个既有 DefaultProvidersTest 失败，与本批无关）。
- 真机（延后到批次验收，不阻塞落地）：
  - 绑定 workspace 助手让它 `cat` 一个大文件 ×N 轮 → 观察第二轮起单条输出被压到
    分级档、`tool_outputs/` 出现 guard-*.txt；
  - 无 workspace 会话调 MCP 大输出工具 → 截断 + 「缩窄重跑」提示；
  - 多图会话连发 5 轮 → 日志确认第二轮起 encodeBase64 走缓存（可临时计时日志）。

## 10. 明确不做

- 不动自动压缩触发逻辑/阈值（第一道线维持现状）。
- 不做 LLM 摘要级降级（design-tool-output-budget §6 既有结论）。
- 不给 85% 卸载做 DB 回写（投影不落库；DB 全文是权威，guard 文件只是发送面备份）。
- 不做 usage 锚点接入聊天 guard（保守估算先行，后续单独议）。
- 编码缓存不做设置开关（8MB 上界 + 自动失效，无用户可见面）。
