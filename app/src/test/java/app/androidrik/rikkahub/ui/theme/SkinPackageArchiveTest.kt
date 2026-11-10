package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.*

class SkinPackageArchiveTest {
    private val manifest = SkinPackageManifest(id = "fixture", name = "Fixture", version = 1, skin = AppSkin())
    @Test fun emptyPackageRoundtripsAndResourceOverridesRemainIndependent() {
        val archive = SkinPackageArchive(manifest, emptyMap())
        assertEquals(archive, SkinPackageArchive.decode(archive.encode()))
        val resources = AppSkinResources(logo = "a".repeat(64))
        val overrides = AppSkinOverrides().withEdits(AppSkin(), AppSkin(resources = resources))
        assertEquals(resources, overrides.resources); assertNull(overrides.typography)
        assertEquals(AppSkinResources(), AppSkinResources(logo = "../escape").normalized())
    }
    @Test fun invalidMetadataAndLocalFontPathsAreRejected() {
        for (invalid in listOf(manifest.copy(id = "../escape"), manifest.copy(version = 0), manifest.copy(formatVersion = 2),
            manifest.copy(skin = AppSkin(typography = AppSkinTypography(customFontPath = "fonts/private.ttf"))),
            manifest.copy(skin = AppSkin(typography = AppSkinTypography(family = ChatFontFamily.CUSTOM))))) {
            assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive(invalid, emptyMap()).encode() }
        }
    }
    @Test fun missingUnreferencedAndCorruptAssetsAreRejected() {
        val bytes = "asset".toByteArray(); val hash = textureDigest(bytes)
        val image = manifest.copy(skin = AppSkin(resources = AppSkinResources(logo = hash)))
        assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive(image, emptyMap()).encode() }
        assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive(manifest, mapOf(hash to bytes)).encode() }
        assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive(image, mapOf(hash to "bad".toByteArray())).encode() }
    }
    @Test fun zipTraversalAndCompressionBombEntriesAreRejectedBeforeInstall() {
        fun zip(name: String, data: ByteArray) = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry() }; out.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive.decode(zip("assets/../../escape", byteArrayOf(1))) }
        assertThrows(IllegalArgumentException::class.java) { SkinPackageArchive.decode(zip("manifest.json", ByteArray(AppearanceArchive.MAX_MANIFEST + 1))) }
    }
}
