package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.glass.GlassStyle

/**
 * 轻量有界 LRU 样式缓存，只缓存不可变样式参数，不缓存背景像素。
 * Haze 后端拥有 RenderEffect/RuntimeShader 的构建和生命周期，这里不跨节点共享可变 shader。
 *
 * 多个玻璃/磨砂消费点（顶栏、顶栏胶囊、聊天输入栏、气泡玻璃、`GlassTopBarScaffold`）共享同一批
 * 主题色与光学参数，复用不可变 [HazeBlurStyle] / [GlassStyle] 实例可避免每次重组重复构造
 * lambda-backed style，进而让 `recoverableHazeBlur` / Haze 节点内部的 style 等价比较持续命中。
 */
internal class BoundedEffectCache<K : Any, V : Any>(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, got $maxEntries" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, V>(maxEntries + 1, 0.75f, true)

    fun get(key: K): V? = synchronized(lock) { entries[key] }

    fun put(key: K, value: V): V = synchronized(lock) {
        entries[key] = value
        while (entries.size > maxEntries) {
            val eldestKey = entries.entries.iterator().next().key
            entries.remove(eldestKey)
        }
        value
    }

    inline fun getOrPut(key: K, create: () -> V): V {
        get(key)?.let { return it }
        return put(key, create())
    }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
    }
}

internal enum class GlassStylePreset {
    STANDARD,
    CAPSULE,
}

internal data class BlurStyleCacheKey(
    val containerColorRaw: Long,
    val blurRadiusDp: Float,
    val darkMode: Boolean,
) {
    companion object {
        fun of(containerColor: Color, blurRadiusDp: Float, darkMode: Boolean): BlurStyleCacheKey =
            BlurStyleCacheKey(
                containerColorRaw = containerColor.value.toLong(),
                blurRadiusDp = blurRadiusDp,
                darkMode = darkMode,
            )
    }
}

internal data class GlassStyleCacheKey(
    val preset: GlassStylePreset,
    val containerColorRaw: Long,
    val tintAlpha: Float,
    val darkMode: Boolean,
    val readabilityMode: LiquidGlassReadabilityMode,
    val cornerShape: RoundedCornerShape?,
) {
    companion object {
        fun of(
            preset: GlassStylePreset,
            containerColor: Color,
            tintAlpha: Float,
            darkMode: Boolean,
            readabilityMode: LiquidGlassReadabilityMode,
            cornerShape: RoundedCornerShape?,
        ): GlassStyleCacheKey = GlassStyleCacheKey(
            preset = preset,
            containerColorRaw = containerColor.value.toLong(),
            tintAlpha = tintAlpha,
            darkMode = darkMode,
            readabilityMode = readabilityMode,
            cornerShape = cornerShape,
        )
    }
}

/** 进程级玻璃/磨砂样式复用池：只存参数化的轻量 style 对象，无像素或 Context 引用。 */
internal object GlassStyleCache {
    val blurStyles = BoundedEffectCache<BlurStyleCacheKey, HazeBlurStyle>(maxEntries = 32)
    val glassStyles = BoundedEffectCache<GlassStyleCacheKey, GlassStyle>(maxEntries = 32)

    fun clear() {
        blurStyles.clear()
        glassStyles.clear()
    }
}
