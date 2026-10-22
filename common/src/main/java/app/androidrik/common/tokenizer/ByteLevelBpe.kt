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
        PRE_TOKEN_PATTERN.findAll(text).forEach { match ->
            val piece = match.value
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

    /** 编码为 token id 序列。 */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val ids = ArrayList<Int>(text.length / 3 + 8)
        PRE_TOKEN_PATTERN.findAll(text).forEach { match ->
            for (piece in encodePiece(match.value)) {
                ids += rankOf(piece)
            }
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
