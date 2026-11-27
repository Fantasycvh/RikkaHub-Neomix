package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.content.ContextWrapper
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 工具输出预算二/三期测试（`docs/design/drafts/design-tool-output-budget.md`）：
 * - 阶段 2：A1 两级限幅 `min(批次份额, 全局上限)` / A2 执行前预留与结清守卫 / A3 零额度最小信封 /
 *   A4 线载荷计量（含信封开销 + B9 字节口径）/ A5 唯一容量来源 / A6 结构预检先于精确计数；
 * - 阶段 3：B1 分级降级 / B2 重复行折叠 ≠ 内容丢失 / B4-B5 信封与错误头优先 / B6 保护原子 /
 *   B7 保头保尾 / B8+D2 单一压缩器（成功与失败共用）/ 避开 senera 1 号坑（落盘失败不改写执行结论）。
 */
class ToolOutputBudgetPhase23Test {

    private val tempDir: File = Files.createTempDirectory("tool-budget-p23-test").toFile()
    private val context: Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = tempDir
    }

    private fun toolPart(id: String = "call-1", name: String = "workspace_shell") = UIMessagePart.Tool(
        toolCallId = id,
        toolName = name,
        input = "{}",
    )

    // ---- 阶段 2：A1 两级限幅 + A2 预留结清 + A3 零额度 ----

    @Test
    fun `A1 two-level cap picks min of batch share and global tier cap`() {
        // 场景 1：窗口很空（200K 窗口、已用 10K、单工具）⇒ 批次份额巨大，取全局上限 32K
        val spacious = ToolOutputBatchBudget(windowTokens = 200_000, initialUsedTokens = 10_000, batchSize = 1)
        val r1 = spacious.reserve("t1")
        assertEquals(MAX_TOOL_OUTPUT_CHARS, r1.budgetChars)
        assertEquals(MAX_TOOL_OUTPUT_CHARS, r1.globalCapChars)

        // 场景 2：小余量 + 8 并发（窗口 40K、已用 32K ⇒ 剩 8K token，8 个工具均分每份 1K token = 4K 字符 < 8K 全局上限）
        val tightParallel = ToolOutputBatchBudget(windowTokens = 40_000, initialUsedTokens = 32_000, batchSize = 8)
        val reservations = (1..8).map { tightParallel.reserve("p$it") }
        assertTrue(
            "并行 8 工具应按份额受限到 ~4K（低于 8K 全局档位上限），实际=${reservations.map { it.budgetChars }}",
            reservations.all { it.budgetChars <= 4_000 && it.budgetChars > 0 },
        )
        assertEquals(8, tightParallel.unsettledCount())
    }

    @Test
    fun `A2 reservation settles actual wire tokens, returns surplus, and asserts zero leaks`() {
        val budget = ToolOutputBatchBudget(windowTokens = 40_000, initialUsedTokens = 32_000, batchSize = 4)
        val r1 = budget.reserve("c1")
        val r2 = budget.reserve("c2")
        assertEquals(2, budget.unsettledCount())

        // c1 实际只用了 100 token（远小于预留），结清后盈余回池
        budget.settle("c1", actualWireTokens = 100)
        assertEquals(1, budget.unsettledCount())
        budget.release("c2")
        assertEquals(0, budget.unsettledCount())
        assertEquals(0, budget.assertAllSettled(throwOnLeak = true))

        // 泄漏检测：存在未结清预留时 assertAllSettled(throwOnLeak = true) 必须报错
        budget.reserve("leaked")
        try {
            budget.assertAllSettled(throwOnLeak = true)
            fail("存在未结清预留应抛错")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("leaked"))
        }
        assertTrue(r1.budgetChars > 0 && r2.budgetChars > 0)
    }

    @Test
    fun `A3 zero budget produces a valid minimal envelope without throwing`() {
        val exhausted = ToolOutputBatchBudget(windowTokens = 10_000, initialUsedTokens = 10_500, batchSize = 1)
        val res = exhausted.reserve("z1")
        assertEquals(0, res.budgetChars)
        assertEquals(0, res.reservedTokens)

        val compressed = ToolOutputCompressor.compressSuccess(
            context = context,
            tool = toolPart("z1"),
            output = listOf(UIMessagePart.Text("data-".repeat(5_000))),
            maxChars = res.budgetChars,
            globalCapChars = res.globalCapChars,
            workspaceBound = true,
            hasShellAccess = true,
        )
        assertEquals(ToolOutputCompressor.Method.MINIMAL_ENVELOPE, compressed.method)
        assertFalse(compressed.complete)
        assertEquals("/tool_outputs/z1.txt", compressed.savedFilePath)
        val outText = compressed.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(outText.contains("minimal envelope"))
        assertTrue(outText.contains("/tool_outputs/z1.txt"))
        assertTrue(outText.contains("workspace_read_file"))
        assertTrue(File(File(tempDir, FileFolders.TOOL_OUTPUTS), "z1.txt").exists())
    }

    // ---- 阶段 2：A4 线载荷计量 + A5 唯一容量来源 + A6 结构预检 ----

    @Test
    fun `A4 A5 A6 wire payload measurement, capacity source, and structural pre-check`() {
        assertEquals(36_000, effectiveInputCapacity(40_000, MIN_REPLY_TOKENS))
        assertEquals(0, effectiveInputCapacity(2_000, MIN_REPLY_TOKENS))

        assertTrue(definitelyExceedsTokenLimit("a".repeat(400), tokenLimit = 100))
        assertFalse(definitelyExceedsTokenLimit("a".repeat(200), tokenLimit = 100))

        val measure = measureWirePayload(
            toolName = "workspace_read_file",
            toolCallId = "call_123",
            output = listOf(UIMessagePart.Text("hello world")),
        )
        assertTrue("短结果计量必须包含协议信封开销", measure.wireTokens >= TOOL_WIRE_ENVELOPE_OVERHEAD_TOKENS)
        assertTrue("必须同时提供 HTTP 字节口径（B9）", measure.wireBytes > "hello world".length)
        assertFalse(measure.fastPathExceeded)

        val hugeMeasure = measureWirePayload(
            toolName = "workspace_shell",
            toolCallId = "call_huge",
            output = listOf(UIMessagePart.Text("z".repeat(40_000))),
            tokenLimitHint = 2_000,
        )
        assertTrue("超限大载荷应触发 A6 快速结构预检", hugeMeasure.fastPathExceeded)
    }

    // ---- 阶段 3：B2 去重 ≠ 丢内容 + B6 保护原子 + B7 保头保尾 ----

    @Test
    fun `B2 folding consecutive duplicate lines fits budget with zero content loss`() {
        // 500 行相同警告（~25K 字符）超出 4K 预算，但折叠后仅几十字符 ⇒ DEDUPED、contentLoss=0、无需落盘
        val repeatedLog = (1..500).joinToString("\n") { "WARN: deprecated API call in module-alpha" }
        val res = ToolOutputCompressor.compressSuccess(
            context = context,
            tool = toolPart("dup1"),
            output = listOf(UIMessagePart.Text(repeatedLog)),
            maxChars = 4_000,
            workspaceBound = true,
            hasShellAccess = false,
        )
        assertEquals(ToolOutputCompressor.Method.DEDUPED, res.method)
        assertTrue("纯粹折叠重复行不是内容丢失", res.complete)
        assertEquals(0.0, res.contentLossRatio, 0.0001)
        assertEquals(499, res.dedupedLines)
        assertEquals(0, res.omittedChars)
        assertNull("纯去重满足预算时不落盘", res.savedFilePath)
    }

    @Test
    fun `B6 and B7 mechanical compression preserves head, tail, and protected atoms from omitted middle`() {
        val head = "=== BUILD START ===\n" + (1..60).joinToString("\n") { "compiling module-$it ok" }
        val middle = buildString {
            repeat(150) { appendLine("verbose progress step $it running checks...") }
            appendLine("CRITICAL: task failed with exit code 1 in app/src/main/Broken.kt:42 (ERR_COMPILE_FAIL, v2.4.1)")
            repeat(150) { appendLine("more verbose cleanup output $it ...") }
        }
        val tail = "\n=== FINAL SUMMARY ===\nTotal failures: 1\nBUILD FAILED"
        val full = "$head\n$middle\n$tail"

        val res = ToolOutputCompressor.compressSuccess(
            context = context,
            tool = toolPart("ht1"),
            output = listOf(UIMessagePart.Text(full)),
            maxChars = 6_000,
            workspaceBound = true,
            hasShellAccess = false,
        )
        assertEquals(ToolOutputCompressor.Method.MECHANICAL_HEAD_TAIL, res.method)
        assertFalse(res.complete)
        assertTrue(res.contentLossRatio > 0.0)
        val outText = res.parts.filterIsInstance<UIMessagePart.Text>().single().text
        // B7：保头又保尾（旧版 head-only 会把 BUILD FAILED 丢掉）
        assertTrue("应保留开头", outText.contains("=== BUILD START ==="))
        assertTrue("应保留结尾", outText.contains("BUILD FAILED"))
        // B6：被裁中段的退出码/错误码/路径受保护
        assertTrue("应保护中段退出码/错误码/路径: $outText", outText.contains("exit code 1") || outText.contains("ERR_COMPILE_FAIL"))
        assertTrue("应保留中段关键路径: $outText", outText.contains("app/src/main/Broken.kt:42"))
    }

    // ---- 阶段 3：B5 错误头进不可裁剪信封 + 避开 senera 1 号坑 ----

    @Test
    fun `B5 D2 error compression preserves error header in envelope while trimming stack trace`() {
        val header = "[IllegalStateException] Connection refused to db.internal:5432"
        val trace = (1..200).joinToString("\n") { "\tat com.example.Service.call$it(Service.kt:$it)" }
        val compressed = ToolOutputCompressor.compressError(
            errorHeader = header,
            stackTrace = trace,
            maxChars = 1_024,
        )
        assertTrue("错误头必须完整保留在不可裁剪信封中", compressed.startsWith(header))
        assertTrue(compressed.contains("[... error text truncated at 1024 chars]"))
        assertTrue("结尾栈帧（根因）也通过保头保尾保留", compressed.contains("Service.kt:200"))
    }

    @Test
    fun `offload failure degrades output availability without throwing or failing the tool execution`() {
        // ContextWrapper(null).filesDir 抛 NPE ⇒ 模拟磁盘不可写，必须优雅降级而非把成功工具改写成异常（senera 坑 #1）
        val brokenContext = ContextWrapper(null)
        val res = ToolOutputCompressor.compressSuccess(
            context = brokenContext,
            tool = toolPart("offload-fail"),
            output = listOf(UIMessagePart.Text("Y".repeat(40_000))),
            maxChars = 8_000,
            workspaceBound = true,
            hasShellAccess = true,
        )
        assertEquals(ToolOutputCompressor.Method.MECHANICAL_HEAD_TAIL, res.method)
        assertEquals(ToolOutputCompressor.OutputAvailability.PARTIAL_OFFLOAD_FAILED, res.availability)
        assertNull(res.savedFilePath)
        val outText = res.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(outText.contains("execution succeeded"))
        assertFalse("落盘失败绝不能撒谎声称已保存文件", outText.contains("Full output saved to:"))
    }
}
