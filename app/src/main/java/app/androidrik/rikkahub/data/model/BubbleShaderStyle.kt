package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Built-in programs only. No source code, executable paths, or unbounded uniform maps in settings. */
@Serializable
enum class BubbleShaderTemplate { AURORA, MARBLE, IRIDESCENT, RIPPLES }

@Serializable
data class BubbleShaderStyle(
    val template: BubbleShaderTemplate = BubbleShaderTemplate.AURORA,
    val colors: List<String> = listOf("#31245C", "#8974C7", "#80D8CC"),
    val scaleDp: Float = 128f,
    val angleDegrees: Float = 0f,
    val contrast: Float = 1f,
    val warp: Float = 0.7f,
    /** A fixed phase, in turns. Changing it selects a different still of the procedural material. */
    val phase: Float = 0f,
) {
    fun normalized(): BubbleShaderStyle {
        val defaults = defaults(template)
        return copy(
            colors = List(3) { index -> colors.getOrNull(index)?.takeIf { HEX.matches(it) } ?: defaults.colors[index] },
            scaleDp = scaleDp.bound(32f, 512f, 128f),
            angleDegrees = angleDegrees.bound(0f, 360f, 0f),
            contrast = contrast.bound(0f, 2f, 1f),
            warp = warp.bound(0f, 2f, 0.7f),
            phase = phase.bound(0f, 1f, 0f),
        )
    }

    companion object {
        fun defaults(template: BubbleShaderTemplate) = BubbleShaderStyle(template = template, colors = when (template) {
            BubbleShaderTemplate.AURORA -> listOf("#31245C", "#8974C7", "#80D8CC")
            BubbleShaderTemplate.MARBLE -> listOf("#25364A", "#7796AC", "#E5EDF2")
            BubbleShaderTemplate.IRIDESCENT -> listOf("#6B53A6", "#DFA3C4", "#A2DFD0")
            BubbleShaderTemplate.RIPPLES -> listOf("#163D61", "#4D9BB5", "#B4EBDF")
        })
        private val HEX = Regex("#[0-9a-fA-F]{6}")
    }
}

private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
