package app.androidrik.rikkahub.web

import android.content.Context
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.web.dto.ErrorResponse
import app.androidrik.rikkahub.web.routes.aiIconRoutes
import app.androidrik.rikkahub.web.routes.assetsRoutes
import app.androidrik.rikkahub.web.routes.conversationRoutes
import app.androidrik.rikkahub.web.routes.eventsRoutes
import app.androidrik.rikkahub.web.routes.filesRoutes
import app.androidrik.rikkahub.web.routes.folderRoutes
import app.androidrik.rikkahub.web.routes.settingsRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/** Configure the Web API. All data routes require authentication, including legacy installations. */
fun Application.configureWebApi(
    context: Context,
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    filesManager: FilesManager,
) {
    val session = WebAuthSession { settingsStore.settingsFlow.value.webServerAccessPassword }

    install(ContentNegotiation) {
        json(JsonInstant)
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, ErrorResponse(cause.message, cause.status.value))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            // Do not expose local paths, provider URLs, or credentials from internal exceptions.
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error", 500))
        }
    }

    // Always install auth: a persisted false toggle must never leave a legacy server unprotected.
    configureWebAuthentication(session)
    routing {
        route("/api") {
            webAuthRoutes(session)
            authenticate(WEB_AUTH_PROVIDER) {
                aiIconRoutes(context)
                conversationRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                folderRoutes(chatService, folderRepo, settingsStore)
                eventsRoutes(chatService, conversationRepo, folderRepo, settingsStore)
                settingsRoutes(settingsStore)
                filesRoutes(filesManager, context)
                assetsRoutes(context)
            }
        }
    }
}
