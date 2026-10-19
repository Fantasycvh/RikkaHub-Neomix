package app.androidrik.rikkahub.ui.components.sketch

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * [SketchDialog] 的端到端冒烟：在画纸上画线、撤销/重做、确认后拿到真正渲染过的图片；
 * 以及底图读取失败时保持不可画、不可确认。
 *
 * 只操作测试自己打开的对话框：不读用户图片、不联网、不留文件。宿主是 debug 变体的
 * [SketchTestHostActivity]（与 instrumentation 同进程）。
 * 注意 Ink 的落笔层在抬手后异步提交 Stroke、确认后的导出也在后台线程，所以都要 waitUntil。
 *
 * 这里用显式 Intent 构造 [ActivityScenarioRule]：默认的 class-launch 在 AndroidX 新 invoker 里
 * 会推断到测试 APK 包（`.test/...`）导致 `Unable to resolve activity`，显式 Intent 绑定
 * targetContext 的包后就不再走那条推断。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SketchDialogInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val composeRule: AndroidComposeTestRule<ActivityScenarioRule<SketchTestHostActivity>, SketchTestHostActivity> =
        AndroidComposeTestRule(
            ActivityScenarioRule(Intent(context, SketchTestHostActivity::class.java)),
        ) { rule ->
            var activity: SketchTestHostActivity? = null
            rule.scenario.onActivity { activity = it }
            requireNotNull(activity) { "SketchTestHostActivity was not launched" }
        }

    @Test
    fun inkStroke_canBeUndoneRedoneAndConfirmedIntoAnImage() {
        // 先把宿主窗口点亮并拿到焦点（此时 Dialog 还没打开），避免第一次注入的触摸被系统丢掉。
        waitForHostWindowFocus()

        val confirmed = AtomicReference<SketchResult?>(null)
        composeRule.setContent {
            SketchDialog(onDismiss = {}, onConfirm = { confirmed.set(it) })
        }
        waitForDialog()

        val ok = context.getString(android.R.string.ok)
        val undo = context.getString(R.string.sketch_dialog_undo)
        val redo = context.getString(R.string.sketch_dialog_redo)
        val clear = context.getString(R.string.sketch_dialog_clear)

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(ok).assertIsNotEnabled()

        // 在画纸中间画一条竖线。Ink 在抬手后**异步**提交 Stroke，而且忙/抖动时一次手势可能被 Ink
        // 拆成多笔；所以这里只用“至少有一笔、确认可用”作为落笔完成的同步点，不假设“撤销一次就清空”。
        composeRule.onNode(isDialog()).performTouchInput {
            swipe(
                start = Offset(center.x, center.y - height * 0.1f),
                end = Offset(center.x, center.y + height * 0.1f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        waitForConfirmEnabledState(ok, enabled = true)
        waitForEnabled(undo, enabled = true)
        waitForEnabled(clear, enabled = true)

        // 撤销/重做用一个**确定的 Clear 步骤**验证：清空后 isBlank 与笔数无关，结果可预期。
        clickButton(clear)
        waitForConfirmEnabledState(ok, enabled = false)
        waitForEnabled(redo, enabled = false)

        // 撤销清空：笔画回来，确认恢复可用，且重做变可用（多个状态一起证明点击真的生效）。
        clickButton(undo)
        waitForEnabled(redo, enabled = true)
        waitForConfirmEnabledState(ok, enabled = true)

        // 重做清空：再次清空，确认禁用、重做栈清空。
        waitForEnabled(redo, enabled = true)
        clickButton(redo)
        waitForConfirmEnabledState(ok, enabled = false)
        waitForEnabled(redo, enabled = false)

        // 再撤销清空，恢复笔画并确认导出（导出的是真实绘制/清空/撤销之后的状态）。
        clickButton(undo)
        waitForConfirmEnabledState(ok, enabled = true)

        clickButton(ok)
        composeRule.waitUntil(CONFIRM_TIMEOUT_MS) { confirmed.get() != null }

        val result = requireNotNull(confirmed.get())
        assertEquals("png", result.extension)
        assertEquals("image/png", result.mimeType)
        // 空白画纸导出的长边固定 1536。
        assertEquals(1536, maxOf(result.bitmap.width, result.bitmap.height))
        assertTrue("confirmed image must contain the drawn ink", containsInk(result.bitmap))
    }

    private fun waitForConfirmEnabledState(ok: String, enabled: Boolean) {
        composeRule.waitUntil(CONFIRM_TIMEOUT_MS) {
            runCatching {
                if (enabled) {
                    composeRule.onNodeWithContentDescription(ok).assertIsEnabled()
                } else {
                    composeRule.onNodeWithContentDescription(ok).assertIsNotEnabled()
                }
            }.isSuccess
        }
    }

    // 有界等待某个按钮可用/禁用（等重组与异步状态同步完成），不做重复点击。
    private fun waitForEnabled(description: String, enabled: Boolean) {
        composeRule.waitUntil(CONFIRM_TIMEOUT_MS) {
            runCatching {
                val node = composeRule.onNodeWithContentDescription(description)
                if (enabled) node.assertIsEnabled() else node.assertIsNotEnabled()
            }.isSuccess
        }
    }

    /**
     * 用**语义 Click 动作**点击工具栏按钮，而不是 `performClick()`。
     *
     * Compose 的 `performClick()` 实现是 `performTouchInput { click() }`（注入真实触摸事件），
     * 在对话框窗口焦点/输入法/设备忙时注入事件可能被丢弃 → 偶发“点了没反应”。这里直接调用
     * 合并语义节点上的 OnClick 动作，和按钮回调等价且不依赖窗口事件分发；点击后仍等一轮 idle。
     */
    private fun clickButton(description: String) {
        waitForEnabled(description, enabled = true)
        composeRule.onNodeWithContentDescription(description)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    // 宿主窗口真正拿到焦点后再注入触摸；此刻 SketchDialog 还没打开，焦点只可能在宿主窗口上。
    private fun waitForHostWindowFocus() {        composeRule.waitUntil(HOST_FOCUS_TIMEOUT_MS) {
            composeRule.runOnUiThread { composeRule.activity.window.decorView.hasWindowFocus() }
        }
    }

    // 等 SketchDialog 的窗口进入语义树，再做节点查询与触摸注入。
    private fun waitForDialog() {
        composeRule.waitUntil(DIALOG_TIMEOUT_MS) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 负面路径：故意用不存在的 file URI 当底图打开画板，验证读图失败后保持不可画/不可撤销/
     * 不可确认，且遮罩吞掉落笔手势。
     *
     * 注意：这条用例**故意**触发系统 Toast“无法加载图片”（`sketch_dialog_image_load_failed`，
     * SketchDialog.kt 的 `stage=load_background` 失败路径）——这是预期失败提示，不是保存失败；
     * 保存失败是另一条路径（`SketchAttachment` 的 `stage=save_chat_file outcome=failed`）。
     * 不屏蔽 Toast、不删除本用例。
     */
    @Test
    fun unreadableBackground_staysBlockedAndCannotConfirm() {
        waitForHostWindowFocus()

        val missing = File(context.cacheDir, "sketch-missing-${UUID.randomUUID()}.jpg")
        composeRule.setContent {
            SketchDialog(onDismiss = {}, onConfirm = {}, image = Uri.fromFile(missing))
        }
        waitForDialog()
        val ok = context.getString(android.R.string.ok)
        val undo = context.getString(R.string.sketch_dialog_undo)

        // 读图期间会显示不确定进度条；它消失就说明读图已经结束（这里是失败）。
        composeRule.waitUntil(LOAD_TIMEOUT_MS) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().isEmpty()
        }

        // “无法加载图片”Toast 的来源必须是 load_background 失败 marker（不是保存失败 marker）
        assertTrue(
            "missing load_background failure marker",
            Logging.getTextLogs().any {
                it.tag == "SketchImage" && it.message.contains("stage=load_background outcome=failed")
            },
        )

        // 读图失败后：不能画、不能撤销、不能确认，但可以重选图片或关闭。
        composeRule.onNodeWithContentDescription(ok).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(undo).assertIsNotEnabled()

        // 遮罩会吞掉落笔手势：画一笔也不能让确认变可用。
        composeRule.onNode(isDialog()).performTouchInput {
            swipe(
                start = Offset(center.x, center.y - height * 0.1f),
                end = Offset(center.x, center.y + height * 0.1f),
                durationMillis = 300,
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(ok).assertIsNotEnabled()
    }

    // 图片底色是白的，找到任何足够深的像素就说明笔画被渲染进去了。
    private fun containsInk(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { pixel ->
            Color.alpha(pixel) > 200 && Color.red(pixel) < 40 && Color.green(pixel) < 40 && Color.blue(pixel) < 40
        }
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 10_000L
        const val LOAD_TIMEOUT_MS = 10_000L
        const val HOST_FOCUS_TIMEOUT_MS = 10_000L
        const val DIALOG_TIMEOUT_MS = 10_000L
    }
}
