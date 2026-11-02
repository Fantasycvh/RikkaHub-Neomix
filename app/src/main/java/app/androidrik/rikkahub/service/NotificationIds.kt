package app.androidrik.rikkahub.service

/**
 * **通知 / PendingIntent 的稳定命名空间**（设计 §4.4）。
 *
 * **为什么要有这个 object**：旧实现把子代理审批通知写死成 `4242`，于是
 * ①并行 8 个 run 的通知互相覆盖——用户只看得见最后一个 run 的待批工具，
 * 其余 run 停在 `awaiting_approval` 上直到 5 分钟超时；
 * ②任一 run 结束/被取消时 `cancel(4242)` 会把**别人**的待批通知一起撤掉。
 * 只撤自己的做法不是"少调一次"，而是"id 必须按 run 区分"。
 *
 * **规则**：`id = stableHash(namespace + ":" + requestId)`，其中 hash 用 FNV-1a
 * （不用 `hashCode()`：本仓 `MediaCreationService` 用 `record.id.hashCode()` 的做法对
 * Uuid 之类平台类型的哈希实现没有保证，换版本/换类型就可能变；FNV-1a 是纯算术，
 * 定死在这里，进程重启、升级 app 后同一个 run 仍是同一个 id）。由此得到三条性质：
 * - **同一个 run 重复投递** → 同一个 id，通知原地更新而不是堆叠；
 * - **不同 run** → 不同 id（32 位碰撞概率可忽略，且碰撞的后果仅是"两条通知互相覆盖"，
 *   不会串审批决定——审批决定本身按 `toolCallId` 路由，见 `SubagentApprovalQueue`）；
 * - **同一 run 的不同动作**（批准 / 拒绝 / 打开详情）→ 不同 requestCode，否则
 *   `FLAG_UPDATE_CURRENT` 会让后注册的 PendingIntent 覆盖前一个（PendingIntent 经典坑）。
 *
 * 进程重启后同一个 run 仍是同一个 id，因此"取消通知"不需要任何内存注册表。
 */
object NotificationIds {

    /** 子代理审批通知的命名空间（一个 run 一条聚合通知）。 */
    private const val NAMESPACE_SUBAGENT_APPROVAL = "subagent-approval"

    /** 子代理审批通知动作的命名空间（同一 run 的多个 PendingIntent 靠它区分）。 */
    private const val NAMESPACE_SUBAGENT_APPROVAL_ACTION = "subagent-approval-action"

    /** FNV-1a 32 位参数（与平台无关的稳定常量；offset basis 的补码即 0x811C9DC5）。 */
    private const val FNV_OFFSET_BASIS = -2128831035
    private const val FNV_PRIME = 16777619

    /**
     * 已被占用的通知 id（`stableId` 会跳过它们）。
     *
     * 只列"固定值"的占用者；`ChatNotificationManager` 的 live-update id 由 conversationId
     * 派生、取值域太大，无法枚举，这里不为它让位。
     */
    private val RESERVED_NOTIFICATION_IDS = setOf(
        1, // ChatNotificationManager 生成完成通知（固定 1）
        40000, // ScheduledTaskRunService 前台服务通知
        40010, // ScheduledTaskSweeper 前台服务通知（P0.5 与 40000 错开）
    )

    /**
     * 子代理审批通知上的动作。
     *
     * 批准 / 拒绝走广播（`SubagentApprovalReceiver`），打开详情走 Activity，三者
     * requestCode 必须互异——见 [subagentApprovalRequestCode]。
     */
    enum class ApprovalAction(val wireName: String) {
        APPROVE("approve"),
        DENY("deny"),
        OPEN_DETAIL("open-detail"),
    }

    /** 某个 run 的审批通知 id：同一 run 稳定，不同 run 不同。 */
    fun subagentApproval(runId: String): Int =
        stableId(NAMESPACE_SUBAGENT_APPROVAL, runId)

    /**
     * 某个 run 某个动作的 PendingIntent requestCode。
     *
     * requestCode 只用来让同 run 的不同 PendingIntent 互不覆盖，不参与通知 id；
     * 但仍走同一套 FNV-1a，避免依赖 `hashCode()`。
     */
    fun subagentApprovalRequestCode(runId: String, action: ApprovalAction): Int =
        stableId(NAMESPACE_SUBAGENT_APPROVAL_ACTION, "$runId:${action.wireName}")

    /**
     * 统一规则：`stableHash(namespace + ":" + requestId)`，结果恒为正整数且避开
     * [RESERVED_NOTIFICATION_IDS]。
     *
     * 公开是为了让后续同名空间（定时 run / 后台 shell task 的通知）复用同一套规则，
     * 而不是各自再发明一个 `hashCode() + 偏移`。
     */
    fun stableId(namespace: String, requestId: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (ch in "$namespace:$requestId") {
            // Int 溢出在 Kotlin/JVM 上按补码回绕，正好等价于 FNV-1a 的 mod 2^32
            hash = (hash xor ch.code) * FNV_PRIME
        }
        // Android 通知 id 是 32 位有符号 int：去掉符号位保证为正；0 不是合法通知 id
        var id = hash and Int.MAX_VALUE
        if (id == 0) id = 1
        // 撞上别人的固定 id 就顺延一格（只可能发生在极少数输入上，保持确定性即可）
        while (id in RESERVED_NOTIFICATION_IDS) id += 1
        return id
    }
}
