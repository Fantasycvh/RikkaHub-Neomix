package app.androidrik.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 */
class ChatCompletionsAPIMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildMessages method via reflection
    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        backfillToolReasoning: Boolean = false,
    ): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            List::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            api,
            messages,
            includeHistoryReasoning,
            listOf(Modality.TEXT, Modality.IMAGE),
            backfillToolReasoning,
        ) as JsonArray
    }

    @Test
    fun `multi-round reasoning and tool calls should be correctly ordered`() {
        // Scenario: Assistant message with multiple rounds of reasoning and tool calls
        // [Reasoning1, Text1, Tool1(executed), Reasoning2, Text2, Tool2(executed), Text3]
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("I'll search for information"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result 1"),
                UIMessagePart.Reasoning(reasoning = "Now I need to calculate..."),
                UIMessagePart.Text("Let me calculate that"),
                createExecutedTool("call_2", "calculate", """{"expr": "1+1"}""", "2"),
                UIMessagePart.Text("The final answer is 2")
            )
        )

        val messages = listOf(
            UIMessage.user("What is 1+1?"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Result should contain:
        // 1. User message
        // 2. Assistant message with reasoning_content, content, and tool_calls for search
        // 3. Tool result for search
        // 4. Assistant message with reasoning_content, content, and tool_calls for calculate
        // 5. Tool result for calculate
        // 6. Assistant message with final text

        assertTrue("Should have at least 6 messages", result.size >= 6)

        // Verify user message
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        // Verify first assistant message (with first tool call)
        val assistant1 = result[1].jsonObject
        assertEquals("assistant", assistant1["role"]?.jsonPrimitive?.content)
        assertTrue("First assistant message should have tool_calls", assistant1.containsKey("tool_calls"))
        val toolCalls1 = assistant1["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls1?.size)
        assertEquals("search", toolCalls1?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify first tool result
        val toolResult1 = result[2].jsonObject
        assertEquals("tool", toolResult1["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolResult1["tool_call_id"]?.jsonPrimitive?.content)

        // Verify second assistant message (with second tool call)
        val assistant2 = result[3].jsonObject
        assertEquals("assistant", assistant2["role"]?.jsonPrimitive?.content)
        assertTrue("Second assistant message should have tool_calls", assistant2.containsKey("tool_calls"))
        val toolCalls2 = assistant2["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls2?.size)
        assertEquals("calculate", toolCalls2?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify second tool result
        val toolResult2 = result[4].jsonObject
        assertEquals("tool", toolResult2["role"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResult2["tool_call_id"]?.jsonPrimitive?.content)

        // Verify final assistant message
        val assistant3 = result[5].jsonObject
        assertEquals("assistant", assistant3["role"]?.jsonPrimitive?.content)
        val content = assistant3["content"]
        assertTrue("Final assistant content should contain 'final answer'",
            content?.jsonPrimitive?.content?.contains("final answer") == true ||
            (content is JsonArray && content.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("final answer") == true })
        )
    }

    @Test
    fun `parallel tool calls should be grouped together`() {
        // Scenario: Multiple tools called in parallel
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search multiple sources"),
                createExecutedTool("call_1", "search_web", """{"query": "test1"}""", "Result 1"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
                UIMessagePart.Text("Combined results show...")
            )
        )

        val messages = listOf(
            UIMessage.user("Search everything"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify parallel tools are in same assistant message
        var foundAssistantWithMultipleTools = false
        for (element in result) {
            val msg = element.jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val toolCalls = msg["tool_calls"]?.jsonArray
                if (toolCalls != null && toolCalls.size == 3) {
                    foundAssistantWithMultipleTools = true
                    // Verify all three tool calls are present
                    val toolNames = toolCalls.map {
                        it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }
                    assertTrue(toolNames.contains("search_web"))
                    assertTrue(toolNames.contains("search_docs"))
                    assertTrue(toolNames.contains("search_wiki"))
                    break
                }
            }
        }
        assertTrue("Should have assistant message with 3 parallel tool calls", foundAssistantWithMultipleTools)

        // Verify 3 separate tool result messages
        val toolResults = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(3, toolResults.size)
    }

    @Test
    fun `reasoning should be included for all assistant messages when history reasoning enabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assertEquals("Initial thinking",
            assistantMessages[0].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("Final thinking",
            assistantMessages[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning should be excluded from all assistant messages when history reasoning disabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assistantMessages.forEach { msg ->
            assertFalse("Assistant should not have reasoning_content",
                msg.jsonObject.containsKey("reasoning_content"))
        }
    }

    private fun createMultiRoundReasoningMessages(): List<UIMessage> {
        val assistant1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Initial thinking"),
                UIMessagePart.Text("Initial response")
            )
        )
        val assistant2 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Final thinking"),
                UIMessagePart.Text("Final response")
            )
        )
        return listOf(
            UIMessage.user("First question"),
            assistant1,
            UIMessage.user("Second question"),
            assistant2
        )
    }

    @Test
    fun `tool_call followed by tool result should maintain correct order`() {
        // Verify the pattern: assistant (with tool_calls) -> tool (result)
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Calling tool"),
                createExecutedTool("call_abc", "my_tool", """{"param": "value"}""", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find the assistant message with tool_calls
        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }

        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The next message should be the tool result
        val nextMsg = result[assistantIndex + 1].jsonObject
        assertEquals("tool", nextMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertFalse("Tool results must omit unsupported name field", nextMsg.containsKey("name"))
    }

    @Test
    fun `complex multi-round conversation with interleaved reasoning and tools`() {
        // Complex scenario simulating agent conversation
        val messages = listOf(
            UIMessage.user("Plan and execute a task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Step 1: Analyze the task"),
                    UIMessagePart.Text("First, I'll gather information"),
                    createExecutedTool("call_1", "gather_info", "{}", "Info gathered"),
                    UIMessagePart.Reasoning(reasoning = "Step 2: Process the information"),
                    UIMessagePart.Text("Now processing..."),
                    createExecutedTool("call_2", "process", "{}", "Processed"),
                    UIMessagePart.Reasoning(reasoning = "Step 3: Generate output"),
                    UIMessagePart.Text("Here is the result")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // 1. user message
        // 2. assistant (reasoning + text + tool_calls)
        // 3. tool result
        // 4. assistant (reasoning + text + tool_calls)
        // 5. tool result
        // 6. assistant (reasoning + text)

        // Count message types
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val assistantCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val toolCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }

        assertEquals("Should have 1 user message", 1, userCount)
        assertEquals("Should have 2 tool results", 2, toolCount)
        assertTrue("Should have at least 3 assistant messages", assistantCount >= 3)

        // Verify order: each tool_calls should be immediately followed by tool result
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assertTrue("Index should not be last", i < result.size - 1)
                val nextMsg = result[i + 1].jsonObject
                assertEquals("Tool result should follow tool_calls",
                    "tool", nextMsg["role"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `assistant with only reasoning and empty text should be filtered out when history reasoning disabled`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 1", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 2", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant with only reasoning and empty text should be kept when history reasoning enabled`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        assertEquals(3, result.size)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `latest assistant with reasoning and empty text should keep reasoning content`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    // ==================== Helper Functions ====================

    /**
     * 网关结构性校验：思考模式下带 `tool_calls` 的 assistant 消息**必须回传思维链**，
     * 历史里没有可回传的（开启思考前的旧消息 / 上游只给加密推理 / 用户关了回传 / 编辑过）时，
     * 省略字段会让**整个请求**被 400 拒掉（实测：DeepSeek Responses 与 opencode zen）。
     * 这里钉住「回填空串」与「非思考模型不凭空多字段」两侧。
     */
    @Test
    fun `tool message without reasoning gets empty reasoning_content only when backfill enabled`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("I'll search for that"),
                createExecutedTool("call_1", "search", """{"query": "x"}""", "result"),
            )
        )
        val messages = listOf(UIMessage.user("hi"), assistantMessage)

        val backfilled = invokeBuildMessages(messages, backfillToolReasoning = true)
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals("", backfilled.jsonObject["reasoning_content"]?.jsonPrimitive?.content)

        // 默认（门槛未命中：非思考模型）不新增该字段——避免给不认识它的服务端凭空送字段
        val plain = invokeBuildMessages(messages)
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertNull(plain.jsonObject["reasoning_content"])
    }

    @Test
    fun `real reasoning is never replaced by the empty backfill`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "deep thought"),
                createExecutedTool("call_1", "search", """{"query": "x"}""", "result"),
            )
        )
        val msg = invokeBuildMessages(listOf(UIMessage.user("hi"), assistantMessage), backfillToolReasoning = true)
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals("deep thought", msg.jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    /**
     * **2026-10-13 真机回归**：回填不能只管「带 tool_calls 的 assistant」。
     *
     * 现场是一份 17KB 的做梦（记忆整理）报文被 `api.deepseek.com` 拒成 400
     * 「The `reasoning_content` in the thinking mode must be passed back」。在设备上原样重放定位到：
     * 带 tool_calls 的那条**已经有** `reasoning_content`，真正缺字段的是**最后那条普通的总结性
     * assistant 消息**（无 tool_calls）。同一命令下：给它补上（**空串即可**）→ 200；删掉带 tool_calls
     * 那条的字段 → 仍 400；`thinking:disabled` → 200。
     * 即：**思考模式下每条 assistant 消息都要有该字段**。
     *
     * 这条用例用一个「既没有 reasoning、也没有 tool_calls」的纯文本 assistant 消息钉住这个形态——
     * 它正是原先判据 `tools.isNotEmpty() && backfill` 会漏掉的那种。
     */
    @Test
    fun `plain assistant message without reasoning also gets the backfill`() {
        val messages = listOf(
            UIMessage.user("hi"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("nothing to review")),
            ),
        )

        val assistant = invokeBuildMessages(messages, backfillToolReasoning = true)
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertEquals(
            "无 tool_calls 的普通 assistant 也必须回填（DeepSeek 思考模式逐条校验）",
            "",
            assistant.jsonObject["reasoning_content"]?.jsonPrimitive?.content,
        )

        // 门槛未命中时不新增字段（不给不认识它的服务端凭空送字段）
        val plain = invokeBuildMessages(messages)
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        assertNull(plain.jsonObject["reasoning_content"])
    }

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }


    @Test
    fun `tools schema fills array items (Gemini-proxy relay compatibility)`() {
        // 带数组属性的工具（args/outputs 嵌套数组，此前经 OpenAI 兼容中转站转 functionDeclarations
        // 时缺 items，Gemini 报 missing field——OpenAI 路径此前没有 ensureArrayItems 归一化）
        val toolWithArrays = Tool(
            name = "reorder_items",
            description = "reorder nested arrays",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("args", buildJsonObject { put("type", JsonPrimitive("array")) })
                        put("outputs", buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {
                                put("items", buildJsonObject { put("type", JsonPrimitive("array")) })
                            })
                        })
                    }
                )
            },
            execute = { emptyList() },
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-3.7-flash",
                displayName = "g",
                abilities = listOf(ModelAbility.TOOL),
            ),
            tools = listOf(toolWithArrays),
        )
        val requestBody = invokeBuildChatCompletionRequest(params)
        val tools = requestBody["tools"]!!.jsonArray
        val fn = tools[0].jsonObject["function"]!!.jsonObject
        val parameters = fn["parameters"]!!.jsonObject
        // args 数组补上 items
        val args = parameters["properties"]!!.jsonObject["args"]!!.jsonObject
        assertNotNull(args["items"])
        assertEquals("string", args["items"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        // 嵌套 object 里的 items 数组也补上（递归）
        val outputs = parameters["properties"]!!.jsonObject["outputs"]!!.jsonObject
        val nestedItems = outputs["properties"]!!.jsonObject["items"]!!.jsonObject
        assertNotNull(nestedItems["items"])
    }

    private fun invokeBuildChatCompletionRequest(params: TextGenerationParams): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, emptyList<UIMessage>(), params, ProviderSetting.OpenAI(), false) as JsonObject
    }

}
