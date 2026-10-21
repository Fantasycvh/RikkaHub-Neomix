package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 翻译页的一条翻译记录：**翻译成功后**写入，只保留最近 [app.androidrik.rikkahub.data.repository.TranslationHistoryRepository.MAX_HISTORY_COUNT] 条。
 *
 * 为什么独立成表而不是塞进 DataStore：记录会累积（上限 100），需要按时间倒序分页、单条删除、
 * 一键清空，用关系表 + 索引最直接；也因此目标语言用 BCP 47 标签存字符串，不依赖当前 `Locale`。
 *
 * 表名/列名刻意与原版 3.x 的 `translation_history` 保持同构：原版备份转换器按「列名交集」
 * 复制同名表，这样旧备份里的翻译历史也能一并导入（见 LegacyBackupConverter）。
 */
@Entity(
    tableName = "translation_history",
    indices = [Index(value = ["create_at"])]
)
data class TranslationHistoryEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_text")
    val sourceText: String,
    @ColumnInfo("translated_text")
    val translatedText: String,
    /** 目标语言的 BCP 47 标签（如 `zh-CN`、`en`） */
    @ColumnInfo("target_language")
    val targetLanguage: String,
    /** 翻译时使用的模型 id；只作排查线索，模型被删后不影响历史展示 */
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("create_at")
    val createAt: Long,
)
