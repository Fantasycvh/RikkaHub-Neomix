package app.androidrik.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/** 工具执行的默认**外层**上限（见 [Tool.executionTimeoutMillis]）。 */
const val DEFAULT_TOOL_EXECUTION_TIMEOUT_MS = 60_000L

/**
 * 审批「**恒不拦**」的规范实例；[Tool.needsApproval] 的默认值即它。
 *
 * 存在意义是让「这个工具没配审批闭包」变得**机器可判**（按引用比较）——装配诊断
 * （P2a，设计稿 §16.3）要回答「这个工具要不要审批」，而审批闭包无法内省；
 * 约定「未配置 = 引用本实例」后，`tool.needsApproval !== TOOL_NO_APPROVAL` 即「有动态审批」。
 * 逻辑等价的字面量 `{ false }` 不参与该判定（宁可多报 dyn，不漏报）。
 */
val TOOL_NO_APPROVAL: (JsonElement) -> Boolean = { false }

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = TOOL_NO_APPROVAL,
    /**
     * 本次调用的**外层执行上限**（毫秒）；`null` = 该工具**自管超时**，外层不介入。
     *
     * 存在意义是「不许无限悬挂」：执行方（`GenerationHandler`）用它包一层 `withTimeout`，
     * 超时后把结果变成**工具错误输出**（模型可据此自我纠正），而不是让整轮一直停着。
     *
     * 默认 60s（[DEFAULT_TOOL_EXECUTION_TIMEOUT_MS]）。**自管超时的工具必须显式声明 null
     * 或更大的值**，否则会被外层误杀——已知两类：①`workspace_shell`（命令上限 600s，
     * 故声明 615s = 上限 + 缓冲）；②`dispatch_subagent`（阻塞式等待整轮子代理，预设超时
     * 上限 7200s，且 runner 自带看门狗 ⇒ 声明 null 交它自管）。
     */
    val executionTimeoutMillis: Long? = DEFAULT_TOOL_EXECUTION_TIMEOUT_MS,
    /** Successful runtime control tools may release the parent turn after the whole tool batch commits. */
    val endTurnOnSuccess: Boolean = false,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

/** A control tool can observe already-completed work and continue normally instead of raising an error. */
fun Tool.shouldEndTurn(result: List<UIMessagePart>): Boolean = endTurnOnSuccess &&
    result.none { it.metadata?.get("runtimeContinueTurn") == JsonPrimitive(true) }

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
