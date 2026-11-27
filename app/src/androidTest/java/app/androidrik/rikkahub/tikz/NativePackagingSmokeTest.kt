package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativePackagingSmokeTest {
    @Test
    fun extractedProotRunsAndroidShell() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(nativeDir, "libproot_exec.so")
        val loader = File(nativeDir, "libproot_loader.so")
        assertTrue("PRoot must be extracted and executable", proot.canExecute())
        assertTrue("PRoot loader must be extracted", loader.isFile)
        val process = ProcessBuilder(
            proot.absolutePath, "-r", "/", "-w", "/", "/system/bin/sh", "-c", "printf native-16kb-ok",
        ).redirectErrorStream(true).apply {
            environment()["PROOT_LOADER"] = loader.absolutePath
            environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            environment()["PROOT_NO_SECCOMP"] = "1"
        }.start()
        try {
            assertTrue("PRoot shell timed out", process.waitFor(20, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            assertTrue(output, output.contains("native-16kb-ok"))
        } finally {
            process.destroyForcibly()
        }
    }
}
