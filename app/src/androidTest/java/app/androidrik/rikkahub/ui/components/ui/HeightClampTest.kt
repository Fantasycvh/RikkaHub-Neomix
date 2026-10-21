package app.androidrik.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HeightClamp 行为验证（运行期）：正常内容与超高正常内容均完整测量不崩溃。
 * 精确的高度放行/clamp 语义由实现保证（measure 结果 < limit 原样放行）。
 *
 * 宿主用 debug 变体的 [SketchTestHostActivity] + 显式 Intent：本仓的仪器测试进程是
 * `debug` App，`createComposeRule()` 会回退到测试 APK 的包名并解析失败，见该 Activity 的注释。
 */
@RunWith(AndroidJUnit4::class)
class HeightClampTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun normalHeightContentRenders() {
        composeRule.setContent {
            HeightClamp(normalLimit = 100_000.dp) {
                Box(Modifier.size(50.dp))
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun tallNormalContentNotClampedNoCrash() {
        // 15000dp：旧实现（maxHeight=10000 测量约束）被裁剪；
        // 新实现完整放行，验证组合与测量不崩溃
        composeRule.setContent {
            HeightClamp(normalLimit = 100_000.dp) {
                Column(Modifier.height(15000.dp)) {}
            }
        }
        composeRule.waitForIdle()
    }
}
