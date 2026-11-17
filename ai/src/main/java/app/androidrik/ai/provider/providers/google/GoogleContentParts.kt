package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.common.http.jsonObjectOrNull
import kotlinx.serialization.json.JsonObject

internal fun JsonObject.googleThoughtMetadata(): JsonObject? =
    stringOrNull("thoughtSignature")?.let { GoogleThoughtMetadata(thoughtSignature = it).toMetadata() }

internal fun JsonObject.googleServerTool(index: Int, fallbackId: () -> String): UIMessagePart.ServerTool? {
    val call = this["toolCall"]?.jsonObjectOrNull
    val result = this["toolResponse"]?.jsonObjectOrNull
    val payload = call ?: result ?: return null
    return UIMessagePart.ServerTool(
        toolCallId = payload.stringOrNull("id") ?: fallbackId(),
        toolName = payload.stringOrNull("toolType").orEmpty(),
        input = call?.get("args"),
        output = result?.get("response"),
        status = if (result != null) ServerToolStatus.COMPLETED else ServerToolStatus.IN_PROGRESS,
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
            call = this.takeIf { call != null },
            callIndex = index.takeIf { call != null },
            result = this.takeIf { result != null },
            resultIndex = index.takeIf { result != null },
        ).toMetadata(),
    )
}

internal fun UIMessagePart.withGoogleSignature(signature: String): UIMessagePart? {
    val existing = metadataAs<GoogleThoughtMetadata>() ?: GoogleThoughtMetadata()
    if (existing.thoughtSignature != null) return null
    val signed = JsonObject(metadata.orEmpty() + existing.copy(thoughtSignature = signature).toMetadata())
    return when (this) {
        is UIMessagePart.Text -> copy(metadata = signed)
        is UIMessagePart.Tool -> copy(metadata = signed)
        is UIMessagePart.Image -> copy(metadata = signed)
        is UIMessagePart.Reasoning -> copy(metadata = signed)
        else -> null
    }
}
