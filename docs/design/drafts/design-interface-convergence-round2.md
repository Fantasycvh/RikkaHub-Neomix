# 统一接口收敛·第二轮评估（用量流水 / 文档解析 / 完成通知）

> 2026-11-21。媒体线（design-unified-media-generation.md）收官后，按「一种接口、三种发起分支」的既成形态盘点全域，识别出三个仍存在平行接口的域。**状态更新：三项已按拍板实施完毕并真机验证通过（同日）**——① 真数据 DB 77→78 迁移、fixture 视频 42.5 秒 + 真实供应商真图 1 张/1616 tokens 两条 MEDIA 行实测、stats 卡片真机可见；③ scheduled_run 频道创建与后台完成通知触达实测；意外收获=SUBMIT_UNKNOWN UI 真机首次实弹（误杀真实生图→待确认文案→重试确认→SUCCEEDED）。② 未做真机 UI 验证（单测覆盖）。文中行号以 37891acc5 为准。

背景形态（媒体线验证过的收敛套路）：类型下沉 → 单一执行入口 → 目录/能力单点 → 记录与恢复语义 → 三个发起分支（模型工具调用 / 手动创作 / 定时自动化）全部走同一条链路。

| # | 候选 | 现状差距 | 价值 | 风险 | 估量 |
| --- | --- | --- | --- | --- | --- |
| ① | 媒体用量流水接入 UsageRecorder | 媒体消费真实计费但**零记账**（MediaCreationService 无 UsageRecorder 引用） | 账本完整性——用量统计页看不见生图/生视频花费 | 低（有 OCR 按页行先例） | 1 批，约 0.5–1 天 |
| ② | 文档解析收编到 DocumentReader facade | facade 已存在但只收编了 WorkspaceTools；三个消费方各自直接调 parser、重复格式分发 | 清理重复 + 错误语义统一（旧 API 失败返回字符串 vs facade 抛异常） | 低（有既有测试回归网） | 1 批小改，约 0.5 天 |
| ③ | 任务完成通知单点 | 完成类通知 4 处各自实现（频道/id 分配/前台抑制/路由 intent） | 行为一致性 + 后续加域不再复制骨架 | 中（前台抑制口径各域不同，须原样保留） | 1 批，约 0.5–1 天 |

推荐顺序：**① → ② → ③**。① 是账本缺口（用户可感知的统计盲区），且"非 token 计费怎么入账"的语义已被 OCR 行趟平；② 纯清理零行为变更；③ 回归面最宽（通知触达是强感知面），放最后。

---

## ① 媒体用量流水接入 UsageRecorder

### 现状与证据

- 聊天域每次 provider 请求都入账：`UsageRecorder.wrap(provider, tag)` 包一层（UsageRecorder.kt:81），`record()` 写 `usage_record` 一行；重试的每次尝试各记各的（「都发出去并计费了」）。
- **媒体域零记账**：MediaCreationService.kt 全文无 UsageRecorder 引用；聊天工具的生图路径（`MediaGenerationExecutor.images()`）同样不记账。生图/生视频是真实花钱的供应商请求，用量统计页却完全不可见——这是三种发起分支里媒体分支唯一没对齐的地方。
- 非 token 计费的入账先例：`recordOcrPages`（UsageRecorder.kt:92）——DeepSeek OCR 按页计费、usage 缺省，**绕开零 token 行丢弃闸门**，page_count 列与 token 行同表共存，统计页按页展示。
- 统计页的来源映射：`StatsPage.kt:1100` 按 `UsageSource` 枚举名映射展示字符串，**新增枚举值必须同步加展示名**，否则退化成裸枚举名（UsageSource kdoc 明文警示）。
- 汇聚点：M3b-1 后所有媒体提交（聊天工具 / 媒体创作页 / 定时模板）都经 `MediaGenerationExecutor`；但 `MediaGenerationTask.usage` 只在**终态任务**上有（同步接口 submit 即终态；异步接口在 query 终态时才有）——记账点不能是 submit，必须挂在各路径拿到终态任务的位置。

### 方案草案

1. **来源**：`UsageSource` 加 `MEDIA`（单值即可；区分聊天工具/手动/定时没有统计意义，媒体行可带 mediaSessionId 供排查）。
2. **落账口径**（对齐 `MediaGenerationUsage` 字段）：
   - 视频 → 秒：`task.usage?.totalSeconds`（SiliconFlow 返回 timings）；缺省时不记秒、只记行。
   - 图像 → 张：`task.usage?.inputImageCount ?: task.outputs.size`（OpenAI 图像接口无 usage，按产出张数计）。
   - token → `task.usage?.totalTokens` 映射到 `completion_tokens` 列（让合计列能对上；prompt/completion 拆分媒体接口不提供）。
   - **只记 SUCCEEDED 终态**：失败任务各已接入供应商均不计费，与 OCR「pageCount>0 才记」同款守卫。
3. **DB 77→78**：`usage_record` 加两列 `image_count INTEGER NOT NULL DEFAULT 0`、`video_seconds REAL NOT NULL DEFAULT 0`（纯新增列，AutoMigration 免 spec；SCHEMA_VERSION 同步 78）。
4. **记账点**（两处，共享一个内部函数）：
   - `MediaCreationService.generate()` 终态处——覆盖手动创作 + 定时模板两个分支；
   - `MediaGenerationExecutor.images()` 终态处——覆盖聊天工具分支。
   - 放 executor 同包抽 `internal fun recordMediaUsage(...)`，两处调用；executor/Service 已注入的依赖不变（UsageRecorder 从 Koin 取）。
5. **统计页**：来源映射加 MEDIA 显示名；行展示分支——`image_count>0` 显示「N 张图像」、`video_seconds>0` 显示「S 秒视频」、token 照旧。
6. **明确不做**：金额估算。媒体刊例价因供应商×模型差异大（SiliconFlow 按秒、图像按张，OpenAI 按张），做钱需要每模型价格表；本项先落数量/时长，金额估算挂账（与工具计费的「落账只存次数」同一哲学）。

### 批次与测试

- 1 批全量落地。单测：UsageRecorderTest 加 media 行用例（usage 缺省时按 outputs 计张、失败任务不记、totalTokens 映射）；stats 展示映射纯函数测试。
- 真机验证：真实出图一单后 stats 页可见「N 张图像」行；存量数据不受影响（迁移零回填）。
- 风险与边界：mediaSessionId 存入 `conversation_id` 列仅作排查用途（UsageTag kdoc 已声明该列语义=排查用，不参与分组）。**2026-11-21 已拍板：存 mediaSessionId**，UsageTag kdoc 同步补一句「媒体行此处存媒体会话 id」。

---

## ② 文档解析收编到 DocumentReader facade

### 现状与证据

- facade 已存在且质量完好：`DocumentReader.read(file, path, startUnit, unitCount)` 窗口化读取 + `totalUnits` + `assembleUnitText`（含「全空文档必须返回空串」的扫描版 PDF 教训，DocumentText.kt:94）；四个 parser 都有对应的窗口化 API（parsePages/parseBlocks/parseSlides/parseSections）。
- **消费方收编不齐**（这是批次 10 时代留下的半成品）：
  - `WorkspaceTools`（data/ai/tools/WorkspaceTools.kt:18）——已用 facade ✓；
  - `DocumentAsPromptTransformer.kt:9-12`——直接 import 四个 parser，按 **MIME 字符串**分发（:83-87），旧全文 API（`PdfParser.parserPdf` 等），失败吞成 `[ERROR, failed to read file]` 字符串；
  - `RagRepository.kt:15-18`——直接 import 四个 parser，按**扩展名**分发（:303-306），后接自己的 TextChunker；
  - `DeepSeekDocumentOcrTransformer.kt:15`——只用 `PdfParser.pageCount`（:121）计费取页数。
- 语义不一致的真实代价：旧全文 API 失败**返回错误字符串**（会被当成文档正文送进模型/RAG 索引），facade **抛异常**（上层分得清「空文档」与「解析失败」）——DocumentText.kt:47 的 kdoc 明说这是新接口存在的理由。RAG 索引把错误字符串切成 chunk 嵌入进去，检索时会召回错误文本，这是现状里真实存在的病。

### 方案草案

1. facade 补两个入口：
   - `DocumentReader.readAll(file, path): DocumentSlice`——全文读取（委托各 parser 现有全文实现，实战最久；窗口化 API 继续留给工具分页）；
   - `DocumentFormat.fromMime(mime: String): DocumentFormat?`——MIME 识别进 enum（现有 extensions 旁边加 mimes 集合），DocumentAsPromptTransformer 的 MIME 分发表删掉。
2. 三个消费方切换：
   - DocumentAsPromptTransformer：MIME → `fromMime`（未知 MIME 保持 `file.readText()` fallback，行为不变）；解析失败从「吞成 ERROR 字符串」改为抛出→上层按现状展示 ERROR 提示（展示行为不变，只是字符串不再来自 parser 内部）；
   - RagRepository：两处分发点换 `readAll`，TextChunker 照旧（分块是 RAG 语义，不进 facade）；**收益点**：解析失败从「错误字符串被切块嵌入」变成走既有 catch → `updateDocumentIndexState(error=…)` 状态行；
   - DeepSeekDocumentOcrTransformer：`PdfParser.pageCount` → `DocumentReader.totalUnits`（一行，顺手收编）。
3. 旧全文 API 处置：`readAll` 委托它们则降 internal；若切换后无其他调用方，直接删除（document 模块内自查）。
4. **不做**：把 DeepSeek 文档 OCR 的「PDF 页图渲染」并入 facade——它走的是 DeepSeek 原生文档上传，不是文本抽取，机制不同（与「不建议动」清单里 OCR 双机制同理）。

### 批次与测试

- 1 批小改。document 模块单测补 `readAll`（四格式 + 不支持格式抛 IllegalArgumentException）与 `fromMime`；DocumentAsPromptTransformer / RagRepository 现有测试回归（关注 fallback 行为与索引失败状态行）。
- 风险：RAG 索引的错误语义从「静默坏数据」变为「显式失败」——属**行为修正**而非回归，但会让此前能「假装索引成功」的损坏文件开始报错，验收时说明该差异。
- 明确不做：EPUB/DOCX 的 totalUnits 未知值（-1）语义不动；不引入 content:// 流式解析（消费方都已先落 tmp 文件）。

---

## ③ 任务完成通知单点（TaskCompletionNotifier）

### 现状与证据

`sendNotification`（utils/NotificationUtil.kt:158）现有 7 个调用方，其中**完成类 4 处**骨架重复、细节口径各自为政：

| 调用方 | 频道 | 通知 id 分配 | 前台抑制 | 点击路由 | 备注 |
| --- | --- | --- | --- | --- | --- |
| ChatNotificationManager.kt:99（generation done） | chat_completed | **固定 1**（后通知覆盖前通知） | `isForeground` + 用户开关 `enableNotificationOnMessageGeneration` 双闸 | 会话 intent | 聊天完成 |
| ScheduledTaskRunner.kt:567 | **复用 chat_completed** | `task.id.hashCode() + OFFSET` | **总是通知** | conversationIntent | 定时运行终态；闸门强放行文案在此拼 |
| MediaCreationService.kt:577（notifyFinished） | media_creation | `record.id.hashCode()` | ProcessLifecycleOwner STARTED 抑制 | sessionPendingIntent | 媒体完成 |
| RunNotifications.kt:71 | agent_run_completed | NotificationIds 单点 ✓ | `shouldNotifyRunCompletion(visible)` + **`notificationRouteAvailable` 前置检查** | RouteActivity runId extra | 子代理 run 终态 |

另有**动作型**两处（SubagentApprovalNotifications.kt:68 批准/拒绝按钮、TaskManageTools.kt:354 管理工具确认）——语义不同，**不并入**。

骨架重复的真实代价：四处的 id 分配方案四种（固定 1 / hashCode / hashCode+offset / NotificationIds 注册表——聊天完成通知固定 id=1 意味着并发两个会话完成时只看到最后一条）、前台抑制三种口径（聊天还叠加用户开关）、`notificationRouteAvailable`（通知路由被系统关闭时跳过发送）只有 RunNotifications 做了——媒体与定时在路由不可用时仍会白发通知。每加一个新执行域（未来的后台任务）都要把这套再抄一遍。

### 方案草案

1. 收「完成类」四处进一个轻 facade（`TaskCompletionNotifier` 或平级 `CompletionNotifications`），统一的是**骨架**而非策略：
   - 通知 id 分配统一走 `NotificationIds`。**2026-11-21 已拍板**：四处全部收编进 `NotificationIds` 注册表；**聊天固定 id=1 的覆盖语义保留**（并发完成仍只显示最后一条），把该固定值登记进 NotificationIds 防冲突——如需改为按会话叠加（行为变更）另议；
   - `notificationRouteAvailable` 前置检查收编为公共步骤（行为增强：媒体/定时路由不可用时不再白发）；
   - 频道创建/常量、BigTextStyle/category 默认值、`PendingIntent` 组装（RouteActivity extras / conversationIntent / sessionPendingIntent 三种路由保持各自参数）；
   - **前台抑制策略按域原样保留**（媒体=前台静默、定时=总通知、聊天=前台写回消息、agent run=visible 判定）——这不是疏漏而是产品口径，facade 只提供挂点不替各域决定。
2. 不搞大抽象：四个薄分支 + 共享 check-and-build 函数，预计 facade 本体 150 行以内；文案资源与频道 id 全部不动（用户已按现有频道管理过通知偏好，改频道 id 会重置用户设置）。
3. **渠道拆分（2026-11-21 已拍板：做）**：ScheduledTaskRunner 从复用的 `chat_completed` 拆出独立频道（建议 `scheduled_run`，IMPORTANCE_DEFAULT 对齐 RunNotifications）。注意两点：① 新频道须在 RikkaHubApp 频道创建清单登记；② **用户已对 chat_completed 频道做过的通知设置（开关/重要性）不会迁移**——定时通知将以新频道默认值出现，属一次性可感知变化，验收时说明。

### 批次与测试

- 1 批。单测：NotificationIds 分配无冲突（现有 NotificationIdsTest 扩展）、四分支的路由 intent 组装纯函数测试。
- 真机回归面（本项最大的成本）：四个域各触发一次完成通知，逐项核对前台/后台两种状态下的触达差异与点击跳转——与既有行为逐一对齐后才能收。这就是它排 ③ 的原因。
- 风险：通知是强感知面，任何口径漂移（少弹/多弹/点错页）都会被立刻发现；前台抑制逻辑迁移时逐行对照原实现。

---

## 与媒体线形态的对齐说明

三项都不需要媒体线那种「类型下沉 + 目录 + 授权指纹」的全套工程：①是给已有单点（UsageRecorder）补一个分支；②是收编一个已建好的 facade 的存量用户；③是把四个各自为政的小骨架合成一个。真正复用的是媒体线验证过的**方法论**：先盘点消费方全貌、先定口径再动手、每批带回归测试收口。
