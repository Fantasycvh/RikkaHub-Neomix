package app.androidrik.rikkahub.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.ui.pages.translator.ProcessTextTranslatorWindow
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import kotlin.math.roundToInt

/**
 * 跨应用划词翻译入口：接收系统的 ACTION_PROCESS_TEXT，以对话框样式窗口展示。
 * 用任务栈 Activity 而非悬浮窗/无障碍实现「跨应用划词」——不需要 draw-over-other-apps
 * 权限，方案来源与取舍记录见 docs/references/other projects/miffan.md。
 *
 * PROCESS_TEXT filter 原挂在 RouteActivity（划词会打开整个 App），迁移到本 Activity 后
 * RouteActivity 只保留 ACTION_SEND 入口。
 */
class ProcessTextTranslatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // startKoin 已推迟到 boot() 编排（B 档，同 SafeModeActivity）；本页 koinViewModel
        // 依赖 Koin，幂等补齐，否则冷启动直达弹窗时会崩
        (applicationContext as? RikkaHubApp)?.ensureKoinStarted()

        val selectedText = intent
            .takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (selectedText.isBlank()) {
            finish()
            return
        }

        setFinishOnTouchOutside(true)

        setContent {
            RikkahubTheme {
                ProcessTextTranslatorWindow(
                    selectedText = selectedText,
                    onClose = ::finish,
                )
            }
        }

        val density = resources.displayMetrics.density
        val compactWidthDp = minOf(resources.configuration.screenWidthDp * 0.92f, 600f)
        window.setLayout(
            (compactWidthDp * density).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
    }
}
