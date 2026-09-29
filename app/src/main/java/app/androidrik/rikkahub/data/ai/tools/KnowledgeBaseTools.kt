package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity
import app.androidrik.rikkahub.data.rag.RagRepository

/**
 * 知识库检索工具（RAG-as-tool，移植自 MineChat KnowledgeBaseTool，跨库扩展）。
 *
 * 与「注入时语义检索」的关键差异：不占每轮 prompt，只在模型调用时产生一次 embedding 请求。
 * 单工具覆盖助手绑定的**全部**知识库（而非每库一个工具）——schema 预算恒定一份。
 */
fun createKnowledgeBaseTool(
    repository: RagRepository,
    knowledgeBases: List<KnowledgeBaseEntity>,
): Tool {
    require(knowledgeBases.isNotEmpty()) { "No knowledge bases enabled" }
    val defaultTopK = knowledgeBases.maxOf { it.topK }
    val names = knowledgeBases.joinToString("、") { it.name }

    return Tool(
        name = "search_knowledge_base",
        description = """
            Search the connected knowledge bases ($names) for relevant information.
            Use this when the user asks about content that may be documented in these knowledge bases.
            The knowledge bases contain indexed documents; this tool performs semantic search over them.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search query to find relevant documents in the knowledge bases")
                    })
                    put("top_k", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of results to return (default: $defaultTopK)")
                    })
                },
                required = listOf("query")
            )
        },
        execute = { args ->
            val json = args.jsonObject
            val query = json["query"]?.jsonPrimitive?.content
                ?: return@Tool listOf(UIMessagePart.Text("query is required"))
            val topK = json["top_k"]?.jsonPrimitive?.intOrNull ?: defaultTopK

            val results = repository.search(
                kbIds = knowledgeBases.mapTo(HashSet()) { it.id },
                query = query,
                topK = topK,
            )

            if (results.isEmpty()) {
                listOf(UIMessagePart.Text("未在知识库中找到相关信息"))
            } else {
                val text = buildString {
                    appendLine("[知识库检索结果 (${results.size}条)]")
                    results.forEachIndexed { index, result ->
                        appendLine()
                        appendLine("--- 结果 ${index + 1} (知识库: ${result.kbName}) ---")
                        appendLine("相似度: ${"%.2f".format(result.similarity)}")
                        appendLine(result.chunkText)
                    }
                }
                listOf(UIMessagePart.Text(text))
            }
        }
    )
}
