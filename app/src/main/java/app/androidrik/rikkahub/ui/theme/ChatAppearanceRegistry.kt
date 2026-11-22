package app.androidrik.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.plugin.manager.PluginLifecycleObserver
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginResources
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.builtinSkinChatLink
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.ChatBubbleGeometry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatAppearancePreset(val id: String, val name: String, val appearance: ChatAppearance)

/** Native plugin extension, deliberately in UI like PluginToolUIRendererProvider. */
interface PluginChatAppearanceProvider : RikkaPlugin {
    val chatAppearancePresets: List<ChatAppearancePreset> get() = emptyList()
    val chatLayouts: List<ChatLayoutRenderer> get() = emptyList()
}

/** Namespaced, observable contributions. Closing an old handle cannot remove its replacement. */
class ChatAppearanceRegistry {
    private val contributions = linkedMapOf<String, List<ChatAppearancePreset>>()
    private val state = MutableStateFlow<List<ChatAppearancePreset>>(emptyList())
    val presets = state.asStateFlow()

    @Synchronized
    fun register(owner: String, presets: List<ChatAppearancePreset>): AutoCloseable {
        require(owner.isNotBlank() && ':' !in owner)
        require(presets.all { it.id.isNotBlank() && ':' !in it.id && it.name.isNotBlank() })
        require(presets.map { it.id }.distinct().size == presets.size)
        val owned = presets.map { it.copy(id = "$owner:${it.id}", appearance = it.appearance.normalized()) }
        contributions[owner] = owned
        publish()
        return AutoCloseable {
            synchronized(this) {
                if (contributions[owner] === owned) {
                    contributions.remove(owner)
                    publish()
                }
            }
        }
    }

    private fun publish() { state.value = contributions.values.flatten() }

    companion object {
        val shared = ChatAppearanceRegistry()

        /** 启动时调用一次；owner "builtin.chat" 与插件 owner 命名空间隔离。 */
        fun installBuiltins(registry: ChatAppearanceRegistry = shared) {
            registry.register("builtin.chat", builtinChatAppearancePresets)
        }
    }
}

/** 内置聊天外观方案：与第三轮皮肤档位（[builtinSkinChatLink]）配套，应用皮肤时联动选择。 */
val builtinChatAppearancePresets = listOf(
    ChatAppearancePreset("glass", "现代旗舰（玻璃气泡）", ChatAppearance(
        userBubble = ChatBubble(material = BubbleMaterial.GLASS,
            geometry = ChatBubbleGeometry(radiusDp = 18f, tailCornerDp = 5f,
                paddingHorizontalDp = 12f, paddingVerticalDp = 9f)),
        assistantBubble = ChatBubble(
            geometry = ChatBubbleGeometry(radiusDp = 18f,
                paddingHorizontalDp = 12f, paddingVerticalDp = 9f)),
    )),
    ChatAppearancePreset("paper", "纸质感（平铺阅读）", ChatAppearance(
        userBubble = ChatBubble(colorHex = "#EBDFC9",
            geometry = ChatBubbleGeometry(radiusDp = 14f, tailCornerDp = 5f,
                paddingHorizontalDp = 13f, paddingVerticalDp = 9f)),
        assistantBubble = ChatBubble(visible = false)),
    ),
    ChatAppearancePreset("engineering", "工程感（平铺）", ChatAppearance(
        userBubble = ChatBubble(
            geometry = ChatBubbleGeometry(radiusDp = 8f, tailCornerDp = 3f,
                paddingHorizontalDp = 12f, paddingVerticalDp = 8f)),
        assistantBubble = ChatBubble(visible = false)),
    ),
    ChatAppearancePreset("ios", "iOS 风（平铺）", ChatAppearance(
        userBubble = ChatBubble(
            geometry = ChatBubbleGeometry(radiusDp = 18f, tailCornerDp = 6f,
                paddingHorizontalDp = 12f, paddingVerticalDp = 8f)),
        assistantBubble = ChatBubble(visible = false)),
    ),
)

fun resolveChatAppearance(
    display: DisplaySetting,
    presets: List<ChatAppearancePreset>,
): ChatAppearance = resolveAppSkin(display, chatPresets = presets).chat

private data class ProvidedAppearance(val display: DisplaySetting, val appearance: ChatAppearance)
private val LocalChatAppearance = compositionLocalOf<ProvidedAppearance?> { null }

@Composable
fun rememberChatAppearance(display: DisplaySetting): ChatAppearance {
    LocalChatAppearance.current?.takeIf { it.display == display }?.let { return it.appearance }
    return rememberAppSkin(display).chat
}

@Composable
fun ChatAppearanceProvider(display: DisplaySetting, content: @Composable () -> Unit) {
    val appearance = rememberChatAppearance(display)
    CompositionLocalProvider(LocalChatAppearance provides ProvidedAppearance(display, appearance), content = content)
}

fun installPluginChatAppearanceBridge(
    manager: PluginManager,
    registry: ChatAppearanceRegistry = ChatAppearanceRegistry.shared,
    layoutRegistry: ChatLayoutRegistry = ChatLayoutRegistry.shared,
    skinRegistry: AppSkinRegistry = AppSkinRegistry.shared,
) {
    manager.addObserver(object : PluginLifecycleObserver {
        override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
            if (plugin is PluginAppSkinProvider) {
                resources.register { skinRegistry.register(plugin.id, plugin.appSkins) }
            }
            if (plugin is PluginChatAppearanceProvider) {
                resources.register { registry.register(plugin.id, plugin.chatAppearancePresets) }
                resources.register { layoutRegistry.register(plugin.id, plugin.chatLayouts) }
            }
        }
    })
}
