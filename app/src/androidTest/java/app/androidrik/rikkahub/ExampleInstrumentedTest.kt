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
        // Context of the app under test. 本仓 debug 变体是 `app.androidrik.rikkahub.debug`，
        // 不能钉死 release applicationId，否则全量测试在 debug 包上必然失败。
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "unexpected target package: ${appContext.packageName}",
            appContext.packageName.startsWith("app.androidrik.rikkahub"),
        )
    }
}
