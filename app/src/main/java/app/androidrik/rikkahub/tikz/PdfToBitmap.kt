package app.androidrik.rikkahub.tikz

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tectonic 产出的 PDF → Bitmap（Android 内置 PdfRenderer，API 21+，零依赖）。
 * 代价是非矢量（dvisvgm 矢量路径为后续第 3 步）。
 */
object PdfToBitmap {

    /** 渲染 PDF 首页为 Bitmap；失败返回 null。IO 线程调用。 */
    suspend fun renderFirstPage(pdf: ByteArray, targetWidthPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("tikz_compile", ".pdf")
            try {
                file.writeBytes(pdf)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount == 0) return@use null
                        renderer.openPage(0).use { page ->
                            val width = targetWidthPx.coerceAtLeast(1)
                            val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                            // PdfRenderer 输出透明底，衬白底保证暗色主题可见
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap.asImageBitmap()
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("PdfToBitmap", "render first page failed: ${t.message}", t)
                null
            } finally {
                file.delete()
            }
        }
}
