package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart

/**
 * view_image：模型按路径把一张已淘汰出上下文的图片**主动拉回**本次请求
 * （借鉴 NMoi D248「View-Image」工具，台账 `docs/references/other projects/nmoi-agent.md` §13.4 候选 1）。
 *
 * 与 [app.androidrik.rikkahub.data.ai.transformers.ContextImageEvictionTransformer] 配对：
 * 淘汰占位文本里带原始路径，模型调本工具传回该路径，结果以 Image part 回填工具输出——
 * 三家 provider 的工具结果都原生支持图片编码（OpenAI `toToolResultContent` / Claude
 * `toToolResultBlock` / Google `toFunctionResponsePart`），不支持的模型自动降级文本占位。
 *
 * 安全边界：路径**只允许本应用私有目录（filesDir 子树）下的存在文件**——占位文本里的
 * 路径来自对话内图片（`filesDir/upload/` 等），模型无从得知也不应该读取私有目录之外
 * 的任何文件。只读、无副作用、可并发（`concurrentSafe = true`）。
 */
fun createViewImageTool(filesDirPath: String): Tool = Tool(
    name = "view_image",
    description = """
        Load an image file back into the conversation context by path.
        Use it when an earlier image was evicted from context (its placeholder text contains
        the original path) or when you need to re-inspect an image the user sent before.
        Pass the exact path shown in the placeholder. Returns the image itself.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Absolute file path (or file:// URI) of the image, as shown in the eviction placeholder")
                }
            },
            required = listOf("path"),
        )
    },
    concurrentSafe = true,
    execute = { args ->
        val rawPath = runCatching {
            args.jsonPrimitiveElseNull("path")
        }.getOrNull()
        if (rawPath == null) {
            listOf(UIMessagePart.Text("Error: missing required parameter \"path\""))
        } else {
            val resolved = app.androidrik.rikkahub.data.ai.transformers.resolveViewableImagePath(
                rawPath = rawPath,
                filesDirPath = filesDirPath,
            )
            if (resolved == null) {
                listOf(
                    UIMessagePart.Text(
                        "Error: \"$rawPath\" is not a readable image inside the app's private storage. " +
                            "Use the exact path from the eviction placeholder."
                    )
                )
            } else {
                listOf(UIMessagePart.Image(url = "file://$resolved"))
            }
        }
    },
)

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveElseNull(key: String): String? {
    val obj = this as? kotlinx.serialization.json.JsonObject ?: return null
    val value = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return value.takeIf { !it.isString || it.content.isNotEmpty() }?.content
}
