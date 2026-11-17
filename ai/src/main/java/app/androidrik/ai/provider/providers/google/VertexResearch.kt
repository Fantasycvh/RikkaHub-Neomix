package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.util.parseErrorDetail
import app.androidrik.common.http.jsonArrayOrNull
import app.androidrik.common.http.jsonObjectOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val VERTEX_RESEARCH_AGENT = "deep-research-preview-04-2026"

@Serializable
data class VertexResearchInteraction(
    val id: String,
    val status: String,
    val progress: String? = null,
    val report: String? = null,
    val citations: List<UIMessageAnnotation.UrlCitation> = emptyList(),
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = status in setOf("completed", "failed", "cancelled", "incomplete", "expired", "requires_action")
}

internal fun JsonObject.parseVertexResearch(idFallback: String? = null): VertexResearchInteraction {
    val id = stringOrNull("id") ?: idFallback ?: error("Vertex Deep Research did not return an interaction ID")
    val status = stringOrNull("status") ?: error("Vertex Deep Research did not return a status")
    val steps = this["steps"]?.jsonArrayOrNull.orEmpty().mapNotNull { it.jsonObjectOrNull }
    val output = steps.filter { it.stringOrNull("type") == "model_output" }
        .flatMap { it["content"]?.jsonArrayOrNull.orEmpty() }
        .mapNotNull { it.jsonObjectOrNull }
        .filter { it.stringOrNull("type") == "text" }
    val latestSummary = steps.asReversed().firstNotNullOfOrNull { step ->
        step["summary"]?.jsonArrayOrNull?.mapNotNull { it.jsonObjectOrNull?.stringOrNull("text") }
            ?.joinToString("")?.takeIf { it.isNotBlank() }
    }
    return VertexResearchInteraction(
        id = id,
        status = status,
        progress = latestSummary,
        report = output.mapNotNull { it.stringOrNull("text") }.joinToString("\n\n").takeIf { it.isNotBlank() },
        citations = output.flatMap { it["annotations"].toInteractionsAnnotations() }
            .filterIsInstance<UIMessageAnnotation.UrlCitation>().distinct(),
        error = this["error"]?.parseErrorDetail()?.message,
    )
}
