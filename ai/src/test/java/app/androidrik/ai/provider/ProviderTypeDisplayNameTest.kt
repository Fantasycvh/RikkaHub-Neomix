package app.androidrik.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提供商**类型展示名**（API 格式名）的契约。
 *
 * 背景：展示名此前取自 `KClass.simpleName`，而 **release 构建经 R8 混淆后**类名会变成
 * `it9`(OpenAI) / `ft9`(Google) / `bt9`(Claude)——于是正式版的自定义提供商类型选择器
 * 显示 "it9"/"ft9"，而 debug 不混淆、看着完全正常（**只在正式版暴露**）。
 * 本测试钉住「展示名与类名解耦、且显式写死」这条契约，防止有人再改回类名派生。
 */
class ProviderTypeDisplayNameTest {

    @Test
    fun `three api formats have explicit stable display names`() {
        assertEquals(
            listOf("OpenAI", "Google", "Claude"),
            ProviderSetting.Types.map { it.displayName },
        )
    }

    // 注：「展示名不是类名派生」**无法用行为断言测出**——debug 构建不混淆，
    // `simpleName` 就是 "OpenAI"/"Google"/"Claude"，与正确实现完全一致；只有在 release
    // （R8 把 ProviderSetting$OpenAI 改名为 it9）才暴露。故该回归由**源码级守卫**
    // 覆盖，见 app 模块的 ProviderTypeLabelSourceGuardTest。

    @Test
    fun `display names are non blank and unique`() {
        val names = ProviderSetting.Types.map { it.displayName }
        assertTrue(names.none { it.isBlank() })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `instance extension agrees with the type list`() {
        // providerTypeDisplayName 是「实例 → 展示名」的唯一入口（模型详情页标签用），
        // 它必须与 Types 单一来源一致，不得各写一份
        assertEquals("OpenAI", ProviderSetting.OpenAI().providerTypeDisplayName)
        assertEquals("Google", ProviderSetting.Google().providerTypeDisplayName)
        assertEquals("Claude", ProviderSetting.Claude().providerTypeDisplayName)
    }

    @Test
    fun `every declared type is covered by the instance extension`() {
        // Types 是本列表的驱动源：往里加类型就必须同时给出展示名，
        // 实例扩展也不会漏（Unknown 说明没写展示名）
        ProviderSetting.Types.forEach { option ->
            val instance = when (option.type) {
                ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI()
                ProviderSetting.Google::class -> ProviderSetting.Google()
                ProviderSetting.Claude::class -> ProviderSetting.Claude()
                else -> error("unexpected provider type: ${option.type}")
            }
            assertEquals(option.displayName, instance.providerTypeDisplayName)
        }
    }
}
