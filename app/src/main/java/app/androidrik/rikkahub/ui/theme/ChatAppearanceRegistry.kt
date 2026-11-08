package app.androidrik.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.plugin.manager.PluginLifecycleObserver
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginResources
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.toChatAppearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChatAppearancePreset(val id: String, val name: String, val appearance: ChatAppearance)

/** Native plugin extension, deliberately in UI like PluginToolUIRendererProvider. */
interface PluginChatAppearanceProvider : RikkaPlugin {
    val chatAppearancePresets: List<ChatAppearancePreset>
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

    companion object { val shared = ChatAppearanceRegistry() }
}

fun resolveChatAppearance(
    display: DisplaySetting,
    presets: List<ChatAppearancePreset>,
): ChatAppearance = presets.firstOrNull { it.id == display.chatAppearancePresetId }
    ?.appearance?.normalized() ?: display.toChatAppearance()

private data class ProvidedAppearance(val display: DisplaySetting, val appearance: ChatAppearance)
private val LocalChatAppearance = compositionLocalOf<ProvidedAppearance?> { null }

@Composable
fun rememberChatAppearance(display: DisplaySetting): ChatAppearance {
    LocalChatAppearance.current?.takeIf { it.display == display }?.let { return it.appearance }
    val presets by ChatAppearanceRegistry.shared.presets.collectAsState()
    return resolveChatAppearance(display, presets)
}

@Composable
fun ChatAppearanceProvider(display: DisplaySetting, content: @Composable () -> Unit) {
    val appearance = rememberChatAppearance(display)
    CompositionLocalProvider(LocalChatAppearance provides ProvidedAppearance(display, appearance), content = content)
}

fun installPluginChatAppearanceBridge(
    manager: PluginManager,
    registry: ChatAppearanceRegistry = ChatAppearanceRegistry.shared,
) {
    manager.addObserver(object : PluginLifecycleObserver {
        override suspend fun onPluginEnabled(plugin: RikkaPlugin, resources: PluginResources) {
            if (plugin is PluginChatAppearanceProvider) {
                resources.register { registry.register(plugin.id, plugin.chatAppearancePresets) }
            }
        }
    })
}
