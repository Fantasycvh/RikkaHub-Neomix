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
        typography = typography.copy(sizeRatio = typography.sizeRatio.bounded(0.5f, 2f, 1f)),
        layout = layout.copy(messageGapDp = layout.messageGapDp.bounded(0f, 32f, 4f)),
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
)

@Serializable
data class ChatLayout(
    val userAlignEnd: Boolean = true,
    val messageGapDp: Float = 4f,
)

@Serializable
data class ChatBubble(
    val visible: Boolean = true,
    val opacity: Float = 1f,
    /** null follows the existing theme role (user primaryContainer / assistant surfaceContainerHigh). */
    val colorHex: String? = null,
    val geometry: ChatBubbleGeometry = ChatBubbleGeometry(),
) {
    fun normalized(): ChatBubble = copy(
        opacity = opacity.bounded(0f, 1f, 1f),
        colorHex = colorHex?.takeIf { COLOR_HEX.matches(it) },
        geometry = geometry.copy(
            radiusDp = geometry.radiusDp.bounded(0f, 48f, 16f),
            paddingDp = geometry.paddingDp.bounded(0f, 32f, 8f),
        ),
    )
}

@Serializable
data class ChatBubbleGeometry(val radiusDp: Float = 16f, val paddingDp: Float = 8f)

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
)

/** Legacy configurations need no disk migration and render identically. */
fun DisplaySetting.toChatAppearance(): ChatAppearance = ChatAppearance(
    typography = ChatTypography(chatFontFamily, chatCustomFontPath, chatCustomFontName, fontSizeRatio),
    layout = chatAppearanceDetails.layout,
    userBubble = ChatBubble(
        chatAppearanceDetails.userBubbleVisible, bubbleOpacity,
        chatAppearanceDetails.userBubbleColorHex, chatAppearanceDetails.userGeometry,
    ),
    assistantBubble = ChatBubble(
        showAssistantBubble, chatAppearanceDetails.assistantOpacity ?: bubbleOpacity,
        chatAppearanceDetails.assistantBubbleColorHex, chatAppearanceDetails.assistantGeometry,
    ),
).normalized()

/** Shared write entry for settings and plugin configurations; unrelated display fields are preserved. */
fun DisplaySetting.withChatAppearance(value: ChatAppearance): DisplaySetting {
    val a = value.normalized()
    return copy(
        chatAppearancePresetId = null,
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
        ),
    )
}

private fun Float.bounded(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback

private val COLOR_HEX = Regex("#[0-9a-fA-F]{6}")
