package app.androidrik.rikkahub.data.remote

import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.registry.ModelsDevCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置快照 models_dev.json 的守门测试：app 启动时装的就是它（首次安装没有 filesDir 缓存），
 * 它坏掉等于全部模型元数据静默回落 token 规则，只能靠这里钉住。
 */
class ModelsDevAssetTest {
    @Test
    fun bundledCatalogIsReadable() {
        // Android 单元测试的工作目录是模块根目录
        val catalog = File("src/main/assets/models_dev.json").inputStream().use { ModelsDevCatalog.decode(it) }
        // 精简快照应覆盖 models.dev 全部模型（数千条）；阈值只用来挡住「拉空了/写坏了」
        assertTrue("bundled catalog too small: ${catalog.size}", catalog.size > 1000)

        // gpt-4o 要认成视觉输入：模态走 models.dev 数据而不是 token 规则后的第一条断言
        val gpt4o = requireNotNull(catalog.find("gpt-4o")) { "gpt-4o missing from bundled catalog" }
        assertTrue("gpt-4o should accept image input", Modality.IMAGE in gpt4o.inputModalities)
        // 上下文长度也来自这份快照（ModelRegistry.MODEL_CONTEXT_LENGTH 的 catalog 来源）：
        // 各家提供商对 gpt-4o 都标 128000，取哪个合并都该是这个值，为 null 说明 limit 字段写丢了
        assertEquals("gpt-4o context length", 128000, gpt4o.contextLength)

        // 带连字符的 Claude id 在快照里直接存在（aihubmix 等提供商），并且要认成支持工具调用
        val claude = requireNotNull(catalog.find("claude-sonnet-4-5")) {
            "claude-sonnet-4-5 missing from bundled catalog"
        }
        assertTrue("claude-sonnet-4-5 should support tools", ModelAbility.TOOL in claude.abilities)
    }
}
