package app.androidrik.rikkahub.utils

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * 「会话内图片」的收集与定位（全屏预览左右切换的唯一依据）。
 *
 * 抽成纯函数是为了单测：这里一旦算错，全屏查看器就会从错误的图片开始，或者把
 * 工具输出里的图（URL 语义不同）混进同一条轮播里。
 */

/**
 * 收集同一会话内所有可预览图片的 URL：按消息顺序、去重保序。
 *
 * 只收**顶层** [UIMessagePart.Image]：
 * - 工具输出（`UIMessagePart.Tool.output`）里的图片不在此列——它们的 URL 由各自的
 *   工具自定义（生图工具是 `images/xxx.png` 这类相对路径，与其他渲染点不共享匹配语义），
 *   混进来会让「点开的图」和「轮播到的那页」对不上；
 * - 空白 URL（流式生成中尚未落盘的占位 Image part）跳过，避免查看器出现空槽位。
 */
fun collectConversationImageUrls(messages: List<UIMessage>): List<String> {
    val urls = LinkedHashSet<String>()
    messages.forEach { message ->
        message.parts.forEach { part ->
            if (part is UIMessagePart.Image && part.url.isNotBlank()) {
                urls.add(part.url)
            }
        }
    }
    return urls.toList()
}

/**
 * 点击 [url] 时全屏查看器要翻的图片集合：
 * - 该 URL 属于会话图片列表（且列表里不止一张）⇒ 整个会话的图片，可左右切换；
 * - 否则退化为单图（工具输出图、Markdown 内嵌图、HTTP 图等保持原有单图预览行为）。
 */
fun imagePreviewPages(group: List<String>, url: String?): List<String> {
    if (url.isNullOrBlank()) return emptyList()
    return if (group.size > 1 && group.contains(url)) group else listOf(url)
}

/**
 * 点击 [url] 时全屏查看器从第几页开始。
 *
 * 不在 [images] 里（单图预览、URL 被归一化后仍对不上等）时回到第 0 页——
 * 退化单图预览时 [images] 只有一张，越界的下标会让 pager 直接崩，必须夹住。
 */
fun imagePreviewStartIndex(images: List<String>, url: String?): Int {
    if (images.isEmpty() || url == null) return 0
    val index = images.indexOf(url)
    return if (index in images.indices) index else 0
}
