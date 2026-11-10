package app.androidrik.ai.provider.providers.claude

import app.androidrik.ai.provider.ClaudePromptCacheTtl
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.providers.ClaudeProvider
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.handleTextGenerationResult
import app.androidrik.ai.util.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claude 思考块「回传保真」回归测试（上游 e1667163c 在本仓的残件验收）。
 *
 * 为什么断言的是**请求体里的 assistant content** 而不是 [UIMessage]：
 * Anthropic 只认「thinking 原文 + 该块完整 signature」逐字匹配；解析 / 合并 / 落盘 / 再构造
 * 任何一步改动了块形状（把相邻块拼接、签名分片只留最后一片、丢掉 redacted 数据），
 * 下一轮就会 400 `Invalid signature` 或 `Expected thinking or redacted_thinking, but found tool_use`，
 * 整个请求被拒。只比对内存里的 part 列表会漏掉「序列化后再构造请求」这一段。
 *
 * 本仓 `763873803` 已覆盖 redacted_thinking 的**非流式**解析与回传（见 ClaudeProviderMessageTest），
 * 本文件补测上游 e1667163c 的另外两处缺口：
 * 1) 非流式相邻 thinking 块在合并层（[UIMessage.mergeDelta]）被拼成一块、只剩后一块签名；
 * 2) 流式 `signature_delta` 分片到达时只保留最后一片（[ClaudeStreamDecoder]）。
 */
class ClaudeThinkingReplayTest {
    private val provider = ClaudeProvider(OkHttpClient())
    private val model = Model(modelId = "claude-test")

    private fun blocks(value: String): JsonArray = json.parseToJsonElement(value).jsonArray

    /**
     * 取「下一次请求里 assistant content 的 blocks」。
     *
     * `buildMessages` 在本仓是 private（沿用 `ClaudeProviderMessageTest` 的反射调用方式）；
     * promptCaching=false，避免插入 cache_control 干扰逐块比对。
     */
    private fun assistantBlocks(messages: List<UIMessage>): List<JsonObject> {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true
        val request = method.invoke(provider, messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
        return request
            .filter { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .flatMap { message -> message.jsonObject.getValue("content").jsonArray.map { it.jsonObject } }
    }

    /** 非流式响应解析入口（private，反射调用）。 */
    private fun parse(content: JsonArray): UIMessage {
        val method = ClaudeProvider::class.java.getDeclaredMethod("parseMessage", JsonArray::class.java)
        method.isAccessible = true
        return method.invoke(provider, content) as UIMessage
    }

    /** 复刻 GenerationHandler 的非流式消费路径：整包结果合并进消息列表。 */
    private fun List<UIMessage>.applyNonStream(content: JsonArray): List<UIMessage> =
        handleTextGenerationResult(
            result = TextGenerationResult(
                id = "resp",
                model = model.modelId,
                message = parse(content),
                finishReason = "end_turn",
            ),
            model = model,
            providerName = "Claude",
        )

    /** 驱动解码器 + 聚合器，结束后做一次序列化往返，模拟「落盘再读出来」。 */
    private fun stream(vararg events: Pair<String, String>): UIMessage {
        val decoder = ClaudeStreamDecoder()
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("first"))
        events.forEach { (name, data) ->
            decoder.accept(SseEvent(event = name, data = data)).chunks.forEach { chunk ->
                messages = handler.handle(messages, chunk)
            }
        }
        val persisted = json.encodeToString(UIMessage.serializer(), messages.last())
        return json.decodeFromString(UIMessage.serializer(), persisted)
    }

    @Test
    fun `non streaming adjacent thinking blocks should keep their own signatures`() {
        val content = blocks(
            """[
            {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]"""
        )

        val messages = listOf(UIMessage.user("first")).applyNonStream(content)

        // 判据：合并层不得把 A/B 拼成一块；A 的签名也不能被 B 覆盖（否则回传时 A 无签名/签名错位）
        assertEquals(content.toList(), assistantBlocks(messages + UIMessage.user("second")))
    }

    @Test
    fun `pause turn continuation should not merge thinking blocks of different responses`() {
        val first = blocks("""[{"type":"thinking","thinking":"Thought A","signature":"sig-A"}]""")
        val second = blocks(
            """[
            {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
            {"type":"text","text":"answer"}
        ]"""
        )

        val afterFirst = listOf(UIMessage.user("first")).applyNonStream(first)
        // pause_turn 续跑要把当前 assistant 响应原样回放：第一块的签名必须在请求里保持完整
        assertEquals(first.toList(), assistantBlocks(afterFirst))

        val afterSecond = afterFirst.applyNonStream(second)

        // 判据：第二次响应追加到同一条 assistant 消息时，两块各自保留（B 的签名不能吃掉 A）
        assertEquals(
            first.toList() + second.toList(),
            assistantBlocks(afterSecond + UIMessage.user("second")),
        )
    }

    @Test
    fun `non streaming redacted thinking should be replayed`() {
        val content = blocks(
            """[
            {"type":"redacted_thinking","data":"opaque-data"},
            {"type":"text","text":"answer"}
        ]"""
        )

        // 本仓 763873803 已实现：解析保留 redactedData、回传还原 redacted_thinking（守住现状）
        assertEquals(
            content.toList(),
            assistantBlocks(listOf(UIMessage.user("first"), parse(content), UIMessage.user("second"))),
        )
    }

    @Test
    fun `streamed signature deltas should accumulate within their block`() {
        val message = stream(
            "content_block_start" to """{"index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"thinking_delta","thinking":"Thought A"}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            "content_block_delta" to """{"index":0,"delta":{"type":"signature_delta","signature":"A"}}""",
            "content_block_stop" to """{"index":0}""",
            "content_block_start" to """{"index":1,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"thinking_delta","thinking":"Thought B"}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"signature_delta","signature":"sig-B"}}""",
            "content_block_stop" to """{"index":1}""",
            "content_block_start" to """{"index":2,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":2,"delta":{"type":"text_delta","text":"answer"}}""",
            "content_block_stop" to """{"index":2}""",
            "message_stop" to "{}",
        )

        // 判据：两块签名各自累加为完整值（sig- + A = sig-A），且不同 block 不互相串
        assertEquals(
            blocks(
                """[
                {"type":"thinking","thinking":"Thought A","signature":"sig-A"},
                {"type":"thinking","thinking":"Thought B","signature":"sig-B"},
                {"type":"text","text":"answer"}
            ]"""
            ).toList(),
            assistantBlocks(listOf(UIMessage.user("first"), message, UIMessage.user("second"))),
        )
    }

    @Test
    fun `streamed redacted thinking should be replayed`() {
        val message = stream(
            "content_block_start" to """{"index":0,"content_block":{"type":"redacted_thinking","data":"opaque-data"}}""",
            "content_block_stop" to """{"index":0}""",
            "content_block_start" to """{"index":1,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"index":1,"delta":{"type":"text_delta","text":"answer"}}""",
            "content_block_stop" to """{"index":1}""",
            "message_stop" to "{}",
        )

        // 判据：流式 redacted 块的 data 必须进 metadata 并在回传时还原（此前流式丢弃，只剩空 thinking）
        assertEquals(
            blocks(
                """[
                {"type":"redacted_thinking","data":"opaque-data"},
                {"type":"text","text":"answer"}
            ]"""
            ).toList(),
            assistantBlocks(listOf(UIMessage.user("first"), message, UIMessage.user("second"))),
        )
    }
}
