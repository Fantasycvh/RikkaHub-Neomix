package app.androidrik.rikkahub.phoneuse

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneUseDisplayServiceTest {

    // PJX110 / Android 15 真机上 `am stack list` 的真实输出节选
    private val sample = """
RootTask id=1 bounds=[0,0][1264,2780] displayId=0 userId=0
  taskId=2: com.android.launcher/com.android.launcher.Launcher bounds=[0,0][1264,2780] userId=0 visible=true topActivity=ComponentInfo{com.android.launcher/com.android.launcher.Launcher}
RootTask id=8711 bounds=[0,0][720,1280] displayId=5 userId=0
  taskId=8711: com.android.settings/com.android.settings.Settings bounds=[0,0][720,1280] userId=0 visible=true topActivity=ComponentInfo{com.android.settings/com.android.settings.Settings}
RootTask id=8700 bounds=[0,0][1264,2780] displayId=0 userId=0
  taskId=8700: app.androidrik.rikkahub.debug/app.androidrik.rikkahub.ui.RouteActivity bounds=[0,0][1264,2780] userId=0 visible=true topActivity=ComponentInfo{app.androidrik.rikkahub.debug/app.androidrik.rikkahub.ui.RouteActivity}
    """.trimIndent()

    @Test
    fun `parses tasks of the target display only, most recent first`() {
        val recent = parseRecentTaskComponents(sample, displayId = 5)
        assertEquals(listOf("com.android.settings"), recent)
    }

    @Test
    fun `keeps multiple tasks in output order and dedupes by package`() {
        val output = """
RootTask id=1 displayId=5 userId=0
  taskId=11: com.android.settings/com.android.settings.Settings userId=0
  taskId=12: com.android.settings/com.android.settings.SubSettings userId=0
  taskId=13: com.android.deskclock/com.android.deskclock.DeskClock userId=0
RootTask id=2 displayId=0 userId=0
  taskId=14: com.other.app/.Main userId=0
        """.trimIndent()
        val recent = parseRecentTaskComponents(output, displayId = 5)
        assertEquals(listOf("com.android.settings", "com.android.deskclock"), recent)
    }

    @Test
    fun `returns empty when the display has no tasks`() {
        val recent = parseRecentTaskComponents(sample, displayId = 7)
        assertEquals(emptyList<String>(), recent)
    }
}
