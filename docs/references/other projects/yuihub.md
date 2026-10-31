# Yuihub（xiaoyuili/Yuihub）借鉴评估：记忆 / 备份 / 压缩续写

> 评估：**2026-09-14**（记忆机制，原 §四）· **2026-10-02**（2.5.5→2.5.7，原 §4.5）· **2026-10-04**（备份体系，原 §4.6；压缩/半截/续写，原 §4.7）· **2026-10-26**（2.5.8→3.0.0，原 §4.8）· **2026-10-31**（工作区镜像站两件套移植，§四.9）。
> 定位：RikkaHub fork（包名 `me.yui.yuihub`，仅维护 en/zh）；**同源程度最高**，评估深度也最深（多批次增量同步）。本地副本 `<work>\projects\Yuihub\`。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 四、Yuihub 记忆机制评估（2026-09-14，源码级）

**来源**：`<work>\projects\Yuihub\`（`me.yui.yuihub`，RikkaHub fork）。**结论：值得借鉴，但价值在「检索与写入的组织方式」，不在「语义向量」——恰恰相反，它上线语义检索后第二天整套删除了。**

### 4.1 关键事实：语义检索上过又被删（时间线仅一天）

| 提交 | 日期 | 动作 |
|---|---|---|
| `a8c33b2d` | 2026-08-31 | **上线**语义检索：`MemoryEntity` 加 `embedding` 向量列（迁移 26→27→28）、新增 `EmbeddingService.kt`（OpenAI 兼容 `/embeddings`，10s 超时、失败降级关键词）、检索改打分制 `重要度 × 时效衰减(30天半衰) × (1 + 0.3×关键词overlap + 0.6×余弦相似度)`、按字符预算截断注入、`PreferencesStore` 加 `EmbeddingConfig` 三件套（URL/Key/Model + 测试按钮） |
| `fbd15331` | 2026-09-12 | **删除**：`EmbeddingService.kt` 整文件移除（-81）、`MemoryEntity` 去掉 `embedding` 与访问统计列（迁移 32→33 显式重建表，且 `WHERE assistant_id != '__global__'` 顺手清掉全局记忆）、`EmbeddingConfig` 及三处接线全删、`MemoryRepository` -170 行 |

当前代码里 `embedding` 仅剩三条残留（迁移注释、通用的 `ProviderConnectionTester`、`ModelType.EMBEDDING` 列表标签），**链路已删干净非停用**。

**意义**：Yuihub 条件比我们好（有 OpenAI 兼容路径、多作用域），仍选择放弃——这是对「记忆语义检索不做」的**独立二次印证**（与本仓 `design-auto-compaction.md` §6.3 的成本结论、以及 `roadmap.md` §一 的 `take(60)` 缺陷分析三方一致）。

### 4.2 值得借鉴（按价值排序）

**① 打分校准 + 字符预算，替掉「取前 N 条」—— 最推荐**

现状 fork 是 `memories.take(60)`，而 `MemoryDAO` 查询无 `ORDER BY`（SQLite 按 rowid 即**插入顺序**返回）→ 超过 60 条后进 prompt 的是**最旧**的 60 条，最新记忆反而进不去（缺陷详见 `roadmap.md` 一·中等价值）。Yuihub 的 `selectForPrompt` 完全不用向量：

```
score = importance × (0.55 + 0.45 × exp(-ln2 × age / 30天))
```

再按 score 降序、以 1800 字符（约 600 token）**预算**逐个纳入（非数条数）。`importance` 由模型写入时给（0.9+ 身份/强偏好、0.6-0.8 普通偏好/进行中项目、0.2-0.5 次要），`updatedAt` 驱动时效衰减。**注意 Yuihub 的 DAO 明确写了 `ORDER BY updated_at DESC`**——它连这个坑也踩过并修了。方案比单纯加 `ORDER BY id DESC` 更进一层：不只取最新，而是取**最该取**的。

**② 被动提取管道 `MemoryExtractor`**

定位是「与模型自觉调用 `memory_tool` 互补」：每轮回复生成完成后异步跑，取最近 6 条消息（过滤记忆快照本身），带**现有记忆索引**（`id | [category] content`，≤100 条）让模型输出 `{"operations":[{"action":"add|update|delete",...}]}`——因提示词含现有记忆，**LLM 天然完成去重与冲突消解**，不靠模型「想起来该调工具」。工程稳健处：

- **并发守卫**：`runningScopes` 单飞 + `pendingScopes` 记「最后触发的会话」，运行中再触发不丢弃、结束后重跑（注释：*「避免连续对话丢事实」*）。
- **防幻觉 id**：`if (existing.none { it.id == id }) continue`——模型编的 id 改不到别的助手（与本仓记忆审批同族顾虑）。
- **失败全 fail-open**：整条 `runCatching` 包住，解析失败直接 return，绝不因记忆提取搞坏正常回复。
- 走 `backgroundTextGenerationParams(model, ReasoningLevel.AUTO)` 后台生成参数。

**成本**：每轮回复多一次 LLM 调用（复用当前对话模型），由阈值兜底——自动整理要求「总数 ≥30 且自上次整理后新增 ≥10」，手动整理门槛 8。要做须配开关或仅长对话触发。

**③ 整理器 `MemoryConsolidator`**

把重复/相似/零散记忆合并成一条、修正归类、控制注入体积。落地讲究：合并**先落库新条再删源条**（避免中途失败丢信息）、`sources` 去重且校验真实存在、`consumed` 集合防一源被两组重复消费、合并条 importance 取源**最大值**（不因合并降权）、手动与自动整理间单飞保护。

三者合起来是自洽闭环：**写入靠提取管道（可靠、去重）→ 存量靠整理器（压缩、纠类）→ 注入靠打分校准（按价值取）**。fork 当时三者皆无；**后续落地状态见 §4.4 末的 2026-10-12 复核**（打分校准 + 整理器已落地，提取管道以「模型主动调 `memory_tool`」的形态覆盖）。

### 4.3 不建议照搬：记忆快照（append-only 合成消息）

Yuihub 把记忆从「每次请求临时拼进 system」改为**持久化的合成 USER 消息**（`isSynthetic=true`，插在最新用户消息之前，`withMemorySnapshot`），意图是「内容仅变化时追加、既有字节永不重写 → 请求前缀稳定 → 持续命中供应商前缀缓存」。识别分两层（`isSynthetic` + 固定前导语，供 UI/搜索/导出过滤）。

思路与本仓**压缩检查点同族**（append-only、合成消息、前缀字节稳定、发送窗口组装），说明双方对前缀缓存的认知一致。但嫁接代价高：需动 ChatService 请求组装、GenerationHandler transformer 跳过、导出/搜索/UI 过滤，**与本仓压缩窗口机制同层**，叠加后交互面不小（快照节点是否算进压缩区域？指纹怎么算？导出含不含快照？）。附带好处是记忆进历史、用户会话内直接可见（现状藏 system prompt 只能去记忆页查），但这点收益不值上述改动。

### 4.4 建议顺序

1. **先拿打分校准 + 预算截断**（含 DAO 补 `ORDER BY`）——成本小、直击现存 `take(60)` 实缺陷、无需新依赖，投产比最高；
2. **再评估提取管道**——须先决定是否接受「每轮多一次 LLM 调用」（其可靠性来源也是最贵部分），建议做成可选开关 + 阈值触发；
3. **整理器随提取管道一起上**（无提取则无膨胀压力，单独做意义不大）；
4. **语义向量：不做**（三方独立结论一致）；**记忆快照：暂不做**，等压缩窗口真需统一时再议。

> **2026-10-12 复核（本节三项的最终落地形态）**：**四项建议全部走完，但「提取管道」换了一条路**——
> - **①打分校准 + 预算截断 ✅**（`91d88551`，见下方 09-15 补充）；
> - **②被动提取管道 ⚠️ 未按本节的 `MemoryExtractor` 形态做**：fork 采用**模型在轮内主动调 `memory_tool`**（配合 create/edit 分档审批 + 幂等去重 + 200 条上限淘汰）——**同样的「可靠写入 + 天然去重」目标，但不额外增加每轮一次 LLM 调用**，因此本节「须接受每轮多一次调用」这个前置代价**不再成立**。若将来要补「模型忘了调工具」的兜底，这里的 `runningScopes` 单飞 / `pendingScopes` 防丢事实 / 防幻觉 id / fail-open 四条仍是现成规格；
> - **③整理器 ✅ 已落地**（「做梦」四批，2026-10-09）：`MemoryConsolidationScheduler`（轮末空闲 2 分钟 / 攒够 6 轮 / 切后台 / 启动补漏 / 手动「整理一下」）+ `MemoryConsolidator` worker + 审批降级（写入审批开着 ⇒ 只提案不改库）+ 成本护栏（步数上限、每库 6 小时自动频次、离线跳过）。**与本节建议的差异（有意）**：整理**不改动** `sources`/`consumed` 那套合并记账，改为「整理 agent 直接调 `memory_tool`」；提案形态取**决策 B**（会话内通知 + 记忆页列出建议，不落库），理由见 `design/design-memory-consolidation.md` §7.2/§8.3。**未做**：独立前台服务保活、「离开对话」钩子。
> - **④语义向量不做 ✅ 维持**（本仓独立复算：全仓 `generateEmbedding` 仅声明、零调用方，`design-memory-scoring.md` 与本节结论一致）。

> **2026-09-15 补充：第 1 项已实现（`91d88551`）+ 设计落盘 → `design/design-memory-scoring.md`**（含与本仓代码/真实数据的逐条核对、tokenizer 可替换性分析、回收站的两个坑）。
> **重要更正**：核对后问题重心不是「换排序」而是「全量注入需砍到预算」——本仓两个上限（60 条 / 100K 字符）在实测数据（54 条 / 60K 字符）下**都没触发**，即当前把全部记忆约 4 万 token 塞进 system prompt；且 Yuihub 的 1800 **字符**预算会把 54 条砍到 7 条，**不可照搬**（已定为 token 计、起步 5K–10K）。

## 四.5、Yuihub 2.5.5 → 2.5.7 增量评估（2026-10-02，09-23 后约 45 提交）

**直接可吸收（小件，均 fork 核实存在同款缺口）——✅ ①②③ 已于 2026-10-02 落地（真机验证待用户）**：

| 项 | Yuihub 提交 | fork 现状与要点 |
|---|---|---|
| **备份 DB 快照改 VACUUM INTO** | `47e7dfaf` | fork `addDatabaseToZip` 是**热拷 db/wal/shm 三件套**（无读事务）——备份瞬间有写入即可能拷到不一致状态/半页（09-15 的 WAL 修复只治了**恢复方向**）。VACUUM INTO=单读事务一致性快照；**必须带回退**（fork minSdk 26 < SQLite 3.27 不支持该语句，老设备回退热拷）。~1h |
| **write/edit 二进制误写防护** | `8a3a37ac` ISSUE-08 | fork `WorkspaceTools:582` 用有损 `toString(UTF-8)` 解码后写回——非文本文件被静默写坏。U+FFFD 检测 → 按二进制拒绝引导走 shell。~30min |
| **工具参数解析加宽松重试层** | `0eaf2057` | 与 fork #1985 修复（`ToolArgumentsJson`）**互补不重叠**：Yuihub=strict 失败后 lenient 重试（治未引号 key/尾随逗号），fork=取第一个完整 JSON 值（治双对象拼接）。可串成三层 strict→lenient→首值提取。~30min |
| ~~**workspace rootfs 补 ca-certificates**~~ ✅ **已吸收（2026-10-16）** | `8a3a37ac` | ubuntu-base 裸镜像无 CA 证书 → 容器内 https 全挂。**真机核实**：fork 的 rootfs 连 `etc/ssl` 目录都没有，`apt-get update`（https 源）报 `No system certificates available`。按 Yuihub 的离线兜底实现（复用 Android 系统证书库生成 `ca-certificates.crt` + 逐证书 `<hash>.0` 副本）；**真机前后对照**：同一条命令从 `Certificate verification failed` 变成 `Fetched 1632 kB`。<br>**四处偏差/发现**：①本仓 patch 是**每次会话启动**都跑（无 Yuihub「已打过标记」的短路）⇒ 既有 rootfs 天然自愈；②因此把它的「bundle 非空即短路」改成「**内容变了才写**」（否则证书库永远停在第一张快照，而 Android 更新 CA 后的失效是静默的）——真机上旧 bundle 含重复、内容不同 ⇒ 被正确重写，这条偏差顺带被验到；③**真机验出去重缺陷**：`/system/etc/security/cacerts` 与 apex 是同一批 145 张，不去重会翻倍（290 / 1.5MB），已按文件名跨来源去重 ⇒ 145 / 758KB；④本仓 rootfs **全程不跑 apt**（`workspace` 模块 apt 零命中）⇒ Yuihub 那半「apt 列表补 ca-certificates」不适用，真正要补的就是证书本身 |

**对照项（架构对撞，维持自研不跟）**：
- **子代理独立子会话**（`52a679d5`/`30783483`/`b7e75fb2`/`06738520`）：Yuihub 把子代理做成真实子会话落库（`parent_conversation_id`）+ 抽屉会话树 + `Semaphore(4)` 真并行。fork 设计稿早已论证过「复用 Conversation 表=12 列表查询/3 聚合漏过滤即静默泄漏」的风险而选独立 `agent_run` 表——**Yuihub 10-02 还在修子代理缺陷（终态/工具越权/edit 缩进），反向验证了我们的选择**。可观测性 UI（抽屉会话树）是好想法，fork 已有 run 详情页等价物，暂不跟。
- ~~**空回复补救**~~ **✅ 已落地（2026-10-02，主循环同款机制：一次性 recovery_note 进系统提示不进历史）**。

**功能灵感（大，未立项按需）**：
- **定时任务/自动化**（`2906828e`+`65525df9`+`e585d82b`+`191f5a8d`，~2300 行）：助手级 ScheduledTask（WorkManager 调度）+ **AI 可在对话中管理本助手的定时任务**（工具化）+ 完成悬浮通知 + 按助手过滤。fork 的时间提醒只是会话内提醒，这是真调度执行（=助手 cron agent）。大功能。
- ~~**todo_write 计划工具 + 计划细条**~~ **✅ 已落地（2026-10-02，方案 E 终态：PlanBar 输入栏上方常驻、正文不渲染、按助手门控 TodoPlan）**，单测 7 项。
- **models.dev 目录匹配**（`ecaca331`，~1000 行含 fixture 测试）：按 modelId 自动补全 contextLength/能力/图标——可作 fork 手工 ModelRegistry 的兜底源。
- **附件注入「封面先行」+ 放开类型白名单**（`bee1dd38`）、**workspace 写入冲突检测 mtime**（`f91d3b16`，与 edit_file dry-run 互补的 TOCTOU 维度）→ **✅ 已落地（2026-10-02，write_file expectedMtimeMs/force + read_file 输出 mtime 闭环）**、**release 体积 31.7→23.1MB 清理**（`7b8c918a`，其手段=砍功能，fork 未评估）。

## 四.6、Yuihub 备份体系对照（2026-10-04，`36b719ef`/`34f1a976`/`3eddec42`/`47e7dfaf`）

**真缺口（fork 核实）——✅ ①②③ 已于 2026-10-04 落地并真机验证通过（日志取证：images 恢复/integrity ok/completed）**：
1. **生成的图片不进备份**——fork `BackupItem.FILES` 只含 UPLOAD/FONTS/SKILLS，而
   `FilesManager.getImagesDir()` = `filesDir/images`（生成图片落这里）⇒ 恢复后消息图片全裂
   （与 Yuihub 修的「DB 记录还在文件丢了」同款）。修法=FileFolders.IMAGES + 打包/恢复/预检三处。
2. **目录清单三处手列**——`collectBackupInputs` / `addAppFilesToZip` / restore 分支各自枚举目录，
   新增目录漏一处即静默不备份。抄 `BACKED_UP_FILE_FOLDERS` 常量 + CoverageTest（断言与 FileFolders
   声明一一对应，漂移即测试红）。1+2 可合并一批。
3. **staged DB 缺 integrity_check**——swap 前对暂存主库跑 `PRAGMA integrity_check`，不过则中止
   （旧格式/外来备份兜底）。~30min。

**已有/不吸收**：
- 旧格式备份残留 WAL 清理、tool_outputs zip-slip canonicalPath 边界校验——fork 均已有（09-15 修复 + restoreFlatEntry 现行实现）。
- **PendingRestoreStore（恢复续传）**：完整解「恢复中被杀→半途库」。fork 暴露面更小（重命名原子换入、
  失败保留旧库；.restore-tmp 留 dbDir 不碍 Room），残余窗口=清 sidecar 与 moveIntoPlace 之间被杀丢
  最近未 checkpoint 尾部——**可选低优先**，重构面大（启动期检测要在 startKoin 之前）。
- **RikkaHubImporter 列名交集搬运**：Yuihub 因 schema 分叉（上游≤25/其 36）才需要；fork 是上游迁移链
  超集，文件级替换+Room 迁移本就工作——不适用。
- **自动备份**（默认关/WorkManager/仅留最新一份 auto_backup/）：功能方向可取，中等工作量，按需立项。

**其他新提交简评（10-03/04）**：压缩批次（`542a6762` 开关+/压缩 手动压缩——fork 09-13 已有；
`499be973` 区分显式结束与裸断流、统一输出截断检测——与我们半截草稿/tool 截断同域可细看；
`93f9e7f0` 检查点落库+统一未完成检测+自动续写——与 #1812/队列同域）；`0502e45d` 工具间过程解说
并入思考折叠（UX 细节可借鉴）；`a045cbf9` 工具结果统一 JSON 契约（senera Observation 投影的轻量版）。

## 四.7、Yuihub 压缩/半截/续写批次深评（2026-10-04，先不吸收）

对照对象：`542a6762`（自动压缩开关+/压缩）、`499be973`（显式结束 vs 裸断流）、`93f9e7f0`（检查点落库+统一未完成检测+自动续写）、`3a721242`（手动压缩交互）。

**结论：压缩批次无吸收点；真正值钱的是 499be973/93f9e7f0 揭示的 fork 三个缺口。**

### A. 压缩批次——平级，无借鉴
fork 09-13 的 harness 式自动压缩更早更全（检查点式/轨迹只追加/区域指纹失效/参数配置档/阈值与保留比可配）；手动压缩有配置档与「无可压缩」区分提示。Yuihub 是同域的简化实现。

### B. **静默截断缺口（fork 实锤存在，最重要）**
Yuihub `499be973`：只有收到**显式终止**（[DONE]/message_stop/response.completed）才算正常结束；连接提前关闭（裸 EOF——中转断流常态）标记 `UIMessage.finishIncomplete`，另加 `isOutputLimitTruncation()` 统一 length/MAX_TOKENS/max_output_tokens。
**fork 现状（实测）**：所有 SSE provider 的 `onClosed → close()` **无异常正常收尾** ⇒ 裸断流被当正常结束——#1812 不触发（只接异常）、无横幅、无草稿、用户看到戛然而止的回答。且 UIMessage 不持久化 finishReason ⇒ 连「撞输出上限」都无法与正常结束区分。
吸收方向：decoder 加 explicitEnd（四 provider SSE 监听记录显式结束，onClosed 无 explicitEnd ⇒ 标 incomplete）→ 上游先做「未完成」提示（省），自动续写另议。**中等工作量，跨 ai 模块四 provider。**

### C. 检查点落库——✅ 已落地（2026-10-04/05 B/C/D 批次：流式 2s 检查点/finishIncomplete/自动续写，见 roadmap）
fork 流式期间只走纯内存、落库点仅轮次结束/取消收尾（b1273a72 分析确认）⇒ **崩溃/杀进程丢半截内容**（#1812 只覆盖失败路径）。Yuihub 方案=onCheckpoint 回调（每 chunk 合并后，视觉转换后消息）+ 调用方节流 upsert **只动正在生成的节点**（不删其它）+ 崩溃后重进可见断点。fork 落地注意：①节流间隔（ANR 刚修，落库频率须节制）②与 #1812 的 NonCancellable 收尾合流 ③复用 updateMetadata/updateConversation 原语。

### D. 自动续写——与 #1812 哲学冲突，建议拆开
Yuihub：截断（length）与裸 EOF 注入续写提示（**复用我们刚落的 pendingRecoveryHint 通道**），上限 3 次，续写追加同一条消息 + `mergeTrailingAssistantTextParts`（防续写新建 Text part 导致 markdown 块空行——这个坑值得直接抄）。
fork #1812 的既定哲学=「保留草稿 + 用户手动重试」（成本控制权在用户）。两者不互斥：**裸 EOF（网关问题非模型选择）自动续写合理；length 截断自动续写烧费风险高（长文可连烧 3 轮），宜提示+手动或可配置**。四分类提示（length/content_filter/裸 EOF/只思考）可补充我们刚落的空回复补救（当前只覆盖「只思考」）。

**建议优先级**：B（静默截断，真缺口）> C（崩溃丢失，已知缺口有现成方案）> D（续写，跟 B/C 捆绑决策）。合计约 1–1.5 天。

### ✅ 2026-10-04 落地（B/C/D 三项，分三批提交）

| 批 | 提交 | 内容 |
|---|---|---|
| B | `9d5774ee` | `MessageChunk.explicitEnd` + `terminalChunk()`：四个 provider 在显式结束处发标记（`[DONE]`/`message_stop`/`response.completed`/Google 带 finishReason 末块），`onClosed` 不发 ⇒ 裸断流可判定；`UIMessage.finishReason`/`finishIncomplete`（JSON blob 零迁移，语义=最后一次流的结束状态）；`isOutputLimitTruncation`/`isContentFilterTruncation` 纯函数统一三家命名 |
| C | `457c102d` | `ConversationRepository.checkpointGeneratingNode(conversation, nodeIndex)`：只序列化并 upsert 一个节点（不按前缀比对全会话、不重建 FTS——索引在轮次结束的正常保存时补齐）；ChatService 每 2 秒节流调用，失败只记日志。**nodeIndex 取 `chunk.messages.lastIndex` 而非 messageNodes.lastIndex**：THIS_ONLY 重生成只改动中间节点 |
| D | 本批 | `ContinuationPolicy.autoContinueHintFor` 纯函数：裸断流**恒**自动续写、撞输出上限**默认只提示**（`RetrySetting.autoContinueOnLengthTruncation` 可开）、两类共用上限 3 次；续写走已有的一次性提示通道追加到同一条消息；`ChatService.continueMessage` + 消息不完整横幅三档（内容拦截不给续写按钮）+ 设置开关 + 6 语言 |

**与 Yuihub 的差异（刻意保留）**：Yuihub 对 length 截断也自动续写（上限 3）；fork 保持 #1812 的「成本控制权在用户」取舍——length 默认只提示 + 手动继续。另：Yuihub 的 `mergeTrailingAssistantTextParts`（续写新建 Text part 在正文多出空行）在 fork 不需要——fork 的同角色 chunk 追加规则会把续写文本拼进最后一个 Text part。

## 四.8、Yuihub 2.5.8 → 3.0.0 增量评估（2026-10-26，`6dcc7224` + `2631f2d3`，评估完成、未落地）

> Yuihub 3.0.0（2026-10-25，`6dcc7224`）为巨型提交（298 文件 / +3.3 万行；-62 万行主要是**删除 jieba 分词词典资产**），宣称「重构数据备份与恢复：分类备份、清单校验、恢复预览、安全备份与启动回滚；更新记忆系统、提示词预算、请求解剖与工具链；重做备份页/助手设置页」。另有 `2631f2d3`（DeepSeek 搜索改官方 Search API）、`08825f5a`（发版 3.0.1）。上游 rikkahub 同期**无新增**（仍 `ad2c4430d` / 3.1.1，已评至 `a8cf290c1`）。**本节为评估快照**；建议批次见 [索引 §三 落地表](../../peer-apps-benchmark.md)（原 §五）9–16 行——表 9–15 已于 2026-10-26 全部落地（上下文闸/MediaEncodingCache/只读快照/manifest+安全备份/B 档 staged 恢复，销账见各行状态），表 16 仍为记账不排期。
>
> 附：§四.6「其他新提交简评（10-03/04）」里 `0502e45d`（工具间过程解说并入思考折叠）**已随后在 fork 落地**（`ChatMessageNarrationStep`，ChatMessageReasoning.kt），此处销账；未评小件仅剩 `12b27763`（侧滑页助手卡片+四宫格，其 UI 路线 fork 不跟）、`206aea90`（分时问候空态，可选 cosmetic）、`1c2f9a8b`（CI，fork 禁用 CI 不适用）。

### A. workspace——ReadOnlyMountSnapshots：**值得吸收（安全缺口，第 2 批）**

Yuihub 的 PRoot `-b` 不支持只读，原 `readOnly` 只是语义标记，`workspace_shell` 可绕过工具层保护直接删只读挂载源。3.0.0 改为**执行前把只读挂载源复制成私有快照目录再绑定**：

- `workspace/.../ReadOnlyMountSnapshots.kt:19-86`：`prepare()` = SHA-256 指纹、未变化复用、1GB/64 代预算、symlink 逃逸/环检测、复制中变更即 fail（+359 行测试）；
- `ProotShellRunner.kt`：`prootBindSpec()` 改 `require(!readOnly)`（只读挂载必须先快照）；会话崩溃重试的 `sessions.remove(root, entry)` 竞态修复——**fork 是无状态 per-command 进程，此修复不适用**；执行串行锁另议；
- 触发点：`RepositoryModule.kt:60-76` 仅 `/upload` 标 readOnly。

**fork 现状（缺口实锤）**：`WorkspaceBindMount`（`workspace/.../ProotShellRunner.kt:15-22`）无 readOnly 字段，bindMounts 直接 `-b` 原件；挂载表含 `/skills` `/builtin_skills` `/tool_outputs` `/upload`（`app/.../di/RepositoryModule.kt:86-103`）；`/upload` 只读仅靠提示词（`WorkspaceReminderTransformer.kt:233`）+ 工具层拦截，shell 可绕过（Yuihub `dec9f2f4` 文档里自己也承认这是 pre-3.0.0 状态）。
**吸收最小面**：只对 `/upload` 快照（skills 允许 AI 写），约 200 行 + 接线 + 测试。

### B. ai 模块——上下文闸/编码缓存/日志脱敏（第 1 批 + 小件打包）

**B1. 工具循环上下文闸（`enforceContextBudget` + `toolOutputBudget`）——优先级最高**。Yuihub 在工具循环内加最后防线：占用 65%/80% 时动态收缩单条工具输出预算（32K→16K→8K）、90% 时把旧工具结果卸载文件留 2K 预览、97% 硬限报错（GenerationHandler）。
**fork 缺口实锤**：`GenerationHandler.kt:1399` 有 `|| !hasShellAccess` 短路 ⇒ **无 shell 时超大工具输出不截断直接进历史**（与 Yuihub「截断始终生效」相反）；`ContextBudgetPlanner` 自述只管无头 run，聊天工具循环无闸。吸收时注意：闸的阈值/卸载路径要与 §C tool-output-budget 句柄体系与 §四.7 explicitEnd 语义对齐。

**B2. MediaEncodingCache——值得吸收**。base64 编码结果按「绝对路径:length:lastModified」缓存（含压缩+EXIF 矫正），3.0.0 把旧条目数 LRU 重构为**字节预算 LRU**（总 8MB、单条 2MB、`@Synchronized`、超限拒存）。动机：多轮会话历史图片每轮重发，重复「解码→EXIF→缩放→JPEG→Base64」使首字延迟随轮次线性恶化。**fork 同款问题**：`ai/.../util/FileEncoder.kt:52-115` 每次调用重新 `compressAndEncode`/`encodeToBase64Streaming`，零缓存。单条 2MB 上限按 fork 大图策略可再调。

**B3. provider 日志脱敏——值得吸收（机械改动）**。四 provider 的请求体/SSE payload/错误响应体改 `messages=N`/`chars=N`/`httpCode=N`/异常类名，删 `printStackTrace`。fork 仍打全文：`ClaudeProvider.kt:134/182/185`、`GoogleProvider.kt:253/262`、`ChatCompletionsAPI.kt:369`、`ResponseAPI.kt:113/122`，且有裸 `println`（ChatCompletionsAPI.kt:240）。**别照抄其 SSE 日志方案**：Yuihub `describeSseEvent` 连结束事件类型都丢了（`StreamChunkDecoder.kt:29-31`），不利诊断裸断流——fork 版应「正文事件只记 chars，结束事件保留 event 名」。

**B4. 小件**：TodoTools 结果 JSON 加 `next` 字段 + 无 in_progress 警告（fork 只有「多 in_progress」警告，`local/TodoTools.kt:110`）——治「建完清单不复更」，便宜，吸收。

**B5. 可选（记账不排期）**：`history_search`/`history_read`（FTS 检索当前会话被压缩归档的消息 + 按 node_id 分页读原文，`ConversationTools.kt` +129 行——取决于 fork compression/ 产物是否可寻址）；SkillsTools 内联 24 条上限（fork 只截描述 1024 字无条数上限）；GenerationHandler 增量 emit（`generationMessageUpdates` 只带新增消息）；`customApiType`（自定义 /v1 路径开关，产品特性）；StreamTraceReplayTest 的 fixture 离线重放（SSE 抓线存 `events.jsonl` 回归裸断流——思路好但 fork 解码内联在 provider listener，需先抽 decoder 接口，成本大）。

**B6. 已有等价**：RequestBreakdown（fork `PromptInspector.kt` PromptSegmentKind 分段计数）、onUsage 回调。

### C. 记忆/提示词——架构换代，但无一推翻 fork 既有决策

Yuihub 记忆改动是**架构换代**：写入带 keywords/weight(1-4)/kind、delete→归档、快照落库三分块、jieba→纯函数分词、整理管道三阶段化。逐项对照：

- **MemoryText.kt（新 148 行）——已有等价**：NFKC + CJK bigram（fork `MemoryRelevance.kt:161-195` 同款且注释已论证不引词典）、Jaccard 去重（fork :113-138 明确论证过**为何不用 Jaccard**）、rankHits = 候选集 IDF×覆盖率×45 天半衰期×weight。**可选**：a) 写入时相似度去重（fork `MemoryRepository` create 无去重）；b) 其 IDF 是候选集相关的连续量，若进注入层会破坏 fork「逐字节可复现/分档量化」（`design-memory-injection-tiering.md:74`），**只可进 searchMemories 工具路径**。
- **ToolSchemaCompactor（新 99 行）——值得吸收（第 4 批）**：请求准备期裁工具/参数描述（句子边界截断，保参数/枚举/required），memory_tool 等 ESSENTIAL_TOOLS 给 3× 上限防纪律文本被裁坏，三档设置（:15-60）。fork 只有测量（ToolSchemaSizeTest）无压缩。RequestBreakdown（给用户看的请求解剖）= fork PromptInspector 已有等价。
- **MemoryConsolidator（-373 行）——主体不吸收**：重写为 merge（非 core 取 80 条）→ 模型判 archived → **core 重写**（1200 字符预算、rewriteCore）三阶段 + onProgress 四段 + 24h 冷却 + MemoryEvent 流水 + 专属维护模型。它直接改库、无审批概念——与 fork「整理 agent 走 memory_tool + 提案不落库」（§8.3/§9.1）语义正交，不采纳即无冲突；core 重写-压预算与 fork「注入期分层裁剪」是刻意不同路线。**可选**：整理完成的**结构化事件流水**（fork 进度表只存最新态）。
- **MemorySnapshot 三分块（core 原位替换 + hit 幂等绑定 + rebase 单块）——不吸收**：为「持久化进会话历史」的落库架构服务；fork 请求期组装（buildInternalMessagesWithPlan + MemoryInjectionPlan）天然幂等，§四.3 已论证不落库。
- **MemoryTools——已有等价为主**：fork 有 search action + expected_revision 乐观并发 + 审批门 + 命中削限提示 + read credit 记账。**可选**：keywords 字段（5-10 个召回词，让匹配面向「用户会说的话」，需动 schema/DB）。weight 整数化、kind 分类与其 3.0.0 体系绑定，不迁移。
- **DefaultPrompts/LegacyPromptDefaults——产品决策可选**：提示词外置为 Settings 可编辑模板（PromptId 枚举 + disabledPromptIds + isPlainChatMode，一次性等值迁移）。与 fork「纪律文本防漂改」取向有张力。
- **⚠ 待核实**：后台整理/提取的 LLM 调用 token 是否已计入 fork 用量统计（Yuihub 走 TokenUsageSource.MEMORY + token_usage_event 按请求记账；fork `UsageRecorder.kt:66` 按请求/重试记账，后台调用是否覆盖待查）。

### D. 备份/恢复——manifest+预览值得吸收；启动回滚难题已被化解

**D1. manifest + 双 SHA-256 + 恢复预览——值得吸收（第 3 批，~1 天）**：Yuihub `BackupModels.kt` 定义 7 类 + Manifest v2（formatVersion、db/settings 双 SHA-256、counts、分项字节）；`LocalBackupService.kt:181` inspectBackup + `BackupPage.kt:772` RestorePreviewSheet（按 presentItems 勾选、分项大小/条数预览）；`:300` RestoreByteBudget（单条 2GB/总 8GB）+ 重复条目/10 万条目上限。**fork 缺口**：`BackupArchiveEngine` 无 manifest、无校验和、无预览，恢复直接执行（`BackupVM.kt:142`）；设置在解压时**即时写入**（Engine:228）；DB 校验仅 integrity_check（:398-414）。顺带防损坏备份/高版本备份/zip 超限。7 类细粒度本身是 UX，可选。

**D2. 安全备份 + 启动回滚——分两档**：
- **A 档（恢复前自动安全备份，~0.5 天，建议立即做）**：恢复含 DB/SETTINGS 时先 `createSafetyBackup`（`LocalBackupService.kt:254`，safety_backup/ 留 3 份），fork 可复用 prepareBackupFile。
- **B 档（staged 换库 + 启动应用 + 失败回滚，~2-3 天，可选-高价值）**：全部先 `PendingRestoreStore.stage`（:76 fsync+原子 marker）**不落现库**；启动时 `applyIfPending`（:129）先把现库+设置（含 -wal/-shm）拷成 rollback 副本→校验 user_version+integrity→temp+rename 换入→写 applied.marker；Room 打开成功（含迁移）才 `confirmApplied`，否则下次启动 `rollbackIfUnconfirmed`（:193）。**§四.6 判定的「startKoin 之前检测难」被化解**：不是「之前检测」而是**推迟 startKoin**——`YuiHubApp` StartupState 状态机，恢复成功才 initializeAndPublish，失败停在 Failed 可重试，Workers 先 `WorkManager.initialize` 再 await startupState。Yuihub 附 170 行测试当蓝图，§四.6 的「重构面大」顾虑仍在但已降级。
- 顺带小件：fork `backup_rules.xml` 仍是上游 sample（仅 upload/），可加 SystemBackupRulesTest 钉 Android auto-backup 白名单。

**D3. DB 36→41 五迁移盘点**：36→37 scheduled_task（fork 无该域，不适用）；37→38 token_usage_event（fork `UsageRecorder.kt:66` 已按请求记账，已有等价）；38→39 compression_summaries（fork CompressionCheckpointEntity 多检查点更强，已有等价）；**39→40 会话级 enabled_skills/mcp_servers/local_tools/subagent_persona 覆盖列——领域功能缺口（与备份无关，另议）**；40→41 Memory 分层 11 列+三表（fork 记忆体系更重，不适用）。

### E. 搜索与 UI——不吸收

- **DeepSeek 官方 Search API（`2631f2d3`）——已有等价且更强**：fork `search/.../DeepSeekSearchService.kt:62-63` 早已是官方 Search + **Fetch/scrape**（:160-199）+ 重试（408/429/5xx，:256）+ 402 提示 + 多 key keyRoulette（:290）；Yuihub 新版反而无 scrape 且删了配置项。唯一可选核查项：其 query≤128 前置校验（若官方确有上限可补一行）。
- **备份页/助手设置页 UI 重做——不适用**：改动绑定其数据层大重构（恢复预览/启动回滚），非纯 UI；`design-ui-yuihub-inspired.md` 清单未列这两页。

## 四.9、工作区镜像站两件套——**已移植**（2026-10-31）

> 来源：Yuihub 2.5.8 线（本仓提交 `9ec9ec79`）。该项落在 2.5.8 之前的 `5ada0f4d`（rootfs 镜像测速安装）/ `6af40c65`（Rootfs UA + 安装失败逐源回退）/ `9309df90`（环境配置页 + 一键配置国内镜像源）三个提交里，§四.5 与 §四.8 两次增量评估均未单列；移植后补记于此，**避免被误记为自研**。

**逐文件对等移植**（仅改包名 `me.rerere.workspace` → `app.androidrik.workspace`、标记文件 `yuihub-mirror` → `rikkahub-mirror`、UA/品牌串改名）：

| 本仓文件（`workspace/.../`） | Yuihub 原文件 | 作用 |
|---|---|---|
| `HttpProbe.kt` | `HttpProbe.kt` | 首字节延迟 + 小段 Range 吞吐测速 |
| `AptSources.kt` | `AptSources.kt` | deb822 / one-line 双格式改写、`.orig` 备份重放防「镜像的镜像」、ubuntu / ubuntu-ports 池按架构区分 |
| `PackageMirrors.kt` | `PackageMirrors.kt` | apt 8 站 + npm / pip / go 候选与一键配置执行器（写 sources / `.npmrc` / `pip.conf` / `profile.d`） |
| `RootfsCatalog.kt` | `RootfsCatalog.kt` | rootfs 下载 6 镜像站 + ABI→架构映射 + 下载 UA（默认镜像 24.04.4） |
| `RootfsMirrorSelector.kt` | `RootfsMirrorSelector.kt` | 并行测速，官方源排首位兜底 |

**本仓适配层（非移植）**：`RootfsPatcher.ensureAptMirror` 装机默认切 TUNA（`AptSources.isExplicit` 短路，不覆盖用户显式选择；本仓的 CA 证书/并发锁是既有改造）；`RootfsInstaller` 补 UA；DB 56→57 `workspaces.package_mirrors`；`WorkspaceRepository.configurePackageMirrors`（写后带 6×2s 锁重试跑 `apt-get update`，失败不回滚只回传状态）/ `clearPackageMirrors`（重装 rootfs 必清摘要）；SSH 工作区一律拒绝；`WorkspaceReminderTransformer` 注入 `<package_mirrors>` 段；详情页「国内镜像源」卡 + 装机对话框测速与逐源回退 + 三语文案。

**许可**：Yuihub 与本仓同为 AGPL-3.0，逐文件移植合规；出处已在 README「借鉴与参考」表与发布说明 `1.0.28-beta.14` 中标注。
