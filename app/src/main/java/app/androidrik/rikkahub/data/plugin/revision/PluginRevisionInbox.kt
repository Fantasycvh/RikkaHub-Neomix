package app.androidrik.rikkahub.data.plugin.revision

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller
import app.androidrik.rikkahub.data.plugin.js.JsPluginManifest
import app.androidrik.rikkahub.data.plugin.rules.RulePluginInstaller
import java.io.File

private const val TAG = "PluginRevisionInbox"

/**
 * ADB inbox 快速通道（R3，设计稿 §5.1）：开发者把修订文件
 * `adb push xxx.js|json files/plugin_revisions/inbox/`，前台 onResume 扫描一次，
 * 解析→预检→激活，**处理后即删**（成败皆删，结果进日志；失败可重新 push 同名文件重试）。
 *
 * 仅 debug 构建调用（RouteActivity.onResume 门控，release 不进该代码路径）。
 * 能力按清单**全量自动批准**：通道使用者=设备主人本人，adb push 已是设备完全控制权，
 * 逐项授权只会在开发者快速通道上制造纯摩擦——此为有记录的取舍，不是安全模型变化。
 */
class PluginRevisionInbox(
    context: Context,
    private val jsInstaller: JsPluginInstaller,
    private val ruleInstaller: RulePluginInstaller,
) {
    private val dir = File(File(context.filesDir, PluginRevisionStore.FOLDER_NAME), "inbox")
    private var lastScanAt = 0L

    /** 扫描并处理 inbox；返回逐文件结果（前台可据此 toast，后台仅日志）。 */
    suspend fun scanAndProcess(): List<String> {
        val now = System.currentTimeMillis()
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return emptyList()
        lastScanAt = now
        if (!dir.isDirectory) return emptyList()

        val results = mutableListOf<String>()
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".js") || f.name.endsWith(".json")) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val outcome = runCatching {
                    val text = file.readText()
                    when (file.name.substringAfterLast('.')) {
                        "js" -> {
                            val definition = JsPluginManifest.parse(text).getOrThrow()
                            // inbox 全量自动批准（见类注释取舍）
                            jsInstaller.installOrUpdate(
                                definition,
                                approvedCapabilities = definition.manifest.capabilities,
                                origin = RevisionOrigin.ADB,
                            ).getOrThrow()
                        }

                        else -> {
                            val definition = ruleInstaller.parseText(text).getOrThrow()
                            ruleInstaller.installOrUpdate(
                                definition,
                                approvedCapabilities = setOf(definition.requiredCapability),
                                origin = RevisionOrigin.ADB,
                                sourceOverride = text,
                            ).getOrThrow()
                        }
                    }
                    "activated"
                }.getOrElse { e ->
                    // CancellationException 不吞（取消即退出，文件保留待下次）
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "failed: ${e.message}"
                }
                Log.i(TAG, "inbox ${file.name}: $outcome")
                results += "${file.name}: $outcome"
                file.delete()
            }
        return results
    }

    companion object {
        const val MIN_SCAN_INTERVAL_MS = 1_000L
    }
}
