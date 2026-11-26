package app.androidrik.rikkahub.ui.pages.setting

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.BackupReminderConfig
import app.androidrik.rikkahub.data.datastore.LocalSnapshotConfig
import app.androidrik.rikkahub.data.ai.mcp.McpCommonOptions
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.web.WebServerState
import app.androidrik.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SettingHomeSummariesTest {

    @Test
    fun `resolves default model, enabled counts, search service, web state and backup priority`() {
        val modelId = Uuid.random()
        val providerEnabled = ProviderSetting.OpenAI(
            enabled = true,
            name = "OpenAI",
            models = listOf(Model(id = modelId, modelId = "gpt-5", displayName = "GPT-5")),
        )
        val providerDisabled = ProviderSetting.OpenAI(enabled = false, name = "Disabled")
        val settings = Settings(
            chatModelId = modelId,
            providers = listOf(providerEnabled, providerDisabled),
            assistants = listOf(Assistant(name = "A"), Assistant(name = "B")),
            searchServices = listOf(SearchServiceOptions.BingLocalOptions(enabled = true)),
            searchServiceSelected = 0,
            mcpServers = listOf(
                McpServerConfig.SseTransportServer(
                    commonOptions = McpCommonOptions(enable = true, name = "mcp1"),
                    url = "https://example.com/sse",
                ),
                McpServerConfig.SseTransportServer(
                    commonOptions = McpCommonOptions(enable = false, name = "mcp2"),
                    url = "https://example.com/sse2",
                ),
            ),
            ttsProviders = emptyList(),
            asrProviders = emptyList(),
            mediaGenerationProviders = emptyList(),
            localSnapshotConfig = LocalSnapshotConfig(enabled = true),
            backupReminderConfig = BackupReminderConfig(enabled = true),
        )

        val summary = resolveSettingHomeSummaries(
            settings = settings,
            webServerState = WebServerState(isRunning = true, port = 9090),
            skinName = "浅蓝",
        )

        assertEquals("浅蓝", summary.skinName)
        assertEquals(2, summary.assistantCount)
        assertEquals("GPT-5", summary.defaultModelName)
        assertEquals(1, summary.enabledProviderCount)
        assertEquals(0, summary.mediaSpeechProviderCount)
        assertEquals("Bing", summary.selectedSearchServiceName)
        assertEquals(1, summary.enabledMcpCount)
        assertTrue(summary.webServerRunning)
        assertEquals(9090, summary.webServerPort)
        assertEquals(SettingBackupSummaryMode.AUTO_SNAPSHOT, summary.backupMode)
    }

    @Test
    fun `handles unset model, out-of-bounds search index, stopped server and backup modes`() {
        val reminderSettings = Settings(
            chatModelId = Uuid.random(),
            providers = emptyList(),
            searchServices = listOf(SearchServiceOptions.BingLocalOptions(enabled = true)),
            searchServiceSelected = 99,
            localSnapshotConfig = LocalSnapshotConfig(enabled = false),
            backupReminderConfig = BackupReminderConfig(enabled = true),
        )
        val reminderSummary = resolveSettingHomeSummaries(
            settings = reminderSettings,
            webServerState = WebServerState(isRunning = false, port = 8080),
            skinName = "  ",
        )
        assertNull(reminderSummary.skinName)
        assertNull(reminderSummary.defaultModelName)
        assertEquals("Bing", reminderSummary.selectedSearchServiceName)
        assertFalse(reminderSummary.webServerRunning)
        assertEquals(SettingBackupSummaryMode.REMINDER, reminderSummary.backupMode)

        val manualSummary = resolveSettingHomeSummaries(
            settings = reminderSettings.copy(backupReminderConfig = BackupReminderConfig(enabled = false)),
            webServerState = WebServerState(),
            skinName = null,
        )
        assertEquals(SettingBackupSummaryMode.MANUAL, manualSummary.backupMode)
    }
}
