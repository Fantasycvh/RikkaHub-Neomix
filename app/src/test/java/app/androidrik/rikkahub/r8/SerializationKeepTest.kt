package app.androidrik.rikkahub.r8

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.CatalogError
import app.androidrik.ai.provider.CatalogErrorKind
import app.androidrik.ai.provider.CatalogFetchResult
import app.androidrik.ai.provider.CatalogScope
import app.androidrik.ai.provider.IncompleteReason
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.ClaudeReasoningMetadata
import app.androidrik.ai.ui.DiffMetadata
import app.androidrik.ai.ui.GoogleInteractionsMetadata
import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.OpenRouterReasoningMetadata
import app.androidrik.ai.ui.PartMetadata
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.common.android.LogEntry
import app.androidrik.common.http.ProviderProxy
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.search.SearchServiceOptions
import app.androidrik.tts.provider.TTSProviderSetting
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType
import kotlin.uuid.Uuid
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R8 多态 `@Serializable` sealed 家族守卫测试（借鉴华灯 5 项防回归契约并升级为全仓自动发现）。
 *
 * 防御目标：
 * 1. 规则单一归口：所有自定义 keep 规则必须收口在 `app/src/main/keepRules/`，禁止散落旧文件；
 * 2. 全仓零遗漏：自动扫描全仓所有模块的 `@Serializable sealed` 类族，每个根类在 `serialization.keep`
 *    中必须同时具备基类与 `* extends <Base>` 保活，且严禁带 `allowshrinking` / `allowoptimization`；
 * 3. 合成序列化器保活：锁死 `<1>$Companion` 与 `<1>$$serializer`；
 * 4. 子类注解与显式判别值：递归检查 `sealedSubclasses`，所有具体子类必须可获取 `KSerializer`、
 *    具备唯一且显式的 `@SerialName`（防止类移动/重命名破坏历史落盘 JSON）；
 * 5. 多态编解码回环：覆盖包括 `@Deprecated` / 尚未接线厂商在内的全部高危解码专用子类。
 */
class SerializationKeepTest {

    private val repoRoot: File by lazy {
        val cwd = File(".").canonicalFile
        if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    private val keepRulesDir: File
        get() = File(repoRoot, "app/src/main/keepRules")

    private val serializationKeepText: String by lazy {
        val file = File(keepRulesDir, "serialization.keep")
        assertTrue("缺少 ${file.absolutePath}", file.isFile)
        stripKeepComments(file.readText())
    }

    private val polymorphicSealedRoots: List<KClass<*>> = listOf(
        InputSchema::class,
        BuiltInTools::class,
        CatalogFetchResult::class,
        ProviderSetting::class,
        StreamChunk::class,
        UIMessageAnnotation::class,
        ToolApprovalState::class,
        UIMessagePart::class,
        PromptInjection::class,
        Avatar::class,
        McpServerConfig::class,
        LocalToolOption::class,
        LogEntry::class,
        ProviderProxy::class,
        MediaGenerationInput::class,
        MediaGenerationProviderSetting::class,
        SearchServiceOptions::class,
        ASRProviderSetting::class,
        TTSProviderSetting::class,
    )

    @Test
    fun `keepRules directory is the single source of truth for app R8 rules`() {
        assertFalse(
            "旧 app/proguard-rules.pro 已废弃，规则须统一放入 app/src/main/keepRules/*.keep",
            File(repoRoot, "app/proguard-rules.pro").exists(),
        )
        assertFalse(
            "旧 app/src/release/keepRules 已废弃，规则须统一放入 app/src/main/keepRules/*.keep",
            File(repoRoot, "app/src/release/keepRules").exists(),
        )
        assertFalse(
            "旧混杂的 rikkahub.keep 已拆分为职责文件，不应残留",
            File(keepRulesDir, "rikkahub.keep").exists(),
        )
        val expectedFiles = setOf(
            "diagnostics-and-navigation.keep",
            "ipc-and-reflection.keep",
            "serialization.keep",
            "third-party.keep",
        )
        val actualFiles = keepRulesDir.listFiles()
            ?.filter { it.isFile && it.extension == "keep" }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
        assertTrue(
            "keepRules 缺少核心职责文件：expected=$expectedFiles, actual=$actualFiles",
            actualFiles.containsAll(expectedFiles),
        )

        val appGradle = File(repoRoot, "app/build.gradle.kts").readText()
        assertTrue(
            "app/build.gradle.kts release 构建必须开启 optimization { enable = true }",
            Regex("""optimization\s*\{\s*enable\s*=\s*true""").containsMatchIn(appGradle),
        )
        assertFalse(
            "app/build.gradle.kts 不应再挂载散落的 proguardFiles(...)",
            Regex("""\bproguardFiles\s*\(""").containsMatchIn(appGradle),
        )
    }

    @Test
    fun `every @Serializable sealed root across all modules is registered in serialization keep without allowshrinking`() {
        val discoveredRoots = discoverSerializableSealedRootsInRepo()
        assertTrue("全仓至少应扫描到已知的 @Serializable sealed 根类", discoveredRoots.size >= 17)

        val requiredRoots = discoveredRoots + setOf(
            PartMetadata::class.java.name,
            Screen::class.java.name,
        )

        for (binaryName in requiredRoots.sorted()) {
            val escaped = Regex.escape(binaryName)
            val baseRuleRegex = Regex("""-keep(?:\s*,[^\n]*?)?\s+class\s+$escaped\b""")
            val extendsRuleRegex = Regex("""-keep(?:\s*,[^\n]*?)?\s+class\s+\*\s+extends\s+$escaped\b""")

            val baseMatch = baseRuleRegex.find(serializationKeepText)
            assertNotNull(
                "缺少 sealed 根类保活规则：请在 app/src/main/keepRules/serialization.keep 中补充 `-keep class $binaryName`",
                baseMatch,
            )
            val extendsMatch = extendsRuleRegex.find(serializationKeepText)
            assertNotNull(
                "缺少 sealed 子类继承链保活规则：请在 app/src/main/keepRules/serialization.keep 中补充 `-keep class * extends $binaryName { ... }`",
                extendsMatch,
            )

            for (match in listOf(baseMatch!!, extendsMatch!!)) {
                val header = match.value
                assertFalse(
                    "$binaryName 的 keep 规则严禁包含 allowshrinking（否则解码专用子类仍会被 R8 裁掉）：$header",
                    header.contains("allowshrinking"),
                )
                assertFalse(
                    "$binaryName 的 keep 规则严禁包含 allowoptimization（防止 HorizontalClassMerger 合并两个子类导致 SealedClassSerializer 冲突）：$header",
                    header.contains("allowoptimization"),
                )
            }
        }
    }

    @Test
    fun `synthetic Companion and serializer classes are unconditionally kept for live serializable types`() {
        assertTrue(
            "serialization.keep 必须保活 <1>\$Companion 的 serializer(...) 且不带 allowshrinking",
            Regex(
                "-if\\s+@kotlinx\\.serialization\\.Serializable\\s+class\\s+\\*\\*\\s+-keep\\s+class\\s+<1>\\\$Companion\\s*\\{[^}]*serializer\\(\\.\\.\\.\\)",
            ).containsMatchIn(serializationKeepText),
        )
        assertTrue(
            "serialization.keep 必须保活 <1>\$\$serializer 且不带 allowshrinking",
            Regex(
                "-if\\s+@kotlinx\\.serialization\\.Serializable\\s+class\\s+\\*\\*\\s+-keep\\s+class\\s+<1>\\\$\\\$serializer\\s*\\{\\s*\\*\\s*;\\s*\\}",
            ).containsMatchIn(serializationKeepText),
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every concrete sealed subclass has a working KSerializer, explicit @SerialName, and unique wire discriminator`() {
        val explicitSerialNamesInSource = collectExplicitSerialNamesFromRepo()

        for (root in polymorphicSealedRoots) {
            val leaves = collectConcreteSealedLeaves(root)
            assertTrue("${root.qualifiedName} 至少应有 1 个具体子类", leaves.isNotEmpty())

            val wireNames = mutableMapOf<String, String>()
            for (leaf in leaves) {
                val leafSerializer = runCatching { serializer(leaf.starProjectedType) }.getOrNull()
                assertNotNull(
                    "${leaf.qualifiedName} 无法获取 KSerializer（是否漏标 @Serializable？）",
                    leafSerializer,
                )
                val binaryName = leaf.java.name
                val sourceSerialName = explicitSerialNamesInSource[binaryName]
                assertNotNull(
                    "${leaf.qualifiedName} ($binaryName) 必须在源码中显式标注 @SerialName（防止重命名/移包静默破坏持久化数据；存量未标注类请显式写历史全限定名）",
                    sourceSerialName,
                )
                val wireName = leafSerializer!!.descriptor.serialName
                assertEquals(
                    "${leaf.qualifiedName} 的编译期描述符名称必须与源码 @SerialName 一致",
                    sourceSerialName,
                    wireName,
                )
                val previous = wireNames.put(wireName, leaf.qualifiedName ?: binaryName)
                assertEquals(
                    "${root.qualifiedName} 下存在重复的 @SerialName('$wireName'): $previous vs ${leaf.qualifiedName}",
                    null,
                    previous,
                )
            }
        }

        // Screen 与 PartMetadata 的全部具体子类也必须具备可用 KSerializer
        for (nonPolymorphicRoot in listOf(Screen::class, PartMetadata::class)) {
            val leaves = collectConcreteSealedLeaves(nonPolymorphicRoot)
            assertTrue("${nonPolymorphicRoot.qualifiedName} 子类不应为空", leaves.isNotEmpty())
            for (leaf in leaves) {
                val s = runCatching { serializer(leaf.starProjectedType) }.getOrNull()
                assertNotNull("${leaf.qualifiedName} 缺少 @Serializable 生成的序列化器", s)
            }
        }

        // 钉死 Avatar 存量历史判别值（见 SettingsJsonMigrator V0b）
        assertEquals(
            "app.androidrik.rikkahub.data.model.Avatar.Dummy",
            Avatar.Dummy.serializer().descriptor.serialName,
        )
        assertEquals(
            "app.androidrik.rikkahub.data.model.Avatar.Emoji",
            Avatar.Emoji.serializer().descriptor.serialName,
        )
        assertEquals(
            "app.androidrik.rikkahub.data.model.Avatar.Image",
            Avatar.Image.serializer().descriptor.serialName,
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `polymorphic round trip succeeds for decode-only, deprecated, and unported sealed subclasses`() {
        assertRoundTrip(
            InputSchema.serializer(),
            listOf(InputSchema.Obj(properties = JsonObject(mapOf("q" to JsonPrimitive("string"))))),
        )
        assertRoundTrip(
            BuiltInTools.serializer(),
            listOf(
                BuiltInTools.Search,
                BuiltInTools.UrlContext,
                BuiltInTools.ImageGeneration,
                BuiltInTools.DeepSeekWebSearch,
                BuiltInTools.DeepSeekReadUrl,
            ),
        )
        assertRoundTrip(
            CatalogFetchResult.serializer(),
            listOf(
                CatalogFetchResult.Complete(emptyList(), CatalogScope("openai", listOf("https://api.openai.com/v1"))),
                CatalogFetchResult.Partial(
                    emptyList(),
                    CatalogScope("openai", listOf("https://api.openai.com/v1")),
                    IncompleteReason.LIMIT,
                ),
                CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.NETWORK, "err")),
                CatalogFetchResult.Unsupported(404),
            ),
        )
        assertRoundTrip(
            ProviderSetting.serializer(),
            listOf(
                ProviderSetting.OpenAI(),
                ProviderSetting.Google(),
                ProviderSetting.Claude(),
                ProviderSetting.ChatGPT(),
                ProviderSetting.Grok(),
            ),
        )
        assertRoundTrip(
            ToolApprovalState.serializer(),
            listOf(
                ToolApprovalState.Auto,
                ToolApprovalState.Pending,
                ToolApprovalState.Approved,
                ToolApprovalState.Denied("no"),
                ToolApprovalState.Answered("yes"),
            ),
        )
        // 重点包含 @Deprecated 的历史兼容分片（Search / ToolCall / ToolResult）——最易因业务代码不再 new 而被 R8 裁剪
        assertRoundTrip(
            UIMessagePart.serializer(),
            listOf(
                UIMessagePart.Notice("e1", "notice"),
                UIMessagePart.Text("hello"),
                UIMessagePart.Image("file:///a.png"),
                UIMessagePart.Video("file:///a.mp4"),
                UIMessagePart.Audio("file:///a.wav"),
                UIMessagePart.Document("file:///a.pdf", "a.pdf"),
                UIMessagePart.Search,
                UIMessagePart.ToolCall("tc1", "fn", "{}"),
                UIMessagePart.ToolResult("tc1", "fn", JsonPrimitive("ok"), JsonObject(emptyMap())),
                UIMessagePart.ServerTool("st1", "web_search", status = ServerToolStatus.FAILED),
                UIMessagePart.Tool("t1", "bash", "{}"),
            ),
        )
        assertRoundTrip(
            UIMessageAnnotation.serializer(),
            listOf(
                UIMessageAnnotation.ScheduledTrigger("run-1"),
                UIMessageAnnotation.ExternalNotice(listOf("e1"), "src", listOf("s1")),
                UIMessageAnnotation.SubagentNotice(listOf("r1")),
                UIMessageAnnotation.UrlCitation("Example", "https://example.com"),
                UIMessageAnnotation.PartialFailure,
                UIMessageAnnotation.QuotedMessage(Uuid.random(), MessageRole.USER, "quote"),
                UIMessageAnnotation.InvokedSkill("skill-creator", "do x"),
                UIMessageAnnotation.InjectionSources(listOf(Uuid.random())),
            ),
        )
        assertRoundTrip(
            StreamChunk.serializer(),
            listOf(
                StreamChunk.TextStart("1"),
                StreamChunk.TextDelta("1", "a"),
                StreamChunk.TextEnd("1"),
                StreamChunk.ToolCallOutput("1", "out"),
                StreamChunk.Usage(TokenUsage(1, 2, 3)),
                StreamChunk.Finish("stop"),
            ),
        )
        assertRoundTrip(
            PromptInjection.serializer(),
            listOf(
                PromptInjection.ModeInjection(name = "m"),
                PromptInjection.RegexInjection(name = "r", keywords = listOf("k")),
            ),
        )
        assertRoundTrip(
            Avatar.serializer(),
            listOf(Avatar.Dummy, Avatar.Emoji("😀"), Avatar.Image("file:///avatar.png")),
        )
        assertRoundTrip(
            McpServerConfig.serializer(),
            listOf(
                McpServerConfig.SseTransportServer(url = "https://example.com/sse"),
                McpServerConfig.StreamableHTTPServer(url = "https://example.com/mcp"),
            ),
        )
        // 华灯事故原发类：LocalToolOption 全量 10 个对象子类
        assertRoundTrip(
            LocalToolOption.serializer(),
            listOf(
                LocalToolOption.JavascriptEngine,
                LocalToolOption.TimeInfo,
                LocalToolOption.Clipboard,
                LocalToolOption.Tts,
                LocalToolOption.AskUser,
                LocalToolOption.ScreenTime,
                LocalToolOption.Calendar,
                LocalToolOption.ChartDisplay,
                LocalToolOption.TodoPlan,
                LocalToolOption.PhoneUse,
            ),
        )
        assertRoundTrip(
            LogEntry.serializer(),
            listOf(
                LogEntry.TextLog(tag = "T", message = "m"),
                LogEntry.RequestLog(tag = "T", url = "https://example.com", method = "GET"),
            ),
        )
        assertRoundTrip(
            ProviderProxy.serializer(),
            listOf(
                ProviderProxy.Inherit,
                ProviderProxy.None,
                ProviderProxy.Http("127.0.0.1", 7890),
                ProviderProxy.Socks5("127.0.0.1", 1080),
            ),
        )
        assertRoundTrip(
            MediaGenerationInput.serializer(),
            listOf(
                MediaGenerationInput.Image("https://example.com/a.png"),
                MediaGenerationInput.Video("https://example.com/a.mp4"),
                MediaGenerationInput.Audio("https://example.com/a.mp3"),
                MediaGenerationInput.Document("https://example.com/a.pdf"),
                MediaGenerationInput.WebPage("https://example.com"),
                MediaGenerationInput.Raw(JsonObject(mapOf("k" to JsonPrimitive("v")))),
            ),
        )
        // 重点覆盖尚未接线的 Aliyun / Volcengine / MiniMax 子类
        assertRoundTrip(
            MediaGenerationProviderSetting.serializer(),
            listOf(
                MediaGenerationProviderSetting.OpenAI(),
                MediaGenerationProviderSetting.Aliyun(),
                MediaGenerationProviderSetting.Volcengine(),
                MediaGenerationProviderSetting.MiniMax(),
                MediaGenerationProviderSetting.OpenRouter(),
                MediaGenerationProviderSetting.Google(),
                MediaGenerationProviderSetting.SiliconFlow(),
            ),
        )
        assertRoundTrip(
            SearchServiceOptions.serializer(),
            listOf(
                SearchServiceOptions.BingLocalOptions(),
                SearchServiceOptions.ZhipuOptions(),
                SearchServiceOptions.DoubaoOptions(),
                SearchServiceOptions.TavilyOptions(),
                SearchServiceOptions.ExaOptions(),
                SearchServiceOptions.SearXNGOptions(),
                SearchServiceOptions.LinkUpOptions(),
                SearchServiceOptions.BraveOptions(),
                SearchServiceOptions.MetasoOptions(),
                SearchServiceOptions.OllamaOptions(),
                SearchServiceOptions.PerplexityOptions(),
                SearchServiceOptions.FirecrawlOptions(),
                SearchServiceOptions.JinaOptions(),
                SearchServiceOptions.BochaOptions(),
                SearchServiceOptions.DeepSeekOptions(),
                SearchServiceOptions.GrokOptions(),
                SearchServiceOptions.TinyfishOptions(),
                SearchServiceOptions.SerperOptions(),
                SearchServiceOptions.CustomJsOptions(),
            ),
        )
        assertRoundTrip(
            ASRProviderSetting.serializer(),
            listOf(
                ASRProviderSetting.OpenAIRealtime(),
                ASRProviderSetting.DashScope(),
                ASRProviderSetting.Volcengine(),
                ASRProviderSetting.MiMo(),
                ASRProviderSetting.Step(),
                ASRProviderSetting.DeepSeek(),
            ),
        )
        assertRoundTrip(
            TTSProviderSetting.serializer(),
            listOf(
                TTSProviderSetting.OpenAI(),
                TTSProviderSetting.Gemini(),
                TTSProviderSetting.SystemTTS(),
                TTSProviderSetting.MiniMax(),
                TTSProviderSetting.Qwen(),
                TTSProviderSetting.Groq(),
                TTSProviderSetting.XAI(),
                TTSProviderSetting.MiMo(),
                TTSProviderSetting.ElevenLabs(),
                TTSProviderSetting.Step(),
                TTSProviderSetting.FishAudio(),
                TTSProviderSetting.DeepSeek(),
            ),
        )

        // PartMetadata 经 reified toMetadata / metadataAs 往返校验
        val part = UIMessagePart.Reasoning(
            reasoning = "think",
            metadata = ClaudeReasoningMetadata(signature = "sig-1").toMetadata(),
        )
        assertEquals("sig-1", part.metadataAs<ClaudeReasoningMetadata>()?.signature)
        assertEquals("enc", UIMessagePart.Text("x", OpenAIReasoningMetadata("id", "enc").toMetadata()).metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertEquals("gsig", UIMessagePart.Text("x", GoogleThoughtMetadata(thoughtSignature = "gsig").toMetadata()).metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals("isig", UIMessagePart.Text("x", GoogleInteractionsMetadata(signature = "isig").toMetadata()).metadataAs<GoogleInteractionsMetadata>()?.signature)
        assertEquals("@@ -1 +1 @@", UIMessagePart.Text("x", DiffMetadata(diff = "@@ -1 +1 @@").toMetadata()).metadataAs<DiffMetadata>()?.diff)
        assertEquals(ServerToolProtocol.OPENAI_RESPONSES, UIMessagePart.Text("x", ServerToolMetadata(protocol = ServerToolProtocol.OPENAI_RESPONSES).toMetadata()).metadataAs<ServerToolMetadata>()?.protocol)
        assertEquals(JsonArray(listOf(JsonPrimitive("d"))), UIMessagePart.Text("x", OpenRouterReasoningMetadata(JsonArray(listOf(JsonPrimitive("d")))).toMetadata()).metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails)
    }

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, samples: List<T>) {
        for (sample in samples) {
            val json = JsonInstant.encodeToString(serializer, sample)
            val decoded = JsonInstant.decodeFromString(serializer, json)
            assertEquals("多态序列化回环不一致 (${sample!!::class.qualifiedName}): json=$json", sample, decoded)
        }
    }

    private fun collectConcreteSealedLeaves(root: KClass<*>): List<KClass<*>> {
        val direct = root.sealedSubclasses
        if (direct.isEmpty()) return listOf(root)
        return direct.flatMap { sub ->
            if (sub.isSealed) collectConcreteSealedLeaves(sub) else listOf(sub)
        }
    }

    /**
     * 扫描全仓各模块 `src/main` 下所有顶层 `@Serializable sealed class/interface` 的二进制类名。
     * 嵌套在已受保活根类内部的二级 sealed（如 `ProviderSetting.ApiKey`）也会转换成 `Outer$Inner` 一并校验。
     */
    private fun discoverSerializableSealedRootsInRepo(): Set<String> {
        val result = linkedSetOf<String>()
        val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
        val sealedDeclRegex = Regex(
            """@Serializable\s+(?:@[A-Za-z0-9_.]+\s*(?:\([^)]*\)\s*)?)*(?:(?:public|internal)\s+)?sealed\s+(?:class|interface)\s+([A-Za-z0-9_]+)""",
        )

        repoRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "src/main").isDirectory }
            ?.forEach { moduleDir ->
                File(moduleDir, "src/main").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { ktFile ->
                        val raw = ktFile.readText()
                        if (!raw.contains("@Serializable") || !raw.contains("sealed ")) return@forEach
                        val pkg = packageRegex.find(raw)?.groupValues?.get(1) ?: return@forEach
                        val stripped = stripKotlinCommentsAndStrings(raw)
                        for (match in sealedDeclRegex.findAll(stripped)) {
                            val name = match.groupValues[1]
                            val prefixBefore = stripped.substring(0, match.range.first)
                            val enclosingChain = findEnclosingTypeNames(prefixBefore)
                            val binaryName = if (enclosingChain.isEmpty()) {
                                "$pkg.$name"
                            } else {
                                "$pkg.${(enclosingChain + name).joinToString("$")}"
                            }
                            result += binaryName
                        }
                    }
            }
        return result
    }

    private fun findEnclosingTypeNames(prefix: String): List<String> {
        val stack = ArrayDeque<String?>()
        var pendingType: String? = null
        var parenDepth = 0
        var angleDepth = 0
        var i = 0

        fun readIdent(start: Int): Pair<String, Int>? {
            if (start >= prefix.length || !(prefix[start].isLetter() || prefix[start] == '_')) return null
            var end = start + 1
            while (end < prefix.length && (prefix[end].isLetterOrDigit() || prefix[end] == '_')) end++
            return prefix.substring(start, end) to end
        }

        while (i < prefix.length) {
            val ch = prefix[i]
            when {
                ch == '(' -> {
                    parenDepth++
                    i++
                }
                ch == ')' -> {
                    if (parenDepth > 0) parenDepth--
                    i++
                }
                parenDepth == 0 && ch == '{' -> {
                    stack.addLast(pendingType)
                    pendingType = null
                    i++
                }
                parenDepth == 0 && ch == '}' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    pendingType = null
                    i++
                }
                parenDepth == 0 && (ch.isLetter() || ch == '_') -> {
                    val (word, nextIdx) = readIdent(i)!!
                    i = nextIdx
                    when (word) {
                        "class", "interface", "object" -> {
                            // companion object 不作为外部类名层级（嵌套类不会挂在 Companion 下，若是则是 Companion 成员）
                            while (i < prefix.length && prefix[i].isWhitespace()) i++
                            val namePair = readIdent(i)
                            if (namePair != null && namePair.first !in setOf("by", "where")) {
                                pendingType = namePair.first
                                i = namePair.second
                            } else {
                                pendingType = null
                            }
                        }
                        "companion" -> {
                            while (i < prefix.length && prefix[i].isWhitespace()) i++
                            val objPair = readIdent(i)
                            if (objPair?.first == "object") {
                                pendingType = null
                                i = objPair.second
                            }
                        }
                        "fun", "val", "var", "init", "constructor", "get", "set", "when", "if", "for", "while", "try", "catch", "finally" -> {
                            if (angleDepth == 0) pendingType = null
                        }
                    }
                }
                else -> i++
            }
        }
        return stack.filterNotNull()
    }

    private fun stripKeepComments(text: String): String =
        text.lineSequence()
            .map { line -> line.substringBefore('#') }
            .joinToString("\n")

    /**
     * 从全仓 `src/main/**/*.kt` 提取带显式 `@SerialName("...")` 的 class / object 声明，
     * 返回 `二进制类名 (pkg.Outer$Inner) -> SerialName 字面量` 映射。
     * （`@SerialName` 为 BINARY retention，运行时 `Class.getAnnotation` 读不到，故从源码提取并与运行期 `descriptor.serialName` 双重互验。）
     */
    private fun collectExplicitSerialNamesFromRepo(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val packageRegex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
        val serialDeclRegex = Regex(
            """@SerialName\(\s*"([^"]+)"\s*\)\s*(?:@[A-Za-z0-9_.]+\s*(?:\([^)]*\)\s*)?)*(?:(?:public|internal|private|data|sealed)\s+)*(?:class|object)\s+([A-Za-z0-9_]+)""",
        )

        repoRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "src/main").isDirectory }
            ?.forEach { moduleDir ->
                File(moduleDir, "src/main").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { ktFile ->
                        val raw = ktFile.readText()
                        if (!raw.contains("@SerialName")) return@forEach
                        val pkg = packageRegex.find(raw)?.groupValues?.get(1) ?: return@forEach
                        // 仅剥离注释（保留 @SerialName("...") 内的字符串字面量）
                        val noComments = stripOnlyKotlinComments(raw)
                        for (match in serialDeclRegex.findAll(noComments)) {
                            val serialValue = match.groupValues[1]
                            val typeName = match.groupValues[2]
                            val enclosing = findEnclosingTypeNames(
                                stripKotlinCommentsAndStrings(noComments.substring(0, match.range.first)),
                            )
                            val binaryName = if (enclosing.isEmpty()) {
                                "$pkg.$typeName"
                            } else {
                                "$pkg.${(enclosing + typeName).joinToString("$")}"
                            }
                            result[binaryName] = serialValue
                        }
                    }
            }
        return result
    }

    private fun stripOnlyKotlinComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < source.length && !(source[i] == '*' && source.getOrNull(i + 1) == '/')) {
                        if (source[i] == '\n') out.append('\n')
                        i++
                    }
                    i += 2
                }
                c == '"' && next == '"' && source.getOrNull(i + 2) == '"' -> {
                    out.append("\"\"\"")
                    i += 3
                    while (i < source.length && !(source[i] == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"')) {
                        out.append(source[i])
                        i++
                    }
                    if (i < source.length) {
                        out.append("\"\"\"")
                        i += 3
                    }
                }
                c == '"' -> {
                    out.append('"')
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) {
                            out.append(source[i])
                            i++
                        }
                        out.append(source[i])
                        i++
                    }
                    if (i < source.length) {
                        out.append('"')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
