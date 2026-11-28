package app.androidrik.rikkahub.data.export

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.IMAGE_TOOL_NAME
import kotlinx.serialization.json.*
import java.io.File

private fun imageFileUrl(path: String): String? = when {
    path.startsWith("file://") -> path
    path.startsWith("/") || File(path).isAbsolute -> "file://$path"
    else -> null
}

private fun imageResult(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

/** Generated images stay text-only in model context, but their local files still travel with exports. */
internal fun UIMessagePart.Tool.generatedImageFileUrls(): List<String> {
    if (toolName != IMAGE_TOOL_NAME) return emptyList()
    return output.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
        (imageResult(part.text)?.get("images") as? JsonArray).orEmpty().mapNotNull { image ->
            ((image as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull?.let(::imageFileUrl)
        }
    }
}

internal fun UIMessagePart.Tool.rewriteGeneratedImageFiles(mapping: Map<String, String>): UIMessagePart.Tool {
    if (toolName != IMAGE_TOOL_NAME) return this
    return copy(output = output.map { part ->
        if (part !is UIMessagePart.Text) return@map part
        val content = imageResult(part.text) ?: return@map part
        val images = content["images"] as? JsonArray ?: return@map part
        val rewritten = JsonArray(images.map images@{ image ->
            val obj = image as? JsonObject ?: return@images image
            val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: return@images image
            val replacement = imageFileUrl(path)?.let(mapping::get) ?: return@images image
            JsonObject(obj + ("path" to JsonPrimitive(replacement)))
        })
        if (rewritten == images) part else part.copy(text = JsonObject(content + ("images" to rewritten)).toString())
    })
}
