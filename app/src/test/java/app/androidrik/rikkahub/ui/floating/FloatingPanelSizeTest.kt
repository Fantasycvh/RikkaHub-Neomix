package app.androidrik.rikkahub.ui.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingPanelSizeTest {
    @Test
    fun `default size is preserved when space allows`() {
        assertEquals(FloatingPanelSize(560f, 520f), FloatingPanelSize())
        assertEquals(FloatingPanelSize(), FloatingPanelSize().bounded(1000f, 900f))
    }

    @Test
    fun `finite dimensions clamp independently to minimum and maximum`() {
        assertEquals(FloatingPanelSize(320f, 240f), FloatingPanelSize(-10f, 0f).bounded(1000f, 900f))
        assertEquals(FloatingPanelSize(1000f, 900f), FloatingPanelSize(2000f, 1800f).bounded(1000f, 900f))
        assertEquals(FloatingPanelSize(320f, 900f), FloatingPanelSize(100f, 1800f).bounded(1000f, 900f))
        assertEquals(FloatingPanelSize(1000f, 240f), FloatingPanelSize(2000f, 100f).bounded(1000f, 900f))
    }

    @Test
    fun `exact limits and fractional dimensions remain unchanged`() {
        assertEquals(FloatingPanelSize(320f, 240f), FloatingPanelSize(320f, 240f).bounded(1000f, 900f))
        assertEquals(FloatingPanelSize(1000f, 900f), FloatingPanelSize(1000f, 900f).bounded(1000f, 900f))
        val fractional = FloatingPanelSize(450.5f, 360.25f)
        assertEquals(fractional, fractional.bounded(1000f, 900f))
    }

    @Test
    fun `narrow and short screens take precedence over minimum dimensions`() {
        assertEquals(FloatingPanelSize(280f, 200f), FloatingPanelSize().bounded(280f, 200f))
        assertEquals(FloatingPanelSize(280f, 200f), FloatingPanelSize(1f, 1f).bounded(280f, 200f))
        assertEquals(FloatingPanelSize(280f, 520f), FloatingPanelSize().bounded(280f, 900f))
        assertEquals(FloatingPanelSize(560f, 200f), FloatingPanelSize().bounded(1000f, 200f))
    }

    @Test
    fun `nonfinite dimensions fall back to defaults independently before clamping`() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(FloatingPanelSize(560f, 400f), FloatingPanelSize(invalid, 400f).bounded(1000f, 900f))
            assertEquals(FloatingPanelSize(450f, 520f), FloatingPanelSize(450f, invalid).bounded(1000f, 900f))
            assertEquals(FloatingPanelSize(), FloatingPanelSize(invalid, invalid).bounded(1000f, 900f))
            assertEquals(FloatingPanelSize(400f, 300f), FloatingPanelSize(invalid, invalid).bounded(400f, 300f))
            assertEquals(FloatingPanelSize(280f, 200f), FloatingPanelSize(invalid, invalid).bounded(280f, 200f))
        }
    }

    @Test
    fun `drag deltas grow and shrink the current size without mutating it`() {
        val original = FloatingPanelSize()
        val enlarged = original.resized(80f, 40f, 1000f, 900f)
        assertEquals(FloatingPanelSize(640f, 560f), enlarged)
        assertEquals(FloatingPanelSize(600f, 540f), enlarged.resized(-40f, -20f, 1000f, 900f))
        assertEquals(FloatingPanelSize(560f, 520f), original)
        assertEquals(FloatingPanelSize(560.5f, 519.75f), original.resized(0.5f, -0.25f, 1000f, 900f))
    }

    @Test
    fun `drag clamps at both bounds and adapts to narrow screens`() {
        assertEquals(FloatingPanelSize(1000f, 900f), FloatingPanelSize().resized(10000f, 10000f, 1000f, 900f))
        assertEquals(FloatingPanelSize(320f, 240f), FloatingPanelSize().resized(-10000f, -10000f, 1000f, 900f))
        assertEquals(FloatingPanelSize(280f, 200f), FloatingPanelSize().resized(100f, -1000f, 280f, 200f))
        assertEquals(FloatingPanelSize(400f, 300f), FloatingPanelSize().resized(0f, 0f, 400f, 300f))
    }

    @Test
    fun `drag adds deltas before bounding the resulting size`() {
        assertEquals(FloatingPanelSize(350f, 270f), FloatingPanelSize(100f, 100f).resized(250f, 170f, 1000f, 900f))
        assertEquals(FloatingPanelSize(700f, 600f), FloatingPanelSize(1200f, 1000f).resized(-500f, -400f, 1000f, 900f))
    }

    @Test
    fun `nonfinite drag results use defaults rather than an extreme bound`() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(FloatingPanelSize(560f, 420f), FloatingPanelSize(450f, 400f).resized(invalid, 20f, 1000f, 900f))
            assertEquals(FloatingPanelSize(470f, 520f), FloatingPanelSize(450f, 400f).resized(20f, invalid, 1000f, 900f))
            assertEquals(FloatingPanelSize(400f, 300f), FloatingPanelSize().resized(invalid, invalid, 400f, 300f))
        }
    }

    @Test
    fun `finite drag arithmetic overflow falls back to defaults`() {
        assertEquals(
            FloatingPanelSize(),
            FloatingPanelSize(Float.MAX_VALUE, Float.MAX_VALUE)
                .resized(Float.MAX_VALUE, Float.MAX_VALUE, 1000f, 900f),
        )
    }
}
