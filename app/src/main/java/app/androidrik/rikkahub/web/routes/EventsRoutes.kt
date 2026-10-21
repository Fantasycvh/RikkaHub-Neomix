package app.androidrik.rikkahub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.web.untilWebPasswordChanges
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.web.dto.ConversationListInvalidateEvent
import app.androidrik.rikkahub.web.dto.FolderListEvent
import app.androidrik.rikkahub.web.dto.toDto
import app.androidrik.rikkahub.web.dto.toWebSettingsDto
import kotlin.time.Duration.Companion.seconds

/**
 * Multiplexed server-sent events stream.
 *
 * A single `/api/events` connection carries several event types, distinguished by the
 * SSE `event:` field, so new event kinds can be added without opening a new connection:
 *  - `settings`                     -> **whitelisted** settings snapshot (`WebSettingsDto`)
 *  - `conversation_list_invalidate` -> the conversation list for an assistant changed
 *  - `folders`                      -> the folder list for an assistant changed
 *
 * The settings payload is deliberately NOT the full `Settings`: that model carries provider
 * API keys, proxy credentials, WebDAV/S3 secrets and assistant persona prompts. Only fields
 * the web client renders are mapped into [app.androidrik.rikkahub.web.dto.WebSettingsDto].
 * Adding a field to that DTO requires a real consumer in `web-ui/` — never forward a business
 * type wholesale, or a future secret field leaks by default.
 *
 * Per-conversation streaming (generation updates) keeps its own dedicated connection
 * at `/api/conversations/{id}/stream`.
 */
fun Route.eventsRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
) {
    sse("/events") {
        val connectedPassword = settingsStore.settingsFlow.value.webServerAccessPassword
        heartbeat {
            period = 15.seconds
        }

        // Settings snapshot; StateFlow emits the current value immediately on connect.
        // 白名单 DTO：不含任何 provider/搜索/MCP 凭据与助手人设正文（见 WebSettingsDto）。
        val settingsEvents = settingsStore.settingsFlow.map { settings ->
            EventPayload(event = "settings", json = JsonInstant.encodeToString(settings.toWebSettingsDto()))
        }

        // Conversation list invalidation, scoped to the currently selected assistant.
        val conversationListEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                combine(
                    conversationRepo.getConversationsOfAssistant(assistantId),
                    chatService.getConversationJobs()
                ) { conversations, generationJobs ->
                    // Key per conversation folds in generation state (so start/stop invalidates the
                    // sidebar even when content isn't persisted) and folderId (so moving a
                    // conversation between folders invalidates other clients' folder views).
                    conversations.map { conversation ->
                        buildString {
                            append(conversation.id)
                            append('|')
                            append(conversation.updateAt.toEpochMilli())
                            append('|')
                            append(generationJobs[conversation.id] != null)
                            append('|')
                            append(conversation.folderId?.toString().orEmpty())
                        }
                    }
                }
                    .distinctUntilChanged()
                    .map { assistantId }
            }
            .map { assistantId ->
                EventPayload(
                    event = "conversation_list_invalidate",
                    json = JsonInstant.encodeToString(
                        ConversationListInvalidateEvent(
                            assistantId = assistantId.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }

        // Folder list for the currently selected assistant (Room flow emits on any change).
        val folderEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                folderRepo.getFoldersOfAssistant(assistantId).map { folders ->
                    EventPayload(
                        event = "folders",
                        json = JsonInstant.encodeToString(
                            FolderListEvent(
                                assistantId = assistantId.toString(),
                                folders = folders.map { it.toDto() }
                            )
                        )
                    )
                }
            }

        merge(settingsEvents, conversationListEvents, folderEvents)
            .untilWebPasswordChanges(
                settingsStore.settingsFlow.map { it.webServerAccessPassword }, connectedPassword,
            ).collect { payload ->
            send(data = payload.json, event = payload.event)
        }
    }
}

private data class EventPayload(
    val event: String,
    val json: String,
)
