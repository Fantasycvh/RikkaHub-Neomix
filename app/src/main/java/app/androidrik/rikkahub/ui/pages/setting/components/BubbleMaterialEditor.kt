package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.BubbleMaterial
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.data.model.GradientDirection
import app.androidrik.rikkahub.ui.components.ui.Select

@Composable
fun BubbleMaterialEditor(title: String, initial: ChatBubble, onSave: (ChatBubble) -> Unit, onDismiss: () -> Unit) {
    BubbleLayersEditor(title, initial, onSave, onDismiss)
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
    BubbleMaterial.GLASS -> "玻璃"
}
