package app.androidrik.rikkahub.data.ai

import android.util.Log
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.ai.tools.buildMemoryTools
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryReadSource
import app.androidrik.rikkahub.data.repository.MemoryRepository
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * 做梦（V3）的生产实现：**建消息 → 调 `generateText` → 数改动**。
 *
 * 所有策略（切多少轮、水位推进到哪、审批怎么降级、步数上限）都在 [MemoryConsolidator]；
 * 这里只把 [MemoryConsolidationRequest] 翻译成一次工具循环调用。刻意做得薄，是因为它依赖 Android
 * （`GenerationHandler`），很难进 JVM 单测——薄到只剩接线时，用一条源码扫描守卫就能钉住。
 *
 * 三条刻意的选择：
 * - **工具就是聊天那个 `memory_tool`**（同一份描述与 schema）：记忆的写入纪律只有一处真相，
 *   后台整理不该有第二套「什么能记」的规则；只提案模式下同一个工具把写入改记成建议（见 `proposalSink`）；
 * - **临时 profile 而不是用户的助手**：做梦要的是模型配置，不是人设。带上用户的人设/注入/世界书
 *   既贵又可能把整理任务带偏；`enableMemory = false` 顺带避免在整理调用里再叠一层聊天式记忆注入
 *   （库现状由我们自己显式给）；
 * - **护栏停止当作失败**：已落库的改动算数，但这轮没走完 ⇒ 抛异常让调度器退回重试（水位不推进、
 *   内容不丢），而不是当成「整理完了」把内容永久跳过。
 */
class MemoryConsolidationGenerationAgent(
    private val generationHandler: GenerationHandler,
    private val memoryRepository: MemoryRepository,
    private val json: Json,
) : MemoryConsolidationAgent {

    companion object {
        private const val TAG = "MemoryConsolidator"
    }

    override suspend fun run(request: MemoryConsolidationRequest): MemoryConsolidationOutcome {
        var applied = 0
        val proposals = mutableListOf<MemoryConsolidationProposal>()
        val tools = buildMemoryTools(
            json = json,
            onCreation = { content, importance, description ->
                memoryRepository.addMemory(request.memoryId, content, importance, description,
                    actor = "CONSOLIDATOR", reason = "memory_tool create").also { applied++ }
            },
            onUpdate = { _, _, _, _ -> error("Versioned update callback is required") },
            onDelete = { error("Versioned delete callback is required") },
            versionedOnUpdate = { id, content, importance, description, revision ->
                memoryRepository.updateContentInStore(request.memoryId, id, content, importance, description,
                    expectedRevision = revision, actor = "CONSOLIDATOR", reason = "memory_tool edit")
                    .also { applied++ }
            },
            versionedOnDelete = { id, revision ->
                check(memoryRepository.deleteMemoryInStore(request.memoryId, id,
                    expectedRevision = revision, actor = "CONSOLIDATOR", reason = "memory_tool delete")) {
                    "Memory record $id not found in store"
                }
                applied++
            },
            initialRevisions = request.initialRevisions,
            // 整理自读单独计数，不能伪装成聊天使用或抬升信用/活跃度。
            onRead = { id ->
                memoryRepository.readMemoryInStore(request.memoryId, id, source = MemoryReadSource.CONSOLIDATION)
            },
            // 整理 agent 尤其需要检索：它拿到的记忆库快照同样是**预算截断**的
            // （`DEFAULT_LIBRARY_TOKENS = 4000`，真机 50 条库 ≈ 58.6K token ⇒ 大部分记录看不见），
            // 而「找重复/找已被覆盖的记录」恰恰要求能看到快照之外的条目，否则会把已存在的
            // 事实重复 create、或漏掉该 retire 的记录。
            onSearch = { query, limit -> memoryRepository.searchMemories(request.memoryId, query, limit) },
            proposalSink = if (request.proposeOnly) { proposal -> proposals += proposal } else null,
        )

        val systemPrompt = if (request.proposeOnly) {
            MEMORY_CONSOLIDATION_SYSTEM_PROMPT + "\n\n" + MEMORY_CONSOLIDATION_PROPOSAL_NOTE
        } else {
            MEMORY_CONSOLIDATION_SYSTEM_PROMPT
        }
        val profile = Assistant(
            id = Uuid.random(),
            name = "memory-consolidator",
            systemPrompt = systemPrompt,
            enableMemory = false,
            streamOutput = false,
        )

        var notice: GenerationNotice? = null
        generationHandler.generateText(
            settings = request.settings,
            model = request.model,
            messages = listOf(UIMessage.user(buildUserMessage(request))),
            assistant = profile,
            memories = emptyList(),
            tools = tools,
            maxSteps = request.maxSteps,
            conversationSystemPrompt = null,
            conversationModeInjectionIds = emptySet(),
            conversationLorebookIds = emptySet(),
            workspaceCwd = null,
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Notice -> notice = chunk.notice
                else -> Unit
            }
        }

        notice?.let { stopped ->
            Log.w(TAG, "consolidation stopped by guard: $stopped")
            throw IllegalStateException("memory consolidation stopped by guard: $stopped")
        }
        Log.i(TAG, "consolidated ${request.memoryId}: applied=$applied proposals=${proposals.size}")
        return MemoryConsolidationOutcome(appliedChanges = applied, proposals = proposals)
    }

    /** 四段：库现状（含渐进披露的清单层）→ 校准表 → **用户拒绝过的改动** → 新轮次。 */
    private fun buildUserMessage(request: MemoryConsolidationRequest): String = buildString {
        appendLine("**Library** (change it only through `memory_tool`)")
        appendLine(request.libraryPrompt)
        if (request.calibrationTable.isNotBlank()) {
            appendLine()
            appendLine("**Calibration** (id | importance | reads | topic)")
            appendLine(request.calibrationTable)
        }
        // 用户拒绝过的改动：**不要重复提出**。
        // 为什么必须有（2026-10-16 真机发现的缺陷）：同一条 `delete` 被两轮整理**原地重提**——
        // 上一轮被用户拒绝、下一轮又提一模一样的。整理 agent 看不到「拒绝」这件事，
        // 于是每轮都重做同样的工作（`proposeOnly` 下还会反复烧模型调用）。
        if (request.refusedChanges.isNotEmpty()) {
            appendLine()
            appendLine(
                "**Already refused by the user** (do NOT propose these again unless the new turns " +
                    "give genuinely new evidence; just skip them)"
            )
            request.refusedChanges.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("**New turns** (not reviewed before)")
        appendLine(request.transcript)
    }
}
