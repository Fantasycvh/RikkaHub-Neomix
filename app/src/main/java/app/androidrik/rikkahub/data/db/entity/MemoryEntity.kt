package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    /**
     * 对外的稳定 opaque id（8 位小写十六进制），模型与 UI 都用它，自增 [id] 仅内部使用。
     *
     * 为什么不把自增 id 对外：删除产生的空洞会暴露「编号不连续」（难看），且编号本身
     * 泄漏记忆条数（模型会把它当总数——上游 #1830 报告者实证「deepseek 把编号 28
     * 理解成有 28 条记忆」）。随机 token 两个问题一起根治，且**永不需要重排**。
     *
     * 迁移给存量行用 `lower(hex(randomblob(4)))` 回填；新建行由 [MemoryRepository]
     * 生成（带唯一性校验）。极端情况下为空串时，读取层按自增 id 兜底。
     */
    @ColumnInfo("public_id", defaultValue = "")
    val publicId: String = "",
    /**
     * 重要度 0.0–1.0，由模型在 `memory_tool` 写入时给、用户可在记忆页覆盖。
     *
     * 用途：注入打分 `importance × (0.55 + 0.45 × 时效衰减)` 的第一个因子——
     * **若没有它，打分退化为纯时效衰减 = 等价于「取最新」**，整套打分校准失去意义。
     *
     * 默认 [DEFAULT_IMPORTANCE]（普通偏好档）：模型不填时仍是一个有意义的中间值。
     */
    @ColumnInfo("importance", defaultValue = "0.6")
    val importance: Float = DEFAULT_IMPORTANCE,
    /**
     * 最近一次写入/修改时间（epoch millis），时效衰减的时间基准。
     *
     * **edit 时必须同步更新**（见 `MemoryRepository.updateContent`），否则改过的记忆
     * 仍按旧时间衰减——等于「改了没用」。
     *
     * 迁移给存量行回填**迁移时刻**（`CAST(strftime('%s','now') AS INTEGER) * 1000`）
     * 而非 0：真实历史时间已不可考，回填 0 会把老记忆当成「极旧」而永久沉底；
     * 回填当下则让它们在升级时刻统一起算，排序交给 importance 决定。
     */
    @ColumnInfo("updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
) {
    companion object {
        /** 缺省重要度（普通偏好档，见 [importance]）。 */
        const val DEFAULT_IMPORTANCE = 0.6f
    }
}
