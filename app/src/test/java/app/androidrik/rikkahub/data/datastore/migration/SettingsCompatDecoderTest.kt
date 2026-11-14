package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class SettingsCompatDecoderTest {
    private fun decode(value: String) = SettingsCompatDecoder.decode(JsonInstant, value)

    @Test fun supportedSettingsRoundTripUnchanged() {
        val original = Settings()
        val result = decode(JsonInstant.encodeToString(Settings.serializer(), original))
        assertEquals(
            JsonInstant.encodeToString(Settings.serializer(), original),
            JsonInstant.encodeToString(Settings.serializer(), result.settings),
        )
        assertTrue(result.droppedEntries.isEmpty())
    }

    @Test fun unknownProvidersAreSkippedWithoutChangingSupportedCredentials() {
        // chatgpt / grok 在 P3 已支持（账号类）；这里用真正未知的类型验证降级丢弃
        val result = decode("""{"providers":[
            {"type":"future_provider"},
            {"type":"openai","name":"Supported","apiKey":"test-secret","models":[]},
            {"type":"another_future_provider"}
        ]}""")
        assertEquals(listOf("Supported"), result.settings.providers.map { it.name })
        assertEquals(
            listOf("providers[0] (future_provider)", "providers[2] (another_future_provider)"),
            result.droppedEntries,
        )
        assertFalse(result.droppedEntries.toString().contains("test-secret"))
    }

    @Test fun upstreamAccountProvidersAreNoLongerDropped() {
        // 上游 #2186 的账号类提供商：P3 起能解码（凭据缺失 = 未登录状态）
        val result = decode("""{"providers":[
            {"type":"chatgpt","name":"ChatGPT"},
            {"type":"grok","name":"Grok"}
        ]}""")
        assertEquals(listOf("ChatGPT", "Grok"), result.settings.providers.map { it.name })
        assertTrue(result.droppedEntries.isEmpty())
    }

    @Test fun selectedUnsupportedTtsFallsBackToSystem() {
        val id = "11111111-1111-4111-8111-111111111111"
        val result = decode("""{"ttsProviders":[{"type":"volcengine","id":"$id"}],
            "selectedTTSProviderId":"$id"}""")
        assertTrue(result.settings.ttsProviders.isEmpty())
        assertEquals(DEFAULT_SYSTEM_TTS_ID, result.settings.selectedTTSProviderId)
        assertEquals(listOf("ttsProviders[0] (volcengine)"), result.droppedEntries)
    }

    @Test fun unknownAsrProvidersAreSkippedAndSelectionCleared() {
        // ASR 与 TTS 同款降级（配料 04 P0-2）：未知类型只丢该条，选中项被丢则清空选择
        // （null = 未选择，读路径如实提示，不静默回退到别的可能计费的 provider）。
        // 注意用 future_asr 而非 deepseek——后者在本仓落地后会变成已知类型。
        val droppedId = "22222222-2222-4222-8222-222222222222"
        val keptId = "33333333-3333-4333-8333-333333333333"
        val result = decode("""{"asrProviders":[
            {"type":"future_asr","id":"$droppedId"},
            {"type":"mimo","id":"$keptId"}
        ],"selectedASRProviderId":"$droppedId"}""")
        assertEquals(1, result.settings.asrProviders.size)
        assertNull(result.settings.selectedASRProviderId)
        assertEquals(listOf("asrProviders[0] (future_asr)"), result.droppedEntries)
    }

    @Test fun supportedAsrProviderRoundTripsWithSelection() {
        val keptId = "44444444-4444-4444-8444-444444444444"
        val result = decode("""{"asrProviders":[{"type":"mimo","id":"$keptId"}],
            "selectedASRProviderId":"$keptId"}""")
        assertEquals(1, result.settings.asrProviders.size)
        assertTrue(result.droppedEntries.isEmpty())
        // Uuid 序列化形式与输入一致即证明选择未被误清
        assertTrue(JsonInstant.encodeToString(Settings.serializer(), result.settings).contains(keptId))
    }

    @Test fun migrationAndDecoderHandleUpstreamNamespacesAndRetiredSearch() {
        val migrated = SettingsJsonMigrator.migrate("""{
            "providers":[{"type":"grok"},{"type":"google"}],
            "searchServices":[{"type":"rikkahub"},{"type":"tavily"}],"searchServiceSelected":1,
            "displaySetting":{"userAvatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"}}
        }""", "app.androidrik.rikkahub")
        val result = decode(migrated)
        // grok 在 P3 起是支持的类型，不再被丢弃
        assertEquals(2, result.settings.providers.size)
        assertTrue(result.settings.providers.any { it is app.androidrik.ai.provider.ProviderSetting.Grok })
        assertEquals(1, result.settings.searchServices.size)
        assertEquals(0, result.settings.searchServiceSelected)
    }

    @Test(expected = SerializationException::class)
    fun malformedKnownProviderIsNotSilentlyDeleted() {
        decode("""{"providers":[{"type":"openai","models":"not-an-array"}]}""")
    }

    @Test(expected = SerializationException::class)
    fun nestedUnknownTypeDoesNotDeleteEntireProvider() {
        decode("""{"providers":[{"type":"openai","models":[{"tools":[{"type":"future-tool"}]}]}]}""")
    }

    @Test(expected = SerializationException::class)
    fun unrelatedInvalidSettingStillFailsAfterUnsupportedProvider() {
        decode("""{"providers":[{"type":"chatgpt"}],"searchServiceSelected":"invalid"}""")
    }

    @Test(expected = SerializationException::class)
    fun invalidRootFails() { decode("[]") }

    @Test fun failedServerToolMessageDecodesAndRoundTrips() {
        val input = """[{"role":"assistant","parts":[{"type":"server_tool",
            "toolCallId":"search-1","toolName":"web_search","status":"failed"}]}]"""
        val messages = JsonInstant.decodeFromString<List<UIMessage>>(input)
        val tool = messages.single().parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.FAILED, tool.status)
        assertEquals(messages, JsonInstant.decodeFromString<List<UIMessage>>(JsonInstant.encodeToString(messages)))
    }
}
