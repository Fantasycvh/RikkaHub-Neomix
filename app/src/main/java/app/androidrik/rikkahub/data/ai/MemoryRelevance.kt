package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import java.text.Normalizer

/**
 * **记忆打分的相关性维度**（`docs/design/design-memory-injection-tiering.md` §3.2）。
 *
 * 此前"相关性"在本仓是个死结：记忆块在 **system**（位置 0），而前缀缓存从位置 0 起连续匹配
 * ⇒ 一旦选取依赖当前查询，记忆块每轮都变 ⇒ 每轮整段前缀失效（实测命中率 98% → 0%）。
 * 所以当时的结论是"相关性 与 保缓存 互斥"。
 *
 * 记忆数据移到**对话尾部**后前提变了：尾部怎么变都不动它前面的前缀（§2/§3）。
 * 于是本文件落地那半句话——**前缀稳定归 L0/L1（头部），查询相关性归 L2（尾部）**。
 *
 * ## 不用 embedding
 *
 * 判据是**词元重叠**（本地可算、无网络、无模型、逐字节可复现），而不是向量相似度。
 * 对本场景足够：记忆条目短、查询也是同一门语言的自然表达，共享实词（"TikZ""渲染""咖啡"）
 * 就已经是很强的信号。余弦相似度要付出的代价（额外模型/额外请求/不确定性）远超它多出来的
 * 那点分辨力。
 *
 * ## 两条保守性设计（"不确定"时一律退回改动前的行为）
 *
 * 1. **只抬不压**：相关性是**乘性增益**（最多 ×(1+[RELEVANCE_BOOST])），不是闸门。
 *    查询与该记忆毫无词汇重叠时增益为 1.0 ⇒ 打分与改动前**逐位一致**。
 *    这一点是刻意的：像"我是素食者"这种身份型记忆**本来就不该**与查询有词汇重叠，
 *    若用相关性去**淘汰**低分记忆，第一批被淘汰的正是最该留住的那批（importance 存在的理由）。
 * 2. **低于 [RELEVANCE_FLOOR] 视为 0**：长消息里的偶发 bigram 巧合（"的是""我们"）不足以
 *    构成相关性证据。短查询一个词元命中就能越过（精确），长查询则需要多个词元（证据更强才算数）。
 *
 * 注意与**分桶**（`SCORE_BUCKET_SIZE`）的分工：分桶是为 system 时代的"分数连续漂移打掉缓存"
 * 服务的；L2 已经不在前缀里，故本层**刻意不套分桶**——套了反而会让"同一桶内按写入先后"
 * 盖掉相关性（差分不到一档就不提升 = 相关性白算）。
 */
internal const val RELEVANCE_BOOST = 1.0f

/** 相关性下限：覆盖度低于此值视为"无相关性证据"（见文件头注释）。 */
internal const val RELEVANCE_FLOOR = 0.1f

/**
 * 分母上限：查询词元再多也只按这么多个算。
 *
 * 为什么需要：用户消息可以很长（一整段），而 bigram 会把它切成上百个词元 ⇒ 一条只命中
 * 3 个词的记忆会被稀释成 0.03，相关性形同不存在。封顶后"命中 3 个词元"在长查询里
 * 同样是 3/24，短查询里是 3/3——**短查询更精确、长查询更需要证据**，符合直觉。
 */
private const val QUERY_TOKEN_CAP = 24

/**
 * 功能字（虚词）。**两个都是功能字的 bigram 才丢弃**（"的是""我们""这个""可以"），
 * 只要有一个是实词就保留（"记忆""渲染""测试"都是实词组合，必须留）。
 *
 * 这不是一份完整的中文停用词表——本仓没有语料可依据，硬凑长表只会引入没人能验证的假设。
 * 这里只处理"几乎必然无信息量"的那批高频虚词，因为它解决的是**长查询稀释**这个具体问题。
 */
private val FUNCTION_CHARS: Set<Char> = (
    "的了是我你他她它们这那在有和与就也不都很吗呢吧啊呀哦嗯个上中下要能会可以被把给对为之其而" +
        "或但只还又再最更太些什么怎样如何时候一二三四五六七八九十几"
    ).toSet()

/** 英文虚词（同样只挡掉必然无信息量的那批）。 */
private val LATIN_STOPWORDS: Set<String> = setOf(
    "the", "and", "for", "with", "you", "your", "are", "was", "were", "is", "to", "of", "in",
    "on", "at", "it", "its", "that", "this", "what", "how", "why", "when", "where", "but", "not",
    "can", "could", "would", "should", "my", "me", "we", "they", "he", "she", "them", "his", "her",
    "do", "does", "did", "be", "been", "have", "has", "had", "will", "just", "about", "from", "as",
    "if", "or", "so", "please", "help", "want", "need",
)

/**
 * 一份文本的**两级词元**（CJK 没有词边界，两级都要有才能同时照顾长、短两种输入）：
 *
 * - [grams]：CJK 相邻二字（bigram）+ 拉丁/数字词。**长文本的主用级别**——"记忆"/"渲染"
 *   这种二字组合是很强的共现单位，而单字级别全是噪声（"的""了"）。
 * - [chars]：非虚词的 CJK **单字**。**只在单字级别比对时使用**（见 [MemoryQuery.relevanceOf]）
 *   ——查询是"猫"这种单字时产不出任何 bigram，若没有这一级则永远算不出相关性。
 *
 * 关键：**匹配时两级不能混用**。查询"猫"（只有 [chars]）拿去和一条长记忆的 [grams] 比，
 * 永远匹配不上（长记忆的 bigram 里没有"猫"这个单字）——这是本文件第一版的真 bug，
 * 由 `single character cjk query still works` 抓出来。
 */
private class Lexicon(
    val grams: Set<String>,
    val chars: Set<String>,
    // Only isolated CJK runs, not every character of a multi-character word.
    val singletonChars: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = grams.isEmpty() && chars.isEmpty()
}

/**
 * 本轮查询（预分词）。**在选取开始前造一次**，不要在逐条记忆里重复分词——一条长消息 ×
 * 几十条记忆是白白的重复劳动。
 */
internal class MemoryQuery private constructor(private val lexicon: Lexicon) {

    internal companion object {
        /**
         * 从查询文本造一个。**构造器私有**：分词用的 [Lexicon] 是本文件的实现细节，
         * 不该经由公开签名泄漏出去（`internal` 构造器会暴露它，Kotlin 会直接报错）。
         */
        fun of(text: String?): MemoryQuery =
            MemoryQuery(if (text.isNullOrBlank()) Lexicon(emptySet(), emptySet()) else lexiconOf(text))
    }

    val isEmpty: Boolean get() = lexicon.isEmpty

    /**
     * 相关性 = 该记忆对查询词元的**覆盖度**，值域 [0,1]；低于 [RELEVANCE_FLOOR] 返回 0。
     *
     * 为什么是"覆盖查询"而不是对称相似度（Jaccard / 余弦）：记忆条目通常比查询长得多，
     * 对称度量会被记忆自身的长度稀释——一条写着用户全部偏好的记忆，即使完整覆盖了查询，
     * 相似度也会很低。这里要回答的问题是**"这条记忆讲的是查询这件事吗"**，单向覆盖正合适。
     */
    fun relevanceOf(memory: AssistantMemory): Float {
        val mine = lexiconOf(memory.content + "\n" + memory.description)
        // Compare grams with grams and isolated CJK probes with chars, never with the opposite level.
        // Multi-character CJK words do not expand into individual probes when grams are available.
        val probeCount: Int
        val matched: Int
        if (lexicon.grams.isNotEmpty()) {
            // Mixed queries such as "猫 python" retain the isolated 猫 signal, at the same unit weight.
            probeCount = lexicon.grams.size + lexicon.singletonChars.size
            matched = lexicon.grams.count { it in mine.grams } + lexicon.singletonChars.count { it in mine.chars }
        } else {
            probeCount = lexicon.chars.size
            matched = lexicon.chars.count { it in mine.chars }
        }
        if (probeCount == 0) return 0f

        val coverage = matched.toFloat() / minOf(probeCount, QUERY_TOKEN_CAP)
        // 低于下限视为无证据 ⇒ 返回 0（增益退化成 1.0，与改动前逐位一致）
        return if (coverage < RELEVANCE_FLOOR) 0f else coverage.coerceAtMost(1f)
    }
}

/**
 * 本轮查询文本 = 发送窗口里**最后一条真实的用户消息**。
 *
 * 为什么排除合成消息（`isSynthetic`）：压缩检查点也是一条 role=USER 的合成消息，而它位于窗口
 * **最前面**——按"最后一条"取不会碰到它，但显式排除更稳：将来若有人在尾部再追加合成 USER
 * 消息（本层的记忆块自己就是），相关性会立刻开始自己算自己。
 *
 * @return null 表示"没有可用的查询" ⇒ 相关性整体退化为 0（增益 1.0，行为与改动前一致）
 */
internal fun memoryQueryOf(messages: List<UIMessage>): String? = messages
    .lastOrNull { !it.isSynthetic && it.role == MessageRole.USER }
    ?.parts
    ?.filterIsInstance<UIMessagePart.Text>()
    ?.joinToString("\n") { it.text }
    ?.takeIf { it.isNotBlank() }

/** 查询文本 → 预分词查询。null / 空白 ⇒ 空查询（相关性整体弃权）。 */
internal fun memoryQueryTokens(text: String?): MemoryQuery = MemoryQuery.of(text)

/** CJK 及日韩文字（这些文字没有词边界，靠 bigram 近似分词）。 */
private fun isCjk(ch: Char): Boolean = when (ch.code) {
    in 0x3400..0x4DBF -> true   // CJK 扩展 A
    in 0x4E00..0x9FFF -> true   // CJK 基本区
    in 0x3040..0x30FF -> true   // 平假名 / 片假名
    in 0xAC00..0xD7AF -> true   // 谚文音节
    else -> false
}

/**
 * 极简分词：拉丁/数字按词切（≥2 字符、挡掉英文虚词），CJK 同时产出 bigram 与非虚词单字。
 *
 * 为什么 CJK 用 bigram 而不是查词典分词：本仓没有任何分词器的依赖，引入一个（连同词典）只
 * 为算相关性不划算；而中文里"相邻两字"已经是很强的共现单位——"记忆"/"渲染"/"咖啡"都是 bigram，
 * 单个虚词造成的噪声由 [FUNCTION_CHARS] 的"双虚词才丢"规则挡掉。
 */
private fun lexiconOf(text: String): Lexicon {
    val grams = HashSet<String>()
    val chars = HashSet<String>()
    val word = StringBuilder()
    // Normalize comparison keys only; never rewrite persisted content, descriptions or identifiers.
    val lowered = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    val singletonChars = HashSet<String>()
    var prev: Char? = null
    var cjkRunLength = 0

    fun flushCjkRun() {
        val single = prev
        if (cjkRunLength == 1 && single != null && single !in FUNCTION_CHARS) {
            singletonChars += single.toString()
        }
        cjkRunLength = 0
        prev = null
    }

    fun flushWord() {
        if (word.length >= 2 && word.toString() !in LATIN_STOPWORDS) grams += word.toString()
        word.setLength(0)
    }

    for (ch in lowered) {
        if (isCjk(ch)) {
            flushWord()
            if (ch !in FUNCTION_CHARS) chars += ch.toString()
            val previous = prev
            if (previous != null && !(previous in FUNCTION_CHARS && ch in FUNCTION_CHARS)) {
                grams += "$previous$ch"
            }
            prev = ch
            cjkRunLength++
        } else {
            flushCjkRun()
            if (ch.isLetterOrDigit()) word.append(ch) else flushWord()
        }
    }
    flushWord()
    flushCjkRun()
    return Lexicon(grams = grams, chars = chars, singletonChars = singletonChars)
}

/** Shared explicit-search ranking; callers remain responsible for supplying only the current store's live records. */
internal fun rankMemoriesForSearch(
    memories: List<AssistantMemory>,
    query: MemoryQuery,
    limit: Int,
): List<AssistantMemory> {
    if (limit <= 0) return emptyList()
    return memories
        .mapNotNull { memory -> query.relevanceOf(memory).takeIf { it > 0f }?.let { memory to it } }
        .sortedWith(compareByDescending<Pair<AssistantMemory, Float>> { it.second }.thenBy { it.first.sortKey })
        .take(limit).map { it.first }
}
