package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.ChatBubbleSurface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatAppearanceRenderingTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun gradientIsDrawnAcrossTheBubble() {
        compose.setContent {
            MaterialTheme {
                ChatBubbleSurface(ChatBubble(material = BubbleMaterial.GRADIENT,
                    gradient = AppearanceGradient(listOf("#FF0000", "#0000FF"), GradientDirection.HORIZONTAL)),
                    defaultColor = Color.Green, modifier = Modifier.size(160.dp, 80.dp).testTag("bubble")) {}
            }
        }
        val pixels = compose.onNodeWithTag("bubble").captureToImage().toPixelMap()
        val left = pixels[pixels.width / 4, pixels.height / 2]
        val right = pixels[pixels.width * 3 / 4, pixels.height / 2]
        assertTrue("left side must be red", left.red > left.blue)
        assertTrue("right side must be blue", right.blue > right.red)
    }

    @Test fun hiddenBubblePreservesTheBackground() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.White)) {
                    ChatBubbleSurface(ChatBubble(visible = false, material = BubbleMaterial.GRADIENT),
                        defaultColor = Color.Red, modifier = Modifier.size(160.dp, 80.dp).testTag("bubble")) {}
                }
            }
        }
        val p = compose.onNodeWithTag("bubble").captureToImage().toPixelMap()
        val middle = p[p.width / 2, p.height / 2]
        assertTrue(middle.alpha < 0.05f || (middle.red > 0.95f && middle.green > 0.95f && middle.blue > 0.95f))
    }

    @Test fun patternAndNoiseProduceVisibleStableTexture() {
        compose.setContent {
            MaterialTheme {
                Column {
                    listOf(BubbleMaterial.PATTERN, BubbleMaterial.NOISE).forEach { material ->
                        ChatBubbleSurface(ChatBubble(material = material, colorHex = "#888888",
                            pattern = BubblePattern(colorHex = "#000000", opacity = 0.7f), noise = BubbleNoise(42, 0.7f)),
                            defaultColor = Color.Gray, modifier = Modifier.size(160.dp, 80.dp).testTag(material.name)) {}
                    }
                }
            }
        }
        listOf(BubbleMaterial.PATTERN, BubbleMaterial.NOISE).forEach { material ->
            val node = compose.onNodeWithTag(material.name)
            val first = node.captureToImage().toPixelMap()
            val second = node.captureToImage().toPixelMap()
            val colors = mutableSetOf<Int>()
            for (x in first.width / 4 until first.width * 3 / 4) {
                for (y in first.height / 4 until first.height * 3 / 4) {
                    colors += (first[x, y].red * 255).toInt()
                    assertEquals("texture changes without a config change", first[x, y], second[x, y])
                }
            }
            assertTrue("$material has no texture", colors.size > 2)
        }
    }
}
