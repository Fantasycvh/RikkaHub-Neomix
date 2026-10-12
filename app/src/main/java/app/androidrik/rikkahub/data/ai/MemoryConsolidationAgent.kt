package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.datastore.Settings

/**
 * 一次整理交给 agent 的全部输入（**纯数据**：不传存储对象、不传助手模型对象）。
 *
 * 存进去的都是算好的东西：库现状（两层披露 + 预算）、校准表、切好的轮次、步数上限、
 * 以及「这轮要不要改库」。agent 只负责一次模型调用，不负责任何决策——决策全在
 * `MemoryConsolidator` 与 `docs/design/design-memory-consolidation.md` 记的口径里。
 */
data class MemoryConsolidationRequest(
    val settings: Settings,
    val model: Model,
    val memoryId: String,
    /** 记忆库现状（复用聊天注入的两层披露，换个更小的预算） */
    val libraryPrompt: String,
    /** 校准表：`id ｜ 重要度 ｜ 被读次数 ｜ 主题`（整理 agent 是唯一该看它的角色） */
    val calibrationTable: String,
    /** 新轮次正文（已按预算切好） */
    val transcript: String,
    val maxSteps: Int,
    /** 审批开着 ⇒ 只提案不改库（工具回执 `applied:false`） */
    val proposeOnly: Boolean,
)

data class MemoryConsolidationOutcome(
    /** 真正落库的改动条数 */
    val appliedChanges: Int,
    /** 只提案模式攒下的建议（正常模式恒为空） */
    val proposals: List<MemoryConsolidationProposal>,
)

/**
 * 做梦的「一次模型调用」抽象。
 *
 * 为什么单独抽一层：整理的全部**策略**（切多少轮、水位推进到哪、审批怎么降级、失败怎么退避）
 * 都要能用 JVM 单测钉死，而真正的模型调用依赖 Android（`GenerationHandler` 构造要 Context、
 * 工具要真实仓库）。抽掉它之后，生产实现只剩「建消息 → 调 `generateText` → 数改动」三件事，
 * 而策略层可以拿一个假 agent 把每条判据测完。
 */
fun interface MemoryConsolidationAgent {
    /** 失败抛异常（调度器会记成一次失败并退回「等下一轮对话/手动」队列）；成功返回这一轮改了什么 */
    suspend fun run(request: MemoryConsolidationRequest): MemoryConsolidationOutcome
}
