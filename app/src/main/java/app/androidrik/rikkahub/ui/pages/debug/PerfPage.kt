package app.androidrik.rikkahub.ui.pages.debug

import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.perf.PerformanceState
import app.androidrik.rikkahub.data.perf.ThreadLoad
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.floating.PerfFloatingWindows
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.Locale

/**
 * 性能统计页（Debug Mode 第五个 tab）。
 *
 * 借鉴 CapFrameX 的「采集 → 聚合统计」工作流（https://github.com/CXWorld/CapFrameX），
 * 但数据源换成 Android `/proc` + GPU sysfs（详见 `data/perf/PerformanceMetrics.kt`）：
 * - 单线程峰值（单核负载）：定位是不是某个线程吃满了一个核；
 * - 进程/系统多核总负载：判断是单线程瓶颈还是并行度不足；
 * - GPU 负载：判断 GPU 是否有参与（N/A = 当前系统读不到）。
 *
 * 采样由 App 进程级单例承担：点「开始」后离开本页/切到其他界面（备份、聊天、设置……）
 * 不会中断，右上角浮层实时显示单核/进程/GPU 负载并可原地停止；回到本页看完整统计、复制报告。
 */
@Composable
fun PerfPage(vm: PerfVM = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val perfFloating = koinInject<PerfFloatingWindows>()
    val systemOverlayEnabled by perfFloating.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var canDrawOverlays by remember { mutableStateOf(perfFloating.hasPermission()) }
    LifecycleResumeEffect(Unit) {
        canDrawOverlays = perfFloating.hasPermission()
        onPauseOrDispose {}
    }
    val toaster = LocalToaster.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "采样 CPU/线程/GPU，定位耗时操作卡在哪个线程、是否吃满单核、GPU 是否参与。" +
                "开始后离开本页仍会采样（右上角浮层可看实时值/停止），回来可看完整统计。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::start, enabled = !state.running) { Text("开始") }
            Button(onClick = vm::stop, enabled = state.running) { Text("停止") }
            OutlinedButton(onClick = vm::reset, enabled = !state.running && state.samples > 0) { Text("清零") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("RikkaHub performance report", vm.reportText())))
                        toaster.show("报告已复制", type = ToastType.Success)
                    }
                },
                enabled = state.samples > 0,
            ) { Text("复制报告") }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("系统浮层（其他 App 之上）", style = MaterialTheme.typography.labelMedium)
                Text(
                    if (canDrawOverlays) {
                        "开始采样后显示在屏幕顶部；切到别的 App 也能看，可复制/停止。"
                    } else {
                        "未授权：打开开关会跳系统设置授予「显示在其他应用上层」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = systemOverlayEnabled && canDrawOverlays,
                onCheckedChange = { on ->
                    if (on && !canDrawOverlays) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                "package:${context.packageName}".toUri(),
                            )
                        )
                    } else {
                        perfFloating.setEnabled(on)
                    }
                },
            )
        }

        if (state.samples == 0) {
            Text(
                if (state.running) "采样中…（每 500 ms 一次）" else "尚未采样。开始后复现卡顿/耗时操作，再停止查看统计。",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "采样 ${state.samples} 次 / ${formatSeconds(state.elapsedMs)}（每 500 ms 一次）",
                    style = MaterialTheme.typography.labelMedium,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val peakThread = state.peakSingleThread
                MetricRow(
                    label = "单线程峰值(单核)",
                    value = peakThread?.let { "${percent(it.peakPercent)}  ${it.name} (tid=${it.tid})" } ?: "—",
                )
                MetricRow("进程多核总负载", percent(state.peakProcessPercent) + " 峰值 / 均值 " + percent(state.avgProcessPercent))
                MetricRow(
                    "系统多核总负载",
                    if (state.systemCpuAvailable) {
                        percent(state.peakSystemPercent) + " 峰值 / 均值 " + percent(state.avgSystemPercent)
                    } else {
                        "N/A（/proc/stat 被系统限制）"
                    },
                )
                MetricRow(
                    "GPU 峰值",
                    state.peakGpuPercent?.let { percent(it) + " 峰值 / 均值 " + percent(state.avgGpuPercent ?: 0.0) }
                        ?: if (vm.gpuSourceLabel != null) "N/A（数据源存在，采样期间无增量，通常 GPU 空闲）"
                        else "N/A（无可读 sysfs 入口）",
                )
                MetricRow("数据源", vm.gpuSourceLabel ?: "无 GPU 入口")
                if (state.corePeakPercents.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("每核峰值", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.corePeakPercents.mapIndexed { index, value -> "cpu$index ${percent(value)}" }
                            .joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetbrainsMono,
                    )
                }
            }
        }

        if (state.running) {
            Text(
                "当前: 进程 " + percent(state.processPercent) +
                    (if (state.systemCpuAvailable) " / 系统 " + percent(state.systemPercent) else "") +
                    " / GPU " + (state.gpuPercent?.let(::percent) ?: "N/A"),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Text("线程（按峰值排序，前 ${MIN_THREAD_ROWS}）", style = MaterialTheme.typography.labelMedium)
        if (state.threads.isEmpty()) {
            Text("(无数据)", style = MaterialTheme.typography.bodySmall)
        } else {
            state.threads.take(MIN_THREAD_ROWS).forEach { thread ->
                ThreadRow(thread)
            }
            if (state.threads.size > MIN_THREAD_ROWS) {
                Text(
                    "还有 ${state.threads.size - MIN_THREAD_ROWS} 个线程，完整报告见「复制报告」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
        Text(
            "说明：单核占满 = 100%；进程多核总负载可超过 100%（350% = 3.5 核）。" +
                "系统/逐核负载依赖 /proc/stat，Android 14+ 部分机型（含本机）禁止 App 读取，此时显示 N/A；" +
                "GPU 读取受机型/SELinux 限制；数据本身不含 App 自检开销校正。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = JetbrainsMono,
        )
    }
}

@Composable
private fun ThreadRow(thread: ThreadLoad) {
    Text(
        "${percent(thread.peakPercent).padStart(6)} 峰值 / ${percent(thread.currentPercent).padStart(6)} 当前  " +
            "${thread.name} (tid=${thread.tid})",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = JetbrainsMono,
    )
}

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

private fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

private const val MIN_THREAD_ROWS = 12
