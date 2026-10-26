package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 manifest v2 的校验语义（design-backup-manifest-and-safety §3.2/§4）。
 *
 * 引擎整体被 Android Context/Room 绑死（本仓无 Robolectric），故把恢复校验抽成纯函数
 * [verifyBackupManifest] 直测；流式记账（sha 采样、条目计数）由真机批次验收覆盖。
 */
class BackupManifestTest {

    private val manifest = BackupManifest(
        createdAt = "20261026_120000",
        items = listOf(BackupItem.DATABASE, BackupItem.FILES),
        sections = mapOf(
            BackupManifestSection.SETTINGS to BackupSectionStats(1, 100),
            BackupManifestSection.DATABASE to BackupSectionStats(1, 2048),
            BackupManifestSection.FILES to BackupSectionStats(3, 4096),
        ),
        settingsSha256 = "a".repeat(64),
        settingsBytes = 100,
        databaseSha256 = "b".repeat(64),
        databaseBytes = 2048,
    )

    private fun verify(
        m: BackupManifest = manifest,
        settingsSha: String? = manifest.settingsSha256,
        settingsBytes: Long = 100,
        dbSha: String? = manifest.databaseSha256,
        dbBytes: Long = 2048,
        restoreDatabase: Boolean = true,
        sections: Map<BackupManifestSection, Int> = mapOf(
            BackupManifestSection.SETTINGS to 1,
            BackupManifestSection.DATABASE to 1,
            BackupManifestSection.FILES to 3,
        ),
    ) {
        verifyBackupManifest(
            manifest = m,
            settingsSha256 = settingsSha,
            settingsBytes = settingsBytes,
            stagedMainSha256 = dbSha,
            stagedMainBytes = dbBytes,
            restoreDatabase = restoreDatabase,
            sectionEntries = sections,
        )
    }

    @Test
    fun `valid manifest passes verification`() {
        verify()
    }

    @Test
    fun `newer manifest version is rejected`() {
        val error = assertThrows(Exception::class.java) {
            verify(m = manifest.copy(formatVersion = BackupManifest.FORMAT_VERSION + 1))
        }
        assertTrue(error.message!!.contains("newer than supported"))
    }

    @Test
    fun `older manifest version is accepted`() {
        verify(m = manifest.copy(formatVersion = 1))
    }

    @Test
    fun `settings checksum mismatch is rejected`() {
        val error = assertThrows(Exception::class.java) {
            verify(settingsSha = "c".repeat(64))
        }
        assertTrue(error.message!!.contains("settings checksum mismatch"))
    }

    @Test
    fun `settings size mismatch is rejected`() {
        assertThrows(Exception::class.java) { verify(settingsBytes = 101) }
    }

    @Test
    fun `database checksum mismatch is rejected only when restoring database`() {
        assertThrows(Exception::class.java) { verify(dbSha = "d".repeat(64)) }
        // 不恢复数据库（只挑 FILES）时主库哈希不核对
        verify(dbSha = "d".repeat(64), restoreDatabase = false)
    }

    @Test
    fun `database size mismatch is rejected`() {
        assertThrows(Exception::class.java) { verify(dbBytes = 2049) }
    }

    @Test
    fun `section entry count mismatch is rejected`() {
        val error = assertThrows(Exception::class.java) {
            verify(sections = mapOf(BackupManifestSection.FILES to 2))
        }
        assertTrue(error.message!!.contains("entry count mismatch"))
    }

    @Test
    fun `legacy manifest without checksums passes structurally`() {
        verify(m = manifest.copy(settingsSha256 = null, settingsBytes = null, databaseSha256 = null, databaseBytes = null))
    }

    // ---- 条目分类（打包/恢复/巡检三处共用的分类器） ----

    @Test
    fun `entry classification covers all sections and excludes manifest`() {
        assertEquals(BackupManifestSection.SETTINGS, classifyBackupEntry("settings.json"))
        assertEquals(BackupManifestSection.DATABASE, classifyBackupEntry("rikka_hub.db"))
        assertEquals(BackupManifestSection.DATABASE, classifyBackupEntry("rikka_hub-wal"))
        assertEquals(BackupManifestSection.DATABASE, classifyBackupEntry("rikka_hub-shm"))
        assertEquals(BackupManifestSection.FILES, classifyBackupEntry("upload/a.png"))
        assertEquals(BackupManifestSection.FILES, classifyBackupEntry("skills/demo/SKILL.md"))
        assertEquals(
            BackupManifestSection.WORKSPACE_FILES,
            classifyBackupEntry("workspace_files/ws1/files/a.txt"),
        )
        assertNull(classifyBackupEntry(BackupManifest.ZIP_ENTRY))
        // 未匹配到专用前缀的条目一律归 FILES（fonts/ 等）——外来文件不丢
        assertEquals(BackupManifestSection.FILES, classifyBackupEntry("unknown/stuff"))
    }

    // ---- JSON 往返（manifest 是跨版本交付物，序列化形状必须稳定） ----

    @Test
    fun `manifest json round trip is stable`() {
        val json = Json { encodeDefaults = false }
        val encoded = json.encodeToString(BackupManifest.serializer(), manifest)
        val decoded = json.decodeFromString(BackupManifest.serializer(), encoded)
        assertEquals(manifest, decoded)
        // 字段名（跨 App 版本读取）钉住；formatVersion 为默认值时可能不编码
        //（encodeDefaults 策略由 DI 的 Json 决定），故只钉非默认字段名
        assertTrue(encoded.contains("\"settingsSha256\""))
        assertTrue(encoded.contains("\"databaseBytes\""))
    }

    @Test
    fun `sha256Hex is the expected digest`() {
        // echo -n abc | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }
}
