package app.androidrik.mediagen.provider.providers.siliconflow

import app.androidrik.mediagen.model.ImageRole
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationParameter
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.capabilities
import app.androidrik.mediagen.provider.providers.MediaGenerationApiException
import app.androidrik.mediagen.provider.providers.bodyAsString
import app.androidrik.mediagen.provider.providers.fakeClient
import app.androidrik.mediagen.provider.providers.jsonResponse
import app.androidrik.mediagen.provider.providers.mediaGenerationJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds

class SiliconFlowMediaGenerationProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val setting = MediaGenerationProviderSetting.SiliconFlow(
        apiKey = "sk-test",
        baseUrl = "https://example.com/v1/",
    )
    private val textToVideoModel = MediaGenerationModel(
        modelId = "Wan-AI/Wan2.2-T2V-A14B",
        kind = MediaKind.VIDEO,
    )
    private val imageToVideoModel = MediaGenerationModel(
        modelId = "Wan-AI/Wan2.2-I2V-14B-720P",
        kind = MediaKind.VIDEO,
    )

    private fun parse(body: String) = mediaGenerationJson.parseToJsonElement(body).jsonObject

    @Test
    fun submitBodyMapsProtocolFieldsAndLetsPublicFieldsOverrideExtras() {
        val firstFrame = tempFolder.newFile("frame.png").apply { writeText("png") }

        val body = SiliconFlowMediaGenerationProvider(OkHttpClient()).buildSubmitBody(
            model = imageToVideoModel,
            request = MediaGenerationRequest(
                prompt = "一只猫",
                resolution = "720x1280",
                seed = 42,
                inputs = listOf(MediaGenerationInput.Image(firstFrame.absolutePath)),
                extraParameters = buildJsonObject {
                    put("negative_prompt", "模糊")
                    put("model", "custom/model")
                    put("prompt", "ignored")
                    put("image_size", "960x960")
                    put("seed", 7)
                },
            ),
        )

        assertEquals(JsonPrimitive("Wan-AI/Wan2.2-I2V-14B-720P"), body["model"])
        assertEquals(JsonPrimitive("一只猫"), body["prompt"])
        assertEquals(JsonPrimitive("720x1280"), body["image_size"])
        assertEquals(JsonPrimitive(42), body["seed"])
        // 没有公共字段的供应商参数经 extraParameters 透传
        assertEquals(JsonPrimitive("模糊"), body["negative_prompt"])
        // 本地首帧编码成 base64 data URI
        assertEquals(JsonPrimitive("data:image/png;base64,cG5n"), body["image"])
    }

    @Test
    fun textToVideoOmitsImageAndRemoteImageStaysUrl() {
        val provider = SiliconFlowMediaGenerationProvider(OkHttpClient())

        val textToVideo = provider.buildSubmitBody(
            model = textToVideoModel,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1280x720"),
        )
        assertNull(textToVideo["image"])
        assertNull(textToVideo["negative_prompt"])

        val remote = provider.buildSubmitBody(
            model = imageToVideoModel,
            request = MediaGenerationRequest(
                prompt = "cat",
                resolution = "1280x720",
                inputs = listOf(MediaGenerationInput.Image("https://cdn.example.com/a.png")),
            ),
        )
        assertEquals(JsonPrimitive("https://cdn.example.com/a.png"), remote["image"])
    }

    @Test
    fun createSubmitsToVideoSubmitAndReturnsQueuedTask() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            bodies += it.bodyAsString()
            jsonResponse(it, """{"requestId":"req-1"}""")
        })

        val task = manager.create(
            setting = setting,
            model = textToVideoModel,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1280x720"),
        ).getOrThrow()

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.com/v1/video/submit", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals(
            JsonPrimitive("Wan-AI/Wan2.2-T2V-A14B"),
            parse(bodies.single())["model"],
        )

        assertEquals("req-1", task.id)
        assertEquals(MediaGenerationStatus.QUEUED, task.status)
        assertFalse(task.isTerminal)
        assertTrue(task.outputs.isEmpty())
    }

    @Test
    fun queryPostsRequestIdAndMapsSucceedStatusToVideoOutput() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            bodies += it.bodyAsString()
            jsonResponse(
                it,
                """
                    {
                      "status": "Succeed",
                      "results": {
                        "videos": [{"url": "https://cdn.example.com/out.mp4"}],
                        "timings": {"inference": 12.3},
                        "seed": 42
                      }
                    }
                """.trimIndent(),
            )
        })

        val task = manager.query(setting, imageToVideoModel, "req-1").getOrThrow()

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.com/v1/video/status", request.url.toString())
        assertEquals(JsonPrimitive("req-1"), parse(bodies.single())["requestId"])

        assertEquals(MediaGenerationStatus.SUCCEEDED, task.status)
        assertEquals("req-1", task.id)
        assertEquals("Wan-AI/Wan2.2-I2V-14B-720P", task.model)
        val output = task.outputs.single()
        assertEquals("https://cdn.example.com/out.mp4", output.url)
        assertEquals("video/mp4", output.mimeType)
        assertTrue("results" in task.metadata)
    }

    @Test
    fun queryMapsPendingStatusesWithoutOutputs() = runBlocking {
        listOf(
            "InQueue" to MediaGenerationStatus.QUEUED,
            "InProgress" to MediaGenerationStatus.RUNNING,
        ).forEach { (raw, expected) ->
            val manager = MediaGenerationManager(fakeClient {
                jsonResponse(it, """{"requestId":"req-2","status":"$raw"}""")
            })

            val task = manager.query(setting, textToVideoModel, "req-2").getOrThrow()

            assertEquals(raw, expected, task.status)
            assertFalse(task.isTerminal)
            assertTrue(task.outputs.isEmpty())
            assertNull(task.error)
        }
    }

    @Test
    fun queryMapsFailedStatusWithReason() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"status":"Failed","reason":"内容审核未通过"}""")
        })

        val task = manager.query(setting, textToVideoModel, "req-3").getOrThrow()

        assertEquals(MediaGenerationStatus.FAILED, task.status)
        assertTrue(task.isTerminal)
        assertEquals("内容审核未通过", task.error?.message)
        assertTrue(task.outputs.isEmpty())
    }

    @Test
    fun generatePollsUntilTerminal() = runBlocking {
        val replies = ArrayDeque(
            listOf(
                """{"requestId":"req-9"}""",
                """{"status":"InProgress"}""",
                """{"status":"Succeed","results":{"videos":[{"url":"https://cdn.example.com/out.mp4"}]}}""",
            )
        )
        val requests = mutableListOf<Request>()
        val manager = MediaGenerationManager(fakeClient(requests) {
            jsonResponse(it, replies.removeFirst())
        })

        val tasks = manager.generate(
            setting = setting,
            model = textToVideoModel,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1280x720"),
            interval = 1.milliseconds,
        ).toList()

        assertEquals(
            listOf(
                MediaGenerationStatus.QUEUED,
                MediaGenerationStatus.RUNNING,
                MediaGenerationStatus.SUCCEEDED,
            ),
            tasks.map { it.status },
        )
        assertEquals(
            listOf("/v1/video/submit", "/v1/video/status", "/v1/video/status"),
            requests.map { it.url.encodedPath },
        )
        assertEquals("req-9", tasks.first().id)
    }

    @Test
    fun apiErrorIsSurfacedWithStatusAndCode() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(
                request = it,
                body = """{"code":20012,"message":"Invalid API key","data":null}""",
                code = 400,
            )
        })

        val error = manager.create(
            setting = setting,
            model = textToVideoModel,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1280x720"),
        ).exceptionOrNull() as MediaGenerationApiException

        assertEquals(400, error.statusCode)
        assertEquals("20012", error.code)
        assertEquals("Invalid API key", error.message)
    }

    @Test
    fun missingRequestIdIsAnError() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"status":"Succeed"}""")
        })

        val error = manager.create(
            setting = setting,
            model = textToVideoModel,
            request = MediaGenerationRequest(prompt = "cat", resolution = "1280x720"),
        ).exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("SiliconFlow response does not contain requestId", error?.message)
    }

    @Test
    fun capabilitiesDeclareVideoOnlyWithRequiredImageSize() {
        assertEquals(setOf(MediaKind.VIDEO), setting.supportedKinds)
        assertNull(setting.capabilities(MediaKind.IMAGE))

        val capabilities = setting.capabilities(MediaKind.VIDEO)!!
        assertEquals(
            setOf(MediaGenerationParameter.RESOLUTION, MediaGenerationParameter.SEED),
            capabilities.parameters,
        )
        assertEquals(setOf(MediaGenerationParameter.RESOLUTION), capabilities.requiredParameters)
        assertEquals(setOf(ImageRole.FIRST_FRAME), capabilities.imageRoles)
        assertTrue(capabilities.requiresPrompt)
        // 本地首帧由适配器编码成 data URI，不要求公网地址
        assertFalse(capabilities.requiresRemoteInputs)
        assertFalse(capabilities.videoInput)
    }

    @Test
    fun adapterAcceptsDeclaredParametersAndRejectsTheRest() {
        val provider = SiliconFlowMediaGenerationProvider(OkHttpClient())
        val capabilities = setting.capabilities(MediaKind.VIDEO)!!

        MediaGenerationParameter.entries.forEach { parameter ->
            // image_size 是必填项，每个请求都带上，只比较待测字段是否被接受
            val base = MediaGenerationRequest(prompt = "cat", resolution = "1280x720")
            val request = when (parameter) {
                MediaGenerationParameter.COUNT -> base.copy(count = 2)
                MediaGenerationParameter.RESOLUTION -> base
                MediaGenerationParameter.ASPECT_RATIO -> base.copy(aspectRatio = "16:9")
                MediaGenerationParameter.DURATION -> base.copy(durationSeconds = 5)
                MediaGenerationParameter.GENERATE_AUDIO -> base.copy(generateAudio = true)
                MediaGenerationParameter.WATERMARK -> base.copy(watermark = false)
                MediaGenerationParameter.SEED -> base.copy(seed = 1)
                MediaGenerationParameter.PROMPT_ENHANCEMENT -> base.copy(promptEnhancement = true)
            }
            if (parameter in capabilities.parameters) {
                provider.buildSubmitBody(textToVideoModel, request)
            } else {
                assertThrows("$parameter should be rejected", IllegalArgumentException::class.java) {
                    provider.buildSubmitBody(textToVideoModel, request)
                }
            }
        }
    }

    @Test
    fun invalidInputsAreRejectedBeforeHittingTheNetwork() {
        val provider = SiliconFlowMediaGenerationProvider(OkHttpClient())
        fun body(request: MediaGenerationRequest) = provider.buildSubmitBody(imageToVideoModel, request)

        // 提示词和 image_size 都是必填的
        assertThrows(IllegalArgumentException::class.java) {
            body(
                MediaGenerationRequest(
                    resolution = "1280x720",
                    inputs = listOf(MediaGenerationInput.Image("https://e.com/a.png")),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat"))
        }
        // 只接受一张图片作为输入
        assertThrows(IllegalArgumentException::class.java) {
            body(
                MediaGenerationRequest(
                    prompt = "cat",
                    resolution = "1280x720",
                    inputs = listOf(
                        MediaGenerationInput.Image("https://e.com/a.png"),
                        MediaGenerationInput.Image("https://e.com/b.png"),
                    ),
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            body(
                MediaGenerationRequest(
                    prompt = "cat",
                    resolution = "1280x720",
                    inputs = listOf(MediaGenerationInput.Video("https://e.com/a.mp4")),
                )
            )
        }
        // 回调地址不在接口约定里
        assertThrows(IllegalArgumentException::class.java) {
            body(MediaGenerationRequest(prompt = "cat", resolution = "1280x720", callbackUrl = "https://e.com/cb"))
        }
    }

    @Test
    fun kindWithoutAdapterIsRejectedBeforeHittingTheNetwork() = runBlocking {
        val manager = MediaGenerationManager(fakeClient { error("unsupported kind must not hit the network") })
        val image = MediaGenerationModel(modelId = "some-image", kind = MediaKind.IMAGE)

        val error = manager.create(setting, image, MediaGenerationRequest(prompt = "cat")).exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertEquals("siliconflow does not support IMAGE generation", error?.message)
    }

    @Test
    fun settingSurvivesSerializationWithDefaultModels() {
        val original = MediaGenerationProviderSetting.SiliconFlow(apiKey = "key")
        val encoded = Json.encodeToString(MediaGenerationProviderSetting.serializer(), original)
        val decoded = Json.decodeFromString(MediaGenerationProviderSetting.serializer(), encoded)

        assertEquals(original, decoded)
        assertEquals("https://api.siliconflow.cn/v1", decoded.baseUrl)
        assertEquals(
            listOf("Wan-AI/Wan2.2-T2V-A14B", "Wan-AI/Wan2.2-I2V-14B-720P"),
            decoded.models.map { it.modelId },
        )
        assertEquals(setOf(MediaKind.VIDEO), decoded.supportedKinds)
    }

    @Test
    fun t2vModelRejectsImageInputsBeforeNetwork() {
        val provider = SiliconFlowMediaGenerationProvider(OkHttpClient())
        val t2v = MediaGenerationModel(modelId = "Wan-AI/Wan2.2-T2V-A14B", kind = MediaKind.VIDEO)
        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.buildSubmitBody(
                model = t2v,
                request = MediaGenerationRequest(
                    prompt = "a cat",
                    resolution = "1280x720",
                    inputs = listOf(MediaGenerationInput.Image("https://cdn.example.com/a.png")),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("T2V"))
    }

    @Test
    fun i2vModelRequiresExactlyOneFirstFrame() {
        val provider = SiliconFlowMediaGenerationProvider(OkHttpClient())
        val i2v = MediaGenerationModel(modelId = "Wan-AI/Wan2.2-I2V-14B-720P", kind = MediaKind.VIDEO)
        val error = assertThrows(IllegalArgumentException::class.java) {
            provider.buildSubmitBody(
                model = i2v,
                request = MediaGenerationRequest(prompt = "a cat", resolution = "1280x720"),
            )
        }
        assertTrue(error.message.orEmpty().contains("first-frame"))
    }
}
