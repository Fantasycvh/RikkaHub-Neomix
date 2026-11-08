package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.theme.ChatAppearanceRegistry

@Composable
fun ChatAppearanceSettings(
    display: DisplaySetting,
    appearance: ChatAppearance,
    onSelectPreset: (String?) -> Unit,
    onChange: ((ChatAppearance) -> ChatAppearance) -> Unit,
) {
    val presets by ChatAppearanceRegistry.shared.presets.collectAsState()
    var editingUser by remember { mutableStateOf<Boolean?>(null) }
    var editingTypography by remember { mutableStateOf(false) }
    CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("聊天外观") }) {
        item(headlineContent = { Text("外观方案") }, supportingContent = {
            Column {
                Select(
                    options = listOf<String?>(null) + presets.map { it.id },
                    selectedOption = display.chatAppearancePresetId.takeIf { id -> presets.any { it.id == id } },
                    onOptionSelected = onSelectPreset,
                    optionToString = { id -> presets.firstOrNull { it.id == id }?.name ?: "自定义" },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (display.chatAppearancePresetId != null && presets.none { it.id == display.chatAppearancePresetId }) {
                    Text("方案暂不可用，已回退到自定义外观")
                }
            }
        })
        item(headlineContent = { Text("正文字体渐变") }, supportingContent = {
            Text(if (appearance.typography.gradient == null) "跟随主题文字色" else "已启用")
        }, trailingContent = {
            TextButton(onClick = { editingTypography = true }) { Text("编辑") }
        })
        item(headlineContent = { Text("用户消息靠右") }, trailingContent = {
            Switch(checked = appearance.layout.userAlignEnd, onCheckedChange = { value ->
                onChange { it.copy(layout = it.layout.copy(userAlignEnd = value)) }
            })
        })
        item(headlineContent = { Text("消息内部间距") }, supportingContent = {
            AppearanceSlider(appearance.layout.messageGapDp, 0f..32f) { value ->
                onChange { it.copy(layout = it.layout.copy(messageGapDp = value)) }
            }
        })
        item(headlineContent = { Text("用户气泡底色") }, trailingContent = {
            Switch(checked = appearance.userBubble.visible, onCheckedChange = { value ->
                onChange { it.copy(userBubble = it.userBubble.copy(visible = value)) }
            })
        })
        // CardGroup uses Column; every supporting slot has exactly one direct child.
        listOf(true, false).forEach { user ->
            val bubble = if (user) appearance.userBubble else appearance.assistantBubble
            val name = if (user) "用户" else "助手"
            fun change(transform: (ChatBubble) -> ChatBubble) = onChange {
                if (user) it.copy(userBubble = transform(it.userBubble))
                else it.copy(assistantBubble = transform(it.assistantBubble))
            }
            item(headlineContent = { Text("${name}气泡材质") }, supportingContent = {
                Text(if (bubble.visible) bubble.material.label() else "无色")
            }, trailingContent = {
                TextButton(onClick = { editingUser = user }) { Text("编辑") }
            })
            item(headlineContent = { Text("${name}气泡圆角") }, supportingContent = {
                AppearanceSlider(bubble.geometry.radiusDp, 0f..48f) { value ->
                    change { it.copy(geometry = it.geometry.copy(radiusDp = value)) }
                }
            })
            item(headlineContent = { Text("${name}气泡内边距") }, supportingContent = {
                AppearanceSlider(bubble.geometry.paddingDp, 0f..32f) { value ->
                    change { it.copy(geometry = it.geometry.copy(paddingDp = value)) }
                }
            })
        }
        item(headlineContent = { Text("助手气泡不透明度") }, supportingContent = {
            Column {
                Text("${(appearance.assistantBubble.opacity * 100).toInt()}%")
                Slider(value = appearance.assistantBubble.opacity, onValueChange = { value ->
                    onChange { it.copy(assistantBubble = it.assistantBubble.copy(opacity = value)) }
                }, valueRange = 0f..1f)
            }
        })
    }
    editingUser?.let { user ->
        BubbleMaterialEditor(
            title = if (user) "用户气泡" else "助手气泡",
            initial = if (user) appearance.userBubble else appearance.assistantBubble,
            onSave = { value ->
                onChange { if (user) it.copy(userBubble = value) else it.copy(assistantBubble = value) }
                editingUser = null
            },
            onDismiss = { editingUser = null },
        )
    }
    if (editingTypography) {
        TypographyGradientEditor(initial = appearance.typography.gradient, onSave = { gradient ->
            onChange { it.copy(typography = it.typography.copy(gradient = gradient)) }
            editingTypography = false
        }, onDismiss = { editingTypography = false })
    }
}

@Composable
private fun AppearanceSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("${value.toInt()} dp")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
