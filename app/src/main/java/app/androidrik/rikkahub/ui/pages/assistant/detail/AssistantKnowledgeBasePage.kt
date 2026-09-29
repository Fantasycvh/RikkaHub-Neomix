package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.rag.RagRepository
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * 助手绑定知识库页：勾选决定该助手装配 `search_knowledge_base` 时的检索范围。
 * 知识库本体在 设置 → 知识库 管理（右上角入口跳过去）。
 */
@Composable
fun AssistantKnowledgeBasePage(
    assistantId: Uuid,
    vm: AssistantKnowledgeBaseVM = koinViewModel { parametersOf(assistantId.toString()) },
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_knowledge_base))
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
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("hint") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.assistant_kb_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_kb_manage_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { nav.navigate(Screen.SettingKnowledgeBase) },
                            ) {
                                Text(stringResource(R.string.assistant_kb_manage))
                            }
                        }
                    }
                }
            }

            if (uiState.knowledgeBases.isEmpty()) {
                item("empty") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = CustomColors.listItemColors.containerColor,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = HugeIcons.Book02,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.setting_page_kb_empty_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(uiState.knowledgeBases, key = { it.id }) { kb ->
                val checked = kb.id in uiState.enabledIds
                Card(
                    onClick = { vm.toggle(kb.id, !checked) },
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = kb.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.setting_page_kb_doc_count,
                                    uiState.documentCounts[kb.id] ?: 0,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { vm.toggle(kb.id, it) },
                        )
                    }
                }
            }
        }
    }
}

class AssistantKnowledgeBaseVM(
    private val assistantId: String,
    private val settingsStore: SettingsStore,
    ragRepository: RagRepository,
) : ViewModel() {
    data class UiState(
        val knowledgeBases: List<app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity> = emptyList(),
        val enabledIds: Set<String> = emptySet(),
        val documentCounts: Map<String, Int> = emptyMap(),
    )

    val uiState = combine(
        ragRepository.getAllKnowledgeBasesFlow(),
        ragRepository.observeDocumentCounts(),
        settingsStore.settingsFlow,
    ) { bases, counts, settings ->
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
        UiState(
            knowledgeBases = bases,
            enabledIds = assistant?.enabledKnowledgeBaseIds ?: emptySet(),
            documentCounts = counts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun toggle(kbId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.id.toString() == assistantId) {
                            val newIds = if (enabled) {
                                assistant.enabledKnowledgeBaseIds + kbId
                            } else {
                                assistant.enabledKnowledgeBaseIds - kbId
                            }
                            assistant.copy(enabledKnowledgeBaseIds = newIds)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
    }
}
