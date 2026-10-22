package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/** Declarative annotation only: never invokes a JS hook, WebView, network, or storage. */
internal class JsPluginVolatilePatterns(manifest: JsPluginManifest) {
    private val patterns = if (JsHook.ON_INPUT in manifest.hooks) {
        manifest.volatilePatterns.map(::Regex)
    } else {
        emptyList()
    }

    fun spans(transformed: List<UIMessage>): List<List<IntRange>>? {
        if (patterns.isEmpty()) return null
        return transformed.map { message ->
            val text = message.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
            patterns.flatMap { pattern ->
                // Zero-width matches mark no characters; JVM regex ranges are UTF-16 inclusive.
                pattern.findAll(text).map { it.range }.filterNot { it.isEmpty() }.toList()
            }.distinct().sortedWith(compareBy({ it.first }, { it.last }))
        }
    }
}
