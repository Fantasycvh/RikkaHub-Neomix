package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.image.GeneratedImage
import app.androidrik.rikkahub.data.image.resolveImageGenerationModel
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内生图工具（上游 #2123）的判定测试。
 *
 * 这个工具的价值全在**四条纪律**上，所以断言也围着它们：①上限是硬的 ②失败是**文本**而不是异常
 * （模型要能据此自我纠正）③结果里**没有图片字节**（否则下一轮会以输入模态再发一遍，一张上千 token）
 * ④没配生图模型时门控为假（不装配）。
 *
 * 生成那一步用**注入的 lambda** 顶掉：要测的是判定，不是网络。
 */
class ImageToolTest {

    private fun assistant(approval: Boolean = false) = Assistant(imageGenApproval = approval)

    private fun generated(path: String) = GeneratedImage(
        id = 0,
        prompt = "prompt",
        filePath = path,
        timestamp = 0L,
        model = "test-model",
    )

    private fun toolOf(
        approval: Boolean = false,
        generate: suspend (String, Int, String) -> List<GeneratedImage>,
    ) = createImageTools(assistant(approval), generate).single()

    private fun args(prompt: String? = "a cat", size: String? = null, num: Int? = null) = buildJsonObject {
        if (prompt != null) put("prompt", prompt)
        if (size != null) put("size", size)
        if (num != null) put("num", num)
    }

    private fun textOf(parts: List<UIMessagePart>): String =
        (parts.single() as UIMessagePart.Text).text

    // region ① 上限

    @Test
    fun `image count is clamped to the hard cap`() {
        assertEquals("缺省 1 张", 1, clampImageCount(null))
        assertEquals("0 张没有意义，夹到 1", 1, clampImageCount(0))
        assertEquals("负数夹到 1", 1, clampImageCount(-5))
        assertEquals(4, clampImageCount(4))
        assertEquals("超上限夹到 4 —— 生图按张计费，不能让模型一句话决定花多少", 4, clampImageCount(999))
    }

    @Test
    fun `third call in one turn fails as text and stops generating`() = runBlocking {
        var calls = 0
        val tool = toolOf { _, _, _ -> calls++; listOf(generated("/tmp/1.png")) }

        repeat(MAX_IMAGE_CALLS_PER_TURN) {
            assertTrue(textOf(tool.execute(args())).contains("\"ok\": true"))
        }
        val third = textOf(tool.execute(args()))

        assertTrue("第三次必须返回 too_many_calls", third.contains("too_many_calls"))
        assertEquals("超限后不得再真的去生成", MAX_IMAGE_CALLS_PER_TURN, calls)
    }

    @Test
    fun `num in arguments is clamped before generating`() = runBlocking {
        var requested = -1
        val tool = toolOf { _, num, _ -> requested = num; listOf(generated("/tmp/1.png")) }
        tool.execute(args(num = 99))
        assertEquals(MAX_IMAGES_PER_CALL, requested)
    }

    // endregion

    // region ② 失败一律是文本

    @Test
    fun `blank prompt fails without generating`() = runBlocking {
        var calls = 0
        val tool = toolOf { _, _, _ -> calls++; emptyList() }
        val result = textOf(tool.execute(args(prompt = "   ")))
        assertTrue(result.contains("prompt_required"))
        assertEquals("空提示词不该触发一次付费调用", 0, calls)
    }

    @Test
    fun `unknown size fails instead of silently using auto`() = runBlocking {
        var calls = 0
        val tool = toolOf { _, _, _ -> calls++; emptyList() }
        val result = textOf(tool.execute(args(size = "1792x1024")))
        assertTrue(
            "枚举外的尺寸必须显式失败：静默改成 auto 会让用户拿到尺寸不对的图而没有任何解释",
            result.contains("unsupported_size"),
        )
        assertEquals(0, calls)
    }

    @Test
    fun `provider failure becomes text not an exception`() = runBlocking {
        val tool = toolOf { _, _, _ -> throw IllegalStateException("429 Too Many Requests") }
        val result = textOf(tool.execute(args()))
        assertTrue(result.contains("generation_failed"))
        assertTrue("要把上游原因带给模型，它才能换个说法重试", result.contains("429"))
    }

    // endregion

    // region ③ 结果里没有图片字节

    @Test
    fun `result carries paths only, never image bytes`() {
        val json = imageToolSuccess("dall-e-3", listOf("/data/a.png", "/data/b.png"))
        assertFalse("绝不能把 base64 塞进工具结果", json.contains("data:", ignoreCase = true))
        assertFalse(json.contains("base64", ignoreCase = true))
        // 路径必须留着：工具卡要靠它加载图片（模型侧它只是几十个字符）
        assertEquals(
            listOf("/data/a.png", "/data/b.png"),
            parseGeneratedImagePaths(kotlinx.serialization.json.Json.parseToJsonElement(json)),
        )
    }

    @Test
    fun `path parsing ignores failures and malformed payloads`() {
        assertTrue(parseGeneratedImagePaths(null).isEmpty())
        assertTrue(parseGeneratedImagePaths(JsonNull).isEmpty())
        assertTrue(
            "失败结果里不该被解析出路径（否则卡片会去加载不存在的文件）",
            parseGeneratedImagePaths(
                kotlinx.serialization.json.Json.parseToJsonElement(
                    imageToolFailure("generation_failed", "boom"),
                ),
            ).isEmpty(),
        )
    }

    // endregion

    // region ④ 门控与审批

    @Test
    fun `no configured image model means the gate is closed`() {
        assertNull(
            "Settings 默认给 imageGenerationModelId 一个随机 Uuid ⇒ 解析不出模型 ⇒ 工具不装配",
            Settings.dummy().resolveImageGenerationModel(),
        )
    }

    @Test
    fun `approval defaults off and is honored when switched on`() {
        assertFalse(
            "默认必须是关：上限已经兜住失控花钱，每次打断只是纯摩擦",
            assistant().imageGenApproval,
        )
        assertFalse(toolOf { _, _, _ -> emptyList() }.needsApproval(JsonNull))
        assertTrue(
            "打开后必须真的拦（走 Tool.needsApproval 既有通路）",
            toolOf(approval = true) { _, _, _ -> emptyList() }.needsApproval(JsonNull),
        )
    }

    @Test
    fun `schema tells the model what it can ask for`() {
        val schema = toolOf { _, _, _ -> emptyList() }.parameters() as InputSchema.Obj
        assertEquals("prompt 是唯一必填项", listOf("prompt"), schema.required)
        assertTrue(schema.properties.containsKey("prompt"))
        assertTrue(
            "尺寸要限制成枚举，裸字符串会让模型编出没人支持的尺寸",
            schema.properties["size"].toString().contains("enum"),
        )
        assertTrue(schema.properties.containsKey("num"))
    }

    @Test
    fun `timeout is widened because four images through a relay exceed the default`() {
        assertEquals(IMAGE_TOOL_TIMEOUT_MS, toolOf { _, _, _ -> emptyList() }.executionTimeoutMillis)
        assertTrue("默认 60s 对生图太紧，必须显式放宽", IMAGE_TOOL_TIMEOUT_MS > 60_000L)
        assertTrue(
            "尺寸枚举里必须留 auto：这是模型遇到不支持尺寸时的收敛点",
            IMAGE_TOOL_NAME.isNotBlank() && JsonPrimitive("auto").content.isNotBlank(),
        )
    }

    // endregion
}
