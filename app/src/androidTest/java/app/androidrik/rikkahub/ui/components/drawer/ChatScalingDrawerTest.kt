package app.androidrik.rikkahub.ui.components.drawer

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real Compose input/animation tests; no app settings, network, or user data are touched. */
@RunWith(AndroidJUnit4::class)
class ChatScalingDrawerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Explicit target-package Intent avoids AndroidX inferring the .test package for the host.
    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    private class Fixture(initialOpen: Boolean = false) {
        val open = mutableStateOf(initialOpen)
        val requests = mutableListOf<Boolean>()
        var homeClicks = 0
        var drawerClicks = 0
        var outerBacks = 0
        var childDragEvents = 0
        var childDragDistance = 0f
        var homeCompositions = 0
        var drawerCompositions = 0
    }

    private val dismissMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)

    private fun show(initialOpen: Boolean = false, childDrag: Boolean = false): Fixture {
        val fixture = Fixture(initialOpen)
        rule.setContent {
            MaterialTheme {
                TestDrawer(fixture, childDrag)
            }
        }
        rule.waitForIdle()
        return fixture
    }

    @Composable
    private fun TestDrawer(fixture: Fixture, childDrag: Boolean) {
        BackHandler { fixture.outerBacks++ }
        // Stable slots deliberately isolate animation recompositions from parent state changes.
        val drawer: @Composable () -> Unit = remember(fixture) {
            {
                SideEffect { fixture.drawerCompositions++ }
                Box(Modifier.fillMaxSize().testTag(DRAWER), contentAlignment = Alignment.Center) {
                    Button(onClick = { fixture.drawerClicks++ }) { Text(DRAWER_BUTTON) }
                }
            }
        }
        val home: @Composable () -> Unit = remember(fixture, childDrag) {
            {
                SideEffect { fixture.homeCompositions++ }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag(HOME)
                        .then(
                            if (childDrag) Modifier.pointerInput(Unit) {
                                detectHorizontalDragGestures { change, amount ->
                                    change.consume()
                                    fixture.childDragEvents++
                                    fixture.childDragDistance += amount
                                }
                            } else Modifier
                        )
                        .clickable { fixture.homeClicks++ },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(HOME_TEXT)
                }
            }
        }
        ChatScalingDrawer(
            open = fixture.open.value,
            onOpenChange = {
                fixture.requests += it
                fixture.open.value = it
            },
            drawerContent = drawer,
            content = home,
            modifier = Modifier.testTag(ROOT),
        )
    }

    private fun assertClosedAndHomeInteractive(fixture: Fixture) {
        rule.waitForIdle()
        rule.onNode(dismissMatcher).assertDoesNotExist()
        rule.onNodeWithText(HOME_TEXT).assertIsDisplayed()
        val before = fixture.homeClicks
        val rootLeft = rule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot.left
        val homeLeft = rule.onNodeWithTag(HOME).fetchSemanticsNode().boundsInRoot.left
        assertEquals("Home transform must settle fully closed", rootLeft, homeLeft, 1f)
        rule.onNodeWithTag(HOME).performTouchInput { click(center) }
        rule.runOnIdle {
            assertFalse(fixture.open.value)
            assertEquals(before + 1, fixture.homeClicks)
        }
    }

    private fun assertOpen(fixture: Fixture) {
        rule.waitForIdle()
        rule.onNode(dismissMatcher).assertExists()
        rule.onNodeWithText(DRAWER_BUTTON).assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.open.value) }
    }

    @Test
    fun controlledClosedDrawerRendersInteractiveMainContent() {
        val fixture = show()
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle { assertTrue(fixture.requests.isEmpty()) }
    }

    @Test
    fun externallyOpeningDrawerExposesDrawerContent() {
        val fixture = show()
        rule.runOnIdle { fixture.open.value = true }
        assertOpen(fixture)
        rule.runOnIdle { assertTrue(fixture.requests.isEmpty()) }
    }

    @Test
    fun externallyClosingDrawerRestoresMainContent() {
        val fixture = show(initialOpen = true)
        assertOpen(fixture)
        rule.runOnIdle { fixture.open.value = false }
        assertClosedAndHomeInteractive(fixture)
    }

    @Test
    fun swipeStartingOneDpInsideEdgeOpensDrawer() {
        val fixture = show()
        rule.onNodeWithTag(ROOT).performTouchInput {
            swipe(Offset(1.dp.toPx(), height * 0.5f), Offset(width * 0.85f, height * 0.5f), 500)
        }
        assertOpen(fixture)
        rule.runOnIdle { assertTrue(fixture.requests.contains(true)) }
    }

    @Test
    fun horizontalSwipeStartingOutsideEdgeMustNotOpenDrawer() {
        val fixture = show()
        rule.onNodeWithTag(ROOT).performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.95f, height * 0.5f), 500)
        }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle { assertTrue(fixture.requests.isEmpty()) }
    }

    @Test
    fun rejectedOutsideEdgeSwipeReachesChildHorizontalDragDetector() {
        val fixture = show(childDrag = true)
        rule.onNodeWithTag(ROOT).performTouchInput {
            swipe(Offset(width * 0.45f, height * 0.5f), Offset(width * 0.9f, height * 0.5f), 500)
        }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle {
            assertTrue("Child must receive real drag events", fixture.childDragEvents > 0)
            assertTrue("Child must receive positive horizontal movement", fixture.childDragDistance > 0f)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun shortEdgeDragSettlesFullyClosedEvenWhenControlledStateDoesNotChange() {
        val fixture = show()
        rule.onNodeWithTag(ROOT).performTouchInput {
            val start = 1.dp.toPx()
            val distance = minOf(32.dp.toPx(), width * 0.06f)
            swipe(Offset(start, height * 0.5f), Offset(start + distance, height * 0.5f), 500)
        }
        // Checking only open==false would miss an Animatable left stuck partially open.
        // Home semantics and an actual click prove the blocking layer has also disappeared.
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle { assertEquals("The short drag must reach settling", listOf(false), fixture.requests) }
    }

    @Test
    fun draggingLeftFromOpenDrawerClosesIt() {
        val fixture = show(initialOpen = true)
        rule.onNodeWithTag(ROOT).performTouchInput {
            swipe(Offset(width * 0.9f, height * 0.5f), Offset(1.dp.toPx(), height * 0.5f), 500)
        }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle { assertTrue(fixture.requests.contains(false)) }
    }

    @Test
    fun scrimTapClosesWithoutDeliveringClickToHome() {
        val fixture = show(initialOpen = true)
        rule.onNodeWithTag(ROOT).performTouchInput {
            // Far-right center is inside the translated home panel, not the drawer panel.
            click(Offset(width - 8.dp.toPx(), height * 0.5f))
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, fixture.homeClicks) }
        assertClosedAndHomeInteractive(fixture)
    }

    @Test
    fun drawerButtonReceivesClickWhileOpen() {
        val fixture = show(initialOpen = true)
        rule.onNodeWithText(DRAWER_BUTTON).performTouchInput { click(center) }
        assertOpen(fixture)
        rule.runOnIdle {
            assertEquals(1, fixture.drawerClicks)
            assertEquals(0, fixture.homeClicks)
        }
    }

    @Test
    fun backDispatcherClosesOnlyWhenOpenAndOtherwiseReachesOuterHandler() {
        val fixture = show()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle {
            assertEquals(1, fixture.outerBacks)
            assertTrue(fixture.requests.isEmpty())
            fixture.open.value = true
        }
        assertOpen(fixture)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle {
            assertEquals("Open drawer must consume back", 1, fixture.outerBacks)
            assertEquals(listOf(false), fixture.requests)
        }
    }

    @Test
    fun accessibilityDismissActionClosesDrawer() {
        val fixture = show(initialOpen = true)
        rule.onNode(dismissMatcher).performSemanticsAction(SemanticsActions.Dismiss) { dismiss ->
            assertTrue(dismiss())
        }
        assertClosedAndHomeInteractive(fixture)
        rule.runOnIdle { assertEquals(listOf(false), fixture.requests) }
    }

    @Test
    fun manuallyAdvancedAnimationMovesDrawerWithoutPerFrameSlotRecomposition() {
        val fixture = show()
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { fixture.open.value = true }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(48)
            rule.waitForIdle()
            val initialRight = rule.onNodeWithTag(DRAWER, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.right
            var homeBefore = 0
            var drawerBefore = 0
            rule.runOnIdle {
                homeBefore = fixture.homeCompositions
                drawerBefore = fixture.drawerCompositions
            }
            repeat(8) { rule.mainClock.advanceTimeByFrame() }
            rule.waitForIdle()
            val laterRight = rule.onNodeWithTag(DRAWER, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.right
            rule.runOnIdle {
                assertTrue("Clock must actually advance the drawer transform", laterRight > initialRight)
                assertEquals("Main slot must not recompose each animation frame", homeBefore, fixture.homeCompositions)
                assertEquals("Drawer slot must not recompose each animation frame", drawerBefore, fixture.drawerCompositions)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        assertOpen(fixture)
    }

    private companion object {
        const val ROOT = "scaling-drawer-root"
        const val HOME = "scaling-drawer-home"
        const val DRAWER = "scaling-drawer-panel-content"
        const val HOME_TEXT = "Main chat content"
        const val DRAWER_BUTTON = "Drawer test action"
    }
}
