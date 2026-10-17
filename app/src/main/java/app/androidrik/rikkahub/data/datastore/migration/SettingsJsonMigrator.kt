package app.androidrik.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.data.datastore.isRetiredSearchService
import app.androidrik.rikkahub.utils.JsonInstant

private const val TAG = "SettingsJsonMigrator"

/**
 * 当前包名**根前缀**（各模块命名空间统一挂在它下面：`.rikkahub` / `.ai` / `.common` …）。
 * **改包名时同步更新**。
 */
private const val CURRENT_ROOT = "app.androidrik"

/**
 * 本 App 历史上用过的包名**根前缀** —— 备份恢复时的兼容对象（覆盖该根下所有模块命名空间）：
 * - `me.rerere`：上游 RikkaHub 的根（`.rikkahub` = app、`.ai` = ai 模块…）；
 * - `app.fantasycvh`：本项目的**私有开发版**根（2026-10 改包名之前）。
 * 旧备份里多态 `type` 字段存的是完整类名，不带这些前缀迁移，反序列化会抛
 * "Serializer for subclass ... is not found in the polymorphic scope"。
 */
private val LEGACY_ROOTS = listOf(
    "me.rerere",
    "app.fantasycvh",
)

/**
 * 旧 **applicationId**（文件绝对路径用的是它，可能带 `.debug` 变体；模块命名空间不会出现在路径里）。
 */
private val LEGACY_APPLICATION_IDS = listOf(
    "me.rerere.rikkahub",
    "app.fantasycvh.rikkahub",
)

/**
 * 对备份文件中的 settings.json 应用与 DataStore migration 相同的迁移逻辑。
 *
 * DataStore migration 作用于分散的 key-value 存储，而备份文件中的 settings.json
 * 是整个 [app.androidrik.rikkahub.data.datastore.Settings] 对象的序列化结果。
 * 此工具类负责在反序列化前对旧格式的 JSON 执行等价的迁移操作。
 */
object SettingsJsonMigrator {

    /**
     * 对 settings JSON 字符串依次应用所有版本的迁移。
     * 若发生异常则返回原始 JSON，不中断恢复流程。
     */
    fun migrate(settingsJson: String): String =
        migrate(settingsJson = settingsJson, currentApplicationId = BuildConfig.APPLICATION_ID)

    /**
     * @param currentApplicationId 当前构建的 applicationId（release/debug 不同），用于把旧备份里的
     *   `file:///data/user/0/<旧包>/…` 绝对路径重写到当前包目录。
     */
    fun migrate(settingsJson: String, currentApplicationId: String): String {
        // V0a: 旧包名绝对路径 → 当前 applicationId。
        // 旧备份里的附件/工作区路径是 `file:///data/user/0/<旧包>/files/…`，跨包（含 release↔debug
        // 的 applicationId 差异）后 uid 不同不可读；这里统一归一为当前包（`/data/data` 也归一为
        // `/data/user/0`）。
        var migrated = settingsJson
        LEGACY_APPLICATION_IDS.forEach { legacyId ->
            val pathRe = Regex("(file://)?/data/(?:user/0|data)/" + Regex.escape(legacyId) + "(?:\\.debug)?")
            migrated = pathRe.replace(migrated) { "file:///data/user/0/$currentApplicationId" }
        }
        // V0b: 旧包名全限定类名 → 当前根前缀（覆盖各模块命名空间）。
        // Kotlin serialization 多态 sealed class 的 type 字段默认存完整类名（如 Avatar.Dummy），
        // 旧备份里这些 type 带旧包名前缀，反序列化会抛
        // "Serializer for subclass ... is not found in the polymorphic scope"。
        LEGACY_ROOTS.forEach { legacyRoot ->
            val clsRe = Regex("\\b" + Regex.escape(legacyRoot) + "\\.(?=[A-Za-z_])")
            migrated = clsRe.replace(migrated, "$CURRENT_ROOT.")
        }
        return runCatching {
            val root = JsonInstant.parseToJsonElement(migrated).jsonObject.toMutableMap()

            // V1: 修复 mcpServers 中全限定类名的 type 字段
            root["mcpServers"]?.let { element ->
                val migrated = migrateMcpServersJson(JsonInstant.encodeToString(element))
                root["mcpServers"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V2: 修复 assistants 中 UIMessagePart 的 type 字段
            root["assistants"]?.let { element ->
                val migrated = migrateAssistantsJson(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migrated)
            }

            // V3: 将 assistants 中内嵌的 quickMessages 提取为全局 quickMessages
            root["assistants"]?.let { element ->
                val (migratedAssistants, extractedQuickMessages) =
                    migrateAssistantsQuickMessages(JsonInstant.encodeToString(element))
                root["assistants"] = JsonInstant.parseToJsonElement(migratedAssistants)

                if (extractedQuickMessages.isNotEmpty()) {
                    val existing = root["quickMessages"]
                    val existingArray = existing?.let {
                        runCatching { JsonInstant.parseToJsonElement(JsonInstant.encodeToString(it)) as? JsonArray }.getOrNull()
                    } ?: JsonArray(emptyList())
                    val existingIds = existingArray.mapNotNull {
                        (it as? JsonObject)?.get("id")?.toString()?.trim('"')
                    }.toSet()
                    val merged = JsonArray(
                        existingArray + extractedQuickMessages.filter { e ->
                            val id = (e as? JsonObject)?.get("id")?.toString()?.trim('"')
                            id != null && id !in existingIds
                        }
                    )
                    root["quickMessages"] = merged
                }
            }

            // V4: 剔除**已退役的搜索提供方**（`rikkahub`，其服务已不可用）。
            // 搜索提供方不走 `refillBuiltIns` 回填，而是整份多态列表存在于 settings 里；子类的
            // `@SerialName` 已从 sealed 层删除 ⇒ 旧备份里若还带着它，`decodeFromString<Settings>`
            // 会抛 SerializationException，恢复流程直接报 "Failed to restore settings"。
            // `searchServiceSelected` 是**完整列表索引**，被剔项在选中项之前时要同步减一
            // （否则恢复后会静默切到另一个提供方），最后 clamp 到合法范围。
            root["searchServices"]?.let { element ->
                val array = element as? JsonArray
                if (array != null) {
                    val selectedElement = root["searchServiceSelected"] as? JsonPrimitive
                    val storedSelected = selectedElement?.intOrNull ?: 0
                    var removedBeforeSelected = 0
                    val kept = array.filterIndexed { index, item ->
                        val retired = item.isRetiredSearchService()
                        if (retired && index < storedSelected) removedBeforeSelected++
                        !retired
                    }
                    if (kept.size != array.size) {
                        root["searchServices"] = JsonArray(kept)
                        if (selectedElement != null) {
                            val adjusted = (storedSelected - removedBeforeSelected)
                                .coerceIn(0, (kept.size - 1).coerceAtLeast(0))
                            root["searchServiceSelected"] = JsonPrimitive(adjusted)
                        }
                    }
                }
            }

            JsonInstant.encodeToString(JsonObject(root))
        }.onFailure {
            Log.e(TAG, "migrate: Failed to migrate settings JSON, using package-migrated JSON", it)
        }.getOrDefault(migrated)
    }
}
