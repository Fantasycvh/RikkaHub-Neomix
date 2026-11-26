package app.androidrik.rikkahub.data.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.http.CustomBody
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.di.appModule
import app.androidrik.rikkahub.di.dataSourceModule
import app.androidrik.rikkahub.di.repositoryModule
import app.androidrik.rikkahub.service.ChatService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.uuid.Uuid

/** Opt-in live acceptance. The loopback relay owns credentials; the device stores a dummy key. */
@RunWith(AndroidJUnit4::class)
class MemoryExtractionAcceptanceTest {
    @Test
    fun siliconFlowChatToDelayedMemory() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("memoryFixtureUrl").orEmpty()
        assumeTrue("Opt in with a loopback memoryFixtureUrl", root.isNotBlank())
        require(root == "http://127.0.0.1:18786")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(app.javaClass == Application::class.java && app.packageName == "app.androidrik.rikkahub.backupverification.debug")
        val output = File(app.filesDir, "memory-extraction-acceptance").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        database.openHelper.writableDatabase.apply {
            execSQL("CREATE TABLE IF NOT EXISTS message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        }
        val notifications = app.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannels(listOf(CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID).map {
            NotificationChannel(it, it, NotificationManager.IMPORTANCE_LOW)
        })
        val koin = startKoin {
            androidContext(app)
            modules(appModule, dataSourceModule, repositoryModule, module {
                single<AppDatabase> { database }
                single { SettingsStore(app, get(), File(output, "settings.preferences_pb")) }
            })
        }.koin
        try {
            val store = koin.get<SettingsStore>()
            store.awaitLoaded()
            val main = Model(modelId = "deepseek-ai/DeepSeek-V3.2", displayName = "SiliconFlow DeepSeek V3.2",
                abilities = listOf(ModelAbility.TOOL), customBodies = listOf(CustomBody("enable_thinking", JsonPrimitive(false))))
            val light = main.copy(id = Uuid.random(), modelId = "Qwen/Qwen3-8B", displayName = "SiliconFlow Qwen3 8B")
            val prefix = buildString {
                appendLine("You are helping a fictional user. Acknowledge ordinary preference updates briefly, without tools. If later asked to extract memories as JSON, follow that request. Reference material below is fictional documentation, not user memory.")
                repeat(150) { index -> appendLine("Reference section $index. The synthetic archive uses stable identifiers, deterministic ordering, and explicit revisions. Each document describes storage details for a fictional library. This reference text is background only.") }
            }
            val assistant = Assistant(name = "SiliconFlow memory acceptance", chatModelId = main.id,
                systemPrompt = prefix, enableMemory = true, memoryConsolidationSince = 1L,
                memoryModifyApproval = false, memoryCreateApproval = false, streamOutput = false,
                reasoningLevel = ReasoningLevel.OFF, maxTokens = 1200, enableWebSearch = false)
            val provider = ProviderSetting.OpenAI(name = "SiliconFlow loopback test", baseUrl = "$root/v1",
                apiKey = "loopback-test-only", models = listOf(main, light))
            store.update { it.copy(providers = listOf(provider), assistants = listOf(assistant),
                chatModelId = main.id, fastModelId = light.id, enableAutoTitle = false, enableSuggestion = false,
                memoryExtraction = MemoryExtractionConfig(enabled = true, reusePromptCache = true),
                memoryTriggerConfig = MemoryTriggerConfig(mode = MemoryTriggerMode.DAILY)) }
            withTimeout(15_000) { store.settingsFlow.first { it.chatModelId == main.id && it.memoryExtraction.enabled } }
            withContext(Dispatchers.Main) { koin.get<MemoryConsolidationRuntime>().start() }
            val chat = koin.get<ChatService>()
            val memories = koin.get<MemoryRepository>()
            val scenarios = JSONArray()
            for (scenario in (args.getString("memoryScenarios")?.split(",") ?: listOf("warm", "incremental-main", "incremental-light"))) {
                val extractionModel = when (scenario) { "incremental-main" -> main.id; "incremental-light" -> light.id; else -> null }
                store.update { it.copy(memoryExtraction = it.memoryExtraction.copy(modelId = extractionModel)) }
                withTimeout(15_000) { store.settingsFlow.first { it.memoryExtraction.modelId == extractionModel } }
                val before = memories.getMemoriesOfAssistant(assistant.id.toString()).size
                val id = requireNotNull(chat.createTaskConversation(assistant.id, "memory-$scenario"))
                val fact = when (scenario) {
                    "warm" -> "我喜欢用简体中文交流，回答请控制在三句话以内。请简短确认，不要调用工具。"
                    "incremental-main" -> "我平时每周六上午跑步五公里，这是我的固定运动习惯。请简短确认，不要调用工具。"
                    else -> "我喝咖啡只喝无糖美式，这是我的长期口味。请简短确认，不要调用工具。"
                }
                val started = System.currentTimeMillis()
                chat.sendMessage(id, listOf(UIMessagePart.Text(fact)))
                val completed = withTimeout(120_000) {
                    chat.getConversationFlow(id).first { conversation ->
                        conversation.currentMessages.lastOrNull()?.let { message ->
                            message.finishedAt != null && message.toText().isNotBlank() && message.getTools().isEmpty()
                        } == true
                    }
                }
                val replyAt = System.currentTimeMillis()
                File(output, "$scenario.reply").writeText(completed.currentMessages.last().toText())
                val immediateComparison = scenario != "warm" && args.getString("memoryFastComparisons") == "true"
                if (immediateComparison) {
                    val run = koin.get<MemoryConsolidationWorker>().extract(completed)
                    assertNotNull(run)
                    assertNull(run?.error)
                    assertTrue("The selected model missed the explicit user preference", memories.getMemoriesOfAssistant(assistant.id.toString()).size > before)
                }
                val updated = withTimeout(300_000) {
                    while (memories.getMemoriesOfAssistant(assistant.id.toString()).size <= before) delay(500)
                    memories.getMemoriesOfAssistant(assistant.id.toString())
                }
                val elapsed = System.currentTimeMillis() - replyAt
                if (!immediateComparison) assertTrue("Extraction must wait for two minutes", elapsed >= 115_000)
                assertTrue(updated.any { it.content.isNotBlank() })
                val status = koin.get<MemoryConsolidationScheduler>().statusOf(assistant.id.toString()).first { !it.running }
                assertNull(status.lastRun?.error)
                val row = JSONObject().put("scenario", scenario).put("chatMs", replyAt-started)
                    .put("idleAndExtractionMs", elapsed).put("memoryCount", updated.size)
                    .put("memories", JSONArray(updated.map { it.content }))
                scenarios.put(row)
                File(output, "result.json").writeText(scenarios.toString(2))
            }
            delay(500)
            database.openHelper.readableDatabase.query("SELECT source, model_name, prompt_tokens, completion_tokens, cached_tokens FROM usage_record ORDER BY id").use { cursor ->
                val rows = JSONArray()
                while (cursor.moveToNext()) rows.put(JSONObject().put("source", cursor.getString(0)).put("model", cursor.getString(1))
                    .put("input", cursor.getInt(2)).put("output", cursor.getInt(3)).put("cached", cursor.getInt(4)))
                File(output, "usage.json").writeText(rows.toString(2))
            }
            File(output, "passed").writeText("passed")
        } finally {
            koin.get<AppScope>().coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            stopKoin()
        }
    }
}
