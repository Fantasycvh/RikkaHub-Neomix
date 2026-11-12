package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.FILE_MEMORY_TOOL
import app.androidrik.rikkahub.data.ai.memoryFileDescription
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.repository.MemoryFileRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal fun buildFileMemoryTool(repository: MemoryFileRepository, storeId: String, assistant: Assistant): Tool = Tool(
    name = FILE_MEMORY_TOOL,
    description = "Manage Markdown memory files. List with an optional offset; read a path in chunks using offset. " +
        "Create a new path with content. Edit replaces the whole file; append adds content; replace changes one exact match. " +
        "Edit, append, replace and delete require the revision from the most recent read. " +
        "Use no_candidate after explicitly assessing that there is nothing worth saving.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        listOf("list", "read", "create", "edit", "append", "replace", "delete", "no_candidate").forEach { add(it) }
                    })
                })
                for (field in listOf("path", "content", "old_text", "revision")) {
                    put(field, buildJsonObject { put("type", "string") })
                }
                put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { input ->
        when ((input as? JsonObject)?.string("action")) {
            "list", "read", "no_candidate" -> false
            "create" -> assistant.memoryCreateApproval
            else -> assistant.memoryModifyApproval
        }
    },
    execute = { input ->
        val args = input.jsonObject
        val action = args.required("action")
        val result = when (action) {
            "no_candidate" -> buildJsonObject { put("memory_sync", "no_candidate") }
            "list" -> {
                val offset = args.offset()
                val page = repository.list(storeId).drop(offset).take(21)
                buildJsonObject {
                    put("files", buildJsonArray {
                        page.take(20).forEach { file ->
                            add(buildJsonObject {
                                put("path", file.path)
                                put("description", memoryFileDescription(file))
                            })
                        }
                    })
                    if (page.size > 20) put("next_offset", offset + 20)
                }
            }
            "read" -> {
                val file = repository.read(storeId, args.required("path")) ?: error("Memory file not found.")
                var offset = args.offset().coerceAtMost(file.content.length)
                if (offset in 1 until file.content.length && file.content[offset].isLowSurrogate()) offset--
                var end = (offset + 4_000).coerceAtMost(file.content.length)
                if (end < file.content.length && file.content[end].isLowSurrogate()) end--
                buildJsonObject {
                    put("path", file.path)
                    put("revision", file.revision)
                    put("content", file.content.substring(offset, end))
                    if (end < file.content.length) put("next_offset", end)
                }
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

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.required(key: String): String = string(key) ?: error("$key is required.")

private fun JsonObject.offset(): Int = ((get("offset") as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
