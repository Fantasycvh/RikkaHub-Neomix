package app.androidrik.rikkahub.ui.pages.setting.search

import app.androidrik.rikkahub.Screen

/**
 * 一条可被全局搜索命中的设置项。
 *
 * [title] / [description] / [pageTitle] / [section] 都是**字符串资源 id** 而不是文本：
 * 搜索必须在当前语言下做（中文包搜「主题」、英文包搜 "theme" 都要能命中），
 * 同时结果列表要能显示本地化标题。
 *
 * [route] 是跳转目标。v1 只跳到页面，不滚动到具体条目——设置页的条目索引不稳定，
 * 强行滚动需要逐页接锚点，收益不抵成本（见 docs/design/design-settings-search.md）。
 */
data class SettingSearchEntry(
    val id: String,
    val title: Int,
    val description: Int? = null,
    val pageTitle: Int,
    val section: Int? = null,
    val route: Screen,
    /** 额外别名：中英文口语说法、缩写、拼音（如「主题」→ theme、zhuti） */
    val keywords: List<String> = emptyList(),
)

/** 一条命中结果。 */
data class SettingSearchHit(
    val entry: SettingSearchEntry,
    val score: Int,
    /** 命中描述而不是标题时，UI 可以优先展示描述片段做佐证 */
    val matchedInDescription: Boolean = false,
)

/**
 * 设置项搜索的**纯匹配逻辑**（不碰 Compose / 资源，便于单测）。
 *
 * 匹配策略：
 * - 查询按空白切成多个 token，**全部 token 都要命中**（AND），避免「主题 深色」被拆成两个不相干结果；
 * - 每个 token 在「标题 > 别名 > 描述」里找最高分；
 * - 大小写不敏感（英文），中文天然子串匹配；
 * - 不做模糊纠错（拼音/错字）：宁可搜不到，也别给一堆似是而非的结果。
 */
object SettingSearchMatcher {

    private const val SCORE_TITLE_EXACT = 120
    private const val SCORE_TITLE_PREFIX = 90
    private const val SCORE_TITLE_CONTAINS = 70
    private const val SCORE_KEYWORD_EXACT = 65
    private const val SCORE_KEYWORD_CONTAINS = 55
    private const val SCORE_DESCRIPTION_CONTAINS = 30

    /** 结果上限：设置项总量有限，但 UI 不需要一次渲染几百条。 */
    const val DEFAULT_LIMIT = 80

    fun search(
        query: String,
        entries: List<SettingSearchEntry>,
        titleOf: (Int) -> String,
        descriptionOf: (Int) -> String?,
        limit: Int = DEFAULT_LIMIT,
    ): List<SettingSearchHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()

        val hits = ArrayList<SettingSearchHit>()
        entries.forEach { entry ->
            val title = normalize(titleOf(entry.title))
            val description = entry.description?.let { normalize(descriptionOf(it) ?: "") }.orEmpty()
            val keywords = entry.keywords.map(::normalize)
            var total = 0
            var matchedInDescription = false
            var allMatched = true
            for (token in tokens) {
                var best = 0
                best = maxOf(best, scoreAgainstTitle(title, token))
                keywords.forEach { keyword -> best = maxOf(best, scoreAgainstKeyword(keyword, token)) }
                if (best == 0 && description.isNotEmpty() && description.contains(token)) {
                    best = SCORE_DESCRIPTION_CONTAINS
                    matchedInDescription = true
                }
                if (best == 0) {
                    allMatched = false
                    break
                }
                total += best
            }
            if (allMatched) {
                hits += SettingSearchHit(entry, total, matchedInDescription)
            }
        }
        // 稳定排序：同分时保持索引声明顺序（页面顺序），用户看到的分组不会跳来跳去
        return hits.sortedByDescending { it.score }.take(limit)
    }

    fun tokenize(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun normalize(text: String): String = text.trim().lowercase()

    private fun scoreAgainstTitle(title: String, token: String): Int = when {
        title.isEmpty() -> 0
        title == token -> SCORE_TITLE_EXACT
        title.startsWith(token) -> SCORE_TITLE_PREFIX
        title.contains(token) -> SCORE_TITLE_CONTAINS
        else -> 0
    }

    private fun scoreAgainstKeyword(keyword: String, token: String): Int = when {
        keyword.isEmpty() -> 0
        keyword == token -> SCORE_KEYWORD_EXACT
        keyword.contains(token) -> SCORE_KEYWORD_CONTAINS
        else -> 0
    }
}
