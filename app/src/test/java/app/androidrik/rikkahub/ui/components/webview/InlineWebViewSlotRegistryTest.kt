package app.androidrik.rikkahub.ui.components.webview

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InlineWebViewSlotRegistryTest {

    @Before
    fun setUp() {
        InlineWebViewSlotRegistry.clearForTest()
    }

    @After
    fun tearDown() {
        InlineWebViewSlotRegistry.clearForTest()
    }

    @Test
    fun `separate component instances never share or release each others slot`() {
        val first = Any()
        val second = Any()
        val third = Any()
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot(first))
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot(second))
        assertFalse(InlineWebViewSlotRegistry.tryAcquireAutoSlot(third))
        InlineWebViewSlotRegistry.releaseAutoSlot(first)
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot(third))
        InlineWebViewSlotRegistry.releaseAutoSlot(first)
        assertFalse(InlineWebViewSlotRegistry.tryAcquireAutoSlot(Any()))
    }

    @Test
    fun `tryAcquireAutoSlot caps concurrent auto webviews and allows re-entry or post-release acquisition`() {
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot("slot-1", maxSlots = 2))
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot("slot-1", maxSlots = 2))
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot("slot-2", maxSlots = 2))
        assertFalse(InlineWebViewSlotRegistry.tryAcquireAutoSlot("slot-3", maxSlots = 2))

        InlineWebViewSlotRegistry.releaseAutoSlot("slot-1")
        assertTrue(InlineWebViewSlotRegistry.tryAcquireAutoSlot("slot-3", maxSlots = 2))
    }
}
