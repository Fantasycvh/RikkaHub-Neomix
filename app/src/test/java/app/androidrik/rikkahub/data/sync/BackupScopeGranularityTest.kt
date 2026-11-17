package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 备份范围细分的纯函数测试：
 * - BackupScope 的 excludedFileGroups round-trip、未知分组容错、容错序列化器（manifest 类型化解码）；
 * - BackupContentFilter 的 8 个新 settings 类别映射与 v3 legacy 语义（新类别键按 ABOUT）；
 * - BackupFolderRegistry 目录→组映射；
 * - manifest v4 verify 兼容矩阵（v2/v3/v4 都可验）。
 */
class BackupScopeGranularityTest {

    private val json = Json

    // ===== BackupScope：excludedFileGroups =====

    @Test
    fun `file groups json round trip`() {
        val scope = BackupScope(
            excluded = setOf(BackupCategory.ABOUT),
            excludedFileGroups = setOf(BackupFileGroup.MEDIA, BackupFileGroup.MISC),
        )
        val decoded = BackupScope.fromJsonOrNull(scope.toJson())!!
        assertEquals(scope, decoded)
        assertFalse(decoded.includesFileGroup(BackupFileGroup.MEDIA))
        assertTrue(decoded.includesFileGroup(BackupFileGroup.PROFILE))
    }

    @Test
    fun `unknown file group name is treated as included`() {
        // 向前兼容：旧版本读新备份时，不认识的分组不能当成「已排除」
        val decoded = BackupScope.fromJsonOrNull(
            buildJsonObject {
                put(
                    "excludedFileGroups",
                    kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("FUTURE_GROUP"))),
                )
            },
        )!!
        assertTrue(decoded.excludedFileGroups.isEmpty())
    }

    @Test
    fun `tolerant serializer drops unknown category names`() {
        // manifest 类型化解码路径（BackupScopeSerializer）：未知类别名按「包含」丢弃，
        // 不抛 SerializationException（否则旧版本读新包整个 manifest 解码失败）
        val decoded = json.decodeFromString(
            BackupScope.serializer(),
            """{"excluded":["SPEECH","FUTURE_CATEGORY"],"excludedFileGroups":["PROFILE","NOPE"]}""",
        )
        assertEquals(setOf(BackupCategory.SPEECH), decoded.excluded)
        assertEquals(setOf(BackupFileGroup.PROFILE), decoded.excludedFileGroups)
    }

    @Test
    fun `withFileGroup updates exclusions`() {
        val scope = BackupScope()
        val excluded = scope.withFileGroup(BackupFileGroup.SKILLS_FONTS, include = false)
        assertFalse(excluded.includesFileGroup(BackupFileGroup.SKILLS_FONTS))
        val restored = excluded.withFileGroup(BackupFileGroup.SKILLS_FONTS, include = true)
        assertEquals(BackupScope(), restored)
    }

    @Test
    fun `intersect combines category and file group exclusions`() {
        val a = BackupScope(excluded = setOf(BackupCategory.DISPLAY), excludedFileGroups = setOf(BackupFileGroup.MEDIA))
        val b = BackupScope(excluded = setOf(BackupCategory.DISPLAY, BackupCategory.SPEECH), excludedFileGroups = emptySet())
        val merged = a.intersect(b)
        assertEquals(setOf(BackupCategory.DISPLAY), merged.excluded)
        assertEquals(emptySet<BackupFileGroup>(), merged.excludedFileGroups)
    }

    // ===== BackupContentFilter：新类别 + legacy =====

    @Test
    fun `new category keys map to their categories`() {
        assertEquals(BackupCategory.SPEECH, BackupContentFilter.categoryOfSettingKey("ttsProviders"))
        assertEquals(BackupCategory.SEARCH, BackupContentFilter.categoryOfSettingKey("searchServices"))
        assertEquals(BackupCategory.MCP, BackupContentFilter.categoryOfSettingKey("mcpServers"))
        assertEquals(BackupCategory.REMOTE_BACKUP, BackupContentFilter.categoryOfSettingKey("webDavConfig"))
        assertEquals(BackupCategory.TOOLS, BackupContentFilter.categoryOfSettingKey("workspaceTurnMaxSteps"))
        assertEquals(BackupCategory.MEMORY, BackupContentFilter.categoryOfSettingKey("memoryTriggerConfig"))
        assertEquals(BackupCategory.TRANSLATE, BackupContentFilter.categoryOfSettingKey("ocrDeepSeekPurpose"))
        assertEquals(BackupCategory.WEB_SERVER, BackupContentFilter.categoryOfSettingKey("webServerPort"))
        assertEquals(BackupCategory.ASSISTANTS, BackupContentFilter.categoryOfSettingKey("quickMessages"))
    }

    @Test
    fun `legacy mode maps new category keys to ABOUT`() {
        // v3 旧包恢复语义：旧版本下这些键属于 ABOUT 兜底——「排除 ABOUT」的旧 scope
        // 在新版恢复时不能因新映射把这些键覆盖成本机之外的值
        assertEquals(
            BackupCategory.ABOUT,
            BackupContentFilter.categoryOfSettingKey("ttsProviders", legacy = true),
        )
        assertEquals(
            BackupCategory.ASSISTANTS,
            BackupContentFilter.categoryOfSettingKey("quickMessages", legacy = true),
        )
        assertEquals(
            BackupCategory.PROVIDERS,
            BackupContentFilter.categoryOfSettingKey("providers", legacy = true),
        )
    }

    @Test
    fun `preserveUnselected legacy keeps local values under ABOUT-excluded v3 scope`() {
        val scope = BackupScope(excluded = setOf(BackupCategory.ABOUT))
        val incoming = buildJsonObject {
            put("ttsProviders", JsonPrimitive("[]"))
            put("providers", JsonPrimitive("[]"))
        }
        val current = buildJsonObject {
            put("ttsProviders", JsonPrimitive("[local]"))
            put("providers", JsonPrimitive("[local]"))
        }
        // legacy（v3 包）：ttsProviders 在旧语义下属 ABOUT（被排除）→ 保留本机
        val legacy = BackupContentFilter.preserveUnselected(incoming, current, scope, legacy = true)
        assertEquals(JsonPrimitive("[local]"), legacy["ttsProviders"])
        assertEquals(JsonPrimitive("[]"), legacy["providers"])
        // 新版语义（v4 包）：ttsProviders 属 SPEECH（未排除）→ 取备份值
        val modern = BackupContentFilter.preserveUnselected(incoming, current, scope, legacy = false)
        assertEquals(JsonPrimitive("[]"), modern["ttsProviders"])
    }

    // ===== BackupFolderRegistry：目录→组 =====

    @Test
    fun `all backed-up folders have a group`() {
        BACKUP_FOLDER_SPECS.filter { it.backedUp }.forEach { spec ->
            assertTrue("folder ${spec.folder} missing group", spec.group != null)
        }
    }

    @Test
    fun `folder group mapping covers all groups`() {
        val groups = BACKUP_FOLDER_SPECS.filter { it.backedUp }.mapNotNull { it.group }.toSet()
        assertEquals(BackupFileGroup.entries.toSet(), groups)
        assertEquals(BackupFileGroup.MEDIA, backupFileGroupFor("images"))
        assertEquals(BackupFileGroup.PROFILE, backupFileGroupFor("avatars"))
        assertEquals(BackupFileGroup.SKILLS_FONTS, backupFileGroupFor("skills"))
        assertEquals(BackupFileGroup.MISC, backupFileGroupFor("upload"))
        assertNull(backupFileGroupFor("not-registered"))
    }

    @Test
    fun `scope file folder filter works with registry mapping`() {
        val scope = BackupScope(excludedFileGroups = setOf(BackupFileGroup.MEDIA))
        assertFalse(scope.includesFileFolder("images", ::backupFileGroupFor))
        assertTrue(scope.includesFileFolder("fonts", ::backupFileGroupFor))
        // 未登记目录不过滤（保守包含）
        assertTrue(scope.includesFileFolder("whatever", ::backupFileGroupFor))
    }

    // ===== manifest v4 兼容矩阵 =====

    private fun manifestOf(version: Int): BackupManifest = BackupManifest(
        createdAt = "20260101_000000",
        items = listOf(BackupItem.DATABASE),
        sections = emptyMap(),
        settingsSha256 = null,
        settingsBytes = null,
        formatVersion = version,
    )

    @Test
    fun `manifest v2 v3 v4 all verify against v4 limit`() {
        listOf(2, 3, 4).forEach { version ->
            verifyBackupManifest(
                manifest = manifestOf(version),
                settingsSha256 = null,
                settingsBytes = 0L,
                stagedMainSha256 = null,
                stagedMainBytes = 0L,
                restoreDatabase = false,
                sectionEntries = emptyMap(),
            )
        }
    }

    @Test
    fun `verify skips settings checksum when verifySettings is false`() {
        // 「仅恢复对话对象」不读 settings 条目：免双校验也能通过
        val manifest = manifestOf(4).copy(settingsSha256 = "deadbeef", settingsBytes = 123)
        verifyBackupManifest(
            manifest = manifest,
            settingsSha256 = null,
            settingsBytes = 0L,
            stagedMainSha256 = null,
            stagedMainBytes = 0L,
            restoreDatabase = false,
            sectionEntries = emptyMap(),
            verifySettings = false,
        )
    }

    @Test
    fun `manifest newer than v4 fails verification`() {
        org.junit.Assert.assertThrows(Exception::class.java) {
            verifyBackupManifest(
                manifest = manifestOf(5),
                settingsSha256 = null,
                settingsBytes = 0L,
                stagedMainSha256 = null,
                stagedMainBytes = 0L,
                restoreDatabase = false,
                sectionEntries = emptyMap(),
            )
        }
    }
}
