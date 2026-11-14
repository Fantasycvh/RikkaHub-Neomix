package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.DEEPSEEK_BUILTIN_PROVIDER_ID
import app.androidrik.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek OCR 后端的纯判定函数守卫（配料 03 §2）。
 *
 * - 扫描 PDF 的本地解析结果是纯 `---Page N:` 页标（解析器无空文本判定的历史坑），
 *   「有没有可用文本」必须剥页标后再判——判错会把扫描件当文本型放行，
 *   模型只看到 `---Page N:`（即配料 02 §2 记录的静默劣化）。
 * - Key 解析默认复用内置 DeepSeek provider（共享一把 Key），Key 空要**上抛**——
 *   静默降级会改变计费口径。
 */
class DeepSeekOcrSupportTest {

    @Test
    fun scanned_pdf_marker_only_text_is_not_usable() {
        assertFalse(hasUsableLocalDocumentText("---Page 1:\n\n---Page 2:\n\n"))
        // 页标之外有正文 → 可用
        assertTrue(hasUsableLocalDocumentText("---Page 1:\n真实内容\n"))
        // 解析错误文本不算可用（值得试远端，远端也失败再回退本地错误文本）
        assertFalse(hasUsableLocalDocumentText("[ERROR, failed to read file: a.pdf]"))
        assertFalse(hasUsableLocalDocumentText("   \n  "))
    }

    @Test
    fun key_resolves_from_builtin_deepseek_provider_by_default() {
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(id = DEEPSEEK_BUILTIN_PROVIDER_ID, name = "DeepSeek", apiKey = "sk-builtin"),
            ),
        )
        val (provider, apiKey) = resolveDeepSeekOcrConfiguration(settings)
        assertEquals(DEEPSEEK_BUILTIN_PROVIDER_ID, provider.id)
        assertEquals("sk-builtin", apiKey)
    }

    @Test
    fun explicit_provider_id_takes_precedence_over_builtin() {
        val customId = kotlin.uuid.Uuid.random()
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(id = DEEPSEEK_BUILTIN_PROVIDER_ID, name = "DeepSeek", apiKey = "sk-builtin"),
                ProviderSetting.OpenAI(id = customId, name = "DeepSeek 2", apiKey = "sk-custom"),
            ),
            ocrDeepSeekProviderId = customId,
        )
        val (provider, apiKey) = resolveDeepSeekOcrConfiguration(settings)
        assertEquals(customId, provider.id)
        assertEquals("sk-custom", apiKey)
    }

    @Test
    fun missing_provider_or_key_raises_instead_of_silent_fallback() {
        val noProvider = Settings(providers = emptyList())
        assertThrows(IllegalStateException::class.java) { resolveDeepSeekOcrConfiguration(noProvider) }

        val noKey = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(id = DEEPSEEK_BUILTIN_PROVIDER_ID, name = "DeepSeek", apiKey = ""),
            ),
        )
        val thrown = assertThrows(IllegalStateException::class.java) { resolveDeepSeekOcrConfiguration(noKey) }
        assertTrue(thrown.message.orEmpty().contains("no API key"))
    }
}
