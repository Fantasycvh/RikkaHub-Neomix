package app.androidrik.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.first
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.repository.FilesRepository

/**
 * 助手**聊天背景**图从 `upload/` 迁到 `backgrounds/` 的**一次性搬迁**
 * （#2127 同款坑的背景图版，2026-10-31）。
 *
 * ## 背景
 *
 * 新写入的背景已由 [FilesManager.createBackgroundImage] 直接落 [FileFolders.BACKGROUNDS]。
 * 但**老版本**的背景经 `createChatFilesByContents` 落在 `upload/` 下，引用也是那条
 * `upload/...` 的 `file://` 地址；若不搬，「设置 → 文件管理」清理聊天文件
 * （整目录递归删）仍会删掉**正在使用的助手背景**。
 *
 * ## 做法与顺序
 *
 * 与 [AvatarMigration] 完全同款（先落盘、后改引用，每步失败保持原状）：
 * 1. 目标已存在 ⇒ 视为内容已在位（上次半途失败），引用改写后旧副本由第③步清理；
 * 2. 移动失败 ⇒ **原封不动**（引用不改，清理时照旧能删，无悬空风险）；
 * 3. 移动成功 ⇒ **先改 `managed_files` 再改引用**（顺序颠倒只会留下无害孤儿记录，方向安全）。
 */
object BackgroundsMigration {
    private const val TAG = "BackgroundsMigration"

    /**
     * 老背景所在的目录。**刻意写成字面量**——与 [AvatarMigration.LEGACY_FOLDER] 同理，
     * 这个常量是迁移的「判据」，即使将来 `UPLOAD` 改名也不改变「哪些算老数据」的含义。
     */
    private const val LEGACY_FOLDER = "upload"

    /**
     * 纯函数：算出需要搬迁的背景 URL 映射（旧 `file://` → 新 `file://`）。
     *
     * 判据是 **URL 里含 `/upload/` 文件路径段**（在 **URL 字符串**上做，不碰 `File.path`
     * ——Windows 反斜杠陷阱见 [AvatarMigration.fileUrlToFile] 的注释）。幂等：
     * 已经是 `/backgrounds/` 或 http(s) 外链的引用不会出现在结果里。
     */
    internal fun planBackgroundMigration(backgroundUrls: List<String>): Map<String, String> {
        return backgroundUrls.mapNotNull { url ->
            if (!url.startsWith("file:")) return@mapNotNull null
            val legacySegment = "/$LEGACY_FOLDER/"
            val index = url.indexOf(legacySegment)
            if (index < 0) return@mapNotNull null
            val newUrl = url.substring(0, index) +
                "/${FileFolders.BACKGROUNDS}/" +
                url.substring(index + legacySegment.length)
            if (newUrl == url) null else url to newUrl
        }.toMap()
    }

    /**
     * 执行搬迁：移文件 → 改 `managed_files` → 改写设置里的引用。
     *
     * 整个过程串行且**逐条独立容错**：某一条失败不影响其余。
     * 不应在主线程调用（含磁盘 IO 与 DataStore 写入）。
     */
    suspend fun migrate(context: Context, repository: FilesRepository, settingsStore: SettingsStore) {
        // 与 AvatarMigration 同理：必须等**真实**设置加载完（first() 挂起等待），
        // 否则 dummy 快照让计划恒为空，迁移被静默跳过
        val settings = settingsStore.settingsFlowRaw.first()
        val plan = planBackgroundMigration(settings.assistants.mapNotNull { it.background })
        if (plan.isEmpty()) return

        val filesDir = context.filesDir
        val backgroundsDir = File(filesDir, FileFolders.BACKGROUNDS)

        data class Applied(val oldUrl: String, val newUrl: String, val oldRelativePath: String)

        val applied = mutableListOf<Applied>()
        plan.forEach { (oldUrl, newUrl) ->
            runCatching {
                val source = AvatarMigration.resolveInFilesDir(filesDir, oldUrl)
                val target = AvatarMigration.resolveInFilesDir(filesDir, newUrl)
                    ?: return@runCatching Log.w(TAG, "migrate: cannot resolve target for $oldUrl, skip")

                if (!backgroundsDir.exists()) {
                    backgroundsDir.mkdirs()
                }

                val moved = when {
                    // 目标内容在位 ⇒ 视为上次半途成功；**不能删旧副本**（引用此刻仍指旧路径）
                    target.isFile -> true
                    source == null || !source.isFile -> {
                        Log.w(TAG, "migrate: both source and target missing for $oldUrl, keep reference")
                        false
                    }
                    else -> source.renameTo(target).also { ok ->
                        if (!ok) Log.w(TAG, "migrate: rename failed ${source.path} -> ${target.path}")
                    }
                }

                if (moved) {
                    applied += Applied(
                        oldUrl = oldUrl,
                        newUrl = newUrl,
                        oldRelativePath = "$LEGACY_FOLDER/${source?.name ?: AvatarMigration.uriLastSegment(oldUrl)}",
                    )
                }
            }.onFailure {
                Log.e(TAG, "migrate: failed for $oldUrl", it)
                Logging.log(TAG, "migrate: failed for $oldUrl ${it.message} | ${it.stackTraceToString()}")
            }
        }

        if (applied.isEmpty()) return

        // ① 先改 managed_files（新路径补录、旧路径删记录）——失败不回滚文件（记录是次要账目）
        applied.forEach { item ->
            runCatching {
                repository.deleteByPath(item.oldRelativePath)
                val target = AvatarMigration.resolveInFilesDir(filesDir, item.newUrl)
                val relativePath = "${FileFolders.BACKGROUNDS}/${target?.name ?: "unknown"}"
                if (target != null && repository.getByPath(relativePath) == null) {
                    val now = System.currentTimeMillis()
                    repository.insert(
                        app.androidrik.rikkahub.data.db.entity.ManagedFileEntity(
                            folder = FileFolders.BACKGROUNDS,
                            relativePath = relativePath,
                            displayName = target.name,
                            mimeType = "image/*",
                            sizeBytes = target.length(),
                            createdAt = target.lastModified().takeIf { it > 0 } ?: now,
                            updatedAt = now,
                        ),
                    )
                }
            }.onFailure { Log.e(TAG, "migrate: managed_files update failed for ${item.newUrl}", it) }
        }

        // ② 再改写引用（最后一步：只有文件确实到位了才会走到这里）
        //    必须走定点改写（dataStore.edit），不能用 update { } 的快照守卫——
        //    同款事故见 AvatarMigration.migrate 的注释（2026-10-13 头像悬空真机事故）
        val urlMap = applied.associate { it.oldUrl to it.newUrl }
        settingsStore.rewriteAssistantBackgroundReferences(urlMap)

        // ③ 引用改完之后才清掉旧副本（扫磁盘，收掉历次半途成功留下的重复文件）
        var cleaned = 0
        applied.forEach { item ->
            runCatching {
                val source = AvatarMigration.resolveInFilesDir(filesDir, item.oldUrl)
                if (source != null && source.isFile && source.delete()) cleaned++
            }
        }

        Log.i(
            TAG,
            "migrate: moved ${applied.size} background(s) from $LEGACY_FOLDER/ to ${FileFolders.BACKGROUNDS}/" +
                " (cleaned $cleaned legacy copy/ies)",
        )
    }
}
