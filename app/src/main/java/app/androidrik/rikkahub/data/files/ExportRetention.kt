package app.androidrik.rikkahub.data.files

/** 导出留存区的默认保留天数。见 `docs/design/design-export-retention.md` §4.3。 */
const val DEFAULT_EXPORT_RETENTION_DAYS: Int = 7

/** 「不自动清理」的取值（用户在设置里关掉 TTL）。 */
const val EXPORT_RETENTION_NEVER: Int = 0

/** 设置里允许的保留天数上限（十年，仅防手滑填出离谱值）。 */
const val EXPORT_RETENTION_DAYS_MAX: Int = 3650

/**
 * 计算导出留存区的清理截止时间（早于该时刻的文件应删除）。
 *
 * **纯函数**（不碰时钟与文件系统），便于单测覆盖边界。
 *
 * @param retentionDays 保留天数；`<= 0`（= [EXPORT_RETENTION_NEVER]）表示**不清理**。
 *   超出 [EXPORT_RETENTION_DAYS_MAX] 时按上限截断（防御性，避免整数溢出）。
 * @param nowMillis 当前时间（epoch millis，由调用方注入以便测试）。
 * @return 截止时间（epoch millis）；**null 表示不清理**。
 */
fun exportRetentionCutoff(retentionDays: Int, nowMillis: Long): Long? {
    if (retentionDays <= 0) return null
    val days = retentionDays.coerceAtMost(EXPORT_RETENTION_DAYS_MAX)
    return nowMillis - days.toLong() * 24L * 60L * 60L * 1000L
}

/**
 * 判断一个留存区文件是否已过期（应清理）。
 *
 * 提示：`createdAt <= cutoff` 视为过期——用"早于或等于"而不是严格早于，
 * 这样"恰好 N 天前"的文件会被清掉，符合用户对"保留 N 天"的直觉。
 */
fun isExportExpired(createdAtMillis: Long, cutoffMillis: Long): Boolean =
    createdAtMillis <= cutoffMillis

/** 留存区写坏时可能残留的临时文件前缀（见 `FilesManager.saveExportAtomically`）。 */
const val EXPORT_TEMP_PREFIX: String = ".tmp-"
