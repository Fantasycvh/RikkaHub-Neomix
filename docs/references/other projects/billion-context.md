# billion-context（ranxianglei/billion-context）借鉴评估

> 评估：**2026-10-18**（原 §11.4）。TypeScript，1773 stars，MIT 系（插件）。
> 定位：宿主无关的**上下文压缩中间件**（kernel + 本地 HTTP 代理 + 多宿主适配）；对本仓的价值是**压缩布局判据与盈亏平衡模型**，不是代理形态。
> 编号说明：原编号为 §11.4（挂在 bigeye 的 11.x 序列下），已独立成文；其 §I 三方综合对比是跨应用内容，仍保留在本文件末尾。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 11.4 billion-context（`ranxianglei/billion-context`，2026-10-18 评估）

> 自述「上下文压缩插件，100K 窗口够用 / 省 5 倍 token / 单会话数十亿 token」。
> **实际形态**：宿主无关 kernel + 本地 HTTP 代理 + 多宿主适配；DSH 只是把 `fetch` 拦到代理的一个入口。
> ⇒ 可拿的是**布局与判据**，不是 SDK 用法。TypeScript，1773 stars。

### A. 核心机制：压缩是「追加」，不是「重写」

把一段可寻址的消息区间折成一个摘要块，块**追加**进块表；被折的原消息只从渲染视图隐去
（`active=false`），原文仍在存储里。**已有摘要永不重写**（唯一例外是 tier-2/3 蒸馏，
且代码注释明确计为前缀缓存损失）。块携带 `directMessageIds` / `effectiveMessageIds` /
`startRef` / `endRef`，因此 lineage 不丢、可链式恢复。

「数十亿 token」**不是靠外存取回**，而是客户端每轮全量重放、代理在 wire 上折掉已消费区间：
实测每次调用上下文**均值 88K / 中位 72K**，4.5 个月 18.76B 累计输入、42,986 次调用**零超窗**。

### B. 它给前缀缓存的答案（三条，全可抄）

1. **摘要只追加**，新折叠只在其覆盖区间的**起点**插入
2. **锚点只许后移**：硬钳制「摘要永不排到**首条 user 消息**之前」
3. **每条消息的 wire 字节恒等**：ref tag 先剥后加、占位符无时间戳无随机

**它的事故记录最值钱**（CHANGELOG #2733）：折叠块锚点解析到 index 0 时，摘要被插到 pinned 的
首条 user 消息**之前** —— **那个 pin 就是 provider 的前缀缓存头，改写它 = 每次折叠都塌掉整个前缀**。
该事故占全部未命中 token 的 **56%**，命中率 93–98% → 89.8%。

### C. 必须诚实的一点：「5×」是反事实，不是实测

| 口径 | 数值 | 证据等级 |
|---|---|---|
| 营销口号 | 5× | 无口径 |
| 1M 窗口马拉松会话 | ~5.3× | **[A] 模拟外推** |
| 受控实验账单 token 降幅 | −31.5% ~ −53.7% | [V] 单模型 pilot |
| 生产单块压缩比 | 中位 7.9×（工具主导块 24×） | [V] 17,783 块 |

且它的对照是「**完全不截断的滑动窗口**」，**不是**替换式摘要 ⇒ **不能直接拿来和本仓比**。

### D. 最值钱的一条：盈亏平衡模型

```
一次性成本 = (w−r)·T + q·σ − r·S      // w=缓存写价 r=读价 q=输出价
每轮节省   = (S−σ)·r
breakevenTurns = 一次性成本 / 每轮节省
```

实测：**5 次级联小折叠重付 2.29×/tok，1 次大折叠只要 0.032×/tok —— 差约 70 倍**。
⇒ **「每次发请求都试着压一点」是净亏的反模式。**

### E. 对本仓的结论：我们这套是它明确记录的**结构性反模式**

本仓现状 = **单个 checkpoint、每次压缩重新生成**（`compression_checkpoint` 表 + 区间指纹）。
按它的判据，这等于「rewrite-history 压缩」：压缩瞬间紧跟 system 之后字节全变 ⇒ 整段前缀失效。

| 我们的现状 | 它证明的 | 应改成 |
|---|---|---|
| 单 checkpoint 重新生成 | 任何 rewrite-history 压缩都让整个前缀失效 | **摘要块表**：旧块不重写，新块只在其区间起点插入 |
| 区域内被编辑 → 指纹失效 → **回退全量** | 编辑是「一条新消息入场」，只有编辑点**之后**失效 | 去掉回退全量，把爆炸半径缩到编辑点之后 |
| 首条 user 消息也被折进 checkpoint | 首条 user 是 pin 的缓存头 | 划**不可变头部** `[system, 首条 user]`，任何压缩不许跨越 |

另两条 P1：**大工具输出到达即外置**（内容寻址 CAS + 确定性占位符，对缓存零扰动）；
**触发改增量门**（自上次压缩以来新增 ≥ 阈值）而非占用率（占用率触发会反复抖动）。

### F. 不该照搬

- **三层蒸馏塔**：生产里只触发 3.5%，是给「数月单会话」兜底，移动端不划算
- **模型自己调 `compress` 工具**：那是 coding agent 多轮 tool loop 的设计；本仓是请求/响应式聊天，
  每次判定多一个 round-trip 就是钱 ⇒ 接缝应是发送前 transformer 管线（插件模式，非代理模式）
- **每轮全量重放 + wire 重写**：代理形态的必然，移动端带宽/耗电不划算
- **「5×」这个数**：红利集中在工具输出（24×），对话语料只有 7.9× 且**有损**

### G. 未查清

- 「compression costs ≤2%」的**具体公式**（仓库只有 breakeven 模型，未找到该数的分子分母）
- **tier-3 从未被观测**（论文自认 carried from design values）
- 消融（去掉 tiers / search / growth-gate / protected-zones）**全部未做**
- 「区域内编辑对前缀缓存的实际代价」只有存在性证明、**无量化** ⇒ 「只有编辑点之后失效」是从代码语义
  推出，**未实测确认**

### H. 与 §11.3 的关系

§11.3 的结论是「**判据不是杠杆、预算是杠杆**」；本节补充了它的另一半：
**预算的"怎么压"比"压多少"更贵** —— 换一种压缩布局（追加式块表 vs 替换式 checkpoint），
同样的压缩量可以把前缀缓存从「每次全失效」变成「只在折叠点之后失效」。

### I. 三方综合对比（bigeye / billion-context / senera）

三者解决的是**三种不同的压力点**，混在一起比容易得出错误结论：

| | 压力点 | 形态 | 规模 |
|---|---|---|---|
| **bigeye** | **注意力 / 连续性**（长任务不降智、不忘） | 应用（Python，单文件风格） | 11M，100 `.py` |
| **billion-context** | **存储 / 带宽**（窗口与 token 成本） | **中间件**（kernel + HTTP 代理） | TS，1773 stars |
| **senera** | **行为可信**（执行与审计） | 宿主（TS Agent 工作台） | 38M，1895 `.ts` |

| 维度 | bigeye | billion-context | senera |
|---|---|---|---|
| **对上下文** | **不压缩历史**，注入「当下该关注什么」 | **折叠但可回读**（追加式摘要块） | 不主打；靠 Pi compaction + 项目记忆/MemOS |
| **对工具执行** | 有 `loop_detector`/`guardian`，**无执行运行时语义** | 工具输出是**压缩主要对象**（24× 压缩比） | **中心命题**：校验→授权→执行→观测→证据 |
| **前缀缓存意识** | 未见 | **一等公民**（三规则 + 事故记录 + breakeven） | 有（冻结快照 / 指纹 / 不重写快照） |
| **自主性** | **核心**（长航时、跨话题） | 无关 | **明确删除**（`World`/`Agenda` 删了） |
| **证据纪律** | 叙述性 | **标注等级**（[V] 实测 / [A] 反事实） | 架构文档齐全，**无量化** |
| **工程化** | 无测试基础设施迹象、自述"不建议生产" | 论文 + 生产数据 + CHANGELOG 事故记录 | monorepo / Docker / Playwright / Vitest / 12 篇架构文档 |

#### 三处**互相不认同**的地方（比"都很好"有用）

1. **上下文策略正面冲突**：bigeye「不压缩、服务注意力」 vs billion-context「折叠 + 可回读」。
   前者风险是**焦点选错**；后者风险是**模型不自发回读**（其论文自认
   "recovery works when explicitly instructed, but the model does not think to invoke it on its own"）。
2. **自主性完全相反**：bigeye 以长航时自主为核心；senera 把「后台自主任务」整体删除并写成原则。
   ⇒ **「自主」是产品取舍，不是技术收敛**，不可当普适结论。
3. **证据纪律差距最大**：billion-context 会明说「5× 是 [A]」「tier-3 从未被观测」「消融未做」；
   bigeye 用叙述性表述；senera 架构严谨但无量化。
   ⇒ 若选方法论榜样是 **billion-context**，不是因为它最好，而是因为**它标了自己哪里没证实**。

#### 对本仓的取舍（我们处在客户端位置）

| 来源 | 拿什么 | 不拿什么 |
|---|---|---|
| bigeye | 规则引擎判据（已借 `loop_detector` → 规则提醒） | 无限上下文那套（已有分层注入 + 压缩，路线不同） |
| billion-context | **压缩布局判据**（追加式块表 / 编辑即入场 / 不可变头 / breakeven） | **代理形态**（每轮全量重放 + wire 重写，移动端不划算） |
| senera | 边界与冻结（快照/指纹/统一注册表/观测压缩判据） | 架构重写（TS monorepo 差异太大） |

#### 统一视角：三者可以叠成一套

```
行为可信（senera）        —— 工具执行必须可审计
      ↓
存储布局（billion-context）—— 上下文怎么折、怎么回读、缓存怎么保
      ↓
注意力（bigeye）          —— 当下的焦点放在哪
```

本仓现状：**三层都各有一点、都不完整** —— 有规则提醒（bigeye 层）、有分层注入 + 压缩
（布局层，但布局是结构性反模式）、有审批与工具配平（行为层）。
⇒ **最该补的是中间那层**，因为已有资产正好卡在那。

### 11.4 J、2026-10-18 → 11-03 增量评估（2026-11-03，约 1457 条非 merge，按 PR 主题聚类）

提交量约 2226（769 merge）；大量为同一判据的返工/CI/博客/ledger 噪音，**不逐条评**，只取机制级 9 个主题。**总评：不推翻「不吸收代理形态」；补记判据 9 条（其中 2 条修正级）+ 1 条小件吸收**。

| # | 主题（代表 sha） | 判据变化 | 对本仓 |
|---|---|---|---|
| B1 | fold anchoring **order over hash**（`6a9d40390` #3641） | 折叠身份不能用内容哈希：id 重铸但内容未变会被读成「内容丢失」→ 大规模误展开；改用 wire 顺序 + 词袋/位置证据，尾部回退/头部截断归「合法删除」 | **修正级**：`compression_checkpoint` 的**区域内容指纹**应降级为一致性核对，不是折叠身份；改 `design-auto-compaction.md` 相关取向 |
| B2 | 覆盖以**单调 ledger seq 区间**为第二键（`66e452711` #4316） | 覆盖 = id 路径 ∪ seq 区间；seq 只在「全量打戳且严格连续」记录、绝不猜；未打戳字节不变 | 本仓 `MessageNode` 无持久 seq（边界用列表位置），落地前需先加；列表位置不能当 seq |
| B3 | Rule-3 锚点跳过 host 注入伪 user 块（`e7fe9c098` #5109） | 「最后一条 user」常是宿主注入（占 user 通道 35%）⇒ 锚到注入、真实请求被折；锚点扫描须跳过注入 carrier 并在收据写明 | 本仓 wire 上同样有模板/模式/工作区提醒注入；做「保护最近一轮」时不能按 role=USER 取锚 |
| B4 | 换模型后 usage 基线污染预检（`041c32b00` #4288） | 旧宽窗口 usage 基线 > 新窗口 ⇒ 判定永不触发 ⇒ 反复折叠烧轮次到会话卡死；改用无地板 payloadEstimate | **修正级**：本仓 token 估算 usage 优先、陈旧判定只看检查点时间戳，**不含 model/contextLength 变化**；补「换模型 ⇒ usage 基线作废」 |
| B5 | 紧急截断 latch 到下一次折叠 + 严格变短（`b9bae8990` #3939③ / `54bb967c6` #3935） | 截断只判一次、后续回合原样重放；clip 必须严格短于原文（曾 4001→4064 越截越长，反写坏前缀缓存） | 本仓 85% 紧急卸载是请求期投影、每请求重算——正是抖动源；需 latch + 单调变短两条硬约束 |
| B6 | 覆盖率闸 + 幂等 no-op（`bc1878371` #4521 / #3353） | 「没得折」不是失败：越界 endId 收敛、已覆盖区间返回 success no-op；提交前拒绝 coverage-regressing fold | 检查点重生成应比「新边界 ⊇ 旧边界」，同区间重压直接 no-op |
| B7 | breakeven 计价口径（`aaa74bb08` #4899 / `058da0498` #4956） | 一次性成本 = 重放段按 **cache-write 价**（≠ input 价，Anthropic 形差 ~28%）+ 摘要输出 − 被压文本 cache-read credit；归因用 wire 实测前缀 | 基线 §D 公式成立，补计价术语与归因方法；本仓若显示回本数据须同口径 |
| B8 | 新字段默认关保 wire 字节恒等（`6fd9be5a9` #4313） | 新可选字段默认 off + 旧字节 goldens 钉住 | 佐证规则 3；本仓 `PlaceholderTransformer` 每请求重算 + system 里 `{{cur_date}}` 是反例 |
| B9 | 出口**字节**预算 + 图片剥离（`6e39a65d5` #3701 / `c7c5b68ff` #3909 / #4079） | 预算单位是 HTTP 体字节；base64 图片使体远超 token 估算；到线在发送点降级/丢图 | **吸收（小件）**：本仓预算全 token 口径、无出口字节预算；图片密集会话有 413/1009 风险 |

**不适用（记账）**：watermark fastpath（宿主/代理全量重放解析开销，本仓纯客户端不做 wire 重写）· `compress-at-fold-point` 默认开启（只修「模型自己调 compress 工具」形态，本仓已等价——折叠行+检查点合成摘要本就在边界）· unsigned thinking replay（代理需字节恒等回放；本仓 `ClaudeProvider.kt:497-499` 选择省略无 signature 的 thinking）· idle prompt-cache keepalive / 自签 `prompt_cache_key`（代理侧/移动端耗电不划算）· 尾部 rewind / 锚点表（本仓单检查点，规模问题不存在）。

> 证据纪律：同一判据 10 天内反转（#4186「失败即结束回合」→ #5220「失败收据原注重发」），引用必须钉 sha；本文件结论一律 `repo@sha` + 文件路径。

## 2026-11-03 → 11-10 增量评估（2026-11-10，671 提交，HEAD `4dea18ea`）

> 窗口：`since=2026-11-03T00:00:00Z` → `4dea18ea`（pushed 2026-11-10T11:24Z）。**671 条 = 438 非 merge + 233 merge**；含少量 10-26 起的分支提交（随 merge 带入）。
> 方法：**全量标题**（`gh api repos/ranxianglei/billion-context/commits?since=...&per_page=100` 翻 7 页）+ **16 个主题代表 commit 的 message/stats 抽样** + HEAD 树核对（`git/trees/4dea18ea?recursive=1`）；**未逐条读 patch**。
> 证据级别：**远端：标题 + 抽样 patch**（非源码级；凡「本仓」判断均 grep 过本仓）。

### 分桶（conventional prefix，精确计数）

| 桶 | 数 | 备注 |
|---|---|---|
| merge | 233 | 226 `Merge pull request` + 7 直接 merge |
| fix | 205 | 以返工 / 回归 pin / review 修为主 |
| test | 73 | 与 fix 成对（CI 契约化） |
| docs | 68 | README / config / architecture / v6 教义 |
| feat | 16 | 见主题表 |
| kernel | 13 | kernel 版本号 + 折叠/重定位语义 |
| refactor | 10 | |
| ci | 9 | |
| chore | 8 | |
| storage | 5 | |
| style | 3 | |
| 其他 | 24 | tests/sync/notices/loop 各 1 等 |

### 主题聚类（标题关键词优先归桶，近似；代表 sha 均为远端实取）

| 主题 | 近似数 | 代表 sha |
|---|---|---|
| v6 渲染快照 / 身份面 / fastpath | 66 | `6d3ca309b` `49ba14dae` `dc65bb7b5` `f3681735f` `66aadbd89`（11-07 整条 revert） |
| 宿主适配 / 面板 / 通知 | 61 | opencode/pi/dsh/codex/hermes/qoder/kiro/goose/qwen lane；web 面板 / notices |
| 传输 / 上游稳定性 | 48 | idle stream `312e42183`、SSE backpressure `105b84dd9`、circuit lease `2ab47e310`、first-byte、429 hold |
| 存储 / 持久化 | 48 | unified kv_store `25405b446`、jsonWriter=off `2f89ca0ec`、watermark `ffb5a3dcf`、SimHash `d90c2b632`、quarantine、resident pool |
| 压缩核心（fold / 摘要 / 锚点 / 蒸馏 / 占位） | 42 | `cc1e2408c` `d126087ab` `60a557e78` `e3d1a887e` |
| 出站体预算 / 图片 / 窗口 | 32 | `6aed3b840` `2e0211094` `d1b2d8fac` `4ec97781a` `73f222e54` |
| 观测 / 诊断 | 20 | `725084b8b` `d15180504` |
| 压缩权限门 / nudge | 7 | `15d134206` `b2096dd8c` `a7cbfd12f` |
| 其他（release / lock / 测试基建） | 114 | 不评 |

### 总判决

**既有 A/B/D/E/F 判据全部未被打翻；本轮无推翻级新增。新增候选 1 项（P2，见 C1）、记账级判据 8 条（B10–B17）、已有等价 4 项（E1–E4）、不适用 6 类（U1–U6）。**

- **§A 追加式布局**：未变。布局级改动只有 relocate-compress v2（`cc1e2408c`，内核 0.0.187→0.0.188），仍是「摘要追加、既有行不动」。
- **§B 不可变头 / 锚点**：被本轮最有价值的一条反例**加固**（B10 + #6021 生产事故）。
- **§D breakeven**：无新数据（`breakeven` 一词在本周 671 条标题中 0 次）。
- **§E 增量触发门**：本仓仍是占用率门（`CompressionPlanner.kt:110-116`）；本轮没出现「占用率触发会抖动」被推翻的证据，反由 `#5860` 幻影压力守住轮次补了一个旁证（B17）。
- **§F 不该照搬**：蒸馏塔本周暴露 T1 堵死的 treadmill（`e3d1a887e` #5700 `a7cbfd12f`），继续支持「移动端不抄三层塔」。

一句话：**本周 671 条的重心不是「再发明压缩布局」，而是「压缩后的行为治理」——拒绝（gate）/ 分级（reconcile）/ 可解释（analysis）/ 可回读（media refs）**。布局是判据层的东西：一周 671 条里布局本体只动 relocate v2 一处，其余全是治理。这反向加强 §E/H。

### 候选（唯一，P2）

**C1 折叠区的媒体 / 附件句柄应机械随摘要携带**（`d126087ab` #5620，2026-11-04）

- **远端机制**：新增 `kernel/src/media-refs.ts`，从五类 wire sidecar 抽「可重取句柄」（非 `data:` URL、file id、fileUri）；`hasUnrecoverableMediaPayload` 改为「**无句柄才钉住**」，未知 opaque 形状继续钉；摘要落盘时**机械追加确定性 MEDIA REFS 段**（`applySingleRange` union 含 consumed-block carry、`applyRefolds` per-block carry），句柄因此穿过折叠/合并/蒸馏——「绝不靠模型自觉（wire 上 URL 部件对摘要器往往不可见）」；T1/T2/T3 压缩规则同步加 preservation 行。动机是 #5620 用量墙：带远程图 / DeepSeek Files API 引用的消息被永久钉住，上下文地板爬到窗口 ~70%。
- **本仓 gap**：压缩摘要输入只取 Text/Notice——`ai/src/main/java/app/androidrik/ai/ui/Message.kt:222-231` 的 `summaryAsText` 对其他 part 一律 `""`；`ChatService.kt:2239` 的 `sourceText` 与分块路径都走它。⇒ **折叠区的图片 / 文档在进摘要器前就被静默丢弃**，折叠后请求里既无图也无句柄；`ContextImageEvictionTransformer` + `view_image` 只覆盖「仍留在窗口里的最近 N 张」（E1），管不到折叠区。
- **量级**：命中条件 = 带图 / 带文档的长会话越过 `retainRatio` 边界（默认窗口 40%）。代价 = 每句柄一行（URL/路径，约 100–200 B），须与 §B9 出口字节预算一起 cap（如 ≤20 条或 ≤4 KB）；工作量 ≈ 半日（在 `sourceText` 组装处从 `toCompress` 机械抽 `UIMessagePart.Image/Document` 的 url，追加独立 `MEDIA REFS:` 段，与模型摘要分离）。
- **判据（可直接复用）**：可回读的媒体必须由**代码**机械携带句柄，句柄丢失 = 不可回读；「让模型在摘要里记住路径」不算。

### 记账级新增判据（8 条，B10–B17）

| # | 出处（远端 sha / PR） | 判据 | 对本仓 |
|---|---|---|---|
| B10 | `60a557e78`（#6027）→ 当日 revert `7d5f42efa` → 最终形态 `f3766fea0`（#6024）；HEAD `src/durable-message-guards.ts:331` | 宿主导入的**伪 user 文本载体**（外置占位符 / 图片占位 / 单发句柄）必须显式登记为 **one-shot carrier**，并**同时**接入 slot 排除与漂移记账两个注册表。否则被改写的旧消息（内容身份变 → 当新行重摄入、新 ref/seq/时间戳）会在折叠后成为**唯一可见 user 消息**，抢 Rule-3 锚点，整回合答陈旧话题（bili 0.1.213 / DSH 0.2.2-rc.1 生产事故 #6021）。 | 锚点侧**已有等价**（E2）；记账：任何新载体必须同时进「锚点排除 / 折叠窗口排除 / 记忆排除」三份清单（`Conversation.kt:533-556`、`MemoryConsolidationPrompts.kt:406`、`TemplateTransformer.kt:68`）；外置占位应**原消息内换 part**（E1 做法）而不是插入新消息——插新消息会改消息序列并可能自己成为尾部 user。 |
| B11 | `6aed3b840`（#5745） | **淘汰工作清单的条数上限是陷阱**：MAX_SHED_IMAGES=64 同时限住「归档」与「降采样」两档 ⇒ >64 张图永远压不到字节预算下 ⇒ 上游 400 无 marker ⇒ 无限重试同一超预算体。修法：rung A（归档 = 廉价字符串替换）不设条数上限、从最旧开始直到估算达标；条数上限只保留在 rung B（解码/重编码，有 CPU 界）。**保护尾部不变量**：tail 永不被归档，只可降采样。 | 图片淘汰**已有等价**（E1：保留最近 N=20、更旧换带路径占位、request-only、幂等、纯函数）+ `ViewImageTool` 取回；差量：淘汰停止条件是「最近 N 张」而非「估算达标」，图片密集 + 大体积仍可能超出口上限。记账：把**字节达标**作停止条件，保留「尾部不淘汰」。 |
| B12 | `2e0211094`、`d1b2d8fac`（#5000 #5794 #5885）、`4ec97781a`（#5996） | **量纲只能是真实出站体字节**（不是 token 估算），且另有正交第二维：条数闸 `BILI_MAX_MESSAGES` + 32 MiB 摄入成本带 `BILI_INGEST_BODY_BYTES`；判死信息**点名超重字段**；无窗口声明时回退 1 M 窗口下限**本地拒绝**，不再裸发上游。 | 无（全 token 口径，见 11-03 B9）→ 记账：若做出口预算，量纲 = HTTP 体字节，且条数/媒体数要单独一维；到线行为是**本地拒绝**而非「裸发碰运气」。 |
| B13 | `15d134206`（#5683）、`b2096dd8c`（#5943/#5945） | **模型自发压缩必须被「建议→执行」闭环门禁拒绝**，且资格是**衰减信用窗口而非单轮标记**：一次真实注入 nudge 铸 20 个 model round 资格，新注入刷新、每个未被 nudge 的 model round 衰减 1、非 model 请求形状 hold open、成功折叠不消耗；静默会话滑回严格拒绝。`requireRecommendation` 按**覆盖**判定（推荐区间内细分通过、refold 更新活动块豁免、逃出推荐集整段拒绝并点名区间）。 | **不适用**（本仓压缩完全宿主决定，模型无 compress 工具）→ 记账：若将来给模型「建议压缩」，资格必须窗口化 + 衰减，不能单轮布尔（多轮清理是常态）。 |
| B14 | `cc1e2408c`（#5470 v2）、`ad242bf6c` | **摘要文本可在折叠点以「追加文本载体」出现，但既有行保持原位**；折叠后载体**不得保留可再被调用的命令形状**：v1 把 compress pair 原样搬到折叠点 → mid-history wall of fully-shaped compress interactions → 弱注意力模型（qwen3-27b）读成继续压缩的邀请 → 自愿 re-compress loop；v2 = 摘要文本作为 plain user text carrier 在折叠点重发（`[bili fold summary — block(s) bN —]`），pair 留在原槽位、args 换成短指针（「already folded — do not re-compress」），结果核原样转发；view-only + deterministic + 幂等（carrier id `acp_reloc_<callId>` + args marker probe）。 | 「不搬行长」**已有等价**（E3：只在发送时组装，`messageNodes` 不动）；记账：把已折叠的工具交互留在窗口里时要换掉「可调用形状」（本仓合成检查点是 `isSynthetic` USER 文本 + `<compressed-summary>` 包裹，方向正确）。 |
| B15 | `2a05a4d5b`（#5715）、`3298cd8ff`（#5737）、`4f8070201`（#5968）、`a39ce3a23`（#5751） | **折叠对账要分级**：直接 ID 多数存活 = **部分丢失**，不是整体漂移（否则整块误回退）；fork 回放要能恢复保留折叠块；工具调用/结果按**精确 id 跨任意距离**折叠（不靠距离启发式）。 | 本仓检查点校验是**二元**（指纹不符即回退全量，E4）→ 记账：分级化（只有「边界节点消失 / 整区不可识别」才回退全量），局部改动应允许「部分保留 + 只重摘要受影响后缀」；落地需先把检查点做成可分段（与 §E 块表同一改动的两面）。 |
| B16 | `725084b8b`（#5963）、`d15180504` | 压缩系统的「**为什么这个会话的上下文是这样**」必须可答，但观测**只能 advisory**：四维 = init profile（首请求条数/估算 token/最大贡献者）、nudge cadence（INJECT 计数 + 哪道闸 idle：mass/growth/cadence）、harvest acceptance（每次注入 offer 的 pendingT1 vs 120 s 内实际折叠 token）、verdict（白话诊断：heavy init、流入>排出的爬升、T2 蒸馏就绪、欠收警报）；输出 = 每次 INJECT 一行日志 + status 报告段 + web 卡片；**严格 advisory**：内存、256 会话上限、caps rings、无决策路径读取、仅进程生命周期。 | 本仓仅一行 `Log.i("auto compress triggered")`（`ChatService.kt:2314`）→ 记账：补压缩决策日志（触发口径 / 压缩前后 token / 覆盖率闸档位 / 回退 tier），advisory 且不进决策。 |
| B17 | `a7cbfd12f`（#5860）、`f1f67e4dd`/`bcda253a5`（#5867 ruling） | **触发/压力注入要有「不动作」态及其上限**：本轮无计量样本 → 压力带注入 hold 一轮（幻影压力守卫）；estimate-grade hold 最多连续 N 轮（`compress.maxEstimateHoldRounds`）必须 backstop，否则会无限 hold 或反复触发。 | 本仓占用估算 usage 优先 + 本地兜底（`estimateWindowTokens`），无「样本缺失」显式态 → 记账：触发判定要有「本轮不动作」状态与上限，与 §B4「换模型 ⇒ usage 基线作废」同族。 |

### 已有等价（4 项，本仓 file:line）

| # | 远端判据 | 本仓等价 |
|---|---|---|
| E1 | 「大媒体/工具输出外置 + 确定性占位 + 可回读」（§E P1、B9、B11） | `app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/ContextImageEvictionTransformer.kt:13-92`（保留最近 N 张、更旧换带路径占位、request-only、幂等、纯函数）+ `data/ai/tools/ViewImageTool.kt`（按路径取回，白名单 filesDir 子树） |
| E2 | B3「锚点扫描须跳过宿主注入的伪 user 载体」（§11.4 J B3；本轮 B10） | `data/model/Conversation.kt:520-556` `taskAnchorSuffix`：取最近一条 `role=USER` 时排除 `isSynthetic` 与 `isExternalNotice`，且注释明确「不新增消息——新增会改变消息序列，让『最后一条 user 消息』的寻址指到锚点自己」 |
| E3 | §A「轨迹只追加，不改历史行」（B14） | `Conversation.kt:246-303` `CompressionWindow` / `compressionWindow()` / `requestWindowMessages()`：`messageNodes` 从不改写，检查点合成消息只在发送时组装；`ChatService.compressToCheckpoint` 落库也只追加 checkpoint 行（注释「轨迹只追加」） |
| E4 | B1「折叠身份不能用内容哈希」（§11.4 J B1） | `Conversation.kt:205-237`：失效指纹 = `nodes[0..boundary]` 的 **node id + 选中 message id** 的 sha256——已经是身份/顺序口径，**不是内容哈希**；残留问题只是失效动作仍是「回退全量」（§E 表第 2 行，见 B15） |

### 不适用（6 类，记账）

- **U1 v6 渲染快照 / 复合身份面**（本周最大工程量，且 11-07 整条删除）：`feat` `6d3ca309b`/`acca4db67`（#5664，schema v12 + O(1) 稳态轮）→ `49ba14dae` 11-05 02:53「NO recast in any scenario；结构分歧永久杀 lane；**fastpath 寿命 = 首次分歧之前**，压缩后即失效」→ `dc65bb7b5` 11-05 07:14「**见证式 rebase**：快照必须能活过第一次压缩，否则在它存在的场景里全废」→ `0f3c77cde`「按事件演化」→ 11-07 03:53 合并 #5652 → **11-07 05:32 整条 revert（`66aadbd89`）**；HEAD 树已无 `src/server/fastpath-v6*.ts`，只剩 `fastpath.ts`/`fastpath-scan.ts`。记账：唯一可留物 = `f3681735f`「**只缓存 post-rewrite generic core 形态，任何映射产物绝不入缓存**；映射每轮用当轮输入现算」——本仓 per-request transformer 天然如此。另 `f8a72c017`：watermark fastpath 只上 5% cohort，不进 GA。**判**：代理物化面不适用；且这是一个反面判据——「物化视图 × 压缩」两次方向反转后选择整条删除，不要借。
- **U2 egress 用户可注册请求体重写管线**（`73f222e54` #6003、`6b4757acb` 统一 chokepoint）：body 改写跑在 shed/compress 之后、re-sign/transport 之前，保证签名字节覆盖最终字节；插件 fail-open、改字节则丢 content-length。代理形态，不适用。记账一句：**出站改写必须收口单一出口**（本仓 `TransformerChainRegistry` 已是），且「改写阶段」本身要被身份/缓存层登记（同 B10 的注册表思路）。
- **U3 持久化写旋钮**（`a7d9f5dcb` #6062/#6066）：`persist.debounceMs` + 新 `persist.maxDelayMs` 接入 session-order / prefix-affinity 两个 sidecar writer；稳态重写率 = `min(debounce, maxDelay − pendingSince)`，只调 debounce 无效。sidecar 持久化节流，不适用。
- **U4 传输 / 空闲 / SSE / WS / circuit / 429**（`312e42183` `3307bf43d` `105b84dd9` `2ab47e310` `dcbe8ff24` 等）：代理侧连接治理，不适用。
- **U5 存储内部**（unified kv_store `25405b446`、jsonWriter=off 写白名单 `2f89ca0ec`、watermark 淘汰 `ffb5a3dcf`、SimHash 投票溢出 `d90c2b632`、quarantine 上限、resident pool 4 GiB、WAL 观测）：不适用。
- **U6 宿主适配 / 面板 / 通知 / 发行**（opencode V2、`pi.commands`、dsh codex ws lane、hermes、qoder、kiro、goose/qwen identity adapters、统一 notices、web 面板、release v0.1.213、lockfile）：不适用。

### 证据纪律（本轮新增两例，引用必须钉 sha）

1. **#6021 当日三态**：`60a557e78`（11-09 11:54 落）→ 15:34 合并（#6027 `c812a843e`）→ 15:35 revert（`7d5f42efa`）→ 15:36 换实现重落（#6024 `f3766fea0`）。同一问题当天「落 → 撤 → 重落」，只引最终态。
2. **v6 渲染快照/身份面 3 天内两次方向反转 + 一次整体删除**：11-05 02:53「永久杀 lane」→ 07:14「见证式 rebase」→ 11-07 05:32 整条 revert。这比任何单条 patch 都更能说明「物化渲染面 × 压缩」仍未解。
