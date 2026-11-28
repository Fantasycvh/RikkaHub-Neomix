package app.androidrik.rikkahub.ui.components.table

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DataTableLayoutTest {

    @Test
    fun `distributeColumnWidths clamps min and max widths`() {
        val result = distributeColumnWidths(
            naturalWidths = intArrayOf(30, 500, 120),
            minWidths = intArrayOf(60, 80, 100),
            maxWidths = intArrayOf(150, 200, 200),
            viewportMaxWidth = 300,
            stretchToFillWidth = true,
        )
        assertArrayEquals(intArrayOf(60, 200, 120), result)
    }

    @Test
    fun `distributeColumnWidths stretches narrow table proportionally to viewport`() {
        val result = distributeColumnWidths(
            naturalWidths = intArrayOf(100, 200),
            minWidths = intArrayOf(0, 0),
            maxWidths = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE),
            viewportMaxWidth = 600,
            stretchToFillWidth = true,
        )
        assertArrayEquals(intArrayOf(200, 400), result)
    }

    @Test
    fun `distributeColumnWidths handles unbounded viewport disabled stretch empty and zero natural widths`() {
        assertArrayEquals(
            intArrayOf(100, 200),
            distributeColumnWidths(
                naturalWidths = intArrayOf(100, 200),
                minWidths = intArrayOf(0, 0),
                maxWidths = intArrayOf(500, 500),
                viewportMaxWidth = Constraints.Infinity,
                stretchToFillWidth = true,
            ),
        )
        assertArrayEquals(
            intArrayOf(100, 200),
            distributeColumnWidths(
                naturalWidths = intArrayOf(100, 200),
                minWidths = intArrayOf(0, 0),
                maxWidths = intArrayOf(500, 500),
                viewportMaxWidth = 600,
                stretchToFillWidth = false,
            ),
        )
        assertArrayEquals(
            IntArray(0),
            distributeColumnWidths(
                naturalWidths = IntArray(0),
                minWidths = IntArray(0),
                maxWidths = IntArray(0),
                viewportMaxWidth = 600,
                stretchToFillWidth = true,
            ),
        )
        assertArrayEquals(
            intArrayOf(0, 0),
            distributeColumnWidths(
                naturalWidths = intArrayOf(0, 0),
                minWidths = intArrayOf(0, 0),
                maxWidths = intArrayOf(100, 100),
                viewportMaxWidth = 600,
                stretchToFillWidth = true,
            ),
        )
    }
}
