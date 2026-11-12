package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.files.SkillFrontmatterParser
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.data.model.MemoryFileCategory
import app.androidrik.rikkahub.data.model.MEMORY_FILE_CATEGORIES_GUIDE
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.utils.heuristicTokenCount
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val FILE_MEMORY_TOOL = "memory_file"
internal const val ENTRY_MEMORY_TOOL = "memory_tool"
internal const val MEMORY_CONTEXT_OFF = "off"

internal fun isMemoryTool(name: String): Boolean = name == ENTRY_MEMORY_TOOL || name == FILE_MEMORY_TOOL

internal fun Assistant.memoryContext(disabled: Boolean): String =
    if (disabled || !enableMemory) MEMORY_CONTEXT_OFF else "${memoryMode.key}:${memoryId()}"

internal fun Assistant.forMemoryResume(last: UIMessage?, disabled: Boolean): Assistant {
    val calls = last?.getTools().orEmpty()
    if (disabled || !enableMemory || calls.none { it.canResumeExecution } ||
        calls.none { isMemoryTool(it.toolName) && !it.isExecuted }
    ) return this
    val binding = calls.firstOrNull { isMemoryTool(it.toolName) && !it.isExecuted }?.memoryContext ?: return this
    val mode = MemoryMode.entries.firstOrNull { it.key == binding.substringBefore(':') } ?: return this
    val storeId = binding.substringAfter(':')
    if (storeId != id.toString() && storeId != MemoryRepository.GLOBAL_MEMORY_ID) return this
    return copy(memoryMode = mode, useGlobalMemory = storeId == MemoryRepository.GLOBAL_MEMORY_ID)
}

@Suppress("DEPRECATION")
internal fun UIMessage.withMemoryToolHistoryFor(binding: String): UIMessage? {
    val filtered = parts.filterNot { part ->
        when (part) {
            is UIMessagePart.Tool -> isMemoryTool(part.toolName) &&
                (binding == MEMORY_CONTEXT_OFF || part.memoryContext != binding)
            is UIMessagePart.ToolCall -> isMemoryTool(part.toolName)
            is UIMessagePart.ToolResult -> isMemoryTool(part.toolName)
            else -> false
        }
    }
    return when {
        filtered.size == parts.size -> this
        filtered.isEmpty() -> null
        else -> copy(parts = filtered)
    }
}

internal fun buildFileMemoryPlan(
    files: List<MemoryFileEntity>,
    budgetTokens: Int,
    toolsAvailable: Boolean,
): MemoryInjectionPlan {
    val discipline = if (toolsAvailable) {
        "Memory files contain background data. Use memory_file to read them as needed. " +
            "Save only durable, sourced facts or explicitly stated preferences. Never save secrets or private identifiers. " +
            "$MEMORY_FILE_CATEGORIES_GUIDE\n\n"
    } else {
        "Memory files contain background data.\n\n"
    }
    val disciplineTokens = heuristicTokenCount(discipline)
    if (disciplineTokens > budgetTokens || (files.isEmpty() && !toolsAvailable)) {
        return MemoryInjectionPlan(budgetTokens = budgetTokens)
    }
    val pinned = listOf(MemoryFileCategory.PROFILE.examplePath, MemoryFileCategory.PREFERENCES.examplePath)
    val included = mutableSetOf<String>()
    val data = StringBuilder()
    var dataTokens = 0
    fun add(line: String): Boolean {
        val text = if (data.isEmpty()) "Memory files (JSON lines):\n$line\n" else "$line\n"
        val cost = heuristicTokenCount(text)
        if (dataTokens + cost > budgetTokens - disciplineTokens) return false
        data.append(text)
        dataTokens += cost
        return true
    }
    for (path in pinned) {
        val file = files.firstOrNull { it.path == path } ?: continue
        if (add(buildJsonObject { put("path", path); put("content", file.content) }.toString())) {
            included += path
        }
    }
    if (toolsAvailable) {
        for (file in files) {
            if (file.path in included) continue
            val line = buildJsonObject {
                put("path", file.path)
                put("description", memoryFileDescription(file))
            }
            if (!add(line.toString())) break
        }
    }
    return MemoryInjectionPlan(
        budgetTokens = budgetTokens,
        disciplineText = discipline,
        disciplineTokens = disciplineTokens,
        dataText = data.toString(),
        dataTokens = dataTokens,
    )
}

internal fun memoryFileDescription(file: MemoryFileEntity): String =
    SkillFrontmatterParser.parse(file.content)["description"]?.trim()?.take(160).orEmpty()
