package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.webview.*
import app.androidrik.rikkahub.ui.components.richtext.buildMermaidHtml
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppSkinWebRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    private val base = "https://rikkahub.local/skin-test"
    private val html = """<!doctype html><html><head><style>body{height:2000px;margin:0}</style></head>
        <body><input id="draft"><script>window.nonce=Math.random();</script></body></html>"""

    @Test fun managedMermaidRendersAndExportsWithItsResolvedSkin() {
        val sink = ExportSink()
        val palette = lightColorScheme(background = Color(0xff112233), onBackground = Color.White)
        val content = buildMermaidHtml("graph TD; A-->B", palette, "monospace", 20f, Color.Cyan)
        val state = WebViewState(WebContent.Data(content, baseUrl = "$WEB_VIEW_BASE_URL/",
            mimeType = "text/html", appOwned = true), interfaces = mapOf("AndroidInterface" to sink))
        compose.setContent { AppSkinTheme(ResolvedAppSkin(), palette) {
            WebView(state, Modifier.fillMaxWidth().height(300.dp))
        } }
        awaitDocument(state, "$WEB_VIEW_BASE_URL/")
        awaitValue(state, "document.querySelector('.mermaid svg')!==null", "true")
        assertTrue(read(state, "getComputedStyle(document.querySelector('.mermaid svg')).fontFamily").contains("monospace"))
        read(state, "exportSvgToPng();'requested'")
        compose.waitUntil(15_000) { sink.image.get() != null }
        val bytes = android.util.Base64.decode(sink.image.get(), android.util.Base64.DEFAULT)
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            assertEquals(0xff112233.toInt(), bitmap.getPixel(0, 0))
        } finally { bitmap.recycle() }
    }

    @Test fun liveSkinUpdatesCssWithoutReloadingInputFocusOrScroll() {
        val state = WebViewState(WebContent.Data(html, baseUrl = base, mimeType = "text/html", appOwned = true))
        var skin by mutableStateOf(AppSkin())
        compose.setContent { AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
            WebView(state, Modifier.fillMaxWidth().height(240.dp))
        } }
        awaitDocument(state, base)
        val nonce = read(state, "window.nonce.toString()")
        read(state, "document.getElementById('draft').value='keep';document.getElementById('draft').focus({preventScroll:true});window.scrollTo(0,300);'ok'")
        val scroll = read(state, "window.scrollY.toString()")
        val view = state.webView
        compose.runOnIdle { skin = skin.copy(typography = skin.typography.copy(sizeRatio = 1.25f),
            embedded = skin.embedded.copy(web = AppSkinWeb(backgroundHex = "#112233", foregroundHex = "#ddeeff"))) }
        awaitValue(state, "getComputedStyle(document.body).backgroundColor", "rgb(17, 34, 51)")
        assertSame(view, state.webView)
        assertEquals(nonce, read(state, "window.nonce.toString()"))
        assertEquals("keep", read(state, "document.getElementById('draft').value"))
        assertEquals("draft", read(state, "document.activeElement.id"))
        assertEquals(scroll, read(state, "window.scrollY.toString()"))
        assertEquals("20px", read(state, "getComputedStyle(document.documentElement).fontSize"))
        assertEquals("1", read(state, "document.querySelectorAll('#rikka-app-skin-v1').length.toString()"))
        compose.runOnIdle { skin = skin.copy(embedded = skin.embedded.copy(web = AppSkinWeb(enabled = false))) }
        compose.waitUntil(10_000) { read(state, "document.getElementById('rikka-app-skin-v1')===null") == "true" }
        assertEquals("16px", read(state, "getComputedStyle(document.documentElement).fontSize"))
        assertEquals("keep", read(state, "document.getElementById('draft').value"))
    }

    @Test fun unmanagedHtmlAndNavigationAwayNeverReceiveThemeCss() {
        val state = WebViewState(WebContent.Data(html, baseUrl = base, mimeType = "text/html"))
        compose.setContent { AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
            WebView(state, Modifier.height(240.dp))
        } }
        awaitDocument(state, base)
        assertEquals("true", read(state, "document.getElementById('rikka-app-skin-v1')===null"))
        compose.runOnIdle { state.loadData(html, baseUrl = base, mimeType = "text/html", appOwned = true) }
        awaitValue(state, "document.getElementById('rikka-app-skin-v1')!==null", "true")
        compose.runOnIdle { state.webView!!.loadUrl("about:blank") }
        awaitDocument(state, "about:blank")
        assertEquals("true", read(state, "document.getElementById('rikka-app-skin-v1')===null"))
    }

    @Test fun authorCssWinsAndReplacementStateReceivesItsOwnCallbacks() {
        var source by mutableStateOf(html.replace("body{", "body{background:#abcdef;"))
        var state: WebViewState? = null
        compose.setContent { AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
            val current = rememberWebViewState(data = source, baseUrl = base, mimeType = "text/html", appOwned = true)
            state = current
            WebView(current, Modifier.height(240.dp))
        } }
        compose.waitUntil(10_000) { state?.currentUrl == base && state?.isLoading == false }
        val first = requireNotNull(state)
        assertEquals("rgb(171, 205, 239)", read(first, "getComputedStyle(document.body).backgroundColor"))
        compose.runOnIdle { source = source.replace("id=\"draft\"", "id=\"replacement\"") }
        compose.waitUntil(10_000) { state !== first && state?.currentUrl == base && state?.isLoading == false }
        assertEquals("true", read(requireNotNull(state), "document.getElementById('replacement')!==null"))
    }

    private fun awaitDocument(state: WebViewState, url: String) {
        compose.waitUntil(15_000) { state.webView != null && state.currentUrl == url && !state.isLoading }
    }

    private fun awaitValue(state: WebViewState, expression: String, expected: String) {
        try { compose.waitUntil(10_000) { read(state, expression) == expected } }
        catch (failure: Throwable) {
            var url: String? = null
            compose.runOnIdle { url = state.webView?.url }
            throw AssertionError("Theme bridge current=${state.currentUrl}, view=$url, document=${read(state, "document.URL")}, " +
                "owned=${(state.content as? WebContent.Data)?.appOwned}, script=${state.skinScript != null}, applied=${state.appliedSkinScript != null}, " +
                "actual=${read(state, expression)}, errors=${state.consoleMessages.map { it.message() }}", failure)
        }
    }

    private fun read(state: WebViewState, expression: String): String {
        val ready = CountDownLatch(1)
        var result: String? = null
        compose.runOnIdle { requireNotNull(state.webView).evaluateJavascript(expression) { result = it; ready.countDown() } }
        assertTrue("WebView JavaScript timed out", ready.await(10, TimeUnit.SECONDS))
        return Json.parseToJsonElement(requireNotNull(result)).jsonPrimitive.content
    }

    class ExportSink {
        val image = AtomicReference<String?>(null)
        @android.webkit.JavascriptInterface fun exportImage(value: String) { image.set(value) }
    }
}
