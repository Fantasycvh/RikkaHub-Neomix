package app.androidrik.rikkahub.service.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowImeGeometryTest {
    private fun fit(g: WindowGeometry, ime: Int, height: Int = 2400) =
        FloatingWindowGeometry.aboveIme(g, 1080, height, ime, 770, 880)

    @Test
    fun `keyboard moves lower window into visible area`() {
        val g = fit(WindowGeometry(100, 1400, 800, 900), 900)
        assertEquals(900, g.height)
        assertEquals(600, g.y)
        assertTrue(g.bottom <= 1500)
    }

    @Test
    fun `keyboard shrinks tall window without changing horizontal position`() {
        val g = fit(WindowGeometry(100, 300, 800, 1800), 1000)
        assertEquals(100, g.x)
        assertEquals(800, g.width)
        assertTrue(g.bottom <= 1400)
        assertTrue(g.height >= 880)
    }

    @Test
    fun `landscape keyboard can reduce height below normal minimum`() {
        val g = fit(WindowGeometry(100, 100, 800, 900), 700, height = 1080)
        assertTrue(g.height in 1..380)
        assertTrue(g.bottom <= 380)
    }

    @Test
    fun `invalid keyboard height never creates negative geometry`() {
        val g = fit(WindowGeometry(100, 300, 800, 900), Int.MAX_VALUE)
        assertEquals(1, g.height)
        assertEquals(0, g.y)
    }

    @Test
    fun `negative keyboard inset is treated as hidden`() {
        val original = WindowGeometry(100, 300, 800, 900)
        assertEquals(original, fit(original, -900))
    }

    @Test
    fun `restoring original geometry after keyboard does not keep temporary size`() {
        val original = WindowGeometry(100, 600, 800, 1600)
        val temporary = fit(original, 1100)
        assertTrue(temporary.height < original.height)
        assertEquals(original, fit(original, 0))
    }
}
