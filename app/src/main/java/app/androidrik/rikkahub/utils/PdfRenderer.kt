package app.androidrik.rikkahub.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * 用 Android 内置 PdfRenderer 把 PDF 首字节渲染成 Bitmap（TikZ 图通常单页）。
 * 零外部依赖，API 21+ 内置。
 */
object PdfToBitmap {
    /**
     * 渲染 PDF 的第一页为 Bitmap。
     * @param pdfBytes PDF 字节（Tectonic 编译输出）
     * @param targetWidth 目标宽度（px），null 则用 PDF 原生尺寸（120dpi）
     */
    fun renderFirstPage(
        pdfBytes: ByteArray,
        targetWidth: Int? = null,
    ): Bitmap? {
        val file = File.createTempFile("tikz_render", ".pdf")
        try {
            file.outputStream().use { it.write(pdfBytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                val renderer = PdfRenderer(descriptor)
                try {
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val density = 120f
                        val baseWidth = (page.width / 72f * density).toInt()
                        val baseHeight = (page.height / 72f * density).toInt()
                        val scale = if (targetWidth != null) {
                            targetWidth.toFloat() / baseWidth
                        } else {
                            1f
                        }
                        val width = (baseWidth * scale).toInt().coerceAtLeast(1)
                        val height = (baseHeight * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        val matrix = Matrix()
                        matrix.postScale(scale, scale)
                        val destClip = Rect(0, 0, width, height)
                        page.render(bitmap, destClip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                } finally {
                    renderer.close()
                }
            } finally {
                descriptor.close()
            }
        } finally {
            file.delete()
        }
    }
}
