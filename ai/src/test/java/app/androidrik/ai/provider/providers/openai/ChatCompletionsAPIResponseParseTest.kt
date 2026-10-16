package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.KeyRoulette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 响应解析的**畸形输入**回归（上游 #2137 同因，2026-10-16）。
 *
 * 为什么单独一组：这条链跑在**流式增量**上 —— `ChatCompletionsAPI` 对**每个 delta** 都调
 * `parseMessage`，所以这里抛一次异常就是**整轮生成中断**（用户在第三方 OpenAI 兼容中转上
 * 看到的现象就是"偶发中断"）。
 *
 * 本组的核心认知：**`?.` 只防 null 引用，不防 `JsonNull` 这个值**。JSON 里的 `null` 到了
 * kotlinx 手里是**非空引用**，因此 `element.jsonObject["x"]?.jsonObject` 这种写法看着有守卫、
 * 实际照样抛 `JsonNull is not a JsonObject`。下面 `null url_citation` 那条正是这个形态。
 *
 * 与上游保持同一条线：**只跳过"元素/字段本身是 null"**；`type` 缺失、未知注解类型仍**响亮失败**，
 * 上游的修复也没动那两处（见 `docs/upstream-analysis.md` 2026-10-15 同步 C 节）。
 */
class ChatCompletionsAPIResponseParseTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun parse(json: String) = api.parseMessage(Json.parseToJsonElement(json).jsonObject)

    private fun imagesOf(json: String) =
        parse(json).parts.filterIsInstance<UIMessagePart.Image>().map { it.url }

    // ---- 注解：上游 #2137 的第 2、3 条输入 ----

    @Test
    fun `null annotation element is skipped, siblings still parsed`() {
        val message = parse(
            """{"role":"assistant","content":"Hi","annotations":[null,{"type":"url_citation","url_citation":{"title":"T","url":"https://e/x"}}]}"""
        )

        assertEquals("null 元素应被跳过而不是抛异常", 1, message.annotations.size)
        val citation = message.annotations.single() as UIMessageAnnotation.UrlCitation
        assertEquals("https://e/x", citation.url)
        assertEquals("T", citation.title)
    }

    @Test
    fun `null url_citation does not crash`() {
        // 这一条是「?. 不防 JsonNull」的正面证据：`url_citation` 是 JsonNull 而非 null 引用
        val message = parse(
            """{"role":"assistant","content":"Hi","annotations":[{"type":"url_citation","url_citation":null}]}"""
        )

        assertEquals(1, message.annotations.size)
        assertEquals("", (message.annotations.single() as UIMessageAnnotation.UrlCitation).url)
    }

    @Test
    fun `all-null annotations yield empty list`() {
        val message = parse("""{"role":"assistant","content":"Hi","annotations":[null,null]}""")

        assertEquals(0, message.annotations.size)
    }

    @Test
    fun `missing annotations field is still empty`() {
        assertEquals(0, parse("""{"role":"assistant","content":"Hi"}""").annotations.size)
    }

    // ---- Mistral 形态：content 是数组且元素可能为 null ----

    @Test
    fun `mistral content array with null element does not crash`() {
        val message = parse("""{"role":"assistant","content":[null]}""")

        assertEquals(0, message.parts.filterIsInstance<UIMessagePart.Text>().size)
    }

    @Test
    fun `mistral thinking content is still extracted`() {
        // 正向钉子：上面那条改的是「取不到就跳过」，不能顺手把正常提取也一起弄坏
        val message = parse(
            """{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]}"""
        )

        assertEquals(
            "好的",
            message.parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("") { it.reasoning },
        )
    }

    // ---- images：https 不再杀整轮；data: 保持原样 ----

    @Test
    fun `https image url is accepted as is`() {
        // 原先这里是 `require(url.startsWith("data:image"))` ⇒ https（OpenAI 兼容接口最常见的形态）
        // 会抛 IllegalArgumentException 中断整轮
        assertEquals(
            listOf("https://e/a.png"),
            imagesOf("""{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"https://e/a.png"}}]}"""),
        )
    }

    @Test
    fun `data uri image keeps its prefix`() {
        // 原先这里 `substringAfter("data:image/png;base64,")` 会把前缀切掉，存下**裸 base64** ——
        // 而 Base64ImageToLocalFileTransformer 只认 `data:image` 开头、encodeBase64 只认
        // file:/data:/http ⇒ 两边都不认，下一轮发请求时图被静默换成空文本
        assertEquals(
            listOf("data:image/png;base64,QUJD"),
            imagesOf("""{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD"}}]}"""),
        )
    }

    @Test
    fun `unsupported image url is skipped rather than thrown`() {
        assertEquals(
            emptyList<String>(),
            imagesOf("""{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"/sdcard/a.png"}}]}"""),
        )
    }

    @Test
    fun `images entries that are null or of other types are skipped`() {
        assertEquals(
            emptyList<String>(),
            imagesOf(
                """{"role":"assistant","images":[null,{"type":"input_text","text":"x"},{"type":"image_url","image_url":null},{"type":"image_url"}]}"""
            ),
        )
    }
}
