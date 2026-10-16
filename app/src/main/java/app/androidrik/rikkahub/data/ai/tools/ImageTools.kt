package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.image.GeneratedImage
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstantPretty
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 会话内生图工具（上游 #2123）。设计与取舍见 `docs/design/design-agent-image-tool.md`。
 *
 * 三件事必须一起看，否则很容易实现成"能生但把上下文吃光"：
 *
 * 1. **工具结果里只有一行 JSON，没有图片字节**。图片落盘后进图库，会话流里由工具卡
 *    （`GenerateImageToolUI`）从**本地路径**渲染。若把 base64 塞进工具结果，它就成了对话
 *    历史的一部分，**下一轮会被当输入模态再发一遍**——一张图上千 token，几次就爆。
 * 2. **用设置里的生图模型**（`settings.imageGenerationModelId`），不是当前 chat 模型。
 *    所以 chat 模型不支持生图也能用；反之"没配生图模型"时本工具**根本不该被装配**
 *    （门控在 `ChatToolFactory.createToolSegments`，判定用 [ImageGenerationRunner.resolveModel]）。
 * 3. **上限是硬的**：单次张数与单轮次数都封顶。生图按张计费、模型又可能自主连生，
 *    上限不能依赖模型自觉。
 */
internal const val IMAGE_TOOL_NAME = "generate_image"

/** 单次调用最多几张。与生图页的既有产品上限一致（`ImgGenVM` 里 `coerceIn(1, 4)`）。 */
internal const val MAX_IMAGES_PER_CALL = 4

/**
 * 单轮生成最多调用几次。
 *
 * 为什么不是"不限制"：一次回复里模型很容易变成"再试一张"直到满意，而每一次都是真金白银。
 * 取 2 而不是 1：允许"第一张不满意 → 改一次提示词"，这恰好是正常创作的最短回路。
 */
internal const val MAX_IMAGE_CALLS_PER_TURN = 2

/**
 * 外层执行上限显式放宽到 180s（默认 60s）。
 *
 * 理由与 `workspace_shell` 同款：**自管时长的工具必须显式声明**，否则会被外层 `withTimeout`
 * 误杀。生图是"一次请求四张 + 中转站排队"，60s 太紧；超时会被转成工具错误输出，模型据此
 * 自我纠正，而不是整轮一直挂着。
 */
internal const val IMAGE_TOOL_TIMEOUT_MS = 180_000L

/**
 * 暴露给模型的尺寸枚举。刻意只给 5 个（`ImageGenSize` 有 8 个）。
 *
 * 依据：这几个是各家 images 接口支持面最大的；其余（1792 系）留给生图页的高级用法。
 * 模型给了枚举外的值时**返回错误而不是静默改成 auto**——静默回退会让用户拿到一张
 * 尺寸不对的图而没有任何解释。
 */
private val IMAGE_TOOL_SIZES = listOf("auto", "1024x1024", "1536x1024", "1024x1536", "512x512")

/** 张数夹取：缺省 1，越界夹到 [1, [MAX_IMAGES_PER_CALL]]。抽成纯函数以便单测直接覆盖生产判定。 */
internal fun clampImageCount(requested: Int?): Int = (requested ?: 1).coerceIn(1, MAX_IMAGES_PER_CALL)

/** 失败一律是**正常返回的文本**（不是异常）——模型要能据此自我纠正。 */
internal fun imageToolFailure(error: String, hint: String): String = JsonInstantPretty.encodeToString(
    buildJsonObject {
        put("ok", false)
        put("error", error)
        put("hint", hint)
    }
)

/**
 * 成功结果。**只带本地路径**：卡片靠它加载图片，模型靠 `count` 知道成了几张。
 *
 * `note` 是刻意写给模型看的——不说的话它会以为自己能看到图，然后开始描述一张它没见过的图。
 */
internal fun imageToolSuccess(modelName: String, paths: List<String>): String = JsonInstantPretty.encodeToString(
    buildJsonObject {
        put("ok", true)
        put("count", paths.size)
        put("model", modelName)
        put("images", buildJsonArray { paths.forEach { add(buildJsonObject { put("path", it) }) } })
        put(
            "note",
            "The image(s) are shown to the user in the conversation. " +
                "Their content is NOT returned to you, so do not describe details you cannot see.",
        )
    }
)

/**
 * 从工具结果里取出图片路径——[imageToolSuccess] 的**逆函数**，两个方向必须成对改动。
 *
 * 为什么放在数据层而不是渲染器里：它是结果格式的一部分（格式变了这里必须跟着变），
 * 而且是**纯函数**，可以像 `memoryToolNeedsApproval` 那样直接单测生产判定，
 * 不必把 Compose 拉进单测。
 */
internal fun parseGeneratedImagePaths(content: JsonElement?): List<String> {
    val obj = content as? JsonObject ?: return emptyList()
    if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
    return obj["images"]?.let { it as? JsonArray }?.mapNotNull { item ->
        (item as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull
    }.orEmpty()
}

/**
 * 生图工具。**调用方（[ChatToolFactory]）必须已确认配置了可用的生图模型**——
 * 本函数不做门控，只负责工具本身。
 *
 * `needsApproval` 由助手的 [Assistant.imageGenApproval] 决定（默认关 = 零摩擦）。
 * 单轮计数是**闭包内的一个 var**：工具装配每轮生成一次、生成循环内复用同一份 `List<Tool>`，
 * 所以这个作用域天然等于"本次生成"。
 */
internal fun createImageTools(
    assistant: Assistant,
    /**
     * 真正去生成的那一步（由调用方注入）。
     *
     * **为什么是 lambda 而不是 `ImageGenerationRunner`**：本函数要测的是"上限、校验、结果形状"
     * 这些**判定**，而注入 lambda 让测试可以完全离线地跑它们（同 `buildMemoryTools` 的
     * onCreation/onUpdate 注入法）。直接依赖 runner 则每个断言都要先接上 ProviderManager、文件系统
     * 与数据库——那时测的就成了外部世界，不是本工具的判定。
     */
    generate: suspend (prompt: String, numOfImages: Int, size: String) -> List<GeneratedImage>,
): List<Tool> {
    var callsThisTurn = 0
    return listOf(
        Tool(
            name = IMAGE_TOOL_NAME,
            description = """
                Generate image(s) from a text description and show them to the user in this conversation.
                Uses the image generation model configured in settings (not the current chat model).
                The user sees the images immediately; you do not receive their content back, so do not
                describe the result in detail. Use this when the user asks for a picture, illustration,
                diagram-like artwork, avatar, wallpaper, or similar. At most $MAX_IMAGES_PER_CALL images
                per call and $MAX_IMAGE_CALLS_PER_TURN calls per turn.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "What to draw. Be specific about subject, style, composition and lighting."
                            )
                        })
                        put("size", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray { IMAGE_TOOL_SIZES.forEach { add(JsonPrimitive(it)) } })
                            put("description", "Output size; default auto (let the model choose).")
                        })
                        put("num", buildJsonObject {
                            put("type", "integer")
                            put("description", "How many images, 1-$MAX_IMAGES_PER_CALL (default 1).")
                        })
                    },
                    required = listOf("prompt"),
                )
            },
            needsApproval = { assistant.imageGenApproval },
            executionTimeoutMillis = IMAGE_TOOL_TIMEOUT_MS,
            execute = { input ->
                val args = input.jsonObject
                val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
                val requestedNum = args["num"]?.jsonPrimitive?.intOrNull

                val result = when {
                    prompt.isBlank() -> imageToolFailure(
                        error = "prompt_required",
                        hint = "Provide a non-empty `prompt` describing the image to generate.",
                    )

                    size != null && size !in IMAGE_TOOL_SIZES -> imageToolFailure(
                        error = "unsupported_size",
                        hint = "Supported sizes: ${IMAGE_TOOL_SIZES.joinToString(", ")}.",
                    )

                    callsThisTurn >= MAX_IMAGE_CALLS_PER_TURN -> imageToolFailure(
                        error = "too_many_calls",
                        hint = "At most $MAX_IMAGE_CALLS_PER_TURN image generations per turn. " +
                            "Use the images already generated, or ask the user.",
                    )

                    else -> {
                        callsThisTurn++
                        val count = clampImageCount(requestedNum)
                        runCatching {
                            generate(prompt, count, size ?: "auto")
                        }.fold(
                            onSuccess = { images ->
                                if (images.isEmpty()) {
                                    imageToolFailure(
                                        error = "no_image_returned",
                                        hint = "The provider returned no image. Try a different prompt.",
                                    )
                                } else {
                                    imageToolSuccess(
                                        modelName = images.first().model,
                                        paths = images.map { it.filePath },
                                    )
                                }
                            },
                            onFailure = { error ->
                                imageToolFailure(
                                    error = "generation_failed",
                                    hint = error.message ?: "Unknown error from the image provider.",
                                )
                            },
                        )
                    }
                }
                listOf(UIMessagePart.Text(result))
            },
        )
    )
}
