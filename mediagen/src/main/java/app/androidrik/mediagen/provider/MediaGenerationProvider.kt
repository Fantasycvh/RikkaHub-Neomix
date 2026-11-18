package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.model.MediaKind

/**
 * 媒体生成供应商只负责任务的提交与查询，不在网络层内部隐式轮询。
 *
 * 一个供应商对应一个厂商，内部按 [MediaGenerationModel.kind] 路由到图像或视频接口。
 * 同步返回结果的接口（如 OpenAI 图像）在 [create] 里直接给出终态任务，无需实现 [query]。
 *
 * 模型的 kind 是否受支持由 [MediaGenerationManager] 按 [MediaGenerationProviderSetting.supportedKinds] 统一校验。
 */
interface MediaGenerationProvider<S : MediaGenerationProviderSetting> {
    val id: String

    suspend fun create(
        setting: S,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask>

    suspend fun query(
        setting: S,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> =
        Result.failure(
            UnsupportedOperationException(
                "$id ${model.kind} generation returns results synchronously and has no task to query"
            )
        )

    /**
     * 任务提交后能否按任务 ID 查询进度与结果。同步返回结果的接口返回 false：
     * 提交窗口内进程中断时，这类接口既无法续查也无法对账，只能重新提交。
     */
    fun supportsQuery(setting: S, kind: MediaKind): Boolean = false
}
