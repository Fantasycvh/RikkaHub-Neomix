package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份可移植化清洗（设备态剥离）的纯函数测试：
 * 打包侧剔除（[BackupPortability.portable]）与恢复侧回填本机值
 * （[BackupPortability.preserveDeviceState]），覆盖整键与嵌套字段两种形态。
 */
class BackupPortabilityTest {

    private fun settingsJson(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    // ===== 打包侧 portable =====

    @Test
    fun `portable strips device state keys`() {
        val input = buildJsonObject {
            put("launchCount", JsonPrimitive(42))
            put("importedSources", JsonObject(emptyMap()))
            put("themeId", JsonPrimitive("dark"))
            put("providers", JsonObject(emptyMap()))
        }
        val output = BackupPortability.portable(input)
        assertFalse("launchCount" in output)
        assertFalse("importedSources" in output)
        // 用户数据原样保留
        assertEquals(JsonPrimitive("dark"), output["themeId"])
        assertTrue("providers" in output)
    }

    @Test
    fun `portable strips only lastBackupTime from backupReminderConfig`() {
        val reminder = buildJsonObject {
            put("enabled", JsonPrimitive(true))
            put("intervalDays", JsonPrimitive(7))
            put("lastBackupTime", JsonPrimitive(1700000000000L))
        }
        val output = BackupPortability.portable(
            buildJsonObject {
                put("backupReminderConfig", reminder)
            },
        )
        val cleaned = output["backupReminderConfig"]!!.jsonObject
        assertFalse("lastBackupTime" in cleaned)
        // 用户偏好字段应随备份走（整键剔除会误伤）
        assertEquals(JsonPrimitive(true), cleaned["enabled"])
        assertEquals(JsonPrimitive(7), cleaned["intervalDays"])
    }

    @Test
    fun `portable is identity when no device state present`() {
        val input = settingsJson("themeId" to "dark", "dynamicColor" to "true")
        assertEquals(input, BackupPortability.portable(input))
    }

    @Test
    fun `portable is identity for reminder config without timestamp`() {
        val input = buildJsonObject {
            put(
                "backupReminderConfig",
                buildJsonObject { put("enabled", JsonPrimitive(false)) },
            )
        }
        assertEquals(input, BackupPortability.portable(input))
    }

    // ===== 恢复侧 preserveDeviceState =====

    @Test
    fun `preserveDeviceState keeps local device state`() {
        val incoming = settingsJson("themeId" to "dark", "launchCount" to "999")
        val current = buildJsonObject {
            put("themeId", JsonPrimitive("light"))
            put("launchCount", JsonPrimitive(5))
        }
        val merged = BackupPortability.preserveDeviceState(incoming, current)
        assertEquals(JsonPrimitive(5), merged["launchCount"])
        // 非设备态字段正常取 incoming
        assertEquals(JsonPrimitive("dark"), merged["themeId"])
    }

    @Test
    fun `preserveDeviceState wins over stale device state in old packages`() {
        // 本阶段之前的旧包未清洗过设备态：恢复时也必须以本机为准
        val incoming = buildJsonObject {
            put("importedSources", JsonObject(emptyMap()))
            put("launchCount", JsonPrimitive(100))
        }
        val current = buildJsonObject {
            put("importedSources", JsonObject(emptyMap()))
            put("launchCount", JsonPrimitive(7))
        }
        val merged = BackupPortability.preserveDeviceState(incoming, current)
        assertEquals(JsonPrimitive(7), merged["launchCount"])
    }

    @Test
    fun `preserveDeviceState keeps local lastBackupTime over incoming one`() {
        val incoming = buildJsonObject {
            put(
                "backupReminderConfig",
                buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put("intervalDays", JsonPrimitive(3))
                    put("lastBackupTime", JsonPrimitive(111L))
                },
            )
        }
        val current = buildJsonObject {
            put(
                "backupReminderConfig",
                buildJsonObject {
                    put("enabled", JsonPrimitive(false))
                    put("intervalDays", JsonPrimitive(7))
                    put("lastBackupTime", JsonPrimitive(222L))
                },
            )
        }
        val merged = BackupPortability.preserveDeviceState(incoming, current.jsonObject)
        val reminder = merged["backupReminderConfig"]!!.jsonObject
        assertEquals(JsonPrimitive(222L), reminder["lastBackupTime"])
        // 偏好字段仍取 incoming
        assertEquals(JsonPrimitive(true), reminder["enabled"])
        assertEquals(JsonPrimitive(3), reminder["intervalDays"])
    }

    @Test
    fun `preserveDeviceState adds local timestamp when incoming lacks it`() {
        // 打包侧已清洗：incoming 无 lastBackupTime，需从 current 回填
        val incoming = buildJsonObject {
            put(
                "backupReminderConfig",
                buildJsonObject { put("enabled", JsonPrimitive(true)) },
            )
        }
        val current = buildJsonObject {
            put(
                "backupReminderConfig",
                buildJsonObject {
                    put("enabled", JsonPrimitive(false))
                    put("lastBackupTime", JsonPrimitive(333L))
                },
            )
        }
        val merged = BackupPortability.preserveDeviceState(incoming, current.jsonObject)
        val reminder = merged["backupReminderConfig"]!!.jsonObject
        assertEquals(JsonPrimitive(333L), reminder["lastBackupTime"])
        assertEquals(JsonPrimitive(true), reminder["enabled"])
    }

    @Test
    fun `preserveDeviceState no-op when current lacks device keys`() {
        val incoming = settingsJson("themeId" to "dark", "launchCount" to "9")
        val current = settingsJson("dynamicColor" to "true")
        val merged = BackupPortability.preserveDeviceState(incoming, current)
        assertFalse("launchCount" in merged)
        assertEquals(JsonPrimitive("dark"), merged["themeId"])
    }

    @Test
    fun `preserveDeviceState handles reminder config malformed gracefully`() {
        // backupReminderConfig 不是对象（脏数据）时不抛异常，原样保留
        val incoming = buildJsonObject {
            put("backupReminderConfig", JsonPrimitive("oops"))
        }
        val current = settingsJson()
        val merged = BackupPortability.preserveDeviceState(incoming, current)
        assertEquals(JsonPrimitive("oops"), merged["backupReminderConfig"])
    }

    // ===== round trip：打包清洗 → 恢复回填 =====

    @Test
    fun `round trip preserves local device state`() {
        val local = buildJsonObject {
            put("launchCount", JsonPrimitive(11))
            put(
                "backupReminderConfig",
                buildJsonObject {
                    put("enabled", JsonPrimitive(true))
                    put("lastBackupTime", JsonPrimitive(999L))
                },
            )
            put("themeId", JsonPrimitive("dark"))
        }
        val packed = BackupPortability.portable(local)
        assertFalse("launchCount" in packed)
        assertFalse("lastBackupTime" in packed["backupReminderConfig"]!!.jsonObject)

        val restored = BackupPortability.preserveDeviceState(packed, local)
        assertEquals(JsonPrimitive(11), restored["launchCount"])
        val reminder = restored["backupReminderConfig"]!!.jsonObject
        assertEquals(JsonPrimitive(999L), reminder["lastBackupTime"])
        assertEquals(JsonPrimitive(true), reminder["enabled"])
        assertEquals(JsonPrimitive("dark"), restored["themeId"])
    }
}
