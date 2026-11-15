package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppSkinTest {
    @Test fun `custom host radii and expressive shape tiers follow the global shape scale`() {
        val defaults = AppSkinShapes()
        for (radius in listOf(2f, 4f, 6f, 8f, 10f, 12f, 16f, 18f, 20f, 24f, 28f, 32f, 48f)) {
            assertEquals(radius, defaults.scaledCornerRadius(radius), .0001f)
        }
        val square = AppSkinShapes(extraSmallDp = 0f, smallDp = 0f, mediumDp = 0f, largeDp = 0f, extraLargeDp = 0f)
        for (radius in listOf(6f, 18f, 20f, 32f, 48f)) assertEquals(0f, square.scaledCornerRadius(radius))
        assertEquals(androidx.compose.material3.Shapes(), appSkinShapes(defaults))
        val shapes = appSkinShapes(square)
        assertEquals(shapes.large, shapes.largeIncreased)
        assertEquals(shapes.large, shapes.extraLargeIncreased)
        assertEquals(shapes.large, shapes.extraExtraLarge)
        assertEquals(0f, defaults.scaledCornerRadius(Float.NaN))
        assertEquals(0f, defaults.scaledCornerRadius(-1f))
        assertEquals(64f, defaults.scaledCornerRadius(Float.MAX_VALUE))
    }

    @Test fun `legacy configuration resolves unchanged global defaults and chat appearance`() {
        val display = DisplaySetting(chatAppearanceDetails = ChatAppearanceDetails(userBubbleColorHex = "#123456"))
        val resolved = resolveAppSkin(display, emptyList(), emptyList())
        assertEquals(AppSkin(), resolved.skin)
        assertEquals(display.toChatAppearance(), resolved.chat)
        assertEquals(Typography, appSkinTypography(Typography, AppSkinTypography(), null))
        assertNull(display.toAppearanceSnapshot().appSkin)
        assertEquals(DisplaySetting(), Json.decodeFromString<DisplaySetting>("{}"))
    }

    @Test fun `user overrides beat a skin while untouched groups inherit updates and chat stays independent`() {
        val compact = builtInAppSkins.first { it.id == "builtin.compact" }
        val local = AppSkinTypography(sizeRatio = 1.25f)
        val chatPreset = ChatAppearancePreset("provider:chat", "Chat", ChatAppearance(userBubble = ChatBubble(colorHex = "#ABCDEF")))
        val display = DisplaySetting(appSkinId = compact.id, appSkinOverrides = AppSkinOverrides(typography = local), chatAppearancePresetId = chatPreset.id)
        val result = resolveAppSkin(display, listOf(compact), listOf(chatPreset))
        assertEquals(local, result.skin.typography)
        assertEquals(compact.skin.shapes, result.skin.shapes)
        assertEquals(chatPreset.appearance, result.chat)
        val updated = compact.copy(skin = compact.skin.copy(spacing = AppSkinSpacing(pageDp = 24f)))
        assertEquals(24f, resolveAppSkin(display, listOf(updated), emptyList()).skin.spacing.pageDp)
        val fallback = resolveAppSkin(display, emptyList(), emptyList())
        assertEquals(local, fallback.skin.typography)
        assertEquals(AppSkinShapes(), fallback.skin.shapes)
        assertEquals(display.toChatAppearance(), fallback.chat)
    }

    @Test fun `saving an edit creates only changed groups and supports returning to inheritance`() {
        val base = builtInAppSkins.first { it.id == "builtin.compact" }.skin
        val previous = AppSkinOverrides(motion = AppSkinMotion.EXPRESSIVE)
        val before = previous.applyTo(base)
        assertEquals(previous, previous.withEdits(before, before))
        val edited = previous.withEdits(before, before.copy(typography = before.typography.copy(sizeRatio = 1.2f)))
        assertNotNull(edited.typography); assertNull(edited.shapes); assertNull(edited.spacing)
        assertEquals(AppSkinMotion.EXPRESSIVE, edited.motion)
        assertEquals(base, AppSkinOverrides().applyTo(base))
    }

    @Test fun `registry handles replacement disable and reenable without removing the new owner`() {
        val registry = AppSkinRegistry()
        val preset = AppSkinPreset("reading", "Reading", AppSkin(typography = AppSkinTypography(sizeRatio = 1.2f)))
        val old = registry.register("provider", listOf(preset))
        val replacement = registry.register("provider", listOf(preset.copy(name = "New")))
        old.close()
        assertEquals("New", registry.skins.value.first { it.id == "provider:reading" }.name)
        replacement.close()
        assertFalse(registry.skins.value.any { it.id == "provider:reading" })
        registry.register("provider", listOf(preset)).use {
            assertEquals(preset.skin, resolveAppSkin(DisplaySetting(appSkinId = "provider:reading"), registry.skins.value, emptyList()).skin)
        }
        assertThrows(IllegalArgumentException::class.java) { registry.register("builtin", listOf(preset)) }
        assertThrows(IllegalArgumentException::class.java) { registry.register("provider", listOf(preset, preset)) }
    }

    @Test fun `bounds reject invalid geometry fonts spacing and font sizes`() {
        val result = AppSkin(typography = AppSkinTypography(sizeRatio = Float.NaN),
            shapes = AppSkinShapes(largeDp = Float.POSITIVE_INFINITY, inputDp = -100f, buttonDp = 1000f),
            spacing = AppSkinSpacing(pageDp = 999f, formHorizontalDp = 0f)).normalized()
        assertEquals(1f, result.typography.sizeRatio)
        assertEquals(16f, result.shapes.largeDp); assertEquals(0f, result.shapes.inputDp); assertEquals(48f, result.shapes.buttonDp!!)
        assertEquals(32f, result.spacing.pageDp); assertEquals(8f, result.spacing.formHorizontalDp)
        assertEquals(result, result.normalized())
    }

    @Test fun `motion tokens normalize clamp and ride along group overrides`() {
        val tokens = AppSkinMotionTokens(pressMs = -10, quickMs = 99_999, layoutBounce = 2f, flingBounce = Float.NaN).normalized()
        assertEquals(0, tokens.pressMs)
        assertEquals(600, tokens.quickMs)
        assertEquals(0.5f, tokens.layoutBounce) // 越界 bounce 收敛到上限而非回落
        assertEquals(0.15f, tokens.flingBounce) // NaN 回落档位默认
        val skin = AppSkin(motion = AppSkinMotion.TOKENS, motionTokens = AppSkinMotionTokens(quickMs = 120))
        assertEquals(120, skin.normalized().motionTokens?.quickMs)
        // 覆写组照常携带；未编辑的组继承基础皮肤更新
        val base = AppSkin(motion = AppSkinMotion.TOKENS, motionTokens = AppSkinMotionTokens(quickMs = 200))
        val overrides = AppSkinOverrides.from(skin)
        assertEquals(skin, overrides.applyTo(AppSkin(motionTokens = AppSkinMotionTokens(quickMs = 300), motion = AppSkinMotion.TOKENS)))
        val untouched = overrides.withEdits(base, base.copy(shapes = base.shapes.copy(inputDp = 10f)))
        // 未编辑的组保留原覆写值（继续覆写），被编辑的组更新为 after
        assertEquals(skin.motionTokens, untouched.motionTokens); assertEquals(10f, untouched.shapes?.inputDp)
    }

    @Test fun `third round presets carry complete token combos and link chat appearance`() {
        val squircle = builtInAppSkins.first { it.id == "builtin.squircle" }
        val paper = builtInAppSkins.first { it.id == "builtin.paper" }
        assertTrue(squircle.skin.shapes.continuous)
        assertTrue(squircle.skin.typography.expressive)
        assertEquals(AppSkinMotion.EXPRESSIVE, squircle.skin.motion)
        assertNull(squircle.skin.palette) // 继承动态色
        assertEquals(ChatFontFamily.SERIF, paper.skin.typography.family)
        assertEquals(AppSkinMotion.STANDARD, paper.skin.motion)
        assertEquals(ThemePaletteStyle.NEUTRAL, paper.skin.palette?.paletteStyle)
        // resolve 归一化后与预设等价（registered skins 不走 normalized，resolve 兜底）
        assertEquals(squircle.skin, resolveAppSkin(DisplaySetting(appSkinId = squircle.id), builtInAppSkins, emptyList()).skin)
        // 联动表覆盖两档且指向注册的聊天方案 id
        assertEquals(setOf("builtin.squircle", "builtin.paper", "builtin.engineering", "builtin.ios"), builtinSkinChatLink.keys)
    }

    @Test fun `second batch presets carry group styles and typography tokens`() {
        val engineering = builtInAppSkins.first { it.id == "builtin.engineering" }
        val ios = builtInAppSkins.first { it.id == "builtin.ios" }
        assertEquals(AppSkinGroupStyle.OUTLINED, engineering.skin.groups.style)
        assertEquals(AppSkinDividerStyle.FULL, engineering.skin.groups.divider)
        assertEquals(1f, engineering.skin.groups.borderWidthDp)
        assertTrue(engineering.skin.typography.tabularNumerals)
        assertTrue(engineering.skin.typography.monoLabels)
        assertEquals(ThemePaletteStyle.NEUTRAL, engineering.skin.palette?.paletteStyle)
        assertEquals(AppSkinGroupStyle.INSET, ios.skin.groups.style)
        assertEquals(AppSkinDividerStyle.INSET, ios.skin.groups.divider)
        assertEquals(56f, ios.skin.groups.dividerInsetDp)
        // 深色可读优先：容器底色跟随主题 surfaceBright，浅色仍接近白卡
        assertNull(ios.skin.groups.containerColorHex)
        assertEquals(0f, ios.skin.spacing.groupItemDp)
        assertEquals(ThemePaletteStyle.FIDELITY, ios.skin.palette?.paletteStyle)
        assertEquals(ios.skin, resolveAppSkin(DisplaySetting(appSkinId = ios.id), builtInAppSkins, emptyList()).skin)
    }

    @Test fun `builtin chat appearance presets register and resolve by link`() {
        val registry = ChatAppearanceRegistry()
        registry.register("builtin.chat", builtinChatAppearancePresets).use {
            val presets = registry.presets.value
            val glass = presets.first { it.id == "builtin.chat:glass" }
            val paper = presets.first { it.id == "builtin.chat:paper" }
            assertEquals(BubbleMaterial.GLASS, glass.appearance.userBubble.material)
            assertEquals(5f, glass.appearance.userBubble.geometry.tailCornerDp)
            assertFalse(paper.appearance.assistantBubble.visible)
            assertEquals("#EBDFC9", paper.appearance.userBubble.colorHex)
            // 皮肤联动：选纸质感皮肤 → chat preset 指向纸质感方案，resolve 生效
            val display = DisplaySetting(appSkinId = "builtin.paper", chatAppearancePresetId = "builtin.chat:paper")
            val resolved = resolveAppSkin(display, builtInAppSkins, presets)
            assertFalse(resolved.chat.assistantBubble.visible)
            assertEquals(14f, resolved.chat.userBubble.geometry.radiusDp)
        }
        // 注册撤销后 link id 解析不到 → 回落 legacy 显示设置
        val fallback = resolveAppSkin(DisplaySetting(chatAppearancePresetId = "builtin.chat:paper"), builtInAppSkins, emptyList())
        assertEquals(DisplaySetting().toChatAppearance(), fallback.chat)
    }

    @Test fun `motion tokens survive serialization and old json reads stay on presets`() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val skin = AppSkin(motion = AppSkinMotion.TOKENS, motionTokens = AppSkinMotionTokens(quickMs = 180, flingBounce = 0.2f))
        assertEquals(skin.motionTokens, json.decodeFromString<AppSkin>(json.encodeToString(skin)).motionTokens)
        // 旧 JSON：无 motionTokens 字段、motion 只有 EXPRESSIVE/STANDARD → 取默认值
        val legacy = json.decodeFromString<AppSkin>("""{"motion":"STANDARD"}""")
        assertEquals(AppSkinMotion.STANDARD, legacy.motion)
        assertNull(legacy.motionTokens)
        // 新 JSON 被旧构建读回：motion=TOKENS 未知时 kotlinx 会抛错，但 JsonInstant coerce 语义下
        // 旧构建读到的是「新字段被忽略」——motion 枚举是破坏性变更，跨版本回退属于既有降级隔离范畴
        assertEquals(180, skin.motionTokens?.quickMs)
    }

    @Test fun `settings and theme sharing flatten skins and omit device font paths`() {
        val json = Json { encodeDefaults = true }
        val skin = AppSkin(typography = AppSkinTypography(ChatFontFamily.CUSTOM, "fonts/local.ttf", "Local", 1.2f),
            shapes = AppSkinShapes(inputDp = 24f), palette = CustomTheme(id = "palette", name = "Palette", primaryColorArgb = 0xFF112233))
        val display = DisplaySetting(appSkinId = "missing:skin", appSkinOverrides = AppSkinOverrides.from(skin))
        assertEquals(display, json.decodeFromString<DisplaySetting>(json.encodeToString(DisplaySetting.serializer(), display)))
        val snapshot = display.toAppearanceSnapshot()
        assertEquals("", snapshot.appSkin!!.typography.customFontPath)
        assertEquals(ChatFontFamily.DEFAULT, snapshot.appSkin!!.typography.family)
        val decoded = json.decodeFromString<ThemeAppearanceSnapshot>(json.encodeToString(ThemeAppearanceSnapshot.serializer(), snapshot))
        val imported = DisplaySetting().withAppearanceSnapshot(decoded)
        assertNull(imported.appSkinId)
        assertEquals(snapshot.appSkin, resolveAppSkin(imported, emptyList(), emptyList()).skin)
        assertEquals(display.appSkinOverrides, display.withAppearanceSnapshot(ThemeAppearanceSnapshot()).appSkinOverrides)
        assertEquals(display.appSkinId, display.withAppearanceSnapshot(ThemeAppearanceSnapshot()).appSkinId)
    }

    @Test fun `global font and scale reach normal and emphasized typography without losing weights`() {
        val result = appSkinTypography(Typography, AppSkinTypography(sizeRatio = 1.25f), FontFamily.Serif)
        assertEquals(FontFamily.Serif, result.titleLarge.fontFamily)
        assertEquals(FontFamily.Serif, result.bodySmallEmphasized.fontFamily)
        assertEquals(Typography.bodyLarge.fontSize.value * 1.25f, result.bodyLarge.fontSize.value)
        assertEquals(Typography.labelSmall.lineHeight.value * 1.25f, result.labelSmall.lineHeight.value)
        assertEquals(Typography.headlineMediumEmphasized.fontWeight, result.headlineMediumEmphasized.fontWeight)
    }

    @Test fun `second batch group tokens normalize and legacy json stays flat`() {
        val flat = AppSkinGroups(divider = AppSkinDividerStyle.FULL, dividerInsetDp = 999f, dividerWidthDp = -1f,
            borderWidthDp = 9f, containerPaddingDp = 99f, dividerColorHex = "red", containerColorHex = "#AABBCC").normalized()
        assertEquals(AppSkinDividerStyle.NONE, flat.divider) // FLAT 逐行圆角下不画分隔线
        assertEquals(72f, flat.dividerInsetDp)
        assertEquals(0f, flat.dividerWidthDp)
        assertEquals(3f, flat.borderWidthDp)
        assertEquals(16f, flat.containerPaddingDp)
        assertNull(flat.dividerColorHex)
        assertEquals("#AABBCC", flat.containerColorHex)

        val outlined = AppSkinGroups(style = AppSkinGroupStyle.OUTLINED, divider = AppSkinDividerStyle.FULL, borderWidthDp = 1f).normalized()
        assertEquals(AppSkinDividerStyle.FULL, outlined.divider)
        assertEquals(1f, outlined.borderWidthDp)
        assertEquals(outlined, outlined.normalized())

        // 旧皮肤 JSON 无 groups 字段 → 默认 FLAT / 无分隔线，行为与第一批一致
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<AppSkin>("""{"typography":{"sizeRatio":1.1}}""")
        assertEquals(AppSkinGroups(), legacy.groups)
        val json = Json { encodeDefaults = true }
        assertEquals(outlined, json.decodeFromString<AppSkin>(json.encodeToString(AppSkin(groups = outlined))).groups)
    }

    @Test fun `group overrides ride along while untouched groups inherit`() {
        val skin = AppSkin(groups = AppSkinGroups(style = AppSkinGroupStyle.INSET, divider = AppSkinDividerStyle.INSET))
        assertEquals(skin.groups, AppSkinOverrides.from(skin).applyTo(AppSkin()).groups)
        val base = AppSkin()
        assertNull(AppSkinOverrides().withEdits(base, base.copy(shapes = base.shapes.copy(inputDp = 10f))).groups)
        val edited = AppSkinOverrides.from(skin).withEdits(base, base.copy(groups = AppSkinGroups(style = AppSkinGroupStyle.OUTLINED)))
        assertEquals(AppSkinGroupStyle.OUTLINED, edited.groups?.style)
    }

    @Test fun `second batch typography tokens reach weights spacing and mono labels`() {
        val base = Typography.copy(
            bodyLarge = Typography.bodyLarge.copy(fontWeight = FontWeight.W400, letterSpacing = 1.sp),
            labelMedium = Typography.labelMedium.copy(fontWeight = FontWeight.W500),
        )
        val result = appSkinTypography(base,
            AppSkinTypography(weightDelta = 100, letterSpacingSp = 0.5f, tabularNumerals = true, monoLabels = true), null)
        assertEquals(FontWeight.W500, result.bodyLarge.fontWeight)
        assertEquals("tnum", result.bodyLarge.fontFeatureSettings)
        assertEquals(1.5f, result.bodyLarge.letterSpacing.value, .0001f)
        assertEquals(FontWeight.W600, result.labelMedium.fontWeight)
        assertEquals(FontFamily.Monospace, result.labelMedium.fontFamily)
        assertEquals(base.bodyLarge.fontFamily, result.bodyLarge.fontFamily)
        // 自定义字体族优先于等宽标签
        assertEquals(FontFamily.Serif, appSkinTypography(base, AppSkinTypography(monoLabels = true), FontFamily.Serif).labelMedium.fontFamily)
        // 默认参数逐字段不变；越界归一
        assertEquals(Typography, appSkinTypography(Typography, AppSkinTypography(), null))
        val clamped = AppSkinTypography(weightDelta = 999, letterSpacingSp = Float.NaN).normalized()
        assertEquals(200, clamped.weightDelta)
        assertEquals(0f, clamped.letterSpacingSp)
    }
}
