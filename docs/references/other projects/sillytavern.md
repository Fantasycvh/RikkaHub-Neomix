# SillyTavern 注入 / 预设借鉴评估

> 评估：**2026-09-16 ～ 09-21**（源码级，原 §六；含 6.8 / 6.11 / 6.12 与 Tier 4 的落地记录）。
> 来源：`<work>\projects\SillyTavern\`（v1.19.0，`06bde939f`；核心 `public/scripts/world-info.js` 6408 行、`openai.js` 7396 行）。许可证 AGPL-3.0。
> 定位：**同域竞品**（注入/预设/世界书），但只借「不是面板」的那部分——世界书激活质量 + 预设槽位序。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 六、SillyTavern 注入 / 预设评估（2026-09-16，源码级）

**来源**：`<work>\projects\SillyTavern\`（v1.19.0，`06bde939f`；核心 `public/scripts/world-info.js` 6408 行、`openai.js` 7396 行）。

**提问背景**：用户判断「ST 的预设原本好好的，后来都去搞花里胡哨的各种面板」——问 ST 的**注入与预设**部分若借鉴，值不值得。**结论先行：用户这个判断对，而且它恰好划出了分界线——ST 值得抄的恰恰是「不是面板」的那部分（世界书激活质量 + 预设的槽位序），需要避开的正是后来堆上去的那些字段与面板。**

### 6.1 世界书：fork 13 字段 vs ST 35 字段，但差距不在数量

fork 的 `PromptInjection.RegexInjection`（`Assistant.kt:226-240`）共 13 字段：
`id/name/enabled/priority/position/content/injectDepth/role` + `keywords/useRegex/caseSensitive/scanDepth/constantActive`。

ST 的 `newWorldInfoEntryDefinition`（`world-info.js:4079-4124`）共约 35 字段。**逐项归类后差距收敛为「4 个该抄、一批该避、其余无关」**：

| 类别 | ST 字段 | 判断 |
|---|---|---|
| **该抄** | `keysecondary` + `selectiveLogic`（4 模式）、`world_info_budget` + `ignoreBudget`、`scanDepth` 的**多源扫描**（`matchCharacterDescription`/`matchPersonaDescription`/`matchScenario` 等 6 个 flag） | 见 6.2 |
| **该避** | `sticky`/`cooldown`/`delay`、`probability`/`useProbability`、`group`/`groupWeight`/`groupOverride`/`useGroupScoring`、`vectorized`、`outletName`、`automationId`、`matchWholeWords`、`addMemo`、`position` 的 EM/AN/outlet 三段 | 见 6.4——**这就是用户说的「花里胡哨」** |
| **无关** | `characterFilterNames`/`characterFilterTags`/`displayIndex`/`uid`/`triggers` 等 ST 内部记账 | 不涉及 |

### 6.2 值得借鉴（按价值排序）

**① 次要关键词 + 选择逻辑（`keysecondary` + `selectiveLogic`）——最推荐，且是「导入保真」问题而非锦上添花**

这是世界书的**核心质量机制**：主键命中后，再用次要键二次收窄。ST 有四种逻辑（`world-info.js:33-38`）：
`AND_ANY`(0，任一次要键满足) / `NOT_ALL`(1) / `NOT_ANY`(2) / `AND_ALL`(3，全部满足)。

**关键事实：fork 完全没有这个概念，而 ST 的 `selective` 默认是 `true`**（`world-info.js:4089`），
第 4925 行判据是 `entry.selective && entry.keysecondary.length`——**即 ST 世界里大量条目依赖次要键来抑制误触发**。

⇒ **后果是确定的：fork 导入 ST 世界书时丢弃 `keysecondary`，这些条目会在只命中主键时照常触发（过度注入）**。
导入者看到的不是「少了个可选项」，而是**同一个世界书在 fork 里比在 ST 里注入更多、更乱的内容**——直接推高 token、稀释 prompt、且难排查（因为看起来「规则都在」）。

**成本**：模型 +2 字段（`keysecondary: List<String>`、`selectiveLogic: 枚举`）、`isTriggered` 改纯函数（主键命中 → 查次要键 → 按 4 模式判定）、UI 加一段次要键编辑 + 逻辑下拉、导入映射补两行。**估：约 1 提交**（含单测：4 种逻辑 × 主/次键命中的真值表）。

**② 关键词的扫描源不止「聊天消息」（ST 的 6 个 `match*` flag）——同一类「导入即行为改变」问题**

fork 的 `extractContextForMatching`（`Assistant.kt:291-298`）**只扫描消息文本**：
`messages.takeLast(scanDepth).joinToString { it.toText() }`（且调用处先 `filter { role != SYSTEM }`）。

ST 则允许条目声明**额外从哪些非对话内容里找关键词**（`newWorldInfoEntryDefinition`）：
`matchPersonaDescription`（用户人设）、`matchCharacterDescription`（角色描述）、`matchCharacterPersonality`、
`matchScenario`、`matchCharacterDepthPrompt`、`matchCreatorNotes`。

⇒ **后果同 ①：条目在 ST 里能被角色描述触发的，在 fork 里永远不触发**（关键词只出现在
`description`/`scenario` 里而未出现在聊天中时）。这是**漏注入**（与 ① 的过度注入方向相反），
二者合起来说明「导入 ST 世界书」目前在 fork 中是**行为不等价**的。

fork 的相关内容在角色卡导入时被压进 `systemPrompt`（见 ③），**源码里拿得到**（`assistant.systemPrompt`
含 Description/Personality/Scenario 段），所以实现上只需把匹配源从「消息列表」扩为「消息列表 + 指定助手字段」，
**不需要新解析层**。估：约 1 提交（与 ① 可合并同批做，因都改 `isTriggered`/上下文组装）。

**③ 世界书 token 预算（`world_info_budget` + `budget_cap` + 条目 `ignoreBudget`）——fork 现在零预算**

ST 的预算语义（`world-info.js:4736-4743`）：`budget = round(world_info_budget × maxContext / 100)`，
默认 **25%**，另有绝对值上限 `world_info_budget_cap`（0=不设）；超预算的条目**被丢弃**，
按 `order` 排序后处理，`ignoreBudget` 的条目豁免。**激活是「竞争预算」的**（第 5024-5040 行）。

**fork 现状：注入侧完全没有预算/裁剪**（`PromptInjectionTransformer.kt` 内检索 token/budget 为零命中，
只有一处 `takeIf` 是索引判断）。⇒ 世界书条目一旦多起来（或导入一个大型 ST 世界书），
**全部命中条目无条件进 prompt**，无任何上界。

**本仓已有现成范式可抄**：`memoryInjectBudgetTokens`（`PreferencesStore.kt:701`）就是同一形状
（token 计 + `Settings` 三件套 + `estimateTokenCount` 复用，见 `design-memory-scoring.md`）。
**估：约 1 提交**（预算字段 + 按 `priority` 排序截断 + 设置项 + 6 语言）。

**④ 预设的「槽位序」思想——只借思想，不借格式**

ST 预设（`default/content/presets/openai/Default.json`）的骨架是：
`prompt_order`（**有序槽位**，按 `character_id` 分组）+ `prompts`（identifier → `{name, role, system_prompt, content}`）。
真正漂亮的地方是 **`chatHistory` 只是这个有序列表里的一个槽位**：

```
main → worldInfoBefore → charDescription → charPersonality → scenario
     → worldInfoAfter → dialogueExamples → chatHistory → jailbreak
```

即「系统提示 / 角色描述 / 世界书 / 聊天历史 / 尾部指令」**是用户可拖拽重排的一维序列**，而非代码里写死的拼装顺序。

**fork 对照**：装配顺序**硬编码**——`before-system 注入 → assistant.systemPrompt（一整条巨串）→ after-system 注入`，
再接 `TOP_OF_CHAT → 历史 → BOTTOM_OF_CHAT/AT_DEPTH`（`applyInjections`）。
而角色卡导入（`AssistantImporter.kt:157-196`）更是把 `description`/`personality`/`scenario`/`mes_example`
**用 `buildString` 压成一条 `systemPrompt` 字符串**——**槽位信息在导入那一步就丢了**，之后无法单独重排或开关。

⇒ **值得借的是「顺序应当是显式的、可重排的」，不值得借 ST 的整个 prompt manager。**
但要注意：**这与 #1542「统一上下文插件」是同一个问题**，我在 `upstream-analysis.md` 已判「全量重写＝架构级（周级起）、不排期」。
所以此处**只建议窄义动作**：至少别在角色卡导入时把 4 个字段压成一条不可分的字符串
（保留为独立部分或独立注入条目），代价很小、且为将来留出重排可能。**不做全量 prompt 管理器。**

### 6.3 顺带发现的现有 ST 导入器缺陷（可立即修，与「借鉴」无关）

核实 `ExportSerializer.kt:166-204` 的 `tryImportSillyTavern`，发现**三处保真问题**：

1. **`useRegex = false` 且注释写错**：第 187 行注释「SillyTavern 格式不支持 useRegex」——**事实相反**。
   ST 的 key 支持 `/pattern/flags` 写法（`parseRegexFromString`，`world-info.js:2901-2927`：
   正则匹配优先于其它所有匹配选项，`world-info.js:337-341`）。**该注释是错的**，且导致带正则键的条目
   被当作**字面量关键词**匹配 → 触发条件整体改变。修法：识别 `/.../flags` 形态并置 `useRegex`。
2. **位置映射有损**：`mapSillyTavernPosition`（第 197-205 行）把 ST 的 `2`(ANTop) 与 `3`(ANBottom)
   **都映射为 `TOP_OF_CHAT`**。ST 的 AN 是独立的「作者注释」锚点（非「聊天开头」），二者语义不同 —— 映射后位置含义变了。
3. **`keysecondary`/`selectiveLogic` 丢弃**：见 6.2 ①，属过度触发。

> 这三条里 1、2 是纯 bug（改映射与解析即可），3 是「要么补字段、要么至少在导入时提示会失真」。
> **即便最终不借鉴任何 ST 机制，这三条也值得修**——它们让「导入 ST 世界书」这个既有功能的产出与源不一致。

### 6.4 不建议照搬（正是用户说的「花里胡哨」）

| ST 机制 | 为何不抄 |
|---|---|
| `sticky` / `cooldown` / `delay` | 需**会话级持久化状态**（ST 存 `chat_metadata.timedWorldInfo`，`world-info.js:508-530`）；fork 的 `Conversation` 无对应 meta 列，且「条目激活持续 N 轮/冷却 M 轮」的跨轮语义与 fork 的**压缩窗口/区域指纹**机制正交、易互坏。**复杂度全在状态机与持久化，收益是极少数场景** |
| `probability` / `useProbability` | **非确定性激活**——同一输入两次请求结果可能不同，直接破坏前缀缓存、且让「为何这条没生效」无法复现。与 fork 已确立的「前缀稳定」方向相反 |
| `group` / `groupWeight` / `groupOverride` / `useGroupScoring` | 「同组只取一条（按权重）」——偏创作型玩法，用户基数小，且需再引入权重抽取与组评分 |
| `vectorized` | 语义检索。**本仓已三方独立结论「不做」**（见 §四、`design-auto-compaction.md` §6.3） |
| `outletName`（outlet 位置） | 依赖 ST 的扩展/插件生态来消费该锚点；fork 无此体系 |
| `automationId` | 依赖 ST 的 automation 系统 |
| `matchWholeWords` / `addMemo` | 边缘开关（整词匹配 / 备忘标记），价值低 |
| `position` 的 EM/AN 三段 | EM=example messages 锚点、AN=作者注释锚点。fork **无「示例消息区」这一独立区块**（角色卡的 `mes_example` 已并入 systemPrompt），没有可挂靠的锚点 → 抄了也无处可插 |

**这张表就是「ST 后来堆上去的东西」**：它们多数要么需要额外的持久化/生态，要么引入不确定性，
**共同点是「字段数增长很快、实际用到的很少」**——正是「又大又不好用」的来源。fork 的窄字段集
（13 个）反而更接近 ST 早期「预设原本好好的」的形态。

### 6.5 另注：ST 已有 `assistant_prefill`，印证 #1918 评估

ST 预设顶部有 `assistant_prefill` 字段（`Default.json`；实现 `openai.js:1325`）——**但它同样只对
Claude 生效**（`supportsAssistantPrefill = chat_completion_source === CLAUDE`，第 1323 行）。
这与 `upstream-analysis.md` #1918 节的结论一致：**「末尾 assistant 预填充」是各家 provider 语义
不一致的能力**（Claude 原生支持、Chat Completions 需 `prefix:true`、Gemini 用 `model` role），
ST 也是靠「仅对支持的源开放」来回避。⇒ **#1918 的判断得到独立印证，不改结论。**

### 6.6 建议顺序（若动手）

1. **修 6.3 的三个导入缺陷**（① 正则键识别、② 位置映射、③ 次要键丢弃的替代处理）——小、纯正确性、无架构风险；
2. **补 `keysecondary` + `selectiveLogic` + 多源扫描**（6.2 ①②，可合并同批，都改 `isTriggered`/上下文组装）——世界书质量的核心，且直接消除「导入即行为不等价」；
3. **补 token 预算**（6.2 ③）——抄 `memoryInjectBudgetTokens` 现成范式，给世界书一个上界；
4. **角色卡导入不再压平 4 字段**（6.2 ④ 的窄义版）——小改动、为将来留白；
5. **全部跳过 6.4**；**preset 管理器不做**（= #1542 全量，已判不排期）；
6. **加「拼装后提示词预览」**（见 6.7 ③）——纯新增、零行为改动，且是上面各项（位置/缓存/截断）能被**看见与验证**的前提。

**总估**：第 1 项半天；2、3 各 1 提交；4 半天；6 约 1 提交（只读拼装，不碰生成）。**全部落在现有架构内，不动 provider、不动压缩线。**

### 6.7 社区讨论补充（2026-09-17，结合群聊截图）

> 来源：用户提供的两张群聊截图（讨论酒馆类应用/「类脑」预设与 Tavo）。以下四条**逐条对到 fork 代码**核对，其中第 ④ 条与群聊结论**相反**，已给出依据。

**① 「做精简一点就可以了，主要是注入，很多都用不到」+「ST 预设没用的东西太多，我都剔除好多」——§6.4 该避清单得到社区印证**

截图里那位用户的做法就是「自己删掉 ST 绝大部分字段，只留最常用的注入项」。这与 §6.4 的结论同向，**不需要改判断**。额外收获是它确认了「ST 的问题不是字段少，而是字段多到用户要手工做减法」。

**② 「图片生成那些东西都直接做成工具算了，再注入这注入那的，麻烦成狗了」——原则对，且 fork 现状已优于 ST**

该说法针对的是 ST 里「靠注入/提示词去驱动图片生成」的做法。核实 fork：

- 图片生成是**独立页面**（`ui/pages/imggen/ImgGenPage.kt` + `GenMediaEntity`），**不是注入**；
- 另有 provider 侧能力标志 `BuiltInTools.ImageGeneration`（`ai/.../provider/Model.kt:60`），只被
  `ResponseAPI.kt:288`（OpenAI Responses 原生 image_generation）与设置页/Web 路由消费，
  **不是普通聊天里模型可调用的 Kotlin `Tool`**。

⇒ 结论：**fork 不在这个问题上**（既没走注入，也没做成工具）。**是否补一个 `generate_image` 工具属独立议题**（要同时考虑 provider 原生能力与自建图片接口两条路），**本轮不建议顺带做**。

**③ 「Tavo 给了实际拼装后提示词，用来优化预设」——这是最有价值的一条，fork 完全没有**

核实：全仓**无**「拼装后提示词预览」实现——grep `promptPreview`/`assembledPrompt` 等**零命中**，
`web-ui/` 亦无。现有的**唯一**可见途径是 `LogPage`（`ui/pages/log/LogPage.kt`）里的原始 HTTP
请求日志（`RequestLoggingInterceptor.kt:23` 捕获完整 `requestBody`），但它有三个硬伤：

1. **要先开两个开关**（`log_page_record_requests` + 写文件），默认不开；
2. 展示的是**原始 JSON**（provider 协议形状），不是「按槽位/按来源」的可读视图——看不出
   哪段来自 systemPrompt、哪段来自世界书、哪段是压缩检查点合成消息；
3. **事后**属性：要真发一次请求才能看。

**为什么这条价值最高**：§6.2 ③（预算）、§六 6.7 ④（位置与缓存）、以及 `upstream-analysis.md`
「注意点」第 8 条（#1542 前缀稳定性）**全都要先能看到拼装结果才好判**。现在用户想调世界书位置、
想知道自己的 `{{cur_date}}` 是否在毁缓存，只能翻原始 JSON。
**建议形态**：复用 `PromptInjectionTransformer` 之后的**同一条 transformer 链**做一次只读拼装
（`messages` → 各 `InputMessageTransformer` → 渲染），**只读、不落库、不发请求**；按 stage
标注来源（system / 注入名 / 世界书名 / 检查点 / 历史），暴露为设置页或调试页入口。
**成本**：约 1 提交（复用现成链，主要是渲染与来源标注）。

**④ 「历史消息放在世界书前面 → 世界书都命不中」——观察属实，但值判断是反的；fork 的默认位置才是该改的那个**

先把 fork 的机制核实清楚（这是判断依据）：

| 事实 | 位置 |
|---|---|
| `RegexInjection`（世界书条目）**默认位置 = `AFTER_SYSTEM_PROMPT`** | `Assistant.kt:231` |
| 该位置**改写 system 消息本体**（`result[systemIndex] = … copy(parts = Text(新串))`） | `PromptInjectionTransformer.kt:128-156` |
| fork 的 Claude 集成**有显式缓存断点**：① system 最后一个 block、② **倒数第二条真实 user 消息**的最后一个 block（另有顶层自动断点） | `ClaudeProvider.kt:293-320`、`390-430` |

**推论（这是关键）**：世界书条目**会随对话滑动而频繁启停**（`scanDepth` 默认仅 4，窗口一滑出就失活）。
在 fork 的默认配置下这等于：**system（位置 0）每几轮就变一次 → 两个缓存断点同时失效 → 整段历史重算**。

而「把世界书放到历史之后（尾部）」时：system 稳定不动 → 断点 ① 命中；世界书插在最后一条消息之前，
**断点 ②（倒数第二条 user）仍在世界书之前** → 历史整段命中，只有「世界书 + 新消息」这一小段重算。

⇒ **群聊那句「世界书都命不中」在事实上成立**（世界书在尾部时，它总落在「新增 token」区、永远进不了缓存前缀），
**但它优化的对象选错了**：世界书条目通常**只有几百 token**，而历史是**几万 token**。
「牺牲小世界书的缓存，换取整段历史的缓存」是对的；反过来（世界书放前面）则会让世界书的每次启停
**掀掉整个历史缓存**。**正确规则是「稳定在前、易变在后」，世界书属易变项 ⇒ 应靠后。**

**这与 fork 自己的两条既有先例一致**（可直接引用为设计依据）：
- **Yuihub 的分段轨迹**把世界书触发做成「**当前轮次尾部补充段**」（见 `upstream-analysis.md` 注意点第 8 条引述）；
- 上游 **PR #1630** 推出的正是「turn-tail 注入」这一缓存友好方向。

**⚠️ 同一根因的既有实例（fork 自带）**：默认助手的 systemPrompt 含 `- Date: {{cur_date}}`
（`PreferencesStore.kt:941`），而 `PlaceholderTransformer` **每次请求都重写占位符**（`:61`、`:130`）
→ **每天（乃至任何触发重算时）从位置 0 作废整段缓存**。**它与「世界书放 system」是同一个 bug 的两个面**：
**位置 0 放了易变内容**。

**建议（不静默改质量默认）**：位置影响模型行为（靠前/靠后对效果有主观差异），**不宜悄悄改默认值**。
可行做法按代价从小到大：
1. **先做 6.7 ③ 的预览**，让「世界书落在哪、system 是否在变」变得可见、可自证；
2. 文档/UI 提示把**易变注入**（关键词触发型世界书）优先放 `BOTTOM_OF_CHAT`/`AT_DEPTH`，
   把 `constantActive` 的稳定条目留在 `AFTER_SYSTEM_PROMPT`；
3. 单独评估 `{{cur_date}}` 的迁位（该值不需要在 system 里，属可安全后移的项）。

**⑤ 「做成和 ST 里一样可开关的吗」——结构槽位缺，但「逐条开关」其实已经有了**

截图那台设备展示的是 ST-PM 风格的**槽位列表 + 每项开关**（Main Prompt / 框架认知 / 创作结构 / 构思 /
转场扩写续写 / 结尾摘要 …）。核实 fork：

- **「逐条开关」fork 已具备**：每条 `ModeInjection`/`RegexInjection` 自带 `enabled` 字段
  （`Assistant.kt:485`/`:501`，基类 `:470`），UI 上就是每条注入的开关——**截图里那种「一堆可开关的提示项」，
  fork 用模式注入就能表达**；
  （**2026-09-19 细化**：条目开关有两套语义——改全局 `enabled`（预设详情页，`97aed655`）与
  改本助手绑定（选择器详情，`d44e90f3`），二者是「与」关系；见 §6.11 ④。）
- **缺的是「结构槽位」**：ST 的 `prompt_order` 里 `charDescription`/`charPersonality`/`scenario`/
  `dialogueExamples`/`chatHistory` 是**可单独开关与重排的槽位**；fork 这四项被角色卡导入
  **压成一条 `systemPrompt` 字符串**（`AssistantImporter.kt:167-195`）→ **无法单独开关，也无法重排**；
- **也没有统一视图**：fork 的注入分散在两处（设置页的模式注入/世界书、助手详情的绑定），
  没有「一条按拼装顺序排列、每项带开关」的总览。

⇒ 这条与 §6.2 ④（槽位序）是同一件事。**最小改法是 §6.2 ④ 的窄义版**（导入不再压平），
**统一槽位视图可与 6.7 ③ 的预览合并做**（预览天然要按顺序列出各段，加开关即成为「槽位管理器」）——
**这是把 ③ 和 ⑤ 合并成一次实现的机会**，也是本组建议里性价比最高的组合。

### 6.8 落地记录（2026-09-17）

按 6.6 顺序动手，已完成**第 1 项（6.3 的两个真 bug）**与 **6.7 ③（拼装预览）**：

**① 拼装预览（6.7 ③）——`f879a222`**

- **抽取共享组装 `data/ai/PromptAssembly.kt`**：把原先**内联在 `GenerationHandler.generateInternal`**
  的系统消息组装与发送窗口抽成 `buildSystemPromptText` / `buildSendWindowMessages` / `buildInternalMessages`。
  **真实请求改调同一函数** ⇒ 预览不可能漂移（这是本次实现的核心手法——若预览另写一份组装，
  它会随时间与真实请求分叉，比没有预览更糟）。
- **`data/ai/PromptInspector.kt`**：`inspectPrompt()` 走同一组装 + **同一个 transformer 链**，
  把结果按来源标注为 `SYSTEM` / `COMPRESSION_CHECKPOINT` / `INJECTION` / `CHAT_HISTORY` 段。
  **只读**：不落库、不发请求。检查点识别复用 `isCompactionCheckpointPreamble`
  （**不能只看 `isSynthetic`**——它是 `@Transient`，持久化后丢失，见 15142509 踩坑）。
- **入口**：聊天页附件面板（「更多选项」）内，「压缩历史」下方新增「拼装后提示词」。
  页面显示：概览（分段数 / 估算 token / **历史 N / M** / 上下文上限）+ 缓存提示卡 + 每段来源卡 + 说明卡。
- **单测 13 项**（`PromptAssemblyTest`）：钉住组装契约（system 在前且标 synthetic、会话级提示词
  仅在助手允许时覆盖、工具 schema 与记忆仅在启用时注入、上下文截断保留尾部、**检查点不参与截断**、
  幂等性、**历史计数不含 system/注入**）。
- **实现中自查出一个真 bug**（真机截图发现）：概览的「消息条数」把**带占位符的 value 串当标签用**，
  渲染出裸 `%1$d / %2$d`；且原口径把 system 计入历史，空会话显示「1 / 0」。
  已改为独立 label 键 + 只数历史（空会话 = `0 / 0`）。

**② ST 导入器两处真 bug（6.3 ① ②）**

- **`useRegex` 修正**：删掉错误注释「SillyTavern 格式不支持 useRegex」——ST 的 key **支持**
  `/pattern/flags`（`parseRegexFromString`，`world-info.js:2901`，且正则**优先于**其它匹配选项）。
  新增 `parseStKeywords()`：识别该形态 → 置 `useRegex=true` 并去掉包裹（`\/` 还原为 `/`）。
  **代价已知**：fork 只有一个 `useRegex` 开关，混合列表无法精确表达 ⇒ 整体走正则语义（宁可如此，
  也不要丢掉正则语义——那会让触发条件整体改变）。同时对齐 ST 的拒绝规则（未转义分隔斜杠不算正则）。
- **AN 位置映射修正**：原实现把 `ANTop(2)`/`ANBottom(3)` **都**映射为 `TOP_OF_CHAT`，
  且注释误写为「After Examples」。核实 ST：两者是包在**作者注释**前后（`world-info.js:5270`），
  而作者注释默认在 **in-chat depth 4**（`authors-note.js:272-273`：DEFAULT_POSITION=1=IN_CHAT、
  DEFAULT_DEPTH=4）——**不是对话最开头**。改映射为 `AT_DEPTH`（配合 `entry.depth`）。
  **诚实损失**：fork 无「作者注释」锚点，故 ANTop/ANBottom/atDepth 三者不再可区分。
- **单测 11 项**（`SillyTavernKeywordParseTest` 7 + `SillyTavernPositionMapTest` 4）。

**③ 六项全部落地（2026-09-17，同日第二批）**

| 批次 | 内容 | 提交 |
|---|---|---|
| A | **世界书次要关键词 + 选择逻辑**（`secondaryKeywords` + `SelectiveLogic` 四模式） | `672f4e83` |
| B | **关键词多源扫描**（`matchAssistantPrompt`，对应 ST 的 6 个 `match*`） | `67844bcb` |
| C | **世界书注入 token 预算**（`limitByTokenBudget`，默认 0 = 不限制） | `2325a327` |
| D | **角色卡导入保留分区字段**（`card*` 五个可空字段 + 重建按钮） | `c4bbd2d5` |
| — | **修：拒绝非世界书文件**（真机发现的静默失败，见下） | `7f8fa46b` |

**A（过度触发）**：`isTriggered` 改为「主键命中 → 有次要键才按逻辑二次判定」；主/次键共用
`matchesKeyword()`（大小写/正则/坏正则按不命中语义一致）。**次要键为空 = 跳过第 2 步**，
存量条目零回归。ST 导入侧补 `keysecondary`/`selectiveLogic`/`selective` 三个字段（**必须
`@SerialName` 对齐小写名**——此前 `ignoreUnknownKeys` 使其**静默丢弃**，这正是过度触发的成因）。
保真细节：ST 的 `selective=false` 时不做二次判定 ⇒ 次要键整体作废，且**必须在算 `useRegex`
之前作废**（否则次键的正则会抬起 `useRegex`，连累主键字面量按正则匹配）。

**B（漏注入）**：`extractContextForMatching` 加 `extraTexts`（追加而非替换，`scanDepth` 仍只作用于
消息）。**刻意用 `assistant.systemPrompt` 而非最终发出的 system 消息**——后者含记忆/工具 schema/
渲染提示，是请求期合成的易变内容，纳入扫描会让条目随机命中自己或工具描述。ST 的 6 个 `match*`
在 fork 无对应字段（已压进一条 `systemPrompt`）⇒ **任一为真即并成一个开关**，诚实的近似，
代价是无法只扫其中某一段。

**C（零预算）**：`limitByTokenBudget()` 按 priority 降序取舍；超预算**跳过本条继续**而非 `break`
（一条超长条目不该堵死后面）；首条即使超预算也入选（避免「预算极小 → 一条都不注入」）；
**常驻条目（`constantActive`）不计预算、不淘汰**（用户显式声明「永远要在」的东西被预算淘汰很反直觉）。
**默认 0 = 不限制**（保持旧行为）——刻意不照搬 ST 的 25%：那会**静默**改变用户既有注入量。

**D（槽位丢失）**：Assistant 加 `cardDescription`/`cardPersonality`/`cardScenario`/
`cardExampleDialogue`/`cardSystemPrompt` 五个**可空**字段（`@Serializable` ⇒ **零 DB 迁移**）。
渲染抽成纯函数 `buildCardSystemPrompt()`，**与原内联 `buildString` 逐字节等价**（有特征测试锁死）。
**权威关系刻意保持单一**：`systemPrompt` 仍是唯一被发送的文本（保持既有行为与自由编辑），
`card*` 只是「零件」+ 一个显式重建按钮——若让零件参与组装就会出现两个真相源，且用户手改
`systemPrompt` 后会被静默覆盖。

**⚠️ 真机发现的既有静默失败（`7f8fa46b`）**：装机验证时看到设备上两个世界书都是「0 条目」，
其中「【Tavo酒馆通用】柯尔的妙想_v1.5」实为**用户导入的 ST 预设文件**（不是世界书）。根因：
`SillyTavernLorebook.entries` 声明为**默认空 map** + `ignoreUnknownKeys = true` ⇒ **任意 JSON
都能解码成「0 条目的世界书」并静默导入成功**。这比直接失败更糟（用户以为成功了）。改为
`entries: Map<...>? = null`（无默认值）：缺失该键 = 不是世界书 → 返回 null → 报「格式不支持」。
**刻意保留「ST 世界书但 0 条目 = 合法」**（空世界书是合法状态）。

**验证**：新增 54 项单测（A 23 / B 10 / C 9 / D 5 / 拒绝 7，含**真值表**与**特征测试**）；
app 模块 662 项仅剩基线 `MarkdownMathParseTest` 失败（`git stash` 双向核对确认与本次无关）；
真机 `<device>` 逐项目视验证：预算卡渲染「不限制（0）」、编辑条目弹窗出现**次要关键词**区
+ **匹配逻辑 = 任一次要词命中**、`0 条目` 的世界书确认为静默失败实例；**数据零损失**
（install -r，uid/size/upload/skills 全不变）。

**④ 槽位管理器已合并落地（2026-09-17，`d2400452`）**——6.7 ⑤ 与 6.2 ④ 合并成一次实现：

角色卡字段区升级为「提示词分段」**槽位视图**，对齐 ST `prompt_order` 的槽位概念：

- 新增 `CardSlot` 枚举（greeting / system_prompt / description / personality / scenario / example）
  + `Assistant.cardSlotOrder`（**空 = 默认序**，故存量助手自动获得未来新增槽位、无需迁移）
  + `disabledCardSlots`。
- `buildCardSystemPrompt` 泛化为「按槽位列表 → 块列表 → `\n\n` 连接」，
  **与原 `buildString` 逐字节等价**（特征测试锁死）。
- **「关闭槽位」与「内容为 null」是两件事**，刻意保留区分：null → 渲染 `Empty` 占位符（历史行为），
  关闭 → 整段不出现。
- `cardPartsDivergeFromSystemPrompt()`：字段改了但 `systemPrompt` 未重建时给提示——
  权威仍是 `systemPrompt`，不提示的话用户改完字段发现请求没变会以为坏了。
- UI 用 `ReorderableColumn`（与本页正则区同款，已解决嵌套滚动）；每槽 = 拖拽手柄 + 序号 +
  独立开关 + 内容框；开场白只给开关（内容由姓名插值）；关闭的槽位仍可查看内容（灰显）。

**真机验证**：导入测试角色卡后 6 个槽位全部渲染，关闭「场景」后开关灰显且漂移提示出现。

**至此 ST 借鉴清单（6.2 四项 + 6.3 三缺陷 + 6.7 ③⑤）全部清零。**

### 6.11 预设中心化（2026-09-17 第三批：`d0f15e97` / `f2f6f50e` / `1395270a`）

用户指令：**UI 明说「优先级更高者在前」、支持预设导入、把整个扩展管理建立在注入（预设）之上**；
世界书暂不重要，之后再做。

**① 预设容器（`d0f15e97`）——容器化的同时不动下游**

新增 `PromptPreset { id, name, description, enabled, entries }`（`entries` 仍是原 `ModeInjection`）。
关键手法：`Settings.promptPresets` 是**持久化字段**，而 `modeInjections` 改为**计算属性**
（`flatMap { it.entries }`）⇒ `PromptInjectionTransformer`、Web API 的 id 校验、web-ui 的勾选列表、
`ExtensionSelector`、`AssistantExtensionsPage` **全部零改动**。全仓只有 1 处写 `modeInjections`。

持久化用**回退读**而非迁移器：新 key `prompt_presets` 优先，旧 key `mode_injections`
包成单个预设兜底 ⇒ 老用户原地可用；写入只落新 key。

绑定为**预设级 + 条目级并集**：`Assistant.promptPresetIds`（可空集合 ⇒ 零 DB 迁移）
与 `modeInjectionIds` 并存，并集后 `distinctBy { id }`（同一 id 走两条路径会注入两次）。
预设绑定刻意做成**助手级、不随会话覆盖**（表达「这个助手基于哪套方案」，与会话级逐条勾选正交）。

配套 `reconcileEntries()`：扁平编辑写回容器时按 id 归位、**并按新序重排**（否则拖拽顺序不生效）、
跨预设重复 id 先到先得。

**② 排序语义（`f2f6f50e`）——priority 为唯一来源**

原先拼装按 `priority` 降序，而列表展示的是存储顺序 ⇒ 给某条设过优先级后**拖拽即失效**
（「看起来能拖、实际没变化」）。现改为：列表按 `priority` 降序展示，拖拽后
`normalizeEntryPriorities()` 把视觉序（top→bottom）写回 `n-1 … 0`
⇒ **显示顺序 == 生效顺序**恒成立，并在 UI 明说（用户指定的做法）。

**③ 预设列表/详情 + ST 预设导入（`1395270a`）**

- `PromptPage` 两级：预设列表（拖拽 + 行内开关 + 条目数）→ 详情（条目增删改 + 拖序 + 逐条开关）。
  详情是**页内二级页**，故返回键用 `BackHandler` 先回列表（`BackButton` 会直接退出整页）。
- 新增 `PromptPresetSerializer`（native + ST 双分支）。ST 分支按 `prompt_order` 顺序取
  `prompts` 内容，**跳过空内容的运行时占位槽位**（`chatHistory` / `worldInfoBefore` /
  `charDescription` …——ST 在请求时填充，fork 无需表达），顺序 → priority 降序。
- **世界书 tab 暂时隐藏**（`SHOW_LOREBOOK_TABS = false`）：只关 UI 入口，数据/编辑器/
  导入导出全保留。两处 tab 行改为按 `ExtensionTab.visible` 渲染 ⇒ 隐藏时页面索引自动跟随。

**⚠️ 本轮踩到并修复的真 bug（数据损坏）**：真机导入 ST 预设时，用户既有的 **14 个注入条目
被覆盖成 1 个**。根因是 `rememberImporter` 用 `remember(serializer)` 缓存 `ImporterState`，
**首次组合传入的 onResult 被永久捕获**；而 `PromptVM.settings` 初始值是 `Settings.dummy()`
（只含 1 个默认预设），真实设置晚一两帧才到 ⇒ 回调闭包捕获 dummy 列表，
「dummy + 新导入」被写回。**修复取结构性方案**：`ImporterState` 改持**回调读取器**
（`currentOnResult: () -> (Result<T>) -> Unit`）+ `rememberUpdatedState`
⇒ 所有调用点（含将来新增）自动拿最新回调，不必各自记得防。
**通用教训：把「回调」交给长生命周期对象时，要传「读取器」而不是「回调本身」——
尤其是在 `remember(key)` 缓存、而闭包捕获的数据晚于首帧到达的场景。**

数据已用 protobuf 精准 splice 从旧 key 恢复（14 条经解析验证），并加回归测试
`ImportPreservesExistingDataTest` 锁住「必须基于最新列表而非 dummy 基线」。

**④ 开关层级现状（2026-09-19 核实）——「条目像预设一样在外部直接开关」已具备，但开关共三层且语义不同，另有一个未接线的预设级绑定**

核实缘由：需求「预设的条目也像预设本身一样可在外部直接开关」。结论是**已经具备**，
但三层开关的语义**各不相同**，容易在后续改动中混淆，故记清：

| 开关 | 位置 | 改的是 | 提交 |
|---|---|---|---|
| 预设级（整包停用） | `PromptPage` 预设列表**行内** | `PromptPreset.enabled`——**全局**字段 | `1395270a` |
| 条目级（改全局启用状态） | `PromptPage` 预设**详情**每张卡片 | `ModeInjection.enabled`（`entry.copy(enabled=…)`） | `97aed655` |
| 条目级（改本助手的绑定） | `ExtensionPicker` 预设详情（对话内扩展面板 + 助手详情页共用） | 绑定 id 集合（`modeInjectionIds` / 会话级 `modeInjectionIds`），**绝不碰全局 `enabled`** | `d44e90f3` |

- 生效判定唯一落点是 `PromptInjectionTransformer.collectInjections`：
  `entry.enabled && (effectiveModeInjectionIds.contains(id) || presetEntryIds.contains(id))`
  ——即**全局开关与绑定是「与」关系**：全局关掉的条目，任何助手绑定它都不生效。
- `ExtensionPicker` 详情那处源码注释明确写了边界（「这些开关只改已有绑定 id，绝不改条目的全局 enabled」），
  并**刻意不做聚合开关**——多个回调基于同一份父快照会互相覆盖（同批次的已知陷阱，见 §6.11 ① 的教训）。
- **预设级绑定 `Assistant.promptPresetIds` 目前未接线**：全仓仅 3 处代码引用
  （`Assistant.kt:108` 声明 + `PromptInjectionTransformer.kt:38`、`PromptInspector.kt:193` 两处读），
  **零写入点**，web-ui/web 模块也无（grep 全空）⇒ `promptPresetIds` 恒为空集，
  「整包启用一个预设」这条能力**已预留但尚无入口**。实际生效的只有条目级绑定 + 预设全局 `enabled`。
  **接线点**（若要做）：`ExtensionPicker` 容器行（现在 `trailingContent` 只有 `›`），
  与世界书容器行 `book.id in selectedIds` 的 Switch 同款写法——**成本很低**。
  注意「助手级绑定不随会话覆盖」是既有设计意图（表达「这个助手基于哪套方案」，与会话级逐条勾选正交）。

### 6.12 拼装预览的浏览体验（2026-09-18，`1235a841`）+ **待办 Tier 4**

**已完成 Tier 1–3**：

- **Tier 1 定位/跳转**：把 `ChatList` 私有 `MessageJumper` 抽成共享
  `ui/components/ui/ScrollJumperBar.kt`（顶尖头/上一项/下一项/底尖头 + 可选 `N/M` 进度标签）；
  预览页加**段落目录卡**（一行一段 `#N · 来源 · token`，点按随机访问）。
  段落下标 = 概览(0)+缓存提示(1)+目录(2) 之后的第 n 项 ⇒ **n + 3**（三者都是 `item` 槽位，
  内容为空也占位 ⇒ 偏移是常量）。跳转条沿用聊天页节奏「滚动浮现、静止 1.5s 隐去」，**靠右放**
  （预览页正文满宽，靠左会叠字）。
- **Tier 2 长段折叠 → 手动收纳（2026-09-19 修订，`41ede01d`）**：初版是「>1200 字符默认
  截断 + 卡头展开」（卡头教训：开关放正文下方，展开 11K 字的 system 段后按钮被推到几十屏外
  再也点不到——真机实测踩到）。用户随后决策**去掉自动截断**：概览职责已由目录卡承担，
  截断让「读内容」多点一步且 SelectionContainer 选不到未渲染文本；折叠保留为**手动**动作
  （默认全文展开，卡头开关把整段收起只留卡头，用于把不关心的大段挪出浏览路径）。
  复制/分享始终全文。
- **Tier 3 段内操作**：复制内容 / 复制（含来源表头 `#N label [role, ≈tokens]`）/ 分享。
  **刻意不做**「翻译」「引用」：只读预览里翻译无语义、引用没有可指向的消息对象。
- 顺带把代理对安全截断提为 `common/text/TextTruncation.kt`（`String.truncateSafely`），
  ai 的引用快照与预览页折叠共用（原先 ai 模块那份 internal 实现改为调用 common）。

#### Tier B 落地记录（2026-09-19，同日推进）

用户拍板推进 Tier B，核心是**system 块化 + 卡槽身份升级**：

- **`buildSystemBlocks`**：system 组装从单个 `buildString` 改为「带来源标记的块列表」
  （PERSONA / CARD_SLOT / MEMORY / TOOL / DIAGRAM_HINT / MATH_HINT），`buildSystemPromptText`
  = `joinSystemBlocks(blocks)`（按各块 `separatorAfter` 连接）。**逐字节契约由
  `PromptAssemblyTest` 17 项锁死**——分隔符是单换行（历史 `appendLine` 行为，含空工具块
  的孤立换行），**卡槽块之间是双换行**（`buildCardSystemPrompt` 的历史连接符，
  `separatorAfter` 按相邻卡槽对计算）。
- **卡槽身份升级（零行为变更）**：未漂移的角色卡助手（`hasCardParts` 且
  `systemPrompt == buildCardSystemPrompt(...)`）的 system 由**卡槽逐槽渲染**成
  CARD_SLOT 块——槽位开关/排序**即时生效**（无需点「重新生成提示词」），输出与
  原 systemPrompt 逐字节相同（「未漂移」的定义即是如此）。漂移（手改过 systemPrompt）
  → 手改内容优先（整段 PERSONA），漂移提示照旧。**09-17「systemPrompt 唯一发送文本」
  的约束以「未漂移才走字段」的方式保留了精神（永不静默丢失手改）同时放开了槽位**。
- **预览归因（Tier 4 的 system 部分完成）**：`PromptInspector` 对 system 消息调
  `buildSystemBlocks`（与真实请求同参）细分出「开场白/系统提示/描述/性格/场景/示例对话/
  记忆/工具 Schema/渲染提示」各段；槽名复用角色卡槽位视图的既有六语言字符串。
  真机实测：原 2 段 → 13 段，目录与正文一致。
- **实现教训（两处）**：①卡槽间的**双换行分隔**差点被压扁成单换行——块连接不能在
  测试里手写 join（必与实现漂移），提为 `joinSystemBlocks` 共用；②测试 fixture 的
  `Assistant` 必须带 `card*` 字段——只给 systemPrompt 会让 `hasCardParts=false`
  永远走 PERSONA 兜底，表现为「卡槽归因不生效」。

#### ✅ Tier 4（已落地，`9c18ee07`）——「点注入段 → 跳到产出它的条目」

**价值**：把预览从「只读展示」变成「可归因并可操作」——看到某段世界书/模式注入不合适时直接
跳到它的编辑处。

**⚠️ 2026-09-19 更正（保留）**：「按条目开关」**早已存在**（`97aed655`，预设详情卡片行内 Switch；
选择器侧另有绑定开关，见 §6.11 ④ 的开关层级表）。故 Tier 4 不承担「解锁某能力」的职责，
真缺口只有「从预览跳回条目」。

**规模：中**。两处结构性障碍（均已解决）：

1. **`PromptSegment` 不带来源身份** → 现由 `PromptSegmentSource`（容器类型/id/名 + 条目名）
   承载；`PromptSegment.source` 仅注入段有值。
2. **逐条身份在合并那一步就丢了**：`PromptInjectionTransformer.createMergedInjectionMessages`
   把同一 position 的多条注入 `groupBy { role }` 后 `joinToString("\n")` **合成一条消息**
   ⇒ 三条世界书条目落到预览里就是一段文本。

**解法（关键取舍）：身份走带外标记，不改编排方式。**

- **为什么用 `UIMessageAnnotation.InjectionSources`**：注解不在 part→API 转换的白名单路径上
  ⇒ **请求字节零变化**，合并规则/顺序/分隔符全照旧；且 `messages` 是 JSON blob、
  反序列化开了 `ignoreUnknownKeys` ⇒ 新增注解类型**无需 DB 迁移**。同 `QuotedMessage`
  的取舍（`UIMessageAnnotation.kt` 注释），`QuoteTransformer` 亦印证「注解不参与请求组装」。
- **为什么不改合并方式**：合并是既有请求形状，改动会改变逐字节输出、且可能触碰端点的
  行为假设（如 deepseek 对消息序列的要求）。**逐字节约束守住了**——`PromptAssemblyTest`
  与本次新增的等价单测都锁着契约。
- **覆盖范围**：四个**非 system** 合并点（TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH /
  END_OF_CHAT 的 system 与 user 段）记贡献者；system 区 BEFORE/AFTER **不改结构**——
  它被拼进 system 本体、与原人设正文交错，一条有序 id 列表表达不了位置关系，
  由预览侧直接调 `collectInjections` 归因。
  （注：system 侧的「块化」障碍已由 Tier B 的 `buildSystemBlocks` 解决，见上节。）

**UI 落点**：新路由 `Screen.PromptEntryTarget`（独立路由而非给 `Prompts` 加可空参数——
后者会改变既有 route 签名、影响深链与返回栈恢复）；`PromptPage` 支持
`initialContainerId`/`initialEntryId` 深链 → 直达预设/世界书详情 → 滚动到条目 →
**短暂高亮**（primary 描边而非改底色：描边不改变内容对比度，与「启用/停用」语义不冲突）。
预览页用 `navigate` 而非 `popBackStack`——预览页留在返回栈，改完按返回即回到预览继续看。

**两条正确性约束（均有单测钉住）**：

- **宁可整段显示、不可少显示**：任一贡献者 id 查不到内容时**整段回退**，不半拆。
  半拆会让预览与实际请求不符，比不拆更糟——该页存在的意义就是「与自己发出的一致」。
- **高亮只在首次进入定位**（`rememberSaveable` 消费掉 id）：否则用户手动往下翻时
  任何重组都会把列表拉回目标条目。

**测试**：`PromptSegmentAttributionTest` 11 项（逐字节等价 / 贡献者顺序与拼接顺序一致 /
多位置各自成段 / 尾部按 role 分组 / 无注入不产生注解噪音 / 拆分成逐条 / 未知贡献者回退 /
空贡献者视为无注解 / 条目已删除仍可正确拆出 / 无名条目回退通用 label）。全量 839 项仅
`MarkdownMathParseTest` 既有基线失败。真机验证通过。

#### ⚠️ 发布说明偏差（已消解）

v1.0.7 更新日志写着「新增拼装预览：发送前可查看提示词实际分段与占用，**支持按条目开关**」，
而**预览页里没有任何开关**。核实：对应实现 `d2400452`（提示词分段槽位视图）**完全没碰**
`promptinspect` 目录，它落在**助手详情页的角色卡字段区**。

**2026-09-19 补充（偏差收窄）**：①「按条目开关」作为**能力确实存在**（`97aed655` 预设详情 +
`d44e90f3` 选择器绑定，见 §6.11 ④），只是都不在预览页；②「预览页无开关」这个具体表述
仍成立。

**2026-09-20 结案**：Tier 4 已把预览变成**可操作面**（跳转到条目编辑处），配合既有的条目级
开关，v1.0.7 那句「据此调整」终于名副其实——此前预览只能看、不能改，也就无从「据此调整」。
文案无需再改（「支持按条目开关」+ 预览可跳转 = 用户能从预览出发完成调整闭环）。

#### ✅ 预览页 token 估算偏低的成因分析与修复（2026-09-20 分析 → 2026-09-21 落地 `8f44c34b`）

用户反馈「预览页的 token 估算与实际发送差别比较大」。核实后**主因不是估算器不准，而是覆盖面不足**——
预览数的是「能拆成文本的那些段」，而不是「这次请求实际会发多少」。按影响排序：

**① 工具 schema 在预览里几乎贡献 0 token（最大项）**

预览的 `TOOL_SCHEMA` 段取的是 `tool.systemPrompt()`（`PromptAssembly.kt:149-151`），
而**实际下发给 provider 的是 `description` + `parameters`（JSON schema），不是 `systemPrompt`**
——两者是 `Tool` 上彼此独立的字段（`ai/core/Tool.kt:16`，`systemPrompt` 默认 `{ _, _ -> "" }`）。

- 全仓**仅 2 处**工具设了 `systemPrompt`（`SkillsTools.kt` 的技能清单、`TextToSpeechTool.kt`）；
- **MCP 工具完全不设**（`ChatToolFactory.kt:151-160` 只给 `description`/`parameters`）。

⇒ 实测过的极端场景下（54 个 MCP 工具 ≈33K token，占 4 万总量约 80%，见 `701361d8`），
**这部分在预览里是 0**。这是「差别比较大」的主因，且是**覆盖面**问题而非精度问题。

**② 口径不同：预览只数 `Text` part，占用条含 `Tool`**

- 预览：`message.parts.filterIsInstance<UIMessagePart.Text>()`（`PromptInspector.kt:199`），
  `totalTokens = segments.sumOf { it.tokens }` ⇒ **图片/文档/音视频 part 一律计 0**
  （而实发会经 `FileEncoder` 压 JPEG 再 base64）。
- 占用条：`estimateTokenCount`（`ContextLength.kt:78-84`）**包含 `Tool` part 的输出文本**
  （fork 工具输出上限 32KB）。**同一份对话，两处 UI 两套口径**。

**③ 估算器是字符启发式，对 schema 恰是最不准的场景**

`estimateTokens` = `cjk/1.5 + other/4`（`ContextLength.kt:113-118`）。散文尚可，但 JSON schema
以 `{`/`"`/`:`/`type`/`string` 等短 token 与标点为主，真实 tokenizer 密度远高于 4 字符/token
⇒ **即便把 schema 接进预览，用这个估算器仍会低估**。
仓里其实已有更准的实现：`ai/util/ToolSchemaSize`（按 provider 实际包装形状，实测误差 3.4%），**预览没复用它**。

**④ 协议包装开销未计入**：每条消息的 `{"role":…,"content":…}`、`tool_calls` 包装、
`ensureArrayItems` 补的 `items` 等。单项不大，消息/工具多时累积。

**已落地（`8f44c34b`，真机验证）**：

1. **工具 schema 独立成段**（原建议的 ①，收益最大）：新增 `TOOL_SCHEMA` kind（与
   `TOOL_GUIDANCE` 区分——后者是 system 正文内的引导文本，此前两者混用同名枚举）。
   复用 `ToolSchemaSize` 并按 provider 形状算体积（与诊断日志同口径），且把它内部的
   `wireElement` 提为 `wireJson()` 供预览渲染 ⇒ **估算与展示同源**，不会「数字按 A 算、
   正文按 B 拼」。标 `TOP_LEVEL_FIELD`，UI 显示「请求顶层字段，不在消息里」——
   **原「归因错误」一并修掉**（此前被渲染成 system 块）。真机实测：13 个工具 ≈2894 token。
2. **工具输出接入**（分析时低估了它的量级，实为第二大来源）：`UIMessagePart.Tool.output`
   是**独立的一类 part**，而预览正文只取顶层 `Text` ⇒ 整条工具输出计 0，而
   `estimateTokenCount` 一直计入（两处口径不一致）。现每条输出各自成段（实发时是独立的
   `tool` 角色消息，一条一段才对得上消息序列），段名带工具名。
3. **非文本 part 占位**（原建议的 ②）：段尾追加「［未计入：图片 N、文档 N、音频 N、视频 N］」。
4. **措辞（A 方案）**：标题「拼装后提示词」→「**请求内容**」——既不冒充「上下文」
   （会与输入框的 `usage.prompt_tokens` 对不上），也不让人误以为只是 system 提示词；
   legend 补**「不计入」清单**（附件编码体积 / 协议包装 / 字符启发式 / schema 另算）。
5. 协议包装开销未做（原建议的 ④：量级小）。

**顺带修掉两处**（真机与回归发现）：①**空工具引导块噪音**——`buildSystemBlocks` 为每个工具
各加一块，实测 13 个里 11 个空 ⇒ 渲染成 11 行「工具引导 ≈0」，已过滤（只影响展示；空块在
system 里仍贡献换行的逐字节契约由 `PromptAssemblyTest` 锁着）；②`sentHistoryCount` 改为
**按消息计**（一条消息现在可产出多个段，按段计会重复统计、甚至超过 `totalMessageCount`）。

**测试**：`PromptToolSchemaSegmentTest` 7 项 + `PromptToolResultSegmentTest` 7 项 +
`SystemBlocksAttributionTest` 修枚举改名回归并钉住空块过滤。全量 860 项仅既有基线失败。

**关联**：`701361d8`（上下文构成诊断日志，真实构成与 3.4% 精度实测）、§6.12 Tier 4。

关联文档：[upstream-analysis.md](../../upstream-analysis.md)（上游综合分析，A1 前台服务取舍——本文第五.5 条为其补充了 Android 15 dataSync 6h 限时的决策依据；「提示词注入/世界书统一重写」议题见其「注意点」第 8 条）、[design-memory-scoring.md](../../design/design-memory-scoring.md)（§六 6.2 ③ 的预算范式来源）、[本页索引](../../peer-apps-benchmark.md)。
