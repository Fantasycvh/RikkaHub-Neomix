package app.androidrik.rikkahub.ui.pages.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SnapshotPasswordSheet(
    configuring: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { if (enabled) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = password,
                onValueChange = { if (it.length <= 256) password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.local_snapshot_password)) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (configuring) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { if (it.length <= 256) confirmation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.local_snapshot_repeat_password)) },
                    singleLine = true,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text(stringResource(R.string.local_snapshot_password_help))
            }
            TextButton(
                onClick = { onSubmit(password) },
                enabled = enabled && password.isNotBlank() && (!configuring || password.length >= 12 && password == confirmation),
            ) {
                Text(stringResource(if (configuring) R.string.common_save else R.string.local_snapshot_unlock))
            }
            onRemove?.let { TextButton(onClick = it, enabled = enabled) { Text(stringResource(R.string.local_snapshot_remove_password)) } }
        }
    }
}
