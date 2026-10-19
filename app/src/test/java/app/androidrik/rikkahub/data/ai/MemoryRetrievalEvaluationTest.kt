package app.androidrik.rikkahub.data.ai

import app.androidrik.common.tokenizer.TokenizerRegistry
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.heuristicTokenCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.ln

/** Offline synthetic retrieval evaluation, not a test of generated answers or provider billing. */
class MemoryRetrievalEvaluationTest {
    private data class QueryCase(
        val id: String,
        val group: String,
        val query: String,
        val expectedIds: Set<String>,
    )

    private data class Corpus(
        val sha256: String,
        val now: Long,
        val budgets: List<Int>,
        val memories: List<AssistantMemory>,
        val cases: List<QueryCase>,
    )

    private data class Outcome(val resultIds: List<String>, val expectedIds: Set<String>)

    private data class Metrics(
        val positiveCount: Int,
        val negativeCount: Int,
        val falsePositiveCount: Int,
        val macroRecall: Double?,
        val mrr: Double?,
        val ndcg: Double?,
        val noAnswerFalsePositiveRate: Double?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("positiveCount", positiveCount)
            put("negativeCount", negativeCount)
            put("falsePositiveCount", falsePositiveCount)
            put("macroRecall", macroRecall.jsonNumber())
            put("mrr", mrr.jsonNumber())
            put("binaryNdcg", ndcg.jsonNumber())
            put("noAnswerFalsePositiveRate", noAnswerFalsePositiveRate.jsonNumber())
        }
    }

    @Test
    fun reportBaseline() = withHeuristic {
        val corpus = loadCorpus()
        val before = corpus.memories.map { it.copy() }
        val frozenIds = corpus.memories.filter { it.isAnchor }.map { it.id }.toSet()
        val outcomes = mutableListOf<Pair<QueryCase, Outcome>>()
        val regressionFailures = mutableListOf<String>()
        val cases = buildJsonArray {
            corpus.cases.forEach { case ->
                val query = memoryQueryTokens(case.query)
                val resultIds = rankMemoriesForSearch(corpus.memories, query, 5).map { it.id }
                assertEquals("Deterministic ranking: ${case.id}", resultIds,
                    rankMemoriesForSearch(corpus.memories, memoryQueryTokens(case.query), 5).map { it.id })
                assertEquals(resultIds.size, resultIds.distinct().size)
                assertTrue(resultIds.all { id -> corpus.memories.any { it.id == id } })
                outcomes += case to Outcome(resultIds, case.expectedIds)
                if (case.group == "regression") {
                    val satisfied = if (case.expectedIds.isEmpty()) resultIds.isEmpty() else {
                        resultIds.firstOrNull() in case.expectedIds && resultIds.containsAll(case.expectedIds)
                    }
                    if (!satisfied) {
                        val content = corpus.memories.filter { it.id in case.expectedIds }
                            .joinToString { "${it.id}: ${it.content.take(180)}" }
                        regressionFailures += "${case.id}: query=${case.query}, expected=${case.expectedIds}, " +
                            "actual=$resultIds, relevantContent=[$content]"
                    }
                }
                add(buildJsonObject {
                    put("id", case.id)
                    put("group", case.group)
                    put("query", case.query)
                    put("resultIds", resultIds.idsJson())
                    put("expectedIds", case.expectedIds.idsJson())
                    put("notRetrievedIds", (case.expectedIds - resultIds.toSet()).idsJson())
                    put("plans", buildJsonArray {
                        corpus.budgets.forEach { budget ->
                            val plan = buildMemoryInjectionPlan(
                                corpus.memories, budget, corpus.now, case.query, frozenIds,
                            )
                            validatePlan(corpus.memories, plan, budget, case.id)
                            val fullIds = plan.fullText.map { it.id }
                            val completeIds = (plan.anchors + plan.fullText).map { it.id }
                            val relevantComplete = completeIds.count { it in case.expectedIds }
                            val visibleIds = (plan.anchors + plan.fullText + plan.indexed).map { it.id }
                            add(buildJsonObject {
                                put("budget", budget)
                                put("anchors", plan.anchors.map { it.id }.idsJson())
                                put("fullText", fullIds.idsJson())
                                put("indexed", plan.indexed.map { it.id }.idsJson())
                                put("omittedCount", plan.omittedCount)
                                put("totalEstimatedTokens", plan.totalEstimatedTokens)
                                put("l2Recall", recall(fullIds, case.expectedIds).jsonNumber())
                                put("fullTextRecall", recall(completeIds, case.expectedIds).jsonNumber())
                                put("relevantFullTextCount", relevantComplete)
                                val efficiency = if (case.expectedIds.isEmpty() || plan.totalEstimatedTokens == 0) null
                                    else relevantComplete * 1000.0 / plan.totalEstimatedTokens
                                put("relevantFullTextPer1kTokens", efficiency.jsonNumber())
                                put("visibleRecall", recall(visibleIds, case.expectedIds).jsonNumber())
                            })
                        }
                    })
                })
            }
        }
        assertEquals("Ranking/planning must not mutate usage or business/source fields", before, corpus.memories)
        assertEquals("Budget admission must not rewrite the frozen anchor set", frozenIds,
            corpus.memories.filter { it.isAnchor }.map { it.id }.toSet())
        val report = buildJsonObject {
            put("version", 1)
            put("corpusSha256", corpus.sha256)
            put("queryCount", corpus.cases.size)
            put("memoryCount", corpus.memories.size)
            put("now", corpus.now)
            put("estimator", MEMORY_ESTIMATION_HEURISTIC)
            put("measurement", "local rendered components, not provider billing or generated answer quality")
            put("injectionOrder", "rendering order only; index visibility is not full-text recall")
            put("cases", cases)
            put("regressionFailures", regressionFailures.idsJson())
            put("summary", buildJsonObject {
                put("overall", summarize(outcomes.map { it.second }))
                put("groups", buildJsonObject {
                    outcomes.map { it.first.group }.distinct().sorted().forEach { group ->
                        put(group, summarize(outcomes.filter { it.first.group == group }.map { it.second }))
                    }
                })
            })
        }
        val output = File("build/reports/memory/retrieval-evaluation.json")
        assertTrue(output.parentFile.isDirectory || output.parentFile.mkdirs())
        output.writeText(report.toString() + "\n", Charsets.UTF_8)
        assertTrue(regressionFailures.joinToString("\n"), regressionFailures.isEmpty())
    }

    @Test
    fun metricsHaveKnownValues() = withHeuristic {
        val outcomes = listOf(
            Outcome(listOf("noise", "a", "b"), setOf("a", "b")),
            Outcome(emptyList(), setOf("z")),
            Outcome(listOf("noise"), emptySet()),
            Outcome(emptyList(), emptySet()),
        )
        val metrics = measure(outcomes, 3)
        assertEquals(2, metrics.positiveCount)
        assertEquals(2, metrics.negativeCount)
        assertEquals(1, metrics.falsePositiveCount)
        assertEquals(0.5, requireNotNull(metrics.macroRecall), 1e-12)
        assertEquals(0.25, requireNotNull(metrics.mrr), 1e-12)
        assertEquals(0.3467132018086354, requireNotNull(metrics.ndcg), 1e-12)
        assertEquals(0.5, requireNotNull(metrics.noAnswerFalsePositiveRate), 1e-12)
        assertEquals(0.0, requireNotNull(measure(outcomes, 1).macroRecall), 0.0)
        val empty = measure(emptyList(), 5)
        assertEquals(0, empty.positiveCount)
        assertEquals(0, empty.negativeCount)
        assertNull(empty.macroRecall)
        assertNull(empty.mrr)
        assertNull(empty.ndcg)
        assertNull(empty.noAnswerFalsePositiveRate)
        assertNull(measure(listOf(outcomes[0]), 3).noAnswerFalsePositiveRate)
        assertNull(measure(listOf(outcomes[2]), 3).macroRecall)
    }

    @Test
    fun rankingTieAndLimitContract() = withHeuristic {
        val memories = listOf(
            AssistantMemory("new-complete", "alpha beta", sortKey = 99),
            AssistantMemory("old-partial", "alpha", sortKey = 1),
            AssistantMemory("old-complete", "alpha beta", sortKey = 2),
        )
        val query = memoryQueryTokens("alpha beta")
        val ranked = rankMemoriesForSearch(memories, query, 5).map { it.id }
        assertEquals(listOf("old-complete", "new-complete", "old-partial"), ranked)
        assertEquals(listOf("old-complete"), rankMemoriesForSearch(memories, query, 1).map { it.id })
        assertTrue(rankMemoriesForSearch(memories, query, 0).isEmpty())
        assertTrue(rankMemoriesForSearch(memories, query, -1).isEmpty())
        assertEquals(ranked,
            rankMemoriesForSearch(memories, memoryQueryTokens("alpha alpha beta beta"), 5).map { it.id })
        assertTrue(rankMemoriesForSearch(memories, memoryQueryTokens("unrelated"), 5).isEmpty())
    }

    @Test
    fun fixedChallengeCasesRemainRetrievable() = withHeuristic {
        val corpus = loadCorpus()
        val challenges = corpus.cases.filter { it.group == "challenge" }
        assertEquals(8, challenges.size)
        challenges.forEach { case ->
            val ids = rankMemoriesForSearch(corpus.memories, memoryQueryTokens(case.query), 5).map { it.id }
            assertTrue("${case.id}: expected=${case.expectedIds}, actual=$ids", ids.containsAll(case.expectedIds))
        }
    }

    @Test
    fun everyQueryRetainsOrImprovesFrozenSearchMetrics() = withHeuristic {
        val corpus = loadCorpus()
        val baseline = loadBaseline(corpus)
        val byId = corpus.cases.associateBy { it.id }
        val oldCases = baseline.getValue("searchCases").jsonArray
        assertEquals(byId.keys, oldCases.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet())
        oldCases.forEach { element ->
            val old = element.jsonObject
            val case = byId.getValue(old.getValue("id").jsonPrimitive.content)
            val previousIds = old.getValue("resultIds").jsonArray.map { it.jsonPrimitive.content }
            val currentIds = rankMemoriesForSearch(corpus.memories, memoryQueryTokens(case.query), 5).map { it.id }
            listOf(1, 3, 5).forEach { k ->
                val previous = measure(listOf(Outcome(previousIds, case.expectedIds)), k)
                val current = measure(listOf(Outcome(currentIds, case.expectedIds)), k)
                if (case.expectedIds.isEmpty()) {
                    assertTrue("${case.id}: no-answer regression", currentIds.isEmpty())
                } else {
                    listOf("recall" to (current.macroRecall to previous.macroRecall),
                        "MRR" to (current.mrr to previous.mrr), "nDCG" to (current.ndcg to previous.ndcg))
                        .forEach { (metric, values) ->
                            assertTrue("${case.id}: $metric@$k regressed",
                                requireNotNull(values.first) + 1e-12 >= requireNotNull(values.second))
                        }
                }
            }
        }
    }

    @Test
    fun fixedBudgetInjectionDoesNotRegressBaselineQualityOrEfficiency() = withHeuristic {
        val corpus = loadCorpus()
        val baseline = loadBaseline(corpus)
        val positives = corpus.cases.filter { it.expectedIds.isNotEmpty() }
        val frozenIds = corpus.memories.filter { it.isAnchor }.map { it.id }.toSet()
        baseline.getValue("injectionMeansPositiveCases").jsonArray.forEach { element ->
            val old = element.jsonObject
            val budget = old.getValue("budget").jsonPrimitive.int
            val plans = positives.map { case ->
                case to buildMemoryInjectionPlan(corpus.memories, budget, corpus.now, case.query, frozenIds)
            }
            assertEquals(positives.size, old.getValue("positiveCount").jsonPrimitive.int)
            val full = plans.map { (case, plan) ->
                requireNotNull(recall((plan.anchors + plan.fullText).map { it.id }, case.expectedIds))
            }.average()
            val visible = plans.map { (case, plan) ->
                requireNotNull(recall((plan.anchors + plan.fullText + plan.indexed).map { it.id }, case.expectedIds))
            }.average()
            val efficiency = plans.mapNotNull { (case, plan) ->
                if (plan.totalEstimatedTokens == 0) null else
                    (plan.anchors + plan.fullText).count { it.id in case.expectedIds } *
                        1000.0 / plan.totalEstimatedTokens
            }.meanOrNull()
            assertTrue("Full-text recall at budget=$budget", full + 1e-12 >=
                old.getValue("fullTextRecall").jsonPrimitive.content.toDouble())
            assertTrue("Visible recall at budget=$budget", visible + 1e-12 >=
                old.getValue("visibleRecall").jsonPrimitive.content.toDouble())
            val previousEfficiency = old.getValue("relevantFullTextPer1kTokens")
            if (previousEfficiency != JsonNull) {
                assertTrue("Efficiency at budget=$budget", requireNotNull(efficiency) + 1e-12 >=
                    previousEfficiency.jsonPrimitive.content.toDouble())
            }
        }
    }

    private fun loadBaseline(corpus: Corpus): JsonObject {
        val text = requireNotNull(javaClass.getResourceAsStream("/memory/retrieval-baseline-v1.json"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text).jsonObject.also {
            assertEquals(corpus.sha256, it.getValue("corpusSha256").jsonPrimitive.content)
            assertEquals(corpus.now, it.getValue("now").jsonPrimitive.long)
            assertEquals(MEMORY_ESTIMATION_HEURISTIC, it.getValue("estimator").jsonPrimitive.content)
            assertEquals(corpus.memories.size, it.getValue("memoryCount").jsonPrimitive.int)
            assertEquals(corpus.cases.size, it.getValue("queryCount").jsonPrimitive.int)
        }
    }

    private fun loadCorpus(): Corpus {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/memory/retrieval-golden-v1.json")) {
            "Frozen corpus missing from test classpath"
        }.use { it.readBytes() }
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        val now = root.getValue("now").jsonPrimitive.long
        val memories = root.getValue("memories").jsonArray.map { element ->
            val item = element.jsonObject
            val repetitions = item["contentRepeat"]?.jsonPrimitive?.intOrNull ?: 1
            require(repetitions in 1..10_000)
            AssistantMemory(
                id = item.getValue("id").jsonPrimitive.content,
                content = item.getValue("content").jsonPrimitive.content.repeat(repetitions),
                sortKey = item.getValue("sortKey").jsonPrimitive.int,
                description = item["description"]?.jsonPrimitive?.content ?: "",
                importance = item["importance"]?.jsonPrimitive?.floatOrNull ?: 0.6f,
                updatedAt = now,
                isAnchor = item["isAnchor"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        val memoryIds = memories.map { it.id }.toSet()
        assertEquals(memories.size, memoryIds.size)
        assertEquals(memories.size, memories.map { it.sortKey }.distinct().size)
        val cases = root.getValue("cases").jsonArray.map { element ->
            val item = element.jsonObject
            val expectedIds = item.getValue("relevantIds").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expectedIds.size, expectedIds.distinct().size)
            assertTrue("Unknown expected id: $item", memoryIds.containsAll(expectedIds))
            QueryCase(
                item.getValue("id").jsonPrimitive.content,
                item.getValue("group").jsonPrimitive.content,
                item.getValue("query").jsonPrimitive.content,
                expectedIds.toSet(),
            )
        }
        assertEquals(cases.size, cases.map { it.id }.distinct().size)
        assertTrue(cases.all { it.group in setOf("regression", "challenge", "limitation") })
        val budgets = root.getValue("budgets").jsonArray.map { it.jsonPrimitive.int }
        assertTrue(budgets.isNotEmpty() && budgets.all { it >= 0 })
        assertEquals(budgets.size, budgets.distinct().size)
        assertEquals(23, memories.size)
        assertEquals(43, cases.size)
        // Pin the labels, allowing only CRLF/LF checkout differences across platforms.
        val canonicalBytes = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(canonicalBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Frozen v1 fixture changed; version the corpus instead of retuning labels",
            "28932bc7a6a3eb1acd7dd4e620fe659ab07d99e4d0501ee4f82de1ee0a97f65e", sha)
        return Corpus(sha, now, budgets, memories, cases)
    }

    private fun validatePlan(memories: List<AssistantMemory>, plan: MemoryInjectionPlan, budget: Int, case: String) {
        assertEquals("$case estimator", MEMORY_ESTIMATION_HEURISTIC, plan.estimationMode)
        assertTrue("$case budget=$budget", plan.totalEstimatedTokens <= maxOf(0, budget))
        val selected = plan.anchors + plan.fullText + plan.indexed
        assertEquals("$case layer overlap", selected.size, selected.map { it.id }.distinct().size)
        assertTrue("$case unknown or modified record", selected.all { it in memories })
        assertEquals("$case hidden count", memories.size - selected.size, plan.omittedCount)
        assertEquals(heuristicTokenCount(plan.disciplineText), plan.disciplineTokens)
        assertEquals(heuristicTokenCount(plan.anchorText), plan.anchorTokens)
        assertEquals(heuristicTokenCount(plan.dataText), plan.dataTokens)
    }

    private fun summarize(outcomes: List<Outcome>): JsonObject = buildJsonObject {
        listOf(1, 3, 5).forEach { k -> put(k.toString(), measure(outcomes, k).toJson()) }
    }

    private fun measure(outcomes: List<Outcome>, k: Int): Metrics {
        require(k > 0)
        val positives = outcomes.filter { it.expectedIds.isNotEmpty() }
        val negatives = outcomes.filter { it.expectedIds.isEmpty() }
        val recalls = positives.map { requireNotNull(recall(it.resultIds.take(k), it.expectedIds)) }
        val reciprocalRanks = positives.map { outcome ->
            val first = outcome.resultIds.take(k).indexOfFirst { it in outcome.expectedIds }
            if (first < 0) 0.0 else 1.0 / (first + 1)
        }
        val ndcgs = positives.map { outcome ->
            val dcg = outcome.resultIds.take(k).mapIndexed { index, id ->
                if (id in outcome.expectedIds) 1.0 / log2(index + 2.0) else 0.0
            }.sum()
            val ideal = (0 until minOf(k, outcome.expectedIds.size)).sumOf { 1.0 / log2(it + 2.0) }
            dcg / ideal
        }
        val falsePositives = negatives.count { it.resultIds.take(k).isNotEmpty() }
        return Metrics(
            positives.size, negatives.size, falsePositives,
            recalls.meanOrNull(), reciprocalRanks.meanOrNull(), ndcgs.meanOrNull(),
            if (negatives.isEmpty()) null else falsePositives.toDouble() / negatives.size,
        )
    }

    private fun recall(ids: List<String>, expected: Set<String>): Double? =
        if (expected.isEmpty()) null else ids.toSet().count { it in expected }.toDouble() / expected.size

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private fun List<Double>.meanOrNull(): Double? = if (isEmpty()) null else average()

    private fun Iterable<String>.idsJson(): JsonArray = JsonArray(map { JsonPrimitive(it) })

    private inline fun <T> withHeuristic(block: () -> T): T {
        TokenizerRegistry.release()
        return try { block() } finally { TokenizerRegistry.release() }
    }

}

private fun Double?.jsonNumber(): JsonElement = if (this == null) JsonNull else {
    require(isFinite()) { "Non-finite evaluation metric" }
    JsonPrimitive(this)
}
