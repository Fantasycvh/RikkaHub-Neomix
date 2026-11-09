package app.androidrik.rikkahub.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatBubbleGeometry
import app.androidrik.rikkahub.data.model.ChatLayout
import app.androidrik.rikkahub.data.model.ChatTypography
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.BubbleMaterial

/** Minimal native provider example, available in the plugin center but disabled by default. */
object BuiltinChatAppearancePlugin : PluginChatAppearanceProvider, PluginAppSkinProvider {
    override val id = "builtin.chat_appearance"
    override val displayName = "聊天外观方案"
    override val description = "提供紧凑、阅读、渐变与玻璃方案，启用后可在偏好设置的用户界面页选择"
    override val version = "1.3.0"
    override val appSkins = listOf(AppSkinPreset("soft", "柔和阅读（插件）",
        app.androidrik.rikkahub.data.model.AppSkin(
            typography = app.androidrik.rikkahub.data.model.AppSkinTypography(family = app.androidrik.rikkahub.data.datastore.ChatFontFamily.SERIF),
            shapes = app.androidrik.rikkahub.data.model.AppSkinShapes(cardGroupOuterDp = 28f, cardGroupInnerDp = 8f, buttonDp = 20f, inputDp = 12f))))
    override val author = "Neomix"
    override val enabledByDefault = false
    override val chatLayouts: List<ChatLayoutRenderer> = listOf(CenteredChatLayout)
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
        ChatAppearancePreset("centered", "居中卡片", ChatAppearance(
            layout = ChatLayout(rendererId = "$id:centered", userAlignEnd = false, contentWidthFraction = 0.85f),
            userBubble = ChatBubble(visible = false),
            assistantBubble = ChatBubble(visible = false),
        )),
    )
}

/** A real plugin-owned renderer, using the same host slots as built-in layouts. */
private object CenteredChatLayout : ChatLayoutRenderer {
    override val id = "centered"
    override val name = "居中卡片（插件）"
    @Composable override fun Render(context: ChatLayoutContext, slots: ChatLayoutSlots) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(context.layout.messageGapDp.dp)) {
            slots.header()
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(12.dp)) { slots.body() }
            }
            slots.footer()
        }
    }
}
