package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.sync.PendingRestoreStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级「恢复完成，必须重启」信号。
 *
 * 为什么不继续让备份页自己弹重启对话框：备份页只是根 UI 下一条可离开的路由，页面内状态会
 * 随返回/切页/重建丢失。一旦丢失，进程就继续在旧库上读写，下次启动时这些写入被 staged 库
 * 整体覆盖——用户看到的是「恢复成功，但恢复后产生的数据不见了」。把"需要重启"提升到进程级
 * 单例：备份页只负责**报告恢复完成**，由根 UI 的 [RestoreRestartGate] 唯一展示强制重启
 * 对话框（页面与根 UI 各弹一个就会叠两层）。
 */
object RestoreRestartSignal {
    private val _restartRequired = MutableStateFlow(false)

    /** 只进不退：置位后直到进程真正重启都不会清除。 */
    val restartRequired = _restartRequired.asStateFlow()

    /**
     * 恢复/导入完成且需要重启时调用；幂等。主场景是整库恢复（换库已 stage 进
     * `restore_pending/`），providers 类导入同样需要重启让设置生效。
     */
    fun requestRestart() {
        _restartRequired.value = true
    }
}

/**
 * App 级「恢复后强制重启」闸门：套在根 UI 外面，命中就盖一层强制重启对话框。
 *
 * 命中路径有两条：
 * 1. [RestoreRestartSignal]——恢复成功当场命中，不必等下一次生命周期回调；
 * 2. 磁盘上的 `pending.marker`——进入 Ready 时与每次回到前台各查一次，兜住"恢复已完成，
 *    但这个进程还没重启"的残留状态（例如弹窗所在页面已被离开/重建）。
 *
 * 没有 pending 时只有一次 `File.exists()`，其余开销为零。本闸门只负责「发现并强制重启」，
 * 不参与换库本身——换库仍由 `RikkaHubApp.doBoot` 在下次启动时执行。
 */
@Composable
fun RestoreRestartGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val restartRequested by RestoreRestartSignal.restartRequired.collectAsStateWithLifecycle()
    // 磁盘兜底状态。先 false，进入组合后再读一次，避免在组合阶段做 IO。
    var pendingOnDisk by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pendingOnDisk = PendingRestoreStore.hasPending(context.filesDir)
    }

    // 每次回到前台再看一眼：本进程完全可能在"恢复完成"之后才回到前台，期间它一直在旧库上写。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pendingOnDisk = PendingRestoreStore.hasPending(context.filesDir)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    content()
    if (restartRequested || pendingOnDisk) {
        BackupDialog()
    }
}
