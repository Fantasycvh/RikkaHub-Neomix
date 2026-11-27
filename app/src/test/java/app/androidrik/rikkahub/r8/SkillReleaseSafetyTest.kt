package app.androidrik.rikkahub.r8

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.SkillFrontmatterParser
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skill 解析链与媒体目录展示名的 Release R8 防回归测试。
 *
 * 覆盖此前真实发生或潜伏的 Release 专属问题：
 * 1. SnakeYAML 2.6 在 R8 拍平默认包后 `Class.getPackage().getName()` 抛 NPE（`aeda077f69`）；
 * 2. `SkillFrontmatterParser.parse` 曾静默吞掉 `Throwable`，导致解析崩溃时零日志、技能全空；
 * 3. 所有随包分发的 `assets/builtin_skills/` 必须能解析出合法 `name` / `description` 与非空正文；
 * 4. `MediaModelCatalog` 在供应商名称留空时必须回退显式厂商展示名，严禁暴露混淆类名。
 */
class SkillReleaseSafetyTest {

    private val repoRoot: File by lazy {
        val cwd = File(".").canonicalFile
        if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile
    }

    @Test
    fun `third-party keep rules protect entire snakeyaml package tree and java beans references`() {
        val thirdPartyKeep = File(repoRoot, "app/src/main/keepRules/third-party.keep")
        assertTrue("缺少 ${thirdPartyKeep.absolutePath}", thirdPartyKeep.isFile)
        val rules = thirdPartyKeep.readText().lineSequence().map { it.substringBefore('#') }.joinToString("\n")

        assertTrue(
            "third-party.keep 必须保留 `org.yaml.snakeyaml` 根包及 `org.yaml.snakeyaml.**` 全子包包名，防止 Logger 初始化 NPE",
            Regex("""-keeppackagenames\s+[^\n]*\borg\.yaml\.snakeyaml\b""").containsMatchIn(rules) &&
                Regex("""-keeppackagenames\s+[^\n]*\borg\.yaml\.snakeyaml\.\*\*""").containsMatchIn(rules),
        )
        assertTrue(
            "third-party.keep 必须包含 `-dontwarn java.beans.**`，防止 SnakeYAML/Jackson JavaBean 内省触发 R8 失败",
            Regex("""-dontwarn\s+java\.beans\.\*\*""").containsMatchIn(rules),
        )
    }

    @Test
    fun `all packaged builtin skills parse cleanly without errors`() {
        val builtinRoot = File(repoRoot, "app/src/main/assets/builtin_skills")
        assertTrue("内置技能目录不存在: ${builtinRoot.absolutePath}", builtinRoot.isDirectory)

        val skillDirs = builtinRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        assertTrue("至少应包含 1 个内置技能（如 skill-creator）", skillDirs.isNotEmpty())

        for (dir in skillDirs) {
            val skillMd = File(dir, "SKILL.md")
            assertTrue("${dir.name} 缺少 SKILL.md", skillMd.isFile)
            val content = skillMd.readText()

            var capturedError: Throwable? = null
            val fm = SkillFrontmatterParser.parse(content) { capturedError = it }
            assertNull("${dir.name}/SKILL.md 解析不应产生异常: $capturedError", capturedError)
            assertTrue("${dir.name}/SKILL.md 缺少非空 name", !fm["name"].isNullOrBlank())
            assertTrue("${dir.name}/SKILL.md 缺少非空 description", !fm["description"].isNullOrBlank())
            assertTrue("${dir.name}/SKILL.md 提取的正文不应为空", SkillFrontmatterParser.extractBody(content).isNotBlank())
        }
    }

    @Test
    fun `malformed yaml frontmatter surfaces error via callback instead of silent disappearance`() {
        val broken = """
            ---
            name: broken-skill
            description: [unclosed bracket
            ---
            Skill body
        """.trimIndent()

        var reported: Throwable? = null
        val result = SkillFrontmatterParser.parse(broken) { reported = it }

        assertNotNull("畸形 YAML 必须触发错误上报回调/日志而非无声吞没", reported)
        assertNull("解析失败时安全降级为空 frontmatter", result["name"])

        // 普通无 frontmatter 文本或空围栏属于合法输入，不得误报错误
        var falseAlarm: Throwable? = null
        SkillFrontmatterParser.parse("Plain markdown without frontmatter") { falseAlarm = it }
        SkillFrontmatterParser.parse("---\n---\nBody") { falseAlarm = it }
        assertNull("无 frontmatter 或空围栏不应误报解析异常", falseAlarm)
    }

    @Test
    fun `media model catalog falls back to explicit provider display names when name is blank`() {
        val blankMediaOpenAI = MediaGenerationProviderSetting.OpenAI(
            name = "   ",
            models = listOf(MediaGenerationModel(modelId = "gpt-image-1", kind = MediaKind.IMAGE)),
        )
        val blankLegacyGoogle = ProviderSetting.Google(
            name = "",
            models = listOf(Model(modelId = "imagen-3", displayName = "Imagen 3", type = ModelType.IMAGE)),
        )
        val settings = Settings(
            mediaGenerationProviders = listOf(blankMediaOpenAI),
            providers = listOf(blankLegacyGoogle),
        )

        val catalog = settings.mediaModelCatalog()
        assertEquals(2, catalog.size)
        assertEquals("OpenAI", catalog.first { it.model.modelId == "gpt-image-1" }.providerName)
        assertEquals("Google", catalog.first { it.model.modelId == "imagen-3" }.providerName)
    }
}
