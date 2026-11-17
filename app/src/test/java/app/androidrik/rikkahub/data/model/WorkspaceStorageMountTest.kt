package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceStorageMountTest {
    private val sharedRoot = "/storage/emulated/0"

    @Test
    fun normalizesBasicPaths() {
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath("/storage/emulated/0/Documents", sharedRoot))
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath(" /storage/emulated/0/Documents/ ", sharedRoot))
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath("/storage/emulated/0/./x/../Documents", sharedRoot))
    }

    @Test
    fun resolvesSdcardAliases() {
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath("/sdcard/Documents", sharedRoot))
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath("/mnt/sdcard/Documents", sharedRoot))
        assertEquals("/storage/emulated/0/Documents", normalizeStorageMountPath("/storage/self/primary/Documents", sharedRoot))
        assertEquals(sharedRoot, normalizeStorageMountPath("/sdcard", sharedRoot))
    }

    @Test
    fun allowsExternalVolumes() {
        assertEquals("/storage/1234-ABCD/repos", normalizeStorageMountPath("/storage/1234-ABCD/repos", sharedRoot))
    }

    @Test
    fun rejectsRelativePaths() {
        assertThrows(InvalidStorageMountPathException::class.java) {
            normalizeStorageMountPath("storage/emulated/0/Documents", sharedRoot)
        }
    }

    @Test
    fun rejectsPathsOutsideStorage() {
        assertThrows(InvalidStorageMountPathException::class.java) {
            normalizeStorageMountPath("/data/local/tmp", sharedRoot)
        }
        assertThrows(InvalidStorageMountPathException::class.java) {
            normalizeStorageMountPath("/storage", sharedRoot)
        }
        assertThrows(InvalidStorageMountPathException::class.java) {
            normalizeStorageMountPath("/storage/emulated", sharedRoot)
        }
    }
}
