package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Skip unsupported processes; a broken supported-ABI package must fail. */
internal fun requirePackagedTectonic() {
    assumeTrue("TeX is arm64-only", System.getProperty("os.arch") == "aarch64")
    assertTrue("arm64 APK is missing libtex_poc.so", TectonicNative.isPackaged)
}
