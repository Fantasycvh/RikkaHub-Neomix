package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 备份 manifest v2（design-backup-manifest-and-safety §3.1，Yuihub 3.0.0 `BackupModels` 同域）。
 *
 * 作为 **zip 末条目** `manifest.json` 写入（实现偏离设计稿的「首条目」：本仓恢复流程本就把
 * settings 缓冲、数据库暂存到最后，校验天然可以末置，无需约束打包顺序）。恢复时在读毕后
 * 统一校验，通过才执行破坏性步骤（settings 写入、换库）——损坏/外来/被篡改的备份零破坏。
 *
 * 双 SHA-256 只做 settings + 主库（全量逐文件哈希成本不成立）；FILES/WORKSPACE_FILES 的
 * section 统计（条数+字节）是**预览信息**，恢复侧只强制条目数（字节流中逐条计费不可得）。
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val createdAt: String,
    val items: List<BackupItem>,
    val sections: Map<BackupManifestSection, BackupSectionStats> = emptyMap(),
    val settingsSha256: String? = null,
    val settingsBytes: Long? = null,
    val databaseSha256: String? = null,
    val databaseBytes: Long? = null,
) {
    companion object {
        const val FORMAT_VERSION = 2
        const val ZIP_ENTRY = "manifest.json"

        /** 恢复侧单条目解压上限（zip 炸弹兜底；ZipEntry.size 常为 -1，必须边拷边数）。 */
        const val MAX_ENTRY_BYTES: Long = 2L * 1024 * 1024 * 1024

        /** 恢复侧总解压上限。 */
        const val MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024 * 1024
    }
}

@Serializable
enum class BackupManifestSection {
    SETTINGS,
    DATABASE,
    FILES,
    WORKSPACE_FILES,
}

@Serializable
data class BackupSectionStats(
    val entries: Int,
    val uncompressedBytes: Long,
)

/** 把 zip 条目名归类到 manifest section（打包/恢复/巡检三处共用，防漂移）。 */
internal fun classifyBackupEntry(name: String): BackupManifestSection? = when {
    name == "settings.json" -> BackupManifestSection.SETTINGS
    name == BackupManifest.ZIP_ENTRY -> null
    name == "rikka_hub.db" || name.startsWith("rikka_hub-") -> BackupManifestSection.DATABASE
    name.startsWith("workspace_files/") -> BackupManifestSection.WORKSPACE_FILES
    else -> BackupManifestSection.FILES
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * [BackupArchiveEngine.inspectBackup] 的结果。
 *
 * `manifest == null` 表示 legacy 备份（v1 格式或外来包）：sections 由条目名推断
 * （中央目录尺寸，ZipFile 随机访问，不全量解压）。
 */
data class BackupInspection(
    val manifest: BackupManifest?,
    val sections: Map<BackupManifestSection, BackupSectionStats>,
    val entryCount: Int,
)

/**
 * manifest 统一校验（design-backup-manifest-and-safety §3.2）——**纯函数**，引擎在
 * 执行任何破坏性步骤（settings 写入、换库）前调用；不过就抛错，零破坏。
 *
 * 抽成纯函数的原因：引擎被 Android Context/Room 绑死（本仓无 Robolectric），
 * 校验语义（版本/双 SHA-256/条目数）又是本批次的核心安全逻辑，必须在 JVM 直测。
 *
 * @param restoreDatabase 恢复项是否含 DATABASE（含才核对主库哈希）
 * @param sectionEntries 恢复侧按条目名归类的条目计数（与打包侧同一份 classify）
 */
internal fun verifyBackupManifest(
    manifest: BackupManifest,
    settingsSha256: String?,
    settingsBytes: Long,
    stagedMainSha256: String?,
    stagedMainBytes: Long,
    restoreDatabase: Boolean,
    sectionEntries: Map<BackupManifestSection, Int>,
) {
    if (manifest.formatVersion > BackupManifest.FORMAT_VERSION) {
        throw Exception(
            "Backup manifest version ${manifest.formatVersion} is newer than supported " +
                "(${BackupManifest.FORMAT_VERSION}); update the app first"
        )
    }
    manifest.settingsSha256?.let { expected ->
        if (settingsSha256 != expected) {
            throw Exception("Backup settings checksum mismatch (corrupted or tampered backup)")
        }
    }
    manifest.settingsBytes?.let { expected ->
        if (settingsBytes != expected) {
            throw Exception("Backup settings size mismatch: expected $expected, got $settingsBytes")
        }
    }
    if (restoreDatabase) {
        manifest.databaseSha256?.let { expected ->
            if (stagedMainSha256 != expected) {
                throw Exception("Backup database checksum mismatch (corrupted or tampered backup)")
            }
        }
        manifest.databaseBytes?.let { expected ->
            if (stagedMainBytes != expected) {
                throw Exception("Backup database size mismatch: expected $expected, got $stagedMainBytes")
            }
        }
    }
    manifest.sections.forEach { (section, stats) ->
        val actual = sectionEntries.getOrDefault(section, 0)
        if (actual != stats.entries) {
            throw Exception(
                "Backup section $section entry count mismatch: " +
                    "expected ${stats.entries}, got $actual"
            )
        }
    }
}
