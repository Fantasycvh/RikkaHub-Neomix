package app.androidrik.common.tokenizer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 tokenizer（字节级 BPE，cl100k_base）——**对照 tiktoken 官方的逐条参考向量**验证。
 *
 * 参考向量由 `tiktoken 0.13.0`（`get_encoding("cl100k_base")`）生成，落在
 * `common/src/test/resources/tokenizer/cl100k_reference.json`（44 条：ASCII / CJK / emoji /
 * 全角空格 / 制表符 / 代码缩进 / 长文本 / 特殊 token 字面量 …）。
 *
 * **为什么必须有参考向量**：BPE 的分词结果对"预分词正则的一个 flag、映射表的一位"都敏感，
 * 只测"结构自洽"（能编能解）会漏掉**系统性偏移**——而本功能存在的意义正是"数准"。
 * 全角空格那条就是专门钉 Java 正则默认 `\s` 不认 Unicode 空白这处差异的。
 */
class ByteLevelBpeTest {

    private val reference by lazy {
        val stream = javaClass.getResourceAsStream("/tokenizer/cl100k_reference.json")
            ?: error("reference vectors missing")
        Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject["vectors"]!!.jsonArray
            .map { it.jsonObject }
    }

    private val bpe: ByteLevelBpe by lazy {
        val stream = javaClass.getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)
            ?: error("vocabulary resource missing")
        ByteLevelBpe.load(stream, keepDecodeTable = true)
    }

    @Test
    fun `every reference vector matches tiktoken exactly`() {
        assertTrue("expected a meaningful number of vectors", reference.size >= 40)
        val mismatches = reference.filter { vector ->
            val text = vector["text"]!!.jsonPrimitive.content
            val expected = vector["count"]!!.jsonPrimitive.content.toInt()
            bpe.count(text) != expected
        }.map { vector ->
            val text = vector["text"]!!.jsonPrimitive.content
            val expected = vector["count"]!!.jsonPrimitive.content.toInt()
            "「${text.take(40)}」 expected=$expected actual=${bpe.count(text)}"
        }
        assertEquals("vectors disagree with tiktoken: $mismatches", emptyList<String>(), mismatches)
    }

    @Test
    fun `encode length equals count and ids round-trip`() {
        reference.forEach { vector ->
            val text = vector["text"]!!.jsonPrimitive.content
            val ids = bpe.encode(text)
            assertEquals("count(${text.take(20)})", ids.size, bpe.count(text))
            assertEquals("round-trip(${text.take(20)})", text, bpe.decode(ids))
        }
    }

    @Test
    fun `empty and whitespace edge cases`() {
        assertEquals(0, bpe.count(""))
        assertEquals(0, bpe.encode("").size)
        assertEquals(1, bpe.count(" "))
        // 连续空格是一个 token（BPE 会把空格串合并），这与 tiktoken 一致
        assertEquals(1, bpe.count("  "))
    }

    @Test
    fun `cjk costs more than one token per two chars`() {
        // 真值参考：中文大约 1 token / 1~1.5 字符（启发式低估最严重的区间）
        val text = "这是一个中文句子，用于测试分词器。"
        val count = bpe.count(text)
        assertTrue("CJK should not be cheap: $count", count >= text.length / 2)
    }

    @Test
    fun `indentation and newlines keep their own tokens`() {
        val code = "def f(x):\n    return x + 1\n"
        // 真值 11（tiktoken 同款：缩进四空格与换行各有自己的 token）
        assertEquals(11, bpe.count(code))
    }

    @Test
    fun `registry loads from classpath and caches`() {
        assertTrue("install failed: ${TokenizerRegistry.lastError}", TokenizerRegistry.installFromClasspath())
        assertTrue(TokenizerRegistry.isReady)
        assertNotNull(TokenizerRegistry.current)

        // 缓存命中不改变结果（同一段文本两次计数必须一致）
        val text = "缓存 cache 测试 test"
        val first = TokenizerRegistry.count(text)
        val second = TokenizerRegistry.count(text)
        assertEquals(first, second)
        assertEquals(bpe.count(text), first)

        // 幂等：重复安装不报错、也不换实例
        val instance = TokenizerRegistry.current
        assertTrue(TokenizerRegistry.installFromClasspath())
        assertTrue(instance === TokenizerRegistry.current)
    }

    @Test
    fun `bulk text is tokenized fast enough for request-time use`() {
        // 系统提示 + 记忆 + 工具 schema 每次请求都要计数；这里用 ~114KB 量级做冒烟
        val bulk = buildString {
            // 每轮 76 字符 ⇒ 1500 轮 ≈ 114K 字符
            repeat(1500) {
                append("系统提示：你是一个助手，需要在回答中保持简洁。\n")
                append("You are a helpful assistant. Keep the answer short.\n")
            }
        }
        assertTrue("bulk too small: ${bulk.length}", bulk.length > 90_000)

        val start = System.nanoTime()
        val count = bpe.count(bulk)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("BPE bulk: ${bulk.length} chars -> $count tokens in ${elapsedMs}ms")
        assertTrue(count > 0)
        // 阈值给得很宽（真机 CPU 与 CI 差异大），只兜"算法退化成 O(n²) 整体扫描"这类事故
        assertTrue("too slow: ${elapsedMs}ms for ${bulk.length} chars", elapsedMs < 5_000)
    }

    @Test
    fun `resident memory stays within the documented budget`() {
        // 词表常驻内存是这项功能的唯一硬成本（APK 里是 456KB gz，运行时是哈希表）。
        // 这里把**两种加载模式**都量一遍并打印（生产用 keepDecodeTable=false），作为文档里的数字来源。
        fun usedHeapMb(): Long {
            val runtime = Runtime.getRuntime()
            repeat(3) { System.gc() }
            return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        }

        val baseline = usedHeapMb()
        val production = ByteLevelBpe.load(
            javaClass.getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)!!,
            keepDecodeTable = false,
        )
        val afterProduction = usedHeapMb()
        val withDecode = ByteLevelBpe.load(
            javaClass.getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)!!,
            keepDecodeTable = true,
        )
        val afterDecode = usedHeapMb()

        println(
            "BPE resident: production +${afterProduction - baseline}MB, " +
                "with decode table +${afterDecode - afterProduction}MB more " +
                "(vocab=${production.vocabSize}/${withDecode.vocabSize})",
        )
        assertTrue("implausibly large: ${afterDecode - baseline}MB", afterDecode - baseline < 60)
    }

    @Test
    fun `vocabulary size matches cl100k`() {
        assertEquals(100_256, bpe.vocabSize)
    }
}
