package app.androidrik.rikkahub.ui.floating

import android.app.AppOpsManager
import android.app.Application
import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.perf.PerformanceSampler
import app.androidrik.rikkahub.data.perf.PerformanceState
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.hooks.rememberUserSettingsState
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.system.permission.FxPermission
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "PerfFloatingWindows"
private const val HUD_TAG = "perf_hud"
private const val PREFS_NAME = "perf_floating_window"
private const val KEY_ENABLED = "enabled"

/**
 * 系统级性能浮层（TYPE_APPLICATION_OVERLAY，复用聊天浮窗已有的 SYSTEM_ALERT_WINDOW 权限）。
 *
 * 采样进行中且用户显式开启时，在**任何 App 之上**显示单核/进程/GPU 实时值与峰值，支持复制报告、
 * 原地停止。窗口由 FloatingX 的 systemHost 管理（与聊天悬浮窗同一套设施），进程存活期间一直显示。
 *
 * 开关状态持久化到 SharedPreferences；没有悬浮窗权限时窗口不创建，由 Perf 页引导授权。
 */
class PerfFloatingWindows(
    private val context: Application,
    private val appScope: AppScope,
    private val sampler: PerformanceSampler,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 窗口是否真的挂在系统层（开启 + 有权限 + 采样中）。In-App 浮层据此让位，避免双份显示。 */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var control: FxControl? = null

    init {
        appScope.launch {
            combine(
                sampler.state,
                _enabled,
                overlayPermission(),
            ) { state, enabled, granted ->
                state.running && enabled && granted
            }.distinctUntilChanged().collect { show ->
                render(show)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun hasPermission(): Boolean = FxPermission.isGranted(context)

    private fun overlayPermission(): Flow<Boolean> = callbackFlow {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val listener = AppOpsManager.OnOpChangedListener { op, packageName ->
            if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == context.packageName) {
                trySend(FxPermission.isGranted(context))
            }
        }
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, listener)
        trySend(FxPermission.isGranted(context))
        awaitClose { appOps.stopWatchingMode(listener) }
    }

    /** 所有 FloatingX 操作都在主线程 AppScope 上执行（与 ChatFloatingWindows 一致）。 */
    private fun render(show: Boolean) {
        if (!show) {
            runCatching { control?.cancel() }
            control = null
            _active.value = false
            return
        }
        if (!FxPermission.isGranted(context)) {
            _active.value = false
            return
        }
        val existing = control
        if (existing != null) {
            existing.show()
            _active.value = true
            return
        }
        try {
            control = FloatingX.install(HUD_TAG) {
                compose { PerfHudContent(sampler = sampler) }
                anchor(FxGravity.TOP_START, dx = dp(8f), dy = dp(56f))
                animation(FxAnimations.fade())
                systemHost(context) { permission(FxPermissionStrategy.skip()) }
            }.also {
                // install 只建窗口对象，不 show 就不会真正 addView（聊天浮窗也是 install 后再 show）。
                it.show()
                _active.value = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 权限可能在 AppOps 检查与 addView 之间被撤销。
            Log.w(TAG, "Unable to show perf HUD", e)
            runCatching { control?.cancel() }
            control = null
            _active.value = false
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}

/** 浮层内容自给自足：独立 ComposeView，不依赖 Activity 的 CompositionLocal（toast 用内联文字代替）。 */
@Composable
private fun PerfHudContent(sampler: PerformanceSampler) {
    val state by sampler.state.collectAsState()
    val settings by rememberUserSettingsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }
    RikkahubTheme {
        CompositionLocalProvider(LocalSettings provides settings) {
            PerfHudCard(
                state = state,
                copied = copied,
                onCopy = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("RikkaHub performance report", sampler.reportText()))
                        )
                        copied = true
                    }
                },
                onStop = sampler::stop,
            )
        }
    }
}

@Composable
private fun PerfHudCard(
    state: PerformanceState,
    copied: Boolean,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.widthIn(max = 380.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
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
                    "单核 " + percent(state.peakSingleThread?.currentPercent ?: 0.0) +
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
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall) }
            IconButton(onClick = onStop) {
                Icon(Lucide.X, contentDescription = "停止采样")
            }
        }
    }
}

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)
