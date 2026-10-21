package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.db.dao.TranslationHistoryDAO
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * 翻译历史的读写入口。
 *
 * 只保留最近 [MAX_HISTORY_COUNT] 条：翻译页是「顺手用」的功能，历史无限增长既占库也没人翻；
 * 保留上限在**写入后**裁剪（SQLite 侧），这样调用方不用关心清理时机。
 *
 * [now] 可注入是为了单测能确定 `createAt`，生产环境走系统时钟。
 */
class TranslationHistoryRepository(
    private val dao: TranslationHistoryDAO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 最多保留的历史条数（超出后删最旧的） */
        const val MAX_HISTORY_COUNT = 100
    }

    /** 最近 [MAX_HISTORY_COUNT] 条，按时间倒序 */
    fun history(): Flow<List<TranslationHistoryEntity>> = dao.flowLatest(MAX_HISTORY_COUNT)

    /**
     * 记一条**成功且非空**的翻译（调用方负责过滤取消/失败/空结果）。
     * 写完立即裁剪到上限，避免每条都全表比对时把内存抬高。
     */
    suspend fun add(
        sourceText: String,
        translatedText: String,
        targetLanguage: String,
        modelId: String,
    ) {
        dao.insert(
            TranslationHistoryEntity(
                id = Uuid.random().toString(),
                sourceText = sourceText,
                translatedText = translatedText,
                targetLanguage = targetLanguage,
                modelId = modelId,
                createAt = now(),
            )
        )
        dao.trimTo(MAX_HISTORY_COUNT)
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun clear() = dao.deleteAll()
}
