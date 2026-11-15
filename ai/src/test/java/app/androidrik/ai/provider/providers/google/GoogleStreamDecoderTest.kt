package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.ui.isProviderSigned
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini 流式「显式结束 vs 裸断流」语义回归（审计 AI-1 的 Google 侧）。
 *
 * Gemini 没有独立终止事件：**带 `finishReason` 的末块**才是显式结束。因此
 * `GoogleStreamDecoder.onClosed()` 只有在拿到过 `finishReason` 时才补 `StreamChunk.Finish`；
 * 否则保持「裸断流」语义（不补 Finish），让 `GenerationHandler` 判定 `finishIncomplete`。
 * 回归背景：移植 #1648 初期 onClosed 无条件补 Finish ⇒ 网关提前切连接被当正常收尾，
 * 用户看到戛然而止的回答且不会自动续写。
 */
class GoogleStreamDecoderTest {

    private fun decoder() = GoogleStreamDecoder(responseId = "resp_1", model = "gemini-2.5-pro")

    private fun chunkPayload(
        text: String,
        finishReason: String? = null,
    ): String = buildJsonObject {
        put("candidates", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("content", buildJsonObject {
                    put("parts", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("text", text) })
                    })
                })
                finishReason?.let { put("finishReason", it) }
            })
        })
    }.toString()

    private fun GoogleStreamDecoder.feed(payload: String): List<StreamChunk> =
        accept(SseEvent(data = payload)).chunks

    @Test
    fun `bare eof without finishReason must not emit Finish`() {
        val decoder = decoder()
        val chunks = decoder.feed(chunkPayload("Hi")) + decoder.onClosed()

        // 连接被提前关闭：内容照常送达，但**没有**显式结束标记
        assertEquals("Hi", chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text })
        assertFalse("裸断流不得补 Finish", chunks.any { it is StreamChunk.Finish })

        val handler = StreamChunkHandler()
        chunks.fold(listOf(UIMessage.user("hi"))) { messages, chunk -> handler.handle(messages, chunk) }
        assertFalse("消费端必须能识别未显式结束", handler.finished)
    }

    @Test
    fun `finishReason chunk plus close emits terminal Finish`() {
        val decoder = decoder()
        val chunks = decoder.feed(chunkPayload("Hi", finishReason = "MAX_TOKENS")) + decoder.onClosed()

        val finish = chunks.filterIsInstance<StreamChunk.Finish>().single()
        assertEquals("MAX_TOKENS", finish.finishReason)
        assertEquals("resp_1", finish.responseId)
        // 本仓 helper 归一大小写后识别为输出上限截断（MAX_TOKENS → max_tokens）
        assertTrue(isOutputLimitTruncation(finish.finishReason))
    }

    /**
     * Gemini 新模型会把 thoughtSignature 放进只含签名的独立 part（#2316）：
     * 旧实现会命中 unknown part 分支抛异常；现在应并入前一个函数调用部件，保住续轮签名。
     */
    @Test
    fun `standalone thoughtSignature part merges into preceding tool call`() {
        val payload = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                putJsonObject("functionCall") {
                                    put("name", "workspace_read_file")
                                    putJsonObject("args") { }
                                }
                            })
                            add(buildJsonObject { put("thoughtSignature", "sig-tool") })
                        })
                    })
                    put("finishReason", "STOP")
                })
            })
        }.toString()

        val toolStart = decoder().feed(payload)
            .filterIsInstance<StreamChunk.ToolCallStart>()
            .single()
        assertTrue(
            "独立签名 part 必须并入前一个工具调用",
            toolStart.metadata.toString().contains("sig-tool"),
        )
    }

    @Test
    fun `signed thought text keeps signature metadata for edit gate`() {
        val payload = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", "thinking")
                                put("thought", true)
                                put("thoughtSignature", "sig-reason")
                            })
                        })
                    })
                    put("finishReason", "STOP")
                })
            })
        }.toString()

        val chunks = decoder().feed(payload)
        val reasoningStart = chunks.filterIsInstance<StreamChunk.ReasoningStart>().single()
        assertTrue(
            "签名推理块必须携带 metadata（isProviderSigned 据此禁用编辑）",
            reasoningStart.metadata.toString().contains("sig-reason"),
        )

        // 消费端合并后仍须判定为签名块，编辑入口才会置灰
        val handler = StreamChunkHandler()
        val messages = chunks.fold(listOf(UIMessage.user("hi"))) { list, chunk ->
            handler.handle(list, chunk)
        }
        val reasoning = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue("签名推理块必须命中编辑门禁", reasoning.isProviderSigned())
    }

    @Test
    fun `onClosed is idempotent`() {
        val decoder = decoder()
        decoder.feed(chunkPayload("Hi", finishReason = "STOP"))
        val first = decoder.onClosed()

        assertTrue(first.any { it is StreamChunk.Finish })
        assertTrue("重复 onClosed 不得重复补 Finish", decoder.onClosed().isEmpty())
    }
}
