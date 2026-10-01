package app.androidrik.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.asr.withEnabled
import kotlin.uuid.Uuid

/**
 * ASR 提供商可停用。
 *
 * 语义对齐搜索服务（`SearchService.enabled`）：停用 = 临时别用它（配置留着），
 * **选中项被停用时不静默回退**到别的提供商——返回 null 让消费路径如实提示「请配置语音识别」。
 */
class AsrProviderEnabledTest {
    private fun providers() = listOf(
        ASRProviderSetting.OpenAIRealtime(name = "first").let { it.copy(id = A) },
        ASRProviderSetting.DashScope(name = "second").let { it.copy(id = B) },
    )

    private fun settings(
        list: List<ASRProviderSetting> = providers(),
        selected: Uuid? = null,
    ) = Settings(asrProviders = list, selectedASRProviderId = selected)

    @Test
    fun `providers default to enabled for zero migration`() {
        // 旧 JSON 缺 enabled 字段 ⇒ 读默认 true（新增字段不改变既有行为）
        providers().forEach { assertTrue(it.name, it.enabled) }
    }

    @Test
    fun `withEnabled flips only the requested provider`() {
        val list = providers()
        val disabled = list[0].withEnabled(false)

        assertFalse(disabled.enabled)
        assertTrue(list[1].enabled)
        // 其它字段原样保留（API key 不该被动）
        assertEquals(list[0].id, disabled.id)
        assertEquals(list[0].name, disabled.name)
    }

    @Test
    fun `selected disabled provider is not silently replaced`() {
        val list = providers()
        val disabledFirst = list[0].withEnabled(false)

        // 选中项被停用 ⇒ null（而不是悄悄回退到第二个提供商）
        assertNull(settings(list = listOf(disabledFirst, list[1]), selected = A).getSelectedASRProvider())
        // 听写与语音模式两条消费路径都按 null 提示「请配置语音识别」
    }

    @Test
    fun `selected enabled provider wins over order`() {
        val list = providers()
        assertEquals(B, settings(list = list, selected = B).getSelectedASRProvider()?.id)
    }

    @Test
    fun `no selection falls back to first enabled provider`() {
        val list = providers()
        val disabledFirst = list[0].withEnabled(false)

        // 未指定选中项（全新安装/首次进入）⇒ 用第一个**启用**的
        assertEquals(B, settings(list = listOf(disabledFirst, list[1])).getSelectedASRProvider()?.id)
        // 第一个就是启用的 ⇒ 用它
        assertEquals(A, settings(list = list).getSelectedASRProvider()?.id)
    }

    @Test
    fun `all disabled yields null`() {
        val list = providers().map { it.withEnabled(false) }
        assertNull(settings(list = list, selected = A).getSelectedASRProvider())
        assertNull(settings(list = emptyList()).getSelectedASRProvider())
    }

    private companion object {
        val A: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val B: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222")
    }
}
