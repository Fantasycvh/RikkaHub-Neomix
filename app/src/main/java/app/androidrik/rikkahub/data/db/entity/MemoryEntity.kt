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
)
