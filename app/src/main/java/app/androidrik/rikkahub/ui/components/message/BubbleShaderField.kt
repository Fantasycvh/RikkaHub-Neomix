package app.androidrik.rikkahub.ui.components.message

import app.androidrik.rikkahub.data.model.BubbleShaderStyle
import app.androidrik.rikkahub.data.model.BubbleShaderTemplate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Periodic CPU counterpart of the built-in AGSL. Inputs are unrotated tile coordinates in turns. */
internal class BubbleShaderField(private val style: BubbleShaderStyle) {
    private val phase = style.phase * TAU
    private val palette = style.colors.map { it.substring(1).toInt(16) }

    fun color(x: Float, y: Float): Int {
        val px = x * TAU
        val py = y * TAU
        val w = style.warp
        val f = when (style.template) {
            BubbleShaderTemplate.AURORA -> 0.5f + 0.25f * sin(px + w * sin(py + phase)) +
                0.25f * cos(py - phase + w * sin(px))
            BubbleShaderTemplate.MARBLE -> 0.5f + 0.5f * sin(2f * px + phase +
                2f * w * sin(py + sin(px - phase)))
            BubbleShaderTemplate.IRIDESCENT -> 0.5f + (sin(px + phase + w * cos(py)) +
                sin(py - phase + w * cos(px)) + cos(px + py + phase)) / 6f
            BubbleShaderTemplate.RIPPLES -> {
                val distance = sqrt((2f - cos(px) - cos(py)).coerceAtLeast(0f))
                0.5f + 0.5f * cos(6f * distance - phase + w * sin(px + py))
            }
        }
        val t = ((f - 0.5f) * style.contrast + 0.5f).coerceIn(0f, 1f) * 2f
        val first = if (t < 1f) palette[0] else palette[1]
        val second = if (t < 1f) palette[1] else palette[2]
        val mix = if (t < 1f) t else t - 1f
        fun channel(shift: Int): Int {
            val a = first shr shift and 255
            val b = second shr shift and 255
            return (a + (b - a) * mix + 0.5f).toInt().coerceIn(0, 255)
        }
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

internal const val BUBBLE_SHADER_TILE_SIDE = 128
private const val TAU = 6.2831855f
