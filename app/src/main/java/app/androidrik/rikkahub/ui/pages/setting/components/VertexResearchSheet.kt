package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.VertexResearchRepository
import app.androidrik.rikkahub.data.ai.VertexResearchTask
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.utils.writeClipboardText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VertexResearchSheet(provider: ProviderSetting.Google, onDismiss: () -> Unit) {
    val repository = koinInject<VertexResearchRepository>()
    val appScope = koinInject<AppScope>()
    val tasks by remember(repository, provider) { repository.observe(provider) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val prompt = rememberTextFieldState()
    val active = tasks.firstOrNull { !it.interaction.isTerminal }
    var busy by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var pollError by remember { mutableStateOf<String?>(null) }
    var expandedReport by rememberSaveable { mutableStateOf<String?>(null) }

    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        appScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = e.message ?: "Deep Research request failed."
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(provider, active?.interaction?.id) {
        pollError = null
        val id = active?.interaction?.id ?: return@LaunchedEffect
        while (true) {
            try {
                repository.refresh(provider, id)
                pollError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pollError = e.message ?: "Could not refresh task status."
            }
            delay(if (pollError == null) 8_000 else 20_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gemini Deep Research", style = MaterialTheme.typography.titleLarge)
            Text("Google stores the task and runs it in the background. Reopen this panel to check progress or read the report.")
            OutlinedTextField(
                state = prompt,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Research question") },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
            )
            Button(
                enabled = active == null && !busy && prompt.text.isNotBlank(),
                onClick = {
                    val input = prompt.text.toString().trim()
                    runAction {
                        repository.start(provider, input)
                        if (prompt.text.toString().trim() == input) prompt.clearText()
                    }
                },
            ) { Text("Start research") }
            listOfNotNull(actionError, pollError).distinct().forEach { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            tasks.forEach { task ->
                val id = task.interaction.id
                HorizontalDivider()
                VertexResearchTaskItem(
                    task = task,
                    busy = busy,
                    expanded = expandedReport == id,
                    onExpand = { expandedReport = if (expandedReport == id) null else id },
                    onCancel = { runAction { repository.cancel(provider, id) } },
                    onRemove = { runAction { repository.remove(provider, id) } },
                )
            }
        }
    }
}

@Composable
private fun VertexResearchTaskItem(
    task: VertexResearchTask,
    busy: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val result = task.interaction
    Text(task.prompt, style = MaterialTheme.typography.titleMedium)
    Text(when (result.status) {
        "in_progress" -> "Researching"
        "queued", "pending" -> "Queued"
        "completed" -> "Completed"
        "cancelled" -> "Cancelled"
        "failed" -> "Failed"
        "incomplete" -> "Incomplete"
        "requires_action" -> "Waiting for input"
        "expired" -> "Expired on Vertex"
        else -> result.status
    })
    if (!result.isTerminal) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        result.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel research") }
    }
    result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (result.isTerminal) {
        Row {
            if (result.report != null) {
                TextButton(onClick = onExpand) { Text(if (expanded) "Hide report" else "View report") }
            }
            TextButton(onClick = onRemove, enabled = !busy) { Text("Remove local record") }
        }
    }
    val report = result.report
    if (expanded && report != null) {
        TextButton(onClick = {
            val sources = result.citations.joinToString("\n") { "${it.title}: ${it.url}" }
            context.writeClipboardText(if (sources.isEmpty()) report else "$report\n\n$sources")
        }) { Text("Copy report") }
        SelectionContainer { MarkdownBlock(content = report, documentKey = result.id) }
        result.citations.forEach { citation ->
            if (citation.url.startsWith("https://") || citation.url.startsWith("http://")) {
                TextButton(onClick = { uriHandler.openUri(citation.url) }) { Text(citation.title) }
            }
        }
    }
}
