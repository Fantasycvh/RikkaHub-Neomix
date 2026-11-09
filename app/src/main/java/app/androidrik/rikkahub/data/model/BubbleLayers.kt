package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** null stack in ChatBubble is the legacy format; an explicit empty stack paints no fill. */
@Serializable
data class BubbleStack(val glass: Boolean = false, val layers: List<BubbleLayer> = emptyList()) {
    fun normalized(): BubbleStack {
        val ids = mutableSetOf<String>()
        return copy(layers = layers.take(MAX_LAYERS).mapIndexed { index, layer ->
            var id = layer.id.takeIf { it.isNotBlank() && it.length <= 128 } ?: "layer-$index"
            if (!ids.add(id)) { id = "layer-$index"; while (!ids.add(id)) id += "-" }
            layer.normalized().copy(id = id)
        })
    }
    companion object { const val MAX_LAYERS = 4 }
}

@Serializable enum class BubbleLayerKind { SOLID, GRADIENT, PATTERN, NOISE, TEXTURE, SHADER }
@Serializable enum class BubbleBlend { NORMAL, MULTIPLY, SCREEN, OVERLAY }
@Serializable enum class BubbleTextureMode { TILE, MIRROR, COVER }

@Serializable
data class BubbleTexture(
    val assetId: String? = null,
    val mode: BubbleTextureMode = BubbleTextureMode.TILE,
    val sizeDp: Float = 64f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
) {
    fun normalized() = copy(
        assetId = assetId?.takeIf(::isBubbleTextureId),
        sizeDp = sizeDp.bound(8f, 512f, 64f),
        offsetXDp = offsetXDp.bound(-2048f, 2048f, 0f),
        offsetYDp = offsetYDp.bound(-2048f, 2048f, 0f),
    )
}

@Serializable
data class BubbleLayer(
    val id: String = Uuid.random().toString(),
    val kind: BubbleLayerKind = BubbleLayerKind.SOLID,
    val enabled: Boolean = true,
    val opacity: Float = 1f,
    val blend: BubbleBlend = BubbleBlend.NORMAL,
    val colorHex: String? = null,
    val gradient: AppearanceGradient = AppearanceGradient(),
    val pattern: BubblePattern = BubblePattern(),
    val noise: BubbleNoise = BubbleNoise(),
    val texture: BubbleTexture = BubbleTexture(),
    val shader: BubbleShaderStyle = BubbleShaderStyle(),
) {
    fun normalized(): BubbleLayer {
        val style = ChatBubble(colorHex = colorHex, gradient = gradient, pattern = pattern, noise = noise).normalized()
        return copy(opacity = opacity.bound(0f, 1f, 1f), colorHex = style.colorHex,
            gradient = style.gradient, pattern = style.pattern, noise = style.noise, texture = texture.normalized(),
            shader = shader.normalized())
    }
}

/** Stable, deterministic projection: reading old settings never generates random layer IDs. */
fun ChatBubble.effectiveStack(): BubbleStack = stack?.normalized() ?: when (material) {
    BubbleMaterial.SOLID -> BubbleStack(layers = listOf(BubbleLayer(id = "legacy-base", colorHex = colorHex)))
    BubbleMaterial.GRADIENT -> BubbleStack(layers = listOf(BubbleLayer(id = "legacy-gradient", kind = BubbleLayerKind.GRADIENT, gradient = gradient)))
    BubbleMaterial.PATTERN -> BubbleStack(layers = listOf(
        BubbleLayer(id = "legacy-base", colorHex = colorHex),
        BubbleLayer(id = "legacy-pattern", kind = BubbleLayerKind.PATTERN, pattern = pattern)))
    BubbleMaterial.NOISE -> BubbleStack(layers = listOf(
        BubbleLayer(id = "legacy-base", colorHex = colorHex),
        BubbleLayer(id = "legacy-noise", kind = BubbleLayerKind.NOISE, noise = noise)))
    BubbleMaterial.GLASS -> BubbleStack(glass = true)
}

fun ChatBubble.textureIds(): Set<String> = effectiveStack().layers
    .filter { it.kind == BubbleLayerKind.TEXTURE }.mapNotNull { it.texture.assetId }.toSet()

fun isBubbleTextureId(value: String): Boolean = TEXTURE_ID.matches(value)
private val TEXTURE_ID = Regex("[a-f0-9]{64}")
private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
