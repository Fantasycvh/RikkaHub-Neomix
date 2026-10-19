package app.androidrik.rikkahub.ui.components.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 画板状态机：撤销/重做/清空、画纸比例与布局、文字与选择变换。
 *
 * 状态类是纯逻辑，但文字排版用 Android 的 `StaticLayout`，所以放在 instrumented 测试里。
 * 不渲染 Compose UI，也不需要 Activity。
 */
@RunWith(AndroidJUnit4::class)
class SketchStateInstrumentedTest {
    private val epsilon = 0.01f

    @Test
    fun undoRedoClear_followTheStepAndRedoStacks() {
        val state = SketchState()
        state.fit(Size(800f, 800f))
        assertTrue(state.isBlank)
        assertFalse(state.canUndo)
        assertFalse(state.canRedo)

        // 空白画纸上的清空什么都不做，也不占一步。
        state.clear()
        assertEquals(0, state.steps.size)
        assertFalse(state.canUndo)

        val first = state.erase(Offset(-100f, 0f), width = 4f)
        state.extend(first, Offset(100f, 0f))
        state.finish(first)
        assertEquals(1, state.steps.size)
        assertTrue(state.steps.single() is SketchStep.Erase)
        assertFalse(state.isBlank)
        assertTrue(state.canUndo)
        assertFalse(state.canRedo)

        val second = state.erase(Offset(0f, -100f), width = 4f)
        state.extend(second, Offset(0f, 100f))
        state.finish(second)
        state.clear()
        assertEquals(3, state.steps.size)
        assertTrue(state.steps.last() is SketchStep.Clear)
        assertTrue("clear makes the paper blank again", state.isBlank)
        assertTrue(state.canUndo)

        state.undo()
        assertEquals(2, state.steps.size)
        assertFalse("undoing clear brings the strokes back", state.isBlank)
        assertTrue(state.canRedo)

        state.redo()
        assertEquals(3, state.steps.size)
        assertTrue(state.steps.last() is SketchStep.Clear)
        assertTrue(state.isBlank)

        // 撤销到清空之前，再画一笔：重做栈必须被清掉。
        state.undo()
        state.undo()
        assertEquals(1, state.steps.size)
        assertTrue(state.canRedo)
        state.erase(Offset(10f, 10f), width = 2f)
        assertEquals(2, state.steps.size)
        assertFalse("a new stroke must clear the redo stack", state.canRedo)
    }

    @Test
    fun paperLayout_followsAvailableSpaceRatioAndBackgroundSize() {
        // 没指定比例时占满可用区域。
        val state = SketchState()
        state.fit(Size(1000f, 400f))
        assertEquals(1000f, state.paperSize.width, epsilon)
        assertEquals(400f, state.paperSize.height, epsilon)
        assertTrue(state.isBlank)

        // 16:9 放在 1000x400 里：高度用满，宽度按比例。
        state.useAspectRatio(16f / 9f)
        assertEquals(711.111f, state.paperSize.width, 0.01f)
        assertEquals(400f, state.paperSize.height, epsilon)

        // 垫了底图时以底图比例为准：300x200 => 3:2，仍受可用区域限制。
        val background = SketchTestImages.solid(300, 200)
        state.useBackground(background)
        assertTrue("a background alone is not content", state.isBlank)
        assertEquals(600f, state.paperSize.width, epsilon)
        assertEquals(400f, state.paperSize.height, epsilon)

        // 去掉底图后回到显式比例。
        state.useBackground(null)
        assertEquals(711.111f, state.paperSize.width, 0.01f)
        assertEquals(400f, state.paperSize.height, epsilon)
    }

    @Test
    fun paperLayout_keepsTheOnScreenScaleWhenStepsExistAndRatioChanges() {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(1000f, 1000f))
        assertEquals(1000f, state.paperSize.width, epsilon)
        assertEquals(1000f, state.paperSize.height, epsilon)

        // 有笔画之后，可用区域变化（如旋转屏幕）只缩放显示，不重排画纸。
        state.erase(Offset.Zero, width = 2f)
        state.fit(Size(500f, 500f))
        assertEquals(1000f, state.paperSize.width, epsilon)
        assertEquals(1000f, state.paperSize.height, epsilon)

        // 换比例时保持笔画在屏幕上的缩放：2:1 时画纸在纸上坐标里变成 1000x500。
        state.useAspectRatio(2f)
        assertEquals(1000f, state.paperSize.width, epsilon)
        assertEquals(500f, state.paperSize.height, epsilon)

        // 换回 1:1 应恢复原画纸尺寸，画到外面的笔画不会被裁掉。
        state.useAspectRatio(1f)
        assertEquals(1000f, state.paperSize.width, epsilon)
        assertEquals(1000f, state.paperSize.height, epsilon)
    }

    @Test
    fun sizeFit_computesTheLargestBoxWithTheGivenRatio() {
        // 可用区域比比例更宽：高度用满。
        val wide = Size(1000f, 400f).fit(2f)
        assertEquals(800f, wide.width, epsilon)
        assertEquals(400f, wide.height, epsilon)

        // 可用区域比比例更高：宽度用满。
        val tall = Size(300f, 1000f).fit(0.5f)
        assertEquals(300f, tall.width, epsilon)
        assertEquals(600f, tall.height, epsilon)

        // 比例相同：原样返回。
        val same = Size(600f, 300f).fit(2f)
        assertEquals(600f, same.width, epsilon)
        assertEquals(300f, same.height, epsilon)
    }

    @Test
    fun writeText_trimsInputAndIgnoresBlankText() {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(500f, 500f))

        state.beginText(Offset(10f, 20f), width = 10f)
        state.write("   ")
        assertEquals("blank text must not become a step", 0, state.steps.size)

        state.beginText(Offset(10f, 20f), width = 10f)
        state.write("  hi  ")
        val text = state.steps.single() as SketchStep.Text
        assertEquals("hi", text.text)
        assertFalse(state.isBlank)
    }

    @Test
    fun selectionTransform_movesTextAndIsUndoneAsOneStep() {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(1000f, 1000f))
        state.beginText(Offset.Zero, width = 10f)
        state.write("hello")
        val text = state.steps.single() as SketchStep.Text
        assertTrue(text.bounds.width > 0f)
        assertTrue(text.bounds.height > 0f)
        assertTrue("the text must be touchable where it was laid out", text.touches(text.bounds))

        // 点选：用一大块区域覆盖画纸，选中唯一的文字。
        state.select(Rect(-400f, -400f, 400f, 400f), topmost = false)
        assertEquals(listOf<SketchStep.Item>(text), state.selection)
        val originalBounds = requireNotNull(state.selectionBounds)
        assertEquals(text.bounds.left, originalBounds.left, epsilon)
        assertEquals(text.bounds.top, originalBounds.top, epsilon)

        // 拖动未松手时，placements/selectionBounds 已经反映移动后的位置。
        val move = SketchPlacement.move(Offset(100f, 50f))
        state.moving = move
        assertEquals(move, state.placements()[text])
        val movingBounds = requireNotNull(state.selectionBounds)
        assertEquals(originalBounds.left + 100f, movingBounds.left, epsilon)
        assertEquals(originalBounds.top + 50f, movingBounds.top, epsilon)

        // 松手把这次变换记成一步。
        state.settle()
        assertEquals(SketchPlacement.None, state.moving)
        assertEquals(2, state.steps.size)
        assertTrue(state.steps.last() is SketchStep.Transform)
        assertEquals(move, state.placements()[text])
        assertTrue(state.canUndo)

        // 撤销一次就回到未移动的状态（撤销会取消选中）。
        state.undo()
        assertEquals(1, state.steps.size)
        assertNull(state.selectionBounds)
        assertTrue(state.selection.isEmpty())
        assertEquals(SketchPlacement.None, state.placements()[text])

        // 重做把变换叠回去。
        state.redo()
        assertEquals(2, state.steps.size)
        assertEquals(move, state.placements()[text])
    }

    @Test
    fun placement_composesScalesAndOffsetsAndInverts() {
        val move = SketchPlacement.move(Offset(10f, 20f))
        val scale = SketchPlacement.scale(by = 2f, around = Offset(100f, 100f))
        val combined = move.then(scale)

        // 先挪再以 (100,100) 为不动点放大 2 倍，等价于 scale=2、offset=(-80,-60)。
        assertEquals(2f, combined.scale, epsilon)
        assertEquals(-80f, combined.offset.x, epsilon)
        assertEquals(-60f, combined.offset.y, epsilon)
        assertEquals(Offset(0f, 0f), combined.apply(Offset(40f, 30f)))

        // apply/invert 对矩形互逆。
        val rect = Rect(10f, 20f, 110f, 220f)
        val roundTrip = combined.invert(combined.apply(rect))
        assertEquals(rect.left, roundTrip.left, epsilon)
        assertEquals(rect.top, roundTrip.top, epsilon)
        assertEquals(rect.right, roundTrip.right, epsilon)
        assertEquals(rect.bottom, roundTrip.bottom, epsilon)
    }
}
