package app.androidrik.rikkahub.ui.pages.debug

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.perf.PerformanceSampler
import app.androidrik.rikkahub.ui.floating.PerfFloatingWindows
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale

/**
 * 采样进行中的全局浮层（挂在 RouteActivity 内容根，覆盖所有 App 页面）。
 *
 * 目的：在「其他界面」复现卡顿/耗时操作时（备份、FTS 重建、聊天流式输出……），
 * 不用停在 Debug Mode 的 Perf 页也能看到实时单核峰值/进程负载/GPU 负载；
 * 采样由进程级单例 [PerformanceSampler] 承担，切页不会中断，点 × 可原地停止，
 * 回到 Debug → Perf 还能看到完整统计并复制报告。
 */
@Composable
fun PerfOverlay(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    sampler: PerformanceSampler = koinInject(),
) {
    val state by sampler.state.collectAsStateWithLifecycle()
    // 系统级浮层已挂在屏幕上时让位，避免 App 内出现两份。
    val systemOverlayActive by koinInject<PerfFloatingWindows>().active.collectAsStateWithLifecycle()
    if (!visible || systemOverlayActive || !state.running) return
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp, end = 8.dp)
            .widthIn(max = 380.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "当前 单核 " + percent(state.peakSingleThread?.currentPercent ?: 0.0) +
                        " · 进程 " + percent(state.processPercent) +
                        " · GPU " + (state.gpuPercent?.let(::percent) ?: "N/A"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                )
                Text(
                    "峰值 单核 " + (state.peakSingleThread?.let { percent(it.peakPercent) } ?: "—") +
                        " · 进程 " + percent(state.peakProcessPercent) +
                        " · GPU " + (state.peakGpuPercent?.let(::percent) ?: "N/A"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("RikkaHub performance report", sampler.reportText()))
                        )
                        toaster.show("报告已复制", type = ToastType.Success)
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("复制", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = sampler::stop) {
                Icon(Lucide.X, contentDescription = "停止采样")
            }
        }
    }
}

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
