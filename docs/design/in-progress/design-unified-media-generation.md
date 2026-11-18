# 统一媒体生成接口、模型目录与定时任务

> 2026-11-18。统一执行入口已在本地提交 `55efb216b`；统一模型目录、按任务授权和定时模板直跑连同本设计稿在本地提交落地，并随 Debug 包装机。真机业务验收和后续阶段见下文。

## 目标与范围

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

[模板创建入口](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/scheduled/ScheduledTaskCreatePage.kt) · [模板运行与对账](../../../app/src/main/java/app/androidrik/rikkahub/service/ScheduledMediaRunner.kt) · [运行历史](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/scheduled/ScheduledTaskRunsPage.kt) · [Room 76 schema](../../../app/schemas/app.androidrik.rikkahub.data.db.AppDatabase/76.json) · [原定时任务设计](../completed/design-scheduled-ai-tasks.md)

## 验证记录

| 项目 | 结果 |
| --- | --- |
| 最终相关单元测试 | 应用 167 项，`:mediagen` 42 项，共 209 项通过。涉及目录合并、模型配置变更、授权单次消耗、预览与编辑、调度和 schema 版本 |
| Room 75→76 | 在 SQLite 中执行 Room 生成的迁移 SQL。三张表的旧行得到保留，新列为空，列结构与新 schema 一致 |
| 应用全量测试 | 实施期间复跑发现 1 项既有失败。`ImportFormatTest` 的断言只允许本地备份在选文件前确认，当前实现也要求原版整库备份先确认 |
| 构建、装机与启动 | `:app:assembleDebug` 成功。2026-11-18 在 PJX110 上用 ARM64 Debug APK 覆盖安装，调试包从 beta.22 更新为 `1.0.28-beta.23`（327），包名 `app.androidrik.rikkahub.debug`。ADB 验证本地与装机包的 SHA-256 一致，冷启动成功 |
| 真机业务验收 | 待验。装机验证覆盖安装与冷启动。聊天、手动创作与自动化的真实供应商调用，以及带数据的 Room 开库、重启续查和取消，仍待逐项验证 |

本次 ARM64 APK 的 SHA-256 为 `1cc4ecc83599baaa3a4a39a1bf3f7bc7763cfccc65ead3b5d307de9dcacfeb90`，构建路径为 `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。

[授权回归测试](../../../app/src/test/java/app/androidrik/rikkahub/data/scheduled/ScheduledMediaActionTest.kt) · [两套生图适配回归测试](../../../app/src/test/java/app/androidrik/rikkahub/data/media/MediaGenerationExecutorTest.kt) · [既有备份失败用例](../../../app/src/test/java/app/androidrik/rikkahub/ui/pages/backup/ImportFormatTest.kt)

## 待完成阶段

### 1. 真机业务验收

| 验收场景 | 通过标准 |
| --- | --- |
| 同模型贯通 | 同一图片模型能在生图页、媒体创作页和定时任务中选到。生图页选为默认后，助手可按交互审批调用。检查输入参数、产物和历史位置 |
| 两种定时聊天执行模式 | 分别验证会话注入与无界面执行。无授权时工具不可用，授权后成功调用一次，第二次及超出张数或分辨率限制的调用被拦截。撤回授权、删除模型或变更端点后无新增供应商请求 |
| 定时直跑与恢复 | 各跑一轮文生图和文生视频，产物可从运行历史打开。视频轮询期间另一项到期任务可执行。持有供应商任务 ID 时重启能继续查询，无 ID 时无二次提交。取消只影响本轮 |
| 升级与回退准备 | 在隔离配置中以含媒体记录和旧定时任务的 v75 数据升级，核对 v76 开库、旧任务行为与产物链接。保留升级前备份作为回退依据 |

### 2. 统一运行记录与恢复语义

建议把应用层接口补到 `submit(request, caller) → generationId`、`observe(generationId)` 和 `cancel(generationId)`。已有的图片流和异步供应商任务继续由适配器转换成预览、状态和产物引用。调用身份由应用提供，聊天、创作和自动化仍沿各自的 UI 展示结果。

先在媒体提交前持久化本地请求标识和明确的提交状态，再按供应商能力接入幂等键或按请求 ID 查询。对于已发出请求、结果未知且无法对账的情况，增加明确的「提交结果待确认」状态，并区分继续查询、重试下载与重新付费提交。验收覆盖进程在远端响应、本地写入和结果下载之间退出的情况。

### 3. 扩充定时模板

在固定提示词模板的基础上加入显式的生成操作与素材引用，优先支持图片编辑和首帧视频。保存时锁定素材及其用途，使后台运行能稳定读取。模型、操作、参数、素材或上传目的地变化时更新授权。验收覆盖素材被删除、进程重启、必填首帧缺失和需要远端素材 URL 的情况。

### 4. 收敛供应商配置与能力声明

统一目录当前汇总两套设置。后续可让兼容的模型共享供应商与凭据引用，并保留无法共用鉴权或协议的配置。先迁移稳定引用并验证旧备份、默认模型、历史记录、定时授权和模型自定义参数，再评估能否收敛重复适配代码。能力描述逐步下沉到模型级，供目录筛选、编辑器参数选项及运行前校验共用。

### 待决范围

如需把文本、语音也接入统一控制面，可复用调用身份、授权和任务追踪，为各自定义输入、流式事件和结果。此项与媒体阶段独立评估。
