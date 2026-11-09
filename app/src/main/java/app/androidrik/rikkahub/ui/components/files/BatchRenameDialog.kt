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
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R

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
        title = { Text(if (single) stringResource(R.string.common_rename) else stringResource(R.string.batch_rename_title_batch, items.size)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (single) stringResource(R.string.batch_rename_hint_single) else stringResource(R.string.batch_rename_hint_batch))
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text(if (single) stringResource(R.string.batch_rename_label_name) else stringResource(R.string.batch_rename_label_base_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!single) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text(stringResource(R.string.batch_rename_label_start_index)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keepExtension, onCheckedChange = { keepExtension = it })
                        Text(stringResource(R.string.batch_rename_keep_extension))
                    }
                }
                if (plan != null) {
                    Text(stringResource(R.string.code_block_preview))
                    items.take(50).forEach { item -> Text("${item.name} → ${plan.getValue(item.key)}") }
                    if (items.size > 50) Text(stringResource(R.string.batch_rename_more_items, items.size - 50))
                } else if (baseName.isNotEmpty()) {
                    val validationError = (result.exceptionOrNull() as? RenamePlanException)?.error
                    Text(
                        when (validationError) {
                            RenameValidationError.BLANK_NAME -> stringResource(R.string.batch_rename_error_blank_name)
                            RenameValidationError.INVALID_CHARS -> stringResource(R.string.batch_rename_error_invalid_chars)
                            RenameValidationError.NAME_TOO_LONG -> stringResource(R.string.batch_rename_error_too_long)
                            RenameValidationError.NO_FILES -> stringResource(R.string.batch_rename_error_no_files)
                            RenameValidationError.DUPLICATE_ITEMS -> stringResource(R.string.batch_rename_error_duplicate_items)
                            RenameValidationError.START_INDEX_OUT_OF_RANGE -> stringResource(R.string.batch_rename_error_start_index_out_of_range)
                            RenameValidationError.DUPLICATE_NAMES -> stringResource(R.string.batch_rename_error_duplicate_names)
                            null -> stringResource(R.string.batch_rename_error_invalid_name)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = changed, onClick = { plan?.let(onConfirm) }) { Text(stringResource(R.string.batch_rename_confirm_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
