package app.androidrik.rikkahub.ui.components.haze

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 液态玻璃门控层冒烟（bilipai.md 落地清单 #5）：玻璃开关开 → 兄弟层挂上并可组合；
 * 关 → 层消失。消费点复刻 ChatList 的兄弟层结构（背景 source + 固定尺寸兄弟节点），
 * SDK 不满足时门控层自动退化为无效果（recoverableHazeBlur/hazeSourceCompat 内部处理），
 * 低版本设备同样应通过。
 */
@RunWith(AndroidJUnit4::class)
class LiquidGlassSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun sdkGatePolicyTiering() {
        // blur 后端（RenderEffect）≥S(31)；玻璃后端（RuntimeShader）≥T(33)；回前台重建 ≥36
        assertFalse(shouldAllowRenderEffectBackedHazeEffect(30))
        assertTrue(shouldAllowRenderEffectBackedHazeEffect(31))
        assertFalse(shouldAllowRuntimeShaderBackedGlassEffect(32))
        assertTrue(shouldAllowRuntimeShaderBackedGlassEffect(33))
        assertFalse(shouldRecreateHazeStateOnForeground(35))
        assertTrue(shouldRecreateHazeStateOnForeground(36))
        // 前后台 gate：用户开 + 前台才生效
        assertTrue(shouldEnableRecoverableBlur(userEnabled = true, isAppInBackground = false))
        assertFalse(shouldEnableRecoverableBlur(userEnabled = true, isAppInBackground = true))
        assertFalse(shouldEnableRecoverableBlur(userEnabled = false, isAppInBackground = false))
    }

    @Test
    fun glassToggleMountsAndClearsSiblingLayer() {
        var glassEnabled by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                GlassSmokeHost(glassEnabled = glassEnabled)
            }
        }
        rule.onNodeWithTag(LiquidGlassSmokeHostTag).assertDoesNotExist()

        rule.runOnIdle { glassEnabled = true }
        rule.onNodeWithTag(LiquidGlassSmokeHostTag).assertExists()

        rule.runOnIdle { glassEnabled = false }
        rule.onNodeWithTag(LiquidGlassSmokeHostTag).assertDoesNotExist()
    }
}

private const val LiquidGlassSmokeHostTag = "liquid_glass_smoke_layer"

/** ChatList 液态玻璃兄弟层的最小复刻：背景 source + 固定尺寸兄弟 blur 节点。 */
@Composable
private fun GlassSmokeHost(glassEnabled: Boolean) {
    val hazeState = rememberRecoverableHazeState()
    val glassHazeState = rememberRecoverableHazeState()
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .hazeSourceCompat(hazeState)
                .hazeSourceCompat(glassHazeState)
        )
        if (glassEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(LiquidGlassSmokeHostTag)
                    .recoverableHazeBlur(
                        state = glassHazeState,
                        style = liquidGlassBlurStyle(LiquidGlassReadabilityMode.AUTO),
                    )
            )
        }
    }
}
