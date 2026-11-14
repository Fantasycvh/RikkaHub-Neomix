package app.androidrik.ai.tools.ocr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek OCR API（2026-11-13 Tool API）的响应模型与 prompt 组装纯函数。
 *
 * 契约（配料 01 §4）：
 * - `pages[].text` 是模型原始输出、**未清洗**，可能含 `<｜ref｜>/<｜box｜>` 特殊 token；
 * - `finish_reason`：`stop` 正常；`length` = 该页输出触顶、文本不完整；
 * - `usage` 仅供参照，不参与计费（计费按成功的 `page_count`）。
 *
 * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/ocr/
 */
@Serializable
data class DeepSeekOcrResult(
    val id: String = "",
    @SerialName("page_count") val pageCount: Int = 0,
    val pages: List<DeepSeekOcrPage> = emptyList(),
    val usage: DeepSeekOcrUsage? = null,
)

@Serializable
data class DeepSeekOcrPage(
    val index: Int = 0,
    val text: String = "",
    @SerialName("finish_reason") val finishReason: String = "",
)

@Serializable
data class DeepSeekOcrUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/** 逐页识别输出的坐标标记（全角竖线 ｜ 是官方原文的一部分，勿改半角） */
private val REF_TOKEN = Regex("<｜ref｜>.*?<｜/ref｜>", RegexOption.DOT_MATCHES_ALL)
private val BOX_TOKEN = Regex("<｜box｜>.*?<｜/box｜>", RegexOption.DOT_MATCHES_ALL)

/** 剥离 `<｜ref｜>/<｜box｜>` 布局 token，只留文本；直接抽文字的文档没有这些 token。 */
fun stripLayoutTokens(text: String): String =
    text.replace(REF_TOKEN, "").replace(BOX_TOKEN, "").trim()

/**
 * 把逐页识别结果组装成注入 prompt 的文本。
 *
 * - 页码从 1 开始（`pages[].index` 是 0 基）；
 * - [includeLayout] 为 true 时保留 ref/box 坐标 token（本仓暂无消费位，默认剥掉）；
 * - `finish_reason=length` 的页前加「可能不完整」标注——截断的文本不标注会让模型
 *   把残缺内容当完整事实用。
 */
fun toPromptText(pages: List<DeepSeekOcrPage>, includeLayout: Boolean = false): String {
    if (pages.isEmpty()) return ""
    return pages.joinToString("\n\n") { page ->
        val body = if (includeLayout) page.text.trim() else stripLayoutTokens(page.text)
        val header = "---Page ${page.index + 1}:"
        val truncated = if (page.finishReason == "length") {
            "$header\n[本页识别输出触顶，文本可能不完整]\n"
        } else {
            header
        }
        "$truncated\n$body"
    }
}
