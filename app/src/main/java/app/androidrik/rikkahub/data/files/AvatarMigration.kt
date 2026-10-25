package app.androidrik.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.first
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.repository.FilesRepository

/**
 * 头像从 `upload/` 迁到 `avatars/` 的**一次性搬迁**（上游 issue #2127 的存量部分）。
 *
 * ## 背景
 *
 * 新写入的头像已由 [FilesManager.createAvatarImage] 直接落 [FileFolders.AVATARS]。
 * 但**老版本**的头像经 `createChatFilesByContents` 落在 [FileFolders.UPLOAD] 下，
 * 引用也是那条 `upload/...` 的 `file://` 地址；若不搬，这些头像仍与聊天附件同处一室，
 * **「设置 → 文件管理」里对聊天文件的「清理」（整目录递归删）仍会删掉正在使用的头像**
 * ——也就是本次要修的那个 bug 对存量用户依然成立。
 *
 * ## 做法与顺序（为什么不能更简单）
 *
 * 参考本仓做过的同类搬迁（备份恢复的包名迁移、世界书种子三重迁移）：**先落盘、后改引用**，
 * 且每一步都「失败就保持原状」——头像引用悬空比占一点空间糟得多。具体：
 * 1. 目标已存在 ⇒ 视为内容已在位（上次半途失败），只清掉旧副本；
 * 2. 移动失败 ⇒ **原封不动**（引用不改、`managed_files` 记录不动，清理时照旧能删）；
 * 3. 移动成功 ⇒ **先改 `managed_files` 再改引用**。这个顺序不是随意的：若反过来（先改引用、
 *    后删记录）而中间崩溃，旧记录还在而文件已不在原处 —— 那只是启动对账
 *    （[FilesManager.syncFolder]）会清掉的**无害孤儿记录**，方向安全。
 *
 * ## 与「清理」的关系
 *
 * 搬完以后头像就不在 `upload/` 里了，因此删除助手头像 / 换头像时走的
 * `deleteChatFiles` 依旧按 `file://` 路径直接删文件 + 删记录，**零改动**。
 */
object AvatarMigration {
    private const val TAG = "AvatarMigration"

    /**
     * 老头像所在的目录。**刻意不是 [FileFolders.UPLOAD] 的引用**——
     * 这个常量是迁移的「判据」，写死成字面量后即使将来 `UPLOAD` 改名也不会
     * 悄悄改变「哪些算老数据」的含义。
     */
    private const val LEGACY_FOLDER = "upload"

    /**
     * 纯函数：算出需要搬迁的头像 URL 映射（旧 `file://` → 新 `file://`）。
     *
     * 判据是 **URL 里含 `/upload/` 文件路径段**，而不是「文件当前是否存在」——
     * 与磁盘状态无关，故可在 JVM 上逐例钉住（见 `AvatarMigrationPlanTest`）。
     * 幂等：已经是 `/avatars/` 的引用不会出现在结果里。
     */
    internal fun planAvatarMigration(settings: Settings): Map<String, String> {
        val urls = buildList {
            settings.assistants.forEach { assistant ->
                (assistant.avatar as? Avatar.Image)?.let { add(it.url) }
            }
            (settings.displaySetting.userAvatar as? Avatar.Image)?.let { add(it.url) }
        }

        return urls.mapNotNull { url ->
            if (!url.startsWith("file:")) return@mapNotNull null
            val legacySegment = "/$LEGACY_FOLDER/"
            val index = url.indexOf(legacySegment)
            // 已经不是 upload/ 的引用（含已搬过的 avatars/）不会命中；文件名保持不变（便于排查）
            if (index < 0) return@mapNotNull null
            val newUrl = url.substring(0, index) +
                "/${FileFolders.AVATARS}/" +
                url.substring(index + legacySegment.length)
            if (newUrl == url) null else url to newUrl
        }.toMap()
    }

    /**
     * `file://` URL → 本地文件；非 `file://`（http(s) 外链等）返回 null。
     *
     * ⚠️ **不要拿 `File.path` 去判断或拼接路径**（本函数的历史版本正是这么错的）：
     * `java.io.File`/`java.net.URI` 的 path 在 **Windows 上是反斜杠**，而这里处理的是
     * **URL 字符串**（永远是正斜杠）⇒ 用 `indexOf("/upload/")` 之类的判据在 Windows 上
     * 会恒不命中，单测在开发机上直接红、而真机（Android/Linux）绿——**两侧行为不一致**正是
     * 这类 bug 最坏的地方。故：**判据与改写一律在 URL 字符串上做**（见 [planAvatarMigration]），
     * 本函数只负责「URL → File」这一步转换。
     */
    private fun fileUrlToFile(url: String): File? {
        if (!url.startsWith("file:")) return null
        return runCatching {
            // URI 解出的是带正斜杠的绝对路径；File 只是把它交给文件系统（各平台自会归一）
            File(URI(url).path ?: return@runCatching null)
        }.getOrNull()
    }

    /**
     * 执行搬迁：移文件 → 改 `managed_files` → 改写设置里的引用。
     *
     * 整个过程串行且**逐头像独立容错**：某一个失败不影响其余。
     * 不应在主线程调用（含磁盘 IO 与 DataStore 写入）。
     */
    suspend fun migrate(context: Context, repository: FilesRepository, settingsStore: SettingsStore) {
        // 必须等**真实**设置加载完，不能用 `settingsFlow.value`：它在 DataStore 首次产出前是
        // `Settings.dummy()`（助手列表为空）⇒ 计划恒为空、迁移被静默跳过，而且这次启动里
        // 用户随后去「文件管理」点清理，那些头像照旧会被删。`first()` 是挂起等待，不是空转。
        val plan = planAvatarMigration(settingsStore.settingsFlowRaw.first())
        if (plan.isEmpty()) return

        val filesDir = context.filesDir
        val avatarsDir = File(filesDir, FileFolders.AVATARS)

        data class Applied(val oldUrl: String, val newUrl: String, val oldRelativePath: String)

        val applied = mutableListOf<Applied>()
        plan.forEach { (oldUrl, newUrl) ->
            runCatching {
                val source = resolveInFilesDir(filesDir, oldUrl)
                val target = resolveInFilesDir(filesDir, newUrl)
                    ?: return@runCatching Log.w(TAG, "migrate: cannot resolve target for $oldUrl, skip")

                if (!avatarsDir.exists()) {
                    avatarsDir.mkdirs()
                }

                // ⚠️ 把内容搬到 target，但**这一步失败不算致命**——只要引用不被改写，旧文件仍在原处、
                // 头像照旧能显示。反过来才是灾难。
                val moved = when {
                    target.isFile -> {
                        // 内容已在目标位。**这里不能删旧副本**（历史版本就是在此处删的）——引用此刻
                        // 仍指向旧路径，删了源文件而后续改写引用又失败的话，引用当场悬空，表现就是
                        // 「助手头像突然显示不出来」（2026-10-13 真机事故）。旧副本留到引用改写成功
                        // 之后再由下面的第③步清理。
                        //
                        // 注意这一分支**也包含「源已不存在」**（source 为 null 或非文件）：那正是
                        // 上述事故留下的现场——文件已搬到 avatars/，引用却还指着 upload/。若把它当
                        // 「源缺失 ⇒ 跳过」，这个状态就永远修不回来（plan 每轮都产出、每轮都被跳过）。
                        // 判据是「目标内容在位」，与源在不在无关。
                        true
                    }
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
                        // source 可能为 null（上次已搬走）或刚被 rename 掉；File.name 只是字符串，可靠
                        oldRelativePath = "$LEGACY_FOLDER/${source?.name ?: uriLastSegment(oldUrl)}",
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
                val target = resolveInFilesDir(filesDir, item.newUrl)
                val relativePath = "${FileFolders.AVATARS}/${target?.name ?: "unknown"}"
                if (target != null && repository.getByPath(relativePath) == null) {
                    val now = System.currentTimeMillis()
                    repository.insert(
                        app.androidrik.rikkahub.data.db.entity.ManagedFileEntity(
                            folder = FileFolders.AVATARS,
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
        //
        // ⚠️ **必须走 settingsStore.rewriteAvatarReferences（dataStore.edit 定点改写），不能用
        // `update { }`**：`update(Settings)` 有 `if (settings.init) return` 守卫，而本迁移在启动极早期
        // 运行、`settingsFlow.value` 当时仍是 `Settings.dummy()`（init = true）⇒ 改写会被静默丢弃。
        // 2026-10-13 真机实证：5 个头像都搬了，引用一条没改，头像因此全部悬空显示不出来。
        val urlMap = applied.associate { it.oldUrl to it.newUrl }
        settingsStore.rewriteAvatarReferences(urlMap)

        // ③ **引用改完之后**才清掉旧副本。扫磁盘而不是按 applied 逐条删：即使某个 target 早就存在
        // （上次半途成功、这次走 target.isFile 分支），旧副本也要在这里被收掉，否则 upload/ 里
        // 会长期留着与头像一模一样的重复文件（白占空间，且下次「清理聊天文件」会把它当附件删掉——
        // 虽然此时已经无害）。
        var cleaned = 0
        applied.forEach { item ->
            runCatching {
                val source = resolveInFilesDir(filesDir, item.oldUrl)
                if (source != null && source.isFile && source.delete()) cleaned++
            }
        }

        Log.i(
            TAG,
            "migrate: moved ${applied.size} avatar(s) from $LEGACY_FOLDER/ to ${FileFolders.AVATARS}/" +
                " (cleaned $cleaned legacy copy/ies)",
        )
    }

    /**
     * 从一个 `file://` URL 里取出最后一段文件名。
     *
     * 只在「源文件已经不在、需要清 `managed_files` 里的旧记录」时用作兜底：那种情况下我们
     * 拿不到 `File.name`，但仍需要拼出 `upload/<name>` 这个**相对路径**去删记录。
     * 一律在 URL 字符串上切（正斜杠），不经过 `File`（Windows 上是反斜杠，见上方注释）。
     */
    private fun uriLastSegment(url: String): String =
        url.substringAfterLast('/').substringBefore('?').substringBefore('#')

    /**
     * 把一个 `file://` URL 解析成 `filesDir` 内的文件；**越界（不在 filesDir 下）返回 null**。
     *
     * 用 `contains` 而非 `==` 是沿用本仓既有判据（见 `getFileNameFromUri` 一类 helper），
     * 以兼容 `/data/user/0/...` 与 `/data/data/...` 这类同义前缀差异。
     */
    private fun resolveInFilesDir(filesDir: File, url: String): File? {
        val file = fileUrlToFile(url) ?: return null
        // 越界保护：只动 filesDir 内的文件（与 FilesManager 既有判据同款，用 contains 兼容
        // /data/user/0/... 与 /data/data/... 这类同义前缀）
        if (!file.path.contains(filesDir.path)) return null
        return file
    }
}
