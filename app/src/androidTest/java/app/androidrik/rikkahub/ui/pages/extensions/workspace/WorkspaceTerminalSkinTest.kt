package app.androidrik.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import androidx.compose.foundation.layout.size
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.theme.*
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import com.termux.terminal.TextStyle
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch

/** Exercises the production AndroidView host using an in-memory transport; never starts a shell. */
@RunWith(AndroidJUnit4::class)
class WorkspaceTerminalSkinTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val transport = MemoryTransport()
    private var session: TerminalSession? = null
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    @After fun cleanup() { transport.kill(); transport.close() }

    @Test fun liveSkinResizesTheRealViewWithoutRestartingSessionOrOverwritingOscColors() {
        var skin by mutableStateOf(AppSkin(embedded = AppSkinEmbedded(terminal = AppSkinTerminal(backgroundHex = "#112233"))))
        var client: WorkspaceTerminalSessionClient? = null
        compose.setContent {
            val currentClient = remember { WorkspaceTerminalSessionClient(target, {}, {}) }
            val currentSession = remember { TerminalSession(transport, 100, currentClient) }
            client = currentClient; session = currentSession
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                WorkspaceTerminalTabContent(WorkspaceTerminalTab(1, 1, currentSession, currentClient), Modifier.size(300.dp, 320.dp))
            }
        }
        compose.waitUntil(10_000) { session?.emulator != null && client?.terminalView != null }
        val firstSession = requireNotNull(session)
        val firstEmulator = requireNotNull(firstSession.emulator)
        var columns = 0
        var view: Any? = null
        compose.runOnIdle {
            columns = firstEmulator.mColumns; view = client!!.terminalView
            assertEquals(0xff112233.toInt(), firstEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            val bytes = "Hello\u001b]11;#abcdef\u0007".toByteArray()
            firstEmulator.append(bytes, bytes.size)
            skin = skin.copy(embedded = skin.embedded.copy(terminal = AppSkinTerminal(
                backgroundHex = "#445566", cursorHex = "#654321", fontSizeSp = 22f)))
        }
        compose.waitUntil(10_000) { firstEmulator.mColumns < columns }
        compose.runOnIdle {
            assertSame(firstSession, session); assertSame(firstEmulator, session!!.emulator)
            assertSame(view, client!!.terminalView)
            assertTrue(firstEmulator.screen.transcriptText.contains("Hello"))
            assertEquals(0xffabcdef.toInt(), firstEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            assertEquals(0xff654321.toInt(), firstEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR])
            val reset = "\u001b]111\u0007".toByteArray()
            firstEmulator.append(reset, reset.size)
            assertEquals(0xff445566.toInt(), firstEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        }
    }

    private class MemoryTransport : TerminalTransport {
        private val finished = CountDownLatch(1)
        private val producer = PipedOutputStream()
        private val input = PipedInputStream(producer)
        private val output = ByteArrayOutputStream()
        override fun start(columns: Int, rows: Int) = Unit
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun setWindowSize(columns: Int, rows: Int) = Unit
        override fun waitFor(): Int { finished.await(); return 0 }
        override fun kill() { runCatching { producer.close() }; finished.countDown() }
        override fun close() { kill(); runCatching { input.close() }; output.close() }
    }
}
