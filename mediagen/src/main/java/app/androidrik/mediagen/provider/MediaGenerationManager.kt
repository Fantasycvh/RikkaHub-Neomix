package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider
import app.androidrik.mediagen.provider.providers.siliconflow.SiliconFlowMediaGenerationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MediaGenerationManager(
    client: OkHttpClient = OkHttpClient(),
) {
    private val openAI = OpenAIMediaGenerationProvider(client)
    private val siliconFlow = SiliconFlowMediaGenerationProvider(client)

    suspend fun create(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = withContext(Dispatchers.Default) {
        val adapter = provider(setting) ?: return@withContext unsupportedProvider(setting)
        unsupportedKind(adapter, setting, model) ?: adapter.createUnsafe(setting, model, request)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = withContext(Dispatchers.Default) {
        val adapter = provider(setting) ?: return@withContext unsupportedProvider(setting)
        unsupportedKind(adapter, setting, model) ?: adapter.queryUnsafe(setting, model, taskId)
    }

    /**
     * 提交请求并跟踪到终态。同步返回结果的供应商只会发出一次终态任务；异步供应商先发出提交结果，
     * 再按 [interval] 轮询。Flow 被取消时，轮询也会立即停止。
     */
    fun generate(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
        interval: Duration = 15.seconds,
    ): Flow<MediaGenerationTask> = flow {
        require(interval.isPositive()) { "interval must be positive" }
        val task = create(setting, model, request).getOrThrow()
        emit(task)
        if (task.isTerminal) return@flow
        delay(interval)
        emitAll(watch(setting, model, task.id, interval))
    }

    /**
     * 轮询并依次发出服务端状态。Flow 被取消时，轮询也会立即停止。
     */
    fun watch(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
        interval: Duration = 15.seconds,
    ): Flow<MediaGenerationTask> = flow {
        require(interval.isPositive()) { "interval must be positive" }
        while (true) {
            val task = query(setting, model, taskId).getOrThrow()
            emit(task)
            if (task.isTerminal) return@flow
            delay(interval)
        }
    }

    // 已接入的厂商适配器；其余厂商的类型尚未移植
    private fun provider(setting: MediaGenerationProviderSetting): MediaGenerationProvider<*>? =
        when (setting) {
            is MediaGenerationProviderSetting.OpenAI -> openAI
            is MediaGenerationProviderSetting.SiliconFlow -> siliconFlow
            else -> null
        }

    // 尚未移植的供应商返回失败而不是崩溃
    private fun unsupportedProvider(setting: MediaGenerationProviderSetting): Result<MediaGenerationTask> =
        Result.failure(
            UnsupportedOperationException("Provider not ported yet: ${setting::class.simpleName}")
        )

    // 厂商适配器尚未实现该模型的 kind 时返回失败，否则返回 null
    private fun unsupportedKind(
        provider: MediaGenerationProvider<*>,
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
    ): Result<MediaGenerationTask>? {
        if (model.kind in setting.supportedKinds) return null
        return Result.failure(
            UnsupportedOperationException("${provider.id} does not support ${model.kind} generation")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun MediaGenerationProvider<*>.createUnsafe(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> =
        (this as MediaGenerationProvider<MediaGenerationProviderSetting>).create(setting, model, request)

    @Suppress("UNCHECKED_CAST")
    private suspend fun MediaGenerationProvider<*>.queryUnsafe(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> =
        (this as MediaGenerationProvider<MediaGenerationProviderSetting>).query(setting, model, taskId)
}
