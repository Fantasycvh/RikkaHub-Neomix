package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.ui.theme.toBrush
import me.rerere.bettertext.text.BetterText

@Composable
fun TypographyGradientEditor(initial: AppearanceGradient?, onSave: (AppearanceGradient?) -> Unit, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(initial != null) }
    var draft by remember { mutableStateOf(initial ?: AppearanceGradient()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正文字体渐变", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("启用渐变")
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            BetterText("渐变文字预览 · Gradient text", style = if (enabled)
                MaterialTheme.typography.titleLarge.copy(brush = draft.normalized().toBrush())
                else MaterialTheme.typography.titleLarge)
            if (enabled) GradientEditor(draft) { draft = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(enabled = !enabled || draft.colors.all { isAppearanceHex(it, false) },
                    onClick = { onSave(if (enabled) draft.normalized() else null) }) { Text("保存") }
            }
        }
    }
}
