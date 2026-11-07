package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.util.Log
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "GenerationHandler"

/**
 * 生成失败自动重试（批次 10 从 [GenerationHandler] 拆出；错误分流谓词见
 * [GenerationErrorPolicy]）。
 *
 * 配置解析优先级：per-provider [ProviderSetting.retryOption]（非 null 时完全覆盖）→
 * 全局 [Settings.retrySetting]。未启用或间隔序列为空时直接执行，零开销。
 *
 * 语义：delaysSeconds 长度 = 重试次数（不含首次请求），值 = 每次重试前的等待秒数。
 * 仅对「可重试错误」（网络异常/限流/服务端错误）重试；用户取消（CancellationException）
 * 与不可重试错误（鉴权失败、参数错误等）立即上抛。重试等待状态通过
 * [processingStatus] 反馈到 UI。
 */
internal suspend fun <T> withGenerationRetry(
    context: Context,
    providerSetting: ProviderSetting,
    settings: Settings,
    processingStatus: MutableStateFlow<String?>?,
    maxRetries: Int? = null,
    action: suspend (ProviderSetting) -> T,
): T {
    val plan = providerSetting.retryOption ?: settings.retrySetting
    if (!plan.enabled || plan.delaysSeconds.isEmpty()) return action(providerSetting)
    // 重试预算：有 fallback 模型时压缩（快速失败切备用，避免长延迟重试拖慢恢复）
    val retryBudget = maxRetries?.coerceAtMost(plan.delaysSeconds.size) ?: plan.delaysSeconds.size

    var currentSetting = providerSetting
    var retryIndex = -1 // 已执行的重试序号；首次请求前为 -1
    while (true) {
        try {
            return action(currentSetting)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GenerationPartialFailureException) {
            // #1812：半截输出失败不进重试循环——已流式内容有计费价值，
            // 清空重发必然丢失；直接上抛由 ChatService 保留为草稿
            throw e
        } catch (e: Throwable) {
            val next = retryIndex + 1
            // 鉴权失败（401/403）或账户限流（429，多账户场景换 key 可避开）且还有其他 key：
            // 立即换 key 重试，不等延迟。OrderKeyRoulette 固定取首个 key，故轮转 =
            // 从瞬态副本去掉刚失败的首个 key（不落盘），下次请求自然用下一个；
            // 每次换 key 消耗一个 key，耗尽后 hasMultipleEnabledKeys=false → 走正常路径上抛，
            // 不会死循环。安全保证：#1812 分流——流式半截 429 已抛
            // GenerationPartialFailureException 被上方 rethrow（保留草稿不换 key），
            // 能走到这里的一定是零输出，换 key 无计费损失。
            if (isKeySwitchingError(e) && hasMultipleEnabledKeys(currentSetting)) {
                val rotated = rotateProviderKeys(currentSetting)
                if (rotated == null) throw e // 理论不可达（hasMultipleEnabledKeys 已保证 ≥2 个）
                retryIndex = next
                currentSetting = rotated
                Log.w(
                    TAG,
                    "key error, switching key (${splitEffectiveKeys(rotated.effectiveApiKeys).size} key(s) left) " +
                        "and retrying: ${e.message}"
                )
                continue
            }
            if (next >= retryBudget || !isRetryableGenerationError(e)) throw e
            retryIndex = next
            val waitSec = plan.delaysSeconds[retryIndex].coerceAtLeast(0)
            Log.w(
                TAG,
                "generation failed, retry ${retryIndex + 1}/$retryBudget in ${waitSec}s: ${e.message}"
            )
            if (processingStatus != null) {
                processingStatus.value = context.getString(
                    R.string.generation_retry_waiting,
                    retryIndex + 1,
                    retryBudget,
                    waitSec,
                )
            }
            try {
                delay(waitSec * 1000L)
            } finally {
                if (processingStatus != null) processingStatus.value = null
            }
        }
    }
}
