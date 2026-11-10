package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.*
import app.androidrik.rikkahub.data.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class SkinTrialControllerTest {
    private val skin = AppSkin(shapes = AppSkinShapes(inputDp = 20f))
    private val target = SkinSelection(null, AppSkinOverrides.from(skin))
    @Test fun previewCancelAndReplacementNeverWriteSettings() = runBlocking {
        val controller = SkinTrialController()
        val display = DisplaySetting()
        controller.start(display, target, skin, "draft")
        val old = controller.trial.value!!.token
        controller.start(display, target, skin, "replacement")
        controller.cancel(old)
        assertNotNull(controller.trial.value)
        var writes = 0
        try { controller.apply({ writes++ }, token = old); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, writes)
        controller.cancel(); assertNull(controller.trial.value)
        assertEquals(DisplaySetting(), display)
    }
    @Test fun applyAndUndoPreserveConcurrentUnrelatedPreferences() = runBlocking {
        val controller = SkinTrialController()
        val initial = DisplaySetting()
        var display = initial
        controller.start(display, target, skin, "draft")
        display = display.copy(topBarStyle = TopBarChromeStyle.entries.last())
        controller.apply({ transform -> display = transform(display) })
        assertEquals(target, SkinSelection.from(display)); assertNull(controller.trial.value)
        controller.undo({ transform -> display = transform(display) })
        assertEquals(SkinSelection.from(initial), SkinSelection.from(display))
        assertEquals(TopBarChromeStyle.entries.last(), display.topBarStyle)
    }
    @Test fun changedSettingsOrChangedProviderRejectApplyAndUndo() = runBlocking {
        val controller = SkinTrialController()
        var display = DisplaySetting()
        controller.start(display, target, skin, "draft")
        display = display.copy(appSkinId = "another:skin")
        try { controller.apply({ display = it(display) }); fail() } catch (_: IllegalStateException) { }
        assertEquals("another:skin", display.appSkinId)
        controller.cancel()
        controller.start(display, target, skin, "draft")
        try { controller.apply({ display = it(display) }, { AppSkin() }); fail() } catch (_: IllegalStateException) { }
        assertNotNull(controller.trial.value)
        controller.apply({ display = it(display) })
        display = display.copy(appSkinOverrides = AppSkinOverrides())
        try { controller.undo({ display = it(display) }); fail() } catch (_: IllegalStateException) { }
        assertEquals(AppSkinOverrides(), display.appSkinOverrides)
    }
    @Test fun failedPersistenceLeavesPreviewRetryableAndRestartHasNoPreview() = runBlocking {
        val controller = SkinTrialController()
        controller.start(DisplaySetting(), target, skin, "draft")
        try { controller.apply({ error("disk failed") }); fail() } catch (_: IllegalStateException) { }
        assertNotNull(controller.trial.value); assertNull(controller.undo.value); assertFalse(controller.busy.value)
        assertNull(SkinTrialController().trial.value)
    }
    @Test fun unavailablePresetCannotBeConfirmedEvenWhenItsFallbackLooksIdentical() = runBlocking {
        val controller = SkinTrialController()
        var display = DisplaySetting()
        controller.start(display, SkinSelection("missing:skin", AppSkinOverrides()), AppSkin(), "missing")
        try { controller.apply({ display = it(display) }); fail() } catch (_: IllegalStateException) { }
        assertEquals(DisplaySetting(), display)
        assertNotNull(controller.trial.value)
    }
    @Test fun disposalDuringFailedWriteStillClearsThePreview() = runBlocking {
        val controller = SkinTrialController()
        controller.start(DisplaySetting(), target, skin, "draft")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            try { controller.apply({ entered.complete(Unit); release.await(); error("disk failed") }) }
            catch (_: IllegalStateException) { }
        }
        entered.await()
        controller.cancel()
        release.complete(Unit); job.join()
        assertNull(controller.trial.value); assertNull(controller.undo.value)
    }
    @Test fun onlyClosingTheLastHostCancelsAndClosedLeasesCannotCancelANewTrial() {
        val controller = SkinTrialController()
        val first = controller.attachHost(); val second = controller.attachHost()
        controller.start(DisplaySetting(), target, skin, "draft")
        first.close(); assertNotNull(controller.trial.value)
        second.close(); assertNull(controller.trial.value)
        val next = controller.attachHost()
        controller.start(DisplaySetting(), target, skin, "next")
        first.close(); assertNotNull(controller.trial.value)
        next.close(); assertNull(controller.trial.value)
    }
}
