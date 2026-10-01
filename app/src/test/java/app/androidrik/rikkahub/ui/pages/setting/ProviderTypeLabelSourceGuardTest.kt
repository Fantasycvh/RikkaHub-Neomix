package app.androidrik.rikkahub.ui.pages.setting

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级守卫：**用户可见的提供商类型名不得取自 `KClass.simpleName`**。
 *
 * 为什么必须是源码级而不是行为断言：release 构建经 R8 混淆后
 * `ProviderSetting$OpenAI/Google/Claude` 会变成 `it9`/`ft9`/`bt9`
 * （见 `app/build/outputs/mapping/release/mapping.txt`），正式版的自定义提供商类型选择器
 * 于是显示 "it9"/"ft9"；而 **debug 构建不混淆，`simpleName` 恰好等于正确名称**——
 * 这类 bug 在任何行为断言里都「看起来正常」，**只在正式版暴露**。所以只能盯源码。
 *
 * 允许的例外：日志/诊断文本（如 `SubagentRunner` 的运行失败详情、`McpStatus` 的兜底摘要）
 * ——它们展示技术类名，混淆后仍可与 mapping.txt 对照，不属于「名称」契约。
 *
 * 注：Android 单元测试的工作目录是模块根（与 `BuiltinSkillsTest` 读 assets 同款前提）。
 */
class ProviderTypeLabelSourceGuardTest {

    private fun sourceFile(relativePath: String): File =
        File("src/main/java/app/androidrik/rikkahub/$relativePath")

    private fun assertNoSimpleNameLabel(relativePath: String) {
        val file = sourceFile(relativePath)
        assertTrue("找不到 ${file.absolutePath}（工作目录应为 app 模块根）", file.exists())
        val code = stripCommentsAndStrings(file.readText())
        assertTrue(
            "$relativePath 仍在使用 KClass.simpleName 取用户可见名称——" +
                "release 经 R8 混淆后会显示成 it9/ft9/bt9 这类短名（只在正式版暴露）。" +
                "请改用 ProviderSetting.Types 的 displayName / providerTypeDisplayName。",
            !Regex("""\bsimpleName\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `provider type selector uses explicit display names`() {
        assertNoSimpleNameLabel("ui/pages/setting/components/ProviderConfigure.kt")
    }

    @Test
    fun `model provider override tag uses explicit display names`() {
        assertNoSimpleNameLabel("ui/pages/setting/SettingProviderDetailPage.kt")
    }

    @Test
    fun `tts provider type selector falls back to a constant, not a class name`() {
        assertNoSimpleNameLabel("ui/pages/setting/components/TTSProviderConfigure.kt")
    }

    @Test
    fun `comment stripper keeps code and drops both comment kinds`() {
        val source = """
            // 行注释里的 simpleName 应被剥掉
            /* 块注释里的 simpleName 也应被剥掉 */
            val a = "字符串里的 simpleName 同样不算"
            val b = obj.simpleName
        """.trimIndent()

        val code = stripCommentsAndStrings(source)

        assertTrue("代码本体必须保留", code.contains("val b = obj.simpleName"))
        assertTrue("行注释必须剥掉", !code.contains("行注释里的"))
        assertTrue("块注释必须剥掉", !code.contains("块注释里的"))
        assertTrue("字符串必须剥掉", !code.contains("字符串里的"))
    }

    /**
     * 去掉注释与字符串字面量后再检查。
     *
     * **必需**：本项修复的价值恰恰在于「注释里解释清楚这个 R8 陷阱」，而那些注释必然提到
     * `simpleName`——不剥掉就会自己绊自己（首版正是这么红的）。字符串也一并剥掉，
     * 免得 URL 里的 `//` 之类影响判断。
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                // 行注释
                c == '/' && next == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                // 块注释 / KDoc
                c == '/' && next == '*' -> {
                    i += 2
                    while (i < source.length && !(source[i] == '*' && source.getOrNull(i + 1) == '/')) i++
                    i += 2
                }
                // 字符串字面量（含转义）
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                    i++
                }
                // 字符字面量
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
}
