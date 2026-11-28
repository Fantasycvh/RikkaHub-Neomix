package app.androidrik.rikkahub.data.task

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.message.tools.GenerateImageToolUI
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIContext
import app.androidrik.rikkahub.ui.components.message.tools.LocalChatMediaReference
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.data.media.ChatMediaGeneration
import app.androidrik.rikkahub.data.ai.tools.imageToolMediaResult
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.components.ui.boundedImagePreviewRequest
import app.androidrik.mediagen.model.ImageRole
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.MEDIA_CREATION_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.files.uploadDestinationFingerprint
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.image.ImageGenerationUpdate
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MediaCreationDraft
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.data.model.MediaCreationStatus
import app.androidrik.rikkahub.data.repository.MediaCreationRepository
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAsset
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAssetStore
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaOperation
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.di.appModule
import app.androidrik.rikkahub.di.dataSourceModule
import app.androidrik.rikkahub.di.repositoryModule
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.MediaCreationService
import app.androidrik.rikkahub.service.MediaCreationSubmission
import app.androidrik.rikkahub.service.ScheduledMediaRunner
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.service.ScheduledTaskRunner
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaCreationVM
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class MediaGenerationAcceptanceTest {
    @Test
    fun exerciseIsolatedMediaPipeline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val root = args.getString("mediaFixtureUrl").orEmpty()
        assumeTrue("opt in with mediaFixtureUrl", root.isNotBlank())
        val url = root.toHttpUrl()
        check(url.host == "127.0.0.1" && url.encodedPath == "/")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(app.javaClass == Application::class.java && app.packageName == "app.androidrik.rikkahub.backupverification.debug")
        val notifications = app.getSystemService(NotificationManager::class.java)
        val channels = listOf(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID,
            CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
        notifications.createNotificationChannels(channels.map { NotificationChannel(it, it, NotificationManager.IMPORTANCE_LOW) })
        val token = requireNotNull(args.getString("mediaFixtureToken")).also {
            require(it.matches(Regex("[a-z0-9]+")))
        }
        val evidence = File(app.filesDir, "media-acceptance/$token").apply { mkdirs() }
        val database = Room.databaseBuilder(app, AppDatabase::class.java, "media-acceptance-$token.db").build()
        database.openHelper.writableDatabase.apply {
            execSQL("CREATE TABLE IF NOT EXISTS message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
            execSQL("CREATE TABLE IF NOT EXISTS message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        }
        val koin = startKoin {
            androidContext(app)
            modules(appModule, dataSourceModule, repositoryModule, module {
                single<AppDatabase> { database }
                single<SettingsStore> { SettingsStore(app, get(), File(evidence, "isolated.preferences_pb")) }
            })
        }.koin
        try {
            val fixture = Harness(koin, app, evidence, root.trimEnd('/'), token)
            val scenario = args.getString("mediaFixtureScenario") ?: "templates"
            if (scenario !in listOf("recover", "recoverreconcile", "recoverchatmedia")) fixture.configure()
            when (scenario) {
                "templates" -> fixture.templates()
                "chat" -> fixture.chat()
                "chatmedia" -> fixture.chatMedia()
                "seedchatmedia" -> fixture.seedChatMedia()
                "recoverchatmedia" -> fixture.recoverChatMedia()
                "prompt" -> fixture.promptOptimization()
                "seed" -> fixture.seedInterruption()
                "recover" -> fixture.recoverInterruption()
                "seedreconcile" -> fixture.seedReconciliation()
                "recoverreconcile" -> fixture.recoverReconciliation()
                else -> error("Unknown media fixture scenario: $scenario")
            }
            File(evidence, "$scenario.passed").writeText("Passed")
        } finally {
            koin.get<AppScope>().coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            stopKoin()
        }
    }

    private class Harness(
        private val koin: Koin,
        private val app: Application,
        private val evidence: File,
        private val url: String,
        private val token: String,
    ) {
        private val http = OkHttpClient()
        private val store = koin.get<SettingsStore>()
        private val tasks = koin.get<ScheduledTaskRepository>()
        private val media = koin.get<MediaCreationRepository>()
        private val service = koin.get<MediaCreationService>()
        private val runner = koin.get<ScheduledTaskRunner>()
        private val runs = koin.get<ScheduledRunRegistry>()
        private val chat = koin.get<ChatService>()
        private val fixtureChat = Model(modelId = "fixture-chat", displayName = "Fixture chat", abilities = listOf(ModelAbility.TOOL))
        private val assistant = Assistant(name = "Media fixture", chatModelId = fixtureChat.id, imageGenApproval = true,
            enableMemory = false, enableWebSearch = false)
        private val mediaImage = MediaGenerationModel("fixture-image", MediaKind.IMAGE)
        private val t2v = MediaGenerationModel("fixture-T2V", MediaKind.VIDEO)
        private val i2v = MediaGenerationModel("fixture-I2V", MediaKind.VIDEO)
        private val videoParams = MediaCreationParams(resolution = "1280x720")
        private val imageParams = MediaCreationParams(count = 1, resolution = "1024x1024")

        suspend fun configure() {
            store.awaitLoaded()
            val chatProvider = ProviderSetting.OpenAI(name = "Media fixture key", baseUrl = "$url/v1",
                apiKey = "media-fixture-key", models = listOf(fixtureChat))
            val images = MediaGenerationProviderSetting.OpenAI(baseUrl = "https://invalid.test/v1",
                apiKey = "invalid-placeholder", credentialRef = chatProvider.id, models = listOf(mediaImage))
            val videos = MediaGenerationProviderSetting.SiliconFlow(baseUrl = "$url/v1",
                apiKey = "media-fixture-key", models = listOf(t2v, i2v))
            store.update { it.copy(
                providers = listOf(chatProvider), chatModelId = fixtureChat.id, imageGenerationModelId = mediaImage.id,
                mediaGenerationProviders = listOf(images, videos), assistants = listOf(assistant),
                enableAutoTitle = false, enableSuggestion = false, enableBackgroundSubagent = true,
                scheduledHeadlessEnabled = false, uploadS3Config = S3Config(),
            ) }
            withTimeout(15_000) { store.settingsFlow.first { it.imageGenerationModelId == mediaImage.id } }
        }

        suspend fun promptOptimization() {
            val fastModel = Model(modelId = "fixture-fast")
            store.update { settings ->
                settings.copy(
                    fastModelId = fastModel.id,
                    providers = settings.providers.map { it.copyProvider(models = it.models + fastModel) },
                )
            }
            withTimeout(15_000) { store.settingsFlow.first { it.fastModelId == fastModel.id } }
            val original = "media-e2e-prompt-$token"
            val session = media.getOrCreateEmptySession(MediaCreationDraft(prompt = original))
            withContext(Dispatchers.Main) {
                val vm = MediaCreationVM(
                    id = session.id.toString(),
                    context = app,
                    settingsStore = store,
                    repository = media,
                    service = service,
                    remoteFileStore = koin.get(),
                    appScope = koin.get(),
                    providerManager = koin.get(),
                    usageRecorder = koin.get(),
                )
                try {
                    withTimeout(15_000) { vm.draft.first { it.prompt == original } }
                    val revised = vm.optimizePrompt(original, MediaKind.IMAGE).getOrThrow()
                    assertEquals("A lighthouse in warm evening light", revised)
                    assertEquals(original, vm.promptState.text.toString())

                    vm.promptState.setTextAndPlaceCursorAtEnd("Edited while waiting")
                    vm.applyOptimizedPrompt(original, revised)
                    assertEquals("Edited while waiting", vm.promptState.text.toString())

                    vm.promptState.setTextAndPlaceCursorAtEnd(original)
                    vm.applyOptimizedPrompt(original, revised)
                    assertEquals(revised, vm.promptState.text.toString())
                } finally {
                    vm.viewModelScope.cancel()
                }
            }
            val request = events(original, "/v1/chat/completions").single()
            assertEquals("fixture-fast", request.text("model"))
            assertEquals(2, request.getValue("messages").jsonPrimitive.int)
        }

        suspend fun templates() {
            val image = option(mediaImage)
            val galleryPrompt = "media-e2e-$token-gallery"
            val gallery = koin.get<ImageGenerationRunner>().generate(store.settingsFlow.value,
                MediaGenerationRequest(prompt = galleryPrompt, count = 1, resolution = "1024x1024"))
                .toList().filterIsInstance<ImageGenerationUpdate.Saved>().single()
            assertTrue(File(gallery.image.filePath).length() > 0)
            assertTrue(events(galleryPrompt, "/v1/images/generations").single()["fixtureAuth"]!!.jsonPrimitive.boolean)

            val manualPrompt = "media-e2e-$token-manual"
            val session = media.getOrCreateSession(Uuid.random(), "Manual image fixture")
            val submitted = service.submit(MediaCreationSubmission(
                session.id, option = image, prompt = manualPrompt, params = imageParams, assets = emptyList(),
            ))
            assertOutput(awaitRecord(submitted.id), "image/png")

            val files = koin.get<ScheduledMediaAssetStore>()
            val original = File(evidence, "original.png").apply { writeBytes(get("/fixture.png")) }
            val locked = files.importImages(listOf(Uri.fromFile(original)), ScheduledMediaAssetStore.newFormDirId()).single()
            assertTrue(original.delete())
            val text = startTemplate("text", image, imageParams)
            assertOutput(passedTemplate(text), "image/png")
            val edit = startTemplate("edit", image, imageParams, ScheduledMediaOperation.IMAGE_EDIT,
                listOf(ScheduledMediaAsset(locked)))
            assertOutput(passedTemplate(edit), "image/png")
            assertTrue(events(edit.prompt, "/v1/images/edits").single()["hasImage"]!!.jsonPrimitive.boolean)

            val video = startTemplate("video", option(t2v), videoParams)
            assertOutput(passedTemplate(video), "video/mp4")
            assertFalse(events(video.prompt, "/v1/video/submit").single()["hasImage"]!!.jsonPrimitive.boolean)
            val firstFrame = startTemplate("firstframe", option(i2v), videoParams, ScheduledMediaOperation.FIRST_FRAME,
                listOf(ScheduledMediaAsset(locked, role = ImageRole.FIRST_FRAME)))
            assertOutput(passedTemplate(firstFrame), "video/mp4")
            assertTrue(events(firstFrame.prompt, "/v1/video/submit").single()["hasImage"]!!.jsonPrimitive.boolean)

            val blocked = startTemplate("cancel", option(t2v), videoParams)
            val blockedId = Uuid.parse(runOf(blocked).id)
            await { media.getRecord(blockedId)?.takeIf { it.taskId != null } }
            val concurrent = startTemplate("concurrent", image, imageParams)
            assertOutput(passedTemplate(concurrent), "image/png")
            assertNull(runOf(blocked).finishedAt)
            assertTrue(runs.cancel(blockedId.toString()))
            assertEquals(TaskRunStatus.CANCELLED, awaitRun(blocked).status)
            assertEquals(MediaCreationStatus.CANCELLED, media.getRecord(blockedId)?.status)
            assertEquals(1, events(blocked.prompt, "/v1/video/submit").size)

            val selected = ScheduledMediaAsset(locked)
            val wrongDestination = createTemplate("destination", image, imageParams, ScheduledMediaOperation.IMAGE_EDIT,
                listOf(selected))
            val s3 = S3Config(endpoint = "https://fixture-unused.test", bucket = "other-destination",
                accessKeyId = "fixture", secretAccessKey = "fixture")
            setUpload(s3)
            run(wrongDestination)
            assertTrue(awaitRun(wrongDestination).runSummary.orEmpty().contains("destination changed"))
            assertTrue(events(wrongDestination.prompt, "/v1/images/edits").isEmpty())
            setUpload(S3Config())
            files.deleteAssets(listOf(selected))
            val missing = startTemplate("missing", image, imageParams, ScheduledMediaOperation.IMAGE_EDIT, listOf(selected))
            val failure = awaitRun(missing)
            assertEquals(TaskRunStatus.FAILED, failure.status)
            assertEquals(app.getString(R.string.media_creation_error_asset_missing), failure.runSummary)
            assertTrue(events(missing.prompt, "/v1/images/edits").isEmpty())
        }

        suspend fun chat() {
            val ordinary = newConversation()
            val ordinaryPrompt = "media-e2e-chat-$token-ordinary"
            chat.sendMessage(ordinary, listOf(UIMessagePart.Text(ordinaryPrompt)))
            val pending = withTimeout(30_000) {
                chat.getConversationFlow(ordinary).first { conversation ->
                    conversation.currentMessages.any { message ->
                        message.parts.filterIsInstance<UIMessagePart.Tool>().any { it.isPending }
                    }
                }.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().first { it.isPending }
            }
            assertTrue(events(ordinaryPrompt, "/v1/images/generations").isEmpty())
            chat.handleToolApproval(ordinary, pending.toolCallId, approved = true)
            await { events(ordinaryPrompt, "/v1/images/generations").singleOrNull() }

            for (headless in listOf(false, true)) {
                store.update { it.copy(scheduledHeadlessEnabled = headless) }
                withTimeout(15_000) { store.settingsFlow.first { it.scheduledHeadlessEnabled == headless } }
                val mode = if (headless) "headless" else "injected"
                val bare = createChatTask("media-e2e-chat-$token-scheduled-$mode-denied", null)
                assertEquals(TaskRunStatus.OK, run(bare))
                assertTrue(events(bare.prompt, "/v1/chat/completions").isNotEmpty())
                assertTrue(events(bare.prompt, "/v1/chat/completions").all { !it["imageTool"]!!.jsonPrimitive.boolean })
                assertTrue(events(bare.prompt, "/v1/images/generations").isEmpty())

                val grant = grant(option(mediaImage), imageParams)
                val allowed = createChatTask("media-e2e-chat-$token-scheduled-$mode-allowed", grant)
                assertEquals(TaskRunStatus.OK, run(allowed))
                val calls = events(allowed.prompt, "/v1/chat/completions")
                assertTrue("The fixture did not try the three tool calls: $calls", calls.any { it["results"]!!.jsonPrimitive.int == 3 })
                val generated = events(allowed.prompt, "/v1/images/generations").single()
                assertEquals(1, generated["count"]!!.jsonPrimitive.int)
                assertEquals("1024x1024", generated.text("size"))
            }

            val original = option(mediaImage)
            val revoked = createChatTask("media-e2e-chat-$token-scheduled-revoked", grant(original, imageParams))
            assertTrue(tasks.update(
                id = revoked.id, title = revoked.title, prompt = revoked.prompt, scheduleType = ScheduleType.ONCE,
                payload = SchedulePayload.decode(revoked.schedulePayload),
                missPolicy = MissPolicy.RUN_LATE, idleOnly = false,
                editMediaAction = true, mediaAction = null,
            ))
            assertEquals(TaskRunStatus.OK, run(revoked))
            assertTrue(events(revoked.prompt, "/v1/images/generations").isEmpty())

            val stale = createChatTask("media-e2e-chat-$token-scheduled-stale", grant(original, imageParams))
            store.update { settings ->
                val replacement = ProviderSetting.OpenAI(baseUrl = "$url/changed", apiKey = "media-fixture-key")
                val mediaProviders = settings.mediaGenerationProviders.map { provider ->
                    if (provider is MediaGenerationProviderSetting.OpenAI) provider.copy(credentialRef = replacement.id)
                    else provider
                }
                settings.copy(providers = settings.providers + replacement, mediaGenerationProviders = mediaProviders)
            }
            withTimeout(15_000) {
                store.settingsFlow.first { settings ->
                    settings.mediaModelCatalog().single { it.model.id == mediaImage.id }.authorizationFingerprint() !=
                        original.authorizationFingerprint()
                }
            }
            assertEquals(TaskRunStatus.OK, run(stale))
            assertTrue(events(stale.prompt, "/v1/chat/completions").all { !it["imageTool"]!!.jsonPrimitive.boolean })
            assertTrue(events(stale.prompt, "/v1/images/generations").isEmpty())

            val removed = createChatTask("media-e2e-chat-$token-scheduled-removed", grant(option(mediaImage), imageParams))
            store.update { settings ->
                settings.copy(mediaGenerationProviders = settings.mediaGenerationProviders.filterNot {
                    it is MediaGenerationProviderSetting.OpenAI
                })
            }
            withTimeout(15_000) { store.settingsFlow.first { settings ->
                settings.mediaModelCatalog().none { it.model.id == mediaImage.id }
            } }
            assertEquals(TaskRunStatus.OK, run(removed))
            assertTrue(events(removed.prompt, "/v1/chat/completions").all { !it["imageTool"]!!.jsonPrimitive.boolean })
            assertTrue(events(removed.prompt, "/v1/images/generations").isEmpty())
        }

        suspend fun chatMedia() {
            verifyIndexMigration()
            val coordinator = koin.get<ChatMediaGeneration>()
            val conversation = Uuid.random().toString()
            val prompt = "media-e2e-$token-chatmedia"
            val first = coordinator.generate(conversation, "turn1", option(mediaImage), prompt, 1, "auto", emptyList())
            assertEquals(first.id, coordinator.generate(conversation, "turn1", option(mediaImage), prompt, 1, "auto", emptyList()).id)
            val completed = awaitRecord(first.id)
            assertOutput(completed, "image/png")
            assertEquals(1, events(prompt, "/v1/images/generations").size)
            val result = imageToolMediaResult(completed) { media.resolve(it).absolutePath }
            val generatedMessage = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call", "generate_image", "{}", listOf(UIMessagePart.Text(result)))),
            )
            val references = coordinator.references(listOf(generatedMessage))
            assertEquals(1, references.size)
            assertTrue(coordinator.references(emptyList()).isEmpty())
            renderChatMediaCard(generatedMessage.parts.single() as UIMessagePart.Tool, completed)

            // Match the UI handoff: a private chat attachment copy retains provenance for versioning.
            val files = koin.get<FilesManager>()
            val copies = withContext(Dispatchers.IO) {
                files.createChatFilesByContents(listOf(Uri.fromFile(media.resolve(completed.outputs.single().path))))
            }
            val attachment = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(copies.single().toString(),
                metadata = kotlinx.serialization.json.buildJsonObject {
                    put("media_record_id", kotlinx.serialization.json.JsonPrimitive(completed.id.toString()))
                })))
            val selected = coordinator.references(listOf(generatedMessage, attachment)).values.last()
            assertTrue("Reference path must resolve inside this installation: ${selected.path}", media.resolve(selected.path).isFile)
            assertEquals(completed.nodeId, selected.nodeId)
            val edited = coordinator.generate(conversation, "turn2", option(mediaImage), "$prompt-edit", 1, "auto", listOf(selected))
            val editedResult = awaitRecord(edited.id)
            assertOutput(editedResult, "image/png")
            assertEquals(completed.nodeId, editedResult.nodeId)
            assertEquals(2, media.getVersionIds(completed.nodeId).size)
            assertEquals(true, events("$prompt-edit", "/v1/images/edits").single()["hasImage"]!!.jsonPrimitive.boolean)
            val fork = coordinator.generate(Uuid.random().toString(), "fork", option(mediaImage), "$prompt-fork", 1, "auto", listOf(selected))
            assertTrue(completed.nodeId != awaitRecord(fork.id).nodeId)
            service.deleteRecord(completed.id)
            assertTrue(media.resolve(editedResult.inputs.single().path).isFile)

            val gateway = coordinator.generate(conversation, "gateway", option(mediaImage), "$prompt-gateway", 1, "auto", emptyList())
            assertEquals(MediaCreationStatus.SUBMIT_UNKNOWN, awaitRecord(gateway.id).status)
            repeat(2) { service.resumePending(); delay(250) }
            assertEquals(1, events("$prompt-gateway", "/v1/images/generations").size)
            assertEquals(1, events(prompt, "/v1/images/generations").size)

            val db = koin.get<AppDatabase>().openHelper.writableDatabase
            fun plan(sql: String): String = db.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
                buildString { while (cursor.moveToNext()) append(cursor.getString(3)) }
            }
            assertTrue(plan("SELECT id FROM media_creation_record WHERE session_id = '${edited.sessionId}'").contains("index_media_creation_record_session_id"))
            assertTrue(plan("SELECT id FROM media_creation_record WHERE status = 'RUNNING'").contains("index_media_creation_record_status"))
            assertTrue(plan("SELECT id FROM genmediaentity WHERE path = 'missing'").contains("index_GenMediaEntity_path", ignoreCase = true))
            db.execSQL("""
                WITH RECURSIVE seq(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM seq WHERE x<5000)
                INSERT INTO media_creation_record
                    (id, session_id, node_id, provider_id, provider_name, model_id, kind, prompt,
                     params, inputs, status, task_id, error, outputs, create_at, update_at, catalog_model_id, request_id)
                SELECT printf('00000000-0000-0000-0001-%012d', x), session_id, node_id,
                    provider_id, provider_name, model_id, kind, prompt, params, inputs, status, task_id,
                    error, outputs, create_at, update_at, catalog_model_id, request_id
                FROM media_creation_record, seq WHERE id = '${edited.id}'
            """.trimIndent())
            val galleryPath = "images/chat_${edited.id}_0.png"
            assertTrue(media.resolve(galleryPath).isFile)
            val start = android.os.SystemClock.elapsedRealtime()
            service.deleteSession(edited.sessionId)
            File(evidence, "delete-5000-ms.txt").writeText((android.os.SystemClock.elapsedRealtime() - start).toString())
            assertTrue(media.getRecordIdsOfSession(edited.sessionId).isEmpty())
            assertFalse(media.sessionDir(edited.sessionId).exists())
            assertTrue(media.resolve(galleryPath).isFile)

            val loader = coil3.ImageLoader.Builder(app).components {
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { http }))
            }.build()
            try {
                val decoded = loader.execute(boundedImagePreviewRequest(app, "$url/large-preview.png"))
                check(decoded is coil3.request.SuccessResult) { decoded.toString() }
                assertTrue(decoded.image.width <= 2048 && decoded.image.height <= 2048)
                File(evidence, "preview-size.txt").writeText("${decoded.image.width}x${decoded.image.height}")
            } finally { loader.shutdown() }
        }

        suspend fun seedChatMedia() {
            val coordinator = koin.get<ChatMediaGeneration>()
            val conversation = Uuid.random().toString()
            val prompt = "media-e2e-$token-restart"
            val completed = coordinator.generate(conversation, "completed", option(mediaImage), prompt, 1, "auto", emptyList())
            assertOutput(awaitRecord(completed.id), "image/png")
            val unknown = coordinator.generate(conversation, "unknown", option(mediaImage), "$prompt-unknown", 1, "auto", emptyList())
            await { events("$prompt-unknown", "/v1/images/generations").singleOrNull() }
            File(evidence, "chat-restart.ids").writeText("${completed.id}\n${unknown.id}\n$prompt")
            File(evidence, "chat-restart.ready").writeText("Kill the isolated test process now")
            awaitCancellation()
        }

        private fun verifyIndexMigration() {
            val schema = JsonInstant.parseToJsonElement(app.assets.open(
                "app.androidrik.rikkahub.data.db.AppDatabase/81.json").bufferedReader().use { it.readText() })
                .jsonObject.getValue("database").jsonObject
            val name = "media-index-migration-$token.db"
            val file = app.getDatabasePath(name).also { it.parentFile?.mkdirs() }
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                schema.getValue("entities").jsonArray.forEach { entity ->
                    val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                    fun sql(value: kotlinx.serialization.json.JsonElement) = value.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                    db.execSQL(sql(entity.jsonObject.getValue("createSql")))
                    (entity.jsonObject["indices"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { index ->
                        db.execSQL(sql(index.jsonObject.getValue("createSql")))
                    }
                }
                schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                db.execSQL("INSERT INTO GenMediaEntity(path,model_id,prompt,create_at,type) VALUES('images/old.png','old-model','old-prompt',1,'image_generation')")
                db.version = 81
            }
            val migrated = Room.databaseBuilder(app, AppDatabase::class.java, name).build()
            try {
                val db = migrated.openHelper.writableDatabase
                assertEquals(82, db.version)
                db.query("SELECT model_id,prompt FROM GenMediaEntity WHERE path='images/old.png'").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("old-model", it.getString(0))
                    assertEquals("old-prompt", it.getString(1))
                }
            } finally { migrated.close() }
            File(evidence, "migration-81-82.passed").writeText("Old gallery row retained; Room schema validated")
        }

        private suspend fun renderChatMediaCard(tool: UIMessagePart.Tool, record: MediaCreationRecord) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val selected = java.util.concurrent.atomic.AtomicReference<String?>()
            val stack = mutableListOf<androidx.navigation3.runtime.NavKey>()
            val navigator = Navigator(stack)
            val content = JsonInstant.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text)
            ActivityScenario.launch<SketchTestHostActivity>(android.content.Intent(app, SketchTestHostActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        androidx.compose.material3.MaterialTheme {
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalNavController provides navigator,
                                LocalChatMediaReference provides { path, _ -> selected.set(path) },
                            ) {
                                androidx.compose.foundation.layout.Column {
                                    GenerateImageToolUI.Summary(ToolUIContext(tool, JsonObject(emptyMap()), content, false))
                                }
                            }
                        }
                    }
                }
                fun button(label: String): android.view.accessibility.AccessibilityNodeInfo? {
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                        if (node.text?.toString()?.contains(label) == true) {
                            generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.let { return it }
                        }
                        for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it) }?.let { return it }
                        return null
                    }
                    return automation.rootInActiveWindow?.let(::visit)
                }
                delay(500)
                automation.takeScreenshot()?.let { bitmap ->
                    File(evidence, "chat-media-before.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                File(evidence, "ui-stage.txt").writeText("Awaiting reference button")
                val referenceButton = await { button("作为参考图继续编辑") }
                assertTrue(referenceButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await { selected.get() }
                assertEquals(media.resolve(record.outputs.single().path).absolutePath, selected.get())
                automation.takeScreenshot()?.let { bitmap ->
                    File(evidence, "chat-media-card.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                assertTrue(await { button("打开创作记录") }.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await { (stack.lastOrNull() == Screen.MediaCreation(record.sessionId.toString())).takeIf { it } }
            }
        }

        suspend fun recoverChatMedia() {
            val (completedId, unknownId, prompt) = File(evidence, "chat-restart.ids").readLines()
            service.resumePending()
            await { media.getRecord(Uuid.parse(unknownId))?.takeIf { it.status == MediaCreationStatus.SUBMIT_UNKNOWN } }
            service.resumePending()
            delay(500)
            assertOutput(checkNotNull(media.getRecord(Uuid.parse(completedId))), "image/png")
            assertEquals(1, events(prompt, "/v1/images/generations").size)
            assertEquals(1, events("$prompt-unknown", "/v1/images/generations").size)
        }

        suspend fun seedInterruption() {
            val video = option(t2v)
            val preparingSession = media.getOrCreateSession(Uuid.random(), "Preparing fixture")
            val preparing = MediaCreationRecord(
                sessionId = preparingSession.id, providerId = video.providerId, providerName = video.providerName,
                catalogModelId = video.model.id, modelId = video.model.modelId, kind = MediaKind.VIDEO,
                prompt = "media-e2e-$token-preparing", params = videoParams,
            )
            media.insertRecord(preparing)
            val resumable = startTemplate("resume", video, videoParams)
            val waitingForId = startTemplate("unknown", video, videoParams)
            val knownRun = runOf(resumable)
            val unknownRun = runOf(waitingForId)
            await {
                val known = media.getRecord(Uuid.parse(knownRun.id))
                val unknown = media.getRecord(Uuid.parse(unknownRun.id))
                (known?.taskId == resumable.prompt && unknown?.status == MediaCreationStatus.RUNNING &&
                    unknown.taskId == null && events(waitingForId.prompt, "/v1/video/submit").size == 1).takeIf { it }
            }
            File(evidence, "restart.ids").writeText(
                listOf(knownRun.id, unknownRun.id, resumable.prompt, waitingForId.prompt, preparing.id.toString())
                    .joinToString("\n"),
            )
            File(evidence, "restart.ready").writeText("Both submission windows are open")
            awaitCancellation()
        }

        suspend fun recoverInterruption() {
            val (knownRun, unknownRun, knownPrompt, unknownPrompt, preparingId) = File(evidence, "restart.ids").readLines()
            release(knownPrompt)
            service.resumePending()
            withTimeout(40_000) { koin.get<ScheduledMediaRunner>().reconcile() }
            assertOutput(awaitRecord(Uuid.parse(knownRun)), "video/mp4")
            val unknown = awaitRecord(Uuid.parse(unknownRun))
            assertEquals(MediaCreationStatus.SUBMIT_UNKNOWN, unknown.status)
            assertNull(unknown.taskId)
            val preparing = awaitRecord(Uuid.parse(preparingId))
            assertEquals(MediaCreationStatus.FAILED, preparing.status)
            assertEquals(app.getString(R.string.media_creation_error_interrupted), preparing.error)
            assertTrue(events(preparing.prompt, "/v1/video/submit").isEmpty())
            assertEquals(TaskRunStatus.OK, tasks.runById(knownRun)?.status)
            assertEquals(TaskRunStatus.FAILED, tasks.runById(unknownRun)?.status)
            assertEquals(1, events(knownPrompt, "/v1/video/submit").size)
            assertEquals(1, events(unknownPrompt, "/v1/video/submit").size)
            assertTrue(events(knownPrompt, "/v1/video/status").isNotEmpty())
            assertTrue(events(unknownPrompt, "/v1/video/status").isEmpty())
        }

        /**
         * 请求 ID 对账的种子阶段：两条视频模板先后进入提交窗口并被进程中断——
         * 「实际受理但响应丢失」（accepted_lost，对账应查到并自动续查）与
         * 「根本没收到」（never_received，对账应查无并维持待确认）。
         */
        suspend fun seedReconciliation() {
            val video = option(t2v)
            visibility("accepted_lost")
            val found = startTemplate("reconcile", video, videoParams)
            val foundRun = runOf(found)
            await {
                val record = media.getRecord(Uuid.parse(foundRun.id))
                (record?.status == MediaCreationStatus.RUNNING && record.taskId == null &&
                    record.requestId != null &&
                    events(found.prompt, "/v1/video/submit").size == 1).takeIf { it }
            }
            visibility("never_received")
            val absent = startTemplate("absent", video, videoParams)
            val absentRun = runOf(absent)
            await {
                val record = media.getRecord(Uuid.parse(absentRun.id))
                (record?.status == MediaCreationStatus.RUNNING && record.taskId == null &&
                    record.requestId != null &&
                    events(absent.prompt, "/v1/video/submit").size == 1).takeIf { it }
            }
            File(evidence, "reconcile.ids").writeText(
                listOf(foundRun.id, absentRun.id, found.prompt, absent.prompt).joinToString("\n"),
            )
            File(evidence, "reconcile.ready").writeText("Both reconciliation windows are open")
            awaitCancellation()
        }

        /** 重启后的对账阶段：查到的自动转回可续查跑完；查无的维持待确认；全程无第二次提交。 */
        suspend fun recoverReconciliation() {
            val (foundRun, absentRun, foundPrompt, absentPrompt) = File(evidence, "reconcile.ids").readLines()
            visibility("normal")
            service.resumePending()
            withTimeout(40_000) { koin.get<ScheduledMediaRunner>().reconcile() }

            // 查到：无需用户处置，自动写回任务 ID 转回可续查并跑完
            val resumed = awaitRecord(Uuid.parse(foundRun))
            assertOutput(resumed, "video/mp4")
            assertNotNull(resumed.requestId)
            // 对账键在提交前落库、且确实随提交载荷下发（submit trace 的 requestId 与记录一致）
            assertEquals(resumed.requestId, events(foundPrompt, "/v1/video/submit").single().text("requestId"))
            // 服务端按对账键找回：任务 ID 即对账键（reconcile 响应回显），随后走了状态查询
            assertEquals(resumed.requestId, resumed.taskId)
            assertTrue(reconcileEvents(resumed.requestId!!).single().get("found")!!.jsonPrimitive.boolean)
            assertTrue(events(resumed.taskId!!, "/v1/video/status").isNotEmpty())

            // 查无：服务端确认没收到 → 维持待确认（此时重试安全），不自动重新提交
            val absent = awaitRecord(Uuid.parse(absentRun))
            assertEquals(MediaCreationStatus.SUBMIT_UNKNOWN, absent.status)
            assertNull(absent.taskId)
            assertNotNull(absent.requestId)
            assertFalse(reconcileEvents(absent.requestId!!).single().get("found")!!.jsonPrimitive.boolean)

            // 对账是只读的：任何分支都不会产生第二次提交
            assertEquals(1, events(foundPrompt, "/v1/video/submit").size)
            assertEquals(1, events(absentPrompt, "/v1/video/submit").size)
            // 运行账本随记录收尾
            assertEquals(TaskRunStatus.OK, tasks.runById(foundRun)?.status)
            assertEquals(TaskRunStatus.FAILED, tasks.runById(absentRun)?.status)
        }

        private suspend fun setUpload(config: S3Config) {
            store.update { it.copy(uploadS3Config = config) }
            withTimeout(15_000) { store.settingsFlow.first { it.uploadS3Config == config } }
        }

        private fun option(model: MediaGenerationModel) =
            store.settingsFlow.value.mediaModelCatalog().single { it.model.id == model.id }

        private fun grant(
            option: MediaModelOption,
            params: MediaCreationParams,
            prompt: String? = null,
            operation: ScheduledMediaOperation = ScheduledMediaOperation.TEXT,
            assets: List<ScheduledMediaAsset> = emptyList(),
        ) = ScheduledMediaAction(option.ref, params, option.authorizationFingerprint(), prompt, operation, assets,
            store.settingsFlow.value.uploadS3Config.uploadDestinationFingerprint())

        private suspend fun createTemplate(
            tag: String,
            option: MediaModelOption,
            params: MediaCreationParams,
            operation: ScheduledMediaOperation = ScheduledMediaOperation.TEXT,
            assets: List<ScheduledMediaAsset> = emptyList(),
        ): ScheduledTaskEntity {
            val prompt = "media-e2e-$token-$tag"
            return createTask("", prompt, grant(option, params, prompt, operation, assets))
        }

        private suspend fun startTemplate(
            tag: String,
            option: MediaModelOption,
            params: MediaCreationParams,
            operation: ScheduledMediaOperation = ScheduledMediaOperation.TEXT,
            assets: List<ScheduledMediaAsset> = emptyList(),
        ) = createTemplate(tag, option, params, operation, assets).also { run(it) }

        private suspend fun newConversation(): Uuid =
            checkNotNull(chat.createTaskConversation(assistant.id, "Media fixture"))

        private suspend fun createChatTask(prompt: String, grant: ScheduledMediaAction?) =
            createTask(newConversation().toString(), prompt, grant)

        private suspend fun createTask(conversationId: String, prompt: String, grant: ScheduledMediaAction?) =
            tasks.create(
                conversationId = conversationId, title = prompt, prompt = prompt, scheduleType = ScheduleType.ONCE,
                payload = SchedulePayload(runAt = System.currentTimeMillis()), mediaAction = grant,
            )

        private suspend fun run(task: ScheduledTaskEntity): String? {
            assertTrue(tasks.claim(task))
            return withTimeout(55_000) { runner.runClaimed(task, "media-fixture") }
        }

        private suspend fun runOf(task: ScheduledTaskEntity) = tasks.allRunsOfTask(task.id).single()

        private suspend fun awaitRun(task: ScheduledTaskEntity): ScheduledTaskRunEntity =
            await { runOf(task).takeIf { it.finishedAt != null } }

        private suspend fun passedTemplate(task: ScheduledTaskEntity): MediaCreationRecord {
            val run = awaitRun(task)
            assertEquals(run.runSummary, TaskRunStatus.OK, run.status)
            assertEquals(run.id, run.mediaSessionId)
            assertNull(run.conversationId)
            assertTrue(media.getSession(Uuid.parse(run.id)) != null)
            return awaitRecord(Uuid.parse(run.id))
        }

        private suspend fun awaitRecord(id: Uuid): MediaCreationRecord =
            checkNotNull(withTimeout(40_000) { service.awaitRecord(id) })

        private fun assertOutput(record: MediaCreationRecord, mimeType: String) {
            assertEquals(record.error, MediaCreationStatus.SUCCEEDED, record.status)
            val output = record.outputs.single()
            assertEquals(mimeType, output.mimeType)
            assertTrue(media.resolve(output.path).length() > 0)
            assertTrue("Fixture artifact did not decode: ${output.path}", (output.width ?: 0) > 0)
        }

        private suspend fun <T : Any> await(condition: suspend () -> T?): T = withTimeout(40_000) {
            var value = condition()
            while (value == null) {
                delay(100)
                value = condition()
            }
            value
        }

        private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull

        private fun events(prompt: String, path: String) =
            JsonInstant.parseToJsonElement(get("/trace").decodeToString()).jsonArray.map { it.jsonObject }
                .filter { it.text("prompt") == prompt && it.text("path") == path }

        private fun get(path: String) =
            http.newCall(Request.Builder().url("$url$path").build()).execute().use { response ->
                check(response.isSuccessful)
                response.body.bytes()
            }

        private fun release(id: String) {
            val body = """{"requestId":"$id"}""".toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("$url/control/release").post(body).build()).execute().use {
                check(it.isSuccessful)
            }
        }

        /** 受理可见性开关：accepted_lost=受理但响应丢失；never_received=挂住但不落任务。 */
        private fun visibility(mode: String) {
            val body = """{"mode":"$mode"}""".toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url("$url/control/submit-visibility").post(body).build()).execute().use {
                check(it.isSuccessful)
            }
        }

        private fun reconcileEvents(requestId: String) =
            JsonInstant.parseToJsonElement(get("/trace").decodeToString()).jsonArray.map { it.jsonObject }
                .filter { it.text("path") == "/v1/video/reconcile" && it.text("requestId") == requestId }
    }
}
