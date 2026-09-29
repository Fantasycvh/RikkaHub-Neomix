package app.androidrik.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity
import app.androidrik.rikkahub.data.rag.RagRepository
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SettingKnowledgeBaseDetailPage(
    kbId: String,
    vm: KnowledgeBaseDetailVM = koinViewModel { parametersOf(kbId) },
) {
    val kb by vm.knowledgeBase.collectAsStateWithLifecycle()
    val documents by vm.documents.collectAsStateWithLifecycle()
    val modelsByProvider by vm.embeddingModels.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showModelPicker by remember { mutableStateOf(false) }
    var editingKb by remember(kb) { mutableStateOf(kb) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importDocument(uri)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(kb?.name.orEmpty())
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editingKb != null) {
                item("settings") {
                    KnowledgeBaseSettingsCard(
                        kb = editingKb!!,
                        modelName = vm.modelDisplayName(editingKb!!.embeddingModelId),
                        onUpdate = { vm.update(it) },
                        onPickModel = { showModelPicker = true },
                    )
                }
            }

            item("documents_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_page_kb_documents),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    }) {
                        Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text(stringResource(R.string.setting_page_kb_import))
                    }
                }
            }

            items(documents, key = { it.id }) { doc ->
                DocumentCard(
                    document = doc,
                    onReindex = { vm.reindex(doc) },
                    onDelete = { vm.deleteDocument(doc) },
                )
            }

            if (documents.isEmpty()) {
                item("documents_empty") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = CustomColors.listItemColors.containerColor,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_kb_no_documents),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        EmbeddingModelPickerDialog(
            modelsByProvider = modelsByProvider,
            selectedModelId = editingKb?.embeddingModelId.orEmpty(),
            onDismissRequest = { showModelPicker = false },
            onConfirm = { modelId ->
                showModelPicker = false
                editingKb?.let { vm.update(it.copy(embeddingModelId = modelId)) }
            },
        )
    }
}

@Composable
private fun KnowledgeBaseSettingsCard(
    kb: KnowledgeBaseEntity,
    modelName: String,
    onUpdate: (KnowledgeBaseEntity) -> Unit,
    onPickModel: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_page_kb_settings),
                style = MaterialTheme.typography.titleMedium,
            )
            FormItem(
                label = { Text(stringResource(R.string.setting_page_kb_name)) },
            ) {
                OutlinedTextField(
                    value = kb.name,
                    onValueChange = { onUpdate(kb.copy(name = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            FormItem(
                label = { Text(stringResource(R.string.setting_page_kb_embedding_model)) },
                description = { Text(stringResource(R.string.setting_page_kb_embedding_model_desc)) },
            ) {
                OutlinedButton(
                    onClick = onPickModel,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = modelName.ifBlank {
                            stringResource(R.string.setting_page_kb_model_not_set)
                        },
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.setting_page_kb_top_k)) },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = kb.topK.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { onUpdate(kb.copy(topK = it.coerceIn(1, 50))) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                FormItem(
                    label = { Text(stringResource(R.string.setting_page_kb_threshold)) },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = kb.similarityThreshold.toString(),
                        onValueChange = { text ->
                            text.toFloatOrNull()
                                ?.let { onUpdate(kb.copy(similarityThreshold = it.coerceIn(0f, 1f))) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
            FormItem(
                label = { Text(stringResource(R.string.setting_page_kb_dimensions)) },
                description = { Text(stringResource(R.string.setting_page_kb_dimensions_desc)) },
            ) {
                OutlinedTextField(
                    value = kb.dimensions?.toString().orEmpty(),
                    onValueChange = { text ->
                        onUpdate(kb.copy(dimensions = text.toIntOrNull()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: KnowledgeBaseDocumentEntity,
    onReindex: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        document.error != null -> Tag(type = TagType.ERROR) {
                            Text(stringResource(R.string.setting_page_kb_index_failed))
                        }

                        document.indexed -> Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.setting_page_kb_indexed, document.chunkCount))
                        }

                        else -> Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.setting_page_kb_not_indexed))
                        }
                    }
                }
                document.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                }
            }
            IconButton(onClick = onReindex) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.setting_page_kb_reindex),
                )
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(imageVector = HugeIcons.Delete01, contentDescription = null)
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(HugeIcons.Delete01, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbeddingModelPickerDialog(
    modelsByProvider: Map<String, List<Pair<String, String>>>,
    selectedModelId: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(selectedModelId) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.setting_page_kb_pick_model))
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                modelsByProvider.forEach { (providerName, models) ->
                    item(providerName) {
                        Text(
                            text = providerName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(models, key = { it.first }) { (modelId, displayName) ->
                        val isSelected = modelId == selected
                        Card(
                            onClick = { selected = modelId },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        ) {
                            Text(
                                text = displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                if (modelsByProvider.isEmpty()) {
                    item("empty") {
                        Text(stringResource(R.string.setting_page_kb_no_models))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotBlank() && selected != selectedModelId,
                onClick = { onConfirm(selected) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

class KnowledgeBaseDetailVM(
    private val kbId: String,
    private val ragRepository: RagRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val reloadTrigger = MutableStateFlow(0)

    val knowledgeBase = combine(
        ragRepository.getAllKnowledgeBasesFlow(),
        reloadTrigger,
    ) { bases, _ -> bases.firstOrNull { it.id == kbId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val documents = ragRepository.getDocumentsFlow(kbId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 提供商名 → [(模型 id, 显示名)]，embedding 模型优先展示 */
    val embeddingModels = combine(
        settingsStore.settingsFlow,
        reloadTrigger,
    ) { settings, _ ->
        settings.providers
            .map { provider ->
                provider.name to provider.models
                    .sortedByDescending { it.type == app.androidrik.ai.provider.ModelType.EMBEDDING }
                    .map { it.id.toString() to it.displayName.ifBlank { it.modelId } }
            }
            .toMap()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun modelDisplayName(modelId: String): String {
        if (modelId.isBlank()) return ""
        val settings = settingsStore.settingsFlow.value
        return settings.providers
            .flatMap { it.models }
            .firstOrNull { it.id.toString() == modelId }
            ?.let { it.displayName.ifBlank { it.modelId } }
            .orEmpty()
    }

    fun update(kb: KnowledgeBaseEntity) {
        viewModelScope.launch {
            ragRepository.updateKnowledgeBase(kb)
            reloadTrigger.value += 1
        }
    }

    fun importDocument(uri: android.net.Uri) {
        val kb = knowledgeBase.value ?: return
        viewModelScope.launch {
            val name = queryDisplayName(uri)
            ragRepository.importDocument(kb, uri, name)
            reloadTrigger.value += 1
        }
    }

    fun reindex(document: KnowledgeBaseDocumentEntity) {
        val kb = knowledgeBase.value ?: return
        viewModelScope.launch {
            ragRepository.embedDocument(kb, document.id)
            reloadTrigger.value += 1
        }
    }

    fun deleteDocument(document: KnowledgeBaseDocumentEntity) {
        viewModelScope.launch {
            ragRepository.deleteDocument(document.id)
        }
    }

    /** SAF Uri 没有可靠的文件名，显示名取最后一段（文档导入用不到 pathList 深层信息） */
    private fun queryDisplayName(uri: android.net.Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }
}
