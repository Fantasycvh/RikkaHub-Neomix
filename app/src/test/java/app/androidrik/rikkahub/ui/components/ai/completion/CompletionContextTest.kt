package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class CompletionContextTest {
    @Test fun `long document copies only the bounded cursor window`() {
        val backing = "文".repeat(100_000) + " @路径/笔记"
        val text = object : CharSequence {
            override val length = backing.length
            override fun get(index: Int) = backing[index]
            override fun toString(): String = error("must not materialize the whole editor")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                error("must not depend on the editor buffer's subsequence implementation")
            }
        }
        val context = completionContext(text, TextRange(text.length))!!
        val mention = findWorkspaceMention(context)!!
        assertEquals("路径/笔记", mention.query)
        assertEquals(TextRange(100_001, text.length), mention.range)
    }

    @Test fun `cursor in middle uses absolute replacement offsets and omits suffix`() {
        val prefix = "旧文".repeat(20_000) + "\n"
        val text = prefix + "@src" + "后文".repeat(20_000)
        val context = completionContext(text, TextRange(prefix.length + 4))!!
        assertEquals(TextRange(prefix.length, prefix.length + 4), findWorkspaceMention(context)!!.range)
        assertFalse(context.text.contains("后文"))
    }

    @Test fun `selection suppresses completion without copying text`() {
        assertNull(completionContext("@src", TextRange(0, 4)))
    }

    @Test fun `emails whitespace and clipped mention tokens do not complete`() {
        listOf("person@example", "@src more", "a".repeat(2000) + "@file", "@" + "x".repeat(2000)).forEach {
            assertNull(findWorkspaceMention(completionContext(it, TextRange(it.length))!!))
        }
    }

    @Test fun `boundaries directory paths and empty mention remain supported`() {
        listOf("@", "hello (@src/", "文\n@/workspace/main.kt", "\"@中文").forEach {
            assertNotNull(findWorkspaceMention(completionContext(it, TextRange(it.length))!!))
        }
    }

    @Test fun `window clipping does not start inside a surrogate pair`() {
        val text = "a😀" + "x".repeat(MAX_COMPLETION_CONTEXT)
        val context = completionContext(text, TextRange(text.length))!!
        assertFalse(context.text.first().isLowSurrogate())
    }
}
