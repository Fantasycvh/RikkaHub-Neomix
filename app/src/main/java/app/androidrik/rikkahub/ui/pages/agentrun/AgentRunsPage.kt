package app.androidrik.rikkahub.ui.pages.agentrun

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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Rocket01
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val RunTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(ts: Long?): String =
    ts?.let {
        RunTimeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "—"

/** 把 `error` 字段的机器前缀映射成本地化文案（前缀不含冒号，按首个 ':' 切）。 */
@Composable
fun errorLabel(error: String?): String? {
    if (error.isNullOrBlank()) return null
    val kind = error.substringBefore(':')
    val detail = error.substringAfter(':', "").trim()
    val res = when (kind) {
        AgentRunEntity.ERROR_NO_MODEL -> R.string.agent_run_error_no_model
        AgentRunEntity.ERROR_LOOP_DETECTED -> R.string.agent_run_error_loop
        AgentRunEntity.ERROR_BUDGET_EXHAUSTED -> R.string.agent_run_error_budget
        AgentRunEntity.ERROR_CONTEXT_BUDGET -> R.string.agent_run_error_context_budget
        AgentRunEntity.ERROR_PERMISSION_DENIED -> R.string.agent_run_error_denied
        AgentRunEntity.ERROR_MODEL_GAVE_UP -> R.string.agent_run_error_gave_up
        else -> R.string.agent_run_error_exception
    }
    val label = stringResource(res)
    return if (detail.isBlank()) label else "$label ($detail)"
}

@Composable
fun statusLabel(status: String): String = stringResource(
    when (status) {
        AgentRunEntity.STATUS_PENDING -> R.string.agent_run_status_pending
        AgentRunEntity.STATUS_RUNNING -> R.string.agent_run_status_running
        AgentRunEntity.STATUS_AWAITING_APPROVAL -> R.string.agent_run_status_awaiting_approval
        AgentRunEntity.STATUS_COMPLETED -> R.string.agent_run_status_completed
        AgentRunEntity.STATUS_FAILED -> R.string.agent_run_status_failed
        else -> R.string.agent_run_status_cancelled
    }
)

@Composable
fun AgentRunsPage(vm: AgentRunsVM = koinViewModel()) {
    val navController = LocalNavController.current
    val runs by vm.runs.collectAsStateWithLifecycle()
    val assistants by vm.assistants.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showDispatch by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.agent_runs_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!vm.canStartRun()) {
                        toaster.show(
                            context.getString(R.string.agent_run_dispatch_busy, vm.activeRunCount())
                        )
                    } else {
                        showDispatch = true
                    }
                },
            ) {
                Icon(HugeIcons.Rocket01, contentDescription = stringResource(R.string.agent_run_dispatch_title))
            }
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (runs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.agent_runs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(runs, key = { it.id }) { run ->
                    RunCard(
                        run = run,
                        onClick = {
                            navController.navigate(Screen.AgentRunDetail(run.id))
                        },
                    )
                }
            }
        }
    }

    if (showDispatch) {
        DispatchDialog(
            assistants = assistants,
            catalogProvider = vm::toolCatalog,
            onDismiss = { showDispatch = false },
            onDispatch = { profile, goal, tools ->
                val id = vm.dispatch(profile, goal, tools)
                if (id == null) {
                    // 必须带活跃数：无参调用不做格式化，会把 `%1$d` 字面显示给用户
                    toaster.show(
                        context.getString(R.string.agent_run_dispatch_busy, vm.activeRunCount())
                    )
                } else {
                    showDispatch = false
                }
            },
        )
    }
}

@Composable
private fun RunCard(run: AgentRunEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
                Text(
                    text = run.profileName.ifBlank { stringResource(R.string.prompt_page_unnamed) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = statusLabel(run.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = when (run.status) {
                        // 等待批准用 primary：它需要用户动作，视觉上要和"后台在跑"区分开
                        AgentRunEntity.STATUS_RUNNING,
                        AgentRunEntity.STATUS_AWAITING_APPROVAL,
                            -> MaterialTheme.colorScheme.primary
                        AgentRunEntity.STATUS_COMPLETED -> MaterialTheme.colorScheme.tertiary
                        AgentRunEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                text = run.goal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            errorLabel(run.error)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    run.modelName,
                    // 发起方（账本列）：对话内工具派发 vs 本页手动派发——排查"这个 run 哪来的"第一眼依据
                    if (run.entryPoint == AgentRunEntity.ENTRY_POINT_CHAT) {
                        stringResource(R.string.agent_run_entry_chat)
                    } else {
                        null
                    },
                    formatTime(run.createdAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DispatchDialog(
    assistants: List<Assistant>,
    catalogProvider: suspend (Assistant) -> List<String>,
    onDismiss: () -> Unit,
    onDispatch: (Assistant, String, Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(assistants.firstOrNull()) }
    var goal by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<String>?>(null) }
    var allowed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // 信封候选 = 该 profile 实际会装配的工具（与 run 侧同口径，含禁用记忆）
    LaunchedEffect(selected) {
        val profile = selected ?: return@LaunchedEffect
        busy = true
        val tools = runCatching { catalogProvider(profile) }.getOrDefault(emptyList())
        catalog = tools
        allowed = tools.toSet()
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.agent_run_dispatch_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // profile 选择（复用共享 Select 组件）
                Text(
                    text = stringResource(R.string.agent_run_dispatch_profile),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Select(
                    options = assistants,
                    // 显式给 T=Assistant：selected 的空值用占位 Assistant 表达，
                    // 避免 T 被推断为 Assistant? 连累 optionToString 的接收者
                    selectedOption = selected ?: Assistant(id = kotlin.uuid.Uuid.random(), name = "…"),
                    onOptionSelected = { selected = it },
                    optionToString = { it.name.ifBlank { "" } },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text(stringResource(R.string.agent_run_dispatch_goal)) },
                    placeholder = { Text(stringResource(R.string.agent_run_dispatch_goal_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 工具信封
                Text(
                    text = stringResource(R.string.agent_run_dispatch_tools),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    text = stringResource(R.string.agent_run_dispatch_tools_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    selected == null || busy -> {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    }

                    catalog.isNullOrEmpty() -> {
                        Text(
                            text = stringResource(R.string.agent_run_dispatch_no_tools),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            catalog?.forEach { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            allowed = if (name in allowed) allowed - name else allowed + name
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = name in allowed,
                                        onCheckedChange = {
                                            allowed = if (it) allowed + name else allowed - name
                                        },
                                    )
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val profile = selected ?: return@TextButton
                    onDispatch(profile, goal.trim(), allowed)
                },
                enabled = selected != null && goal.isNotBlank() && !allowed.isEmpty(),
            ) {
                Text(stringResource(R.string.agent_run_dispatch_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
