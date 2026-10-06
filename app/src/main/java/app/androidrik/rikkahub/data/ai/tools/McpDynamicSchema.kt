package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP schema **动态分层加载**（peer⑤③ 缩水版，设计见 handoff §三「工具 schema 三档加载」
 * 与 senera 参考实现 peer-apps-benchmark §七）。
 *
 * **问题**：MCP 工具的完整 JSON schema 全量进 provider schema——实测单服务器即可吃掉
 * 33K token（见 ChatToolFactory 装配注释），是「谁吃掉了上下文」的头号元凶。
 *
 * **缩水版两档**（完整三档的子集，刻意不动 tools 数组结构/装配顺序）：
 * - **原生档**：schema 体积 ≤ 阈值的工具照旧全量进 schema（小服务器零行为变化）；
 * - **折叠档**：超过阈值的工具**不进 schema**，由两个固定元工具替代——
 *   `get_tool_map`（返回折叠档的 名称/服务器/描述/参数提示，让模型知道有什么、参数长什么样）
 *   + `mcp_tool_call(name, arguments)`（桥：模型以 JSON 串传参，宿主解析后走**原有** MCP 执行链）。
 *
 * **为什么是桥而不是「按需装回 schema」**：mid-run 换 schema 会作废前缀缓存（Claude 断点
 * 在最后工具上）——桥让 provider schema 恒定不变。代价是弱模型对「JSON 串传参」的桥
 * 适配较差，故设按工具体积阈值：小工具保留原生 schema（原生调用质量最好），
 * 只有重型工具才走桥。
 *
 * 纯函数 + 无 Android 依赖（MCP 执行依赖由调用方闭包注入），可 JVM 单测。
 */

/** 单工具 schema 折叠阈值（token，按 provider 实际下发形状估算）。 */
internal const val MCP_SCHEMA_COLLAPSE_TOKENS = 600

/** 折叠档工具在工具地图里的描述上限（截断，地图本体要小）。 */
internal const val TOOL_MAP_DESCRIPTION_CHARS = 200

/** 一个待装配的 MCP 工具条目（装配点的中间形态，纯数据）。 */
internal data class McpToolEntry(
    val serverId: String,
    val serverName: String,
    /** 净化去重后的 provider function name（模型看到的名字） */
    val functionName: String,
    /** MCP 协议层的原始工具名（callTool 用它，不是净化名） */
    val originalToolName: String,
    val description: String,
    /** 紧凑参数提示（`name:type, ...`；由 InputSchema.properties 派生） */
    val paramsHint: String,
    val needsApproval: Boolean,
    /** provider 实际下发形状的 schema token 估算 */
    val schemaTokens: Int,
    /** 组装好的原生工具（原生档直接进 schema 用） */
    val nativeTool: Tool,
)

/** 拆分结果：原生档照旧进 schema；折叠档由元工具替代。 */
internal data class McpSchemaSplit(
    val native: List<McpToolEntry>,
    val collapsed: List<McpToolEntry>,
)

/** 按单工具 schema token 估算拆两档（稳定排序：原生档在前、折叠档按体积降序）。 */
internal fun splitMcpToolsBySchemaSize(
    entries: List<McpToolEntry>,
    threshold: Int = MCP_SCHEMA_COLLAPSE_TOKENS,
): McpSchemaSplit {
    val (small, large) = entries.partition { it.schemaTokens <= threshold }
    return McpSchemaSplit(
        native = small.sortedBy { it.schemaTokens },
        collapsed = large.sortedByDescending { it.schemaTokens },
    )
}

/**
 * 从 InputSchema 的 properties JSON 生成**紧凑参数提示**（如 `query:string, limit:integer`）。
 *
 * 完整 JSON schema 的描述文本动辄数百 token；提示只保留「参数名:类型」骨架——模型据此
 * 构造 `mcp_tool_call` 的 arguments，宿主执行时把原始 schema 交给 MCP 服务器校验。
 * 嵌套对象/数组只标注 `object`/`array` 不展开（提示要小，精确形状靠试错自愈）。
 */
internal fun buildParamsHint(schema: JsonObject?): String {
    val properties = schema?.get("properties") as? JsonObject ?: return ""
    return properties.entries.take(12).joinToString(", ") { (name, value) ->
        val type = (value as? JsonObject)?.get("type")?.jsonPrimitive?.content ?: "any"
        "$name:$type"
    }
}

/** 折叠档工具地图 payload：模型据此决定调哪个工具、怎么传参。 */
internal fun buildToolMapPayload(collapsed: List<McpToolEntry>): String {
    val array = collapsed.joinToString(",\n") { entry ->
        val description = entry.description.take(TOOL_MAP_DESCRIPTION_CHARS)
        buildString {
            append("""{"name": """)
            append(jsonQuote(entry.functionName))
            append(""", "server": """)
            append(jsonQuote(entry.serverName))
            append(""", "description": """)
            append(jsonQuote(description))
            append(""", "params_hint": """)
            append(jsonQuote(entry.paramsHint))
            append(""", "needs_approval": """)
            append(entry.needsApproval.toString())
            append("""}""")
        }
    }
    return """{"tools": [
$array
]}"""
}

private fun jsonQuote(text: String): String =
    "\"" + text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")
        .replace("\t", "\\t") + "\""

/** 桥调用寻址：按净化后的 function name 找折叠档条目；找不到返回 null。 */
internal fun resolveCollapsedEntry(
    collapsed: List<McpToolEntry>,
    functionName: String,
): McpToolEntry? = collapsed.firstOrNull { it.functionName == functionName }
