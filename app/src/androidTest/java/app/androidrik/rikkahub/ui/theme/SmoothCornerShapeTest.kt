package app.androidrik.rikkahub.ui.theme

import android.graphics.Region
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmoothCornerShapeTest {
    @Test
    fun startSideStaysSquareWhenOnlyEndCornersAreRounded() {
        val shape = SmoothCornerShape(24.dp).copy(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(24.dp),
            bottomEnd = CornerSize(24.dp),
            bottomStart = CornerSize(0.dp),
        )
        val width = 300
        val height = 800
        for (direction in LayoutDirection.entries) {
            val outline = shape.createOutline(
                Size(width.toFloat(), height.toFloat()), direction, Density(1f),
            ) as Outline.Generic
            val region = Region()
            region.setPath(outline.path.asAndroidPath(), Region(0, 0, width, height))

            val startX = if (direction == LayoutDirection.Ltr) 2 else width - 3
            val endX = width - 1 - startX
            assertTrue("$direction top start", region.contains(startX, 2))
            assertTrue("$direction middle start", region.contains(startX, height / 2))
            assertTrue("$direction bottom start", region.contains(startX, height - 3))
            assertFalse("$direction top end", region.contains(endX, 2))
            assertFalse("$direction bottom end", region.contains(endX, height - 3))
        }
    }
}
