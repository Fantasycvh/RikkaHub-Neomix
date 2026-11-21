# 脱敏管线：压缩侧密钥再脱敏 + 导出侧内容脱敏

> 状态：定稿已实施（2026-11-21 拍板五项收齐，见 §9；原设计稿 revision 1）。
> 来源：[OpenHands 台账 #6](../../references/other%20projects/openhands.md)（`<CUSTOM_SECRETS>` 兜底再脱敏）+
> [NMoi 台账 §十三 待办④](../../peer-apps-benchmark.md)（内容级脱敏，落库前 vs 导出/备份路径）——两线合并立项（拍板 3）。
> 对应总表 §三 #21（新增，待登记）。

## 1. 背景与泄漏面盘点

| 通路 | 是否外泄 | 本稿处理 |
|---|---|---|
| 压缩请求 → 压缩模型（可配置成与对话**不同 provider**） | **是**：历史中的密钥随摘要请求出门 | **§3 压缩侧（已拍板，本轮范围）** |
| 导出 Markdown/HTML/图片、分享、Web API `/conversations` | 是（产物外发/归档） | §4 导出侧（NMoi ④，边界待拍板） |
| 备份 DATABASE | 是（整库 SQLite 文件，**无法内容级正则脱敏**） | §4.3 能力边界，如实不承诺 |
| 普通 chat 发送 → 对话模型 | 用户意图域（让模型处理配置文件是合法场景） | **不做**（拍板 2） |
| 落库 / UI 渲染 | 本地不外泄 | 不做（除非 §5.1 拍板选「落库前」） |

## 2. 已拍板（2026-11-21）

1. **脱敏对象 = 标记块约定 + 高置信正则白名单混合**；
2. **作用面 = 仅压缩 prompt 侧**（普通 chat 发送不脱敏）；
3. **与 NMoi 待办④ 合并立项**本稿（一套判定引擎，两个作用面）。

## 3. 压缩侧（本轮范围，已拍板）

### 3.1 判定层：`Redactor`（纯函数，无 Android 依赖）

三层按序执行，**全值替换**（不保留前缀残段——不给猜测线索、不留可再匹配片段）：

| 层 | 判据 | 覆盖 | 误伤 |
|---|---|---|---|
| L1 已知密钥登记 | 供应商 `apiKey`/`apiKeys`（按 `splitEffectiveKeys` 同规则拆分）+ 供应商/助手两级 `customHeaders` 的值（≥8 字符），精确字符串匹配 | 自己的密钥被模型复述/回显 | 零（登记值是精确串） |
| L2 高置信正则白名单 | 见 §3.2 初始集 | 历史里出现的**第三方**密钥（用户粘贴） | 白名单成员按「高置信」门槛筛选 |
| L3 私钥块 | `-----BEGIN … PRIVATE KEY-----` 到 `-----END…`（或文本末尾——**截断容忍**）整块替换 | PEM 家族 | 零（块头是强指示） |

**「标记块约定」在本仓的落地语义**：OpenHands 的标记块依附其 secret registry（后端掩码 + 前端兜底）；本仓无该机制，等价落地为 **L1 显式登记 + 脱敏产物统一标记形态**——替换输出一律为自闭合标记 `<redacted kind="…" />`（`credential` / `api-key` / `token` / `private-key`），不携带任何原值片段。

**幂等性（设计约束，不靠识别标记）**：二次压缩时 prior 检查点（已脱敏产物）再进 Redactor——原值已不存在（L1/L2 天然不命中），标记文本不匹配任何白名单形态 ⇒ 原样通过。**约束：标记文本不得包含任何白名单可命中片段**（单测钉住）。

**白名单初始集草案**（§5.3 待拍板增删）：

```
sk-[A-Za-z0-9_-]{20,}                        // OpenAI 系（含 sk-ant- 前缀族）
AIza[0-9A-Za-z_-]{35}                        // Google API key
AKIA[0-9A-Z]{16}                             // AWS access key id
gh[pousr]_[A-Za-z0-9]{36,}                   // GitHub token 家族
github_pat_[A-Za-z0-9_]{20,}                 // GitHub fine-grained
eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}   // JWT 三段式
-----BEGIN [A-Z ]*PRIVATE KEY-----           // 私钥块头（L3 块级）
```

**明确不进白名单**（误伤面）：通用 Bearer 头、任意长 hex/base64 串、数据库连接串（`postgres://user:pass@`——中置信，确有样本再议）。

### 3.2 截断容忍与落点顺序（OpenHands 核心教训的对应关系）

压缩输入链（`ChatService.compressToCheckpoint` → `compressChunk`）：

```
plan.toCompress.map { it.currentMessage }.messagesForAuxGeneration()
  → 逐条 summaryAsText(maxLength = 2000)   ← 先截断
  → chunk.joinToString → contentToCompress ← Redactor 落点（截断之后 ⇒ 标记不会被截断）
  → applyPlaceholders("content" to …, "additional_context" to priorContext)
  → providerHandler.generateText(压缩 provider)
```

- **落点 = `contentToCompress` 拼好后、进 prompt 前**，同一落点覆盖 `additional_context`（priorContext 已是脱敏产物，幂等通过；用户 `additionalPrompt` 不脱敏——用户显式输入与 chat 同信任域，理由同拍板 2）。
- OpenHands 的「闭合标签缺失照样脱敏」在本仓的对应物 = **L3 私钥块的截断容忍**（只有块头无块尾 ⇒ 替换到文本末尾）；L1/L2 是单值匹配，无块概念。
- `attachTraceSection` 痕迹附录若含原文摘录，随检查点落库——**检查点正文出自已脱敏的输入** ⇒ 附录同样只含标记形态 ✓（无例外通路；单测覆盖）。

### 3.3 审计与观察

- Redactor 命中时 `Log.i` 记**计数与类型**（绝不记原值、不记命中值本身）。
- 压缩 trace 附录（`attachTraceSection`）追加一行脱敏计数（确定性附录惯例）——观察期看真实命中频率，为白名单增删供数据（§5.4）。
- 落库 / UI / 导出路径**不感知**（拍板 2：仅压缩 prompt 侧）——压缩产物里的 `<redacted>` 标记是唯一可见痕迹。

### 3.4 与净化（ContextPurifier）的关系

**不合并**。净化 = 发送窗口副本的思考残留清理（chat 发送路径，dry-run P0）；脱敏 = 压缩输入的密钥移除（压缩请求路径）。作用面、判据域、开关都不同——两个独立 transform。防止将来被问「为什么不在净化器里加一条规则」。

## 4. 导出侧（NMoi 待办④，待拍板后实施）

### 4.1 语义边界（§5.1 拍板项）

| 方案 | 效果 | 代价 |
|---|---|---|
| 落库前脱敏 | 全链路生效（UI/导出/Web API/备份产物全净） | **原值丢失**：用户自己粘贴的内容在 UI 也变标记、无法编辑还原、破坏「让模型处理配置」场景——NMoi 台账原文即列为风险 |
| 仅导出/备份路径脱敏（**我倾向**） | 落库原文保留；外发产物脱敏 | 导出可编辑性受损 ⇒ 需要开关；备份 DATABASE 覆盖不了（见 4.3） |

### 4.2 落点（若拍板「仅导出」）

- 导出/分享三路径（`ChatExportSheet`：Markdown/HTML/图片）+ 长按分享 + Web API `/conversations`——同一 Redactor 实例，导出文本出口处调用。
- **设置项**：导出脱敏开关（默认开/关随 §5.2 拍板），预览卡显示命中计数。

### 4.3 能力边界

- 备份 DATABASE 是整库 SQLite 文件拷贝，**不做内容级脱敏**（设计稿如实声明，不造虚假安全感）；
- 若将来出现 JSON 会话导出格式，可复用同一 Redactor（文本出口同构）。

## 5. 待拍板清单（2026-11-21 全部拍板，见括号）

1. **NMoi ④ 语义边界**：落库前 vs 仅导出/备份路径（我倾向后者，理由见 §4.1）——决定 §4 是否立项及形态。**拍板：仅导出/备份。**
2. **导出脱敏默认态**（若拍板仅导出）：默认开（安全优先，用户可关）vs 默认关（导出可编辑性优先）。**拍板：默认开。**
3. **白名单初始集**（§3.1 草案）：确认/增删条目。**拍板：确认（六形态 + PEM 块，原样）。**
4. **L1 登记的密钥源范围**：供应商 apiKey/apiKeys + 两级 customHeaders 值（本稿草案）——账号类 OAuth 凭证**不进登记**（登录态不进对话历史）。**拍板：确认。**
5. **脱敏计数进 trace 附录**（§3.3）：是否要（观察期手段，建议要）。**拍板：进入。**

## 6. 验收

| 场景 | 预期 |
|---|---|
| 自己的供应商 key 被模型复述进历史 | 压缩请求内容中替换为 `<redacted kind="credential"/>`；落库/UI/导出不变 |
| 历史含第三方 key（sk-…/AIza…/ghp_…） | 替换为对应 kind 标记；普通代码对话（`sk` 开头变量名等）不误伤 |
| PEM 私钥块（完整/被截断只剩块头） | 整块（或到文本末尾）替换 |
| 二次压缩（prior 已脱敏） | 幂等通过，标记不二次处理、不被误伤 |
| `summaryAsText` 截断后进 Redactor | 无泄漏（落点在截断之后） |
| 压缩模型与对话模型同 provider | 行为不变（脱敏照跑，幂等无害） |

交付顺序：判定层 `Redactor`（纯函数 + 单测）→ 压缩侧接线（单落点 + trace 计数）→ 导出侧（待 §5.1/§5.2 拍板）。压缩反馈（OpenHands #4）与本稿无耦合，独立排期。

## 9. 实施记录（2026-11-21，本地未提交）

**落地件**：
- **判定层** `data/ai/Redactor.kt`（新）：`Redactor.redact(text, credentials)`（L3 私钥块含截断容忍 → L1 已知密钥最长优先精确匹配 → L2 白名单六形态 lookbehind 防误伤）+ `collectCredentials(providers, assistants)` + `List<UIMessage>.redactedForExport`（Text/Reasoning/Notice + Tool.output 内嵌同类 part；无命中共享实例零成本）。旧版 `ToolResult` 的 content 是 JsonElement，**不在 v1 范围**（非文本 part，导出 JSON 里原样——白名单命中样本出现再议）。
- **压缩侧** `ChatService.compressToCheckpoint`：凭据登记收集一次；`contentToCompress` 截断后脱敏 + `priorContext` 脱敏（历史检查点可能是上线前产物）+ 计数归集；`attachTraceSection` 新增 `redactionCounts` 参数渲染「脱敏 credential×2、api-key×1」审计行（parseTraceBundle 对未识别行跳过，接力不受影响）；审计日志只记 kind×次数。
- **导出侧**：`Settings.exportRedactEnabled`（默认开，PreferencesStore 三处键同步）+ `ChatExportSheet` 三出口（Markdown/HTML/图片）用脱敏副本 + 完成提示「已脱敏 N 处密钥」+ 设置页开关（SettingPreferencesUIPage 导出区，文案暂硬编码中文——周边条目走 stringResource，此处是后续 i18n 漏网点）。**JSON 归档导出（`exportToConversationArchive`）与长按分享走归档路径不脱敏**——可回读产物属备份语义（§4.3）；抽屉/列表长按分享走同一 sheet（archive 路径）。
- **Web API**：`ConversationRoutes` 三处 toDto（GET 单会话 / 更新响应 / 流推送）过 `redactedForWeb`——流推送凭据**流开始时取一次**（事件频率随生成节拍，流内全量正则不可承受）；Web 服务是 LAN 暴露面（NSD 注册），与本地导出同域处理。

**语义定案（实施中）**：
- 私钥块先于 L1/L2（防块内 base64 体被白名单误计）；L1 最长优先交替（防短 key 是长 key 前缀的半截替换）；幂等靠标记零残留设计约束（单测钉住），不靠识别。
- `redactedForExport` 无命中的消息/part **共享原实例**（快速路径零成本，Web 流高频调用依赖此）。

**验证**：`RedactorTest` 14 用例（三层判据/误伤负例/截断容忍/幂等零残留/消息级/collectCredentials/legacy ToolResult 范围外声明）；两模块全量 3686 项绿（唯一失败仍是既有 `ImportFormatTest`）。未做真机验证（挂账：导出实弹 + Web API 实弹 + trace 观察期计数）。
