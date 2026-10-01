package app.androidrik.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.tts.provider.TTSProviderSetting
import app.androidrik.tts.provider.withEnabled
import kotlin.uuid.Uuid

/**
 * TTS 提供商可停用 + 系统 TTS 兜底 + 内置项可删（墓碑）。
 *
 * 三条契约：①默认启用（零迁移）②**不允许「一个启用的都不剩」**（系统 TTS 是唯一免 key 的
 * 兜底）③内置项删除记墓碑后**不再被回填**（否则等于删不掉）。
 */
class TtsProviderEnabledTest {
    private fun system() = TTSProviderSetting.SystemTTS(id = SYSTEM_ID, name = "System TTS")
    private fun custom(id: Uuid = CUSTOM_ID) = TTSProviderSetting.OpenAI(id = id, name = "Custom")

    @Test
    fun `providers default to enabled for zero migration`() {
        assertTrue(system().enabled)
        assertTrue(custom().enabled)
    }

    @Test
    fun `withEnabled flips only the requested provider`() {
        val disabled = custom().withEnabled(false)

        assertFalse(disabled.enabled)
        // 其它字段（含 API key 所在的那批）原样保留
        assertEquals(CUSTOM_ID, disabled.id)
        assertEquals("Custom", disabled.name)
    }

    @Test
    fun `disabling the last enabled provider keeps system tts as fallback`() {
        // 只剩系统 TTS 启用时想停用它 ⇒ 被兜底不变式拉回
        val result = listOf(system().withEnabled(false)).ensureFallbackTtsEnabled()

        assertTrue(result.single().enabled)
    }

    @Test
    fun `fallback keeps system tts when every provider was disabled`() {
        val result = listOf(custom().withEnabled(false), system().withEnabled(false))
            .ensureFallbackTtsEnabled()

        assertTrue("系统 TTS 必须被拉回启用", result.first { it.id == SYSTEM_ID }.enabled)
        // 其它非系统项保持用户的停用选择，不被一并打开
        assertFalse(result.first { it.id == CUSTOM_ID }.enabled)
    }

    @Test
    fun `fallback does nothing when someone else is still enabled`() {
        val list = listOf(system().withEnabled(false), custom())
        assertEquals(list, list.ensureFallbackTtsEnabled())
    }

    @Test
    fun `fallback does nothing when system tts itself was deleted`() {
        // 系统 TTS 被删（墓碑）⇒ 没有兜底可用，如实保持全停用
        val list = listOf(custom().withEnabled(false))
        assertEquals(list, list.ensureFallbackTtsEnabled())
    }

    @Test
    fun `selected disabled provider is not silently replaced`() {
        val providers = listOf(system(), custom().withEnabled(false))
        val settings = Settings(ttsProviders = providers, selectedTTSProviderId = CUSTOM_ID)

        // 选中项被停用 ⇒ null（不回退到系统 TTS，由 TTS 侧如实置「不可用」）
        assertNull(settings.getSelectedTTSProvider())
    }

    @Test
    fun `missing selection falls back to first enabled provider`() {
        val providers = listOf(system(), custom())
        // 默认选中系统 TTS 且它启用 ⇒ 用它
        assertEquals(SYSTEM_ID, Settings(ttsProviders = providers).getSelectedTTSProvider()?.id)

        // 选中 id 指向**已删除**的提供商（find 不到）⇒ 退回第一个启用的
        val onlyCustom = listOf(system().withEnabled(false), custom())
        assertEquals(
            CUSTOM_ID,
            Settings(ttsProviders = onlyCustom, selectedTTSProviderId = DELETED_ID)
                .getSelectedTTSProvider()?.id,
        )
    }

    @Test
    fun `selected but disabled yields null even when another is enabled`() {
        // 「选中项被停用」与「选中 id 已不存在」是两条分支：前者**不静默回退**
        // （不替用户改用另一个可能计费的提供商），由 UI 在停用选中项时自动改选来避免这个状态
        val providers = listOf(system().withEnabled(false), custom())
        assertNull(
            Settings(ttsProviders = providers, selectedTTSProviderId = SYSTEM_ID)
                .getSelectedTTSProvider(),
        )
    }

    // ---- 内置项可删（墓碑） ----

    @Test
    fun `builtin providers are refilled when no tombstone`() {
        val existing = mutableListOf<TTSProviderSetting>()
        refillDefaultTtsProviders(existing, removedIds = emptySet())

        // 全新安装：两个内置项都被补上
        assertEquals(2, existing.size)
        assertTrue(existing.any { it.id == DEFAULT_SYSTEM_TTS_ID })
    }

    @Test
    fun `tombstoned builtin provider is not refilled`() {
        val existing = mutableListOf<TTSProviderSetting>()
        refillDefaultTtsProviders(existing, removedIds = setOf(DEFAULT_SYSTEM_TTS_ID))

        // 删过的内置项不再回填（这正是「内置提供商删不掉」的修法）
        assertFalse(existing.any { it.id == DEFAULT_SYSTEM_TTS_ID })
        assertEquals(1, existing.size)
    }

    @Test
    fun `deleting all builtins stays empty`() {
        val existing = mutableListOf<TTSProviderSetting>()
        val allBuiltInIds = listOf(system()).map { it.id }.toSet() + setOf(OPENAI_BUILT_IN_ID)
        refillDefaultTtsProviders(existing, removedIds = allBuiltInIds)

        // 全删光后列表就该是空的（旧实现在此处用 ifEmpty 会整体复活）
        assertTrue(existing.isEmpty())
    }

    @Test
    fun `user provider is never tombstoned`() {
        assertFalse(isBuiltInTtsProvider(CUSTOM_ID))
        assertTrue(isBuiltInTtsProvider(DEFAULT_SYSTEM_TTS_ID))
    }

    private companion object {
        val SYSTEM_ID: Uuid = DEFAULT_SYSTEM_TTS_ID
        val OPENAI_BUILT_IN_ID: Uuid = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c")
        val CUSTOM_ID: Uuid = Uuid.parse("33333333-3333-3333-3333-333333333333")
        /** 一个不存在的提供商 id（模拟「选中项已被删除」） */
        val DELETED_ID: Uuid = Uuid.parse("44444444-4444-4444-4444-444444444444")
    }
}
