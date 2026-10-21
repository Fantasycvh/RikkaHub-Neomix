package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条**记忆整理建议**（做梦在「只提案」模式下产出的改动），落库版。
 *
 * ## 为什么必须落库（这不是可选优化）
 *
 * 落库前建议只活在 `MemoryConsolidationStatus`（进程内的 `MutableStateFlow`）里，而
 * `memoryModifyApproval` **默认 true** ⇒ `proposeOnly` 是**默认行为**
 * （`MemoryConsolidator.kt` 的 `proposeOnly = participants.any { … }`），
 * 同时全仓**没有任何应用路径**。两者相乘的后果是：**默认配置下自动整理持续烧模型调用，
 * 产出永远无法采纳、且进程一重启就丢**。代码自己的 kdoc 早就写着
 * 「持久化 + 审批入口是 V3-b3 的事 —— **在那之前不要接自动触发**」，而自动触发已经接了
 * （`MemoryConsolidationRuntime` 听 `ChatGenerationEnded`）。本表是那条注记的兑现。
 *
 * ## 字段与「成组」语义
 *
 * [batchId] 把**同一次整理**产出的建议绑在一起：一次「合并重复记忆」在工具层是
 * `edit` + `delete` 两个调用，**拆开应用会丢内容**（先 edit 后被别的东西删掉，或反之）。
 * batch 记录同次运行的来源，不强制整批采纳。显式 [dependsOn] 指向同批 create/edit 操作；
 * delete 只消费这些操作的成功输出，并复核输出内容版本，无关建议被拒绝不阻止删除。
 * [expectedRevision] 必须来自模型实际看过的内容，不能在保存时取最新版本伪造证据。
 *
 * [decision] 是审计字段：建议**被应用/被拒绝/已过期**都留行，这样「梦里到底改了什么」
 * 事后可查——与「删了没有账」是本轮要修掉的两件事之一。
 *
 * **不做** [ForeignKey]：建议属于**记忆库**（`memoryId`，语义 = assistantId 或 `__global__`），
 * 而助手住在 DataStore 不在 Room 里，没有可挂的父表；记忆本身也会被应用动作删掉，
 * 挂 CASCADE 会让「应用后审计行跟着消失」，正好毁掉审计的意义。
 */
@Entity(
    tableName = "memory_proposal",
    indices = [Index("memory_id"), Index("batch_id")],
)
data class MemoryProposalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 属于哪个记忆库（`MemoryConsolidationWorker` 契约里的不透明 `memoryId`） */
    @ColumnInfo("memory_id")
    val memoryId: String,
    /** 同一次整理的一批；依赖边而非整批成员关系决定操作前置条件。 */
    @ColumnInfo("batch_id")
    val batchId: String,
    /** `create` / `edit` / `delete` */
    @ColumnInfo("action")
    val action: String,
    /** edit / delete 的目标（`create` 为 null）；应用时还须验证所属记忆库。 */
    @ColumnInfo("target_id")
    val targetId: String?,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("importance")
    val importance: Float?,
    @ColumnInfo("description")
    val description: String?,
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** 见 [DECISION_*] */
    @ColumnInfo("decision")
    val decision: String = DECISION_PENDING,
    @ColumnInfo("decided_at")
    val decidedAt: Long = 0L,
    @ColumnInfo(name = "expected_revision", defaultValue = "NULL")
    val expectedRevision: Long? = null,
    @ColumnInfo(name = "operation_id", defaultValue = "''")
    val operationId: String = "",
    /** JSON array of same-batch create/edit operation IDs. */
    @ColumnInfo(name = "depends_on", defaultValue = "'[]'")
    val dependsOn: String = "[]",
    @ColumnInfo(name = "applied_target_id", defaultValue = "NULL")
    val appliedTargetId: String? = null,
    @ColumnInfo(name = "applied_revision", defaultValue = "NULL")
    val appliedRevision: Long? = null,
    /** SQL/Kotlin default 0 deliberately leaves legacy rows untrusted. */
    @ColumnInfo(name = "contract_version", defaultValue = "0")
    val contractVersion: Int = 0,
    /** Blank means unspecified (legacy edits preserve their current classification). */
    @ColumnInfo(name = "kind", defaultValue = "''")
    val kind: String = "",
    /** 0 = unspecified, -1 = explicit clear, positive = epoch millis. */
    @ColumnInfo(name = "due_at", defaultValue = "0")
    val dueAt: Long = 0L,
) {
    companion object {
        const val DECISION_PENDING = "pending"
        const val DECISION_APPLIED = "applied"
        const val DECISION_REJECTED = "rejected"

        /**
         * 目标缺失、版本变化、依赖输出变化或旧契约无可靠证据 ⇒ 不应用。
         *
         * 单列一档而不是复用 `rejected`：用户没有拒绝它，是**前提不成立**了。
         * 界面上这两种的说明完全不同（前者是「你拒绝了」，后者是「这条已经不能安全应用了」），
         * 混在一起会让用户以为自己点过拒绝。
         */
        const val DECISION_STALE = "stale"
    }
}
