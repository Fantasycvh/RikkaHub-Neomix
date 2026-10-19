package app.androidrik.rikkahub

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * debug 变体里的空 Activity，只作为 Compose instrumented 测试
 * （`AndroidComposeTestRule` + 显式 Intent）的宿主。
 *
 * 必须放在 app 的 debug source set：`ActivityScenario` 通过
 * `Instrumentation.startActivitySync` 启动 Activity，只能启动与 instrumentation 同进程的、
 * 且声明在被测应用 manifest 里的 Activity；androidTest APK 里的 Activity 跑在另一个进程。
 * release 变体不包含本类与 `app/src/debug/AndroidManifest.xml` 里的声明。
 */
class SketchTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 测试期间屏幕不能息屏/锁屏：息屏后注入的触摸和 Ink 的异步提交都会丢失。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
    }
}
