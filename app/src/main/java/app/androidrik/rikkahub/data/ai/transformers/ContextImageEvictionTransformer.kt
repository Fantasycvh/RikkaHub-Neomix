package app.androidrik.rikkahub.data.ai.transformers

import android.net.Uri
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import java.io.File

/**
 * 上下文图片淘汰（借鉴 NMoi D248/D270/D272 判据，台账 `docs/references/other projects/nmoi-agent.md` §13.4 候选 1）。
 *
 * 长会话带图只增不减：附件 Image part 随消息**永久重发**，上下文被历史图片撑爆。
 * 本转换器在**请求组装时**把最旧的图片换成一行占位文本（消息与落库不动——只影响本次请求，
 * 与上下文净化同哲学：request-only、可逆），并配 `view_image` 工具让模型按路径把图**主动拉回**
 * 上下文——被淘汰后模型仍可按需取回，等价于 NMoi 的「临时额度」语义（本仓淘汰是每请求重算，
 * view_image 的结果天然是「最近的图片」，下一次请求自动保留，无需额外状态）。
 *
 * 四条约束：
 * 1. **只作用于请求副本**：落库正文与 UI 读原始 messages（同 [QuoteTransformer] 约束 1）。
 * 2. **幂等**：占位符是 Text part，再次 transform 不会被当作图片；每步工具循环重跑无副作用。
 * 3. **计数口径**：全窗口的 Image part（含工具输出里的图——image gen / view_image 的结果）
 *    都计入 [Settings.contextImageLimit]；`file://` 与内联 data:/裸 base64 同样计数。
 * 4. **保留最新的**：按消息顺序从头数到尾，保留最后 N 张；同一消息内多张图按 parts 顺序。
 */
object ContextImageEvictionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = evictOldImages(messages, ctx.settings.contextImageLimit)
}

/** 占位文本格式（view_image 的寻址约定靠它：占位里必须带模型可直接透传的路径）。 */
internal fun evictedImagePlaceholder(imageUrl: String): String {
    val label = when {
        imageUrl.startsWith("file://") -> {
            val path = runCatching { Uri.parse(imageUrl).path }.getOrNull() ?: imageUrl
            val name = path.substringAfterLast('/')
            "$name ($path)"
        }
        imageUrl.startsWith("data:") -> "内联图片"
        else -> "内联图片"
    }
    return "[图片已移出本次上下文：$label。需要查看时调用 view_image 工具并传入该图片的路径。]"
}

/**
 * 淘汰主逻辑（**纯函数**，便于 JVM 单测——`TransformerContext` 需要 Android Context）。
 *
 * [limit] <= 0 = 功能关闭（不淘汰任何图片）。
 */
internal fun evictOldImages(messages: List<UIMessage>, limit: Int): List<UIMessage> {
    if (limit <= 0) return messages
    // 先数总量：不足限额直接原样返回（绝大多数请求走这条零成本路径）
    val total = messages.sumOf { message -> message.parts.count { it is UIMessagePart.Image } }
    if (total <= limit) return messages
    // 从头遍历，前 (total - limit) 张是要淘汰的（即保留最后 limit 张）
    var remainingToEvict = total - limit
    return messages.map { message ->
        if (remainingToEvict <= 0 ||
            message.parts.none { it is UIMessagePart.Image }
        ) {
            return@map message
        }
        val newParts = message.parts.map { part ->
            if (part is UIMessagePart.Image && remainingToEvict > 0) {
                remainingToEvict--
                UIMessagePart.Text(evictedImagePlaceholder(part.url))
            } else {
                part
            }
        }
        message.copy(parts = newParts)
    }
}

/** [ViewImageTool] 的路径校验（**纯函数**）：只允许本应用私有目录（filesDir 子树）下的存在文件。 */
internal fun resolveViewableImagePath(rawPath: String, filesDirPath: String): String? {
    val path = rawPath.trim()
        .removePrefix("file://")
        // view_image 的参数是模型写的，URL 解码防止 %20 之类的转义把路径打断
        .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    if (path.isEmpty()) return null
    val normalized = try {
        File(path).canonicalPath
    } catch (_: Exception) {
        return null
    }
    val root = File(filesDirPath).canonicalPath + File.separator
    if (!normalized.startsWith(root)) return null
    val file = File(normalized)
    return if (file.isFile && file.exists()) normalized else null
}
