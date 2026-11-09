package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.androidrik.rikkahub.data.model.BubbleShaderStyle
import app.androidrik.rikkahub.data.model.BubbleShaderTemplate
import app.androidrik.rikkahub.ui.components.ui.Select

/** The enclosing editor owns the raw draft, including incomplete color input. */
@Composable
internal fun BubbleShaderControls(value: BubbleShaderStyle, onChange: (BubbleShaderStyle) -> Unit) {
    Select(options = BubbleShaderTemplate.entries, selectedOption = value.template,
        onOptionSelected = { onChange(value.copy(template = it)) },
        optionToString = { when (it) {
            BubbleShaderTemplate.AURORA -> "极光"
            BubbleShaderTemplate.MARBLE -> "流纹"
            BubbleShaderTemplate.IRIDESCENT -> "虹彩"
            BubbleShaderTemplate.RIPPLES -> "水波"
        } })
    value.colors.forEachIndexed { index, color ->
        AppearanceColorField("Shader 颜色 ${index + 1}", color, allowEmpty = false) { hex ->
            onChange(value.copy(colors = value.colors.mapIndexed { i, old -> if (i == index) hex.orEmpty() else old }))
        }
    }
    ShaderParameter("尺度", "${value.scaleDp.toInt()} dp", value.scaleDp, 32f..512f) { onChange(value.copy(scaleDp = it)) }
    ShaderParameter("旋转", "${value.angleDegrees.toInt()}°", value.angleDegrees, 0f..360f) { onChange(value.copy(angleDegrees = it)) }
    ShaderParameter("对比度", "${(value.contrast * 100).toInt()}%", value.contrast, 0f..2f) { onChange(value.copy(contrast = it)) }
    ShaderParameter("扭曲", "${(value.warp * 100).toInt()}%", value.warp, 0f..2f) { onChange(value.copy(warp = it)) }
    ShaderParameter("相位", "${(value.phase * 100).toInt()}%", value.phase, 0f..1f) { onChange(value.copy(phase = it)) }
    Text("相位选择材质的静态画面；聊天和导出使用相同参数。")
    TextButton(onClick = { onChange(BubbleShaderStyle.defaults(value.template)) }) { Text("重置模板参数") }
}

@Composable
private fun ShaderParameter(label: String, text: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label：$text")
    Slider(value, onValueChange = onChange, valueRange = range,
        modifier = Modifier.semantics { contentDescription = "Shader $label" })
}
