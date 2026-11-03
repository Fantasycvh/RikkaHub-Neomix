package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式解码器图片 data URI 解析回归。
 *
 * 历史 bug（2026-10-16 非流式已修、流式漏了）：
 * 1. `require(url.startsWith("data:image"))` 遇到中转返回的 https 图片直接抛，整轮生成中断；
 * 2. `substringAfter("data:image/png;base64,")` 对 jpeg/webp 的 data URI 会切成裸 base64，
 *    transformer 与 encodeBase64 都不认，图片在下一轮被静默换成空文本。
 *
 * 现在的语义与非流式路径对齐：`data:` / `http(s)` 原样存，其它形态跳过而不抛。
 */
class ChatCompletionsStreamDecoderImageTest {

    private fun parse(payload: String) =
        ChatCompletionsStreamDecoder().parseMessage(Json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `jpeg data uri is preserved intact`() {
        val message = parse(
            """{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QUJD"}}]}"""
        )
        val images = message.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("data:image/jpeg;base64,QUJD", images.single().url)
    }

    @Test
    fun `https image url is preserved instead of throwing`() {
        val message = parse(
            """{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"https://example.com/a.png"}}]}"""
        )
        val images = message.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("https://example.com/a.png", images.single().url)
    }

    @Test
    fun `unsupported shape is skipped instead of aborting`() {
        val message = parse(
            """{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"file:///tmp/a.png"}}]}"""
        )
        assertTrue(message.parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
    }
}
