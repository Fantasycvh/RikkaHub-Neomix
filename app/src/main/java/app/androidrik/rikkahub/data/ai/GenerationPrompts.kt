package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            // 注入上限：最多 60 条、总内容 100K，防记忆爆炸 prompt（turbo 06baee4）
            // 取「最新」的 60 条而非列表头部：按内部 sortKey（自增主键）降序即写入先后。
            // （此前用对外 id 排序，但 id 已改为随机 opaque token、不再单调，故改用 sortKey。
            //   排序放这里，使行为不依赖调用方的列表顺序。）
            var totalChars = 0
            memories.sortedByDescending { it.sortKey }.take(MAX_MEMORY_INJECT_COUNT).forEach { memory ->
                if (totalChars >= MAX_MEMORY_TOTAL_CHARS) return@forEach
                val content = memory.content.take(MAX_MEMORY_TOTAL_CHARS - totalChars)
                totalChars += content.length
                add(buildJsonObject {
                    // 对模型暴露 opaque token（非自增编号）：避免编号泄漏记忆条数、也避免删除空洞难看
                    put("id", memory.id)
                    put("content", content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

/** 记忆注入最大条数 */
private const val MAX_MEMORY_INJECT_COUNT = 60
/** 记忆注入总字符上限（约 100K） */
private const val MAX_MEMORY_TOTAL_CHARS = 100_000
