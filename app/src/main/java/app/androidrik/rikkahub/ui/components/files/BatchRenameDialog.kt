package app.androidrik.rikkahub.ui.components.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Preview is the same immutable plan passed to the caller; storage must still reject conflicts. */
@Composable
fun BatchRenameDialog(
    items: List<RenameItem>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    if (items.isEmpty()) return
    val single = items.size == 1
    var baseName by remember(items) { mutableStateOf(if (single) items.single().name else "") }
    var startText by remember(items) { mutableStateOf("1") }
    var keepExtension by remember(items) { mutableStateOf(true) }
    val result = runCatching {
        buildRenamePlan(items, baseName, if (single) 1 else startText.toIntOrNull() ?: -1, keepExtension)
    }
    val plan = result.getOrNull()
    val changed = plan != null && items.any { plan[it.key] != it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single) "重命名" else "批量重命名（${items.size} 项）") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (single) "请输入完整名称（含扩展名）。" else "按当前列表顺序命名为「基本名称_序号」，确认前请检查预览。同名文件不会被覆盖。")
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text(if (single) "名称" else "基本名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!single) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("起始序号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepExtension, onCheckedChange = { keepExtension = it })
                        Text("保留文件扩展名")
                    }
                }
                if (plan != null) {
                    Text("预览")
                    items.take(50).forEach { item -> Text("${item.name} → ${plan.getValue(item.key)}") }
                    if (items.size > 50) Text("另有 ${items.size - 50} 项将按相同规则命名")
                } else if (baseName.isNotEmpty()) {
                    Text(result.exceptionOrNull()?.message ?: "名称无效")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed, onClick = { plan?.let(onConfirm) }) { Text("确认重命名") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
