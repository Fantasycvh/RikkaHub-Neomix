package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.AppButton
import app.androidrik.rikkahub.ui.components.ui.AppTextButton
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.theme.AppSkinPanelSurface

@Composable
internal fun SkinPanelsControls(value: AppSkin, onChange: (AppSkin) -> Unit) {
    var editing by remember { mutableStateOf<AppSkinPanelSlot?>(null) }
    Text("顶栏、抽屉与内容卡片", style = MaterialTheme.typography.titleMedium)
    Select(options = listOf<TopBarChromeStyle?>(null) + TopBarChromeStyle.entries,
        selectedOption = value.topBar.mode, onOptionSelected = { onChange(value.copy(topBar = value.topBar.copy(mode = it))) },
        optionToString = { when (it) { null -> "顶栏：跟随原设置"; TopBarChromeStyle.FULL_BAR -> "顶栏：整条"; TopBarChromeStyle.CAPSULES -> "顶栏：浮动胶囊" } })
    AppSkinPanelSlot.entries.forEach { slot ->
        AppTextButton(onClick = { editing = slot }) { Text("编辑${slot.label()}") }
    }
    editing?.let { slot -> SkinPanelEditor(slot.label(), value.panel(slot), onSave = { panel ->
        onChange(value.withPanel(slot, panel)); editing = null
    }, onDismiss = { editing = null }) }
}

@Composable
internal fun SkinPanelEditor(title: String, initial: AppSkinPanel, onSave: (AppSkinPanel) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(initial.normalized()) }
    var materialEditor by remember { mutableStateOf(false) }
    val valid = isAppearanceHex(draft.contentColorHex) && ((draft.borderWidthDp ?: 0f) == 0f || isAppearanceHex(draft.borderColorHex))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("编辑$title", style = MaterialTheme.typography.titleLarge)
            AppSkinPanelSurface(draft.normalized(), Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy((draft.gapDp ?: 8f).dp)) {
                    Text("$title · Preview", style = MaterialTheme.typography.titleMedium)
                    Text("背景与文字预览")
                    AppButton(onClick = {}) { Text("操作按钮") }
                }
            }
            Text(if (draft.background == null) "背景：跟随原样" else "背景：自定义材质")
            Row {
                AppTextButton(onClick = { materialEditor = true }) { Text("编辑背景材质") }
                AppTextButton(enabled = draft.background != null, onClick = { draft = draft.copy(background = null) }) { Text("跟随原背景") }
            }
            Text("自定义材质覆盖原背景；顶栏实时玻璃请使用“跟随原背景”。思维链引用、极简和自定义档继续使用其专属设置。")
            PanelToggle("自定义圆角", draft.radiusDp != null) { draft = draft.copy(radiusDp = if (it) 16f else null) }
            draft.radiusDp?.let { radius -> PanelSlider("圆角", radius, 0f..48f) { draft = draft.copy(radiusDp = it) } }
            AppearanceColorField("文字颜色（留空自动）", draft.contentColorHex) { draft = draft.copy(contentColorHex = it) }
            PanelToggle("自定义边框", draft.borderWidthDp != null) { draft = draft.copy(borderWidthDp = if (it) 1f else null) }
            draft.borderWidthDp?.let { width ->
                PanelSlider("边框宽度（0 为关闭）", width, 0f..4f) { draft = draft.copy(borderWidthDp = it) }
                AppearanceColorField("边框颜色（留空跟随主题）", draft.borderColorHex) { draft = draft.copy(borderColorHex = it) }
            }
            PanelToggle("自定义留白", draft.paddingHorizontalDp != null || draft.paddingVerticalDp != null) {
                draft = draft.copy(paddingHorizontalDp = if (it) 12f else null, paddingVerticalDp = if (it) 8f else null)
            }
            draft.paddingHorizontalDp?.let { padding -> PanelSlider("水平留白", padding, 0f..32f) { draft = draft.copy(paddingHorizontalDp = it) } }
            draft.paddingVerticalDp?.let { padding -> PanelSlider("垂直留白", padding, 0f..24f) { draft = draft.copy(paddingVerticalDp = it) } }
            PanelToggle("自定义内容间距", draft.gapDp != null) { draft = draft.copy(gapDp = if (it) 8f else null) }
            draft.gapDp?.let { gap -> PanelSlider("内容间距", gap, 0f..24f) { draft = draft.copy(gapDp = it) } }
            AppTextButton(onClick = { draft = AppSkinPanel() }) { Text("恢复此槽位默认") }
            Row {
                AppTextButton(onClick = onDismiss) { Text("取消") }
                AppButton(enabled = valid, onClick = { onSave(draft.normalized()) }) { Text("保存") }
            }
        }
    }
    if (materialEditor) BubbleMaterialEditor("$title 背景", draft.background ?: ChatBubble(),
        onSave = { draft = draft.copy(background = it); materialEditor = false },
        onDismiss = { materialEditor = false }, staticGlass = true)
}

@Composable
private fun PanelToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label); Switch(value, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
@Composable
private fun PanelSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label：${"%.1f".format(value)}")
    Slider(value, onValueChange = onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = "槽位 $label" })
}
private fun AppSkinPanelSlot.label(): String = when (this) {
    AppSkinPanelSlot.TOP_BAR -> "顶栏"; AppSkinPanelSlot.DRAWER -> "抽屉"; AppSkinPanelSlot.DRAWER_ITEM -> "抽屉条目"
    AppSkinPanelSlot.REASONING -> "思维链卡片"; AppSkinPanelSlot.TOOL -> "工具卡片"
}
