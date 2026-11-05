package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出「提供商 / 模型」标签解析的纯函数测试（PNG / Markdown / HTML 三处共用同一份逻辑）。
 *
 * 覆盖点都是真实存在的退化路径：模型/提供商被删后只剩快照、自定义文案里的空占位符、
 * 空模板、非 assistant 消息——这些不该产出「· GPT-4o」这类悬空分隔符或空白标签。
 */
class ExportDisplayLabelTest {

    private val model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
    private val provider = ProviderSetting.OpenAI(name = "OpenAI", models = listOf(model))
    private val providers = listOf(provider)

    private fun assistant(
        modelId: Uuid? = model.id,
        modelNameSnapshot: String? = null,
        providerNameSnapshot: String? = null,
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        modelId = modelId,
        modelNameSnapshot = modelNameSnapshot,
        providerNameSnapshot = providerNameSnapshot,
    )

    private fun label(
        message: UIMessage = assistant(),
        config: ExportDisplayConfig = ExportDisplayConfig(),
    ) = resolveExportModelLabel(message, providers, config)

    @Test
    fun `default config shows model display name`() {
        assertEquals("GPT-4o", label().label)
    }

    @Test
    fun `hidden mode never shows a label`() {
        val resolved = label(config = ExportDisplayConfig(mode = ExportModelLabelMode.HIDDEN))
        assertEquals("", resolved.label)
        assertNull(resolved.iconSeed)
    }

    @Test
    fun `provider model mode joins provider and model`() {
        val resolved = label(config = ExportDisplayConfig(mode = ExportModelLabelMode.PROVIDER_MODEL))
        assertEquals("OpenAI · GPT-4o", resolved.label)
    }

    @Test
    fun `provider name prefers generation snapshot over live provider`() {
        val message = assistant(providerNameSnapshot = "Renamed Relay")
        val resolved = label(
            message = message,
            config = ExportDisplayConfig(mode = ExportModelLabelMode.PROVIDER_MODEL),
        )
        assertEquals("Renamed Relay · GPT-4o", resolved.label)
    }

    @Test
    fun `deleted model falls back to name snapshot`() {
        val orphan = assistant(modelId = Uuid.random(), modelNameSnapshot = "Ghost-1")
        val resolved = label(
            message = orphan,
            config = ExportDisplayConfig(mode = ExportModelLabelMode.PROVIDER_MODEL),
        )
        assertEquals("Ghost-1", resolved.label)
        assertEquals("Ghost-1", resolved.iconSeed)
    }

    @Test
    fun `custom template renders placeholders`() {
        val resolved = label(
            config = ExportDisplayConfig(
                mode = ExportModelLabelMode.CUSTOM,
                customTemplate = "{provider} / {model} ({modelId})",
            ),
        )
        assertEquals("OpenAI / GPT-4o (gpt-4o)", resolved.label)
    }

    @Test
    fun `custom template cleans dangling separators when provider is missing`() {
        val orphan = assistant(modelId = Uuid.random(), modelNameSnapshot = "GPT-4o")
        val resolved = label(
            message = orphan,
            config = ExportDisplayConfig(
                mode = ExportModelLabelMode.CUSTOM,
                customTemplate = "{provider} · {model}",
            ),
        )
        assertEquals("GPT-4o", resolved.label)
    }

    @Test
    fun `custom template drops empty brackets and repeated separators`() {
        assertEquals(
            "GPT-4o",
            renderExportLabelTemplate(
                template = "({provider}) · · {model}",
                providerName = null,
                modelName = "GPT-4o",
                modelId = null,
            ),
        )
    }

    @Test
    fun `blank custom template yields no label`() {
        val resolved = label(
            config = ExportDisplayConfig(mode = ExportModelLabelMode.CUSTOM, customTemplate = "   "),
        )
        assertEquals("", resolved.label)
    }

    @Test
    fun `icon seed stays bound to model identity with custom text`() {
        val resolved = label(
            config = ExportDisplayConfig(
                mode = ExportModelLabelMode.CUSTOM,
                customTemplate = "answered by {provider}",
            ),
        )
        assertEquals("answered by OpenAI", resolved.label)
        // 自定义文案不能拿去选图标，否则品牌图标会退化成文字头像
        assertEquals("gpt-4o", resolved.iconSeed)
    }

    @Test
    fun `non assistant messages get no label`() {
        val userMessage = UIMessage(role = MessageRole.USER, parts = emptyList())
        assertEquals("", resolveExportModelLabel(userMessage, providers, ExportDisplayConfig()).label)
    }

    @Test
    fun `preview never leaks blank state`() {
        assertEquals("", previewExportModelLabel(ExportDisplayConfig(mode = ExportModelLabelMode.HIDDEN)))
        assertTrue(
            previewExportModelLabel(ExportDisplayConfig(mode = ExportModelLabelMode.PROVIDER_MODEL))
                .isNotBlank()
        )
    }

    // ---- Phase D：额外占位符（{assistant} / {date} / {time} / {tokens}）与内置预设 ----

    private fun assistantWithMeta(
        assistantName: String? = null,
        usage: TokenUsage? = TokenUsage(
            promptTokens = 1000,
            completionTokens = 234,
            totalTokens = 1234,
        ),
    ) = resolveExportModelLabel(
        message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            modelId = model.id,
            createdAt = LocalDateTime(2026, 1, 31, 9, 41),
            usage = usage,
        ),
        providers = providers,
        config = ExportDisplayConfig(
            mode = ExportModelLabelMode.CUSTOM,
            customTemplate = "{assistant} · {model} · {date} {time} · {tokens}",
        ),
        assistantName = assistantName,
    )

    @Test
    fun `custom template renders assistant date time and tokens`() {
        assertEquals(
            "Rikka · GPT-4o · 2026-01-31 09:41 · 1234",
            assistantWithMeta(assistantName = "Rikka").label,
        )
    }

    @Test
    fun `missing assistant name and token usage collapse cleanly`() {
        // 助手名缺失 + 无用量：占位符按空串处理，重复分隔符/尾部分隔符必须被收干净
        assertEquals("GPT-4o · 2026-01-31 09:41", assistantWithMeta(usage = null).label)
    }

    @Test
    fun `token placeholder falls back to prompt plus completion`() {
        val resolved = assistantWithMeta(
            assistantName = "Rikka",
            usage = TokenUsage(promptTokens = 120, completionTokens = 34, totalTokens = 0),
        )
        assertTrue(resolved.label.endsWith("· 154"))
    }

    @Test
    fun `zero token usage hides the placeholder`() {
        val resolved = assistantWithMeta(
            assistantName = "Rikka",
            usage = TokenUsage(),
        )
        assertEquals("Rikka · GPT-4o · 2026-01-31 09:41", resolved.label)
    }

    @Test
    fun `built-in presets carry the expected modes`() {
        val byId = BUILT_IN_EXPORT_DISPLAY_PRESETS.associateBy { it.id }
        assertEquals(
            ExportModelLabelMode.HIDDEN,
            byId.getValue(EXPORT_PRESET_HIDDEN).mode,
        )
        assertEquals(
            ExportModelLabelMode.PROVIDER_MODEL,
            byId.getValue(EXPORT_PRESET_PROVIDER_MODEL).mode,
        )
        assertEquals(
            ExportModelLabelMode.CUSTOM,
            byId.getValue(EXPORT_PRESET_MODEL_PROVIDER).mode,
        )
        assertEquals(
            "{model} · {provider}",
            byId.getValue(EXPORT_PRESET_MODEL_PROVIDER).template,
        )
    }

    @Test
    fun `applying a preset produces its rendered label`() {
        val preset = BUILT_IN_EXPORT_DISPLAY_PRESETS
            .first { it.id == EXPORT_PRESET_MODEL_PROVIDER }
        val resolved = resolveExportModelLabel(
            message = assistant(),
            providers = providers,
            config = preset.config,
        )
        assertEquals("GPT-4o · OpenAI", resolved.label)
    }

    @Test
    fun `preview substitutes assistant and token samples`() {
        assertEquals(
            "Rikka · GPT-4o · 1234",
            previewExportModelLabel(
                ExportDisplayConfig(
                    mode = ExportModelLabelMode.CUSTOM,
                    customTemplate = "{assistant} · {model} · {tokens}",
                )
            ),
        )
    }
}
