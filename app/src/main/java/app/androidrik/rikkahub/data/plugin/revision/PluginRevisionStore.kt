package app.androidrik.rikkahub.data.plugin.revision

import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "PluginRevisionStore"

/**
 * 修订历史的文件存储（R1，设计稿 §六）：`files/plugin_revisions/<pluginId>.json`。
 *
 * - 目录已登记 [app.androidrik.rikkahub.data.files.FileFolders.PLUGIN_REVISIONS]，
 *   备份**豁免**（设计稿 §1.2：初版不入备份，重装即丢历史；生效定义本体仍在既有 DataStore，
 *   备份/迁移行为与现状一致）。
 * - 每插件一个文件，写盘原子化（tmp+rename）；读失败按空历史处理（坏文件不拖垮安装流程）。
 */
class PluginRevisionStore(private val baseDir: File, private val json: Json) {
    private val dir get() = File(baseDir, FOLDER_NAME)

    fun load(pluginId: String): PluginRevisionState {
        val file = File(dir, revisionFileName(pluginId))
        if (!file.isFile) return PluginRevisionState(pluginId = pluginId)
        return runCatching {
            json.decodeFromString(PluginRevisionState.serializer(), file.readText())
        }.onFailure { Log.w(TAG, "failed to load revisions for $pluginId", it) }
            .getOrDefault(PluginRevisionState(pluginId = pluginId))
    }

    fun save(state: PluginRevisionState) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, revisionFileName(state.pluginId))
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(PluginRevisionState.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed for ${file.name}" }
            }
        }.onFailure { Log.w(TAG, "failed to save revisions for ${state.pluginId}", it) }
    }

    companion object {
        const val FOLDER_NAME = "plugin_revisions"
    }
}
