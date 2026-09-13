package app.androidrik.rikkahub.data.datastore

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 压缩模型解析优先级：
 * 显式指定（可解析）> followModel（会话生成模型）> 全局当前对话模型
 *
 * 「跟随对话模型」用 compressModelId == null 表达；显式指定失效（模型被删）时回退。
 */
class CompressionModelResolveTest {

    private fun model(name: String) = Model(modelId = name, displayName = name)

    private fun settingsWith(
        compressModelId: Uuid?,
        providerModels: List<Model>,
    ): Settings {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "test",
            models = providerModels,
        )
        return Settings(
            providers = listOf(provider),
            compressModelId = compressModelId,
        )
    }

    @Test
    fun `explicit compress model wins when resolvable`() {
        val explicit = model("explicit")
        val follow = model("follow")
        val settings = settingsWith(explicit.id, listOf(explicit, follow))

        assertEquals(explicit.id, settings.resolveCompressionModel(follow)?.id)
    }

    @Test
    fun `null compress model follows the session generation model`() {
        val follow = model("follow")
        val other = model("other")
        val settings = settingsWith(null, listOf(follow, other))

        assertEquals(follow.id, settings.resolveCompressionModel(follow)?.id)
    }

    @Test
    fun `dangling explicit model falls back to follow model`() {
        // 显式指定的模型已被删除（悬空引用）-> 回退到会话生成模型
        val follow = model("follow")
        val settings = settingsWith(Uuid.random(), listOf(follow))

        assertEquals(follow.id, settings.resolveCompressionModel(follow)?.id)
    }

    @Test
    fun `follow model is skipped when null`() {
        // 无法拿到会话生成模型时应继续回退（这里 providers 无匹配 -> null），不崩溃
        val settings = settingsWith(Uuid.random(), emptyList())
        assertNull(settings.resolveCompressionModel(null))
    }

    @Test
    fun `default settings locks to built-in model not follow`() {
        // 默认值必须是显式模型（保持原行为），「跟随对话」是用户主动清除后的可选状态
        val defaults = Settings()
        assertEquals(DEFAULT_AUTO_MODEL_ID, defaults.compressModelId)
    }
}
