package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.FILE_MEMORY_TOOL
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.ai.memoryFileDescription
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryFileCategory
import app.androidrik.rikkahub.data.repository.MemoryFileRepository
import app.androidrik.rikkahub.data.repository.normalizeMemoryFilePath
import app.androidrik.rikkahub.data.repository.validFileChange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal const val FILE_MEMORY_ORGANIZE_TOOL = "organize_memory_files"
private const val FILE_MEMORY_RESULT_CHARS = 7_500

internal fun buildFileMemoryTool(
    repository: MemoryFileRepository,
    storeId: String,
    assistant: Assistant,
    propose: ((MemoryConsolidationProposal) -> Unit)? = null,
): Tool {
    data class ReadProgress(val revision: String, val end: Int, val complete: Boolean)
    val reads = mutableMapOf<String, ReadProgress>()
    val actions = if (propose == null) {
        listOf("list", "read", "create", "edit", "append", "replace", "delete", "no_candidate")
    } else {
        listOf("list", "read", "create", "edit", "move", "no_candidate")
    }
    val description = if (propose == null) {
        "Manage Markdown memory files. List with an optional offset; read a path in chunks using offset. " +
        "Create a new path with content. Edit replaces the whole file; append adds content; replace changes one exact match. " +
        "Edit, append, replace and delete require the revision from the most recent read. " +
        "Use no_candidate after explicitly assessing that there is nothing worth saving."
    } else {
        "List files using optional offset and read with pagination. Propose create with path and content, edit with path, " +
            "revision and complete revised content, or move with path, revision and destination to preserve its body. " +
            "Read the entire file before editing and obtain its revision before moving. Use no_candidate if no changes are needed."
    }
    return Tool(
        name = if (propose == null) FILE_MEMORY_TOOL else FILE_MEMORY_ORGANIZE_TOOL,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            actions.forEach { add(it) }
                        })
                    })
                    val fields = if (propose == null) listOf("path", "content", "old_text", "revision")
                        else listOf("path", "content", "destination", "revision")
                    for (field in fields) {
                        put(field, buildJsonObject { put("type", "string") })
                    }
                    put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            if (propose != null) false else when ((input as? JsonObject)?.string("action")) {
                "list", "read", "no_candidate" -> false
                "create" -> assistant.memoryCreateApproval
                else -> assistant.memoryModifyApproval
            }
        },
        execute = { input ->
            val args = input.jsonObject
            val action = args.required("action")
            require(action in actions) { "Unknown memory file action." }
            if (propose != null && action in setOf("create", "edit", "move")) {
                val path = normalizeMemoryFilePath(args.required("path"))
                val revision = if (action == "create") null else args.required("revision")
                if (action == "create") {
                    check(repository.read(storeId, path) == null) { "The destination already exists." }
                } else {
                    val read = reads[path]
                    check(read != null && read.revision == revision && (action == "move" || read.complete)) {
                        "Read the file and use its observed revision first. Edits require all pages."
                    }
                }
                val destination = if (action == "move") normalizeMemoryFilePath(args.required("destination")) else null
                if (destination != null) check(repository.read(storeId, destination) == null) { "The destination already exists." }
                val proposal = MemoryConsolidationProposal(
                    action = action,
                    targetId = path,
                    content = if (action == "move") "" else args.required("content"),
                    expectedFileRevision = revision,
                    fileDestination = destination,
                )
                require(proposal.validFileChange()) { "Invalid proposed memory file change." }
                propose(proposal)
                return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("proposed", true); put("path", path) }.toString()))
            }
            val result = when (action) {
                "no_candidate" -> buildJsonObject { put("memory_sync", "no_candidate") }
                "list" -> {
                    val offset = args.offset()
                    val page = repository.list(storeId).drop(offset).take(21)
                    val entries = mutableListOf<JsonObject>()
                    var chars = 100
                    for (file in page.take(20)) {
                        val entry = buildJsonObject {
                            put("path", file.path)
                            put("category", MemoryFileCategory.of(file.path).name.lowercase())
                            put("description", memoryFileDescription(file))
                        }
                        val cost = entry.toString().length + 1
                        if (chars + cost > FILE_MEMORY_RESULT_CHARS) break
                        chars += cost
                        entries += entry
                    }
                    buildJsonObject {
                        put("files", buildJsonArray { entries.forEach { add(it) } })
                        if (page.size > entries.size) put("next_offset", offset + entries.size)
                    }
                }
                "read" -> {
                    val file = repository.read(storeId, args.required("path")) ?: error("Memory file not found.")
                    var offset = args.offset().coerceAtMost(file.content.length)
                    if (offset in 1 until file.content.length && file.content[offset].isLowSurrogate()) offset--
                    fun pageEnd(limit: Int): Int {
                        val end = (offset + limit).coerceAtMost(file.content.length)
                        return if (end < file.content.length && file.content[end].isLowSurrogate()) end - 1 else end
                    }
                    fun render(end: Int) = buildJsonObject {
                        put("path", file.path)
                        put("revision", file.revision)
                        put("content", file.content.substring(offset, end))
                        if (end < file.content.length) put("next_offset", end)
                    }
                    var limit = 4_000
                    var end = pageEnd(limit)
                    var rendered = render(end)
                    while (rendered.toString().length > FILE_MEMORY_RESULT_CHARS) {
                        limit /= 2
                        end = pageEnd(limit)
                        rendered = render(end)
                    }
                    if (propose != null) {
                        val previous = reads[file.path]?.takeIf { it.revision == file.revision }
                        if (offset == 0 || (previous != null && offset <= previous.end)) {
                            val observedEnd = maxOf(end, previous?.end ?: 0)
                            reads[file.path] = ReadProgress(file.revision, observedEnd, observedEnd == file.content.length)
                        }
                    }
                    rendered
                }
                "delete" -> {
                    repository.delete(storeId, args.required("path"), args.required("revision"))
                    buildJsonObject {
                        put("path", args.required("path"))
                        put("success", true)
                        put("memory_sync", "saved")
                    }
                }
                "create", "edit", "append", "replace" -> {
                    val expected = if (action == "create") null else args.required("revision")
                    val content = args.required("content")
                    val saved = repository.write(storeId, args.required("path"), expected) { old ->
                        val updated = when (action) {
                            "append" -> old + content
                            "replace" -> {
                                val text = args.required("old_text")
                                require(text.isNotEmpty() && old.indexOf(text) >= 0 && old.indexOf(text) == old.lastIndexOf(text)) {
                                    "old_text must match exactly once."
                                }
                                old.replaceFirst(text, content)
                            }
                            else -> content
                        }
                        findForbiddenMemoryContent(updated)?.let { error("Memory content contains ${it.category}.") }
                        updated
                    }
                    buildJsonObject {
                        put("path", saved.path)
                        put("revision", saved.revision)
                        put("success", true)
                        put("memory_sync", "saved")
                    }
                }
                else -> error("Unknown memory file action.")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    )
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.required(key: String): String = string(key) ?: error("$key is required.")

private fun JsonObject.offset(): Int = ((get("offset") as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
