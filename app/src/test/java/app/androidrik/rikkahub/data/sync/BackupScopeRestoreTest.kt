package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份范围与恢复语义的纯函数测试（借鉴 Kelivo 的 BackupScope/merge 组织形式）。
 *
 * 覆盖：BackupScope 序列化 round-trip 与未知类别容错；BackupContentFilter 的
 * 打包过滤 / 恢复回填 / merge 幂等合并；manifest v2（scope=null）与 v3 兼容。
 */
class BackupScopeRestoreTest {

    private val json = Json

    // ===== BackupScope 序列化 =====

    @Test
    fun `scope json round trip`() {
        val scope = BackupScope(excluded = setOf(BackupCategory.PROVIDERS, BackupCategory.DISPLAY))
        val decoded = BackupScope.fromJsonOrNull(scope.toJson())!!
        assertEquals(scope, decoded)
    }

    @Test
    fun `empty scope round trips to full inclusion`() {
        val decoded = BackupScope.fromJsonOrNull(BackupScope().toJson())!!
        assertTrue(decoded.excluded.isEmpty())
        BackupCategory.entries.forEach { assertTrue(decoded.includes(it)) }
    }

    @Test
    fun `unknown category name is treated as included`() {
        // 向前兼容：旧版本读新备份时，不认识的类别不能当成「已排除」
        val decoded = BackupScope.fromJsonOrNull(
            buildJsonObject {
                put("excluded", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("FUTURE_CATEGORY"))))
            }
        )!!
        assertTrue(decoded.excluded.isEmpty())
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(BackupScope.fromJsonOrNull(JsonPrimitive("not-an-object")))
        // 缺字段 = excluded-set 语义下的「全包含」
        assertEquals(BackupScope(), BackupScope.fromJsonOrNull(null))
    }

    @Test
    fun `withCategory toggles membership`() {
        val scope = BackupScope()
            .withCategory(BackupCategory.ASSISTANTS, include = false)
        assertFalse(scope.includes(BackupCategory.ASSISTANTS))
        val restored = scope.withCategory(BackupCategory.ASSISTANTS, include = true)
        assertTrue(restored.excluded.isEmpty())
    }

    @Test
    fun `intersect keeps only mutually excluded`() {
        val a = BackupScope(excluded = setOf(BackupCategory.PROVIDERS, BackupCategory.DISPLAY))
        val b = BackupScope(excluded = setOf(BackupCategory.DISPLAY))
        assertEquals(setOf(BackupCategory.DISPLAY), a.intersect(b).excluded)
    }

    @Test
    fun `isEmpty requires all categories excluded`() {
        val all = BackupScope(excluded = BackupCategory.entries.toSet())
        assertTrue(all.isEmpty)
        assertFalse(BackupScope(excluded = setOf(BackupCategory.ABOUT)).isEmpty)
    }

    // ===== manifest 兼容 =====

    @Test
    fun `v2 manifest without scope decodes as full inclusion`() {
        val v2Json = """
            {"formatVersion":2,"createdAt":"2026-01-01T00:00:00Z",
             "items":["DATABASE"],"sections":{}}
        """.trimIndent()
        val manifest = json.decodeFromString(BackupManifest.serializer(), v2Json)
        assertNull(manifest.scope)
        assertEquals(2, manifest.formatVersion)
    }

    @Test
    fun `v3 manifest with scope decodes`() {
        val v3Json = """
            {"formatVersion":3,"createdAt":"2026-01-01T00:00:00Z",
             "items":["DATABASE","FILES"],
             "scope":{"excluded":["PROVIDERS","DISPLAY"]}}
        """.trimIndent()
        val manifest = json.decodeFromString(BackupManifest.serializer(), v3Json)
        assertEquals(
            setOf(BackupCategory.PROVIDERS, BackupCategory.DISPLAY),
            manifest.scope?.excluded,
        )
    }

    @Test
    fun `manifest serializes with format version 4`() {
        // v4：BackupScope 增加 excludedFileGroups + 8 个 settings 细分类别（见 BackupScopeGranularityTest）
        assertEquals(4, BackupManifest.FORMAT_VERSION)
    }

    // ===== BackupContentFilter：打包过滤 =====

    @Test
    fun `filterSettingsJson drops excluded category keys`() {
        val settingsJson = buildJsonObject {
            put("providers", buildJsonObject { put("x", JsonPrimitive(1)) })
            put("assistants", buildJsonObject { put("y", JsonPrimitive(2)) })
            put("themeId", JsonPrimitive("dark"))
            put("webDavConfig", buildJsonObject { put("url", JsonPrimitive("https://dav")) })
        }
        val scope = BackupScope(excluded = setOf(BackupCategory.PROVIDERS))
        val filtered = BackupContentFilter.filterSettingsJson(settingsJson, scope)
        assertFalse("providers" in filtered)
        assertTrue("assistants" in filtered)
        assertTrue("themeId" in filtered)
        assertTrue("webDavConfig" in filtered)
    }

    @Test
    fun `filterSettingsJson full scope returns same object`() {
        val settingsJson = buildJsonObject { put("themeId", JsonPrimitive("dark")) }
        assertSame(settingsJson, BackupContentFilter.filterSettingsJson(settingsJson, BackupScope()))
    }

    // ===== BackupContentFilter：恢复回填 =====

    @Test
    fun `preserveUnselected keeps current values for excluded categories`() {
        val incoming = buildJsonObject {
            put("assistants", buildJsonObject { put("from", JsonPrimitive("backup")) })
            put("themeId", JsonPrimitive("backup-theme"))
        }
        val current = buildJsonObject {
            put("assistants", buildJsonObject { put("from", JsonPrimitive("local")) })
            put("themeId", JsonPrimitive("local-theme"))
            put("providers", buildJsonObject { put("from", JsonPrimitive("local")) })
        }
        val scope = BackupScope(excluded = setOf(BackupCategory.PROVIDERS))
        val merged = BackupContentFilter.preserveUnselected(incoming, current, scope)
        assertEquals("backup", merged["assistants"]!!.jsonObject["from"]!!.toString().trim('"'))
        assertEquals("backup-theme", merged["themeId"]!!.toString().trim('"'))
        // 被排除类别：保留本机值
        assertEquals("local", merged["providers"]!!.jsonObject["from"]!!.toString().trim('"'))
    }

    @Test
    fun `preserveUnselected drops incoming keys of excluded categories`() {
        // 打包侧本不该写出被排除类别的键；出现即视为陈旧数据，不得回灌
        val incoming = buildJsonObject {
            put("providers", buildJsonObject { put("from", JsonPrimitive("stale-backup")) })
            put("themeId", JsonPrimitive("backup-theme"))
        }
        val current = buildJsonObject {
            put("providers", buildJsonObject { put("from", JsonPrimitive("local")) })
            put("themeId", JsonPrimitive("local-theme"))
        }
        val scope = BackupScope(excluded = setOf(BackupCategory.PROVIDERS))
        val merged = BackupContentFilter.preserveUnselected(incoming, current, scope)
        assertEquals("local", merged["providers"]!!.jsonObject["from"]!!.toString().trim('"'))
        assertEquals("backup-theme", merged["themeId"]!!.toString().trim('"'))
    }

    @Test
    fun `preserveUnselected full scope returns incoming as is`() {
        val incoming = buildJsonObject { put("themeId", JsonPrimitive("dark")) }
        assertSame(
            incoming,
            BackupContentFilter.preserveUnselected(incoming, buildJsonObject { }, BackupScope()),
        )
    }

    // ===== merge 幂等合并 =====

    private data class Item(val id: Uuid, val label: String)

    private val uuidA = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val uuidB = Uuid.parse("00000000-0000-0000-0000-00000000000b")
    private val uuidC = Uuid.parse("00000000-0000-0000-0000-00000000000c")

    @Test
    fun `mergeById appends missing and keeps local for same id`() {
        val current = listOf(Item(uuidA, "local-a"), Item(uuidB, "local-b"))
        val incoming = listOf(Item(uuidB, "backup-b"), Item(uuidC, "backup-c"))
        val merged = BackupContentFilter.mergeById(current, incoming) { it.id }
        // 本地在前且同 id 保留本地版；备份多出的追加在尾
        assertEquals(listOf("local-a", "local-b", "backup-c"), merged.map { it.label })
    }

    @Test
    fun `mergeById with empty incoming returns current unchanged`() {
        val current = listOf(Item(uuidA, "local-a"))
        assertSame(current, BackupContentFilter.mergeById(current, emptyList()) { it.id })
    }

    @Test
    fun `mergeSettingsById merges assistants by id`() {
        val localAssistant = Assistant(id = uuidA, name = "local")
        val backupSameId = Assistant(id = uuidA, name = "backup")
        val backupNew = Assistant(id = uuidC, name = "new")
        val incoming = Settings(assistants = listOf(backupSameId, backupNew))
        val current = Settings(assistants = listOf(localAssistant))
        val merged = BackupContentFilter.mergeSettingsById(incoming, current)
        assertEquals(listOf("local", "new"), merged.assistants.map { it.name })
    }

    @Test
    fun `mergeSettingsById keeps incoming for non-list fields`() {
        val incoming = Settings(chatModelId = uuidB)
        val current = Settings(chatModelId = uuidA)
        val merged = BackupContentFilter.mergeSettingsById(incoming, current)
        assertEquals(uuidB, merged.chatModelId)
    }
}
