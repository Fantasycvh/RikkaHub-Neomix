package app.androidrik.rikkahub.data.files

import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException

/**
 * 模型输出里 base64 图片的**保存前清洗**（纯逻辑，可 JVM 单测；[FilesManager] 只做 IO）。
 *
 * 背景（P1-6）：`ConversationRepository.conversationToConversationEntity` 有
 * `require(... none { it.hasBase64Part() })` —— 只要消息里还留着 `data:` 图片，**整条会话
 * 都写不进去**（用户看到回复，重启后消失）。旧实现遇到超限 base64 时「保留原 part 避免
 * decode OOM」，恰好把 `data:` 留到了落库那一刻。这里改成：
 *
 * 1. 超限 / 非图片 data URI / 解码或落盘失败 ⇒ 用**可见占位文本**替换该 part，文本照常落库；
 * 2. `UIMessagePart.Tool.output` 里嵌套的图片同样递归处理（D3：工具结果里的图）；
 * 3. 取消（[CancellationException]）原样上抛，不能被吞成占位。
 */

/** base64 payload（不含 `data:...;base64,` 前缀）的字符数上限，约 20MB base64。 */
internal const val MAX_BASE64_IMAGE_CHARS = 20_000_000

private const val BASE64_MARKER = ";base64,"

/** 图片被省略时的可见占位文本（显式失败：用户能看到，整轮回复也能落库）。 */
internal enum class Base64ImageOmitReason(val placeholder: String) {
    TooLarge("[Image omitted: too large to save]"),
    Unsupported("[Image omitted: unsupported data URI]"),
    ConversionFailed("[Image omitted: failed to save]"),
}

/**
 * 递归清洗 [parts] 里的 base64 图片。
 *
 * @param convert 把 base64 payload 转成本地文件 URL；返回 null 视为转换失败。
 *   它抛出的异常（除 [CancellationException]）同样降级为占位。
 * @param onOmit 省略回调（用于 logcat / 应用内日志），只带原因、不带图片内容。
 * @param maxPayloadChars 仅在测试里注入小上限用；生产调用走默认值。
 */
internal suspend fun sanitizeBase64ImageParts(
    parts: List<UIMessagePart>,
    convert: suspend (base64Payload: String) -> String?,
    onOmit: (Base64ImageOmitReason) -> Unit = {},
    maxPayloadChars: Int = MAX_BASE64_IMAGE_CHARS,
): List<UIMessagePart> = parts.map { part ->
    when (part) {
        is UIMessagePart.Image -> sanitizeBase64ImagePart(part, convert, onOmit, maxPayloadChars)
        is UIMessagePart.Tool -> part.copy(
            output = sanitizeBase64ImageParts(part.output, convert, onOmit, maxPayloadChars),
        )
        else -> part
    }
}

private suspend fun sanitizeBase64ImagePart(
    part: UIMessagePart.Image,
    convert: suspend (String) -> String?,
    onOmit: (Base64ImageOmitReason) -> Unit,
    maxPayloadChars: Int,
): UIMessagePart {
    // 非 data: 图片（本地 file:// / http(s)://）保持原样
    if (!part.url.startsWith("data:")) return part

    val markerIndex = part.url.indexOf(BASE64_MARKER)
    if (!part.url.startsWith("data:image") || markerIndex < 0) {
        return omitted(Base64ImageOmitReason.Unsupported, onOmit)
    }

    val payloadStart = markerIndex + BASE64_MARKER.length
    // **先按 URL 长度判超限，再 substring**：substring 会先把整段 base64 复制进新字符串，
    // 对刻意的大 payload（>20MB）等于白占一份内存，判断要放在复制之前。
    if (part.url.length - payloadStart > maxPayloadChars) {
        return omitted(Base64ImageOmitReason.TooLarge, onOmit)
    }

    val localUrl = try {
        convert(part.url.substring(payloadStart))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return if (localUrl.isNullOrBlank()) {
        omitted(Base64ImageOmitReason.ConversionFailed, onOmit)
    } else {
        part.copy(url = localUrl)
    }
}

private fun omitted(
    reason: Base64ImageOmitReason,
    onOmit: (Base64ImageOmitReason) -> Unit,
): UIMessagePart.Text {
    onOmit(reason)
    return UIMessagePart.Text(reason.placeholder)
}
