# 华灯（rikkahub-sillytavern-android）借鉴评估

> 评估：**2026-10-05**（原 §八，本地源码调研）+ **2026-10-14**（增量同步复评 `4dcd60603` → `a9b95d45c`，198 提交，原 §8.1）。
> 定位：另一条 RikkaHub × SillyTavern 融合路线；对本仓的价值集中在**待办命中**与**提示词调试面**（`PromptDebugCache` 即借自它）。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 八、华灯（rikkahub-sillytavern-android）评估（2026-10-05，本地源码调研）

**仓库**：[MiaoWuNYA/rikkahub-sillytavern-android](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)「华灯」——RikkaHub 深度定制分支，**2150+ 提交**（最近合入上游 2.5.6），定位「通用聊天 + 安卓酒馆兼容」双路线（AGPL）。`DIVERGENCE.md` 是公开的**合并手册**（冲突分组/已回退项/工作流）——重度 fork 的工程实践范本，值得借鉴其形式。我们 09-17 的 ST 借鉴批次（槽位/世界书官方语义等）与之同源同域。

**高价值借鉴候选（按价值）**：

1. **前缀分叉诊断（`PromptDebugCache` + 诊断页）**——缓存每次生成实际发送的完整消息列表（按对话隔离、LRU 8 组、含上一次+本次），诊断页**定位上一轮与本次请求的第一条分叉消息并估算可命中前缀 tokens**，把「缓存为什么断在 6.7k 不再涨」变成可观察事实；另有 redaction 机制（插件注入内容在调试页遮蔽，防提取入口）。与拼装预览互补：我们做的是**单次请求逐段归因**（Tier1-4），他们做的是**跨请求差分定位**——对缓存线（#1542/PR #1630 的命中率追踪）是直接升级，小–中工作量。
2. **Jev 智能决策架构**（`data/ai/jev/`）——小模型（TypeSafe System One）路由层：记忆筛选 + 主模型快问快答。精髓在**契约设计**：`JevResult{Ok/Uncertain/Failed}` + 置信度阈值 + **Uncertain/Failed 一律静默回退原逻辑**（隐形决策层，判不出不得影响对话/弹错误）。我们 fastModel 已做标题/建议，该模式可平移（标题质量校验、记忆门控二次确认等）。中。
3. **R8 sealed 序列化 keep 家族 + SerializationKeepTest（5 项防回归）**——他们 release 实测「打开助手设置页闪退：Serializer for subclass LocalToolOption$ShowImage not found」。**触发模式=子类只被解码路径引用、无直接代码引用** ⇒ R8 判不可达删除。**fork 暴露面分析**：我们 `LocalToolOption` 全部子类在 `LocalTools.getTools`/开关页有直接引用 ⇒ 可达、安全（dex 层混淆改名后 grep 不可靠，按可达性分析）；但「新增 LocalToolOption/任何 @Serializable sealed 子类时必须有直接代码引用」应记为教训，或抄他们的 keep 规则 + 防回归测试（继承链 keep/不带 allowshrinking/$serializer+Companion 保留/通配符覆盖）。
4. **群聊**（GroupChatPage +1167 行 + GroupSpeakerSelector 说话人选择器）——多助手群聊，方向级大特性，fork 完全没有。产品决策问题非技术问题。
5. **宏引擎 2.0**（879 行，60+ 官方宏）+ **MVU 逐楼快照**（状态栏/世界书按楼显示）+ 斜杠命令——深酒馆兼容域。我们 09-17 吸收的是 ST 最小语义集（宏运行时/世界书/槽位）；60+ 官方宏与 MVU 变量系统是更深一层，受众=酒馆重度用户，按需。
6. **三层记忆/滚动压缩**（CrossWindowMemory/FixedMemorySection/MemoryEmbeddingService/VectorStoreCache）——与语义检索三方差评（不做）结论对照：他们的 RAG 记忆加了**相关性下限**（`d31c52ed5`，防低相关注入）——这个阈值思想对我们记忆注入通用。
7. **过程性小件**：工具描述去动态日期 + 按模式分叉（`b2748ade5`，与我们 cur_date 破缓存同源——已排查，fork 工具描述零动态日期 ✓）；memory_tool 描述瘦身 844 字符（`390eb225d`）；~~世界书预算不裁角色卡条目~~（`d31c52ed5`，**深评不吸收，2026-10-05**：fork 默认预算 0=不限制本就不裁，且卡内嵌世界书根本未导入（character_book 是 V2/V3 导入器的既有缺口）——它是「补 character_book 导入」立项时的**必带附属件**）；~~卡片正则三连锁修复~~（`5fdb74bc0`，**深评不吸收**：fork 不导入卡自带 `regex_scripts`，没有执行路径——但该提交是将来做卡正则时的**现成实现规格**：Kotlin vs JS 引擎三坑〔`${}` 当组引用抛异常 / `groups[name]` 对不存在组抛异常非 null / 反斜杠折叠毁正则字面量〕+ safeNamedGroup 降级 + StringBuilder 重载陷阱，全部有真实 157KB 卡测试背书）；Office 结构化读写（`342adadd9`，我们 document 模块只读）。
8. **允许数据库降级重建**（2026-10-14 用户备忘，**候选未立项**）——目的是让「版本回退」不再把应用打死。
   触发场景很现实：①同一台设备上 release 与 debug 交替安装；②**在本仓与华灯之间来回装**
   （两者**同名库** `rikka_hub`，而**华灯 DB 版本 30 vs 本仓 36** ⇒ 从本仓装回华灯就是一次降级）。
   **本仓现状**（`app/src/main/java/app/androidrik/rikkahub/di/DataSourceModule.kt:75` 的
   `Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")`）：**没有**任何
   `fallbackToDestructiveMigration`（全仓 `grep` 零命中）⇒ 打开版本更高的库文件时 Room 抛
   `IllegalStateException`（`Room cannot verify the data integrity` / 找不到 `迁移 新→旧`），
   **应用起不来**。注意：**数据本身没坏**，只是打不开，所以这是「可用性」问题而非「数据丢失」问题。
   标准做法二选一：`.fallbackToDestructiveMigration(dropAllTables = true)`，或注册 `Migration(新, 旧)`
   显式 `DROP` 后重建。**代价必须写明：会清空数据库**（对话/记忆/助手配置全丢）⇒ 要么只在 debug 构建启用，
   要么在有明确提示的前提下由用户确认；**不要在生产 release 上悄悄打开**。
   > ✅ **已定位（2026-10-14 复评更正）**：华灯 `4227ee716`（2026-10-14 12:49）就是这么做的，两个 builder 各加一行。
> 上一版此处写「未定位到实现」的原因是**本地克隆落后 198 提交**（当时该提交还没进仓），**不是指认有误**。
> **且它用的变体更准**：`.fallbackToDestructiveMigrationOnDowngrade(true)`，**只对降级清库，正常升级路径的数据不受影响**；
> 而 `.fallbackToDestructiveMigration(dropAllTables = true)` 是「任何迁移失败都清库」，粒度更粗。详见下方 §8.1 A.①。
**不适用**：逆向工具链（已移除，APK 109→100MB 的路线）；Chaquopy Python 桥（我们 eval_javascript/MCP/Workspace 已覆盖且不做 Python）；离线 OCR（我们评估过不做）。

**建议优先级**：数据库降级重建（**已定位现成实现、2 行 × 2 处、只影响降级路径**，见 §8.1 A.①）

> **⚠️ 复评提醒（2026-10-14）**：华灯持续合入上游，其提交清单里**混着大量上游提交**。
> 复评时请先做「上游交集」减法（本批 182 条实质提交里约三分之一是上游提交），否则清单会被上游噪声淹没；
> 另判定「借鉴关系」前，顺序是：**先查上游有无这个文件/能力（最先）→ 再把两旁文件逐字 `diff` → 最后才看各自历史**。

### 8.1 增量同步复评（2026-10-14，`4dcd60603` → `a9b95d45c`，198 提交）

**同步方式**：本地克隆 `<work>/rikkahub-sillytavern-android`（默认分支就是 `huadeng`）落后 198 提交，
`fetch` 后 `merge --ff-only` 到 `a9b95d45c`（tip 即「Merge rikkahub/master into huadeng」）。
**上一节 8 项「高价值候选」的编号与结论仍然有效**，本小节只记**新增**内容。

**规模**：198 提交里 nightly 自动提交 14 条，实质 **182 条**（fix 76 / feat 63 / refactor 12 / docs 12 / chore 6 / test 2 / perf 2 / ci 1）。
按模块：**webview 21**、chat 15、settings 16、docs 10，其余分散。

#### A. 直接解决本仓待办的（最高价值）

**① 数据库降级重建——已在华灯落地，本仓可照抄（`4227ee716`，2026-10-14）** ✅
发布说明原文：「从更高版本回退安装时，设备上的库版本会高于本版 schema，此前 Room 直接抛
migration not found 崩溃；现在降级时清库重建」。实现只 **2 处 × 2 行**，两个 builder 都加（主库 +
备份暂存库校验用的 factory）：

```kotlin
// 从更高版本回退安装时库版本会高于本版 schema，清库重建而不是直接崩
.fallbackToDestructiveMigrationOnDowngrade(true)
```

> **⚠️ 更正本项目上一版备忘**：节 8 第 8 项当时写「未定位到华灯的实现」——原因是**克隆落后 198 提交**
> （该提交 10-14 12:49 才进仓），不是指认有误。**现已定位，结论成立**。
> 另注意它用的是 **`OnDowngrade` 变体**，比备忘里写的 `fallbackToDestructiveMigration(dropAllTables = true)`
> **粒度更准**：只对「降级」清库，**正常升级路径的数据不受影响**。

#### B. 新增的大特性（本仓完全没有）

| 特性 | 规模/形态 | fork 现状 | 价值判断 |
|---|---|---|---|
| **前端卡运行环境**（WebView 消息区） | `richtext/` 下 `CardHostBridge`/`CardHostShim`/`HtmlCardDocument`/`HtmlWebViewBlock` 等约 **1700 行** + `pages/webview/`；21 个提交反复打磨 | **零命中** | **方向级大特性**：整条消息区改由单个 WebView 渲染，向卡片注入酒馆风格桥（`TavernHelper`/`SillyTavern.triggerSlash`/变量读写/按钮回传消息）、宏补全、ResizeObserver 高度自适应；带 HTML 前端的卡自动进「前端卡专属模式」并可在设置里强制开启，**会话中途不切换**（原逐条内嵌路径保留）。属产品决策（重、且与 fork 的渲染链冲突），**不建议跟**，但可知悉其形态 |
| **画板（sketch）** | 3 提交：接入 `androidx.ink`（压感/低延迟/多笔刷）+ 文字与选择工具 + 聊天/媒体创作可手绘或在图上圈画 | **零命中**（连依赖都没有） | **中**：fork 无绘图能力；价值取决于「圈画后发给模型」的使用频率。技术上是独立新页，不侵入现有链路 |
| **子 agent（`delegate_agent`）** | `data/ai/tools/AgentTool.kt`（90 行）+ 设置开关（默认关）+ 步数上限 24。**设计不同**：单工具、子 agent 无会话历史、固定精简工具面（文件/搜索/抓取/计算器）、**不含 delegate_agent 自身防递归**、返回最终答案截断 12K | **本仓已有更强的子代理**（`agent_run` 表 + 预设 + 并行闸 + 审批 + 预算规划器，Phase 1–4） | **不跟**：本仓实现更完整，方向一致而粒度更细 |
| **主动消息（proactive）** | 大量提交，且**连续修四层 P0**（清空聊天记录 / 对话归属被改写 / 定时永久静默 / 丢数据路径），并补「执行日志页」（看「上次为什么没发」） | **零命中** | **不跟**：fork 无此功能；其反复出 P0 说明形态本身风险高，且本仓没有这块产品需求 |
| **多 author note** | `feat: support multiple independent author notes` + 测试覆盖 | 零命中 | **按需**：属酒馆兼容域小件 |

#### C. 一条容易误判的来源（已更正）

初稿本节写成「华灯借了本仓」，**是错的**。逐字比对后的事实：

| 版本 | 作者 / 提交 | 行数 | 说明 |
|---|---|---|---|
| **上游原版** | `RE` / `c9a159a5c`（2026-10-12，「记忆改为按文件组织的 Markdown 记忆库」#2130） | **39** | Luhn 而无 BIN 白名单 |
| **本仓增强适配版** | `rikkahub-neomix` / `f4c2def29`（2026-10-09） | **104** | 保留 BIN 白名单、枚举化 |
| **华灯版** | — | **39** | 与上游版 **逐字相同** |

**结论**：上游先有简版（10-12）⇒ 本仓在其基础上做了**增强适配**（10-09 落库）⇒ **华灯直接吸收了上游原版**。
本仓文件头部就写明了出处：「来源=上游 #2130 的 `MemoryContentGuard`，本仓适配」。
故：既不是「华灯借本仓」，也不是「本仓借华灯」，而是**同源于上游、两边各自适配**。

> **复评教训（本条真正值得记的地方）**：初稿先看本仓历史、就据此断定「谁抄谁」，**没有先去上游查同名文件**；
> 而上游就有这个文件（39 行）、**华灯版与它逐字相同**——一条 `diff` 就能结案。
> 以后判「借鉴关系」的顺序应是：**上游有无（最先）→ 两旁文件逐字比对 → 才看各自历史**。
#### D. 属上游的（本仓已评估，无需重复）

清单里大量提交其实就是**上游提交**（tip 是「Merge rikkahub/master into huadeng」），例如
`b8d309e66`/`4061537da`/`927fdd51f`/`b69331473`/`923ad3d91`/`f6e3e49a4`/`2e572c204`/`c94237eaa`/
`660571449`/`93f231149`/`c72fa6853` 等 —— 已在本文件「2026-10-14 同步」节逐条评估过，**不重复**。
复评华灯时建议先减掉「上游交集」，否则清单会被上游提交淹没。

#### E. 其他值得记的小件（按价值）

- **P0 级修复**：`240c84ae5`「重新生成时找不到目标消息会清空整个对话（issue #7 第二层）」、
  `a9d6d2eb4`/`1f26d2393` 的「对话归属被改写 / 消息被清空」四层防护 —— 都是**数据安全**类，可作本仓同类路径的对照审计清单。
- **设置写入语义**：`4dbf303f8`「修改设置一律基于最新值，不再整份写回界面手里的快照」+ `3462d9ee1`
  「设置写入改为以内存为准，修复快速输入和拖动 slider 时丢更新」——与本仓 `SettingsStore` 的
  Mutex 串行化（D5）是**同一个问题域的两条解法**，值得对照。
- **性能**：`d1c81bf99` `LocalSettings` 改 `compositionLocalOf`、`640496127` 设置解码移出主线程
  （后者本仓已于 `13ab8a39d` 吸收）。
- **MCP**：`c907608bc` 升 SDK 到 `0.15.0-rikka.4` 修非标准 schema（本仓已另行评估，见上文）。
- **构建**：`8a2cb2354`/`95d22589a` 删 Daemon JVM 配置与禁自动下载 JDK（与本仓 `9f5b5b8ea` 同题，
  本仓实测「直接采纳会红测试」，结论仍是**本批不动**）；`942e09453` 为 SnakeYAML 补 R8 放行规则
  —— 与本仓「R8 keep 家族缺失」同题，**又一次印证该待办是真的**。

> **复评结论**：本批**华灯值得本仓吸收的只有一件** —— `4227ee716` 的
> `fallbackToDestructiveMigrationOnDowngrade(true)`（2 行 × 2 处，直接解决节 8 第 8 项的待办）。
> 其余要么是本仓已有且更强（子代理、记忆硬拦截），要么是产品级大特性（前端卡、主动消息），要么已在上游评估中处理。

## 8.2、2026-10-14 → 11-02 增量评估（2026-11-03，`a9b95d45c..90a5da9c4`，227 提交）

`--no-merges` 227 条：31 条 nightly `update.json`（忽略）+ 71 条 `re-ovo` 上游提交（用三个上游合并的第二父提交做 `merge-base --is-ancestor` 核验：54 条祖先 + 17 条 nightly rebase 重写副本，逐条有同标题孪生）+ 1 条 `RE`（上游 #2186）+ **124 条华灯自有**（121 MiaoWuNYA + 3 喵呜Miaowu）。总评：**吸收 2 / 候选 6 / 已有等价 10 / 不适用 106**。

### 吸收（2）

| 提交 | 内容 | 本仓落点 |
|---|---|---|
| `0fdc96f33`（AMOLED 部分） | AMOLED 下补 `surfaceDim`/`surfaceContainerLowest/Low/High/Highest` 纯黑或近黑，否则设置卡片仍是深灰「开关像没反应」 | `ui/theme/Theme.kt:102-111` 现只压 `background`/`surface`；约 10+8 行；注意角色覆写（`:86-101`）在 AMOLED 之前生效，压黑需一并决定。**✅ 已落地 `064a8b772`，真机验证通过（2026-11-03）** |
| `3300d0400` | 正则编辑框 `singleLine` + `maxLines=8`，防整段 HTML 替换串撑爆布局 | `ui/pages/assistant/detail/AssistantPromptPage.kt:1003-1041` 同一张卡无约束，4 行；属 `AGENTS.md` TextField 坑族。**✅ 已落地 `064a8b772`（2026-11-03）** |

### 候选（6）

`04600ed52` 流式正则只对正在生成的末条执行（本仓 `GenerationHandler.kt:605-624` 每 chunk 对全列表跑 `visualTransforms`，`AssistantRegexDiagnosis.kt:10-14` 已记「二次替换累积」隐患；改造需先确认历史消息是否已落库替换结果）· `37641722a` 迁移链单源化 + `addColumnIfNotExists`（本仓迁移数组在 `DataSourceModule.kt:107-113` 与 `LegacyBackupConverter.kt:86-92` 各一份靠人肉同步；`autoMigrations` 的 `ADD COLUMN` 非幂等，「本仓/华灯来回装同版本库」场景真实）· `71314869d` `DatabaseSchemaReconciler`（补表补列后再交 Room，覆盖本仓未覆盖的「同版本号不同 schema 外来库」，需先真机确认崩在 duplicate column 还是 identity hash）· `aad09b915` 采样补 `topK`（本仓 `Provider.kt:79-87` 无该字段；Anthropic/Gemini 原生支持，默认必须 null 防严格网关 400）· `3884e5f34` 代码块字号独立缩放（本仓 `HighlightCodeBlock.kt:127,432` 硬编码 12.sp，`PreferencesStore.kt:1565-1567` 三开关唯缺字号）· `5ffa6d4ca` 设置页通用背景图（装饰性；叠加半透明卡片时需与玻璃/可读性联动考虑对比度）。

### 已有等价（10，勿重复立项）

`50d27ae2d` 记忆冻结锚点 → 本仓 `PromptAssembly.kt:110-119,163-174` 更强 · `5de4079d8`/`0bac3f982`/`801c863b5`/`5c2e92731` LaTeX 归一化 → 已换 BetterText/KaTeX（`DisplayMathNormalizer.kt`/`LatexEnvironments.kt`；`\(..\)` 定界符需真机一条验证）· `0ccdd2cab` 压缩目标 token → `ChatPage.kt:838` · `029c92260` 备份提醒 → `BackupReminderConfig` · `f7587efa3` 图标三选一 → 本仓预置真 Activity（`AndroidManifest.xml:181`，刻意不用 alias）· `71f879136` 清理下线模型 → `CatalogMissingModels.kt` · `b2e6dffe5` 崩溃日志 → `CrashHandler.kt` + `LogPage.kt`。

### 不适用（106，分组）

酒馆助手 API / 前端卡 WebView 渲染链 / 插件 JS 桥 31 条（与 2026-11-03 反向迁 BetterText 冲突）· 群聊 / 生活空间 / 共读批注 / QQ Bot / 伴侣 11 条（`roadmap.md` 明确不做或产品级大特性）· 停用自动压缩 2 条（方向相反）· 设置信息架构 / 改名 / 入口隐藏 7 条 · 玻璃 / 抽屉装饰 6 条（本仓 haze 2.0 三开关已覆盖）· 品牌图标 8 条 · 自营商网关 6 条 · i18n 6 条 · 发布 / CI / 版本 16 条（含 2 条合上游）· 无障碍手机控制 3 条（`roadmap.md:402-403` 明确不参考；其错误码 `STALE/NOT_FOUND/PAUSED`、步数 12 + 120s 双预算、包名黑名单、敏感词审批规格已在评估报告存档，翻案可直接当规格）· 其它单点 10 条。

> 与 2026-10-14 复评口径一致：华灯值得吸收的仍是**小件**，方向级功能（前端卡 WebView、群聊、手机控制）继续不跟。

## 8.3、2026-11-03 → 11-14 增量评估（`90a5da9c4..8107f093e`，96 提交）

> 同步方式：本地克隆 fetch（SSH）。feat 逐条读标题、代表提交读 diff；fix 32 条全量过标题。release 3.1.2（versionCode 249）。

**主题簇**：① **酒馆前端六连**（`6056132e5` 资产管线+只读宿主「先铺地基不接线」→ `1ef76f673` 前端跑通 → `2aafaf5a3` 面板 API → `e35c3659f` CSS 变量 → `a0e8f1d9f` 兼容模式收敛逐条内嵌 → `d68cf1c91` 卡片面板改由酒馆运行时挂载，脚本与 MVU 真正生效）——深酒馆域方向级，**不跟（基调不变）**；其「先铺地基不接线、逐提交点亮」的渐进节奏可作大特性接入的节奏参考。② **记忆 RAG 两连**（`64451996f` 片段级召回 + 「注入走尾部冻结锚点不改前缀缓存」、`e7414fcc6` 参数可调 + fix 三连：语义+词法混合召回/单文件召回数上限/精简常驻提示词）——RAG 本体本仓三方定案**不做**，不变；「注入走尾部冻结锚点」与本仓 design-memory-injection-tiering frozen_anchors（`MemoryAnchorWiringTest`）**同款已有等价**。③ 头像裁剪/备份修复。④ 插件创造模式线。⑤ 手机控制加强。⑥ AI 浏览器。⑦ i18n/nightly/版本。

| 出处 | 判定 | 理由 |
|---|---|---|
| `48e98a622` **预请求 MCP Hook**（`PreRequestMcpHookTransformer` 88 行：每轮请求前静默调用指定 MCP 工具，结果插最新 user 前，仅当轮不入历史） | **候选（P3）** | 本仓 InputMessageTransformer request-only 管线可直接承载，接线成本低；价值场景 = 请求前拉外部动态状态（天气/日程/外部记忆索引）。「仅当轮、不写历史」与本仓 context purification 的 request-only 语义同构 |
| `48e98a622` 同提交：**主动消息前缀缓存修复**（触发说明/时间/电量移出最前 SYSTEM、追加到最后一条 user；`[PASS]` 子串匹配改整段标记判定） | 已有等价（结构免疫）+ 判据 | 本仓定时任务触发说明走 user 注入（`ScheduledTaskRunner`）或 headless `agent_run`，动态内容不进 SYSTEM 头部；TemplateTransformer 时间注入也在 user 尾部。判据成文：**动态注入内容（时间/电量/触发原因）一律尾部追加** |
| `87f169d35` 快捷消息短按追加自动换行/长按替换 | 已有等价 | 本仓 11-11 `055faeb38` 同款已落地（互证，同周独立实现） |
| `2bdfcd9b4` 信任用户 CA | 已有等价 | 上游 `90fe936ed` 已吸收 |
| `6a2823989` 余额查询 POST+JSON | 已有等价 | 上游 `6328f4a8d` 已吸收 |
| `291d85e3c` 模型面板多关键词搜索 | 已有等价 | 11-03 上游吸收轮已落地 |
| fix(chat) 流式分支收尾补推累计统计 + fix(usage) 多步用量累加落库 | **已有等价（顺手销上游挂账）** | 本仓 `GenerationHandler.kt:1649-1690` `roundBaseUsage.accumulateRound` + `lastUsage = stepUsage` 双口径已覆盖；上游 11-10 轮挂账的「921427f95 lastUsage 旧账」本仓已实现 |
| `a74fe1d98` Tinyfish purpose 参数 | 记账 | 上游 10-31 候选 `a5db9c9b7` 的第三方实现先例，重启时参考 |
| fix(ai) 缓存历史图片压缩结果 | 已有等价 | 本仓 MediaEncodingCache（上游 `ae599e8d7` 同判） |
| fix(ai) 空 role/非标准角色名兼容 | 已有等价 | 上游 `1575d179d` 已吸收 |
| fix(quota) 非英文系统余额数字格式 | 判据记账 | 数值解析须 Locale 无关；本仓 new-api 余额侧未逐一核，留作核对点 |
| fix(message) 折叠思考链保持待审批工具可见 | 判据记账 | 同型 UI 风险：折叠容器不得吞审批卡（本仓审批卡在消息 part 渲染，未复现，留观察） |
| 工具懒加载族（load_tools/懒加载组/常驻白名单三 fix） | 已有等价 | 本仓 MCP schema 分层加载（get_tool_map/mcp_tool_call 桥）更强；内置工具量小无需懒加载 |
| fix(chat) 注入转换器重复注册 | 判据记账 | transformer 注册去重守卫；本仓 DI 单例注册风险低，留观察 |
| fix(phone) phone_open 无需先读屏 | 判据记账 | 本仓 phone-use 已落地，「读屏先行」是否为不必要前置，真机验证时顺带核 |
| 头像裁剪 jpg 失败 → PNG/HEIF 预转 + 备份恢复按文件名重写路径 | 判据记账 | **裁剪输出格式必须与压缩格式对应**；恢复失败退回程序化头像兜底。本仓 backgrounds 归一化已做裁剪降采样，格式对应点未核 |
| 酒馆前端六连 / 创造模式插件小程序 / AI 浏览器 / 情侣空间 / 手机控制加强 / 头像切角色卡 / 请求体日志仅 debug | 不适用 | 域外/隐私边界（roadmap 明确不做）/酒馆域/已有等价（本仓日志脱敏+Debug 统计） |

**总评：吸收 0 / 候选 1（MCP 预请求 Hook，P3）/ 已有等价 9 / 判据记账 6 / 不适用 ~80。** 基调与 8.2 一致：华灯值得吸收的仍是小件；本轮唯一新增量是 MCP 预请求 Hook（低成本可接线），其余主力投入酒馆前端整线（方向级不跟）。

> 判决汇总见 [docs/peer-verdicts-2026-11-08.md](../../peer-verdicts-2026-11-08.md) §八。
