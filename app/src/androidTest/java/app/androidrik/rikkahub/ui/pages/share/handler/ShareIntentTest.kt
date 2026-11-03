package app.androidrik.rikkahub.ui.pages.share.handler

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    private val first = Uri.parse("content://sender/first")
    private val second = Uri.parse("content://sender/second")

    @Test
    fun manifestResolvesImagesDocumentsAndTextToIndependentReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Exercise the system resolver used by the share sheet.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("image/png", "application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("cmd package query-activities --brief -a $action -t $mime -p ${context.packageName}"),
                ).bufferedReader().use { it.readText() }
                assertTrue("$action $mime: $result", result.contains(".ShareReceiverActivity"))
            }
        }
    }

    @Test
    fun singleParcelableAndLegacyCameraString() {
        assertEquals(listOf(first.toString()), Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first).readSharedUris())
        assertEquals(listOf(first.toString()), Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, first.toString()).readSharedUris())
    }

    @Test
    fun multipleExtrasAndClipDataKeepOrderWithoutDuplicates() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
            clipData = ClipData.newRawUri("files", second).apply { addItem(ClipData.Item(first)) }
        }
        assertEquals(listOf(first.toString(), second.toString()), intent.readSharedUris())
    }

    @Test
    fun clipDataOnlyAndUnsafeSchemes() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            clipData = ClipData.newRawUri("files", first).apply {
                addItem(ClipData.Item(second))
                addItem(ClipData.Item(Uri.parse("file:///data/data/private.xml")))
                addItem(ClipData.Item(Uri.parse("https://example.com/image.png")))
            }
        }
        assertEquals(listOf(first.toString(), second.toString()), intent.readSharedUris())
    }
}
