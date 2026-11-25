package app.androidrik.rikkahub.saltui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupScope
import app.androidrik.rikkahub.ui.theme.AppSkinTheme
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.ResolvedAppSkin
import app.androidrik.rikkahub.ui.theme.builtInAppSkins
import app.androidrik.rikkahub.ui.theme.findPresetTheme

internal const val SALT_SPIKE_ITEM_DETAIL = "管理已创建的定时任务并查看下次执行时间。这里使用同一段长文本来观察换行和多行布局。"
internal const val SALT_SPIKE_SWITCH_DETAIL = "允许在指定时间运行任务并保留最近的结果。"

@Composable
internal fun SaltUiSpikePreview(modifier: Modifier = Modifier, saltPanel: Boolean? = null) {
    var lightBlue by rememberSaveable { mutableStateOf(false) }
    var dark by rememberSaveable { mutableStateOf(false) }
    var rtl by rememberSaveable { mutableStateOf(false) }
    var largeFont by rememberSaveable { mutableStateOf(false) }
    val preset = remember(lightBlue) {
        val id = if (lightBlue) "builtin.premium-dark" else "builtin.default"
        builtInAppSkins.first { it.id == id }
    }
    val (lightScheme, darkScheme) = remember(preset) {
        val palette = preset.skin.palette
        val fallback = findPresetTheme("")
        (palette?.generateColorScheme(false) ?: fallback.standardLight) to
            (palette?.generateColorScheme(true) ?: fallback.standardDark)
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDarkMode provides dark,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalDensity provides if (largeFont) Density(density.density, fontScale = 2f) else density,
    ) {
        AppSkinTheme(ResolvedAppSkin(preset.skin), if (dark) darkScheme else lightScheme) {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("SaltUI 3.0.0 对照", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(lightBlue, { lightBlue = !lightBlue }, label = { Text("浅蓝") })
                        FilterChip(dark, { dark = !dark }, label = { Text("深色") })
                        FilterChip(rtl, { rtl = !rtl }, label = { Text("RTL") })
                        FilterChip(largeFont, { largeFont = !largeFont }, label = { Text("200% 字体") })
                    }
                    if (saltPanel != true) SpikePanel(salt = false)
                    if (saltPanel != false) {
                        SaltUiSpikeTheme(lightScheme, darkScheme) {
                            SpikePanel(salt = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpikePanel(salt: Boolean) {
    val tag = if (salt) "salt" else "appskin"
    val inset = LocalAppSkin.current.skin.spacing.formHorizontalDp.dp
    var checked by rememberSaveable { mutableStateOf(false) }
    var clicks by rememberSaveable { mutableStateOf(0) }
    var changes by rememberSaveable { mutableStateOf(0) }
    val onSwitch: (Boolean) -> Unit = {
        checked = it
        changes++
    }

    Column(Modifier.fillMaxWidth().testTag("$tag-group")) {
        Text(if (salt) "SaltUI" else "AppSkin", style = MaterialTheme.typography.titleMedium)
        if (salt) {
            Surface(color = MaterialTheme.colorScheme.surfaceBright, shape = MaterialTheme.shapes.large) {
                Column {
                    SaltSpikeItem("定时任务", SALT_SPIKE_ITEM_DETAIL, true, { clicks++ }, Modifier.testTag("$tag-item"))
                    SaltSpikeDivider(inset)
                    SaltSpikeSwitch("运行提醒", SALT_SPIKE_SWITCH_DETAIL, checked, true, onSwitch, Modifier.testTag("$tag-switch"))
                    SaltSpikeDivider(inset)
                    SaltSpikeItem("不可用任务", "当前任务已停用。", false, { clicks++ }, Modifier.testTag("$tag-disabled-item"))
                    SaltSpikeDivider(inset)
                    SaltSpikeSwitch("不可用提醒", "当前提醒已停用。", false, false, onSwitch, Modifier.testTag("$tag-disabled-switch"))
                }
            }
        } else {
            CardGroup {
                appSkinSpikeItem("定时任务", SALT_SPIKE_ITEM_DETAIL, { clicks++ }, Modifier.testTag("$tag-item"))
                appSkinSpikeSwitch("运行提醒", SALT_SPIKE_SWITCH_DETAIL, checked, true, onSwitch, Modifier.testTag("$tag-switch"))
                appSkinSpikeItem("不可用任务", "当前任务已停用。", null, Modifier.testTag("$tag-disabled-item"))
                appSkinSpikeSwitch("不可用提醒", "当前提醒已停用。", false, false, onSwitch, Modifier.testTag("$tag-disabled-switch"))
            }
        }
        Text("点击 ${clicks} · 切换 ${changes}", Modifier.testTag("$tag-events"), style = MaterialTheme.typography.bodySmall)
    }
}

private fun CardGroupScope.appSkinSpikeItem(
    title: String,
    detail: String,
    onClick: (() -> Unit)?,
    modifier: Modifier,
) {
    item(
        modifier = modifier.semantics {
            if (onClick == null) disabled() else role = Role.Button
        },
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
    )
}

private fun CardGroupScope.appSkinSpikeSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    item(
        modifier = modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
    )
}
