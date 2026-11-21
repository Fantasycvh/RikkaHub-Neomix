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

    /**
     * 提交时是否随载荷下发对账键（[MediaGenerationRequest.requestId]），并支持提交窗口中断后
     * 按 [queryByRequestId] 找回服务端任务。返回 false 的供应商，中断记录只能交由用户处置。
     */
    fun supportsReconciliation(setting: S, kind: MediaKind): Boolean = false

    /**
     * 按提交时下发的对账键查询服务端任务，响应形态与 [query] 相同。查无此任务时抛
     * 404 类 [app.androidrik.mediagen.provider.providers.MediaGenerationApiException]，
     * 上层据此区分「服务端确认没收到」与「暂时查不了」。
     */
    suspend fun queryByRequestId(
        setting: S,
        model: MediaGenerationModel,
        requestId: String,
    ): Result<MediaGenerationTask> = Result.failure(
        UnsupportedOperationException(
            "$id ${model.kind} generation does not support reconciliation by request id"
        )
    )
}
