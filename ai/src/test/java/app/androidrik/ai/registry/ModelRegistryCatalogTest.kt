package app.androidrik.ai.registry

import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ModelAbility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * catalog 安装后的注册表访问器行为：命中 catalog 用 catalog 值，未命中回落 token 规则，
 * 卸载（`installCatalog(null)`）后与未安装完全一致。
 *
 * 与 [ModelsDevCatalogTest] 分开：那边钉的是 catalog 自身的数据合并语义（多数投票、索引、
 * 序列化），这里钉的是「注册表接上 catalog 之后」的四个访问器，且 fixture 刻意选与本仓注册表
 * **结论不同**的条目，否则断言在 catalog 生效与否两种情况下都成立，等于没测。
 *
 * 每例结束后必须卸载 catalog：访问器读的是 object 单例上的 volatile 字段，同 JVM 里不清理
 * 会污染 [app.androidrik.ai.ModelRegistryTest] 这类「未安装 catalog」的断言。
 */
class ModelRegistryCatalogTest {
    private val fixture = """
        {
          "openai": {
            "models": {
              "o3-mini": {
                "reasoning": true,
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] },
                "limit": { "context": 200000 }
              }
            }
          },
          "anthropic": {
            "models": {
              "claude-3.5-sonnet": {
                "reasoning": false,
                "tool_call": true,
                "modalities": { "input": ["text", "image"], "output": ["text"] },
                "limit": { "context": 180000 }
              }
            }
          },
          "google": {
            "models": {
              "gemini-2.5-flash-image": {
                "modalities": { "input": ["text", "image"], "output": ["text", "image"] }
              }
            }
          },
          "openrouter": {
            "models": {
              "claude-sonnet-5": {
                "tool_call": true,
                "modalities": { "input": ["text"], "output": ["text"] }
              }
            }
          }
        }
    """.trimIndent()

    private val catalog = ModelsDevCatalog.decode(fixture.byteInputStream())

    @After
    fun resetCatalog() {
        ModelRegistry.installCatalog(null)
    }

    @Test
    fun catalogOverridesRegistryModalitiesAbilitiesAndContext() {
        ModelRegistry.installCatalog(catalog)

        // 注册表 OPENAI_O_MODELS 给了 o3 全系视觉输入，catalog 里 o3-mini 只收文本
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
        // 注册表没声明 o3-mini 的上下文长度（null），catalog 有 200k
        assertEquals(200_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("o3-mini"))

        // 注册表 CLAUDE_SONNET_3_5 标了 reasoning，catalog 里 claude-3.5-sonnet 没有
        assertEquals(
            listOf(ModelAbility.TOOL),
            ModelRegistry.MODEL_ABILITIES.getData("claude-3.5-sonnet")
        )
        assertEquals(180_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-3.5-sonnet"))

        // 输出模态也走 catalog：gemini-2.5-flash-image 输出图片
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
    }

    @Test
    fun catalogMissingContextFallsBackToRegistry() {
        ModelRegistry.installCatalog(catalog)

        // catalog 命中（模态/能力以它为准），但它没给上下文长度
        // ⇒ 回落注册表的 1M（CLAUDE_SONNET_5）
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-sonnet-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL),
            ModelRegistry.MODEL_ABILITIES.getData("claude-sonnet-5")
        )
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5"))
    }

    @Test
    fun modelsMissingFromCatalogFallBackToRegistry() {
        ModelRegistry.installCatalog(catalog)

        // muse-spark 不在 fixture 里 ⇒ 完全按注册表 MUSE_SPARK 的声明
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("muse-spark")
        )
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("muse-spark"))
        // 未命中的模型上下文长度也走注册表（DEEPSEEK_V4_1_FLASH 声明了 1M）
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-1-flash"))
    }

    @Test
    fun removingCatalogRestoresRegistryBehavior() {
        ModelRegistry.installCatalog(catalog)
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
        assertEquals(
            listOf(ModelAbility.TOOL),
            ModelRegistry.MODEL_ABILITIES.getData("claude-3.5-sonnet")
        )

        // 卸载后必须回到安装前的注册表结论（也是 ModelRegistryTest 钉住的行为）
        ModelRegistry.installCatalog(null)
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("claude-3.5-sonnet")
        )
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-3.5-sonnet"))
        assertNull(ModelRegistry.MODEL_CONTEXT_LENGTH.getData("o3-mini"))
    }
}
