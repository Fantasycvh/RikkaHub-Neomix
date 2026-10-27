package app.androidrik.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

/**
 * 应用自重启。
 *
 * 备份恢复的换库家族只被 stage 进 `filesDir/restore_pending/`，真正的换库发生在**下次启动、
 * Room 打开之前**（见 `PendingRestoreStore`）。也就是说，恢复完成后的旧进程仍在旧库上读写，
 * 这些写入在下次启动时会被 staged 库整体覆盖，等于静默丢弃。所以恢复成功后必须立即重启，
 * 「重启」是流程的一部分，不是可以跳过的提示。
 *
 * 风险：`exitProcess` 直接杀进程，来不及落盘的正常数据（内存里的编辑、进行中的后台任务）会丢。
 * 这与恢复语义一致——恢复就是用备份内容整体取代现有数据；反过来不重启才是真的丢数据。
 */
object AppRestart {
    private const val TAG = "AppRestart"

    /**
     * 拉起 launcher 入口启动新实例，然后结束当前进程。
     *
     * 拿不到 launcher 入口时（理论上不该发生，但入口组件被禁用/替换时可能出现）退化为只结束
     * 进程、由用户从桌面重新启动：绝不能因为"重启失败"就把用户留在旧库上继续写。
     */
    fun restart(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            // NEW_TASK：允许从非 Activity 上下文启动；CLEAR_TASK：清掉旧任务栈，否则用户
            // 返回时还能看到本该消失的旧界面。
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            runCatching { context.startActivity(launchIntent) }
                .onFailure { Log.e(TAG, "restart: startActivity failed, exiting only", it) }
        } else {
            Log.w(TAG, "restart: no launch intent for ${context.packageName}, exiting only")
        }
        // 无论新实例是否拉起成功都要退出：留在旧进程里继续写就是数据丢失。
        exitProcess(0)
    }
}
