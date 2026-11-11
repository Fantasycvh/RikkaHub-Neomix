package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.local.buildPhoneUseTool
import app.androidrik.rikkahub.data.ai.tools.local.describe
import app.androidrik.rikkahub.data.ai.tools.local.phoneUseActionNeedsApproval
import app.androidrik.rikkahub.phoneuse.PhoneApp
import app.androidrik.rikkahub.phoneuse.PhoneController
import app.androidrik.rikkahub.phoneuse.PhoneElement
import app.androidrik.rikkahub.phoneuse.PhoneKey
import app.androidrik.rikkahub.phoneuse.PhoneScreen
import app.androidrik.rikkahub.phoneuse.PhoneUseManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseToolTest {
    private val manager = PhoneUseManager()
    private val controller = FakeController(manager)
    private var selfInForeground = false
    private var floatingWindowOn = true
    private val savedScreenshots = mutableListOf<ByteArray>()
    private val tool: Tool = buildPhoneUseTool(
        ownPackageName = "app.androidrik.rikkahub",
        manager = manager,
        saveScreenshot = { bytes ->
            savedScreenshots += bytes
            "file:///screenshot.jpg"
        },
        isFloatingWindowOn = { floatingWindowOn },
        isSelfInForeground = { selfInForeground },
    )

    private suspend fun call(build: JsonObjectBuilder.() -> Unit): String =
        tool.execute(buildJsonObject(build)).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun approval(action: String, vararg extra: Pair<String, String>): Boolean =
        phoneUseActionNeedsApproval(buildJsonObject {
            put("action", action)
            extra.forEach { (key, value) -> put(key, value) }
        })

    @Test
    fun `read only actions skip approval but screenshot and changes do not`() {
        assertFalse(approval("observe"))
        assertFalse(approval("list_apps"))
        assertFalse(approval("wait"))
        assertFalse(approval("press", "key" to "home"))
        // S2：截图按敏感读取处理
        assertTrue(approval("screenshot"))
        assertTrue(approval("tap"))
        assertTrue(approval("type", "text" to "hi"))
        assertTrue(approval("swipe"))
        assertTrue(approval("open_app", "app" to "Demo"))
        assertTrue(approval("press", "key" to "back"))
        // 半截 JSON / 未知动作按最严格处理
        assertTrue(phoneUseActionNeedsApproval(Json.parseToJsonElement("{}")))
        assertTrue(approval("something_new"))
    }

    @Test
    fun `fails with guidance when the accessibility service is off`() = runTest {
        val error = runCatching { call { put("action", "observe") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("not enabled"))
    }

    @Test
    fun `refuses to run while the floating window is off`() = runTest {
        manager.attach(controller)
        floatingWindowOn = false
        val error = runCatching { call { put("action", "press"); put("key", "home") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("floating window"))
        assertTrue(controller.keys.isEmpty())
    }

    @Test
    fun `observe lists elements on the grid and masks passwords`() = runTest {
        manager.attach(controller)
        val output = call { put("action", "observe") }
        assertTrue(output.contains("App: Demo (com.demo)"))
        // 中心 (540,1200) 在 1080x2400 的屏幕上是网格的 (500,500)
        assertTrue(output.contains("[0] Button \"Send\" clickable @(500,500)"))
        assertTrue(output.contains("[1] EditText \"Message\" editable focused @(250,708)"))
        assertTrue(output.contains("[2] ImageView #btn_more clickable @(888,62)"))
        // 密码框只保留可输入/密码语义，不返回文字
        assertTrue(output.contains("[3] EditText #password password editable"))
        assertFalse(output.contains("hunter2"))
    }

    @Test
    fun `tap on element hits its center while overlays yield`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        val output = call {
            put("action", "tap")
            put("element", 0)
        }
        assertEquals(listOf(540f, 1200f, 540f, 1200f), controller.gestures.single())
        assertEquals(listOf(true), controller.actingDuringGesture)
        assertFalse(manager.acting.value)
        assertTrue(output.startsWith("tap done."))
    }

    @Test
    fun `tap by grid coordinates converts to pixels and accepts string numbers`() = runTest {
        manager.attach(controller)
        call {
            put("action", "tap")
            put("x", "250")
            put("y", 750)
        }
        assertEquals(listOf(270f, 1800f, 270f, 1800f), controller.gestures.single())
    }

    @Test
    fun `scroll down moves the finger up`() = runTest {
        manager.attach(controller)
        call {
            put("action", "scroll")
            put("direction", "down")
        }
        val (fromX, fromY, toX, toY) = controller.gestures.single()
        assertEquals(fromX, toX)
        assertTrue(fromY > toY)
    }

    @Test
    fun `stale element index is rejected`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        val error = runCatching {
            call {
                put("action", "tap")
                put("element", 9)
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("Observe the screen again"))
        assertTrue(controller.gestures.isEmpty())
    }

    @Test
    fun `refuses to operate while this app is in the foreground except leaving it`() = runTest {
        manager.attach(controller)
        selfInForeground = true
        val error = runCatching {
            call {
                put("action", "tap")
                put("x", 1)
                put("y", 1)
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("foreground"))
        assertTrue(controller.gestures.isEmpty())

        call { put("action", "press"); put("key", "home") }
        assertEquals(listOf(PhoneKey.HOME), controller.keys)

        call { put("action", "open_app"); put("app", "demo") }
        assertEquals(listOf("com.demo"), controller.opened)
    }

    @Test
    fun `blocked apps cannot be listed or opened`() = runTest {
        manager.attach(controller)
        assertFalse(call { put("action", "list_apps") }.contains("Alipay"))
        val error = runCatching {
            call {
                put("action", "open_app")
                put("app", "Alipay")
            }
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("blocked"))
        assertTrue(controller.opened.isEmpty())
    }

    @Test
    fun `custom blocked packages are enforced`() = runTest {
        manager.attach(controller)
        val customTool = buildPhoneUseTool(
            ownPackageName = "app.androidrik.rikkahub",
            manager = manager,
            saveScreenshot = { "file:///screenshot.jpg" },
            isFloatingWindowOn = { floatingWindowOn },
            isSelfInForeground = { selfInForeground },
            userBlockedPackages = { setOf("com.other") },
        )
        val list = customTool.execute(buildJsonObject { put("action", "list_apps") })
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        assertFalse(list.contains("Other"))
        val error = runCatching {
            customTool.execute(buildJsonObject {
                put("action", "open_app")
                put("app", "com.other")
            })
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("blocked"))
        assertTrue(controller.opened.isEmpty())
    }

    @Test
    fun `blocked app on screen stops reading and acting`() = runTest {
        manager.attach(controller)
        controller.screen = controller.screen.copy(packages = setOf("com.eg.android.AlipayGphone"))
        val error = runCatching { call { put("action", "observe") } }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("com.eg.android.AlipayGphone"))
        assertTrue(error.message!!.contains("blocked"))
    }

    @Test
    fun `screenshot returns the saved image and grid instructions`() = runTest {
        manager.attach(controller)
        val parts = tool.execute(buildJsonObject { put("action", "screenshot") })
        val image = parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals("file:///screenshot.jpg", image.url)
        assertTrue(savedScreenshots.single().isNotEmpty())
        val text = parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(text.contains("1000 x 1000 grid"))
    }

    @Test
    fun `unchanged screen after an action is compressed to a one-liner`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        // FakeController 的 tap 不改界面：差分应命中“没变化”，而不是再回一份全量列表
        val output = call {
            put("action", "tap")
            put("element", 0)
        }
        assertTrue(output.contains("Screen unchanged"))
        assertFalse(output.contains("Elements as [index]"))
    }

    @Test
    fun `only changed labels are returned when the layout is stable`() = runTest {
        manager.attach(controller)
        call { put("action", "observe") }
        controller.screen = controller.screen.copy(
            elements = controller.screen.elements.map {
                if (it.index == 0) it.copy(label = "Send now") else it
            },
        )
        val output = call { put("action", "wait"); put("seconds", 0.5) }
        assertTrue(output.contains("Only these elements changed"))
        assertTrue(output.contains("\"Send now\""))
        assertFalse(output.contains("Elements as [index]"))
        // 未变化的元素不再重复
        assertFalse(output.contains("\"Message\""))
    }

    @Test
    fun `position changes fall back to the full element list`() = runTest {
        manager.attach(controller)
        val before = controller.screen
        val after = before.copy(
            elements = before.elements.map {
                if (it.index == 0) it.copy(top = it.top + 10) else it
            },
        )
        val output = describe(after, before)
        assertTrue(output.contains("Elements as [index]"))
    }

    @Test
    fun `added elements fall back to the full element list`() = runTest {
        manager.attach(controller)
        val before = controller.screen
        val after = before.copy(elements = before.elements + before.elements.first().copy(index = 4))
        val output = describe(after, before)
        assertTrue(output.contains("Elements as [index]"))
    }

    private class FakeController(private val manager: PhoneUseManager) : PhoneController {
        var screen = PhoneScreen(
            packageName = "com.demo",
            appLabel = "Demo",
            packages = setOf("com.demo"),
            width = 1080,
            height = 2400,
            keyboardVisible = false,
            elements = listOf(
                PhoneElement(
                    index = 0, className = "Button", label = "Send", resourceId = "btn_send",
                    left = 440, top = 1100, right = 640, bottom = 1300,
                    clickable = true, editable = false, scrollable = false, checked = null,
                    focused = false, password = false,
                ),
                PhoneElement(
                    index = 1, className = "EditText", label = "Message", resourceId = null,
                    left = 100, top = 1600, right = 440, bottom = 1800,
                    clickable = false, editable = true, scrollable = false, checked = null,
                    focused = true, password = false,
                ),
                PhoneElement(
                    index = 2, className = "ImageView", label = "", resourceId = "btn_more",
                    left = 940, top = 130, right = 980, bottom = 170,
                    clickable = true, editable = false, scrollable = false, checked = null,
                    focused = false, password = false,
                ),
                PhoneElement(
                    index = 3, className = "EditText", label = "", resourceId = "password",
                    left = 100, top = 1900, right = 440, bottom = 2000,
                    clickable = false, editable = true, scrollable = false, checked = null,
                    focused = false, password = true,
                ),
            ),
            truncated = false,
        )

        val gestures = mutableListOf<List<Float>>()
        val actingDuringGesture = mutableListOf<Boolean>()
        val keys = mutableListOf<PhoneKey>()
        val opened = mutableListOf<String>()

        override fun visiblePackages(): Set<String> = screen.packages

        override suspend fun captureScreen(): PhoneScreen = screen

        override suspend fun screenshot(maxSide: Int): ByteArray = byteArrayOf(1, 2, 3)

        override suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
            gestures += listOf(fromX, fromY, toX, toY)
            actingDuringGesture += manager.acting.value
            return true
        }

        override suspend fun setText(elementIndex: Int?, text: String): Boolean = true

        override suspend fun pressKey(key: PhoneKey): Boolean {
            keys += key
            return true
        }

        override fun listApps(): List<PhoneApp> = listOf(
            PhoneApp("com.demo", "Demo"),
            PhoneApp("com.other", "Other"),
            PhoneApp("com.eg.android.AlipayGphone", "Alipay"),
        )

        override fun openApp(packageName: String): Boolean {
            opened += packageName
            return true
        }
    }
}
