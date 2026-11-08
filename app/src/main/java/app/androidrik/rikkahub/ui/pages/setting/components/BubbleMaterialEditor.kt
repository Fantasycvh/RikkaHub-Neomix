package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.BubblePatternKind
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.GradientDirection
import app.androidrik.rikkahub.ui.components.message.ChatBubbleSurface
import app.androidrik.rikkahub.ui.components.ui.Select

@Composable
fun BubbleMaterialEditor(title: String, initial: ChatBubble, onSave: (ChatBubble) -> Unit, onDismiss: () -> Unit) {
    // The sheet owns editing state for its lifetime; settings updates never write into an active IME session.
    var draft by remember { mutableStateOf(initial) }
    val valid = isAppearanceHex(draft.colorHex) && isAppearanceHex(draft.pattern.colorHex) &&
        draft.gradient.colors.all { isAppearanceHex(it, allowEmpty = false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            ChatBubbleSurface(style = draft.normalized(), defaultColor = MaterialTheme.colorScheme.primaryContainer) {
                Text("聊天气泡预览 · Preview", Modifier.padding(8.dp))
            }
            Select(
                options = listOf<BubbleMaterial?>(null) + BubbleMaterial.entries,
                selectedOption = draft.material.takeIf { draft.visible },
                onOptionSelected = { draft = if (it == null) draft.copy(visible = false) else draft.copy(visible = true, material = it) },
                optionToString = { it?.label() ?: "无色" },
            )
            if (draft.visible) {
                AppearanceColorField("底色（留空跟随主题）", draft.colorHex) { draft = draft.copy(colorHex = it) }
                when (draft.material) {
                    BubbleMaterial.GRADIENT -> GradientEditor(draft.gradient) { draft = draft.copy(gradient = it) }
                    BubbleMaterial.PATTERN -> {
                        Select(options = BubblePatternKind.entries, selectedOption = draft.pattern.kind,
                            onOptionSelected = { draft = draft.copy(pattern = draft.pattern.copy(kind = it)) },
                            optionToString = { when (it) { BubblePatternKind.DOTS -> "圆点"; BubblePatternKind.GRID -> "网格"; BubblePatternKind.STRIPES -> "斜纹" } })
                        AppearanceColorField("图案颜色（留空自动选择）", draft.pattern.colorHex) {
                            draft = draft.copy(pattern = draft.pattern.copy(colorHex = it))
                        }
                        Text("图案间距：${draft.pattern.spacingDp.toInt()} dp")
                        Slider(value = draft.pattern.spacingDp, valueRange = 4f..64f,
                            onValueChange = { draft = draft.copy(pattern = draft.pattern.copy(spacingDp = it)) })
                        Text("图案强度：${(draft.pattern.opacity * 100).toInt()}%")
                        Slider(value = draft.pattern.opacity, onValueChange = { draft = draft.copy(pattern = draft.pattern.copy(opacity = it)) })
                    }
                    BubbleMaterial.NOISE -> {
                        Text("颗粒强度：${(draft.noise.strength * 100).toInt()}%")
                        Slider(value = draft.noise.strength, onValueChange = { draft = draft.copy(noise = draft.noise.copy(strength = it)) })
                    }
                    BubbleMaterial.SOLID -> Unit
                }
                Text("不透明度：${(draft.opacity * 100).toInt()}%")
                Slider(value = draft.opacity, onValueChange = { draft = draft.copy(opacity = it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(enabled = valid, onClick = { onSave(draft.normalized()) }) { Text("保存") }
            }
        }
    }
}

@Composable
internal fun GradientEditor(value: AppearanceGradient, onChange: (AppearanceGradient) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        value.colors.forEachIndexed { index, color ->
            AppearanceColorField("渐变颜色 ${index + 1}", color, allowEmpty = false) { hex ->
                onChange(value.copy(colors = value.colors.mapIndexed { i, old -> if (i == index) hex.orEmpty() else old }))
            }
        }
        Select(options = GradientDirection.entries, selectedOption = value.direction,
            onOptionSelected = { onChange(value.copy(direction = it)) },
            optionToString = { when (it) { GradientDirection.HORIZONTAL -> "横向"; GradientDirection.VERTICAL -> "纵向"; GradientDirection.DIAGONAL -> "对角线" } })
    }
}

@Composable
internal fun AppearanceColorField(label: String, value: String?, allowEmpty: Boolean = true, onChange: (String?) -> Unit) {
    OutlinedTextField(value = value.orEmpty(), onValueChange = { onChange(it.takeIf(String::isNotEmpty)) },
        label = { Text(label) }, placeholder = { Text("#RRGGBB") }, singleLine = true,
        isError = !isAppearanceHex(value, allowEmpty), modifier = Modifier.fillMaxWidth())
}

internal fun isAppearanceHex(value: String?, allowEmpty: Boolean = true): Boolean =
    if (value.isNullOrEmpty()) allowEmpty else Regex("#[0-9a-fA-F]{6}").matches(value)

internal fun BubbleMaterial.label(): String = when (this) {
    BubbleMaterial.SOLID -> "纯色"
    BubbleMaterial.GRADIENT -> "渐变"
    BubbleMaterial.PATTERN -> "图案"
    BubbleMaterial.NOISE -> "颗粒噪声"
}
