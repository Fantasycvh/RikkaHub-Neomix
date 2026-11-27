package app.androidrik.rikkahub.r8

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全仓源码级守卫：**拦截用户可见名称 / 协议字段误用 `simpleName`**。
 *
 * 背景：Debug 不开 R8，`KClass.simpleName` / `Class.simpleName` 恰好等于源码短名，行为测试 100% 绿；
 * 一旦打 Release 包就会变成 `it9`/`ft9`/`crc`/`y89` 等短名。此前 `ProviderTypeLabelSourceGuardTest`
 * 仅盯住 3 个文件，导致 `MediaModelCatalog` 与 `WebSettingsDto` 再次漏进 `simpleName`。
 *
 * 本守卫扫描**全仓所有模块的 `src/main/**/*.kt`**：
 * 1. 仅放行已核验的诊断日志/异常类名兜底与受 `-keepnames`/`-keep` 锁死的白名单文件；
 * 2. **双向契约**：放行 `Screen::class.simpleName` 与 `Throwable.simpleName` 的前提是
 *    `diagnostics-and-navigation.keep` 中对应的 `-keepnames` 规则真实存在——删规则同样红测试。
 */
class R8SimpleNameSourceGuardTest {

    private val repoRoot: File by lazy {
        val cwd = File(".").canonicalFile
        if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    /**
     * 经人工逐行审计并由对应 `.keep` 规则兜底的允许出现 `simpleName` 的生产源码路径（相对仓库根，正斜杠）。
     *
     * **严禁将设置页、模型选择器、DTO、持久化实体、Skill/插件元数据解析加入此表！**
     * 新增用户可见类型名称请一律走显式 `displayName` / `@SerialName`。
     */
    private val allowedDiagnosticFiles: Set<String> = setOf(
        // 路由本地统计：由 diagnostics-and-navigation.keep 的 `-keepnames class ...Screen$*` 保活原名
        "app/src/main/java/app/androidrik/rikkahub/RouteActivity.kt",
        // LazyColumn item contentType 区分（LogEntry 由 serialization.keep 原名保活）
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/log/LogPage.kt",
        // 内部性能/调试日志（Transformer 耗时回调、ChatToolFactory debug 日志、慢流 chunk 警告）
        "app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/Transformer.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
        // MediaGenerationProviderSetting / MediaGenerationInput 均由 serialization.keep 原名保活，用于不支持输入的异常诊断文本
        "mediagen/src/main/java/app/androidrik/mediagen/provider/MediaGenerationManager.kt",
        "mediagen/src/main/java/app/androidrik/mediagen/provider/providers/google/GoogleMediaGenerationProvider.kt",
        "mediagen/src/main/java/app/androidrik/mediagen/provider/providers/openai/OpenAIMediaGenerationProvider.kt",
        "mediagen/src/main/java/app/androidrik/mediagen/provider/providers/openrouter/OpenRouterMediaGenerationProvider.kt",
        "mediagen/src/main/java/app/androidrik/mediagen/provider/providers/siliconflow/SiliconFlowMediaGenerationProvider.kt",
        // Throwable 异常类名诊断兜底（由 diagnostics-and-navigation.keep 的 `-keepnames class * extends Throwable` 保活）
        "app/src/main/java/app/androidrik/rikkahub/data/ai/account/ProviderAccountManager.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/ai/compression/MultiPassCompression.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/ai/mcp/McpStatus.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/ai/tools/WorkspaceTools.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/files/FilesManager.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/plugin/js/JsPluginRuntime.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/plugin/net/PluginHttpGateway.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/sync/BackupRunCoordinator.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/MediaCreationService.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/SubagentRunner.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/activity/RecoveryExport.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/activity/RecoveryImport.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/activity/SafeModeActivity.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/components/ai/SketchAttachment.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/components/sketch/SketchDialog.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/components/sketch/SketchImage.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceSshEditorVM.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/mediacreation/MediaCreationVM.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/StorageVM.kt",
        "app/src/main/java/app/androidrik/rikkahub/ui/pages/translator/ProcessTextTranslatorWindow.kt",
        "app/src/main/java/app/androidrik/rikkahub/web/WebServerManager.kt",
        "workspace/src/main/java/app/androidrik/workspace/HttpProbe.kt",
        "workspace/src/main/java/app/androidrik/workspace/WorkspaceBackgroundTasks.kt",
    )

    @Test
    fun `keepnames rules backing allowed simpleName usages remain intact`() {
        val diagKeepFile = File(repoRoot, "app/src/main/keepRules/diagnostics-and-navigation.keep")
        assertTrue("缺少 ${diagKeepFile.absolutePath}", diagKeepFile.isFile)
        val rules = diagKeepFile.readText().lineSequence().map { it.substringBefore('#') }.joinToString("\n")

        assertTrue(
            "diagnostics-and-navigation.keep 必须保留 `-keepnames class * extends java.lang.Throwable`，否则异常兜底 simpleName 会变成混淆名",
            Regex("""-keepnames\s+class\s+\*\s+extends\s+java\.lang\.Throwable\b""").containsMatchIn(rules),
        )
        assertTrue(
            "diagnostics-and-navigation.keep 必须保留 `-keepnames class app.androidrik.rikkahub.Screen$*`，否则路由统计会被混淆为 crc/esc",
            Regex("""-keepnames\s+class\s+app\.androidrik\.rikkahub\.Screen\$\*""").containsMatchIn(rules),
        )
    }

    @Test
    fun `no unapproved production file uses simpleName`() {
        val violations = mutableListOf<String>()
        val simpleNameRegex = Regex("""\bsimpleName\b""")

        repoRoot.listFiles()
            ?.filter { it.isDirectory && File(it, "src/main").isDirectory }
            ?.sortedBy { it.name }
            ?.forEach { moduleDir ->
                File(moduleDir, "src/main").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        val relPath = repoRoot.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                        val raw = file.readText()
                        if (!raw.contains("simpleName")) return@forEach
                        val stripped = stripKotlinCommentsAndStrings(raw)
                        if (simpleNameRegex.containsMatchIn(stripped) && relPath !in allowedDiagnosticFiles) {
                            val hitLines = stripped.lines().mapIndexedNotNull { idx, line ->
                                if (simpleNameRegex.containsMatchIn(line)) "${idx + 1}: ${raw.lines().getOrNull(idx)?.trim()}" else null
                            }
                            violations += "$relPath -> ${hitLines.joinToString("; ")}"
                        }
                    }
            }

        assertTrue(
            "发现未经 R8 审计的 simpleName 调用（Release 混淆后会显示成 it9/y89 等乱码，请改用显式 displayName 或 @SerialName）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `kotlin comment and raw string stripper preserves code and strips triple quoted strings`() {
        val sample = """
            // simpleName in line comment
            /* simpleName in block comment */
            val normal = "simpleName in normal string"
            val raw = ${"\"\"\""}
                simpleName in raw multiline string
            ${"\"\"\""}
            val actual = target::class.simpleName
        """.trimIndent()
        val stripped = stripKotlinCommentsAndStrings(sample)
        assertTrue(stripped.contains("target::class.simpleName"))
        assertTrue(!stripped.contains("in line comment"))
        assertTrue(!stripped.contains("in block comment"))
        assertTrue(!stripped.contains("in normal string"))
        assertTrue(!stripped.contains("in raw multiline string"))
    }
}

/**
 * 剥离 Kotlin 行注释、块注释、多行原始字符串 `"""..."""`、普通字符串与字符字面量，保留行号对齐。
 */
internal fun stripKotlinCommentsAndStrings(source: String): String {
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
                i += 3
                while (i < source.length && !(source[i] == '"' && source.getOrNull(i + 1) == '"' && source.getOrNull(i + 2) == '"')) {
                    if (source[i] == '\n') out.append('\n')
                    i++
                }
                i += 3
            }
            c == '"' -> {
                i++
                while (i < source.length && source[i] != '"') {
                    if (source[i] == '\n') out.append('\n')
                    if (source[i] == '\\') i++
                    i++
                }
                i++
            }
            c == '\'' -> {
                i++
                while (i < source.length && source[i] != '\'') {
                    if (source[i] == '\\') i++
                    i++
                }
                i++
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
