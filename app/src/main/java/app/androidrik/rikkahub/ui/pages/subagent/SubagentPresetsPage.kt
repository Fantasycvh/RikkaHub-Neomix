package app.androidrik.rikkahub.ui.pages.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.model.SUBAGENT_NAME_MAX_CHARS
import app.androidrik.rikkahub.data.model.SUBAGENT_TIMEOUT_SECONDS_RANGE
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import app.androidrik.rikkahub.data.model.isSubagentNameAvailable
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Rocket01
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * 子代理预设管理页（设计见 `docs/design-subagent-context.md` §十）。
 *
 * 一页只回答两件事：**要不要让模型在对话里派子代理**（顶部开关），以及**有哪些组合可选**
 * （下方列表 + 编辑对话框）。刻意不做成助手设置里的一个 tab：预设是全局配置，
 * 放在助手页会让人以为"每个助手各配一套"。
 */
@Composable
fun SubagentPresetsPage(vm: SubagentPresetsVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val presets = settings.subagentPresets

    // null = 编辑器关闭；值 = 正在编辑的草稿（新建时是默认构造的预设）
    var editing by remember { mutableStateOf<SubagentPreset?>(null) }
    var deleting by remember { mutableStateOf<SubagentPreset?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.subagent_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = SubagentPreset() }) {
                Icon(HugeIcons.Rocket01, contentDescription = stringResource(R.string.subagent_preset_new))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text(stringResource(R.string.subagent_dispatch_enabled)) },
                        supportingContent = { Text(stringResource(R.string.subagent_dispatch_enabled_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.enableSubagentDispatch,
                                onCheckedChange = { vm.setDispatchEnabled(it) },
                            )
                        },
                    )
                }
            }

            if (presets.isEmpty()) {
                item {
                    CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                        item(
                            headlineContent = { Text(stringResource(R.string.subagent_presets_empty)) },
                            supportingContent = { Text(stringResource(R.string.subagent_page_desc)) },
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.subagent_page_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(presets, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        settings = settings,
                        onClick = { editing = preset },
                        onToggleEnabled = { vm.setPresetEnabled(preset.id, it) },
                        onDelete = { deleting = preset },
                    )
                }
            }
        }
    }

    editing?.let { draft ->
        PresetEditorDialog(
            draft = draft,
            presets = presets,
            settings = settings,
            workspaces = workspaces,
            vm = vm,
            onDismiss = { editing = null },
            onSave = {
                vm.upsertPreset(it)
                editing = null
                toaster.show(context.getString(R.string.subagent_preset_saved))
            },
        )
    }

    deleting?.let { target ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.subagent_preset_delete),
            text = { Text(stringResource(R.string.subagent_preset_delete_confirm, target.name)) },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                vm.deletePreset(target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PresetCard(
    preset: SubagentPreset,
    settings: Settings,
    onClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val modelName = preset.modelId
        ?.let { settings.findModelById(it)?.displayName }
        ?: stringResource(R.string.subagent_preset_model_follow)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clickable(onClick = onClick),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 长调度名不得把右侧的开关/删除挤出卡片（段卡真机踩过的坑）
                Text(
                    text = preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = preset.enabled, onCheckedChange = onToggleEnabled)
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = HugeIcons.Delete02,
                            contentDescription = stringResource(R.string.subagent_preset_delete),
                        )
                    }
                }
            }
            if (preset.description.isNotBlank()) {
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    modelName,
                    if (preset.toolApproval == SubagentToolApproval.ASK) {
                        stringResource(R.string.subagent_preset_tool_approval_ask)
                    } else {
                        null
                    },
                    if (preset.enableWebSearch) stringResource(R.string.subagent_preset_web_search) else null,
                    if (preset.workspaceId != null) {
                        stringResource(R.string.subagent_preset_capability_workspace)
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 模型下拉的选项类型：`id == null` = 跟随当前对话模型（不用可空类型是为了让 Select 的类型推导干净）。 */
private data class ModelChoice(val id: Uuid?, val label: String)

/** 工作区下拉的选项类型：`id == null` = 不使用工作区。 */
private data class WorkspaceChoice(val id: String?, val label: String)

@Composable
private fun PresetEditorDialog(
    draft: SubagentPreset,
    presets: List<SubagentPreset>,
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    vm: SubagentPresetsVM,
    onDismiss: () -> Unit,
    onSave: (SubagentPreset) -> Unit,
) {
    var edited by remember(draft) { mutableStateOf(draft) }
    var candidates by remember(draft) { mutableStateOf<List<String>>(emptyList()) }
    var preview by remember(draft) { mutableStateOf<List<String>?>(null) }

    val modelChoices = remember(settings.providers) {
        buildList {
            add(ModelChoice(id = null, label = ""))
            settings.providers.forEach { provider ->
                provider.models.forEach { model ->
                    add(ModelChoice(id = model.id, label = "${model.displayName} · ${provider.name}"))
                }
            }
        }
    }
    val workspaceChoices = remember(workspaces) {
        buildList {
            add(WorkspaceChoice(id = null, label = ""))
            workspaces.forEach { add(WorkspaceChoice(id = it.id, label = it.name)) }
        }
    }

    val selfIndex = presets.indexOfFirst { it.id == draft.id }.takeIf { it >= 0 }
    val nameOk = isSubagentNameAvailable(edited.name, presets, excludeIndex = selfIndex)
    val editedIndex = presets.indexOfFirst { it.id == edited.id }.takeIf { it >= 0 }

    // 候选工具与"按能力开关"的生效集合都来自同一次真实装配（与 run 侧同源）
    LaunchedEffect(edited.modelId, edited.enableWebSearch, edited.workspaceId) {
        candidates = runCatching { vm.toolCandidates(edited) }.getOrDefault(emptyList())
    }
    LaunchedEffect(edited.modelId, edited.enableWebSearch, edited.workspaceId, edited.allowedTools) {
        preview = runCatching { vm.effectiveTools(edited) }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editedIndex == null) R.string.subagent_preset_new else R.string.subagent_preset_edit
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = edited.name,
                    onValueChange = { edited = edited.copy(name = it.take(SUBAGENT_NAME_MAX_CHARS + 8)) },
                    label = { Text(stringResource(R.string.subagent_preset_name)) },
                    supportingText = {
                        Text(
                            text = if (nameOk) {
                                stringResource(R.string.subagent_preset_name_hint)
                            } else {
                                stringResource(R.string.subagent_preset_name_taken)
                            },
                            color = if (nameOk) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    },
                    isError = !nameOk,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = edited.description,
                    onValueChange = { edited = edited.copy(description = it) },
                    label = { Text(stringResource(R.string.subagent_preset_description)) },
                    supportingText = { Text(stringResource(R.string.subagent_preset_description_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_preset_model),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Select(
                        options = modelChoices,
                        selectedOption = modelChoices.firstOrNull { it.id == edited.modelId }
                            ?: modelChoices.first(),
                        onOptionSelected = { edited = edited.copy(modelId = it.id) },
                        optionToString = { choice ->
                            if (choice.id == null) {
                                stringResource(R.string.subagent_preset_model_follow)
                            } else {
                                choice.label
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = edited.systemPrompt,
                    onValueChange = { edited = edited.copy(systemPrompt = it) },
                    label = { Text(stringResource(R.string.subagent_preset_prompt)) },
                    supportingText = { Text(stringResource(R.string.subagent_preset_prompt_hint)) },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.subagent_preset_web_search),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = edited.enableWebSearch,
                        onCheckedChange = { edited = edited.copy(enableWebSearch = it) },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_preset_workspace),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Select(
                        options = workspaceChoices,
                        selectedOption = workspaceChoices
                            .firstOrNull { it.id == edited.workspaceId?.toString() }
                            ?: workspaceChoices.first(),
                        onOptionSelected = {
                            edited = edited.copy(
                                workspaceId = it.id?.let { id -> Uuid.parse(id) },
                            )
                        },
                        optionToString = { choice ->
                            if (choice.id == null) {
                                stringResource(R.string.subagent_preset_workspace_none)
                            } else {
                                choice.label
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 工具信封：两种模式（"按能力开关" = null / "指定工具" = 显式集合）
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_preset_tools),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = edited.allowedTools == null,
                            onClick = { edited = edited.copy(allowedTools = null) },
                            label = { Text(stringResource(R.string.subagent_preset_tools_auto)) },
                        )
                        FilterChip(
                            selected = edited.allowedTools != null,
                            onClick = {
                                // 切到显式模式时用**当前生效集合**做初值：不让模式切换静默改行为
                                edited = edited.copy(
                                    allowedTools = preview?.toSet() ?: edited.allowedTools ?: emptySet()
                                )
                            },
                            label = { Text(stringResource(R.string.subagent_preset_tools_explicit)) },
                        )
                    }
                }

                if (edited.allowedTools != null) {
                    Text(
                        text = stringResource(R.string.subagent_preset_tools_explicit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { edited = edited.copy(allowedTools = candidates.toSet()) }) {
                            Text(stringResource(R.string.subagent_preset_tools_select_all))
                        }
                        TextButton(onClick = { edited = edited.copy(allowedTools = emptySet()) }) {
                            Text(stringResource(R.string.subagent_preset_tools_clear))
                        }
                    }
                    if (candidates.isEmpty()) {
                        Text(
                            text = stringResource(R.string.subagent_preset_tools_no_candidates),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            candidates.forEach { toolName ->
                                val checked = toolName in edited.allowedTools.orEmpty()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val current = edited.allowedTools.orEmpty()
                                            edited = edited.copy(
                                                allowedTools = if (checked) current - toolName else current + toolName
                                            )
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { on ->
                                            val current = edited.allowedTools.orEmpty()
                                            edited = edited.copy(
                                                allowedTools = if (on) current + toolName else current - toolName
                                            )
                                        },
                                    )
                                    Text(text = toolName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // 生效工具预览（**真实装配 + 信封解析**的结果，不是页面推算）
                Text(
                    text = preview?.let { list ->
                        if (list.isEmpty()) {
                            stringResource(R.string.subagent_preset_tools_pure_reasoning)
                        } else {
                            stringResource(R.string.subagent_preset_tools_effective, list.size) +
                                "\n" + list.joinToString(", ")
                        }
                    } ?: "…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.subagent_preset_approval),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = edited.requireApproval,
                        onCheckedChange = { edited = edited.copy(requireApproval = it) },
                    )
                }

                // 工具级审批（设计 §十一）：这是"解锁写入类子代理"的那一档
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.subagent_preset_tool_approval),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = edited.toolApproval == SubagentToolApproval.DENY,
                            onClick = { edited = edited.copy(toolApproval = SubagentToolApproval.DENY) },
                            label = { Text(stringResource(R.string.subagent_preset_tool_approval_deny)) },
                        )
                        FilterChip(
                            selected = edited.toolApproval == SubagentToolApproval.ASK,
                            onClick = { edited = edited.copy(toolApproval = SubagentToolApproval.ASK) },
                            label = { Text(stringResource(R.string.subagent_preset_tool_approval_ask)) },
                        )
                    }
                    Text(
                        text = stringResource(R.string.subagent_preset_tool_approval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(
                            R.string.subagent_preset_timeout,
                            (edited.timeoutSeconds / 60f).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = edited.timeoutSeconds.toFloat(),
                        onValueChange = { edited = edited.copy(timeoutSeconds = it.roundToInt()) },
                        valueRange = SUBAGENT_TIMEOUT_SECONDS_RANGE.first.toFloat()..
                            SUBAGENT_TIMEOUT_SECONDS_RANGE.last.toFloat(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(edited) },
                enabled = nameOk,
            ) {
                Text(stringResource(R.string.assistant_page_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
