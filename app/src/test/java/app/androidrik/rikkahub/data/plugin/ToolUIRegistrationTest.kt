package app.androidrik.rikkahub.data.plugin

import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRegistry
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRenderer
import org.junit.Assert.assertSame
import org.junit.Test

class ToolUIRegistrationTest {
    private fun renderer(name: String) = object : ToolUIRenderer { override val toolName = name }

    @Test fun `old cleanup cannot remove new renderer even when renderer object is reused`() {
        val name = "test.registration.reused"
        val fallback = ToolUIRegistry.resolve(name)
        val shared = renderer(name)
        val old = ToolUIRegistry.register(shared)
        val replacement = ToolUIRegistry.register(shared)
        try {
            old.close()
            old.close()
            assertSame(shared, ToolUIRegistry.resolve(name))
            replacement.close()
            assertSame(fallback, ToolUIRegistry.resolve(name))
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Test fun `overridden renderer is restored unless its owner already released it`() {
        val name = "test.registration.layers"
        val fallback = ToolUIRegistry.resolve(name)
        val first = renderer(name)
        val second = renderer(name)
        val firstHandle = ToolUIRegistry.register(first)
        val secondHandle = ToolUIRegistry.register(second)
        try {
            assertSame(second, ToolUIRegistry.resolve(name))
            secondHandle.close()
            assertSame(first, ToolUIRegistry.resolve(name))
            firstHandle.close()
            assertSame(fallback, ToolUIRegistry.resolve(name))
        } finally {
            firstHandle.close()
            secondHandle.close()
        }
    }
}
