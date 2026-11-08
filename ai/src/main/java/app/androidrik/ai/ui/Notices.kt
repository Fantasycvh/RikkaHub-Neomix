package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlin.uuid.Uuid

/** Expand only at the request boundary. Original persisted messages and tool/result batches stay intact. */
fun List<UIMessage>.expandNotices(): List<UIMessage> = flatMap { message ->
    if (message.parts.none { it is UIMessagePart.Notice }) listOf(message) else buildList {
        val buffer = mutableListOf<UIMessagePart>()
        var segment = 0
        fun id(key: String) = Uuid.parse(java.util.UUID.nameUUIDFromBytes("${message.id}:$key".toByteArray(Charsets.UTF_8)).toString())
        fun flush() {
            if (buffer.isNotEmpty()) {
                add(message.copy(id = if (segment == 0) message.id else id("segment:$segment"), parts = buffer.toList()))
                segment++
                buffer.clear()
            }
        }
        message.parts.forEach { part ->
            if (part is UIMessagePart.Notice) {
                flush()
                add(message.copy(id = id("notice:${part.eventId}"), role = MessageRole.SYSTEM, isSynthetic = true,
                    parts = listOf(UIMessagePart.Text(part.text)),
                    annotations = message.annotations + UIMessageAnnotation.ExternalNotice(listOf(part.eventId), "runtime", emptyList())))
                segment++
            } else buffer.add(part)
        }
        flush()
    }
}
