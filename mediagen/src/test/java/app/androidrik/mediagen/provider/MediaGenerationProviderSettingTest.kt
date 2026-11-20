package app.androidrik.mediagen.provider

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationProviderSettingTest {
    @Test
    fun settingKeepsImageAndVideoModelsThroughSerialization() {
        val setting: MediaGenerationProviderSetting = MediaGenerationProviderSetting.Volcengine(
            apiKey = "key",
            models = listOf(
                MediaGenerationModel(modelId = "doubao-seedance-2-0-260128", kind = MediaKind.VIDEO),
                MediaGenerationModel(modelId = "doubao-seedream-5-0", kind = MediaKind.IMAGE, displayName = "Seedream"),
            ),
        )

        val encoded = Json.encodeToString(MediaGenerationProviderSetting.serializer(), setting)
        val decoded = Json.decodeFromString(MediaGenerationProviderSetting.serializer(), encoded)

        assertEquals(setting, decoded)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.IMAGE), decoded.models.map { it.kind })
        // supportedKinds 描述的是适配器，不随配置保存
        assertFalse("supportedKinds" in Json.parseToJsonElement(encoded).jsonObject)
    }

    @Test
    fun copyProviderReplacesModelsAndKeepsCredentials() {
        val setting = MediaGenerationProviderSetting.OpenAI(apiKey = "sk-test")
        val models = setting.models + MediaGenerationModel(modelId = "gpt-image-1", kind = MediaKind.IMAGE)

        val copy = setting.copyProvider(name = "中转", models = models)

        assertTrue(copy is MediaGenerationProviderSetting.OpenAI)
        assertEquals(setting.id, copy.id)
        assertEquals("sk-test", copy.apiKey)
        assertEquals("中转", copy.name)
        assertEquals(
            listOf("gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-1"),
            copy.models.map { it.modelId }
        )
    }

    @Test
    fun copyProviderReplacesCredentialsAndKeepsVendorFields() {
        val setting = MediaGenerationProviderSetting.Aliyun(apiKey = "old", workspaceId = "ws-1")

        val copy = setting.copyProvider(apiKey = "new", baseUrl = "https://dashscope.aliyuncs.com/api/v1")

        assertEquals("new", copy.apiKey)
        assertEquals("https://dashscope.aliyuncs.com/api/v1", copy.baseUrl)
        assertEquals("ws-1", (copy as MediaGenerationProviderSetting.Aliyun).workspaceId)
        assertEquals(setting.models, copy.models)
    }

    @Test
    fun proxyAndMultiKeysSurviveSerializationAndOldPayloadsFallBackToDefaults() {
        // 新字段（M3a-1 parity）：序列化往返
        val setting: MediaGenerationProviderSetting = MediaGenerationProviderSetting.OpenAI(
            apiKey = "sk-legacy",
            apiKeys = listOf("sk-a", "sk-b"),
            proxy = app.androidrik.common.http.ProviderProxy.Http("127.0.0.1", 7890),
        )
        val encoded = Json.encodeToString(MediaGenerationProviderSetting.serializer(), setting)
        val decoded = Json.decodeFromString(MediaGenerationProviderSetting.serializer(), encoded)
        assertEquals(setting, decoded)

        // 旧备份没有 proxy/apiKeys 字段：反序列化落默认值（Inherit + 空列表），行为与升级前一致
        val legacy = Json.decodeFromString(
            MediaGenerationProviderSetting.serializer(),
            """{"type":"openai","id":"${setting.id}","apiKey":"sk-legacy"}""",
        ) as MediaGenerationProviderSetting.OpenAI
        assertEquals(app.androidrik.common.http.ProviderProxy.Inherit, legacy.proxy)
        assertTrue(legacy.apiKeys.isEmpty())
    }

    @Test
    fun effectiveMediaApiKeysPrefersListAndFallsBackToLegacySingleKey() {
        val multi = MediaGenerationProviderSetting.OpenAI(apiKey = "sk-legacy", apiKeys = listOf("sk-a", " ", "sk-a", "sk-b"))
        assertEquals("sk-a\nsk-b", multi.effectiveMediaApiKeys)

        val single = MediaGenerationProviderSetting.OpenAI(apiKey = "sk-legacy")
        assertEquals("sk-legacy", single.effectiveMediaApiKeys)
        assertEquals("sk-legacy", single.firstMediaApiKey())
        assertEquals("sk-a", multi.firstMediaApiKey())
    }

    @Test
    fun copyProviderPassesProxyAndMultiKeys() {
        val setting = MediaGenerationProviderSetting.SiliconFlow(apiKey = "sk")
        val proxy = app.androidrik.common.http.ProviderProxy.Http("10.0.0.1", 8080)

        val copy = setting.copyProvider(proxy = proxy, apiKeys = listOf("k1", "k2"))

        assertEquals(proxy, copy.proxy)
        assertEquals(listOf("k1", "k2"), copy.apiKeys)
        assertEquals("k1", copy.firstMediaApiKey())
    }

    @Test
    fun modelCustomHeadersAndBodiesSurviveSerializationAndOldPayloadsFallBackToEmpty() {
        // M3a-2 parity：模型级请求定制序列化往返；旧数据无字段 → 空列表
        val model = MediaGenerationModel(
            modelId = "gpt-image-2",
            kind = MediaKind.IMAGE,
            customHeaders = listOf(app.androidrik.common.http.CustomHeader("X-Test", "yes")),
            customBodies = listOf(app.androidrik.common.http.CustomBody("n", kotlinx.serialization.json.JsonPrimitive(2))),
        )
        val encoded = Json.encodeToString(MediaGenerationModel.serializer(), model)
        assertEquals(model, Json.decodeFromString(MediaGenerationModel.serializer(), encoded))

        val legacy = Json.decodeFromString(
            MediaGenerationModel.serializer(),
            """{"modelId":"gpt-image-2","kind":"IMAGE"}""",
        )
        assertTrue(legacy.customHeaders.isEmpty())
        assertTrue(legacy.customBodies.isEmpty())
    }
}
