package app.androidrik.ai.provider.providers.openai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 禁止把 `RequestBody` 丢给 kotlinx 序列化。
 *
 * ## 失效形态（2026-10-13 真机事故，release/debug 都会中）
 *
 * 两个写法都让 app 在**成功路径**上直接失败，报
 * `kotlinx.serialization.SerializationException: Serializer for class 'RequestBody' is not found`：
 *
 * 1. `json.encodeToString(request.body)` —— `request.body` 的类型就是 `okhttp3.RequestBody`；
 * 2. `.post(json.encodeToString(buildChatCompletionRequest(...)).toRequestBody(...))` ——
 *    `.post()` 有接受 `RequestBody` 的重载，Kotlin 会把 `encodeToString` 的**期望类型**推断成
 *    `RequestBody`，于是去序列化 `RequestBody` 本身。
 *
 * 两者都在流式请求的 `buildRequest` / onStart 日志上，所以表现是「每次生成都失败」，
 * 且因为 `ai` 模块的 `ChatCompletionsAPI` 覆盖所有 OpenAI 兼容网关（本仓绝大多数 provider），
 * 影响面是全部对话。
 *
 * ## 局限
 *
 * 源码扫描只能钉住这两个**具体写法**，不是行为证明（真正的行为验证在真机）。
 * 它的价值是：下次有人为了打日志再写一遍 `request.body` 时，单测直接红。
 */
class ChatCompletionsRequestBodySerializationTest {

    private fun source(): String {
        val file = File("src/main/java/app/androidrik/ai/provider/providers/openai/ChatCompletionsAPI.kt")
        assertTrue("源文件不存在：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** 去掉注释与字符串字面量，避免把说明文字/日志文案当成代码判据。 */
    private fun codeOnly(text: String): String = text
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""\"\"\"[\s\S]*?\"\"\""""), "\"\"")
        .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")

    @Test
    fun `never serializes an okhttp RequestBody`() {
        val code = codeOnly(source())
        assertTrue(
            "不许写 encodeToString(*.body)：RequestBody 没有序列化器，会在成功路径上抛异常",
            !Regex("""encodeToString\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\.\s*body\s*\)""").containsMatchIn(code),
        )
        // 危险的是**把请求体构造内联进 `.post(...)`**：`.post()` 有接受 `RequestBody` 的重载，
        // 于是 `encodeToString` 的期望类型被推断成 `RequestBody`，去序列化它本身。
        // 注意 `.post(json.encodeToString(requestBody))`（变量已是显式 JsonObject）是安全的，
        // 不在此列 —— 判据必须窄到只针对「内联构造调用」。
        assertTrue(
            "不许把 buildChatCompletionRequest(...) 内联进 .post(...)：期望类型会被推断成 RequestBody",
            !Regex("""\.post\s*\(\s*json\.encodeToString\s*\(\s*build[A-Za-z0-9_]*\s*\(""")
                .containsMatchIn(code),
        )
    }

    @Test
    fun `request body json is produced from an explicitly typed JsonObject`() {
        val code = codeOnly(source())
        assertTrue(
            "请求体必须先落到显式类型的 JsonObject 局部变量再 encodeToString",
            Regex("""val\s+requestBody\s*:\s*JsonObject\s*=""").containsMatchIn(code),
        )
    }
}
