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
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_ALL_HISTORY
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.ai.consolidationMemoryId
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.data.model.MemoryFileCategory
import app.androidrik.rikkahub.data.repository.MemoryFileError
import app.androidrik.rikkahub.data.repository.MemoryFileException
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.titleRes
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
internal fun AssistantFileMemoryContent(
    assistant: Assistant,
    storeId: String,
    vm: AssistantDetailVM,
    showRecovery: Boolean,
    onDismissRecovery: () -> Unit,
) {
    val repository = vm.memoryFiles
    val files by remember(repository, storeId) { repository.observe(storeId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = remember(files) { files.groupBy { MemoryFileCategory.of(it.path) } }
    val libraryId = assistant.consolidationMemoryId()
    val status by remember(vm, libraryId) { vm.consolidationStatusOf(libraryId) }
        .collectAsStateWithLifecycle(initialValue = MemoryConsolidationStatus())
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    val triggerModeTitle = LocalSettings.current.memoryTriggerConfig.mode.titleRes()
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MemoryFileEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryFileEntity?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showTimeInterval by remember { mutableStateOf(false) }

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
                error = failure.memoryFileErrorRes()
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
            if (assistant.enableMemory) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.memory_trigger_page_title)) },
                    supportingContent = { Text(stringResource(triggerModeTitle)) },
                    modifier = Modifier.clickable { navController.navigate(Screen.SettingMemoryTrigger) },
                )
            }
            FileMemorySwitch(
                label = stringResource(R.string.assistant_page_memory_consolidation),
                checked = assistant.fileMemoryConsolidationSince > 0,
                enabled = assistant.enabled && assistant.enableMemory,
                description = stringResource(R.string.assistant_page_memory_file_idle_desc),
            ) { checked ->
                vm.updateMemorySettings {
                    it.copy(fileMemoryConsolidationSince = if (checked) System.currentTimeMillis() else 0)
                }
            }
            if (assistant.enableMemory && assistant.fileMemoryConsolidationSince > 0L) {
                val allHistory = assistant.fileMemoryConsolidationSince == MEMORY_CONSOLIDATION_ALL_HISTORY
                ListItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_consolidation_range)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.assistant_page_memory_file_range_desc))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !allHistory,
                                    onClick = {
                                        if (allHistory) vm.updateMemorySettings {
                                            it.copy(fileMemoryConsolidationSince = System.currentTimeMillis())
                                        }
                                    },
                                    label = { Text(stringResource(R.string.assistant_page_memory_consolidation_range_new)) },
                                )
                                FilterChip(
                                    selected = allHistory,
                                    onClick = {
                                        if (!allHistory) vm.updateMemorySettings {
                                            it.copy(fileMemoryConsolidationSince = MEMORY_CONSOLIDATION_ALL_HISTORY)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.assistant_page_memory_consolidation_range_all)) },
                                )
                            }
                        }
                    },
                )
            }
            FileMemoryReviewStatus(
                status = status,
                enabled = assistant.enabled && assistant.enableMemory,
                libraryId = libraryId,
                vm = vm,
            )
            if (assistant.enableMemory) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_memory_freeze)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_memory_freeze_desc)) },
                    trailingContent = {
                        TextButton(onClick = {
                            scope.launch {
                                val count = vm.freezeMemoryAnchors()
                                toaster.show(resources.getString(R.string.assistant_page_memory_freeze_done, count))
                            }
                        }) { Text(stringResource(R.string.assistant_page_memory_freeze_action)) }
                    },
                )
            }
            FileMemorySwitch(
                stringResource(R.string.assistant_page_recent_chats),
                assistant.enableRecentChatsReference,
                description = stringResource(R.string.assistant_page_recent_chats_desc),
            ) { checked -> vm.updateMemorySettings { it.copy(enableRecentChatsReference = checked) } }
            FileMemorySwitch(
                stringResource(R.string.assistant_page_time_reminder),
                assistant.enableTimeReminder,
                description = stringResource(R.string.assistant_page_time_reminder_desc),
            ) { checked -> vm.updateMemorySettings { it.copy(enableTimeReminder = checked) } }
            if (assistant.enableTimeReminder) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = {
                        Text(stringResource(R.string.assistant_page_time_reminder_interval_value, assistant.timeReminderIntervalMinutes))
                    },
                    modifier = Modifier.clickable { showTimeInterval = true },
                )
            }
        }
        item(key = "new-file") {
            TextButton(onClick = { selected = null; error = null; editing = true }) {
                Text(stringResource(R.string.assistant_page_memory_file_new))
            }
            if (files.isEmpty()) {
                Text(stringResource(R.string.assistant_page_memory_file_empty), Modifier.padding(horizontal = 16.dp))
            }
        }
        for (category in MemoryFileCategory.entries) {
            val categoryFiles = groups[category] ?: continue
            item(key = category) {
                Text(
                    stringResource(category.labelRes()),
                    Modifier.padding(start = 16.dp, top = 12.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            items(categoryFiles, key = { it.identity }) { file ->
                ListItem(
                    headlineContent = { Text(file.path) },
                    supportingContent = if (file.anchorId != null) {
                        { Text(stringResource(R.string.assistant_page_memory_anchor)) }
                    } else null,
                    modifier = Modifier.clickable { selected = file; error = null; editing = true },
                    trailingContent = {
                        TextButton(onClick = { error = null; pendingDelete = file }) {
                            Text(stringResource(R.string.delete))
                        }
                    },
                )
            }
        }
    }
    if (showTimeInterval) {
        MemoryTimeReminderIntervalDialog(
            initialMinutes = assistant.timeReminderIntervalMinutes,
            onDismiss = { showTimeInterval = false },
            onSave = { minutes ->
                vm.updateMemorySettings { it.copy(timeReminderIntervalMinutes = minutes) }
                showTimeInterval = false
            },
        )
    }
    if (editing) {
        FileMemoryEditor(
            file = selected,
            error = error?.let { stringResource(it) },
            busy = busy,
            onDismiss = { if (!busy) editing = false },
            onSave = { path, content, pin ->
                val file = selected
                runChange {
                    if (file == null) {
                        repository.writeFile(storeId, path, null, pin) { content }
                    } else {
                        repository.move(storeId, file.path, path, file.revision, content, pin)
                    }
                    editing = false
                }
            },
        )
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(stringResource(R.string.assistant_page_memory_file_delete_confirm)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(file.path)
                    Text(error?.let { stringResource(it) } ?: stringResource(R.string.memory_file_delete_to_trash))
                }
            },
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
    if (showRecovery) FileMemoryRecoverySheet(storeId, files, repository, onDismissRecovery)
}

internal fun Exception.memoryFileErrorRes(): Int = when ((this as? MemoryFileException)?.reason) {
    MemoryFileError.INVALID_PATH -> R.string.assistant_page_memory_file_path_invalid
    MemoryFileError.CHANGED -> R.string.assistant_page_memory_file_changed
    MemoryFileError.PATH_OCCUPIED -> R.string.memory_file_restore_path_occupied
    MemoryFileError.TOO_LARGE -> R.string.assistant_page_memory_file_too_large
    MemoryFileError.LIBRARY_FULL -> R.string.assistant_page_memory_file_library_full
    null -> R.string.assistant_page_memory_file_update_failed
}

@Composable
private fun FileMemorySwitch(
    label: String, checked: Boolean, enabled: Boolean = true, description: String? = null, onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileMemoryEditor(
    file: MemoryFileEntity?,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    val path = rememberTextFieldState(file?.path.orEmpty())
    val content = rememberTextFieldState(file?.content.orEmpty())
    var pinned by remember { mutableStateOf(file?.anchorId != null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val value = path.text.toString()
                val selectedCategory = value.takeIf { it.startsWith("/") }?.let(MemoryFileCategory::of)
                for (category in MemoryFileCategory.entries) {
                    FilterChip(
                        selected = category == selectedCategory,
                        enabled = !busy,
                        onClick = {
                            val name = value.substringAfterLast('/').takeIf { it.endsWith(".md") }
                                ?: category.examplePath.substringAfterLast('/')
                            val next = if (category.pathPrefix.endsWith(".md")) category.examplePath
                                else category.pathPrefix + name
                            if (next != value) path.setTextAndPlaceCursorAtEnd(next)
                        },
                        label = { Text(stringResource(category.labelRes())) },
                    )
                }
            }
            OutlinedTextField(
                state = path,
                modifier = Modifier.fillMaxWidth(),
                readOnly = busy,
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
            FileMemorySwitch(
                stringResource(R.string.assistant_page_memory_anchor),
                pinned,
                enabled = !busy,
                description = stringResource(R.string.assistant_page_memory_anchor_hint),
                onChange = { pinned = it },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
                TextButton(enabled = !busy, onClick = { onSave(path.text.toString(), content.text.toString(), pinned) }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            }
        }
    }
}

private fun MemoryFileCategory.labelRes(): Int = when (this) {
    MemoryFileCategory.PROFILE -> R.string.assistant_page_memory_file_category_profile
    MemoryFileCategory.PREFERENCES -> R.string.assistant_page_memory_file_category_preferences
    MemoryFileCategory.TOPICS -> R.string.assistant_page_memory_file_category_topics
    MemoryFileCategory.AREAS -> R.string.assistant_page_memory_file_category_areas
    MemoryFileCategory.PEOPLE -> R.string.assistant_page_memory_file_category_people
    MemoryFileCategory.OTHER -> R.string.assistant_page_memory_file_category_other
}
