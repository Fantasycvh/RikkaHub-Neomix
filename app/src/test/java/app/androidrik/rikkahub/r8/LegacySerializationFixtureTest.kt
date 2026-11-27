package app.androidrik.rikkahub.r8

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.core.MessageRole
import app.androidrik.common.http.ProviderProxy
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.providerTypeDisplayName
import app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption
import app.androidrik.rikkahub.data.datastore.loadSearchServices
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlin.uuid.Uuid
import org.junit.Test

/** Fixed wire-format inputs must decode independently of the current encoder. */
class LegacySerializationFixtureTest {
    private val fixture = javaClass.getResourceAsStream("/r8/legacy-settings.json")!!.use {
        JsonInstant.decodeFromString<JsonObject>(it.bufferedReader().readText())
    }

    @Test
    fun `complete historical message decodes with the stored role and deprecated parts`() {
        val message = JsonInstant.decodeFromJsonElement<UIMessage>(fixture.getValue("message"))
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertEquals(4, message.parts.size)
        assertEquals("R8 legacy decode survived", (message.parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun `fixed media grant survives reload and rejects changed endpoints and old obfuscated fingerprints`() {
        val grant = JsonInstant.decodeFromJsonElement<ScheduledMediaAction>(fixture.getValue("mediaGrant"))
        val provider = MediaGenerationProviderSetting.OpenAI(
            id = Uuid.parse("00000000-0000-0000-0000-000000000011"),
            baseUrl = "https://fixture.invalid/v1",
            models = listOf(MediaGenerationModel("r8-fixture-openai", MediaKind.IMAGE,
                id = Uuid.parse("00000000-0000-0000-0001-000000000000"))),
        )
        val settings = Settings.dummy().copy(providers = emptyList(), mediaGenerationProviders = listOf(provider))
        assertTrue(grant.permits(settings.mediaModelCatalog().single()))
        val moved = settings.copy(mediaGenerationProviders = listOf(provider.copy(baseUrl = "https://changed.invalid/v1")))
        assertFalse(grant.permits(moved.mediaModelCatalog().single()))
        val oldGrant = grant.copy(configurationFingerprint = fixture.getValue("preKeepFingerprint").jsonPrimitive.content)
        assertFalse(oldGrant.permits(settings.mediaModelCatalog().single()))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `decode-only historical message parts retain their payloads`() {
        val parts = JsonInstant.decodeFromJsonElement<List<UIMessagePart>>(fixture.getValue("messageParts"))
        assertEquals(UIMessagePart.Search, parts[0])
        assertEquals("old-call", (parts[1] as UIMessagePart.ToolCall).toolCallId)
        assertEquals("time_info", (parts[2] as UIMessagePart.ToolResult).toolName)
        assertEquals("{\"time\":\"2026-11-25\"}", (parts[2] as UIMessagePart.ToolResult).content.toString())
    }

    @Test
    fun `legacy tools retain every enabled option and order`() {
        val tools = JsonInstant.decodeFromJsonElement<List<LocalToolOption>>(fixture.getValue("localTools"))
        assertEquals(listOf(
            LocalToolOption.JavascriptEngine, LocalToolOption.TimeInfo, LocalToolOption.Clipboard,
            LocalToolOption.Tts, LocalToolOption.AskUser, LocalToolOption.ScreenTime,
            LocalToolOption.Calendar, LocalToolOption.ChartDisplay, LocalToolOption.TodoPlan,
            LocalToolOption.PhoneUse,
        ), tools)
    }

    @Test
    fun `historical qualified avatar discriminators preserve content`() {
        val avatars = JsonInstant.decodeFromJsonElement<List<Avatar>>(fixture.getValue("avatars"))
        assertEquals(listOf(Avatar.Dummy, Avatar.Emoji("旧头像"), Avatar.Image("file:///legacy/avatar.png")), avatars)
    }

    @Test
    fun `legacy proxies preserve direct and inherited behavior`() {
        val proxies = JsonInstant.decodeFromJsonElement<List<ProviderProxy>>(fixture.getValue("proxies"))
        assertEquals(listOf(ProviderProxy.Inherit, ProviderProxy.None,
            ProviderProxy.Http("127.0.0.1", 8080), ProviderProxy.Socks5("127.0.0.1", 1080)), proxies)
    }

    @Test
    fun `retired search service migration keeps selected provider identity`() {
        val loaded = loadSearchServices(fixture.getValue("searchServices").toString(), storedSelected = 2)
        assertEquals(listOf("Tavily", "Exa"), loaded.services.map { it.displayName })
        assertEquals(1, loaded.selected)
        assertEquals("00000000-0000-0000-0000-000000000003", loaded.services[loaded.selected].id.toString())
    }

    @Test
    fun `all historical media types retain ids and explicit labels`() {
        val providers = JsonInstant.decodeFromJsonElement<List<MediaGenerationProviderSetting>>(fixture.getValue("mediaProviders"))
        assertEquals(listOf("OpenAI", "Google", "Aliyun", "Volcengine", "MiniMax", "OpenRouter", "SiliconFlow"),
            providers.map { it.providerTypeDisplayName })
        assertEquals((11..17).map { "00000000-0000-0000-0000-${it.toString().padStart(12, '0')}" },
            providers.map { it.id.toString() })
        assertTrue(providers.all { it.name.isBlank() && it.models.isEmpty() })
    }
}
