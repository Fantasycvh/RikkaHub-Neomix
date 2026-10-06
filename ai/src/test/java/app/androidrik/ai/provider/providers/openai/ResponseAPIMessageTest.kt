package app.androidrik.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(messages)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")), params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `function tools should disable strict mode so optional fields stay optional`() {
        // #1990：Response API 不传 strict 时默认按严格模式处理，可选字段全变必填
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(tools = listOf(createFunctionTool("get_weather")))
        )

        val tool = requestBody["tools"]!!.jsonArray[0].jsonObject
        assertEquals("false", tool["strict"]?.jsonPrimitive?.content)
    }

    @Test
    fun `function tools and built-in tools should coexist in the same tools array`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(
                tools = listOf(createFunctionTool("get_weather")),
                builtInTools = setOf(BuiltInTools.Search)
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertTrue("tools should exist", tools != null)
        val types = tools!!.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("function tool should not be dropped", types.contains("function"))
        assertTrue("built-in web_search should be present", types.contains("web_search"))
        assertEquals(2, tools.size)
    }

    @Test
    fun `function tools should be sent when no built-in tools configured`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(tools = listOf(createFunctionTool("get_weather")))
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(1, tools?.size)
        assertEquals("function", tools!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools key should be absent when neither function nor built-in tools exist`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams()
        )

        assertFalse("tools key should not be written", requestBody.containsKey("tools"))
    }

    // ==================== Helper Functions ====================

    private fun createToolParams(
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet()
    ): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.TOOL),
                tools = builtInTools
            ),
            tools = tools
        )
    }

    private fun createFunctionTool(name: String): Tool {
        return Tool(
            name = name,
            description = "test tool",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

    @Test
    fun `re-emitted reasoning items carry no id to avoid duplicate rs ids`() {
        // 同一轮多个 function_call 时的补插副本不带 id：复用原 rs_ id 会让同一请求出现
        // 重复 item id（OpenAI 官方可能按唯一性拒绝）；主 reasoning item 保留 id。
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "thinking...",
                    metadata = app.androidrik.ai.ui.OpenAIReasoningMetadata(reasoningId = "rs_1").toMetadata()
                ),
                createExecutedTool("call_1", "search_web", """{"q": "a"}""", "result a"),
                createExecutedTool("call_2", "search_web", """{"q": "b"}""", "result b"),
                UIMessagePart.Text("Done")
            )
        )
        val input = invokeBuildMessages(listOf(assistantMessage))

        val reasoningItems = input
            .filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(2, reasoningItems.size)
        assertEquals("rs_1", reasoningItems[0]["id"]?.jsonPrimitive?.content)
        assertNull(reasoningItems[1]["id"])
    }

    @Test
    fun `multi tool calls in one round should re-emit reasoning before every function_call`() {
        // Regression for issue #1690: DeepSeek/openode gateways require a reasoning
        // item before EVERY function_call within the same round (not just the first).
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking..."),
                createExecutedTool("call_1", "search_web", """{"q": "a"}""", "result a"),
                createExecutedTool("call_2", "search_web", """{"q": "b"}""", "result b"),
                createExecutedTool("call_3", "get_time_info", "{}", "now"),
                UIMessagePart.Text("Done")
            )
        )
        val input = invokeBuildMessages(listOf(assistantMessage))

        // 统计每个 function_call 前是否紧跟 reasoning item
        var functionCalls = 0
        var reasoningBeforeCall = 0
        var prevWasReasoning = false
        val items = input.filter { it is JsonObject }
        for (item in items) {
            val obj = item.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "reasoning") {
                prevWasReasoning = true
                val content = obj["content"]?.jsonArray
                assertTrue("reasoning item must carry content[].reasoning_text", content != null && content.isNotEmpty())
                val textPart = content!!.first().jsonObject
                assertEquals("reasoning_text", textPart["type"]?.jsonPrimitive?.content)
                assertEquals("thinking...", textPart["text"]?.jsonPrimitive?.content)
            } else if (type == "function_call") {
                functionCalls++
                if (prevWasReasoning) reasoningBeforeCall++
                prevWasReasoning = false
            } else {
                prevWasReasoning = false
            }
        }
        assertEquals("3 function_calls should be emitted", 3, functionCalls)
        assertEquals(
            "every function_call must be preceded by a reasoning item",
            functionCalls, reasoningBeforeCall
        )
    }

    @Test
    fun `reasoning item should include content reasoning_text for spec compliance`() {
        // Issue #1690 part 1: historical reasoning replayed as item must carry content[].reasoning_text
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "chain of thought"),
                createExecutedTool("call_1", "search", """{"q": "x"}""", "r")
            )
        )
        val input = invokeBuildMessages(listOf(assistantMessage))
        val reasoningItem = input.filterIsInstance<JsonObject>()
            .firstOrNull { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertTrue("reasoning item should exist", reasoningItem != null)
        val content = reasoningItem!!["content"]?.jsonArray
        assertTrue("reasoning item must have content array", content != null)
        val textPart = content!!.first().jsonObject
        assertEquals("reasoning_text", textPart["type"]?.jsonPrimitive?.content)
        assertEquals("chain of thought", textPart["text"]?.jsonPrimitive?.content)
    }

    /**
     * 网关结构性校验（实测 DeepSeek Responses）：思考模式下**每个工具调用轮次前必须有 reasoning item**，
     * 历史里没有思维链时（旧消息 / 只给加密推理 / 用户关了回传 / 编辑过）直接 400
     * 「The `reasoning_text` in the thinking mode must be passed back to the API」。
     * 这里钉住：回填的占位 item 必须**非空**（该 API 拒绝空文本）、排在 function_call 之前、且默认关闭。
     */
    @Test
    fun `tool call without reasoning gets a non-empty placeholder reasoning item when backfill enabled`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("calling the tool"),
                createExecutedTool("call_1", "search", """{"query": "x"}""", "ok"),
            )
        )

        val items = api.buildMessages(listOf(assistantMessage), reasoningTextRequired = true)
        // 注意首项是正文内容项（无 type 字段），占位 reasoning 必须排在 function_call **之前**
        assertEquals(
            listOf(null, "reasoning", "function_call", "function_call_output"),
            items.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
        val reasoningItem = items.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val reasoningText = reasoningItem.jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("占位文本必须非空（该 API 拒绝空文本）", reasoningText.isNotEmpty())

        // 门槛未命中（非思考模型）时保持既有行为：不插 reasoning item
        val plain = api.buildMessages(listOf(assistantMessage))
        assertEquals(
            listOf(null, "function_call", "function_call_output"),
            plain.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `existing reasoning is reused instead of a placeholder`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "think first"),
                createExecutedTool("call_1", "search", """{"query": "x"}""", "ok"),
            )
        )
        val items = api.buildMessages(listOf(assistantMessage), reasoningTextRequired = true)
        val reasoningItem = items.first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }
        val reasoningText = reasoningItem.jsonObject["content"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("think first", reasoningText)
    }

    /**
     * 加密推理**也必须**带非空 `content[].reasoning_text`（DeepSeek Responses 实测：
     * 只有 encrypted_content 时 400「The `reasoning_text` … must be passed back」）；
     * 但不能因此回放明文——占位文本即可满足结构校验。
     */
    @Test
    fun `encrypted reasoning gets a non-empty placeholder content when host requires it`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "plaintext reasoning",
                    metadata = OpenAIReasoningMetadata(
                        reasoningId = "rs_1",
                        encryptedContent = "encrypted",
                    ).toMetadata(),
                ),
                createExecutedTool("call_1", "search", """{"query": "x"}""", "ok"),
            )
        )

        val item = api.buildMessages(listOf(assistantMessage), reasoningTextRequired = true)
            .first { it.jsonObject["type"]?.jsonPrimitive?.content == "reasoning" }.jsonObject

        assertEquals("encrypted", item["encrypted_content"]?.jsonPrimitive?.content)
        val contentText = item["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("占位必须非空（该 API 拒绝空文本）", contentText.isNotEmpty())
        assertNotEquals("加密时不得回放明文推理", "plaintext reasoning", contentText)
    }

    @Test
    fun `encrypted reasoning stays encrypted-only on hosts that do not require reasoning_text`() {
        val item = invokeBuildMessages(listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "plaintext reasoning",
                        metadata = OpenAIReasoningMetadata(
                            reasoningId = "rs_1",
                            encryptedContent = "encrypted",
                        ).toMetadata(),
                    )
                ),
            )
        )).single().jsonObject

        assertEquals("encrypted", item["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse("未验证的 host 不塞占位 content", item.containsKey("content"))
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
    fun `encrypted reasoning should not replay plaintext content`() {
        val reasoningItem = invokeBuildMessages(listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "plaintext reasoning",
                        metadata = OpenAIReasoningMetadata(
                            reasoningId = "rs_1",
                            encryptedContent = "encrypted",
                        ).toMetadata(),
                    )
                ),
            )
        )).single().jsonObject

        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse(reasoningItem.containsKey("content"))
    }

    @Test
    fun `unencrypted reasoning should replay plaintext content`() {
        val reasoningItem = invokeBuildMessages(listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(
                        reasoning = "plaintext reasoning",
                        metadata = OpenAIReasoningMetadata(
                            reasoningId = "rs_1",
                        ).toMetadata(),
                    )
                ),
            )
        )).single().jsonObject

        val content = reasoningItem["content"]?.jsonArray
        assertEquals(1, content?.size)
        assertEquals(
            "plaintext reasoning",
            content?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )
        assertFalse(reasoningItem.containsKey("encrypted_content"))
    }


    // ---- system 角色：第一条进 instructions，后续**不得静默丢弃**（2026-10-06 修）----

    private fun itemTexts(json: JsonArray): List<String> =
        json.map { item ->
            val obj = item.jsonObject
            val role = obj["role"]?.jsonPrimitive?.content ?: "?"
            val content = obj["content"]
            "$role:${content?.toString() ?: ""}"
        }

    @Test
    fun `subsequent system messages are downgraded to user instead of dropped`() {
        // 轮次尾部注入的 system 段（PromptInjectionTransformer 的 END_OF_CHAT system 分支）
        // 会作为独立 system 消息进入这里。旧实现 filter 掉 ⇒ 内容凭空消失；
        // 现在对齐 Claude/Google 的做法：降级为 user 送达（位置语义保住，仅语气降级）。
        val messages = listOf(
            UIMessage.system("系统提示本体"),
            UIMessage.user("你好"),
            UIMessage.system("尾部 system 注入"),
        )

        val items = invokeBuildMessages(messages)
        val texts = itemTexts(items)

        // 第一条 system 已进顶层 instructions ⇒ 不应在 input 里重复出现
        assertFalse("第一条 system 不应进 input: $texts", texts.any { it.contains("系统提示本体") })
        // 后续 system 的内容**必须**留在 input 里（降级为 user）
        assertTrue("后续 system 内容不得丢失: $texts", texts.any { it.contains("尾部 system 注入") })
        assertTrue(
            "后续 system 应以 user 角色送达: $texts",
            texts.any { it.startsWith("user:") && it.contains("尾部 system 注入") },
        )
        // ③-3b：降级为 user 时必须加标记包裹（失去 role 后仍能自证是系统指令）
        assertTrue("降级后应带 <system-reminder> 包裹: $texts", texts.any { it.contains("<system-reminder>") })
    }

    @Test
    fun `messages without system are unaffected`() {
        val messages = listOf(
            UIMessage.user("a"),
            UIMessage.assistant("b"),
        )

        val items = invokeBuildMessages(messages)

        assertEquals(2, items.size)
        assertTrue(itemTexts(items).any { it.startsWith("user:") && it.contains("a") })
        assertTrue(itemTexts(items).any { it.startsWith("assistant:") && it.contains("b") })
    }

}
