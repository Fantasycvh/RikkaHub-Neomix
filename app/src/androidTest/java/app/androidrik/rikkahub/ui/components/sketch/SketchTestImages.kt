package app.androidrik.rikkahub.ui.components.sketch

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** 测试用的纯色图片：只在内存里分配，不读文件、不联网。 */
internal object SketchTestImages {
    fun solid(width: Int, height: Int, color: Int = Color.RED): ImageBitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()
}
