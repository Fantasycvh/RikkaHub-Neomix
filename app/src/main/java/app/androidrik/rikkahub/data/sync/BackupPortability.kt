package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 备份可移植化清洗（设备态剥离）：让备份包描述「用户数据」而非「这台设备的状态」。
 *
 * **设备态 vs 用户数据的界线**：随备份迁移会误导目标设备的字段即为设备态——
 * - `launchCount`：本机启动计数（更新提醒等按它判断），恢复别台的数字毫无意义；
 * - `importedSources`：导入来源记录，键是**本机库**的会话 id（重复导入提示用），
 *   与备份里的库内容对不上号，恢复只会压掉本机的重复导入检测；
 * - `backupReminderConfig.lastBackupTime`：本机上次备份时间戳（提醒按它判断）。
 *   注意 enabled/intervalDays 是用户偏好**应随备份走**，故只手术掉嵌套的时间戳字段，
 *   不整键剔除。
 *
 * **为何在 JSON 层做**：与 [BackupContentFilter] 同款理由——typed 级 copy 逐字段维护
 * 必然漏（`Settings` 上百字段），JSON 键级操作新增字段零成本。纯函数，JVM 可直测。
 */
internal object BackupPortability {
    /** 整键设备态：打包时剔除，恢复时一律保留本机值。 */
    private val DEVICE_STATE_KEYS = setOf("launchCount", "importedSources")

    private const val BACKUP_REMINDER_KEY = "backupReminderConfig"
    private const val BACKUP_REMINDER_TIME_FIELD = "lastBackupTime"

    /**
     * 打包侧：剔除设备态字段（纯函数）。
     * 调用点：`BackupArchiveEngine.prepareBackupFileLocked` 生成 settings.json 之前，
     * 在 [BackupContentFilter.filterSettingsJson]（scope 剔除）之后叠加。
     */
    internal fun portable(settings: JsonObject): JsonObject {
        var result = JsonObject(settings.filterKeys { it !in DEVICE_STATE_KEYS })
        val reminder = result[BACKUP_REMINDER_KEY] as? JsonObject
        if (reminder != null && BACKUP_REMINDER_TIME_FIELD in reminder) {
            result = JsonObject(
                LinkedHashMap<String, JsonElement>(result).apply {
                    put(
                        BACKUP_REMINDER_KEY,
                        JsonObject(reminder.filterKeys { it != BACKUP_REMINDER_TIME_FIELD }),
                    )
                },
            )
        }
        return result
    }

    /**
     * 恢复侧：设备态字段一律保留 `current`（本机）值（纯函数）。
     *
     * 必须在 `settingsStore.update { }` 的 transform 内用 transform 给的 `current` 计算
     * （单向数据流，同 [BackupContentFilter.preserveUnselected] 的约束）。
     * 即使 incoming 携带了设备态键（本阶段之前的旧包没清洗过），也以本机为准覆盖。
     */
    internal fun preserveDeviceState(incoming: JsonObject, current: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>(incoming)
        DEVICE_STATE_KEYS.forEach { key ->
            val localValue = current[key]
            if (localValue != null) merged[key] = localValue else merged.remove(key)
        }
        val incomingReminder = merged[BACKUP_REMINDER_KEY] as? JsonObject
        if (incomingReminder != null) {
            val localTime = (current[BACKUP_REMINDER_KEY] as? JsonObject)
                ?.get(BACKUP_REMINDER_TIME_FIELD)
            val cleaned = LinkedHashMap<String, JsonElement>(
                incomingReminder.filterKeys { it != BACKUP_REMINDER_TIME_FIELD },
            )
            localTime?.let { cleaned[BACKUP_REMINDER_TIME_FIELD] = it }
            merged[BACKUP_REMINDER_KEY] = JsonObject(cleaned)
        }
        return JsonObject(merged)
    }
}
