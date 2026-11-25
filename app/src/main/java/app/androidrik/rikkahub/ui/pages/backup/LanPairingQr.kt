package app.androidrik.rikkahub.ui.pages.backup

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.lan.LanInvite
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor

@Composable
internal fun LanPairingQr(code: String) {
    val matrix = remember(code) { QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 1, 1) }
    val description = stringResource(R.string.lan_pair_qr_content)
    Canvas(Modifier.size(172.dp).semantics { contentDescription = description }) {
        val cell = floor(size.minDimension / matrix.width)
        val inset = (size.minDimension - cell * matrix.width) / 2
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(inset + x * cell, inset + y * cell), Size(cell, cell))
            }
        }
    }
}

internal suspend fun readLanPairingQr(resolver: ContentResolver, uri: Uri): String? = withContext(Dispatchers.IO) {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return@withContext null
    var sampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / sampleSize > 1536) sampleSize *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: return@withContext null
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val value = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        LanInvite.parse(value)?.code
    } catch (_: ReaderException) {
        null
    } finally {
        bitmap.recycle()
    }
}
