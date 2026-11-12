package app.androidrik.rikkahub.service

import android.util.Log
import androidx.room.withTransaction
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import app.androidrik.workspace.WorkspaceTaskTerminal
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.model.mergeGenerationMessages
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.task.externalNoticeMatches
import app.androidrik.rikkahub.data.task.externalNoticeMessage
import app.androidrik.rikkahub.data.task.toExternalNotice
import app.androidrik.rikkahub.utils.estimateTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Durable event input alongside, rather than replacing, the editable user queue. */
internal class WorkspaceNoticeInbox(
    private val dao: app.androidrik.rikkahub.data.db.dao.ExternalNoticeDAO,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
    private val exists: suspend (Uuid) -> Boolean,
    private val persist: suspend (app.androidrik.rikkahub.data.model.Conversation) -> Unit,
    private val terminals: Flow<List<WorkspaceTaskTerminal>>,
    private val acknowledgeTerminal: (String) -> Unit,
    private val settings: StateFlow<Settings>,
    private val loadSession: suspend (Uuid) -> ConversationSession?,
    private val remainingTokens: (ConversationSession) -> Int,
    private val startContinuation: (ConversationSession, String, List<String>) -> Boolean,
    private val agentTerminals: Flow<List<app.androidrik.rikkahub.data.db.entity.AgentRunEntity>> = flowOf(emptyList()),
    private val acknowledgeAgents: suspend (List<String>, Boolean) -> Unit = { _, _ -> },
) {
    constructor(
        database: AppDatabase,
        terminals: Flow<List<WorkspaceTaskTerminal>>,
        acknowledgeTerminal: (String) -> Unit,
        conversations: ConversationRepository,
        settings: StateFlow<Settings>,
        loadSession: suspend (Uuid) -> ConversationSession?,
        remainingTokens: (ConversationSession) -> Int,
        startContinuation: (ConversationSession, String, List<String>) -> Boolean,
    ) : this(database.externalNoticeDao(), { block -> database.withTransaction { block() } },
        conversations::existsConversationById, conversations::updateConversation,
        terminals, acknowledgeTerminal, settings, loadSession, remainingTokens, startContinuation,
        database.agentRunDao().observePendingDeliveries().map { rows -> rows.filter {
            it.deliveryMode == app.androidrik.rikkahub.data.db.entity.AgentRunEntity.DELIVERY_MODE_RESUME
        } }, { ids, orphaned ->
            if (orphaned) database.agentRunDao().markOrphaned(ids)
            else database.agentRunDao().markDelivered(ids, System.currentTimeMillis())
        })
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val cancelledScopes = ConcurrentHashMap.newKeySet<String>()

    fun request() { requests.trySend(Unit) }

    /** Synchronous fence before cancellation starts publishing terminal events. */
    fun suppress(scope: String) { cancelledScopes.add(scope); request() }

    suspend fun suppressAndPersist(scope: String) {
        suppress(scope)
        try { withContext(NonCancellable) { transaction {
            // A resolved control record also suppresses completions that do not exist yet, after restart.
            dao.insert(ExternalNoticeEntity(
                java.util.UUID.nameUUIDFromBytes("cancel:$scope".toByteArray(Charsets.UTF_8)).toString(),
                "", "", "", "", scope, scope, "cancelled", "", System.currentTimeMillis(), false,
                deliveryState = "delivered", wakeState = "suppressed", sourceType = "cancel_scope"))
            dao.suppressScope(scope)
        } } }
        catch (e: Exception) { Log.e("WorkspaceInbox", "Suppression retained in memory for retry", e) }
    }

    fun start(scope: CoroutineScope) = scope.launch {
        dao.pruneResolved(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        dao.recoverInterrupted() // Never replay a possibly side-effecting interrupted continuation.
        launch {
            terminals.collect { terminals ->
                for (terminal in terminals) {
                    // The registry retains the terminal snapshot until this durable handoff succeeds.
                    while (true) {
                        try {
                            val notice = terminal.toExternalNotice()
                            transaction {
                                check(dao.get(notice.id) != null || dao.pendingCount() < 2048) { "Durable task inbox is full; result retained in registry" }
                                dao.insert(notice)
                                if (notice.rootScope in cancelledScopes || dao.isScopeSuppressed(notice.rootScope)) dao.suppressScope(notice.rootScope)
                            }
                            acknowledgeTerminal(terminal.id)
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.e("WorkspaceInbox", "Saving task result failed; retained for retry", e); delay(5_000) }
                    }
                }
            }
        }
        launch { dao.observePending().collect { request() } }
        launch {
            agentTerminals.collect { runs ->
                for (run in runs) {
                    while (true) {
                        try {
                            val notice = run.toExternalNotice() ?: error("Resumable run has no captured destination: ${run.id}")
                            transaction {
                                check(dao.get(notice.id) != null || dao.pendingCount() < 2048) { "Durable task inbox is full; agent result retained" }
                                dao.insert(notice)
                                if (notice.rootScope in cancelledScopes || dao.isScopeSuppressed(notice.rootScope)) dao.suppressScope(notice.rootScope)
                            }
                            break
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.e("TaskInbox", "Agent completion retained for retry", e); delay(5_000) }
                    }
                }
            }
        }
        launch { settings.collect { request() } }
        for (ignored in requests) {
            try {
                mutex.withLock {
                    cancelledScopes.forEach { dao.suppressScope(it) }
                    val rows = dao.observePending().first()
                    for ((parent, notices) in rows.groupBy { it.conversationId }) {
                        val id = runCatching { Uuid.parse(parent) }.getOrNull()
                        val session = id?.let { loadSession(it) }
                        if (session == null) {
                            transaction {
                                dao.delivered(notices.map { it.id }, "orphaned")
                                dao.wake(notices.map { it.id }, "suppressed")
                                acknowledgeAgents(notices.filter { it.sourceType == "subagent" }.map { it.sourceId }, true)
                            }
                        } else session.withRefSuspend { process(session, notices, beforeUser = false) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("WorkspaceInbox", "Delivery failed; retained for retry", e)
                delay(5_000)
                request()
            }
        }
    }

    /** Called inside the reserved user job, before reading the conversation snapshot. */
    suspend fun beforeUser(session: ConversationSession) = mutex.withLock {
        val rows = dao.observePending().first().filter { it.conversationId == session.id.toString() }
        process(session, rows, beforeUser = true)
    }

    /** Called by the active loop with its authoritative full message list at a completed step boundary. */
    suspend fun atStep(session: ConversationSession, live: List<app.androidrik.ai.ui.UIMessage>): List<app.androidrik.ai.ui.UIMessage> = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (session.messageQueue.state.value.paused || !settings.value.workspaceTaskNoticeEnabled ||
                live.any { m -> m.parts.any { it is UIMessagePart.Tool && (it.isPending || it.canResumeExecution) } }) return@withContext live
            val rows = dao.pendingForConversation(session.id.toString())
            if (rows.isEmpty()) return@withContext live
            val original = session.state.value
            val liveIds = live.map { it.id }.toSet()
            if (original.currentMessages.any { !it.isExternalNotice && it.id !in liveIds }) return@withContext live
            val staged = original.mergeGenerationMessages(live)
            session.state.value = staged
            try {
                process(session, rows, beforeUser = true)
                session.state.value.currentMessages
            } catch (e: Throwable) {
                if (session.state.value === staged) session.state.value = original
                throw e
            }
        }
    }

    private suspend fun process(session: ConversationSession, rows: List<ExternalNoticeEntity>, beforeUser: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            if (rows.isEmpty() || settings.value.init) return@withContext
            if (session.taskHandoffInProgress) return@withContext
            val original = session.state.value
            val assistant = settings.value.getAssistantById(original.assistantId)
            val selected = original.currentMessages.map { it.id.toString() }.toSet()
            val sourceEvents = app.androidrik.rikkahub.data.task.selectedTaskCompletionEvents(original.currentMessages)
            val matching = rows.filter {
                externalNoticeMatches(it, original.assistantId.toString(), assistant?.workspaceId?.toString(), selected) && it.id in sourceEvents &&
                    (!beforeUser ||
                        it.rootScope == (session.externalNoticeRootScope ?: session.backgroundTaskOwner))
            }
            // Resume one original task at a time; other roots keep their own unconsumed completion.
            val applicable = if (beforeUser) matching else matching.filter { it.rootScope == matching.firstOrNull()?.rootScope }
            // Branch/assistant changes retain the original destination; never move a result elsewhere.
            if (applicable.isEmpty()) return@withContext
            val suppressedRoots = applicable.map { it.rootScope }.distinct().filter { dao.isScopeSuppressed(it) }.toSet()
            if (!beforeUser && (session.getJob() != null || session.processingStatus.value != null ||
                        session.messageQueue.state.value.messages.isNotEmpty() ||
                        original.currentMessages.any { m -> m.parts.any { it is UIMessagePart.Tool && it.isPending } })) return@withContext

            val pending = applicable.filter { it.deliveryState == "pending" }
            var chars = 0
            val batch = pending.take(8).takeWhile { chars += it.body.length; chars <= 8_000 }
            val unseen = batch.filter { it.id !in selected }
            val messages = unseen.map { row -> externalNoticeMessage(row).let { message ->
                message.copy(parts = listOf(UIMessagePart.Notice(row.id, (message.parts.single() as UIMessagePart.Text).text)))
            } }
            if (messages.isNotEmpty() && estimateTokenCount(messages.map { message -> message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Notice) UIMessagePart.Text(part.text) else part
            }) }) + 4096 >= remainingTokens(session)) return@withContext
            val updated = original.copy(
                messageNodes = original.messageNodes + messages.map { app.androidrik.rikkahub.data.model.MessageNode(messages = listOf(it)) },
                chatSuggestions = if (messages.isEmpty()) original.chatSuggestions else emptyList(),
            )
            session.state.value = updated
            try {
                withContext(NonCancellable) {
                    transaction {
                        check(exists(session.id)) { "Notice target was deleted" }
                        if (batch.isNotEmpty()) persist(updated)
                        dao.delivered(batch.map { it.id })
                        acknowledgeAgents(batch.filter { it.sourceType == "subagent" }.map { it.sourceId }, false)
                    }
                }
            } catch (e: Throwable) {
                if (session.state.value === updated) session.state.value = original
                throw e
            }
            val delivered = applicable.filter { it.deliveryState == "delivered" || it in batch }
            val wakeable = delivered.filter { it.wakeState == "pending" }
            if (wakeable.isEmpty()) return@withContext
            val suppressed = wakeable.filter { beforeUser || !it.allowWake || it.rootScope in cancelledScopes || it.rootScope in suppressedRoots ||
                !settings.value.workspaceTaskNoticeEnabled }
            dao.wake(suppressed.map { it.id }, "suppressed")
            val remaining = wakeable - suppressed.toSet()
            if (remaining.isEmpty() || session.messageQueue.state.value.paused ||
                session.messageQueue.state.value.messages.isNotEmpty()) return@withContext
            val root = remaining.first().rootScope
            val group = remaining.filter { it.rootScope == root }
            if (dao.wakeCount(root) >= 8) {
                dao.wake(group.map { it.id }, "suppressed")
                return@withContext
            }
            val ids = group.map { it.id }
            dao.wake(ids, "claimed")
            // DB suspension may have allowed a user send/stop. The callback rechecks under the session lock.
            val current = session.state.value
            val currentWorkspace = settings.value.getAssistantById(current.assistantId)?.workspaceId?.toString()
            val currentIds = current.currentMessages.map { it.id.toString() }.toSet()
            val currentEvents = app.androidrik.rikkahub.data.task.selectedTaskCompletionEvents(current.currentMessages)
            if (group.any { !externalNoticeMatches(it, current.assistantId.toString(), currentWorkspace, currentIds) || it.id !in currentEvents })
                dao.wake(ids, "pending")
            else if (root in cancelledScopes || dao.isScopeSuppressed(root) || !settings.value.workspaceTaskNoticeEnabled) dao.wake(ids, "suppressed")
            else if (!startContinuation(session, root, ids)) dao.wake(ids, "pending")
        }
}
