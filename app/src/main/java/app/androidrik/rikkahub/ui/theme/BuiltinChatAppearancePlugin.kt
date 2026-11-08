package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatBubbleGeometry
import app.androidrik.rikkahub.data.model.ChatLayout
import app.androidrik.rikkahub.data.model.ChatTypography
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.BubbleMaterial

/** Minimal native provider example, available in the plugin center but disabled by default. */
object BuiltinChatAppearancePlugin : PluginChatAppearanceProvider {
    override val id = "builtin.chat_appearance"
    override val displayName = "聊天外观方案"
    override val description = "提供紧凑、阅读、渐变与玻璃方案，启用后可在偏好设置的用户界面页选择"
    override val version = "1.1.0"
    override val author = "Neomix"
    override val enabledByDefault = false
    override val chatAppearancePresets = listOf(
        ChatAppearancePreset("compact", "紧凑", ChatAppearance(
            layout = ChatLayout(messageGapDp = 2f),
            userBubble = ChatBubble(geometry = ChatBubbleGeometry(radiusDp = 10f, paddingDp = 6f)),
            assistantBubble = ChatBubble(visible = true, geometry = ChatBubbleGeometry(radiusDp = 10f, paddingDp = 6f)),
        )),
        ChatAppearancePreset("reading", "阅读", ChatAppearance(
            typography = ChatTypography(sizeRatio = 1.15f),
            layout = ChatLayout(userAlignEnd = false, messageGapDp = 8f),
            userBubble = ChatBubble(visible = false),
            assistantBubble = ChatBubble(visible = false),
        )),
        ChatAppearancePreset("colorful", "缤纷渐变", ChatAppearance(
            typography = ChatTypography(gradient = AppearanceGradient(listOf("#8E24AA", "#1565C0"))),
            userBubble = ChatBubble(material = BubbleMaterial.GRADIENT),
            assistantBubble = ChatBubble(visible = true, material = BubbleMaterial.PATTERN),
        )),
        ChatAppearancePreset("glass", "透光玻璃", ChatAppearance(
            userBubble = ChatBubble(material = BubbleMaterial.GLASS, opacity = 0.85f),
            assistantBubble = ChatBubble(visible = true, material = BubbleMaterial.GLASS, opacity = 0.85f),
        )),
    )
}
