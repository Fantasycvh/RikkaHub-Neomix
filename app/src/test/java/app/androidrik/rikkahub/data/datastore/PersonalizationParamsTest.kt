package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationParamsTest {
    @Test
    fun `avatar frame params json roundtrip`() {
        val params = AvatarFrameParams(
            kind = "dashed", colorHex = "#2E7D32", secondaryColorHex = "#FF8A00",
            strokeWidthDp = 3f, dashDp = 8f, gapDp = 5f,
        )
        val decoded = JsonInstant.decodeFromString<AvatarFrameParams>(
            JsonInstant.encodeToString(AvatarFrameParams.serializer(), params)
        )
        assertEquals(params, decoded)
    }

    @Test
    fun `avatar frame params tolerate unknown fields and blank kind`() {
        // 用户手写/未来版本的 JSON：未知字段忽略、kind 未知值保留原样（渲染端按 ring 回退）
        val decoded = JsonInstant.decodeFromString<AvatarFrameParams>(
            """{"kind":"sparkle","colorHex":"#112233","futureField":1}"""
        )
        assertEquals("sparkle", decoded.kind)
        assertEquals("#112233", decoded.colorHex)
        assertNull(decoded.secondaryColorHex)
    }

    @Test
    fun `chain of thought custom style roundtrip and null colors`() {
        val style = ChainOfThoughtCustomStyle(
            containerColorHex = null, accentColorHex = "#FF5500",
            cornerRadiusDp = 8f, timelineLineWidthDp = 2f, showTimeline = false,
        )
        val decoded = JsonInstant.decodeFromString<ChainOfThoughtCustomStyle>(
            JsonInstant.encodeToString(ChainOfThoughtCustomStyle.serializer(), style)
        )
        assertEquals(style, decoded)
        assertNull(decoded.containerColorHex)
        assertTrue(decoded.showTimeline == false)
    }

    @Test
    fun `chain of thought custom style partial json gets defaults`() {
        val decoded = JsonInstant.decodeFromString<ChainOfThoughtCustomStyle>(
            """{"accentColorHex":"#123456"}"""
        )
        assertEquals("#123456", decoded.accentColorHex)
        assertEquals(16f, decoded.cornerRadiusDp, 0f)
        assertEquals(1f, decoded.timelineLineWidthDp, 0f)
        assertEquals(true, decoded.showTimeline)
    }
}
