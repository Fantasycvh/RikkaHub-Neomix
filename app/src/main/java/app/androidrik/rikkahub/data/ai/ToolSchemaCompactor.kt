package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool

/**
 * 工具 schema 描述压缩（批次4，Yuihub 3.0.0 `ToolSchemaCompactor` 移植）：只裁描述文本与
 * 冗余的 title/examples，保留全部参数、类型、枚举与默认值——不减少任何可调用能力，
 * 只是让模型读更短的说明。工具数增长时（MCP 装配面），这块是请求体里最大的固定开销。
 *
 * 说明文本按句子边界截断，避免切断关键约束；参数名与 required 永不改动。
 *
 * 本仓默认 OFF（Settings.toolSchemaCompactLevel=0）：压缩改变模型看到的内容，
 * 默认开启属于行为变更，由用户显式选择档位（设置 → 通用）。
 */
object ToolSchemaCompactor {
    // 纪律文本承载关键约束的工具：给更高上限（3×），避免压缩后调用质量下降。
    // 本仓清单（对齐 Yuihub 的 ESSENTIAL_TOOLS 思路，按本仓真实工具名）：
    // 记忆纪律 / 子代理委派政策 / 计划规则 / 工作区读写与技能装的说明都远超普通工具。
    private val ESSENTIAL_TOOLS = setOf(
        "memory_tool",
        "dispatch_subagent",
        "todo_write",
        "workspace_shell",
        "workspace_read_file",
        "workspace_edit_file",
        "save_skill",
        "install_skill",
    )

    enum class Level(
        val topDescriptionMax: Int,
        val parameterDescriptionMax: Int,
        val dropExamples: Boolean,
    ) {
        OFF(Int.MAX_VALUE, Int.MAX_VALUE, false),
        BALANCED(320, 120, false),
        AGGRESSIVE(180, 64, true);

        companion object {
            /** Settings.toolSchemaCompactLevel（Int 持久化）：0=OFF，2=AGGRESSIVE，其余=BALANCED。 */
            fun from(value: Int): Level = when (value) {
                0 -> OFF
                2 -> AGGRESSIVE
                else -> BALANCED
            }
        }
    }

    fun compact(tools: List<Tool>, level: Level): List<Tool> {
        if (level == Level.OFF || tools.isEmpty()) return tools
        return tools.map { tool ->
            val descriptionCap = if (tool.name in ESSENTIAL_TOOLS) {
                minOf(level.topDescriptionMax * 3, 960)
            } else {
                level.topDescriptionMax
            }
            tool.copy(
                description = trimDescription(tool.description, descriptionCap),
                parameters = {
                    when (val schema = tool.parameters()) {
                        is InputSchema.Obj -> InputSchema.Obj(
                            properties = compactObject(schema.properties, level),
                            required = schema.required,
                        )
                        null -> null
                    }
                },
            )
        }
    }

    private fun compactObject(obj: JsonObject, level: Level): JsonObject = buildJsonObject {
        obj.forEach { (key, value) ->
            when {
                key == "title" || key == "\$comment" -> Unit
                key == "examples" && level.dropExamples -> Unit
                key == "description" && value is JsonPrimitive && value.isString -> {
                    put(key, trimDescription(value.content, level.parameterDescriptionMax))
                }
                value is JsonObject -> put(key, compactObject(value, level))
                value is JsonArray -> put(
                    key,
                    JsonArray(
                        value.map { item ->
                            if (item is JsonObject) compactObject(item, level) else item
                        }
                    )
                )
                else -> put(key, value)
            }
        }
    }

    internal fun trimDescription(text: String, maxChars: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= maxChars) return normalized
        val window = normalized.take(maxChars)
        val boundary = window.indexOfLast { it == '.' || it == '。' || it == '!' || it == '！' || it == '\n' }
        val cut = if (boundary >= maxChars * 0.5) window.substring(0, boundary + 1) else window
        return cut.trimEnd() + "…"
    }
}
