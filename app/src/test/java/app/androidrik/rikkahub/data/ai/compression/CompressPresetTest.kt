package app.androidrik.rikkahub.data.ai.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩配置档的纯函数（匹配 / 重名校验 / 钳制）。
 *
 * 设计约束（`docs/design/design-compress-presets.md`）：真相源是**数值**，档只是填充快捷方式，
 * 故「当前档位」由数值反推而非存选中态——改一个数字就应变成「自定义」。
 */
class CompressPresetTest {

    private val custom = listOf(
        CompressPreset(name = "我的方案", retainPercent = 35, targetTokens = 1500),
        CompressPreset(name = "激进", retainPercent = 15, targetTokens = 800),
    )

    // ---- 匹配（数值 → 档位） ----

    @Test
    fun `matches built-in preset by exact values`() {
        val match = matchCompressPreset(
            BuiltInCompressPreset.DEFAULT.retainPercent,
            BuiltInCompressPreset.DEFAULT.targetTokens,
            customPresets = emptyList(),
        )
        assertEquals(CompressPresetMatch.BuiltIn(BuiltInCompressPreset.DEFAULT), match)
    }

    @Test
    fun `matches each built-in preset`() {
        BuiltInCompressPreset.entries.forEach { preset ->
            assertEquals(
                "should match $preset",
                CompressPresetMatch.BuiltIn(preset),
                matchCompressPreset(preset.retainPercent, preset.targetTokens, emptyList()),
            )
        }
    }

    @Test
    fun `matches custom preset by exact values`() {
        assertEquals(
            CompressPresetMatch.Custom(custom[0]),
            matchCompressPreset(35, 1500, custom),
        )
    }

    @Test
    fun `unmatched values yield Values marker`() {
        // 只改一个数字就该变成「自定义」——这是「不存选中态」的核心语义
        assertEquals(
            CompressPresetMatch.Values,
            matchCompressPreset(BuiltInCompressPreset.DEFAULT.retainPercent + 1,
                BuiltInCompressPreset.DEFAULT.targetTokens, custom),
        )
        assertEquals(
            CompressPresetMatch.Values,
            matchCompressPreset(BuiltInCompressPreset.DEFAULT.retainPercent,
                BuiltInCompressPreset.DEFAULT.targetTokens + 1, custom),
        )
    }

    @Test
    fun `built-in wins when values collide with a custom preset`() {
        // 允许数值重复，高亮按首个匹配（内置优先）——不做特殊处理
        val colliding = CompressPreset(
            name = "撞车",
            retainPercent = BuiltInCompressPreset.DEFAULT.retainPercent,
            targetTokens = BuiltInCompressPreset.DEFAULT.targetTokens,
        )
        val match = matchCompressPreset(
            BuiltInCompressPreset.DEFAULT.retainPercent,
            BuiltInCompressPreset.DEFAULT.targetTokens,
            listOf(colliding),
        )
        assertEquals(CompressPresetMatch.BuiltIn(BuiltInCompressPreset.DEFAULT), match)
    }

    @Test
    fun `built-in presets are pinned to fork defaults`() {
        // 默认档必须与 fork 一直以来的默认值一致，否则升级后老用户的高亮会莫名变成「自定义」
        assertEquals(DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT, BuiltInCompressPreset.DEFAULT.retainPercent)
        assertEquals(DEFAULT_AUTO_COMPRESS_TARGET_TOKENS, BuiltInCompressPreset.DEFAULT.targetTokens)
    }

    @Test
    fun `all built-in presets stay within clamp ranges`() {
        BuiltInCompressPreset.entries.forEach { preset ->
            assertTrue(
                "$preset retain out of range",
                preset.retainPercent in AUTO_COMPRESS_RETAIN_PERCENT_RANGE,
            )
            assertTrue(
                "$preset target out of range",
                preset.targetTokens in COMPRESS_TARGET_TOKENS_RANGE,
            )
        }
    }

    // ---- 重名校验 ----

    @Test
    fun `name must be non-blank`() {
        assertFalse(isCompressPresetNameAvailable("", custom, emptyList()))
        assertFalse(isCompressPresetNameAvailable("   ", custom, emptyList()))
    }

    @Test
    fun `name must not collide with existing custom preset`() {
        assertFalse(isCompressPresetNameAvailable("我的方案", custom, emptyList()))
        // 忽略大小写与首尾空白
        assertFalse(isCompressPresetNameAvailable("  我的方案  ", custom, emptyList()))
    }

    @Test
    fun `name must not collide with built-in names`() {
        assertFalse(isCompressPresetNameAvailable("Default", custom, listOf("Default", "Compact")))
        assertFalse(isCompressPresetNameAvailable("default", custom, listOf("Default", "Compact")))
    }

    @Test
    fun `editing a preset may keep its own name`() {
        // excludeIndex 指向自己时，允许保留原名（否则改名对话框里保存会被误判重名）
        assertTrue(
            isCompressPresetNameAvailable("我的方案", custom, emptyList(), excludeIndex = 0),
        )
        // 但仍不能与他人重名
        assertFalse(
            isCompressPresetNameAvailable("激进", custom, emptyList(), excludeIndex = 0),
        )
    }

    @Test
    fun `fresh name is available`() {
        assertTrue(isCompressPresetNameAvailable("全新方案", custom, listOf("Default")))
    }

    // ---- 钳制 ----

    @Test
    fun `clamped brings values into range`() {
        val tooSmall = CompressPreset("a", retainPercent = 1, targetTokens = 10).clamped()
        assertEquals(AUTO_COMPRESS_RETAIN_PERCENT_RANGE.first, tooSmall.retainPercent)
        assertEquals(COMPRESS_TARGET_TOKENS_RANGE.first, tooSmall.targetTokens)

        val tooBig = CompressPreset("b", retainPercent = 999, targetTokens = 999_999).clamped()
        assertEquals(AUTO_COMPRESS_RETAIN_PERCENT_RANGE.last, tooBig.retainPercent)
        assertEquals(COMPRESS_TARGET_TOKENS_RANGE.last, tooBig.targetTokens)
    }

    @Test
    fun `clamped leaves in-range values untouched`() {
        val ok = CompressPreset("c", retainPercent = 40, targetTokens = 2000)
        assertEquals(ok, ok.clamped())
    }
}
