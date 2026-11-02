package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 无头 agent run 的**账本行**（设计见 `docs/design/design-subagent-context.md` §9.1/9.3）。
 *
 * **为什么是独立表而不是复用 `ConversationEntity`**：run 的轨迹是**线性**的（无重生成分支、
 * 无用户编辑），`message_node` 的分支机制全是死重；run 也不需要 Conversation 的元数据丛林
 * （pin / 文件夹 / 注入绑定）。分开存储让「侧栏会话列表」与「运行列表」两类 UI 面
 * **在数据层就不可能混淆**——复用则需要给 15 处查询加过滤、漏一处即静默泄漏。
 *
 * **账本语义**：`entry_point` 区分发起方（Phase 1 只有 subagent；未来定时任务/外部自动化
 * 共用此表），`status` 状态机把「看起来跑完了」和「真的完成了」区分开——
 * **空响应不得记成 COMPLETED**（`SubagentRunner` 的完成判定负责，见 §9.2.1 ③）。
 *
 * 轨迹存 `transcript` JSON 列（线性、追加式，单 run 几百 KB 级）；需要结构化查询再拆表
 * （拆是增量，不迁移）。
 */
@Entity(tableName = "agent_run")
data class AgentRunEntity(
    @PrimaryKey @ColumnInfo("id") val id: String,
    /** 发起方标识（账本列）：运行页手动派发 = [ENTRY_POINT_SUBAGENT]，对话内工具派发 = [ENTRY_POINT_CHAT]。 */
    @ColumnInfo("entry_point") val entryPoint: String,
    /** 状态机：[STATUS_PENDING] → [STATUS_RUNNING] → 终态之一。 */
    @ColumnInfo("status") val status: String,
    /** profile（复用现有 Assistant 当模板）id。 */
    @ColumnInfo("profile_assistant_id") val profileAssistantId: String,
    /** profile 名快照（profile 被删后详情页仍有可读标识）。 */
    @ColumnInfo("profile_name") val profileName: String,
    /** 模型 id 快照。 */
    @ColumnInfo("model_id") val modelId: String?,
    /** 模型名快照（同 [app.androidrik.ai.ui.UIMessage.modelNameSnapshot] 的理由）。 */
    @ColumnInfo("model_name") val modelName: String?,
    /** 任务目标（派发时的用户输入，原样成为首条 user 消息）。 */
    @ColumnInfo("goal") val goal: String,
    /**
     * 能力信封：**允许装配**的工具名 JSON 数组（派发时快照）。
     * 未入信封的工具**不装配**（而非拒批）——`needsApproval` 的语义是「等人批」，
     * 无人头时它拦不住任何东西，故过滤在装配后做。
     */
    @ColumnInfo("tool_envelope") val toolEnvelope: String,
    /** 轨迹：`List<UIMessage>` 的 JSON（线性追加；成功路径末条 assistant 即结果）。 */
    @ColumnInfo("transcript") val transcript: String,
    /** 成功时的结果摘要（末条 assistant 文本）；**空 = 异常终态**，不得与 COMPLETED 共存。 */
    @ColumnInfo("result_summary") val resultSummary: String?,
    /** 失败/中止原因（人读；status 非 COMPLETED 时的解释）。 */
    @ColumnInfo("error") val error: String?,
    /**
     * 派发它的**父会话 id**（仅对话内工具派发有；运行页手动派发为 null）。
     *
     * **刻意是纯列、无外键**（设计 §9.1 的边界：父指针不强依赖会话存在）——
     * 会话被删后这里会残留一个悬空 id，展示层先用 `existsConversationById` 判一下再给入口，
     * 而不是靠约束去阻止删除、也不是靠级联把 run 一并删掉（run 的账本价值独立于会话）。
     */
    @ColumnInfo("parent_conversation_id") val parentConversationId: String? = null,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("started_at") val startedAt: Long?,
    @ColumnInfo("finished_at") val finishedAt: Long?,
    /** 逻辑归属指针；父 run/会话删除不级联删除账本。P1 深度恒为 0。 */
    @ColumnInfo("parent_run_id") val parentRunId: String? = null,
    @ColumnInfo(name = "depth", defaultValue = "0") val depth: Int = 0,
    @ColumnInfo("scope_token") val scopeToken: String? = null,
    @ColumnInfo(name = "delivery_mode", defaultValue = "'await'")
    val deliveryMode: String = DELIVERY_MODE_AWAIT,
    @ColumnInfo(name = "delivery_state", defaultValue = "'none'")
    val deliveryState: String = DELIVERY_STATE_NONE,
    @ColumnInfo("delivered_at") val deliveredAt: Long? = null,
    /** 人读取消原因，与 error 的机器前缀分开。 */
    @ColumnInfo("cancel_reason") val cancelReason: String? = null,
) {
    companion object {
        const val DELIVERY_MODE_AWAIT = "await"
        const val DELIVERY_MODE_NOTICE = "notice"
        const val DELIVERY_MODE_NOTIFY = "notify"
        const val DELIVERY_STATE_NONE = "none"
        const val DELIVERY_STATE_PENDING = "pending"
        const val DELIVERY_STATE_DELIVERED = "delivered"
        const val DELIVERY_STATE_ORPHANED = "orphaned"

        /** 运行页**手动派发**（选助手当 profile + 勾信封）。 */
        const val ENTRY_POINT_SUBAGENT = "subagent"

        /**
         * 对话内**工具派发**（模型调 `dispatch_subagent` 按预设调度，设计 §十）。
         *
         * 与 [ENTRY_POINT_SUBAGENT] 分开记：两者的信封来源不同（预设 vs 手工勾选），
         * 排查"子代理为什么碰不到某个工具"时要先看是哪条入口把它派出去的。
         */
        const val ENTRY_POINT_CHAT = "chat"

        /**
         * 定时任务 headless 派发（P4b，`design-agent-task-runtime.md` §7 P4）：
         * 不再把 prompt 注入目标会话，而是独立跑一个 run，结果按 `notice` 投递 + 完成通知。
         */
        const val ENTRY_POINT_SCHEDULED = "scheduled"

        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"

        /**
         * **停在等人批准上**（设计 §十一；仅 `ASK` 策略会出现）。
         *
         * 与 RUNNING 分开记的理由：这是 run 的**第三个可观测状态**——用户要能一眼看出
         * "它不是在慢慢跑，而是在等我"，否则只会觉得卡住。新值不需要迁移（列本身存字符串）。
         */
        const val STATUS_AWAITING_APPROVAL = "awaiting_approval"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        /**
         * **非自然中断**（P0.5 启动对账专用）：账本行停在 running/awaiting_approval，
         * 但产生它的进程已经不存在（进程被杀/系统回收），run 不可能再往前走。
         *
         * 与 [STATUS_FAILED] 分开：这是「没跑完、不知道结果」而不是「跑完但失败」，
         * UI 文案与统计口径都不同（设计 §4.3/§6.2）。**不由运行中的 runner 写入**，
         * 只由启动对账写入——否则会和「正在优雅收尾」的 run 抢状态。
         */
        const val STATUS_INTERRUPTED = "interrupted"

        /** 失败原因细分（`error` 的机器可读前缀，展示层映射文案）。 */
        const val ERROR_NO_MODEL = "no_model"
        const val ERROR_LOOP_DETECTED = "loop_detected"
        const val ERROR_BUDGET_EXHAUSTED = "budget_exhausted"

        /**
         * 整体截止时间到（`SubagentRunner` 的 `withTimeout` 兜底）。
         *
         * 单独成前缀而不是复用 `exception`：`TaskStateMappings` 要把它还原成
         * TIMED_OUT，展示层才能把「被 30 分钟截止时间收掉」和「provider 报错」分开。
         */
        const val ERROR_TIMEOUT = "timeout"

        /**
         * 进程重启对账写入的中断原因（P0.5）：产生该 run 的进程已不存在，
         * 无法判定是正常收尾还是被杀。与 [ERROR_EXCEPTION] 分开，便于统计把
         * 「系统回收/进程被杀」与「run 自身异常」区分开。
         */
        const val ERROR_PROCESS_RESTART = "process_restart"

        /**
         * 上下文预算耗尽（设计 §十二）：轨迹把窗口吃到剩下的不够产出一轮回答。
         *
         * 与 [ERROR_BUDGET_EXHAUSTED]（workspace 工具输出体量）分开记：两者防的不是同一件事，
         * 排查时看细分就能知道该调哪个预算。
         */
        const val ERROR_CONTEXT_BUDGET = "context_budget"
        const val ERROR_PERMISSION_DENIED = "permission_denied"
        const val ERROR_MODEL_GAVE_UP = "model_gave_up"
        const val ERROR_EXCEPTION = "exception"
    }
}
