package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.utils.AppRestart
import kotlinx.coroutines.delay

/**
 * 恢复完成后的**强制**重启提示。
 *
 * 换库要等到下次启动才发生，当前进程继续活在旧库上，期间的写入会在下次启动被 staged 库
 * 整体覆盖。所以这里不给任何关闭出口：返回键、点击外部都不行，也没有可点的按钮——
 * 短暂展示文案后直接重启。
 *
 * 只由 [RestoreRestartGate] 一处渲染：备份页只上报 [RestoreRestartSignal] 信号，页面自己
 * 不再弹框，避免根 UI 与页面各弹一个、叠成两层。
 *
 * 防重入：`LaunchedEffect(Unit)` 只在进入组合时启动一次，重组不会重跑 effect；即便极端情况下
 * 再次进入组合，[AppRestart.restart] 以 `exitProcess` 收尾，也不存在第二次执行的机会。
 */
@Composable
fun BackupDialog() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // 留一点时间让用户读到"正在重启"，也避免恢复成功的提示刚出现就被重启吞掉。
        delay(700)
        AppRestart.restart(context)
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(stringResource(R.string.backup_page_restarting))
            }
        },
        confirmButton = {},
    )
}
