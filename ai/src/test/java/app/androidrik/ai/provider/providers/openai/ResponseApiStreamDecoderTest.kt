package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.ReasoningType
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.util.HttpException
import app.androidrik.ai.util.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResponseApiStreamDecoder] 的回归测试（移植自上游 #1648
 * `ai/src/test/java/me/rerere/ai/provider/providers/openai/ResponseApiStreamDecoderTest.kt`）。
 *
 * 解码器 + [StreamChunkHandler] 的断言整体保留。以下三处依赖本仓/上游差异被裁剪，核心断言不受影响：
 * - 上游非流式入口是 `ResponseAPI.parseResponseOutput(...)` 返回 `MessageChunk`，本仓返回
 *   `TextGenerationResult` ⇒ 改为断言 `result.message.parts`；
 * - 上游 `buildMessages` 不做 `isValidToUpload` 过滤，本仓会过滤 ⇒「纯 metadata 推理 item」
 *   的多轮回传断言在本仓不成立（属既有仓库差异，不在此测试范围）；
 * - 本仓 `addReasoningItem` 对已加密推理**不回放明文**（#1719）：只有 `encrypted_content` 时
 *   不写 `summary`/`content` 数组，故去掉上游「raw/summary 回传形状」的断言。
 */
class ResponseApiStreamDecoderTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `incomplete response should preserve terminal metadata and usage`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(
            SseEvent(
                event = "response.incomplete",
                data = json.encodeToString(
                    buildJsonObject {
                        put("type", "response.incomplete")
                        put("response", buildJsonObject {
                            put("id", "resp_incomplete")
                            put("model", "test-model")
                            put("status", "incomplete")
                            put("incomplete_details", buildJsonObject {
                                put("reason", "max_output_tokens")
                            })
                            put("usage", buildJsonObject {
                                put("input_tokens", 10)
                                put("output_tokens", 20)
                                put("total_tokens", 30)
                                put("input_tokens_details", buildJsonObject {
                                    put("cached_tokens", 4)
                                })
                            })
                        })
                    }
                ),
            )
        )

        assertTrue(result.completed)
        assertEquals(
            TokenUsage(promptTokens = 10, completionTokens = 20, cachedTokens = 4, totalTokens = 30),
            (result.chunks[0] as StreamChunk.Usage).usage,
        )
        assertEquals(
            StreamChunk.Finish(
                finishReason = "incomplete:max_output_tokens",
                responseId = "resp_incomplete",
                model = "test-model",
            ),
            result.chunks[1],
        )
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `failed response should terminate with provider error`() {
        val decoder = ResponseApiStreamDecoder()

        val exception = assertThrows(HttpException::class.java) {
            decoder.accept(
                SseEvent(
                    event = "response.failed",
                    data = json.encodeToString(
                        buildJsonObject {
                            put("type", "response.failed")
                            put("response", buildJsonObject {
                                put("id", "resp_failed")
                                put("status", "failed")
                                put("error", buildJsonObject {
                                    put("code", "server_error")
                                    put("message", "Upstream generation failed")
                                })
                            })
                        }
                    ),
                )
            )
        }

        assertEquals("Upstream generation failed", exception.message)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `reasoning item without summary should preserve encrypted content`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is StreamChunk.ReasoningStart)
        assertTrue(chunks[1] is StreamChunk.ReasoningEnd)

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertFalse(messages.last().isValidToUpload())
    }

    @Test
    fun `reasoning item should keep final metadata after summary done event`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("item_id", "rs_test")
                put("summary_index", 0)
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertEquals("summary", reasoningParts.single().reasoning)
        assertEquals(
            "encrypted",
            reasoningParts.single().metadataAs<OpenAIReasoningMetadata>()?.encryptedContent,
        )
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoningParts.single().reasoningType)
    }

    @Test
    fun `raw reasoning and summary with the same index should remain distinct`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_text.delta")
                put("item_id", "rs_test")
                put("content_index", 0)
                put("delta", "raw")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(2, reasoningParts.size)
        assertEquals(
            "raw",
            reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning,
        )
        assertEquals(
            "summary",
            reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning,
        )
        // 本仓只回传 encrypted_content（#1719 不回放明文），故仅断言多轮形状仍是一个 reasoning item
        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `web search events should produce one completed server tool`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", webSearchItem("in_progress"))
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.searching")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.completed")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", webSearchItem("completed"))
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("ws_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("search", tool.input?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(
            "web_search_call",
            tool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `non streaming response should parse web search without synthetic output`() {
        val result = api.parseResponseOutput(
            buildJsonObject {
                put("id", "resp_1")
                put("model", "test-model")
                put("status", "completed")
                put("output", buildJsonArray { add(webSearchItem("completed")) })
            }
        )

        val tool = result.message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(null, tool.output)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should preserve metadata-only reasoning`() {
        val result = api.parseResponseOutput(
            buildJsonObject {
                put("id", "resp_1")
                put("model", "test-model")
                put("status", "completed")
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", "rs_test")
                        put("encrypted_content", "encrypted")
                        put("summary", buildJsonArray {})
                        put("content", buildJsonArray {})
                    })
                })
            }
        )

        val reasoning = result.message.parts.single() as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
    }

    @Test
    fun `non streaming response should distinguish raw reasoning and summary`() {
        val result = api.parseResponseOutput(
            buildJsonObject {
                put("id", "resp_1")
                put("model", "test-model")
                put("status", "completed")
                put("output", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning")
                        put("id", "rs_test")
                        put("encrypted_content", "encrypted")
                        put("summary", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "summary_text")
                                put("text", "summary")
                            })
                        })
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "reasoning_text")
                                put("text", "raw")
                            })
                        })
                    })
                })
            }
        )

        val reasoningParts = result.message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoningParts.size)
        assertEquals(
            "raw",
            reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning,
        )
        assertEquals(
            "summary",
            reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning,
        )
        reasoningParts.forEach {
            assertEquals("rs_test", it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
            assertEquals("encrypted", it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        }
    }

    private fun webSearchItem(status: String) = buildJsonObject {
        put("type", "web_search_call")
        put("id", "ws_1")
        put("status", status)
        put("action", buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
        })
    }

    private fun reasoningItemEvent(type: String, encryptedContent: String? = null) = buildJsonObject {
        put("type", type)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_test")
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: JsonObject): List<StreamChunk> =
        accept(
            SseEvent(
                event = payload["type"]?.jsonPrimitive?.content,
                data = json.encodeToString(payload),
            )
        ).chunks
}
