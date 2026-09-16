package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 一条「导入来源」记录：某个导出文件被导入过的时间与原会话标题。
 *
 * 存于 `Settings.importedSources`（键 = `ExportData.sourceId`，即**导出时的会话 id**）。
 * 用途是**重复导入提示**——同一文件再次导入时告知用户"已于 x 导入过"。
 *
 * 为什么需要它：导入**总是换新 id**（见 `docs/design-conversation-import-export.md` 决策 1），
 * 因此原有的 `existsConversationById` 判据在换 id 后永不命中，"同一文件导入两次"会被
 * 当成两次全新导入、静默产生重复副本。`sourceId` 随文件固定不变，是稳定的幂等锚点。
 *
 * @param importedAt 导入时间（epoch millis）——提示里显示的是**导入**时间，不是导出时间。
 * @param title 导入时该会话的标题（用于提示"xxx 已于 x 导入过"；可能为空）。
 */
@Serializable
data class ImportSourceRecord(
    val importedAt: Long,
    val title: String? = null,
)
