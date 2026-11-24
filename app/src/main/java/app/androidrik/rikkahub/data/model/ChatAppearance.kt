package app.androidrik.rikkahub.data.model

import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import kotlinx.serialization.Serializable

/** Chat appearance contract. Only implemented materials are exposed; new renderers extend this contract. */
@Serializable
data class ChatAppearance(
    val typography: ChatTypography = ChatTypography(),
    val layout: ChatLayout = ChatLayout(),
    val userBubble: ChatBubble = ChatBubble(),
    val assistantBubble: ChatBubble = ChatBubble(visible = false),
) {
    fun normalized(): ChatAppearance = copy(
        typography = typography.copy(
            sizeRatio = typography.sizeRatio.bounded(0.5f, 2f, 1f),
            gradient = typography.gradient?.normalized(),
        ),
        layout = layout.copy(
            messageGapDp = layout.messageGapDp.bounded(0f, 32f, 4f),
            rendererId = layout.rendererId.takeIf { it.isNotBlank() && it.length <= 160 } ?: "builtin.standard",
            contentWidthFraction = layout.contentWidthFraction.bounded(0.35f, 1f, 1f),
            listSpacingDp = layout.listSpacingDp.bounded(0f, 40f, 12f),
            listPaddingDp = layout.listPaddingDp.bounded(0f, 40f, 16f),
        ),
        userBubble = userBubble.normalized(),
        assistantBubble = assistantBubble.normalized(),
    )
}

@Serializable
data class ChatTypography(
    val family: ChatFontFamily = ChatFontFamily.DEFAULT,
    val customFontPath: String = "",
    val customFontName: String = "",
    val sizeRatio: Float = 1f,
    val gradient: AppearanceGradient? = null,
)

@Serializable
data class ChatLayout(
    val userAlignEnd: Boolean = true,
    val messageGapDp: Float = 4f,
    val rendererId: String = "builtin.standard",
    val showHeader: Boolean = true,
    val contentWidthFraction: Float = 1f,
    val listSpacingDp: Float = 12f,
    val listPaddingDp: Float = 16f,
)

@Serializable
data class ChatBubble(
    val visible: Boolean = true,
    val opacity: Float = 1f,
    /** null follows the existing theme role (user primaryContainer / assistant surfaceContainerHigh). */
    val colorHex: String? = null,
    val geometry: ChatBubbleGeometry = ChatBubbleGeometry(),
    val material: BubbleMaterial = BubbleMaterial.SOLID,
    val gradient: AppearanceGradient = AppearanceGradient(),
    val pattern: BubblePattern = BubblePattern(),
    val noise: BubbleNoise = BubbleNoise(),
    val stack: BubbleStack? = null,
) {
    fun normalized(): ChatBubble = copy(
        opacity = opacity.bounded(0f, 1f, 1f),
        colorHex = colorHex?.takeIf { COLOR_HEX.matches(it) },
        gradient = gradient.normalized(),
        pattern = pattern.copy(
            spacingDp = pattern.spacingDp.bounded(4f, 64f, 16f),
            opacity = pattern.opacity.bounded(0f, 1f, 0.15f),
            colorHex = pattern.colorHex?.takeIf { COLOR_HEX.matches(it) },
        ),
        noise = noise.copy(strength = noise.strength.bounded(0f, 1f, 0.12f)),
        stack = stack?.normalized(),
        geometry = geometry.normalized(),
    )
}

@Serializable
enum class BubbleMaterial { SOLID, GRADIENT, PATTERN, NOISE, GLASS }

@Serializable
enum class GradientDirection { HORIZONTAL, VERTICAL, DIAGONAL }

@Serializable
data class AppearanceGradient(
    val colors: List<String> = listOf("#6750A4", "#D0BCFF"),
    val direction: GradientDirection = GradientDirection.DIAGONAL,
) {
    fun normalized(): AppearanceGradient = normalizedOrNull() ?: copy(colors = AppearanceGradient().colors)

    internal fun normalizedOrNull(): AppearanceGradient? {
        val valid = colors.asSequence().filter(COLOR_HEX::matches).take(8).toList()
        return if (valid.size >= 2) copy(colors = valid) else null
    }
}

@Serializable
enum class BubblePatternKind { DOTS, GRID, STRIPES }

@Serializable
data class BubblePattern(
    val kind: BubblePatternKind = BubblePatternKind.DOTS,
    val spacingDp: Float = 16f,
    val colorHex: String? = null,
    val opacity: Float = 0.15f,
)

@Serializable
data class BubbleNoise(val seed: Int = 0, val strength: Float = 0.12f)

@Serializable
data class ChatBubbleGeometry(
    val radiusDp: Float = 16f,
    /** 旧单值内边距；[normalized] 会把它拆进横竖两项（只读兼容，新代码消费横竖字段）。 */
    @Deprecated("迁移字段：读写一律走 paddingHorizontalDp / paddingVerticalDp") val paddingDp: Float = 8f,
    /** 尾角收紧（dp）：映射到气泡对齐侧的下角，null = 对称四角。 */
    val tailCornerDp: Float? = null,
    /** 气泡内容宽度上限比例（0.5–1.0），相对聊天可用宽度；渲染接线随皮肤档落地。 */
    val maxWidthFraction: Float = 1f,
    val paddingHorizontalDp: Float = 8f,
    val paddingVerticalDp: Float = 8f,
) {
    fun normalized(): ChatBubbleGeometry {
        val radius = radiusDp.bounded(0f, 48f, 16f)
        val legacy = paddingDp.bounded(0f, 32f, 8f)
        val horizontal = paddingHorizontalDp.bounded(0f, 32f, 8f)
        val vertical = paddingVerticalDp.bounded(0f, 32f, 8f)
        // 旧配置只有 paddingDp：横竖仍是默认时拆分过去，paddingDp 原值保留（旧构建前向兼容）。
        val migrate = horizontal == 8f && vertical == 8f && legacy != 8f
        return copy(
            radiusDp = radius,
            tailCornerDp = tailCornerDp?.takeIf { it.isFinite() }?.coerceIn(0f, radius),
            maxWidthFraction = maxWidthFraction.bounded(0.5f, 1f, 1f),
            paddingHorizontalDp = if (migrate) legacy else horizontal,
            paddingVerticalDp = if (migrate) legacy else vertical,
        )
    }
}

/** Only additional tokens live here. Existing font/opacity switches remain the storage authority. */
@Serializable
data class ChatAppearanceDetails(
    val layout: ChatLayout = ChatLayout(),
    val userBubbleVisible: Boolean = true,
    val userBubbleColorHex: String? = null,
    val assistantBubbleColorHex: String? = null,
    val userGeometry: ChatBubbleGeometry = ChatBubbleGeometry(),
    val assistantGeometry: ChatBubbleGeometry = ChatBubbleGeometry(),
    /** null retains the legacy shared opacity. */
    val assistantOpacity: Float? = null,
    val userMaterial: BubbleMaterial = BubbleMaterial.SOLID,
    val assistantMaterial: BubbleMaterial = BubbleMaterial.SOLID,
    val userGradient: AppearanceGradient = AppearanceGradient(),
    val assistantGradient: AppearanceGradient = AppearanceGradient(),
    val userPattern: BubblePattern = BubblePattern(),
    val assistantPattern: BubblePattern = BubblePattern(),
    val userNoise: BubbleNoise = BubbleNoise(),
    val assistantNoise: BubbleNoise = BubbleNoise(),
    val textGradient: AppearanceGradient? = null,
    val userStack: BubbleStack? = null,
    val assistantStack: BubbleStack? = null,
)

/** Legacy configurations need no disk migration and render identically. */
fun DisplaySetting.toChatAppearance(): ChatAppearance = ChatAppearance(
    typography = ChatTypography(chatFontFamily, chatCustomFontPath, chatCustomFontName, fontSizeRatio,
        chatAppearanceDetails.textGradient),
    layout = chatAppearanceDetails.layout,
    userBubble = ChatBubble(
        chatAppearanceDetails.userBubbleVisible, bubbleOpacity,
        chatAppearanceDetails.userBubbleColorHex, chatAppearanceDetails.userGeometry,
        chatAppearanceDetails.userMaterial, chatAppearanceDetails.userGradient,
        chatAppearanceDetails.userPattern, chatAppearanceDetails.userNoise,
        chatAppearanceDetails.userStack,
    ),
    assistantBubble = ChatBubble(
        showAssistantBubble, chatAppearanceDetails.assistantOpacity ?: bubbleOpacity,
        chatAppearanceDetails.assistantBubbleColorHex, chatAppearanceDetails.assistantGeometry,
        chatAppearanceDetails.assistantMaterial, chatAppearanceDetails.assistantGradient,
        chatAppearanceDetails.assistantPattern, chatAppearanceDetails.assistantNoise,
        chatAppearanceDetails.assistantStack,
    ),
).normalized()

/** Shared write entry for settings and plugin configurations; unrelated display fields are preserved. */
fun DisplaySetting.withChatAppearance(value: ChatAppearance): DisplaySetting {
    val a = value.normalized()
    return copy(
        chatAppearancePresetId = null,
        // 微调即解绑：独立设置的聊天域不再跟随皮肤推荐方案（design-skin-chat-binding.md §3.3）
        chatAppearanceDetached = true,
        chatFontFamily = a.typography.family,
        chatCustomFontPath = a.typography.customFontPath,
        chatCustomFontName = a.typography.customFontName,
        fontSizeRatio = a.typography.sizeRatio,
        showAssistantBubble = a.assistantBubble.visible,
        bubbleOpacity = a.userBubble.opacity,
        chatAppearanceDetails = ChatAppearanceDetails(
            layout = a.layout,
            userBubbleVisible = a.userBubble.visible,
            userBubbleColorHex = a.userBubble.colorHex,
            assistantBubbleColorHex = a.assistantBubble.colorHex,
            userGeometry = a.userBubble.geometry,
            assistantGeometry = a.assistantBubble.geometry,
            assistantOpacity = a.assistantBubble.opacity.takeIf { it != a.userBubble.opacity },
            userMaterial = a.userBubble.material,
            assistantMaterial = a.assistantBubble.material,
            userGradient = a.userBubble.gradient,
            assistantGradient = a.assistantBubble.gradient,
            userPattern = a.userBubble.pattern,
            assistantPattern = a.assistantBubble.pattern,
            userNoise = a.userBubble.noise,
            assistantNoise = a.assistantBubble.noise,
            textGradient = a.typography.gradient,
            userStack = a.userBubble.stack,
            assistantStack = a.assistantBubble.stack,
        ),
    )
}

private fun Float.bounded(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback

private val COLOR_HEX = Regex("#[0-9a-fA-F]{6}")
