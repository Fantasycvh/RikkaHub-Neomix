package app.androidrik.common.tokenizer

import java.io.InputStream
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * **字节级 BPE 分词器**（GPT-2 / cl100k 系，tiktoken 同款算法）。
 *
 * 为什么需要它：本仓此前的 token 数是**字符启发式**（CJK 1.5 字符/token、其余 4 字符/token），
 * 用真词表比对下来**系统性偏低**（44 条样本里启发式/真值中位数只有 0.64，区间 0.17–2.0）——
 * 而启用它的地方全是"预算"判定（上下文占用条、自动压缩触发、记忆注入预算、子代理上下文预算），
 * 偏低意味着**该压的时候不压、该停的时候不停**。
 *
 * ## 算法（与 tiktoken 的 `cl100k_base` 逐字节等价）
 *
 * 1. **预分词**：按 [PRE_TOKEN_PATTERN] 把文本切成片段（tiktoken 的正则，逐字符移植）；
 * 2. **字节级映射**：每片先 UTF-8 编码，再经 [byteToCharTable] 把每个字节映到一个**可见字符**
 *    （GPT-2 的 `bytes_to_unicode`：0x20 → 'Ġ'，0x0A → 'Ċ' …）——这样"半个 UTF-8 字符"
 *    也能作为独立 token 参与合并，CJK/emoji 因此不会被拆坏；
 * 3. **按 rank 合并**：反复找相邻对里 rank 最小的一对并**合并其全部出现**，直到无可合并；
 * 4. 每个最终片在词表里的 rank 就是 token id。
 *
 * ## 内存与线程
 *
 * 词表是**只读**的（100k 条，`HashMap<String, Int>`，键是映射后的字符串）⇒ 本类实例不可变、线程安全。
 * 常驻内存见 [load] 的参数说明；解码表默认不建（只编码时不需要它）。
 *
 * 词表数据放在 `common/src/main/resources/tokenizer/`（Java 资源，Android 与 JVM 单测都能按
 * classpath 读，不需要 AssetManager/Context）。
 */
class ByteLevelBpe private constructor(
    private val ranks: Map<String, Int>,
    /** 仅当 [load] 要求时构建：rank → 原始字节（解码/按 token 截断才需要）。 */
    private val tokensByRank: List<ByteArray>?,
    /** 词表里最长 token 的字符数（映射后 1 字符 = 1 字节），用于合并剪枝。 */
    private val maxTokenChars: Int,
    /** 256 项：字节 → 映射字符。 */
    private val byteToCharTable: Array<String>,
) {

    /**
     * token 数（不建 id 数组，只数）。
     *
     * 逐片段走一层**片段级缓存**：自然语言里不同片段（词/符号串）数量远小于总片段数，
     * 长文本（整个对话轨迹）里重复片段极多——实测 114K 字符从 180ms 降到 ~40ms。
     * 缓存上限到了就整体清空（不搞 LRU：这里追求的是"零同步开销 + 命中率够"）。
     */
    fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val cache = pieceCounts.get()
        var total = 0
        var index = 0
        while (index < text.length) {
            val end = nextPieceEnd(text, index)
            val piece = text.substring(index, end)
            index = end
            val cached = cache[piece]
            if (cached != null) {
                total += cached
            } else {
                val counted = encodePiece(piece).size
                if (cache.size >= PIECE_CACHE_MAX_ENTRIES) cache.clear()
                cache[piece] = counted
                total += counted
            }
        }
        return total
    }

    /**
     * Counts exactly while allowing callers to persistently cache stable chunks via [countStable].
     *
     * [spans] are inclusive UTF-16 ranges in [text]. Each range gets at least 16 UTF-16
     * characters of context on either side (or [minimumContextChars], if larger), clamped
     * to the text. Every complete [PRE_TOKEN_PATTERN] match intersecting that window is
     * volatile, including matches arbitrarily longer than the context window. A boundary
     * after whitespace is also expanded through the next non-whitespace-ending match:
     * the regex's whitespace lookahead must not see an artificial end-of-input. Consecutive
     * matches with the same classification form one chunk; no regex match is ever split.
     * Thus the sum equals [count] provided [countStable] returns this tokenizer's exact
     * count for its argument. Volatile chunks bypass the callback and are counted here.
     *
     * Empty, reversed, negative or out-of-bounds ranges fall back to [count] for the whole
     * text, without invoking [countStable]. No spans means the whole nonempty text is stable.
     */
    fun countWithVolatileSpans(
        text: String,
        spans: List<IntRange>,
        minimumContextChars: Int = 16,
        /** Diagnostics only: (stableChars, volatileChars, chunks, splitNanos, countNanos) — no text. */
        onStats: ((Int, Int, Int, Long, Long) -> Unit)? = null,
        countStable: (String) -> Int,
    ): Int {
        if (spans.any { it.isEmpty() || it.first < 0 || it.last >= text.length }) {
            onStats?.invoke(-1, text.length, 0, 0L, 0L)
            return count(text)
        }
        if (text.isEmpty()) return 0
        if (spans.isEmpty()) {
            onStats?.invoke(text.length, 0, 1, 0L, 0L)
            return countStable(text)
        }

        val splitStarted = System.nanoTime()
        val context = minimumContextChars.coerceAtLeast(16).toLong()
        // Long arithmetic also makes Int.MAX_VALUE context safe. Merge overlapping windows
        // so a single forward scan of matches and windows suffices, even for unsorted spans.
        val windows = ArrayList<IntRange>()
        for (span in spans.sortedBy { it.first }) {
            val start = (span.first.toLong() - context).coerceAtLeast(0).toInt()
            val end = (span.last.toLong() + context).coerceAtMost(text.lastIndex.toLong()).toInt()
            val previous = windows.lastOrNull()
            if (previous != null && start.toLong() <= previous.last.toLong() + 1) {
                windows[windows.lastIndex] = previous.first..maxOf(previous.last, end)
            } else {
                windows += start..end
            }
        }

        val chunkStarts = ArrayList<Int>()
        val chunkEnds = ArrayList<Int>()
        val chunkVolatileFlags = ArrayList<Boolean>()

        fun appendChunk(start: Int, end: Int, volatile: Boolean) {
            val last = chunkStarts.lastIndex
            if (last >= 0 && chunkVolatileFlags[last] == volatile && chunkEnds[last] == start) {
                chunkEnds[last] = end
            } else {
                chunkStarts += start
                chunkEnds += end
                chunkVolatileFlags += volatile
            }
        }

        // Single streaming pass: classify each match against the volatile windows, fold
        // whitespace-lookahead groups, and accumulate chunk boundaries directly. Materialising
        // every MatchResult first (tens of thousands of objects for a 128KB prompt) was pure
        // overhead on top of the scan that already dominates the cost.
        var windowIndex = 0
        var groupStart = -1
        var groupEnd = -1
        var groupVolatile = false
        var previousEndsWithWhitespace = false
        var scanIndex = 0
        while (scanIndex < text.length) {
            val matchStart = scanIndex
            val matchEnd = nextPieceEnd(text, matchStart)
            scanIndex = matchEnd
            while (windowIndex < windows.size && windows[windowIndex].last < matchStart) {
                windowIndex++
            }
            val volatile = windowIndex < windows.size && windows[windowIndex].first <= matchEnd - 1
            if (groupStart < 0) {
                groupStart = matchStart
                groupEnd = matchEnd
                groupVolatile = volatile
            } else if (previousEndsWithWhitespace) {
                groupEnd = matchEnd
                groupVolatile = groupVolatile || volatile
            } else {
                appendChunk(groupStart, groupEnd, groupVolatile)
                groupStart = matchStart
                groupEnd = matchEnd
                groupVolatile = volatile
            }
            previousEndsWithWhitespace = isPreTokenWhitespace(text.codePointBefore(matchEnd))
        }
        if (groupStart >= 0) appendChunk(groupStart, groupEnd, groupVolatile)

        val splitNanos = System.nanoTime() - splitStarted
        val countingStarted = System.nanoTime()
        var total = 0
        var stableChars = 0
        var volatileChars = 0
        for (index in chunkStarts.indices) {
            val chunk = text.substring(chunkStarts[index], chunkEnds[index])
            total += if (chunkVolatileFlags[index]) count(chunk) else countStable(chunk)
            if (chunkVolatileFlags[index]) volatileChars += chunk.length else stableChars += chunk.length
        }
        onStats?.invoke(stableChars, volatileChars, chunkStarts.size, splitNanos, System.nanoTime() - countingStarted)
        return total
    }

    /** Diagnostics: number of pre-tokenizer pieces, so scan cost can be measured in isolation. */
    fun countPreTokenMatches(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            index = nextPieceEnd(text, index)
            count++
        }
        return count
    }

    /**
     * 手写预分词：返回从 [start] 开始的片段结束下标（不含），与 cl100k 正则**逐字节等价**。
     *
     * 为什么不用正则：Android 上 `java.util.regex` 的 `Matcher` 极慢——实测同一段 11.5 万字符文本，
     * 正则扫描 2.4–2.6 秒，而本函数只需要约 26 毫秒（debug 与 release 包都是这个比例，与构建类型无关）。
     * 分词计数是上下文预算/自动压缩/记忆注入的公共底座，这一层慢会把所有上层功能一起拖慢。
     *
     * 语义对应关系（按正则分支顺序，先匹配者胜）：
     * 1. `'(?i:[sdmt]|ll|ve|re)` 缩写；
     * 2. `[^\r\n\p{L}\p{N}]?+\p{L}+` 可选前缀 + 字母串（前缀是**占有量词**，不回头）；
     * 3. `\p{N}{1,3}` 至多三位数字（`\p{N}` 是 Unicode Number 大类，不只是 0-9）；
     * 4. ` ?[^WS\p{L}\p{N}]++[\r\n]*` 可选空格 + 符号串 + 尾随换行；
     * 5. `[WS]*[\r\n]` 空白后接换行（贪婪回溯 ⇒ 落在空白串里**最后一个**换行）；
     * 6. `[WS]+(?![^WS])` 空白串其后不是非空白（贪婪回溯 ⇒ 长度 L≥2 时只吃 L-1）；
     * 7. `[WS]+` 兜底空白串。
     *
     * 等价性由 `ByteLevelBpePreTokenParityTest` 与 [RegexReference] 对拍保证。
     */
    private fun nextPieceEnd(text: String, start: Int): Int {
        val length = text.length
        val current = text.codePointAt(start)

        // 1. 's 'd 'm 't 'll 've 're（ASCII 大小写不敏感）
        if (current == APOSTROPHE && start + 1 < length) {
            val first = text.codePointAt(start + 1)
            if (first == 's'.code || first == 'S'.code || first == 'd'.code || first == 'D'.code ||
                first == 'm'.code || first == 'M'.code || first == 't'.code || first == 'T'.code
            ) {
                return start + 2
            }
            val secondIndex = start + 1 + Character.charCount(first)
            if (first == 'l'.code || first == 'L'.code || first == 'v'.code || first == 'V'.code ||
                first == 'r'.code || first == 'R'.code
            ) {
                if (secondIndex < length) {
                    val second = text.codePointAt(secondIndex)
                    if (((first == 'l'.code || first == 'L'.code) && (second == 'l'.code || second == 'L'.code)) ||
                        ((first == 'v'.code || first == 'V'.code) && (second == 'e'.code || second == 'E'.code)) ||
                        ((first == 'r'.code || first == 'R'.code) && (second == 'e'.code || second == 'E'.code))
                    ) {
                        return secondIndex + Character.charCount(second)
                    }
                }
            }
        }

        // 2. 可选前缀（非 CR/LF/字母/数字）+ 字母串
        if (isPreTokenLetter(current)) {
            return scanLetters(text, start)
        }
        if (current != CARRIAGE_RETURN && current != LINE_FEED && !isPreTokenNumber(current)) {
            val afterPrefix = start + Character.charCount(current)
            if (afterPrefix < length && isPreTokenLetter(text.codePointAt(afterPrefix))) {
                return scanLetters(text, afterPrefix)
            }
        }

        // 3. 1..3 位数字
        if (isPreTokenNumber(current)) {
            var index = start + Character.charCount(current)
            var digits = 1
            while (index < length && digits < 3) {
                val next = text.codePointAt(index)
                if (!isPreTokenNumber(next)) break
                index += Character.charCount(next)
                digits++
            }
            return index
        }

        // 4. 可选空格 + 符号串 + 尾随换行
        var index = start
        if (current == SPACE) index += 1
        val symbolsStart = index
        while (index < length) {
            val next = text.codePointAt(index)
            if (isPreTokenWhitespace(next) || isPreTokenLetter(next) || isPreTokenNumber(next)) break
            index += Character.charCount(next)
        }
        if (index > symbolsStart) {
            while (index < length) {
                val next = text.codePointAt(index)
                if (next != CARRIAGE_RETURN && next != LINE_FEED) break
                index += 1
            }
            return index
        }

        // 5/6/7. 空白串
        var runEnd = start
        while (runEnd < length) {
            val next = text.codePointAt(runEnd)
            if (!isPreTokenWhitespace(next)) break
            runEnd += Character.charCount(next)
        }
        if (runEnd > start) {
            var lastBreak = -1
            var scan = start
            while (scan < runEnd) {
                val next = text.codePointAt(scan)
                if (next == CARRIAGE_RETURN || next == LINE_FEED) lastBreak = scan
                scan += Character.charCount(next)
            }
            if (lastBreak >= 0) return lastBreak + 1
            if (runEnd == length) return runEnd
            if (runEnd - Character.charCount(text.codePointBefore(runEnd)) > start) {
                return runEnd - Character.charCount(text.codePointBefore(runEnd))
            }
            return runEnd
        }

        // 正则覆盖所有字符，理论上到不了这里；保守前进一个字符避免死循环。
        return start + Character.charCount(current)
    }

    /** 从 [from]（必须是字母）开始吃满字母串，返回结束下标。 */
    private fun scanLetters(text: String, from: Int): Int {
        var index = from
        while (index < text.length) {
            val next = text.codePointAt(index)
            if (!isPreTokenLetter(next)) break
            index += Character.charCount(next)
        }
        return index
    }

    /** 测试用：暴露手写扫描器的单步结果，供与正则对拍。 */
    internal fun pieceEndsForTest(text: String, start: Int): Int = nextPieceEnd(text, start)

    /** 测试用参考实现：把文本按 cl100k 正则切成片段。 */
    inner class RegexReference {
        fun pieces(text: String): List<String> = PRE_TOKEN_PATTERN.findAll(text).map { it.value }.toList()
    }

    /** 编码为 token id 序列。 */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val ids = ArrayList<Int>(text.length / 3 + 8)
        var index = 0
        while (index < text.length) {
            val end = nextPieceEnd(text, index)
            for (piece in encodePiece(text.substring(index, end))) {
                ids += rankOf(piece)
            }
            index = end
        }
        return ids.toIntArray()
    }

    /**
     * 解码回文本（**需要 [load] 时保留解码表**）。
     *
     * 用途不是"还原原文"而是"按 token 边界安全截断"之类的场景（截出来的 ids 必须是完整 token）。
     */
    fun decode(ids: IntArray): String {
        val table = tokensByRank
            ?: error("decode 需要加载时保留解码表（load(..., keepDecodeTable = true)）")
        val bytes = ArrayList<Byte>(ids.size * 4)
        ids.forEach { id ->
            val token = table.getOrNull(id) ?: error("unknown token id: $id")
            token.forEach { bytes += it }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** 词表大小（诊断用）。 */
    val vocabSize: Int get() = ranks.size

    /**
     * 片段级计数缓存（见 [count]）：**每线程独立**。
     *
     * 早先是一张共享的 `ConcurrentHashMap`：单线程够用，但拼装预览把计数分摊到 6 个线程后，
     * 每个片段都要抢同一张表的写锁、且"装满整表清空"会造成跨线程抖动——实测并发只跑到
     * 串行的 2 倍。改成 ThreadLocal 后各线程互不干扰（缓存内容只是"片段→计数"的纯函数结果，
     * 分片不改变任何数值）。
     */
    private val pieceCounts = ThreadLocal.withInitial { HashMap<String, Int>(1024) }


    /**
     * 一个预分词片段 → 合并后的片序列（映射形式）。
     *
     * 合并规则与 tiktoken 相同：每轮取**相邻对里 rank 最小**的一对，并把该对的**所有出现**一起合并。
     * 片段本身很短（正则已经把文本切到词/数字组/符号串的粒度），所以这里的朴素实现足够快；
     * 真要有长片段（比如几百个空格的缩进），合并轮数也远小于长度。
     */
    private fun encodePiece(piece: String): List<String> {
        val bytes = piece.toByteArray(Charsets.UTF_8)
        if (bytes.size == 1) return listOf(byteToCharTable[bytes[0].toInt() and 0xFF])
        var parts: MutableList<String> = ArrayList(bytes.size)
        bytes.forEach { parts += byteToCharTable[it.toInt() and 0xFF] }

        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestPair: String? = null
            for (i in 0 until parts.size - 1) {
                // 剪枝：拼起来比词表里最长的 token 还长 ⇒ 不可能命中，连拼接都省掉
                // （cl100k 最长 token 是 128 字节；热路径上这一步省掉的是绝大多数失败查询）
                if (parts[i].length + parts[i + 1].length > maxTokenChars) continue
                val joined = parts[i] + parts[i + 1]
                val rank = ranks[joined] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestPair = joined
                }
            }
            if (bestPair == null) break
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] + parts[i + 1] == bestPair) {
                    merged += bestPair
                    i += 2
                } else {
                    merged += parts[i]
                    i += 1
                }
            }
            parts = merged
        }
        return parts
    }

    private fun rankOf(piece: String): Int =
        ranks[piece] ?: error("token not in vocabulary: ${piece.toByteArray(Charsets.UTF_8).decodeToString()}")

    companion object {
        /** 片段级计数缓存上限：到顶整体清空（见 [count]）。 */
        private const val PIECE_CACHE_MAX_ENTRIES = 16_384

        /**
         * **Unicode 空白集**（Python `re` 里 `\s` 对 str 的语义，逐项抄下来）。
         *
         * **为什么不用 `Pattern.UNICODE_CHARACTER_CLASS`**：桌面 JVM 支持这个 flag，但
         * **Android 的 `java.util.regex.Pattern` 直接抛 `IllegalArgumentException: UNICODE_CHARACTER_CLASS
         * flag not supported`**——单测（JVM）绿、真机启动时 `ExceptionInInitializerError`
         * （本功能首版正是这样栽的，见 `docs/` 的设计稿）。⇒ 只能把 `\s` 展开成显式字符集。
         */
        private const val WS = " \t\n\r\u000b\u000c\u001c\u001d\u001e\u001f\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000"

        private const val APOSTROPHE = 0x27
        private const val SPACE = 0x20
        private const val CARRIAGE_RETURN = 0x0D
        private const val LINE_FEED = 0x0A

        /** `\p{L}`：Unicode Letter 大类（按码点判断，补充平面字母也能识别）。 */
        private fun isPreTokenLetter(codePoint: Int): Boolean = Character.isLetter(codePoint)

        /** `\p{N}`：Unicode Number 大类（Nd/Nl/No），比 `Char.isDigit()` 宽。 */
        private fun isPreTokenNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt(),
            -> true

            else -> false
        }

        private fun isPreTokenWhitespace(codePoint: Int): Boolean = when (codePoint) {
            in 0x0009..0x000d, in 0x001c..0x0020, 0x0085, 0x00a0, 0x1680,
            in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000 -> true

            else -> false
        }

        /**
         * tiktoken `cl100k_base` 的预分词正则（逐字符移植；`\s` 已按 [WS] 展开、`\S` 写成 `[^WS]`）。
         *
         * `\p{L}` / `\p{N}` 在 Android 上本就是 Unicode 感知的（ICU 实现），无需额外处理；
         * `(?i:)` 只用于 ASCII 的 `s/d/m/t` 缩写，故也不依赖 `UNICODE_CASE`。
         * 参考向量里放了全角空格（U+3000）、不换行空格（U+00A0）、零宽空格（U+200B）三条来钉住这处差异。
         */
        private val PRE_TOKEN_PATTERN: Regex = Pattern.compile(
            """'(?i:[sdmt]|ll|ve|re)|[^\r\n\p{L}\p{N}]?+\p{L}+|\p{N}{1,3}| ?[^$WS\p{L}\p{N}]++[\r\n]*|[$WS]*[\r\n]|[$WS]+(?![^$WS])|[$WS]+""",
        ).toRegex()

        /**
         * 加载词表。
         *
         * @param input **gzip 压缩的紧凑二进制**：按 rank 升序排列的 `varint(长度) + 原始字节`。
         *   这份格式由构建期从 `cl100k_base.tiktoken`（官方 base64 rank 表）转换而来：1.68MB 文本
         *   → 744KB 二进制 → **456KB gzip**（`common/src/main/resources/tokenizer/cl100k_base.bin.gz`）。
         * @param keepDecodeTable 是否同时保留 rank → 字节（解码用）。生产只编码 ⇒ 默认 false，
         *   省下约三分之一常驻内存（100k 条字节数组的开销）。
         */
        fun load(input: InputStream, keepDecodeTable: Boolean = false): ByteLevelBpe {
            val byteToChar = buildByteToCharTable()
            val ranks = HashMap<String, Int>(200_000)
            val byRank = if (keepDecodeTable) ArrayList<ByteArray>(100_256) else null
            var maxTokenChars = 1

            GZIPInputStream(input).buffered(1 shl 16).use { stream ->
                var rank = 0
                while (true) {
                    val length = stream.readVarInt() ?: break
                    val token = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = stream.read(token, read, length - read)
                        if (n < 0) error("truncated vocabulary at rank $rank")
                        read += n
                    }
                    val mapped = buildString(length) {
                        token.forEach { append(byteToChar[it.toInt() and 0xFF]) }
                    }
                    ranks[mapped] = rank
                    if (length > maxTokenChars) maxTokenChars = length
                    byRank?.add(token)
                    rank += 1
                }
            }
            require(ranks.isNotEmpty()) { "empty vocabulary" }
            return ByteLevelBpe(
                ranks = ranks,
                tokensByRank = byRank,
                maxTokenChars = maxTokenChars,
                byteToCharTable = byteToChar,
            )
        }

        /** GPT-2 的 `bytes_to_unicode`：把 256 个字节映到互不相同、且可直接放进字符串的字符。 */
        private fun buildByteToCharTable(): Array<String> {
            val direct = (33..126) + (161..172) + (174..255)
            val table = arrayOfNulls<String>(256)
            direct.forEach { b -> table[b] = b.toChar().toString() }
            var extra = 0
            for (b in 0 until 256) {
                if (table[b] == null) {
                    table[b] = (256 + extra).toChar().toString()
                    extra += 1
                }
            }
            @Suppress("UNCHECKED_CAST")
            return table as Array<String>
        }

        /** 读一个 varint；流结束（第一字节就读不到）返回 null。 */
        private fun InputStream.readVarInt(): Int? {
            var shift = 0
            var result = 0
            while (true) {
                val b = read()
                if (b < 0) return if (shift == 0) null else error("truncated varint")
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
