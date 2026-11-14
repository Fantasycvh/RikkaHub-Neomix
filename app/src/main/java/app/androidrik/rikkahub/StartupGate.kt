package app.androidrik.rikkahub

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.ui.pages.agentrun.SubagentApprovalDialog
import okhttp3.OkHttpClient

@Composable
internal fun AppStartupGateContent(
    okHttpClient: OkHttpClient,
    appRoutes: @Composable () -> Unit,
) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { okHttpClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory(scaleToDensity = true))
            }
            .build()
    }
    appRoutes()
    app.androidrik.rikkahub.ui.pages.agentrun.SubagentApprovalDialog()
}

/** Restoring 阻塞屏：换库 + Koin 完成前的全屏转圈。基础 Material 主题（不走 Koin）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StartupBlockingScreen() {
    GateTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularWavyProgressIndicator()
                Text(
                    text = stringResource(R.string.startup_restore_running),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 降级隔离的两个弹窗（design-db-downgrade-rebuild.md §4.5/§5，2026-10-31 拍板：弹窗需要）：
 * 1. [RikkaHubApp.DowngradeState.quarantined] 非空 = 本次启动发生了隔离 → 一次性告知（不可点外关闭）；
 * 2. 否则有可恢复隔离件 → 恢复提示：确认则 stage + 走 RestoreRestartGate 强制重启，
 *    「不再提示」写 dismissed.marker，备份页常驻入口仍可见（拍板：双入口）。
 */
@Composable
internal fun DowngradeDialogs(startupApp: RikkaHubApp) {
    val state by startupApp.downgradeState.collectAsState()
    val scope = rememberCoroutineScope()

    val quarantined = state.quarantined
    if (quarantined != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("数据库已重建") },
            text = {
                Text(
                    "检测到数据库来自更新版本的应用（schema v${quarantined.schemaVersion}），" +
                        "本版本无法打开。已重建空白数据库，原数据已备份在本地；升级回对应版本后，" +
                        "可在「设置 → 备份 → 降级数据恢复」中找回。",
                )
            },
            confirmButton = {
                TextButton(onClick = { startupApp.acknowledgeDowngradeNotice() }) {
                    Text("知道了")
                }
            },
        )
        return
    }

    val entry = state.recoverable.firstOrNull() ?: return
    val backupDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(entry.marker.quarantinedAt))
    // 失败信息内联展示（本层在 Toaster provider 之外；弹窗失败不关，内联比 toast 直观）
    var restoreError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("发现降级前的数据备份") },
        text = {
            Text(
                "检测到 $backupDate 备份的数据（schema v${entry.marker.schemaVersion}）。\n\n" +
                    "恢复将覆盖当前全部数据，重启后生效。" +
                    (restoreError?.let { "\n\n失败：$it" } ?: ""),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    startupApp.restoreDowngradeEntry(entry).onFailure { e ->
                        restoreError = e.message ?: "未知错误"
                    }
                }
            }) { Text("恢复并重启") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { startupApp.dismissDowngradeEntry(entry) }
            }) { Text("不再提示") }
        },
    )
}

/** Failed 阻塞屏：applyIfPending 可重试；Koin/Room 失败只能重启进程。 */
@Composable
internal fun StartupFailedScreen(state: StartupState.Failed, onRetry: () -> Unit) {
    GateTheme {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text(
                    text = if (state.canRetry) {
                        stringResource(R.string.startup_restore_failed, state.message)
                    } else {
                        stringResource(R.string.startup_restore_failed_exit, state.message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.canRetry) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.startup_restore_retry))
                    }
                }
            }
        }
    }
}

/** Koin 之外的兜底主题：仅随系统深浅色取默认配色，不解析任何 Koin 依赖。 */
@Composable
internal fun GateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
