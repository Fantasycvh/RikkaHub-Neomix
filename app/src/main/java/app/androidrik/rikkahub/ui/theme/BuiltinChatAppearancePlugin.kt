package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatBubbleGeometry
import app.androidrik.rikkahub.data.model.ChatLayout
import app.androidrik.rikkahub.data.model.ChatTypography

/** Minimal native provider example, available in the plugin center but disabled by default. */
object BuiltinChatAppearancePlugin : PluginChatAppearanceProvider {
    override val id = "builtin.chat_appearance"
    override val displayName = "聊天外观方案"
    override val description = "提供紧凑与阅读方案，启用后可在偏好设置的用户界面页选择"
    override val version = "1.0.0"
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
    )
}
