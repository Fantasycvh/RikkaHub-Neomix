package app.androidrik.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import me.rerere.bettertext.highlight.HighlightTextColorPalette
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import me.rerere.bettertext.highlight.MAX_HIGHLIGHTED_LENGTH
import kotlin.time.TimeSource

private const val HIGHLIGHT_CACHE_CAPACITY = 64
private const val STREAMING_HIGHLIGHT_INTERVAL_MS = 150L

@OptIn(ExperimentalCoroutinesApi::class)
private val CodeHighlightDispatcher = Dispatchers.Default.limitedParallelism(1)

private data class HighlightCacheKey(
    val code: String,
    val language: String,
    val colors: HighlightTextColorPalette,
    val highlighterId: Int,
)

private data class HighlightRequest(
    val code: String,
    val language: String,
    val colors: HighlightTextColorPalette,
    val streaming: Boolean,
)

private object HighlightCache {
    private val map = object : LinkedHashMap<HighlightCacheKey, AnnotatedString>(
        HIGHLIGHT_CACHE_CAPACITY,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightCacheKey, AnnotatedString>?): Boolean =
            size > HIGHLIGHT_CACHE_CAPACITY
    }

    @Synchronized
    fun get(key: HighlightCacheKey): AnnotatedString? = map[key]

    @Synchronized
    fun put(key: HighlightCacheKey, value: AnnotatedString) {
        map[key] = value
    }
}

/**
 * 异步代码语法高亮（串行后台线程 + LRU 缓存 + 流式前缀平滑续接）。
 *
 * BetterText 的 `CodeHighlighter` 内部维护语言状态机且非线程安全，
 * 因此所有后台高亮任务均通过单并发调度器 [CodeHighlightDispatcher] 串行执行，避免阻塞主线程。
 */
@Composable
internal fun rememberAsyncHighlightedCode(
    code: String,
    language: String,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    streaming: Boolean = false,
): AnnotatedString {
    val highlighter = LocalCodeHighlighter.current
    val normalizedLanguage = remember(language) { language.lowercase() }
    val shouldHighlight = remember(highlighter, normalizedLanguage, code.length) {
        code.isNotEmpty() &&
            code.length <= MAX_HIGHLIGHTED_LENGTH &&
            normalizedLanguage.isNotBlank() &&
            normalizedLanguage != "plaintext" &&
            highlighter.supports(normalizedLanguage)
    }
    if (!shouldHighlight) {
        return remember(code) { AnnotatedString(code) }
    }

    val cacheKey = remember(code, normalizedLanguage, colors, highlighter) {
        HighlightCacheKey(code, normalizedLanguage, colors, System.identityHashCode(highlighter))
    }
    val cached = HighlightCache.get(cacheKey)

    var highlighted by remember(highlighter) { mutableStateOf(cached) }
    var highlightedKey by remember(highlighter) { mutableStateOf(if (cached != null) cacheKey else null) }

    LaunchedEffect(highlighter) {
        var lastHighlightMark: kotlin.time.TimeMark? = null
        val timeSource = TimeSource.Monotonic
        snapshotFlow { HighlightRequest(code, normalizedLanguage, colors, streaming) }
            .distinctUntilChanged()
            .conflate()
            .collect { request ->
                val reqKey = HighlightCacheKey(
                    request.code,
                    request.language,
                    request.colors,
                    System.identityHashCode(highlighter),
                )
                HighlightCache.get(reqKey)?.let { hit ->
                    highlighted = hit
                    highlightedKey = reqKey
                    return@collect
                }
                if (request.streaming) {
                    val elapsed = lastHighlightMark?.elapsedNow()?.inWholeMilliseconds
                    if (elapsed != null && elapsed < STREAMING_HIGHLIGHT_INTERVAL_MS) {
                        delay(STREAMING_HIGHLIGHT_INTERVAL_MS - elapsed)
                    }
                }
                val result = withContext(CodeHighlightDispatcher) {
                    runCatching {
                        highlighter.highlightToAnnotatedString(request.code, request.language, request.colors)
                    }.getOrElse {
                        AnnotatedString(request.code)
                    }
                }
                HighlightCache.put(reqKey, result)
                highlighted = result
                highlightedKey = reqKey
                lastHighlightMark = timeSource.markNow()
            }
    }

    if (cached != null) return cached
    if (highlightedKey == cacheKey && highlighted != null) return highlighted!!
    return remember(code, highlighted) { mergeHighlightedPrefix(code, highlighted) }
}

/**
 * 异步高亮尚未就绪时，保留上一次同前缀的高亮片段并在末尾拼接新字符，避免流式增长时整块闪烁退回纯白文本。
 */
internal fun mergeHighlightedPrefix(code: String, previous: AnnotatedString?): AnnotatedString {
    if (previous == null || previous.text.isEmpty() || !code.startsWith(previous.text)) {
        return AnnotatedString(code)
    }
    if (code.length == previous.text.length) return previous
    return buildAnnotatedString {
        append(previous)
        append(code, previous.text.length, code.length)
    }
}

/**
 * 将整体高亮结果按换行符切分为逐行 [AnnotatedString]，保留跨行或行内的 SpanStyle，
 * 避免带行号自动换行模式对每一行重复调用词法高亮引擎。
 */
internal fun splitAnnotatedStringByLines(text: AnnotatedString): List<AnnotatedString> {
    if (text.isEmpty()) return listOf(AnnotatedString(""))
    val lines = ArrayList<AnnotatedString>()
    var lineStart = 0
    for (index in 0 until text.length) {
        if (text[index] == '\n') {
            lines += text.subSequence(lineStart, index)
            lineStart = index + 1
        }
    }
    lines += text.subSequence(lineStart, text.length)
    return lines
}
