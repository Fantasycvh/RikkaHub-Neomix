package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.capabilityLabels

/**
 * 对话内子代理派发的**工具名**。
 *
 * 三处共用，故为常量：①装配（[ChatToolFactory]）②展示（`ToolUIRegistry` 的渲染器）
 * ③**防嵌套**（运行器恒定把它从子代理的工具集里剔掉）。
 *
 * 命名与 `use_skill` / `workspace_shell` 同款：动词 + 宾语，让模型一眼看出是"把活派出去"。
 */
const val SUBAGENT_DISPATCH_TOOL_NAME = "dispatch_subagent"

/**
 * 预设的**能力开关覆盖的工具来源**（[ChatToolFactory] 的段名）。
 *
 * 只有这两个来源受「联网搜索 / 工作区」两个开关控制，因此 [resolveSubagentEnvelope] 在
 * `allowedTools == null`（"按能力开关"）时只放这两个来源的工具——
 * **MCP / 技能安装这类无条件装配的工具不在其中**（见该函数的取舍说明）。
 */
private val CAPABILITY_SOURCE_SEGMENTS = setOf("search", "workspace")

/**
 * 派发结果（工具与运行器之间的 DTO）。
 *
 * 刻意不复用 [app.androidrik.rikkahub.data.db.entity.AgentRunEntity]：工具只需要
 * 「跑完没有 / 结果文本 / 错在哪 / 去哪看」，把账本实体（含 transcript、信封、时间戳）
 * 递给工具层既无必要，也会诱导工具层去改账本。
 */
data class SubagentRunResult(
    /** 账本行 id（父模型可见，用于让用户去运行详情页查看过程）。 */
    val runId: String,
    /** `AgentRunEntity.STATUS_*` 之一。 */
    val status: String,
    /** 成功时的**完整**最终文本（列表行里的摘要会被截断，工具结果要全文）。 */
    val summary: String?,
    /** 失败原因（机器前缀 + 详情），人读与模型读同一份。 */
    val error: String?,
)

/**
 * 「跑一个子代理预设并等它结束」的窄接口。
 *
 * **为什么要这层接口**：`SubagentRunner` 依赖 [ChatToolFactory]（它要用装配点装配工具），
 * 而工具又要由 [ChatToolFactory] 装配——直接互相注入就是构造环。工具只依赖这个两方法的
 * 接口，工厂侧拿到的是**延迟解析的 provider**（`() -> SubagentDispatcher?`），环就断在
 * 那一个 lambda 上。
 */
interface SubagentDispatcher {
    /**
     * 阻塞式派发：跑完（或被护栏/超时中止）才返回。
     *
     * @return **null = 当前已有活跃 run**（Phase 1 串行；调用方应把"忙"如实转达给模型）
     */
    suspend fun dispatchAndAwait(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
        /** 派发它的父会话 id（账本列，供详情页「回到对话」）；非对话入口传 null。 */
        parentConversationId: String? = null,
    ): SubagentRunResult?
}

/**
 * 信封解析（**纯函数**，三处共用：运行器 / 派发工具 / 管理页预览）。
 *
 * @param segments `ChatToolFactory` 的**分段**装配结果（段名 → 工具），不是扁平列表——
 *   因为 `null` 语义需要知道工具来自哪个开关控制的来源。
 *
 * 语义：
 * - `allowedTools == null`：**能力开关决定**——只放 [CAPABILITY_SOURCE_SEGMENTS]
 *   （`search` / `workspace`）里的工具。这一条是本函数最关键的取舍：
 *   `createTools` 里有**无条件装配**的来源（MCP 服务器工具、技能安装工具），
 *   若 `null` 解成"装配出的全部"，一个只勾了"联网搜索"的研究型预设会**静默获得**
 *   第三方 MCP 工具（可能是写日历、发消息这类有副作用的工具）。⇒ `null` 必须只覆盖
 *   用户显式打开的那两个开关，其它来源一律要靠**显式点名**才进得来（见下）。
 * - `allowedTools` 非空：与装配结果取交集（点名可以放进 MCP / 技能安装等来源，
 *   **但要用户亲手写出工具名**——这是有意的摩擦）。
 * - `allowedTools` 空集：**不给任何工具**（纯推理型子代理）。
 *
 * 恒定剔除 [SUBAGENT_DISPATCH_TOOL_NAME]：**子代理不得再派发子代理**（禁嵌套）。
 * 这是第一层（也是主要一层）防护——它不依赖运行器的串行闸，因此单层保证成立；
 * 串行闸只是第二层。
 */
internal fun resolveSubagentEnvelope(
    segments: List<ToolSegment>,
    allowedTools: Set<String>?,
): Set<String> {
    val byCapability = segments
        .filter { it.source in CAPABILITY_SOURCE_SEGMENTS }
        .flatMapTo(mutableSetOf()) { segment -> segment.tools.map { it.name } }

    val assembled = segments.flatMapTo(mutableSetOf()) { segment -> segment.tools.map { it.name } }

    val allowed = if (allowedTools == null) {
        byCapability
    } else {
        assembled.intersect(allowedTools)
    }
    return allowed - SUBAGENT_DISPATCH_TOOL_NAME
}

/**
 * 该 profile 在**一次 run 里**实际可用的工具名目录（手工派发页的信封候选）。
 *
 * 与 [resolveSubagentEnvelope] 同口径地剔掉子代理工具本身：候选清单里若出现
 * `dispatch_subagent`，勾选它等于承诺一件**不会发生**的事（run 恒不装配它）。
 */
internal fun runToolCatalog(segments: List<ToolSegment>): List<String> =
    segments.flatMap { it.tools }
        .map { it.name }
        .filter { it != SUBAGENT_DISPATCH_TOOL_NAME }
        .distinct()
        .sorted()

/**
 * 装配对话内的子代理派发工具。
 *
 * @param presets **已按当前对话模型解析过 `modelId` 的**可用预设（见 `ChatToolFactory`：
 *   预设的 `modelId` 留空 = 跟随当前对话，解析发生在装配点，因为那里才知道"当前"是谁）
 */
fun createSubagentTools(
    presets: List<SubagentPreset>,
    dispatcher: SubagentDispatcher,
    /**
     * 父会话 id（由装配点透传：只有 `ChatToolFactory` 知道"这次生成属于哪个会话"）。
     * 记进账本后，运行详情页才能给「回到对话」这个反向入口。
     */
    parentConversationId: String? = null,
): List<Tool> {
    if (presets.isEmpty()) return emptyList()

    val namesInOrder = presets.joinToString(", ") { it.name }

    return listOf(
        Tool(
            name = SUBAGENT_DISPATCH_TOOL_NAME,
            description = """
                Hand a self-contained task to a named subagent and get its final answer back.

                The subagent runs in its own separate context with its own system prompt and a restricted tool set. It cannot see this conversation and you only receive its final answer — not its searches, its reasoning or its dead ends. State everything it needs inside `goal`.

                Use this when a task is long, multi-step, or full of trial and error: done here, that noise would fill your context and stay there. Do not use it for a single lookup you could do yourself.

                The call blocks until the subagent finishes, fails, or hits its timeout. If the result status is not "completed", decide yourself whether to retry with a better `goal` or to continue without it.
            """.trimIndent(),
            systemPrompt = { _, _ -> subagentCatalogBlock(presets) },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "name",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "The subagent to dispatch. Exactly one of: $namesInOrder",
                                )
                            }
                        )
                        put(
                            "goal",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "The task, written so it stands alone. The subagent cannot see this conversation: include the material it needs, the exact question, and what the answer should look like.",
                                )
                            }
                        )
                    },
                    required = listOf("name", "goal"),
                )
            },
            // 审批由预设作者按组合决定（默认关：信封已把工具面收窄到他自己配的范围）
            // 阻塞式等待整轮子代理（预设超时可达 7200s），且 SubagentRunner 自带看门狗与
            // 超时收尾 ⇒ 外层不介入（见 Tool.executionTimeoutMillis 注释）
            executionTimeoutMillis = null,
            needsApproval = { args ->
                val requested = args.jsonObject["name"]?.jsonPrimitive?.content
                presets.firstOrNull { it.name.equals(requested, ignoreCase = true) }?.requireApproval == true
            },
            execute = { args ->
                val requested = args.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (requested.isEmpty()) {
                    error("name is required. Available subagents: $namesInOrder")
                }
                // 大小写不敏感：模型偶尔会改大小写，没必要为此浪费一轮
                val preset = presets.firstOrNull { it.name.equals(requested, ignoreCase = true) }
                    ?: error("Unknown subagent '$requested'. Available subagents: $namesInOrder")
                val goal = args.jsonObject["goal"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (goal.isEmpty()) {
                    error("goal is required: the subagent cannot see this conversation, so the task must be stated here.")
                }

                val result = dispatcher.dispatchAndAwait(
                    preset = preset,
                    goal = goal,
                    timeoutMillis = preset.timeoutSeconds * 1000L,
                    parentConversationId = parentConversationId,
                )
                listOf(UIMessagePart.Text(formatSubagentResult(result)))
            },
        )
    )
}

/**
 * 子代理清单（进 system 正文的工具引导块，形态对齐 `use_skill` 的 `<available_skills>`）。
 *
 * 清单是父模型**唯一**能知道"有哪些子代理、各自能干什么"的地方——
 * 所以派发工具的 schema 里只有名字与任务两个参数，不含任何预设配置。
 */
internal fun subagentCatalogBlock(presets: List<SubagentPreset>): String = buildString {
    appendLine("**Subagents**")
    appendLine(
        "A subagent is a separate model call with its own context: it cannot see this conversation, " +
            "and you only get its final answer back. Use `$SUBAGENT_DISPATCH_TOOL_NAME` to hand it one " +
            "self-contained task when the work would otherwise flood this context with searches, files and dead ends."
    )
    appendLine("<available_subagents>")
    presets.forEach { preset ->
        appendLine("  <subagent>")
        appendLine("    <name>${preset.name}</name>")
        if (preset.description.isNotBlank()) {
            appendLine("    <description>${preset.description}</description>")
        }
        val capabilities = preset.capabilityLabels()
        appendLine(
            "    <capabilities>" +
                (if (capabilities.isEmpty()) "no tools (reasoning only)" else capabilities.joinToString("; ")) +
                "</capabilities>"
        )
        appendLine("  </subagent>")
    }
    append("</available_subagents>")
    appendLine()
}

/**
 * 工具结果文本（**纯函数**）：结构化结果 + 指针（`run_id`）。
 *
 * 契约（§1.3）：回传**只带结论与指针，不带子代理的轨迹**。上层接口把 JSON 直接展示为
 * 工具卡内容，模型也直接读它——所以失败原因沿用 run 的机器前缀，与运行详情页同一份口径。
 */
internal fun formatSubagentResult(result: SubagentRunResult?): String {
    if (result == null) {
        return buildJsonObject {
            put("status", JsonPrimitive(SUBAGENT_STATUS_UNAVAILABLE))
            put(
                "error",
                JsonPrimitive("The run limit is reached (at most 2 runs at a time, and they do not queue)."),
            )
            put(
                "note",
                JsonPrimitive("Do not call this tool again right away. Answer without a subagent, or tell the user to try again once the running one finishes."),
            )
        }.toString()
    }

    return buildJsonObject {
        put("status", JsonPrimitive(result.status))
        put("run_id", JsonPrimitive(result.runId))
        val summary = result.summary
        if (summary != null) {
            put("summary", JsonPrimitive(summary))
        }
        val error = result.error
        if (error != null) {
            put("error", JsonPrimitive(error))
            put(
                "note",
                JsonPrimitive("The subagent stopped before producing a final answer. Its partial transcript is stored as run ${result.runId}."),
            )
        }
    }.toString()
}

/** 「忙」不是 run 的状态，是**派发没发生**；单独一个取值以免与账本状态机混淆。 */
internal const val SUBAGENT_STATUS_UNAVAILABLE = "unavailable"
