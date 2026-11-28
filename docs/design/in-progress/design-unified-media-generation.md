# 统一媒体生成接口、模型目录与定时任务

> 2026-11-18/19。统一执行入口已在本地提交 `55efb216b`；统一模型目录、按任务授权和定时模板直跑连同本设计稿在本地提交落地，并随 Debug 包装机。提交结果待确认与恢复语义（阶段 2 主体）、定时模板的生成操作与素材引用（阶段 3 主体）、能力下沉与凭据引用共享（阶段 4 M1+M2）均已实现；M3 适配器收敛已全部完成（2026-11-21），按请求 ID 对账的客户端侧已实现（等厂商接口实弹，见下文 §2）；真机验收已跑（11-20），Google 真 Key 实弹挂账。真机业务验收和后续阶段见下文。

## 目标与范围

2026-11-28 增量：[会话内媒体创作](design-chat-media-creation.md) 将普通聊天生图接到 `MediaCreationService` 的持久记录与恢复流程，增加参考图编辑和版本关联。下文原有「聊天工具只以聊天消息记账」及普通聊天使用 `ImageGenerationRunner` 的描述属于此前实现；独立生图页、定时聊天仍保留原路径。

一个媒体模型接入后，聊天中的模型、手动创作者和自动化任务都可以使用它。应用统一选择模型、构造媒体请求和调用供应商。发起方决定授权来源、任务生命周期和结果去向。

本轮覆盖图片生成、手动参考图编辑和视频生成。定时模板直跑已支持固定提示词文生图和文生视频。定时聊天任务可以获准调用应用内的 `generate_image`。新增能力以媒体为范围，文本、语音与其他模型能力列在后续阶段。

| 调用来源 | 模型与授权 | 结果去向 |
| --- | --- | --- |
| 聊天模型 | `generate_image` 使用生图页选中的默认图片模型，沿用助手的审批和每轮限额 | 聊天工具卡和独立生图画廊 |
| 手动创作者 | 在生图页或媒体创作页从同一目录挑选模型，由用户点击生成 | 生图页可显示供应商提供的流式预览，终态图进画廊。媒体创作的图、视频及版本进创作时间线 |
| 定时聊天任务 | 任务编辑页预先授权指定图片模型和参数范围，每轮最多调用一次 | 聊天工具卡和画廊，任务沿用原有聊天运行账本 |
| 定时媒体模板 | 任务保存指定模型、参数和固定提示词，经用户授权后到点直跑 | 每轮对应一个媒体创作会话，从定时运行历史打开 |

## 已落地的实现

### 统一执行与模型选择

- `MediaGenerationExecutor.submit(target, request)` 接收统一的 `MediaGenerationRequest`。旧生图供应商适配成 `ImageStream`，媒体供应商返回 `Task`。`query` 用于供应商任务续查，`images` 把同步媒体图片结果适配给生图画廊和聊天工具。
- 聊天工具与独立生图页共用 `ImageGenerationRunner`，包括终态图落盘与画廊记录。媒体创作页和定时模板共用 `MediaCreationService`，包括素材准备、任务状态、产物下载和媒体创作历史。两种历史各用自己的数据表。
- `Settings.mediaModelCatalog()` 读取原有供应商配置中的生图模型与 `mediaGenerationProviders` 中已接入的媒体模型，按模型能力提供给同一套选择器。当前媒体适配器为 OpenAI 兼容图片与 SiliconFlow 视频。目录条目用模型 UUID、供应商 UUID、供应商模型 ID 和媒体类型标识。已保存的默认图片模型 UUID 可解析到两套配置中的任一图片模型，有效的旧配置可以继续读取。
- 目录统一发生在应用层。现在仍在各自设置中编辑供应商与凭据，原有适配器继续处理各自的参数、鉴权和协议差异。

[统一执行入口](../../../app/src/main/java/app/androidrik/rikkahub/data/media/MediaGenerationExecutor.kt) · [模型目录与历史记录解析](../../../app/src/main/java/app/androidrik/rikkahub/data/media/MediaModelCatalog.kt) · [原媒体生成设计](../completed/design-media-generation.md)

### 目录收录口径与 legacy 生图执行层（2026-11-19 补）

统一目录落地时暴露过一则误判：「legacy 分支过滤比执行能力窄（Claude 已实现 generateImage）」。核实结果相反——`ClaudeProvider.generateImage` 是 override 但函数体为 `error` 占位（Anthropic API 无生图端点，上游同款），`GoogleProvider` 此前无实现走接口默认 error；执行能力实际只有 OpenAI 兼容一档。重构前的行为是 Google/Claude 模型「能选但一跑就报错」，统一目录把它们挡在目录外正是把门控对齐到执行能力。本轮把 Google 从「挡掉」变成「真支持」：

- **收录口径 = 执行能力**（`MediaModelCatalog.legacyImageExecutable`）：OpenAI 兼容按 IMAGE 类型声明；Google 按「IMAGE 类型声明 或 IMAGE 输出模态」（Gemini 图片模型以 CHAT 类型录入、添加模型时由 models.dev 注册表回填输出模态，不能只认类型）；Claude/账号类不收录。原 `model.id == imageGenerationModelId` 逃生舱移除——指向不可执行模型的存量 id 会走兜底解析。
- **`GoogleProvider.generateImage`/`editImage`**：复用 generateContent 端点与聊天路径的 IMAGE 输出模态请求形态（systemInstruction 语义、safetySettings 全 OFF、customBody 顶层合并同约定），鉴权/代理/Vertex 服务账号走既有 transformRequest/buildUrl。张数按 n 语义循环（Gemini 图片模型单请求只稳定产一张，candidateCount 不稳）；显式尺寸映射 aspectRatio（auto/未知值不下发）；响应解析跳过思考草稿图，无图时把模型文本/拦截原因透传成错误（会话内工具会把失败转成文本回给模型）。
- **授权指纹**：OpenAI 分支逐字节不变（存量定时批准不作废），Google 用 `image-google` 前缀新分支。`getProviderByType` 已能分发 Google，`MediaGenerationExecutor` 无需改动。

测试：`GoogleProviderImageTest`（请求体形态/宽高比/解析）、`ScheduledMediaActionTest` 新增收录/排除/指纹三例。

### 按任务授权

- 用户在任务编辑页授权。授权记录 `ScheduledMediaAction` 保存模型引用、参数和配置指纹，直跑模板另存已批准的提示词。配置指纹覆盖适配器和端点，旧生图适配器还绑定相关自定义请求头和请求体。任务运行时按当前配置精确解析，端点或请求配置变化后需重新授权。模型缺失时报告错误。
- 定时聊天的工具调用按应用提供的 `sched:<runId>` 识别。会话注入与无界面执行都使用任务授权。每轮原子消耗一次授权，图片张数受任务上限约束，分辨率采用任务设置。应用只在存在有效授权时装配生图工具，定时轮的模型配置会移除供应商内置的图片生成工具。普通交互聊天继续使用助手审批。
- 定时模板只使用保存时批准的提示词和参数。提交前重读任务授权与提示词，同时检查模型当前能力。只有用户创建的任务可以获得此授权。

[授权模型](../../../app/src/main/java/app/androidrik/rikkahub/data/scheduled/ScheduledMediaAction.kt) · [定时运行注册表](../../../app/src/main/java/app/androidrik/rikkahub/service/ScheduledRunRegistry.kt) · [聊天工具装配](../../../app/src/main/java/app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt)

### 定时模板、产物和恢复

1. 在定时任务页新建任务，打开 **Run a media template**。选择模型、填写提示词、设置时间和模型支持的参数，再开启 **Authorize one generation per scheduled run** 并保存。当前模板使用纯文本输入，依赖首帧的 I2V 模型由选择器过滤。定时聊天生图可在聊天任务上启用 **Allow scheduled image generation**。
2. 现有调度器先认领到期任务并开运行账本，`ScheduledMediaRunner` 复用其 `runId` 作为本轮媒体会话 ID 和媒体记录 ID。媒体记录落库后，调度器继续处理后续任务，产物生成、轮询和下载由应用后台服务接管。模板直跑无需创建聊天会话或请求聊天模型。
3. 从任务详情的运行历史选择 **Open media result** 即可打开对应媒体会话。停止运行会取消本地等待。有供应商任务 ID 的远端视频可继续存在。正常完成、失败、取消或中断会写入运行账本。
4. 重启后，已有供应商任务 ID 的未完成记录继续查询，定时账本随后对账。无供应商任务 ID 的活动记录按中断收尾，当前媒体记录显示失败和中断原因，系统不会自动再次提交这一轮。

Room 75→76 给 `media_creation_record`、`scheduled_tasks` 和 `scheduled_task_runs` 分别追加可空的 `catalog_model_id`、`media_action` 和 `media_session_id`。旧记录可以沿用原供应商与模型标识。旧任务继续按原计划执行，后台生图需要在任务设置里授权。

[模板创建入口](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/scheduled/ScheduledTaskCreatePage.kt) · [模板运行与对账](../../../app/src/main/java/app/androidrik/rikkahub/service/ScheduledMediaRunner.kt) · [运行历史](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/scheduled/ScheduledTaskRunsPage.kt) · [Room 77 schema](../../../app/schemas/app.androidrik.rikkahub.data.db.AppDatabase/77.json) · [原定时任务设计](../completed/design-scheduled-ai-tasks.md)

### 提交结果待确认与恢复语义

一次媒体提交的三个时间点——请求发出、任务 ID 落库、结果下载完成——本地都先持久化再推进：`MediaCreationService` 在发请求**之前**把记录置为 RUNNING（提交前状态已持久化），服务端返回后立刻用 NonCancellable 写任务 ID。进程在窗口之间退出时按 `resolveMediaInterruption(taskId, status)` 处置：

| 死亡时状态 | 处置 |
| --- | --- |
| 带任务 ID（QUEUED / RUNNING / DOWNLOADING） | 注册任务接着查询，下载被打断的续查后重新下载 |
| RUNNING 且无任务 ID | 新状态 `SUBMIT_UNKNOWN`（提交结果待确认）：请求可能已到达服务端并计费，但本地没有可续查的凭据，也无法对账；不自动重试 |
| PREPARING 等提交前 | 标记为中断失败，重新提交是安全的 |

待确认的记录在时间线上有独立状态文案和说明，重试动作带确认弹窗（重新提交会重新发送请求，可能重复计费）；也可以直接删除。定时模板直跑的死轮在对账时按 FAILED 入运行账本，媒体记录保留待确认状态。

供应商能力新增 `MediaGenerationProvider.supportsQuery`（同步返回结果的接口返回 false，当前只有 SiliconFlow 视频为 true）；按请求 ID 自动对账已在此之上实现，见下文「接入按请求 ID 对账」。聊天工具的流式生图维持会话内生命周期：同步接口没有任务可恢复，聊天消息本身就是这一轮请求的账本。

[中断判定](../../../app/src/main/java/app/androidrik/rikkahub/service/MediaInterruption.kt) · [状态模型](../../../app/src/main/java/app/androidrik/rikkahub/data/model/MediaCreation.kt) · [能力声明](../../../mediagen/src/main/java/app/androidrik/mediagen/provider/MediaGenerationProvider.kt)

### 定时模板的生成操作与素材引用

任务编辑页的媒体模板新增显式操作三选：**Text prompt**（纯提示词直跑）、**Image edit**（参考图图片编辑）、**First-frame video**（首帧图生视频）。操作决定可选模型范围：编辑操作要求图片模型且接受图片输入，首帧操作要求视频模型且声明首帧角色（SiliconFlow I2V 因此进入模板可选范围，纯文本门控不再排除它）。

素材在选中相册图片时**立即复制**进应用私有目录 `filesDir/scheduled_media/<目录>/`（编辑既有任务时目录就是任务 id），授权 `ScheduledMediaAction.templateAssets` 只引用副本及用途（角色），后台到点直跑读副本，相册原文件被删不影响。保存时清理目录里未引用的副本；删除任务时按授权路径清理。带素材的授权还保存素材上传目的地指纹（S3 配置，密钥不参与），运行前核对，不一致视为授权失效并要求重新保存任务。

`ScheduledMediaAction.permits` 对素材强校验：素材必须被模型能力完整接受（不能有被丢弃的角色或类型），编辑操作至少一张参考图，首帧操作必须恰好提供首帧。素材被删除、必填首帧缺失、上传目的地变化的失败路径分别有明确错误。

[授权模型](../../../app/src/main/java/app/androidrik/rikkahub/data/scheduled/ScheduledMediaAction.kt) · [素材锁定仓库](../../../app/src/main/java/app/androidrik/rikkahub/data/scheduled/ScheduledMediaAssetStore.kt) · [模板字段](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/scheduled/ScheduledMediaFields.kt) · [上传目的地指纹](../../../app/src/main/java/app/androidrik/rikkahub/data/files/RemoteFileStore.kt)

## 验证记录

| 项目 | 结果 |
| --- | --- |
| 最终相关单元测试 | 应用 167 项，`:mediagen` 42 项，共 209 项通过。涉及目录合并、模型配置变更、授权单次消耗、预览与编辑、调度和 schema 版本 |
| 提交中断判定测试 | `MediaInterruptionTest`（三窗口处置 + 终态不动）与 `MediaGenerationManagerTest.supportsQueryFollowsTheAdapter` 通过；媒体/定时相关套件复跑全绿 |
| 模板操作与素材测试 | `ScheduledMediaActionTest`（三操作 × 模型/角色门控 + 序列化往返 + 上传指纹）与 `ScheduledMediaAssetStoreTest`（清理/删除/路径越界）通过；全量 3627 项除既有 `ImportFormatTest` 外全绿 |
| 能力下沉与凭据引用测试 | `MediaGenerationCapabilitiesTest`（SiliconFlow 按模型收紧 / 其他供应商直通）与 `ScheduledMediaActionTest`（引用解析、引用失效、端点变更重新批准、credentialRef 序列化往返）通过；全量 3630 项除既有 `ImportFormatTest` 外全绿 |
| Room 75→76 | 在 SQLite 中执行 Room 生成的迁移 SQL。三张表的旧行得到保留，新列为空，列结构与新 schema 一致 |
| 应用全量测试 | 实施期间复跑发现 1 项既有失败。`ImportFormatTest` 的断言只允许本地备份在选文件前确认，当前实现也要求原版整库备份先确认 |
| 构建、装机与启动 | `:app:assembleDebug` 成功。2026-11-18 在 PJX110 上用 ARM64 Debug APK 覆盖安装，调试包从 beta.22 更新为 `1.0.28-beta.23`（327），包名 `app.androidrik.rikkahub.debug`。ADB 验证本地与装机包的 SHA-256 一致，冷启动成功 |
| 真机业务验收 | 2026-11-19 在 PJX110（Android 15）完成本地模拟供应商的核心流程验收，含带数据的 Room 开库、两种定时聊天授权、四种模板生成路径、取消和进程重启后的续查。真实供应商调用与界面手测仍单独记录 |
| 真机实弹验收 | 2026-11-20 在 PJX110 装入 `98ea41fc8`（Debug 包覆盖安装，冷启动 0 FATAL）完成真实供应商实弹：兜底解析（`imageGenerationModelId` 从未设置时默认模型解析为目录首个可见生图模型）、生图页与媒体创作页选择器同目录贯通、玩转ai生图（OpenAI 兼容）真实出图落盘入画廊、聊天 `generate_image` 工具实弹（deepseek-flash 调用，工具卡展开提示词/尺寸正常，产物落库）。Google (Gemini) 实弹按用户决定跳过（执行层有 9 例单测覆盖）；待确认重试弹窗设备无 `SUBMIT_UNKNOWN` 存量记录，状态机已由 2026-11-19 模拟验收覆盖，真实中断场景留待日常使用中自然出现 |
| 按请求 ID 对账真机验证 | 2026-11-21 在 PJX110 装入 `3f8c452d5`（Debug 覆盖安装，真数据 Room 76→77 迁移通过、冷启动 0 FATAL、`request_id` 列就位）。用回环 fixture（`/control/submit-visibility` 受理可见性开关）走两个场景：**查到**——accepted_lost 下提交 `reconcile-a`（线上请求带 `requestId=4af54281…`，与落库值逐字节一致），提交窗口杀进程，重启后自动对账（`/v1/video/reconcile` found=true）→ 任务 ID 写回（=对账键回显）→ 状态续查 → 记录 SUCCEEDED 且产物落盘，全程仅一次提交；**查无**——never_received 下提交 `reconcile-b`，杀进程重启后对账 found=false → 记录维持 `SUBMIT_UNKNOWN` 交用户处置，同样无二次提交。对账器全程只读、任何分支不自动重提，mock trace 作为「无第二次提交」的真值（存档 `.tmp/reconcile-verify/fixture-trace-reconcile.json`）。验收载具（`MediaGenerationAcceptanceTest` 的 seedreconcile/recoverreconcile 两场景）已入仓；本次走 debug 包 UI 路线，隔离 instrumentation 路线未跑通（`am instrument` 绑定阶段报 Unable to find instrumentation info，复装多次同因，待查）。设备上留有验证痕迹：Fixture 供应商（127.0.0.1:18979）与 reconcile-a/b 两条记录 |

本次 ARM64 APK 的 SHA-256 为 `1cc4ecc83599baaa3a4a39a1bf3f7bc7763cfccc65ead3b5d307de9dcacfeb90`，构建路径为 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。

[授权回归测试](../../../app/src/test/java/app/androidrik/rikkahub/data/scheduled/ScheduledMediaActionTest.kt) · [两套生图适配回归测试](../../../app/src/test/java/app/androidrik/rikkahub/data/media/MediaGenerationExecutorTest.kt) · [既有备份失败用例](../../../app/src/test/java/app/androidrik/rikkahub/ui/pages/backup/ImportFormatTest.kt)

## 待完成阶段

### 1. 真机业务验收

#### 2026-11-19 本地模拟验收结果

验收基线为 `cf794cfda`。测试以独立库和独立设置文件运行，图片与视频由本机回环 HTTP 服务提供，ADB 端口转发用于真机访问。PJX110 的跨包测试运行器在绑定阶段报 `Unable to find instrumentation info`。临时将用例和 `MemoryIsolationTestRunner` 打进同一个隔离 Debug APK 后，迁移、模板、聊天和恢复四个用例全部通过。常规 Debug 包在排查期间覆盖安装，应用 UID 核对一致。

| 场景 | 真机证据 |
| --- | --- |
| 同一图片模型与凭据引用 | 同一图片模型经画廊、媒体创作、聊天和定时模板生成。模拟端核对了引用解析后的端点与请求头，产物已落盘 |
| 交互审批与定时授权 | 交互聊天批准前，模拟端无生图请求。会话注入和无界面定时任务各跑授权与未授权两轮。授权轮尝试超张数、合法调用和再次调用，最终各只产生一次符合授权的生图请求。撤销授权、改变端点和移除模型均未增加生图请求 |
| 四种定时模板与取消 | 文生图、参考图编辑、文生视频、首帧视频均落盘并可解码。原图删除后，已锁定的副本仍能下发。删除锁定副本、切换上传目的地时，运行记为失败且未向生成端发请求。等待中的视频被取消时，另一笔已完成的媒体运行保持成功 |
| 真进程中断与恢复 | 模拟端收到两次独立视频提交后，终止隔离应用进程。重启后，已保存任务 ID 的记录只走状态查询并落盘，运行账本为 `OK`。远端已收请求但尚无任务 ID 的记录变为 `SUBMIT_UNKNOWN`，账本为 `FAILED`。两笔视频各只有一次提交。另用预置的 `PREPARING` 记录验证了提交前中断收尾，全程无对应提交 |
| Room 升级 | 真机用 Room 的迁移测试工具开带有历史媒体记录、定时任务和运行账本的 v75 数据库，升级到 v76 后核对记录关联、旧任务启用状态、计划时间、产物路径及空授权 |

本轮范围为模拟供应商。真实供应商调用、应用界面的运行历史导航与待确认重试弹窗仍待手测。上表的运行历史检查覆盖数据库关联和产物落盘，重启检查覆盖状态与请求次数。

[真机媒体验收用例](../../../app/src/androidTest/java/app/androidrik/rikkahub/data/task/MediaGenerationAcceptanceTest.kt) · [Room 75→76 真机用例](../../../app/src/androidTest/java/app/androidrik/rikkahub/data/db/migrations/Migration_75_76_Test.kt) · [回环模拟供应商](../../../scripts/verification/media_generation_fixture.py)

#### 完整验收标准

| 验收场景 | 通过标准 |
| --- | --- |
| 同模型贯通 | 同一图片模型能在生图页、媒体创作页和定时任务中选到。生图页选为默认后，助手可按交互审批调用。检查输入参数、产物和历史位置 |
| 两种定时聊天执行模式 | 分别验证会话注入与无界面执行。无授权时工具不可用，授权后成功调用一次，第二次及超出张数或分辨率限制的调用被拦截。撤回授权、删除模型或变更端点后无新增供应商请求 |
| 定时直跑与恢复 | 各跑一轮文生图和文生视频，产物可从运行历史打开。视频轮询期间另一项到期任务可执行。持有供应商任务 ID 时重启能继续查询，无 ID 时无二次提交。取消只影响本轮 |
| 提交窗口中断 | 任务型供应商提交瞬间杀进程，重启后媒体记录显示「结果待确认」且不自动重试；重试时出现可能重复计费的确认弹窗。提交前杀进程的记录直接标记为中断失败 |
| 模板素材路径 | 图片编辑与首帧视频模板各跑一轮，产物可从运行历史打开。锁定的素材被删除后到点运行明确报错而不是静默失败；素材上传目的地变化后授权失效并提示重新保存 |
| 升级与回退准备 | 在隔离配置中以含媒体记录和旧定时任务的 v75 数据升级，核对 v76 开库、旧任务行为与产物链接。保留升级前备份作为回退依据 |

### 2. 接入按请求 ID 对账（2026-11-21 客户端侧已实现，等厂商接口实弹）

状态语义与恢复判定已落地（见「提交结果待确认与恢复语义」）。客户端侧完整链路已实现：

- **对账键随提交下发**：`MediaGenerationRequest.requestId` 由调用方生成，`MediaCreationService` 在**提交前**随记录落库（insert/retry 即写入，提交窗口内无需额外补写），提交时随载荷下发。重试的再提交分支换新键（旧键对应的提交结果已有结论），接着查询的分支保持原键。对账键在 custom body 之后追加——它是协议字段，不允许被模型级 customBodies 覆盖。
- **能力位**：`MediaGenerationProvider.supportsReconciliation` + `queryByRequestId`（默认不支持/失败），`MediaGenerationManager` 按 supportedKinds 校验后路由。当前只有 SiliconFlow 视频声明支持：提交体带 `requestId`，按 `POST /video/reconcile` 查回（响应形态与 status 相同，查无 404）。
- **启动对账器**：`resumePending` 的 `SUBMIT_UNKNOWN` 分支先按对账键查一次服务端（`classifyReconciliation` 分类，只读、任何分支都不重新提交）：查到 → 写回任务 ID 转回可续查并注册接着跑；404 → 维持待确认（此时重试安全）；其余失败 → 保持 RUNNING 下次启动再对账，不升级成失败。不可对账时（不支持能力位 / 无对账键 / 目录解析不到）维持原有口径直接标记待确认。
- **DB 76→77**：`media_creation_record.request_id`（可空纯列，AutoMigration 免 spec；null = 提交前中断或旧版本记录）。
- **测试**：`MediaInterruptionTest` 覆盖对账处置矩阵（Resume / ConfirmAbsent / RetryLater）；`MediaGenerationManagerTest` + `SiliconFlowMediaGenerationProviderTest` 覆盖能力位、载荷下发（防 custom body 覆盖）与 404 语义；fixture 新增 `/v1/video/reconcile` 假想厂商端点 + `/control/submit-visibility` 受理可见性开关（`accepted_lost`=实际受理但响应丢失 / `never_received`=根本没收到），回环 smoke 全场景通过（`.tmp/reconcile_fixture_smoke.py`）。
- **挂账**：真机验证已完成（2026-11-21，见验收表「按请求 ID 对账真机验证」行）——查到自动续查、查无维持待确认、全程无二次提交均实测通过。剩余：真厂商接口出现后抓包确认请求 ID 下发 + 服务端幂等（厂商侧要求 = 接受提交体里的请求 ID 并提供按 ID 查询端点）。

### 3. 收尾模板素材验收

真机模拟已覆盖图片编辑、首帧视频、锁定副本丢失和上传目的地变化。真实供应商下的素材兼容性仍待验证。已接入的图片和视频适配器可直接处理本地素材，要求远端素材 URL 的流程待相应适配器启用后再验。

### 4. 收敛供应商配置与能力声明（M1+M2 已实现，M3 待评估）

现状问题：旧生图（`ProviderSetting`，ai 模块适配器）与媒体（`MediaGenerationProviderSetting`，mediagen 适配器）两套配置并存，目录在应用层合并；同一厂商常常配两份（同 baseUrl 同 key），凭据重复维护。能力声明是供应商级静态表（`MediaGenerationCapabilities` 按「厂商类型 × kind」），模型级差异散落在适配器的 `modelId.contains("I2V")` 字符串匹配与应用层 `supportsTextTemplate` 里，目录筛选、编辑器参数选项（presets）、运行前校验三处口径不完全一致。

**M1 能力下沉到模型级（已实现）**
- `MediaGenerationCapabilities` 新增 `requiredImageInputs`（模型必须携带的图片输入角色）；mediagen 新增模型级派生 `MediaGenerationProviderSetting.modelCapabilities(model)`：供应商静态能力之外按模型收紧，首个约束是 SiliconFlow 的 I2V（imageRoles={FIRST_FRAME} + 必填首帧）/ T2V（无图片输入）。
- 三处字符串匹配收敛为这一个函数：SiliconFlow 适配器请求校验、应用层 `supportsTextTemplate`（改判 `requiredImageInputs.isEmpty()`）、模板首帧过滤（`ScheduledMediaFields` 按 imageRoles 过滤，自动生效）。
- 无存储/DB 变化；`MediaGenerationModel` 的显式能力覆盖字段留待厂商能力接口出现时再加。

**M2 凭据引用共享（已实现）**
- `MediaGenerationProviderSetting.OpenAI` 新增可选 `credentialRef: Uuid?`（序列化向后兼容，旧备份/旧版本双向安全）；目录解析 `Settings.effectiveMediaProvider` 在引用有效时把 baseUrl/apiKey 替换为被引用的旧 `ProviderSetting.OpenAI` 的值，引用被删或对象不是凭据型供应商时整套配置的模型从目录消失（运行期走既有「模型不可用」报错）。
- 媒体供应商自身 id 不变 ⇒ `MediaModelRef`、默认图片模型 UUID、历史记录、定时授权引用全部不受影响；授权指纹只看解析后的 baseUrl，改 key 不触发重新批准，换端点才要求重新批准。
- 入口：编辑器「凭据」区（引用/更换引用/解除引用，解除后回退本地快照）+ 原有的「从聊天供应商导入」现在默认生成活引用；列表项显示引用标记。
- 已知边界：被引用供应商的代理（proxy）设置不随引用生效（媒体适配器本来也不走代理）；多 key 轮询取第一个有效 key。

**M3 重复适配收敛评估**（2026-11-20 立项；**M3a 四批 + M3b 全部完成**，实施记录见提交 5d6bae8fd / 224ef4697 / 151267131 / 4918f7376 / dbf15813b / M3b-2）

> 立项拍板（2026-11-20）：共享类型（ProviderProxy/CustomHeader/CustomBody/KeyRoulette/configureClientWithProxy）**下沉 common** 由 ai+mediagen 复用；媒体供应商编辑器补**完整 UI**（代理四档/多 key/模型级头 body），独立配置形态不降级。另盘点出设计稿未覆盖的增量项：`98ea41fc8` 的 Google 生图执行层在 M3b 退役旧路径时会被一并废掉，须先移植为 mediagen 适配器（M3a-4）。

| 批次 | 内容 |
| --- | --- |
| M3a-1 | 类型下沉 common；`MediaGenerationProviderSetting` 加 `proxy`/`apiKeys`（base 层默认值，序列化向后兼容）；OpenAI/SiliconFlow adapter 接 keyRoulette + per-setting 代理 client；credentialRef 引用时 proxy/apiKeys 随被引用者生效（M2 边界「proxy 不随引用」作废）；编辑器补代理四档 + 多 key 列表 |
| M3a-2 | `MediaGenerationModel` 加 `customHeaders`/`customBodies`；adapter 应用（JSON 顶层合并 + multipart 字段合并，同旧链路语义）；`permitsBoundedCount` 改读新字段；模型编辑器补自定义头/body |
| M3a-3 | OpenRouter 图片编辑适配器（`/images/generations` + `input_references` data-URL，移植旧链路 `editImageWithInputReferences`）+ Manager 注册 |
| M3a-4 | Google 生图适配器移植进 mediagen（generateContent + IMAGE 输出模态，平移 `98ea41fc8` 实现）+ `MediaGenerationProviderSetting.Google` |
| M3b-1 | 目录切换：legacy IMAGE 模型（OpenAI/Google）运行期映射为 Media target（零数据迁移，`MediaModelRef`/定时授权 id 不变）；**指纹必须与旧分支逐字节一致，否则存量定时批准作废**；聊天 `generate_image`/生图页全走统一执行器 |
| M3b-2 | 退役：删 ai 模块 `generateImage`/`editImage` 与 `MediaGenerationTarget.Image`、`legacyImageExecutable`、旧测试清理 |



对比两条链路（旧 `ai/OpenAIProvider.generateImage/editImage` vs mediagen `OpenAIMediaGenerationProvider`）：

| 能力 | 旧链路 | mediagen |
| --- | --- | --- |
| 生成/编辑端点与 multipart | `/images/generations` + `/images/edits` | 相同，解析还多一层空串 b64 防御 |
| Grok size 特判 / `auto` 不下发 | ✓ | ✓（同一套语义） |
| **代理** | per-provider proxy | 无 |
| **多 key 轮询** | keyRoulette | 无（单 key） |
| **模型级 customHeaders/customBodies** | ✓（可覆盖 n/prompt/size，定时授权据此做 bounded count 检查） | 无（extraParameters 仅请求级） |
| OpenRouter 图片编辑 | 特判走 `/generations` + `input_references` | mediagen 的 OpenRouter 适配器未接入 |
| 模型列表 | 无 | `GET /models` ✓ |

流式预览不是收敛障碍：两条链路都是响应完成后一次性 emit，`ImageGenerationItem.partial` 在 OpenAI 路径从未使用。

结论：**收敛的前置条件是三项 parity——代理、多 key 轮询、模型级自定义头/body（含 `permitsBoundedCount` 语义移植）**，缺一即为功能降级（存量用户里多 key 与代理配置真实存在）；OpenRouter 图片编辑还需先移植 mediagen 的 OpenRouter 适配器。原估 M3a（三项 parity）≈ 3 批提交、M3b（目录切换 + 退役旧路径 + 聊天 `generate_image` 改走统一执行器）≈ 2 批；2026-11-20 立项后按上方批次表执行，Google 适配器移植（M3a-4）为增量批次。

处置：M2 已让**数据层面**的凭据重复可通过活引用消除（新配置推荐走引用）；三项 parity 已于 2026-11-20 立项实施（批次见上），M3b 完成前两条链路并存。

### 待决范围

如需把文本、语音也接入统一控制面，可复用调用身份、授权和任务追踪，为各自定义输入、流式事件和结果。此项与媒体阶段独立评估。
