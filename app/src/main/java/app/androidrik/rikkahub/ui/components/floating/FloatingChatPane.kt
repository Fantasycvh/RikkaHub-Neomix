package app.androidrik.rikkahub.ui.components.floating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.ui.hooks.readStringPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Shared by the Activity and SYSTEM_ALERT_WINDOW hosts. No navigation/settings composition locals,
 * Activity casts, approval controls, or attachment pickers are used here. Generation belongs to
 * ChatService, never to the composition. Unsent drafts survive selection changes, not pane disposal.
 */
@Composable
fun FloatingChatPane(
    onOpenApp: (String, String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    initialConversationId: String? = null,
    onConversationChanged: (String) -> Unit = {},
) {
    val service: ChatService = koinInject()
    val store: SettingsStore = koinInject()
    val repository: ConversationRepository = koinInject()
    val appScope: AppScope = koinInject()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val settings by store.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val focusCallback by rememberUpdatedState(onInputFocusChanged)
    val conversationCallback by rememberUpdatedState(onConversationChanged)
    var selectedId by remember(initialConversationId) {
        mutableStateOf(floatingChatConversationId(initialConversationId))
    }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val drafts = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(initialConversationId) {
        if (selectedId == null) {
            try {
                val last = floatingChatConversationId(context.readStringPreference("lastConversationId"))
                // A deleted saved ID must not silently become a new conversation.
                val existingLast = last?.takeIf { repository.getConversationById(it) != null }
                val recent = if (existingLast == null) repository.getRecentConversations(
                    assistantId = store.settingsFlow.value.getCurrentAssistant().id,
                    limit = 1,
                ).firstOrNull()?.id else null
                selectedId = floatingChatInitialId(null, existingLast?.toString(), recent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                selectionError = error.message ?: "无法读取最近会话"
                selectedId = Uuid.random()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { focusCallback(false) } }
    val id = selectedId
    if (id == null) {
        Text("正在打开会话…", modifier.padding(12.dp))
        return
    }
    LaunchedEffect(id) { conversationCallback(id.toString()) }

    // getConversationFlow establishes the session before querying its job/status flows.
    val conversationFlow = remember(service, id) { service.getConversationFlow(id) }
    val conversation by conversationFlow.collectAsState()
    val jobFlow = remember(service, id) { service.getGenerationJobStateFlow(id) }
    val job by jobFlow.collectAsState(initial = null)
    val statusFlow = remember(service, id) { service.getProcessingStatusFlow(id) }
    val processing by statusFlow.collectAsState()
    val queueFlow = remember(service, id) { service.getMessageQueueFlow(id) }
    val queue by queueFlow.collectAsState()
    val errors by service.errors.collectAsState()
    var ready by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf<String?>(null) }
    var stopping by remember(id) { mutableStateOf(false) }
    var recent by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var showRecent by remember { mutableStateOf(false) }
    var loadingRecent by remember { mutableStateOf(false) }
    var loadAttempt by remember(id) { mutableStateOf(0) }

    LaunchedEffect(service, id, loadAttempt) {
        ready = false
        failure = null
        holdFloatingChatSession(
            acquire = { service.addConversationReference(id) },
            initialize = { service.initializeConversation(id) },
            onReady = { ready = true },
            onFailure = { failure = it.message ?: "会话初始化失败" },
            release = { service.removeConversationReference(id) },
        )
    }
    val messages = conversation.currentMessages
    val draft = drafts[id.toString()].orEmpty()
    val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
    val configured = assistant != null && settings.findModelById(assistant.chatModelId ?: settings.chatModelId) != null
    val generating = job?.isActive == true
    val pending = messages.floatingChatHasPendingApproval()
    val actions = floatingChatActions(ready, configured, generating, stopping, draft, messages)
    val openApp = { focusManager.clearFocus(); focusCallback(false); onOpenApp(id.toString(), draft) }
    val listState = key(id) { rememberLazyListState() }
    var initiallyScrolled by remember(id) { mutableStateOf(false) }
    // 首次加载定位最新消息；之后仅在原本接近底部时跟随，不抢走正在阅读历史的位置。
    LaunchedEffect(id, ready, messages.size, messages.lastOrNull()?.parts) {
        val nearBottom = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= messages.lastIndex - 2
        if (ready && messages.isNotEmpty() && !listState.isScrollInProgress && (!initiallyScrolled || nearBottom)) {
            listState.scrollToItem(messages.lastIndex)
            initiallyScrolled = true
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = {
                    showRecent = true
                    loadingRecent = true
                    scope.launch {
                        try {
                            recent = repository.getRecentConversations(conversation.assistantId, limit = 10)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            selectionError = error.message ?: "无法读取最近会话"
                        } finally { loadingRecent = false }
                    }
                }) {
                    Text(conversation.title.ifBlank { "最近会话" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showRecent, onDismissRequest = { showRecent = false }) {
                    if (loadingRecent) DropdownMenuItem(text = { Text("加载中…") }, onClick = {}, enabled = false)
                    if (!loadingRecent && recent.isEmpty()) {
                        DropdownMenuItem(text = { Text("暂无最近会话") }, onClick = {}, enabled = false)
                    }
                    recent.forEach { entry ->
                        DropdownMenuItem(text = { Text(entry.title.ifBlank { "未命名会话" }, maxLines = 1) }, onClick = {
                            focusManager.clearFocus()
                            focusCallback(false)
                            showRecent = false
                            selectedId = entry.id
                        })
                    }
                }
            }
            TextButton(onClick = { focusManager.clearFocus(); focusCallback(false); selectedId = Uuid.random() }) { Text("新建") }
            TextButton(onClick = openApp) { Text("主界面") }
        }
        if (!ready && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        failure?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { loadAttempt++ }) { Text("重试加载") }
        }
        selectionError?.let { text ->
            TextButton(onClick = { selectionError = null }) { Text("$text（点击关闭）") }
        }
        if (!configured && ready) {
            TextButton(onClick = openApp) { Text("尚未配置会话模型，请到主界面设置") }
        }
        val error = errors.lastOrNull { floatingChatErrorVisible(it.conversationId, id) }
        error?.let {
            Text(it.title.orEmpty() + " " + (it.error.message ?: "操作失败"),
                color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row {
                TextButton(onClick = { service.dismissError(it.id) }) { Text("关闭错误") }
                TextButton(onClick = openApp) { Text("主界面处理") }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && ready) item { Text("发送消息开始对话", Modifier.padding(8.dp)) }
            items(messages, key = { it.id.toString() }) { message ->
                Surface(
                    color = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(if (message.role == MessageRole.USER) "你" else message.role.name,
                            style = MaterialTheme.typography.labelSmall)
                        SelectionContainer {
                            Text(message.parts.joinToString("\n", transform = ::floatingChatPartText))
                        }
                        if (message.floatingChatHasAttachment()) {
                            TextButton(onClick = openApp) { Text("在主界面查看附件") }
                        }
                    }
                }
            }
        }
        if (queue.messages.isNotEmpty()) {
            TextButton(onClick = openApp) {
                Text("${if (queue.paused) "队列已暂停" else "等待发送"} · ${queue.messages.size} 条 · 主界面管理")
            }
        }
        if (pending) TextButton(onClick = openApp) { Text("工具等待审批 · 到主界面处理（小窗只读）") }
        processing?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        Row {
            TextButton(enabled = actions.continueReply, onClick = { service.continueMessage(id) }) { Text("继续") }
            TextButton(enabled = actions.regenerate, onClick = {
                floatingChatRegenerateTarget(messages)?.let {
                    service.regenerateAtMessage(id, it, scope = ChatService.RegenerateScope.THIS_ONLY)
                }
            }) { Text("重新生成") }
            TextButton(onClick = openApp) { Text("附件") }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { drafts[id.toString()] = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focusCallback(it.isFocused) },
            placeholder = { Text("输入消息") },
            maxLines = 4,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (generating || stopping) TextButton(enabled = actions.stop, onClick = {
                stopping = true
                // UI removal must not cancel stopGeneration before partial content is persisted.
                appScope.launch {
                    try { service.stopGeneration(id) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { service.addError(error, id) }
                    finally { stopping = false }
                }
            }) { Text(if (stopping) "停止中…" else "停止") }
            Button(enabled = actions.send, onClick = {
                service.sendMessage(id, listOf(UIMessagePart.Text(draft)))
                drafts[id.toString()] = ""
            }) { Text(if (generating) "排队发送" else "发送") }
        }
    }
}
