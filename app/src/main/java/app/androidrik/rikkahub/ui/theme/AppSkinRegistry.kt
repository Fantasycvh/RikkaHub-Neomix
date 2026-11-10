package app.androidrik.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSkinPreset(val id: String, val name: String, val skin: AppSkin)

interface PluginAppSkinProvider : RikkaPlugin {
    val appSkins: List<AppSkinPreset> get() = emptyList()
}

/** Owner-scoped contributions are automatically released by the existing plugin lifecycle bridge. */
class AppSkinRegistry {
    private val contributions = linkedMapOf<String, List<AppSkinPreset>>()
    private var installed = emptyList<AppSkinPreset>()
    @Synchronized internal fun replaceInstalled(skins: List<AppSkinPreset>) { installed = skins; publish() }
    private val state = MutableStateFlow(builtInAppSkins)
    val skins = state.asStateFlow()

    @Synchronized fun register(owner: String, skins: List<AppSkinPreset>): AutoCloseable {
        require(owner.isNotBlank() && owner !in setOf("builtin", "package") && ':' !in owner)
        require(skins.all { it.id.isNotBlank() && ':' !in it.id && it.name.isNotBlank() })
        require(skins.map { it.id }.distinct().size == skins.size)
        val owned = skins.map { it.copy(id = "$owner:${it.id}", skin = it.skin.normalized()) }
        contributions[owner] = owned
        publish()
        return AutoCloseable { synchronized(this) {
            if (contributions[owner] === owned) { contributions.remove(owner); publish() }
        } }
    }

    private fun publish() { state.value = builtInAppSkins + installed + contributions.values.flatten() }
    companion object { val shared = AppSkinRegistry() }
}

data class ResolvedAppSkin(val skin: AppSkin = AppSkin(), val chat: ChatAppearance = ChatAppearance())

/** One pure entry for global tokens and the legacy chat domain. Missing plugins retain user overrides. */
fun resolveAppSkin(
    display: DisplaySetting,
    skins: List<AppSkinPreset> = AppSkinRegistry.shared.skins.value,
    chatPresets: List<ChatAppearancePreset> = ChatAppearanceRegistry.shared.presets.value,
): ResolvedAppSkin {
    val base = skins.firstOrNull { it.id == display.appSkinId }?.skin ?: AppSkin()
    val chat = chatPresets.firstOrNull { it.id == display.chatAppearancePresetId }?.appearance ?: display.toChatAppearance()
    return ResolvedAppSkin(display.appSkinOverrides.applyTo(base), chat.normalized())
}

val LocalAppSkin = compositionLocalOf { ResolvedAppSkin() }

@Composable
fun rememberAppSkin(display: DisplaySetting): ResolvedAppSkin {
    val skins by AppSkinRegistry.shared.skins.collectAsState()
    val chats by ChatAppearanceRegistry.shared.presets.collectAsState()
    val trial by SkinTrialController.shared.trial.collectAsState()
    val resolved = resolveAppSkin(display, skins, chats)
    return trial?.let { resolved.copy(skin = it.skin) } ?: resolved
}

val builtInAppSkins = listOf(
    AppSkinPreset("builtin.default", "默认", AppSkin()),
    AppSkinPreset("builtin.compact", "紧凑", AppSkin(
        shapes = AppSkinShapes(cardGroupOuterDp = 12f, cardGroupInnerDp = 2f, buttonDp = 12f, inputDp = 8f),
        spacing = AppSkinSpacing(sectionDp = 10f, formHorizontalDp = 12f, formVerticalDp = 8f, formGapDp = 8f),
        motion = AppSkinMotion.STANDARD)),
    AppSkinPreset("builtin.reading", "舒展阅读", AppSkin(
        typography = AppSkinTypography(sizeRatio = 1.1f),
        shapes = AppSkinShapes(cardGroupOuterDp = 28f, cardGroupInnerDp = 8f, buttonDp = 24f, inputDp = 16f),
        spacing = AppSkinSpacing(pageDp = 12f, sectionDp = 20f, groupItemDp = 4f, formVerticalDp = 16f, formGapDp = 16f))),
)
