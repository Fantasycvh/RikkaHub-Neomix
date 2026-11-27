package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.resolveCompressionRefineModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/**
 * 多步压缩二期测试（`design-multi-pass-compression.md` §8 测试计划）：
 * 1. token 预算分块（含事实 D：条数未超 256 但估算超帽必须切块）+ 超长单条（>8K 字符）独占 chunk；
 * 2. 单条消息按 token 帽截断并标 `[truncated]`，不再无条件 2000 字截断；
 * 3. 滚动摘要：prior 仅首块注入、`PRIOR SUMMARY` 串行 carry 传递、用户附加指令每块保留、fail-fast 传播；
 * 4. 输出侧退化自检（containment >= 0.95 / 短周期循环）：命中跳块、carry 保持上一值、标注 `[skipped: degenerate]`；
 * 5. `<anchors>` 固定 schema（六类型、≤20 行、去重、入档前精确剥离）；
 * 6. 精炼遍三道护栏（回声指令、长度 <50% 丢弃、硬事实不退化、fail-open 保留 Sn）；
 * 7. `resolveCompressionRefineModel` 回退链（fastModelId → compressModelId → followModel → chatModel）；
 * 8. `runCompressionLadder` C0(ROLLING_REFINED) → C1(ROLLING) → C2(SINGLE) → C4(TRUNCATED) 编排与接线守卫。
 */
class MultiPassCompressionTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    // ---- §3.2 token 预算分块与超长单条独占 ----

    @Test
    fun `token budgeted chunking splits even when message count is well below 256`() {
        // 每条 4000 字符 ≈ 1000 token；20 条 ≈ 20K token，远低于 256 条上限，但超 5000 token 预算必须切块
        val messages = (1..20).map { msg("m$it:" + "a".repeat(3_990)) }
        val chunks = splitIntoChunks(messages, maxPerChunk = 256, maxTokens = 5_000)
        assertTrue("应切出多块（事实 D），实际=${chunks.size}", chunks.size >= 4)
        assertEquals(20, chunks.sumOf { it.size })
        assertEquals(messages.map { it.toText() }, chunks.flatten().map { it.toText() })
    }

    @Test
    fun `oversized single message gets its own dedicated chunk`() {
        val messages = listOf(
            msg("short-1"),
            msg("short-2"),
            msg("HUGE:" + "x".repeat(9_000)),
            msg("short-3"),
        )
        val chunks = splitIntoChunks(messages, maxPerChunk = 256, maxTokens = 24_000, oversizedMessageChars = 8_000)
        assertEquals(3, chunks.size)
        assertEquals(listOf("short-1", "short-2"), chunks[0].map { it.toText() })
        assertEquals(1, chunks[1].size)
        assertTrue(chunks[1].single().toText().startsWith("HUGE:"))
        assertEquals(listOf("short-3"), chunks[2].map { it.toText() })
    }

    @Test
    fun `formatMessageForCompression preserves text beyond 2000 chars up to token cap and marks truncated beyond cap`() {
        val medium = msg("M".repeat(4_000))
        val formattedMedium = formatMessageForCompression(medium, maxTokens = 4_000)
        assertFalse("4000 字符在 token 帽内不应被旧版 2000 字截断", formattedMedium.contains("[truncated]"))
        assertTrue(formattedMedium.length >= 4_000)

        val giant = msg((1..300).joinToString("\n") { "line-$it " + "w".repeat(60) })
        val formattedGiant = formatMessageForCompression(giant, maxTokens = 500)
        assertTrue(formattedGiant.endsWith("[truncated]"))
        assertTrue(formattedGiant.length < giant.toText().length)
    }

    // ---- §3.1 滚动摘要 carry 与 prompt 契约 ----

    @Test
    fun `rolling compression passes prior only to chunk 0 and carries Si sequentially`() = runBlocking {
        val seenCarries = mutableListOf<Pair<Int, String>>()
        val chunks = listOf(
            listOf(msg("c0")),
            listOf(msg("c1")),
            listOf(msg("c2")),
        )
        val result = runRollingCompression(
            chunks = chunks,
            priorSummary = "OLD PRIOR",
            summarizeChunk = { idx, _, carry ->
                seenCarries += idx to carry
                """
                I remember chunk $idx merged with ($carry).
                <anchors>
                - [fact] fact-$idx
                - [file] src/chunk$idx.kt
                </anchors>
                """.trimIndent()
            },
        )
        assertEquals(3, seenCarries.size)
        assertEquals("OLD PRIOR", seenCarries[0].second)
        assertEquals("I remember chunk 0 merged with (OLD PRIOR).", seenCarries[1].second)
        assertTrue(seenCarries[2].second.contains("chunk 1"))
        // 最终结果已精确剥离 <anchors>
        assertFalse(result.summaryBody.contains("<anchors>"))
        assertEquals(6, result.anchors.size)
        assertEquals(0, result.skippedChunks)
    }

    @Test
    fun `additional context contract injects PRIOR CHECKPOINT on chunk 0, PRIOR SUMMARY after, and user prompt every chunk`() {
        val ctx0 = buildRollingAdditionalContext(0, "keep dates", "old summary")
        assertTrue(ctx0.contains("Additional instructions from user: keep dates"))
        assertTrue(ctx0.contains("PRIOR CHECKPOINT"))
        assertFalse(ctx0.contains("PRIOR SUMMARY"))

        val ctx1 = buildRollingAdditionalContext(1, "keep dates", "S0 summary")
        assertTrue(ctx1.contains("Additional instructions from user: keep dates"))
        assertTrue(ctx1.contains("PRIOR SUMMARY"))
        assertFalse(ctx1.contains("PRIOR CHECKPOINT"))
    }

    @Test
    fun `rolling compression is fail-fast on chunk exception`() = runBlocking {
        val chunks = listOf(listOf(msg("c0")), listOf(msg("c1")), listOf(msg("c2")))
        var calls = 0
        try {
            runRollingCompression(
                chunks = chunks,
                priorSummary = "",
                summarizeChunk = { idx, _, _ ->
                    calls++
                    if (idx == 1) throw IllegalStateException("rate limit")
                    "summary-$idx"
                },
            )
            fail("应 fail-fast 抛错")
        } catch (expected: IllegalStateException) {
            assertEquals("rate limit", expected.message)
            assertEquals(2, calls)
        }
    }

    // ---- §4.2 退化自检 ----

    @Test
    fun `degenerate chunk output is skipped while preserving previous carry`() = runBlocking {
        val degenerateLoop = "I will check the config file again. ".repeat(30)
        assertTrue("循环复读应命中退化自检", isDegenerateSummary(degenerateLoop))

        val normalSummary = buildString {
            appendLine("# Primary Request and Intent")
            appendLine("- The user asked me to refactor CompressionPlanner.kt with token-budgeted chunking.")
            appendLine("# Files and Code")
            appendLine("- app/src/main/java/app/androidrik/rikkahub/data/ai/compression/CompressionPlanner.kt: added maxTokens.")
            appendLine("# Critical Context")
            appendLine("- I decided to keep threshold at 0.95 until field observations are collected.")
        }
        assertFalse("正常摘要不应被误杀", isDegenerateSummary(normalSummary))

        val chunks = listOf(listOf(msg("c0")), listOf(msg("c1")), listOf(msg("c2")))
        val carriesSeen = mutableListOf<String>()
        val result = runRollingCompression(
            chunks = chunks,
            priorSummary = "",
            summarizeChunk = { idx, _, carry ->
                carriesSeen += carry
                when (idx) {
                    0 -> normalSummary
                    1 -> degenerateLoop // 命中退化 → 跳块，carry 保持 chunk 0 的 normalSummary
                    else -> "Merged final with chunk 2 and ${carry.take(20)}"
                }
            },
        )
        assertEquals(1, result.skippedChunks)
        assertEquals(normalSummary.trim(), carriesSeen[2])
        assertTrue(result.summaryBody.contains(SKIPPED_DEGENERATE_MARKER))
    }

    // ---- §4.3 锚点 schema 与剥离 ----

    @Test
    fun `anchor extraction enforces 6 allowed types, deduplicates, caps at 20, and strips fence cleanly`() {
        val raw = buildString {
            appendLine("# Summary")
            appendLine("I completed the migration.")
            appendLine()
            appendLine("<anchors>")
            appendLine("- [fact] package is app.androidrik")
            appendLine("- [fact] package is app.androidrik") // dup
            appendLine("- [unknown] should be ignored")
            for (i in 1..25) {
                appendLine("- [task] task item #$i")
            }
            appendLine("</anchors>")
        }
        val anchors = extractAnchors(raw)
        assertEquals(MAX_ANCHOR_LINES, anchors.size)
        assertTrue(anchors.all { it.type in ALLOWED_ANCHOR_TYPES })
        assertEquals("fact", anchors.first().type)
        val stripped = stripAnchorsSection(raw)
        assertFalse(stripped.contains("<anchors>"))
        assertFalse(stripped.contains("task item #1"))
        assertTrue(stripped.contains("I completed the migration."))
    }

    // ---- §4.1 精炼遍三道护栏与快速模型解析 ----

    @Test
    fun `refine pass rejects blank or overly short output and keeps original Sn`() = runBlocking {
        val original = "I updated app/src/main/Foo.kt to version 2.5.1 with 12000 rows and 87.5% pass rate."
        val rolling = RollingCompressionResult(
            summaryBody = original,
            anchors = listOf(CompressionAnchor("file", "app/src/main/Foo.kt")),
            skippedChunks = 0,
            containmentScores = listOf(0.1),
        )
        val shortOutcome = runRefinePass(
            sourceText = original,
            rollingResult = rolling,
            targetTokens = 2000,
            locale = "en",
            refineCall = { "Too short" },
        )
        assertFalse(shortOutcome.refined)
        assertEquals(original, shortOutcome.summary)

        val failOpenOutcome = runRefinePass(
            sourceText = original,
            rollingResult = rolling,
            targetTokens = 2000,
            locale = "en",
            refineCall = { throw IllegalStateException("fast model timeout") },
        )
        assertFalse(failOpenOutcome.refined)
        assertEquals(original, failOpenOutcome.summary)
    }

    @Test
    fun `refine pass rejects output that regresses hard-fact coverage`() = runBlocking {
        val source = "Paths var/log/app.log and src/main/A.kt, version 2.5.1, count 12000, rate 87.5%"
        val original = "I checked var/log/app.log and src/main/A.kt on version 2.5.1 (12000 rows, 87.5%)."
        val rolling = RollingCompressionResult(original, emptyList(), 0, listOf(0.0))
        val outcome = runRefinePass(
            sourceText = source,
            rollingResult = rolling,
            targetTokens = 2000,
            locale = "en",
            refineCall = {
                "I checked the logs and source files on the latest version with good overall results and high success rate."
            },
        )
        assertFalse("丢掉硬事实的精炼输出必须拒收", outcome.refined)
        assertEquals(original, outcome.summary)
    }

    @Test
    fun `refine prompt includes first-person rule, echo guardrail, and lost marker guidance`() {
        val prompt = buildRefinePrompt(
            summaryBody = "I fixed Foo.kt",
            anchors = listOf(CompressionAnchor("file", "src/Foo.kt")),
            missingHardFacts = listOf("2.5.1"),
            targetTokens = 2000,
            locale = "en",
        )
        assertTrue(prompt.contains("first person"))
        assertTrue(prompt.contains("VERBATIM"))
        assertTrue(prompt.contains("(lost)"))
        assertTrue(prompt.contains("- [file] src/Foo.kt"))
        assertTrue(prompt.contains("- 2.5.1"))
    }

    @Test
    fun `resolveCompressionRefineModel chains fastModelId then compressModelId then followModel then chatModel`() {
        val fast = Model(modelId = "fast", displayName = "Fast", id = Uuid.random())
        val compress = Model(modelId = "comp", displayName = "Comp", id = Uuid.random())
        val follow = Model(modelId = "follow", displayName = "Follow", id = Uuid.random())
        val chat = Model(modelId = "chat", displayName = "Chat", id = Uuid.random())
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            models = listOf(fast, compress, follow, chat),
            apiKey = "k",
        )
        val withFast = Settings(
            providers = listOf(provider),
            fastModelId = fast.id,
            compressModelId = compress.id,
            chatModelId = chat.id,
        )
        assertEquals(fast.id, withFast.resolveCompressionRefineModel(follow)?.id)

        val withoutFast = withFast.copy(fastModelId = null)
        assertEquals(compress.id, withoutFast.resolveCompressionRefineModel(follow)?.id)

        val onlyFollow = withoutFast.copy(compressModelId = null)
        assertEquals(follow.id, onlyFollow.resolveCompressionRefineModel(follow)?.id)
        assertEquals(chat.id, onlyFollow.resolveCompressionRefineModel(null)?.id)
    }

    // ---- 阶梯 C0 → C1 → C2 → C4 与接线守卫 ----

    @Test
    fun `ladder returns ROLLING_REFINED when refine succeeds and degrades to ROLLING on fail-open`() = runBlocking {
        val src = "var/log/app.log 2.5.1 12000 87.5%"
        val good = "I preserved var/log/app.log, 2.5.1, 12000, and 87.5%."
        val refined = "I verified var/log/app.log, 2.5.1, 12000, and 87.5% cleanly."

        val c0 = runCompressionLadder(
            sourceText = src,
            chunkedSummarize = { good },
            singleSummarize = { error("C2 should not run") },
            truncatedFallback = "FB",
            primaryTier = CompressionTier.ROLLING,
            refineSummary = { refined },
        )
        assertEquals(CompressionTier.ROLLING_REFINED, c0.tier)
        assertEquals(refined, c0.summary)

        val c1 = runCompressionLadder(
            sourceText = src,
            chunkedSummarize = { good },
            singleSummarize = { error("C2 should not run") },
            truncatedFallback = "FB",
            primaryTier = CompressionTier.ROLLING,
            refineSummary = { null },
        )
        assertEquals(CompressionTier.ROLLING, c1.tier)
        assertEquals(good, c1.summary)
    }

    @Test
    fun `cancellation in refine pass propagates instead of silently swallowing`() {
        try {
            runBlocking {
                runRefinePass(
                    sourceText = "src",
                    rollingResult = RollingCompressionResult("body", emptyList(), 0, emptyList()),
                    targetTokens = 2000,
                    locale = "en",
                    refineCall = { throw CancellationException("cancelled") },
                )
            }
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    @Test
    fun `ChatService wires rolling compression, token-budgeted chunking, and refine pass`() {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory } ?: error("source root not found")
        val source = root.resolve("app/androidrik/rikkahub/service/ChatService.kt").readText()
        assertTrue(source.contains("runRollingCompression("))
        assertTrue(source.contains("runRefinePass("))
        assertTrue(source.contains("resolveCompressionRefineModel("))
        assertTrue(source.contains("chunkTokenBudget("))
        assertTrue(source.contains("singleMessageTokenCap("))
    }
}
