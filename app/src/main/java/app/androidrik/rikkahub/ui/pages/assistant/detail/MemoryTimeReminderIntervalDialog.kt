package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.androidrik.rikkahub.R

@Composable
internal fun MemoryTimeReminderIntervalDialog(initialMinutes: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val state = rememberTextFieldState(initialMinutes.toString())
    val minutes = state.text.toString().toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
        text = {
            TextField(
                state = state,
                label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                isError = minutes == null,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = minutes != null, onClick = { minutes?.let(onSave) }) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_page_cancel)) }
        },
    )
}
