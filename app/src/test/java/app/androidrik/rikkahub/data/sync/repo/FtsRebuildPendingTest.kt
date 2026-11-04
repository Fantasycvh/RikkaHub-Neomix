package app.androidrik.rikkahub.data.sync.repo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FtsRebuildPendingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun markAndClearFollowFileExistence() {
        val filesDir = temp.newFolder()
        assertFalse(FtsRebuildPending.isPending(filesDir))
        FtsRebuildPending.mark(filesDir)
        assertTrue(FtsRebuildPending.isPending(filesDir))
        assertTrue(FtsRebuildPending.markerFile(filesDir).isFile)
        FtsRebuildPending.mark(filesDir)
        assertTrue(FtsRebuildPending.isPending(filesDir))
        FtsRebuildPending.clear(filesDir)
        assertFalse(FtsRebuildPending.isPending(filesDir))
        // 重复清理无害
        FtsRebuildPending.clear(filesDir)
    }

    @Test fun emptyMarkerStillMeansPendingAfterCrash() {
        val filesDir = temp.newFolder()
        val marker = FtsRebuildPending.markerFile(filesDir)
        marker.parentFile!!.mkdirs()
        marker.writeText("")
        assertTrue(FtsRebuildPending.isPending(filesDir))
    }
}
