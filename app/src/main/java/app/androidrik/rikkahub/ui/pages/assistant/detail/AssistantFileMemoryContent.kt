package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.data.repository.MemoryFileError
import app.androidrik.rikkahub.data.repository.MemoryFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MemoryModeSelector(mode: MemoryMode, onSelect: (MemoryMode) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.assistant_page_memory_mode))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == MemoryMode.ENTRIES,
                onClick = { onSelect(MemoryMode.ENTRIES) },
                label = { Text(stringResource(R.string.assistant_page_memory_mode_entries)) },
            )
            FilterChip(
                selected = mode == MemoryMode.FILES,
                onClick = { onSelect(MemoryMode.FILES) },
                label = { Text(stringResource(R.string.assistant_page_memory_mode_files)) },
            )
        }
        Text(stringResource(R.string.assistant_page_memory_mode_desc))
    }
}

@Composable
internal fun AssistantFileMemoryContent(assistant: Assistant, storeId: String, vm: AssistantDetailVM) {
    val repository = vm.memoryFiles
    val files by remember(repository, storeId) { repository.observe(storeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MemoryFileEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryFileEntity?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runChange(action: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when ((failure as? MemoryFileException)?.reason) {
                    MemoryFileError.INVALID_PATH -> R.string.assistant_page_memory_file_path_invalid
                    MemoryFileError.CHANGED -> R.string.assistant_page_memory_file_changed
                    MemoryFileError.TOO_LARGE -> R.string.assistant_page_memory_file_too_large
                    MemoryFileError.LIBRARY_FULL -> R.string.assistant_page_memory_file_library_full
                    null -> R.string.assistant_page_memory_file_update_failed
                }
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        item(key = "settings") {
            FileMemorySwitch(stringResource(R.string.assistant_page_memory), assistant.enableMemory) { checked ->
                vm.updateMemorySettings { it.copy(enableMemory = checked) }
            }
            FileMemorySwitch(stringResource(R.string.assistant_page_global_memory), assistant.useGlobalMemory) { checked ->
                vm.updateMemorySettings { it.copy(useGlobalMemory = checked) }
            }
            FileMemorySwitch(stringResource(R.string.assistant_page_memory_create_approval), assistant.memoryCreateApproval) { checked ->
                vm.updateMemorySettings { it.copy(memoryCreateApproval = checked) }
            }
            FileMemorySwitch(stringResource(R.string.assistant_page_memory_modify_approval), assistant.memoryModifyApproval) { checked ->
                vm.updateMemorySettings { it.copy(memoryModifyApproval = checked) }
            }
            TextButton(onClick = { selected = null; error = null; editing = true }) {
                Text(stringResource(R.string.assistant_page_memory_file_new))
            }
            if (files.isEmpty()) {
                Text(stringResource(R.string.assistant_page_memory_file_empty), Modifier.padding(horizontal = 16.dp))
            }
        }
        items(files, key = { it.path }) { file ->
            ListItem(
                headlineContent = { Text(file.path) },
                modifier = Modifier.clickable { selected = file; error = null; editing = true },
                trailingContent = {
                    TextButton(onClick = { error = null; pendingDelete = file }) {
                        Text(stringResource(R.string.delete))
                    }
                },
            )
        }
    }
    if (editing) {
        FileMemoryEditor(
            file = selected,
            error = error?.let { stringResource(it) },
            busy = busy,
            onDismiss = { if (!busy) editing = false },
            onSave = { path, content ->
                val revision = selected?.revision
                runChange {
                    repository.write(storeId, path, revision) { content }
                    editing = false
                }
            },
        )
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.assistant_page_memory_file_delete_confirm)) },
            text = { Text(error?.let { stringResource(it) } ?: file.path) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    runChange {
                        repository.delete(storeId, file.path, file.revision)
                        pendingDelete = null
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun FileMemorySwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileMemoryEditor(
    file: MemoryFileEntity?,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    val path = rememberTextFieldState(file?.path.orEmpty())
    val content = rememberTextFieldState(file?.content.orEmpty())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                state = path,
                modifier = Modifier.fillMaxWidth(),
                readOnly = file != null || busy,
                label = { Text(stringResource(R.string.assistant_page_memory_file_path)) },
                placeholder = { Text("/topics/example.md") },
            )
            OutlinedTextField(
                state = content,
                modifier = Modifier.fillMaxWidth(),
                readOnly = busy,
                label = { Text(stringResource(R.string.chat_page_export_markdown)) },
            )
            error?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
                TextButton(enabled = !busy, onClick = { onSave(path.text.toString(), content.text.toString()) }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            }
        }
    }
}
