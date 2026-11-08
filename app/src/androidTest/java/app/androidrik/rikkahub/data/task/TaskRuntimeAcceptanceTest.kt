package app.androidrik.rikkahub.data.task

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isSubagentNotice
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import app.androidrik.rikkahub.service.RunNotifications
import android.app.NotificationChannel
import android.app.NotificationManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.SubagentExecutionMode
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.data.plugin.PluginToolProvider
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.di.appModule
import app.androidrik.rikkahub.di.dataSourceModule
import app.androidrik.rikkahub.di.repositoryModule
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.ConversationSession
import app.androidrik.rikkahub.service.SubagentRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

/**
 * 显式 opt-in 的真实执行验收。只允许 MemoryIsolationTestRunner + debug 包；
 * baseUrl 指向测试代理，凭据不落手机。seed/verify 支持宿主从外部杀进程后验收。
 */
@RunWith(AndroidJUnit4::class)
class TaskRuntimeAcceptanceTest {
    @Test fun exerciseProductionRuntime() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("runtimeBaseUrl").orEmpty()
        assumeTrue("opt in with runtimeBaseUrl", baseUrl.isNotBlank())
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(app.javaClass == Application::class.java && app.packageName.endsWith(".debug"))
        val scenario = args.getString("runtimeScenario") ?: "long"
            val automatic = scenario.startsWith("auto")
        val approvalMode = args.getString("runtimeApproval") ?: "none"
        val carrier = NoticeCarrier.fromStorage(args.getString("runtimeCarrier"))
        val evidence = File(app.filesDir, "runtime-acceptance").apply { mkdirs() }
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "runtime-acceptance.db").build()
        db.openHelper.writableDatabase.apply {
            execSQL("CREATE TABLE IF NOT EXISTS message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        }
        val koin = startKoin {
            androidContext(app)
            modules(appModule, dataSourceModule, repositoryModule, module {
                single<AppDatabase> { db }
                // Never load or update the app's providers/models/settings during acceptance.
                single<SettingsStore> {
                    SettingsStore(app, get(), File(evidence, "isolated-settings.preferences_pb"))
                }
            })
        }.koin
        var notificationRunId: String? = null
        val notifications = app.getSystemService(NotificationManager::class.java)
        val hadApprovalChannel = notifications.getNotificationChannel(SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID) != null
        if (approvalMode != "none" && !hadApprovalChannel) notifications.createNotificationChannel(NotificationChannel(
            SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID, "Runtime approval", NotificationManager.IMPORTANCE_HIGH))
        try {
            val store = koin.get<SettingsStore>()
            store.awaitLoaded()
            val testModel = Model(modelId = args.getString("runtimeModel") ?: "runtime-fixture",
                displayName = "Runtime acceptance", abilities = listOf(ModelAbility.TOOL), contextLength = 32768)
            val provider: ProviderSetting = if (args.getString("runtimeProtocol") == "openai")
                ProviderSetting.OpenAI(name = "Runtime acceptance proxy", baseUrl = baseUrl,
                    apiKey = "host-proxy-only", models = listOf(testModel))
            else ProviderSetting.Claude(name = "Runtime acceptance proxy", baseUrl = baseUrl,
                apiKey = "host-proxy-only", models = listOf(testModel))
            val model = testModel
            val assistant = Assistant(name = "Runtime acceptance", chatModelId = model.id, enableMemory = false,
                systemPrompt = "You are a runtime acceptance test. Follow the supplied synthetic test instructions.")
            val handoffPreset = SubagentPreset(name = "handoff-blocking", modelId = model.id,
                allowedTools = setOf("runtime_acceptance_wait"), executionMode = SubagentExecutionMode.BLOCKING,
                systemPrompt = "Call runtime_acceptance_wait, then return its result.")
            store.update { it.copy(providers = listOf(provider), chatModelId = model.id,
                assistants = listOf(assistant), enableBackgroundSubagent = scenario != "handoff" && !automatic, noticeCarrier = carrier,
                workspaceTaskNoticeEnabled = automatic,
                enableSubagentDispatch = scenario == "handoff" || automatic, subagentPresets = listOf(handoffPreset),
                enableAutoTitle = false, enableSuggestion = false) }
            store.settingsFlow.first { it.enableBackgroundSubagent == (scenario != "handoff" && !automatic) &&
                it.workspaceTaskNoticeEnabled == automatic && it.chatModelId == model.id }
            val chat = koin.get<ChatService>()
            val dao = db.agentRunDao()
            val repo = koin.get<ConversationRepository>()
            if (scenario == "verify") {
                val lines = File(evidence, "restart.ids").readLines()
                val parent = Uuid.parse(lines[0])
                val runId = lines[1]
                ProcessStartClock.markStarted()
                AgentRunReconciler(dao).reconcileProcessRestart()
                chat.startNoticeDelivery()
                val stored = withTimeout(30_000) { dao.observeRun(runId).first { it?.deliveryState == "delivered" }!! }
                assertEquals("interrupted", stored.status)
                val conversation = repo.getConversationById(parent)!!
                assertEquals(1, conversation.currentMessages.count { it.isSubagentNotice })
                assertNull(chat.getGenerationJobStateFlow(parent).first())
                File(evidence, "restart.passed").writeText("interrupted + delivered once; no automatic generation")
                return@runBlocking
            }
            val seconds = if (scenario == "seed") 180 else args.getString("runtimeSeconds")?.toInt() ?: 180
            val toolStarted = CompletableDeferred<Unit>()
            val releaseHandoffTask = CompletableDeferred<Unit>()
            val newMessageStarted = CompletableDeferred<Unit>()
            val releaseNewMessage = CompletableDeferred<Unit>()
            var toolExecutions = 0
            koin.get<PluginManager>().register(object : PluginToolProvider {
                override val id = "test.runtime.acceptance"
                override val displayName = "Runtime acceptance wait"
                override val description = "Test-only bounded delay"
                override val version = "1"
                override val capabilities = setOf(PluginCapability.TOOL_REGISTER)
                override fun tools() = listOf(Tool(name = "runtime_acceptance_wait", description =
                    "Wait for the acceptance test barrier, then return a fixed test value. Call exactly once.",
                    executionTimeoutMillis = 240_000,
                    needsApproval = { approvalMode != "none" },
                    execute = {
                        File(evidence, "tool.started").writeText("$scenario:$seconds")
                        toolStarted.complete(Unit)
                        toolExecutions++
                        if (scenario == "handoff" || automatic) releaseHandoffTask.await() else delay(seconds * 1000L)
                        listOf(UIMessagePart.Text("RUNTIME_ACCEPTANCE_OK"))
                    }), Tool(name = "runtime_new_message_wait", description = "Wait for the new-message test barrier.", execute = {
                        newMessageStarted.complete(Unit)
                        releaseNewMessage.await()
                        listOf(UIMessagePart.Text("NEW_MESSAGE_OK"))
                    }))
            })
            val parent = Conversation.ofId(Uuid.random(), assistant.id).copy(
                messageNodes = listOf(UIMessage.user("Synthetic runtime acceptance parent").toMessageNode()))
            repo.insertConversation(parent)
            withContext(Dispatchers.Main) { chat.ensureConversationLoaded(parent.id) }
            if (automatic) {
                chat.startNoticeDelivery()
                withContext(Dispatchers.Main) { chat.sendMessage(parent.id, listOf(UIMessagePart.Text("AUTO_OLD_MESSAGE"))) }
                withTimeout(30_000) { toolStarted.await() }
                withTimeout(30_000) { chat.getGenerationJobStateFlow(parent.id).first { it == null } }
                val run = dao.observeRunsByConversation(parent.id.toString()).first().single()
                notificationRunId = run.id
                assertEquals(AgentRunEntity.DELIVERY_MODE_RESUME, run.deliveryMode)
                assertNull(run.finishedAt)
                assertTrue(chat.getConversationFlow(parent.id).value.currentMessages.flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>().any { it.toolName == "await_background_tasks" && it.isExecuted })
                if (scenario == "auto-stop") withContext(Dispatchers.Main) { chat.stopGeneration(parent.id) }
                if (scenario == "auto-priority" || scenario == "auto-handoff") {
                    withContext(Dispatchers.Main) {
                        val parts = listOf(UIMessagePart.Text("AUTO_NEW_MESSAGE"))
                        if (scenario == "auto-handoff") chat.sendRetainingTasks(parent.id, parts)
                        else chat.sendMessage(parent.id, parts)
                    }
                    withTimeout(30_000) { newMessageStarted.await() }
                }
                releaseHandoffTask.complete(Unit)
                withTimeout(30_000) { dao.observeRun(run.id).first { it?.finishedAt != null } }
                if (scenario == "auto-priority" || scenario == "auto-handoff") {
                    delay(200)
                    assertEquals("pending", dao.getRun(run.id)!!.deliveryState)
                    assertEquals(0, chat.getConversationFlow(parent.id).value.currentMessages.count { it.isExternalNotice })
                    releaseNewMessage.complete(Unit)
                    withTimeout(30_000) { chat.getConversationFlow(parent.id).first { c -> c.currentMessages.any { m ->
                        m.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("NEW_MESSAGE_OK") } } } }
                }
                withTimeout(30_000) { dao.observeRun(run.id).first { it?.deliveryState == "delivered" } }
                if (scenario == "auto-stop" || scenario == "auto-handoff") {
                    delay(300)
                    assertEquals("suppressed", db.externalNoticeDao().get(run.id)!!.wakeState)
                    withTimeout(30_000) { chat.getGenerationJobStateFlow(parent.id).first { it == null } }
                } else {
                    withTimeout(30_000) { chat.getConversationFlow(parent.id).first { c -> c.currentMessages.any { m ->
                        m.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("AUTO_RESUME_OK") } } } }
                    withTimeout(30_000) { chat.getGenerationJobStateFlow(parent.id).first { it == null } }
                    assertEquals("completed", db.externalNoticeDao().get(run.id)!!.wakeState)
                }
                assertEquals(1, toolExecutions)
                assertEquals(1, chat.getConversationFlow(parent.id).value.currentMessages.count { it.isExternalNotice })
                assertEquals(0, chat.getConversationFlow(parent.id).value.currentMessages.count { it.isSubagentNotice })
                File(evidence, "$scenario.passed").writeText("parent_released_before_completion=true;automatic=${scenario != "auto-stop"};new_message_priority=${scenario == "auto-priority"};delivered_once=true;tool_executions=$toolExecutions")
                return@runBlocking
            }
            if (scenario == "handoff") {
                check(args.getString("runtimeProtocol") == "openai") { "handoff fixture requires openai protocol" }
                chat.startNoticeDelivery()
                withContext(Dispatchers.Main) { chat.sendMessage(parent.id, listOf(UIMessagePart.Text("HANDOFF_OLD_MESSAGE"))) }
                withTimeout(30_000) { toolStarted.await() }
                val row = dao.observeRunsByConversation(parent.id.toString()).first().single()
                notificationRunId = row.id
                assertEquals(AgentRunEntity.DELIVERY_MODE_AWAIT, row.deliveryMode)
                assertEquals(AgentRunEntity.STATUS_RUNNING, row.status)
                assertNotNull(chat.getGenerationJobStateFlow(parent.id).first())
                withContext(Dispatchers.Main) { chat.sendRetainingTasks(parent.id, listOf(UIMessagePart.Text("HANDOFF_NEW_MESSAGE"))) }
                withTimeout(30_000) {
                    chat.getConversationFlow(parent.id).first { conversation ->
                        conversation.currentMessages.any { it.role == MessageRole.ASSISTANT &&
                            it.parts.filterIsInstance<UIMessagePart.Text>().any { part -> part.text.contains("NEW_MESSAGE_OK") } }
                    }
                    chat.getGenerationJobStateFlow(parent.id).first { it == null }
                }
                val retained = dao.getRun(row.id)!!
                assertEquals(AgentRunEntity.DELIVERY_MODE_HANDOFF, retained.deliveryMode)
                assertEquals(AgentRunEntity.STATUS_RUNNING, retained.status)
                assertNull(retained.finishedAt)
                assertEquals(1, toolExecutions)
                assertTrue(selectedSubagentRunIds(chat.getConversationFlow(parent.id).value.currentMessages).contains(row.id))
                releaseHandoffTask.complete(Unit)
                val terminal = withTimeout(30_000) { dao.observeRun(row.id).first { it?.deliveryState == "delivered" }!! }
                assertEquals(AgentRunEntity.STATUS_COMPLETED, terminal.status)
                assertEquals(1, chat.getConversationFlow(parent.id).value.currentMessages.count { it.isSubagentNotice })
                assertNull(chat.getGenerationJobStateFlow(parent.id).first())
                File(evidence, "handoff.passed").writeText("new_reply_before_task_finish=true;blocking_run_retained=true;automatic_background_disabled=true;delivered_once=true;tool_executions=$toolExecutions")
                return@runBlocking
            }
            val runner = koin.get<SubagentRunner>()
            val started = System.currentTimeMillis()
            val result = runner.dispatchBackground(SubagentPreset(name = "runtime-acceptance", modelId = model.id,
                systemPrompt = "Call runtime_acceptance_wait exactly once, then reply only with the value returned by the tool.",
                allowedTools = setOf("runtime_acceptance_wait"), executionMode = SubagentExecutionMode.BACKGROUND,
                toolApproval = if (approvalMode != "none") SubagentToolApproval.ASK else SubagentToolApproval.DENY),
                "Call runtime_acceptance_wait now. After it returns, output RUNTIME_ACCEPTANCE_OK.",
                300_000, parent.id.toString())!!
            notificationRunId = result.runId
            assertTrue(System.currentTimeMillis() - started < 10_000)
            File(evidence, "restart.ids").writeText("${parent.id}\n${result.runId}")
            if (approvalMode != "none") {
                if (approvalMode != "unavailable") {
                    val request = withTimeout(30_000) { SubagentApprovalQueue.requests.first { list ->
                        list.any { it.runId == result.runId }
                    }.first { it.runId == result.runId } }
                    assertEquals("awaiting_approval", dao.getRun(result.runId)!!.status)
                    assertTrue(SubagentApprovalQueue.resolve(request, approvalMode == "approve"))
                }
                chat.startNoticeDelivery()
                val terminal = withTimeout(60_000) { dao.observeRun(result.runId).first { it?.finishedAt != null }!! }
                assertEquals(terminal.error, "completed", terminal.status)
                assertEquals(approvalMode == "approve", toolStarted.isCompleted)
                if (approvalMode == "unavailable") assertTrue(terminal.transcript.contains("notification is unavailable"))
                withTimeout(30_000) { dao.observeRun(result.runId).first { it?.deliveryState == "delivered" } }
                assertFalse(SubagentApprovalQueue.requests.value.any { it.runId == result.runId })
                File(evidence, "approval-$approvalMode.passed").writeText("executed=${toolStarted.isCompleted};terminal=${terminal.status};delivered=true")
                return@runBlocking
            }
            withTimeout(60_000) { toolStarted.await() }
            if (scenario == "seed") {
                File(evidence, "seed.ready").writeText("kill this process now")
                delay(600_000) // 宿主此时 force-stop；本轮必须无法自然完成。
                error("seed must be killed by host")
            }
            // 通过生产 session 注册接口保持父会话 busy，模型 run 独立继续。
            @Suppress("UNCHECKED_CAST")
            val sessions = ChatService::class.java.getDeclaredField("sessions").apply { isAccessible = true }
                .get(chat) as Map<Uuid, ConversationSession>
            val parentSession = sessions.getValue(parent.id)
            val parentJob = Job()
            withContext(Dispatchers.Main) { parentSession.setJob(parentJob) }
            chat.startNoticeDelivery()
            val terminal = withTimeout(300_000) { dao.observeRun(result.runId).first { it?.finishedAt != null }!! }
            assertEquals(terminal.error, "completed", terminal.status)
            assertTrue(System.currentTimeMillis() - started >= seconds * 1000L)
            delay(1000)
            assertEquals("pending", dao.getRun(result.runId)!!.deliveryState)
            assertEquals(0, repo.getConversationById(parent.id)!!.currentMessages.count { it.isSubagentNotice })
            withContext(Dispatchers.Main) { parentJob.complete() }
            withTimeout(30_000) { dao.observeRun(result.runId).first { it?.deliveryState == "delivered" } }
            val delivered = repo.getConversationById(parent.id)!!.currentMessages.single { it.isSubagentNotice }
            assertEquals(if (carrier == NoticeCarrier.USER) MessageRole.USER else
                MessageRole.SYSTEM, delivered.role)
            assertNull(parentSession.getJob())
            File(evidence, "long.passed").writeText("duration_ms=${System.currentTimeMillis()-started};carrier=$carrier;busy_deferred=true;delivered_once=true")
            Log.i("RuntimeAcceptance", "long run, busy defer, idle delivery passed")
        } finally {
            koin.get<AppScope>().coroutineContext[Job]!!.cancelAndJoin()
            notificationRunId?.let { RunNotifications(app).cancel(it) }
            if (approvalMode != "none" && !hadApprovalChannel) notifications.deleteNotificationChannel(SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID)
            db.close()
            stopKoin()
        }
    }
}
