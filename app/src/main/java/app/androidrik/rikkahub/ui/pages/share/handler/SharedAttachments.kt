package app.androidrik.rikkahub.ui.pages.share.handler

import android.content.Intent
import android.net.Uri
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.ui.navigation.isAllowedFileType
import kotlinx.coroutines.CancellationException

/** Stream extras and ClipData may overlap; only content URIs carry Android read grants. */
@Suppress("DEPRECATION")
internal fun Intent.readSharedUris(): List<String> {
    val stream = runCatching { extras?.get(Intent.EXTRA_STREAM) }.getOrNull()
    val candidates = buildList {
        when (stream) {
            is Uri -> add(stream.toString())
            is String -> add(stream) // Camera shortcut compatibility.
            is ArrayList<*> -> stream.forEach { if (it is Uri) add(it.toString()) }
        }
        clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let { add(it.toString()) } }
        }
    }
    return candidates.filter { Uri.parse(it).scheme == "content" }.distinct()
}

internal data class SharedFileInfo(val name: String, val mime: String)
internal data class SharedFileFailure(val name: String, val unsupported: Boolean)
internal data class SharedImportResult(
    val parts: List<UIMessagePart>,
    val failures: List<SharedFileFailure>,
)

/** Each source keeps its own metadata even if an earlier copy fails. */
internal suspend fun importSharedAttachments(
    sources: List<String>,
    describe: suspend (String) -> SharedFileInfo,
    copy: suspend (String) -> String,
): SharedImportResult {
    val parts = mutableListOf<UIMessagePart>()
    val failures = mutableListOf<SharedFileFailure>()
    for (source in sources.distinct()) {
        var name = "file"
        try {
            val info = describe(source)
            name = info.name
            val media = info.mime.startsWith("image/") || info.mime.startsWith("audio/") || info.mime.startsWith("video/")
            if (!media && !isAllowedFileType(info.name, info.mime)) {
                failures += SharedFileFailure(name, unsupported = true)
                continue
            }
            val local = copy(source)
            parts += when {
                info.mime.startsWith("image/") -> UIMessagePart.Image(local)
                info.mime.startsWith("audio/") -> UIMessagePart.Audio(local)
                info.mime.startsWith("video/") -> UIMessagePart.Video(local)
                else -> UIMessagePart.Document(url = local, fileName = info.name, mime = info.mime)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failures += SharedFileFailure(name, unsupported = false)
        }
    }
    return SharedImportResult(parts, failures)
}
