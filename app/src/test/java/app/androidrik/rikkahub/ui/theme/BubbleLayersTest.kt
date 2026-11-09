package app.androidrik.rikkahub.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.files.AppearanceArchive
import app.androidrik.rikkahub.data.files.textureDigest
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.BubbleCoordinateSpace
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BubbleLayersTest {
    @Test fun `legacy material projection is deterministic and keeps the original painter order`() {
        BubbleMaterial.entries.forEach { material ->
            val bubble = ChatBubble(material = material)
            assertEquals(bubble.effectiveStack(), bubble.effectiveStack())
            assertNull(bubble.normalized().stack)
        }
        assertEquals(listOf(BubbleLayerKind.SOLID, BubbleLayerKind.PATTERN), ChatBubble(material = BubbleMaterial.PATTERN).effectiveStack().layers.map { it.kind })
        assertEquals(listOf(BubbleLayerKind.SOLID, BubbleLayerKind.NOISE), ChatBubble(material = BubbleMaterial.NOISE).effectiveStack().layers.map { it.kind })
        assertTrue(ChatBubble(material = BubbleMaterial.GLASS).effectiveStack().glass)
    }

    @Test fun `layer order blend visibility and texture transforms survive settings and sharing`() {
        val texture = BubbleTexture("a".repeat(64), BubbleTextureMode.MIRROR, 128f, 5f, -7f)
        val stack = BubbleStack(glass = true, layers = listOf(
            BubbleLayer(id = "base", colorHex = "#112233"),
            BubbleLayer(id = "texture", kind = BubbleLayerKind.TEXTURE, opacity = 0.4f, blend = BubbleBlend.MULTIPLY, texture = texture),
            BubbleLayer(id = "noise", kind = BubbleLayerKind.NOISE, enabled = false)))
        val bubble = ChatBubble(stack = stack)
        val display = DisplaySetting().withChatAppearance(ChatAppearance(userBubble = bubble, assistantBubble = bubble))
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display))
        assertEquals(stack, decoded.toChatAppearance().userBubble.stack)
        assertEquals(stack, DisplaySetting().withAppearanceSnapshot(display.toAppearanceSnapshot()).toChatAppearance().assistantBubble.stack)
        assertEquals(setOf("a".repeat(64)), bubble.textureIds())
        assertEquals(BubbleStack(), ChatBubble(stack = BubbleStack()).effectiveStack())
    }

    @Test fun `normalization bounds layer count textures and duplicate ids without changing them on every read`() {
        val input = BubbleStack(layers = List(8) { BubbleLayer(id = "same", kind = BubbleLayerKind.TEXTURE,
            opacity = Float.NaN, texture = BubbleTexture("../../file", sizeDp = Float.POSITIVE_INFINITY, offsetXDp = -99999f)) })
        val result = input.normalized()
        assertEquals(4, result.layers.size)
        assertEquals(4, result.layers.map { it.id }.distinct().size)
        assertEquals(result, result.normalized())
        assertNull(result.layers.first().texture.assetId)
        assertEquals(64f, result.layers.first().texture.sizeDp)
        assertEquals(1f, result.layers.first().opacity)
    }

    @Test fun `archive roundtrip validates hashes and rejects traversal duplicate entries and missing manifests`() {
        val bytes = byteArrayOf(1, 2, 3)
        val archive = AppearanceArchive("{}", mapOf(textureDigest(bytes) to bytes))
        val decoded = AppearanceArchive.decode(archive.encode())
        assertEquals("{}", decoded.manifest)
        assertArrayEquals(bytes, decoded.textures.values.single())
        fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { z -> names.forEach { z.putNextEntry(ZipEntry(it)); z.write(bytes); z.closeEntry() } }
            out.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(zip("textures/../../bad.png")) }
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(zip("textures/${"a".repeat(64)}.png")) }
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(zip("unknown")) }
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(zip("textures/${textureDigest(bytes)}.png")) }
        val duplicate = zip("manifest.json", "manifesx.json").toString(Charsets.ISO_8859_1)
            .replace("manifesx.json", "manifest.json").toByteArray(Charsets.ISO_8859_1)
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(duplicate) }
        val oversized = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write(ByteArray(AppearanceArchive.MAX_MANIFEST + 1)); z.closeEntry()
            }
            out.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { AppearanceArchive.decode(oversized) }
    }

    @Test fun `adjacent lazy slices share phase while scrolling and after anchor disposal`() {
        val space = BubbleCoordinateSpace()
        val first = space.attach("one"); val second = space.attach("two")
        space.update("one", Offset(20f, 100f), Size(300f, 80f))
        space.update("two", Offset(20f, 180f), Size(300f, 80f))
        assertEquals(Offset.Zero, first.offset())
        assertEquals(Offset(0f, 80f), second.offset())
        space.update("two", Offset(20f, 130f), Size(300f, 80f))
        space.update("one", Offset(20f, 50f), Size(300f, 80f))
        assertEquals(Offset(0f, 80f), second.offset())
        space.detach("one")
        space.update("two", Offset(20f, 70f), Size(300f, 80f))
        assertEquals(Offset(0f, 80f), second.offset())
    }
}
