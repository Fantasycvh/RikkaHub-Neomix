package app.androidrik.rikkahub.ui.activity

import app.androidrik.rikkahub.ui.components.ui.appSkinButtonShape
import app.androidrik.rikkahub.RikkaHubApp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.GateTheme
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.datastore.RecoverySettings
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.hooks.writeStringPreference
import app.androidrik.rikkahub.RouteActivity
import app.androidrik.rikkahub.utils.CrashHandler
import kotlin.uuid.Uuid

class SafeModeActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RikkaHubApp
        if (!app.crashRecoveryPending) {
            finish()
            return
        }

        val stackTrace = CrashHandler.getStackTrace(this)
        val recoverySettings = RecoverySettings(app)
        enableEdgeToEdge()
        setContent {
            GateTheme {
                val export: RecoveryExportViewModel = viewModel()
                var settings by remember { mutableStateOf<Settings?>(null) }
                var showAssistantPicker by remember { mutableStateOf(false) }
                var largeFields by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
                var pendingClearKey by remember { mutableStateOf<String?>(null) }
                var repairing by remember { mutableStateOf(false) }
                var repairFailed by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val controlsEnabled = !export.busy && !repairing

                LaunchedEffect(repairing) {
                    if (!repairing) {
                        largeFields = recoverySettingOperation(app) { recoverySettings.largeFields() }.orEmpty()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text(stringResource(R.string.safe_mode_title)) })
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.safe_mode_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        RecoveryExportActions(export, enabled = !repairing)

                        settings?.let { loaded ->
                            Text(
                                text = stringResource(
                                    R.string.safe_mode_current_assistant,
                                    loaded.getCurrentAssistant().name.ifEmpty {
                                        stringResource(R.string.safe_mode_default_assistant)
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        Button(
                            onClick = {
                                app.resumeAfterCrashRecovery()
                                startActivity(Intent(this@SafeModeActivity, RouteActivity::class.java))
                                finish()
                            },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_enter_app))
                        }

                        OutlinedButton(
                            onClick = {
                                repairing = true
                                repairFailed = false
                                scope.launch {
                                    try {
                                        val loaded = recoverySettingOperation(app) { recoverySettings.readForBackup() }
                                        if (loaded != null) {
                                            settings = loaded
                                            showAssistantPicker = true
                                        } else {
                                            repairFailed = true
                                        }
                                    } finally {
                                        repairing = false
                                    }
                                }
                            },
                            enabled = controlsEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.safe_mode_switch_assistant))
                        }

                        if (repairFailed) {
                            Text(stringResource(R.string.safe_mode_repair_failed))
                        }

                        if (largeFields.isNotEmpty()) {
                            CardGroup(
                                modifier = Modifier.fillMaxWidth(),
                                title = { Text(stringResource(R.string.safe_mode_oversized_data_title)) },
                            ) {
                                largeFields.forEach { (key, chars) ->
                                    item(
                                        headlineContent = { Text(storedFieldLabel(key)) },
                                        supportingContent = {
                                            // 百万字符级展示（500 万 = 5.0M），避免 7 位数挤爆行尾
                                            Text(stringResource(
                                                R.string.safe_mode_oversized_field_chars,
                                                "%.1fM".format(chars / 1_000_000f),
                                            ))
                                        },
                                        trailingContent = {
                                            FilledTonalButton(
                                                onClick = { pendingClearKey = key },
                                                enabled = controlsEnabled,
                                            ) {
                                                Text(stringResource(R.string.safe_mode_clear))
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        if (stackTrace != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.safe_mode_crash_report),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                OutlinedButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("crash", stackTrace))
                                    }
                                ) {
                                    Text(stringResource(R.string.safe_mode_copy))
                                }
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                val vScroll = rememberScrollState()
                                val hScroll = rememberScrollState()
                                Text(
                                    text = stackTrace,
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .verticalScroll(vScroll)
                                        .horizontalScroll(hScroll),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                pendingClearKey?.let { key ->
                    // AlertDialog 只承载「确认清除」的简短确认（两个按钮、无输入框），不属于
                    // AGENTS.md 禁止的多字段表单宿主场景
                    AlertDialog(
                        onDismissRequest = { pendingClearKey = null },
                        title = { Text(stringResource(R.string.safe_mode_clear_field_title, storedFieldLabel(key))) },
                        text = { Text(stringResource(R.string.safe_mode_clear_field_desc)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingClearKey = null
                                    repairing = true
                                    repairFailed = false
                                    scope.launch {
                                        try {
                                            if (recoverySettingOperation(app) { recoverySettings.resetField(key) } == null) {
                                                repairFailed = true
                                            } else {
                                                settings = null
                                            }
                                        } finally {
                                            repairing = false
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.safe_mode_clear), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingClearKey = null }) {
                                Text(stringResource(R.string.safe_mode_cancel))
                            }
                        },
                    )
                }

                val loaded = settings
                if (showAssistantPicker && loaded != null) {
                    AssistantPickerSheet(
                        settings = loaded,
                        onAssistantSelected = { assistantId ->
                            showAssistantPicker = false
                            repairing = true
                            repairFailed = false
                            scope.launch {
                                try {
                                    if (recoverySettingOperation(app) { recoverySettings.selectAssistant(assistantId) } == null) {
                                        repairFailed = true
                                    } else {
                                        settings = loaded.copy(assistantId = assistantId)
                                        context.writeStringPreference("lastConversationId", null)
                                    }
                                } finally {
                                    repairing = false
                                }
                            }
                        },
                        onDismiss = { showAssistantPicker = false }
                    )
                }
            }
        }
    }
}

private suspend fun <T> recoverySettingOperation(app: RikkaHubApp, block: suspend () -> T): T? =
    try {
        DataOperationLock.runWaiting(DataOperation.BACKUP) {
            check(app.crashRecoveryPending)
            withContext(Dispatchers.IO) { block() }
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        Log.w("SafeMode", "Settings operation failed: ${failure.javaClass.simpleName}")
        null
    } catch (_: OutOfMemoryError) {
        null
    }

@Composable
private fun storedFieldLabel(key: String): String = when (key) {
    SettingsStore.LOREBOOKS.name -> stringResource(R.string.prompt_page_lorebook_tab)
    SettingsStore.ASSISTANTS.name -> stringResource(R.string.safe_mode_assistants)
    else -> key
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantPickerSheet(
    settings: Settings,
    onAssistantSelected: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    val filteredAssistants = remember(settings.assistants, selectedTagIds) {
        if (selectedTagIds.isEmpty()) settings.assistants
        else settings.assistants.filter { it.tags.any { id -> id in selectedTagIds } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_assistants),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (settings.assistantTags.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(settings.assistantTags, key = { it.id }) { tag ->
                        FilterChip(
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            selected = tag.id in selectedTagIds,
                            shape = appSkinButtonShape(),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredAssistants, key = { it.id }) { assistant ->
                    val checked = assistant.id == settings.assistantId
                    Card(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onAssistantSelected(assistant.id)
                            }
                        },
                        modifier = Modifier.animateItem(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.safe_mode_default_assistant) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
