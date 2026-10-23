package app.androidrik.rikkahub

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only foreground accessibility snapshot; no activity launch or user action. */
@RunWith(AndroidJUnit4::class)
class ReplyScreenDiagnosticTest {
    @Test fun snapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank()) lines.add("${node.className}: text=$text desc=$desc")
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(automation.rootInActiveWindow, 0)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", lines.joinToString("\n")) })
    }
}
