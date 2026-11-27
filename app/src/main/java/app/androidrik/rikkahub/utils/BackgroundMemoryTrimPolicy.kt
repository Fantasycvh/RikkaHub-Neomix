package app.androidrik.rikkahub.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.androidrik.rikkahub.ui.components.haze.GlassStyleCache
import app.androidrik.rikkahub.ui.components.message.BubbleShaderTiles
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 后台图片缓存与系统内存压力分级策略（借鉴 BiliPai `BackgroundMemoryTrimPolicy`，按本仓重写）。
 *
 * 设计要点：
 * 1. **切后台温和阶梯收敛**：刚切到多任务/桌面只把图片内存缓存收敛到 [BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES]
 *    （64 MiB），避免短切回来时整屏图片重新磁盘解码抢占主线程；持续后台超过 [BACKGROUND_IMAGE_TRIM_DELAY_MS]
 *    （45s）后再收敛到 [BACKGROUND_IMAGE_CACHE_DEEP_MAX_BYTES]（32 MiB），回前台即取消延迟任务。
 * 2. **系统压力分级响应**：图片缓存由 Coil 自己的 ComponentCallbacks2 独占处理
 *    （RUNNING_LOW/CRITICAL 减半，BACKGROUND 及以上清空）；这里仅在 CRITICAL 及以上
 *    清派生缓存与空闲连接，避免两个回调对同一缓存重复按比例裁剪。
 */
internal const val BACKGROUND_IMAGE_TRIM_DELAY_MS = 45_000L
internal const val BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES = 64L * 1024 * 1024
internal const val BACKGROUND_IMAGE_CACHE_DEEP_MAX_BYTES = 32L * 1024 * 1024

internal data class MemoryTrimPlan(
    val evictDerivedCaches: Boolean,
    val evictIdleConnections: Boolean,
)

/** 纯函数：根据后台驻留时长计算 Coil 内存缓存的温和收敛目标字节数。 */
internal fun resolveBackgroundImageCacheTrimTargetBytes(
    cacheSizeBytes: Long,
    backgroundElapsedMs: Long,
): Long {
    val budget = if (backgroundElapsedMs >= BACKGROUND_IMAGE_TRIM_DELAY_MS) {
        BACKGROUND_IMAGE_CACHE_DEEP_MAX_BYTES
    } else {
        BACKGROUND_IMAGE_CACHE_LIGHT_MAX_BYTES
    }
    return cacheSizeBytes.coerceIn(0L, budget)
}

/** 纯函数：将 `ComponentCallbacks2.TRIM_MEMORY_*` 映射为统一内存回收编排计划。 */
@Suppress("DEPRECATION")
internal fun resolveMemoryTrimPlan(level: Int): MemoryTrimPlan = when (level) {
    // 短切桌面/多任务已由 ProcessLifecycleOwner 后台阶梯预算接管，此处不二次砍半
    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> MemoryTrimPlan(
        evictDerivedCaches = false,
        evictIdleConnections = false,
    )

    ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryTrimPlan(
        evictDerivedCaches = false,
        evictIdleConnections = false,
    )

    ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryTrimPlan(
        evictDerivedCaches = true,
        evictIdleConnections = true,
    )

    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
    ComponentCallbacks2.TRIM_MEMORY_MODERATE,
    ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryTrimPlan(
        evictDerivedCaches = true,
        evictIdleConnections = true,
    )

    else -> MemoryTrimPlan(
        evictDerivedCaches = false,
        evictIdleConnections = false,
    )
}

/**
 * 防止在 `StartupGate` 注册自定义 Coil `SingletonImageLoader` 工厂前就误触发默认构建。
 * 仅在自定义工厂完成构建后才允许后台/压力回调访问 `SingletonImageLoader.get(context)`。
 */
internal object SingletonImageLoaderGuard {
    private val initialized = AtomicBoolean(false)

    fun markInitialized() {
        initialized.set(true)
    }

    fun isInitialized(): Boolean = initialized.get()
}

internal class BackgroundMemoryTrimController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttpClientProvider: () -> OkHttpClient?,
) {
    private val registered = AtomicBoolean(false)
    @Volatile
    private var delayedTrimJob: Job? = null

    /** 主线程调用（ProcessLifecycleOwner 约束）。 */
    fun register() {
        if (!registered.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    delayedTrimJob?.cancel()
                    delayedTrimJob = null
                }

                override fun onStop(owner: LifecycleOwner) {
                    trimImageCacheForBackground(backgroundElapsedMs = 0L)
                    delayedTrimJob?.cancel()
                    delayedTrimJob = scope.launch(Dispatchers.Default) {
                        delay(BACKGROUND_IMAGE_TRIM_DELAY_MS)
                        trimImageCacheForBackground(backgroundElapsedMs = BACKGROUND_IMAGE_TRIM_DELAY_MS)
                    }
                }
            }
        )
    }

    fun onTrimMemory(level: Int) {
        val plan = resolveMemoryTrimPlan(level)
        if (!plan.evictDerivedCaches && !plan.evictIdleConnections) {
            return
        }
        applyPlan(plan, reason = "trim($level)")
    }

    @Suppress("DEPRECATION")
    fun onLowMemory() {
        applyPlan(
            resolveMemoryTrimPlan(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
            reason = "lowMemory",
        )
    }

    private fun trimImageCacheForBackground(backgroundElapsedMs: Long) {
        val cache = activeImageMemoryCache() ?: return
        val before = cache.size
        val target = resolveBackgroundImageCacheTrimTargetBytes(
            cacheSizeBytes = before,
            backgroundElapsedMs = backgroundElapsedMs,
        )
        if (target < before) {
            runCatching { cache.trimToSize(target) }
                .onFailure { Log.w(TAG, "background image cache trim failed", it) }
        }
    }

    private fun applyPlan(plan: MemoryTrimPlan, reason: String) {
        // Application.super.onTrimMemory 已分发给 Coil；不要再次触碰图片缓存。
        if (plan.evictDerivedCaches) {
            runCatching {
                BubbleShaderTiles.evictAll()
                GlassStyleCache.clear()
            }.onFailure { Log.w(TAG, "derived cache evict ($reason) failed", it) }
        }

        if (plan.evictIdleConnections) {
            scope.launch(Dispatchers.IO) {
                runCatching { okHttpClientProvider()?.connectionPool?.evictAll() }
                    .onFailure { Log.w(TAG, "okhttp idle pool evict ($reason) failed", it) }
            }
        }
    }

    private fun activeImageMemoryCache(): MemoryCache? {
        if (!SingletonImageLoaderGuard.isInitialized()) return null
        return runCatching { SingletonImageLoader.get(context).memoryCache }.getOrNull()
    }

    companion object {
        private const val TAG = "MemoryTrimController"
    }
}
