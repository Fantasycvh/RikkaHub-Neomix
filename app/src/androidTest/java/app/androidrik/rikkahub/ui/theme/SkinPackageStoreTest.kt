package app.androidrik.rikkahub.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.datastore.*
import app.androidrik.rikkahub.data.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SkinPackageStoreTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "skin-package-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    }
    private val registry = AppSkinRegistry()
    private val store = SkinPackageStore(context, registry)
    @After fun cleanup() { directory.deleteRecursively() }
    private fun skin(): AppSkin {
        val bytes = ByteArrayOutputStream().use { out ->
            val image = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            image.eraseColor(android.graphics.Color.BLUE)
            image.compress(Bitmap.CompressFormat.PNG, 100, out); image.recycle(); out.toByteArray()
        }
        val hash = BubbleTextureStore(context).importImage(bytes)
        val font = File(context.filesDir, "fonts/source.ttf").apply { parentFile!!.mkdirs() }
        target.resources.openRawResource(R.font.jetbrains_mono).use { input -> font.outputStream().use { input.copyTo(it) } }
        return AppSkin(typography = AppSkinTypography(ChatFontFamily.CUSTOM, "fonts/source.ttf", "Fixture"),
            resources = AppSkinResources(logo = hash, chatBackground = hash))
    }
    @Test fun fontsImagesAndCatalogSurviveExportInstallRestartAndUpgrade() {
        val original = skin()
        store.install(store.exportSkin("fixture", "Fixture", 1, original))
        val resolved = resolveAppSkin(DisplaySetting(appSkinId = "package:fixture"), registry.skins.value, emptyList()).skin
        assertEquals(original.resources, resolved.resources)
        assertNotNull(Typeface.createFromFile(File(context.filesDir, resolved.typography.customFontPath)))
        val exported = store.exportInstalled("fixture")
        assertEquals(2, SkinPackageArchive.decode(exported).assets.size)
        val restarted = AppSkinRegistry()
        SkinPackageStore(context, restarted).reload()
        assertTrue(restarted.skins.value.any { it.id == "package:fixture" })
        store.install(store.exportSkin("fixture", "Updated", 2, original.copy(shapes = original.shapes.copy(inputDp = 20f))))
        assertEquals(2, store.list().single().version)
        assertEquals(20f, resolveAppSkin(DisplaySetting(appSkinId = "package:fixture"), registry.skins.value, emptyList()).skin.shapes.inputDp)
        assertThrows(IllegalArgumentException::class.java) { store.install(exported) }
        assertEquals(2, store.list().single().version)
    }
    @Test fun invalidImagesLeaveOldVersionAndRegistryIntact() {
        store.install(store.exportSkin("fixture", "Fixture", 1, AppSkin()))
        val bad = "not an image".toByteArray(); val hash = textureDigest(bad)
        val archive = SkinPackageArchive(SkinPackageManifest(id = "fixture", name = "Bad", version = 2,
            skin = AppSkin(resources = AppSkinResources(logo = hash))), mapOf(hash to bad)).encode()
        assertThrows(IllegalArgumentException::class.java) { store.install(archive) }
        assertEquals(1, store.list().single().version)
        assertTrue(registry.skins.value.any { it.id == "package:fixture" && it.name.contains("v1") })
    }
    @Test fun rollbackRestoresHistoryAndKeepsVersionHighWaterAcrossRestart() {
        store.install(store.exportSkin("fixture", "One", 1, AppSkin()))
        store.install(store.exportSkin("fixture", "Three", 3, AppSkin(shapes = AppSkinShapes(inputDp = 24f))))
        assertEquals(listOf(3, 1), store.history("fixture").map { it.version })
        store.rollback("fixture", 1, 3)
        assertEquals(1, store.list().single().version)
        val restarted = SkinPackageStore(context, registry)
        restarted.reload()
        assertEquals(listOf(3, 1), restarted.history("fixture").map { it.version })
        assertThrows(IllegalArgumentException::class.java) { restarted.install(store.exportSkin("fixture", "Two", 2, AppSkin())) }
        assertThrows(IllegalArgumentException::class.java) { restarted.rollback("fixture", 3, 99) }
        restarted.rollback("fixture", 3, 1)
        assertEquals(3, restarted.list().single().version)
    }
    @Test fun missingHistoricalImageDoesNotChangeActiveVersion() {
        val old = skin()
        store.install(store.exportSkin("fixture", "One", 1, old))
        store.install(store.exportSkin("fixture", "Two", 2, AppSkin()))
        BubbleTextureStore(context).file(requireNotNull(old.resources.logo))!!.delete()
        assertThrows(Exception::class.java) { store.rollback("fixture", 1, 2) }
        assertEquals(2, store.list().single().version)
    }
    @Test fun historyRetentionIsBoundedWithoutDeletingCurrentVersion() {
        for (version in 1..12) store.install(store.exportSkin("fixture", "Fixture", version, AppSkin()))
        assertTrue(store.history("fixture").size <= 10)
        assertEquals(12, store.list().single().version)
        assertFalse(store.history("fixture").any { it.version == 1 })
    }
    @Test fun uninstallFallsBackAndKeepsUserOverridesAndTheirResources() {
        store.install(store.exportSkin("fixture", "Fixture", 1, skin()))
        val base = registry.skins.value.first { it.id == "package:fixture" }.skin
        val display = DisplaySetting(appSkinId = "package:fixture", appSkinOverrides = AppSkinOverrides(typography = base.typography))
        store.uninstall("fixture")
        assertTrue(store.list().isEmpty())
        assertFalse(registry.skins.value.any { it.id == "package:fixture" })
        assertEquals(base.typography, resolveAppSkin(display, registry.skins.value, emptyList()).skin.typography)
        assertTrue(File(context.filesDir, base.typography.customFontPath).isFile)
        assertNotNull(BubbleTextureStore(context).load(requireNotNull(base.resources.logo)))
    }
}
