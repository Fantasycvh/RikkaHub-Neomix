package app.androidrik.common.tokenizer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 计数的**性能护栏**。
 *
 * 起因是一次真机 ANR：`ChatService` 在主线程上做记忆注入的 token 计数，
 * 而 `\p{L}+` 会把一整段没有空格的中文当成**一个**片段 —— 合并循环是 O(n²) 且每轮都在拼字符串，
 * 单片段几千字符就是秒级（主线程一卡 5 秒 ⇒ 系统弹「无响应」）。
 *
 * 这个用例把"数一段长文本不许病态变慢"钉住：它不测精度（[ByteLevelBpeTest] 的参考向量管精度），
 * 只测**量级**（阈值给得很松，正常实现快两个数量级）。
 */
class TokenizerPerformanceTest {

    private val bpe: ByteLevelBpe by lazy {
        val stream = javaClass.getResourceAsStream(TokenizerRegistry.CL100K_BASE_RESOURCE)
            ?: error("vocabulary resource missing")
        ByteLevelBpe.load(stream, keepDecodeTable = false)
    }

    private fun measure(label: String, text: String, limitMs: Long = 300): Int {
        // 先热身一次词表与 JIT，避免把首次加载算进计时
        bpe.count("热身")
        val started = System.nanoTime()
        val count = bpe.count(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(
            "$label（${text.length} 字符）用了 ${elapsedMs}ms，超过 ${limitMs}ms —— " +
                "主线程上这就是 ANR 的量级",
            elapsedMs < limitMs,
        )
        return count
    }

    @Test
    fun `a long chinese paragraph is counted quickly`() {
        // 4K 字符中文（无空格 ⇒ 预分词后是一整片段）
        val text = "这是一段用于测试的中文文本，用来验证计数不会因为长片段变得病态。" .repeat(100)
        assertTrue(measure("中文长段", text) > 0)
    }

    @Test
    fun `a long ascii blob is counted quickly`() {
        // 100KB 的压缩型 JSON（字段名与长字符串都不含空格）
        val text = """{"tool":"workspace_run","args":{"command":"${"x".repeat(50)},${"y".repeat(50)}"}}""".repeat(800)
        assertTrue(measure("长 ASCII/JSON", text, limitMs = 1_000) > 0)
    }

    @Test
    fun `repeated counting stays cached`() {
        val text = "重复片段很多的长文本，边界处有数字 12345 与符号 —— hello world".repeat(50)
        bpe.count(text)
        measure("重复计数（应命中片段缓存）", text, limitMs = 50)
    }
}
