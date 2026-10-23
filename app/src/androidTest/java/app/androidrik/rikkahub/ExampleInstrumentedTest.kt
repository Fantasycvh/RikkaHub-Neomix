package app.androidrik.rikkahub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test. 本分支全局 applicationId 是 `app.androidrik.neomix`
        //（debug 为 `app.androidrik.neomix.debug`），不能钉死具体包名，否则换 applicationId 后必失败。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "unexpected target package: ${appContext.packageName}",
            appContext.packageName.startsWith("app.androidrik."),
        )
    }
}
