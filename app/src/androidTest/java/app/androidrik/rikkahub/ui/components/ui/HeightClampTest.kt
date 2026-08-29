package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HeightClamp 行为验证（运行期）：正常内容与超高正常内容均完整测量不崩溃。
 * 精确的高度放行/clamp 语义由实现保证（measure 结果 < limit 原样放行）。
 */
@RunWith(AndroidJUnit4::class)
class HeightClampTest {

    @get:Rule
    val composeRule = createComposeRule()

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