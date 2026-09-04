package app.androidrik.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /** #1812：半截生成失败保留为草稿——消息上方渲染警示横幅（重试走现有 regenerate）。 */
    @Serializable
    @SerialName("partial_failure")
    data object PartialFailure : UIMessageAnnotation()
}
