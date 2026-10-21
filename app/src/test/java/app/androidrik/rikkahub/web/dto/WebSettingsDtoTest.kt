package app.androidrik.rikkahub.web.dto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.ai.mcp.McpCommonOptions
import app.androidrik.rikkahub.data.ai.mcp.McpOAuthState
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * `/api/events` 的 `settings` 事件必须是**白名单 DTO**，不能是全量 [Settings]。
 *
 * 被钉住的缺陷：`EventsRoutes` 曾直接 `JsonInstant.encodeToString(settings)` 下发全量配置，
 * 其中 provider `apiKey`/`apiKeys`、`customHeaders`、`baseUrl`（可能内嵌账号密码）、
 * 助手 `systemPrompt`、世界书/注入条目正文、MCP `headers`/`oauth` 令牌全部跨 HTTP 边界。
 *
 * 两个方向都断言：
 * 1. **不泄漏**：任何已知 secret 字面量 / 敏感字段名都不能出现在序列化结果里
 *    （新增字段忘了白名单就会红）；
 * 2. **够用**：web-ui 真实读取的字段都在（`providers[].models[].tools[].type`、
 *    `assistants[].avatar`、`searchServices[].type`、`mcpServers[].commonOptions.tools` …），
 *    否则前端会静默渲染空列表。
 */
class WebSettingsDtoTest {

    private val secretValues = listOf(
        "API-KEY-SECRET",
        "KEY-LIST-SECRET",
        "CUSTOM-HEADER-SECRET",
        "BASE-URL-PASSWORD",
        "ASSISTANT-PROMPT-SECRET",
        "ASSISTANT-HEADER-SECRET",
        "LOREBOOK-ENTRY-SECRET",
        "INJECTION-CONTENT-SECRET",
        "MCP-URL-SECRET",
        "MCP-HEADER-SECRET",
        "MCP-OAUTH-TOKEN",
        "SEARCH-API-KEY-SECRET",
    )

    private fun settingsWithSecrets(): Settings = Settings.dummy().copy(
        providers = listOf(
            ProviderSetting.OpenAI(
                name = "Provider A",
                models = listOf(
                    Model(
                        modelId = "model-a",
                        displayName = "Model A",
                        abilities = listOf(ModelAbility.REASONING),
                        tools = setOf(BuiltInTools.Search),
                    )
                ),
                apiKey = "API-KEY-SECRET",
                apiKeys = listOf(ApiKeyEntry(key = "KEY-LIST-SECRET")),
                baseUrl = "https://user:BASE-URL-PASSWORD@example.com/v1",
                customHeaders = listOf(CustomHeader("Authorization", "Bearer CUSTOM-HEADER-SECRET")),
            )
        ),
        assistants = listOf(
            Assistant(
                name = "Assistant A",
                systemPrompt = "ASSISTANT-PROMPT-SECRET",
                customHeaders = listOf(CustomHeader("X-Token", "ASSISTANT-HEADER-SECRET")),
                avatar = Avatar.Emoji("🐱"),
            )
        ),
        assistantTags = listOf(Tag(id = Uuid.random(), name = "Tag A")),
        lorebooks = listOf(
            Lorebook(
                name = "Lorebook A",
                description = "desc",
                entries = listOf(
                    PromptInjection.RegexInjection(content = "LOREBOOK-ENTRY-SECRET")
                ),
            )
        ),
        promptPresets = listOf(
            PromptPreset(
                name = "Preset A",
                entries = listOf(
                    PromptInjection.ModeInjection(
                        name = "Injection A",
                        content = "INJECTION-CONTENT-SECRET",
                    )
                ),
            )
        ),
        mcpServers = listOf(
            McpServerConfig.SseTransportServer(
                url = "https://mcp.example.com/sse?token=MCP-URL-SECRET",
                commonOptions = McpCommonOptions(
                    name = "MCP A",
                    headers = listOf("Authorization" to "Bearer MCP-HEADER-SECRET"),
                    oauth = McpOAuthState(enabled = true, accessToken = "MCP-OAUTH-TOKEN"),
                ),
            )
        ),
        searchServices = listOf(SearchServiceOptions.ExaOptions(apiKey = "SEARCH-API-KEY-SECRET")),
        favoriteModels = listOf(Uuid.random()),
        searchServiceSelected = 0,
    )

    private val settings: Settings by lazy { settingsWithSecrets() }

    private fun encodedJson(): String =
        JsonInstant.encodeToString(WebSettingsDto.serializer(), settings.toWebSettingsDto())

    @Test
    fun `settings event never carries credentials or persona text`() {
        val json = encodedJson()

        secretValues.forEach { secret ->
            assertFalse(
                "凭据/私有正文泄漏到 /api/events：$secret\n$json",
                json.contains(secret),
            )
        }

        listOf(
            "apiKey",
            "apiKeys",
            "customHeaders",
            "customBodies",
            "baseUrl",
            "systemPrompt",
            "proxy",
            "headers",
            "oauth",
            "clientSecret",
            "accessToken",
            "refreshToken",
            "clientCertificate",
            "certData",
            "keyData",
            "entries",
            "presetMessages",
            "background",
        ).forEach { field ->
            assertFalse("敏感字段名不应出现在 Web 设置快照里：$field\n$json", json.contains("\"$field\""))
        }
    }

    @Test
    fun `settings event keeps every field web-ui reads`() {
        val dto = settings.toWebSettingsDto()

        // 顶层身份字段（web-ui 用来定位当前助手/默认模型）
        assertEquals(settings.assistantId.toString(), dto.assistantId)
        assertNotNull(dto.chatModelId)
        assertTrue(dto.favoriteModels.isNotEmpty())
        assertTrue(dto.webServerJwtEnabled) // Settings 默认恒开 Web 鉴权
        assertEquals(0, dto.searchServiceSelected)

        // providers[].models[]：模型列表选择器 + 推理档位 + 内置搜索判定
        val provider = dto.providers.single()
        assertEquals("Provider A", provider.name)
        assertTrue(provider.enabled)
        val model = provider.models.single()
        assertEquals("model-a", model.modelId)
        assertEquals("Model A", model.displayName)
        assertEquals("CHAT", model.type)
        assertEquals(listOf("REASONING"), model.abilities)
        assertEquals(listOf("TEXT"), model.inputModalities)
        assertEquals(listOf("search"), model.tools.map { it.type })

        // assistants[]：名称/头像/可见标签/注入绑定
        val assistant = dto.assistants.single()
        assertEquals("Assistant A", assistant.name)
        assertEquals("emoji", assistant.avatar.type)
        assertEquals("🐱", assistant.avatar.content)
        assertNull(assistant.chatModelId) // 未绑定 → null，前端已按 string|null 处理
        assertEquals("auto", assistant.reasoningLevel)
        assertEquals(listOf("Tag A"), dto.assistantTags.map { it.name })

        // 注入条目/世界书/快捷消息：只给展示字段（正文不出现）
        assertEquals("Injection A", dto.modeInjections.single().name)
        assertTrue(dto.modeInjections.single().enabled)
        assertEquals("Lorebook A", dto.lorebooks.single().name)
        assertEquals("desc", dto.lorebooks.single().description)
        assertTrue(dto.quickMessages.isEmpty())

        // MCP：只保留开关/名字/工具清单
        val mcp = dto.mcpServers.single()
        assertTrue(mcp.commonOptions.enable)
        assertEquals("MCP A", mcp.commonOptions.name)
        assertTrue(mcp.commonOptions.tools.isEmpty())

        // 搜索服务：判别值必须是 @SerialName（前端据此映射显示名）
        assertEquals("exa", dto.searchServices.single().type)
    }

    @Test
    fun `settings event top-level shape is the web-ui contract`() {
        val root = JsonInstant.encodeToJsonElement(
            WebSettingsDto.serializer(),
            settings.toWebSettingsDto(),
        ) as JsonObject

        assertEquals(
            setOf(
                "assistantId",
                "chatModelId",
                "favoriteModels",
                "displaySetting",
                "providers",
                "assistants",
                "assistantTags",
                "modeInjections",
                "lorebooks",
                "quickMessages",
                "mcpServers",
                "searchServices",
                "searchServiceSelected",
                "webServerJwtEnabled",
            ),
            root.keys,
        )

        // displaySetting 的字段必须是嵌套白名单（web-ui 逐字段读取，缺一个就渲染成默认值）
        val display = root["displaySetting"]!!.jsonObject
        assertEquals(
            setOf(
                "userNickname",
                "userAvatar",
                "showUserAvatar",
                "showModelIcon",
                "showModelName",
                "showTokenUsage",
                "showThinkingContent",
                "autoCloseThinking",
                "codeBlockAutoWrap",
                "showLineNumbers",
                "sendOnEnter",
                "pasteLongTextAsFile",
                "pasteLongTextThreshold",
            ),
            display.keys,
        )

        // 可空字段按 JSON null 下发（而不是省略/空串），前端 `string | null` 直接可用
        val assistant = (root["assistants"]!! as JsonArray).single().jsonObject
        assertEquals("null", assistant["chatModelId"].toString())
        assertEquals("emoji", assistant["avatar"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
