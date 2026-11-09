package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSkinControlCoverageTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun filledAndOutlinedValueOverloadsKeepDefaultMaterialPixels() {
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                Row {
                    Column(Modifier.width(160.dp).testTag("material")) {
                        OutlinedTextField("Value", {}, readOnly = true, label = { Text("Label") })
                        TextField("Value", {}, readOnly = true)
                        TextField(TextFieldValue("Value"), {}, readOnly = true, label = { Text("Label") })
                        FilledTonalButton(onClick = {}) { Text("Tonal") }
                    }
                    Column(Modifier.width(160.dp).testTag("skin")) {
                        AppOutlinedTextField("Value", {}, readOnly = true, label = { Text("Label") })
                        AppTextField("Value", {}, readOnly = true)
                        AppTextField(TextFieldValue("Value"), {}, readOnly = true, label = { Text("Label") })
                        AppFilledTonalButton(onClick = {}) { Text("Tonal") }
                    }
                }
            }
        }
        assertSamePixels("material", "skin")
    }

    @Test fun stateFieldsKeepAttachedAndAboveLabelLayoutAndPixels() {
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                Row {
                    Column(Modifier.width(160.dp).testTag("material")) {
                        OutlinedTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") })
                        TextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") })
                        TextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") },
                            labelPosition = TextFieldLabelPosition.Above())
                        OutlinedTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") },
                            labelPosition = TextFieldLabelPosition.Inside())
                    }
                    Column(Modifier.width(160.dp).testTag("skin")) {
                        AppOutlinedTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") })
                        AppTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") })
                        AppTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") },
                            labelPosition = TextFieldLabelPosition.Above())
                        AppOutlinedTextField(rememberTextFieldState("Value"), readOnly = true, label = { Text("Label") },
                            labelPosition = TextFieldLabelPosition.Inside())
                    }
                }
            }
        }
        assertSamePixels("material", "skin")
    }

    @Test fun stateFieldKeepsFocusTransformationsAndImeCallbackAcrossSkinSwitch() {
        val state = TextFieldState()
        var skin by mutableStateOf(AppSkin())
        var done = 0
        var layouts = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                AppTextField(state, Modifier.testTag("input"),
                    inputTransformation = InputTransformation { if (length > 12) revertAllChanges() },
                    outputTransformation = OutputTransformation { insert(0, ">") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onKeyboardAction = { done++ }, lineLimits = TextFieldLineLimits.SingleLine,
                    onTextLayout = { layouts++ }, scrollState = rememberScrollState())
            }
        }
        compose.onNodeWithTag("input").performTextInput("draft")
        compose.runOnIdle { skin = AppSkin(shapes = AppSkinShapes(inputDp = 24f),
            typography = AppSkinTypography(sizeRatio = 1.2f)) }
        compose.onNodeWithTag("input").assertIsFocused().assertTextContains(">draft")
        compose.onNodeWithTag("input").performTextInput("012345678901234")
        compose.onNodeWithTag("input").performImeAction()
        compose.runOnIdle { assertEquals("draft", state.text.toString()); assertEquals(1, done); assertTrue(layouts > 0) }
    }

    @Test fun textFieldValueSelectionAndCompositionAreNotRewrittenBySkin() {
        var skin by mutableStateOf(AppSkin())
        var value by mutableStateOf(TextFieldValue("draft", TextRange(2), TextRange(1, 3)))
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                AppOutlinedTextField(value, { value = it }, Modifier.testTag("input"))
            }
        }
        compose.runOnIdle { skin = skin.copy(shapes = AppSkinShapes(inputDp = 0f)) }
        compose.runOnIdle {
            assertEquals("draft", value.text)
            assertEquals(TextRange(2), value.selection)
            assertEquals(TextRange(1, 3), value.composition)
        }
        compose.onNodeWithTag("input").performTextInput("X")
        compose.onNodeWithTag("input").assertIsFocused()
        compose.runOnIdle { assertTrue(value.text.contains("X")) }
    }

    @Test fun tonalButtonsHonorSkinCornersExplicitShapesAndDisabledSemantics() {
        var clicks = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(AppSkin(shapes = AppSkinShapes(buttonDp = 24f))), lightColorScheme()) {
                Column {
                    Box(Modifier.background(Color.White).testTag("round")) {
                        AppFilledTonalButton(onClick = { clicks++ }, modifier = Modifier.size(120.dp, 48.dp).testTag("click"),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.Blue)) { Text("Go") }
                    }
                    Box(Modifier.background(Color.White).testTag("square")) {
                        AppFilledTonalButton(onClick = {}, enabled = false,
                            modifier = Modifier.size(120.dp, 48.dp).testTag("disabled"),
                            shape = androidx.compose.ui.graphics.RectangleShape,
                            colors = ButtonDefaults.filledTonalButtonColors(disabledContainerColor = Color.Blue)) { Text("Off") }
                    }
                }
            }
        }
        assertTrue(compose.onNodeWithTag("round").captureToImage().toPixelMap()[2, 2].red > .9f)
        assertTrue(compose.onNodeWithTag("square").captureToImage().toPixelMap()[2, 2].blue > .9f)
        compose.onNodeWithTag("click").performClick()
        compose.onNodeWithTag("disabled").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    private fun assertSamePixels(a: String, b: String) {
        val first = compose.onNodeWithTag(a).captureToImage().toPixelMap()
        val second = compose.onNodeWithTag(b).captureToImage().toPixelMap()
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        for (x in 1 until first.width step 7) for (y in 1 until first.height step 7) {
            val left = first[x, y]; val right = second[x, y]
            assertEquals("red at $x,$y", left.red, right.red, .02f)
            assertEquals("green at $x,$y", left.green, right.green, .02f)
            assertEquals("blue at $x,$y", left.blue, right.blue, .02f)
        }
    }
}
