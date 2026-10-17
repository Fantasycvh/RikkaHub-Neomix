package app.androidrik.rikkahub.data.ai.tools

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **截断必须带可机读声明**（源码守卫）。
 *
 * ## 为什么要扫源码
 *
 * 「截断要说出来」以前靠各工具自己写散文，散文漏写就是**静默截断**：模型以为拿到的
 * 就是全部，于是把半截内容当事实推理——单测照不出来（没有断言可挂），只有扫源码拦得住。
 * 契约本体见 [ToolOutputMeta]：**凡手写「我截断了」的地方必须调 `markTruncated`**。
 *
 * 规则一（通用）：`tools` 目录下的顶层 `Kt` 文件里出现 `truncated = true` 的，必须同时出现 `markTruncated`。
 * 规则二（点名）：已知会截断的文件必须留在名单里并带 `markTruncated`——防止有人把调用挪走后
 * 规则一因为「连 `truncated = true` 也删了」而静默失效。
 */
class ToolTruncationDeclarationGuardTest {

    private val toolsDir = listOf(
        File("src/main/java/app/androidrik/rikkahub/data/ai/tools"),
        File("app/src/main/java/app/androidrik/rikkahub/data/ai/tools"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到工具源码目录，工作目录=${File(".").absolutePath}")

    private val ktFiles: List<File> = toolsDir
        .listFiles { file -> file.isFile && file.name.endsWith(".kt") }
        .orEmpty()
        .sortedBy { it.name }

    /** 去掉行注释与块注释：注释里提到 `truncated = true` 不该让人误判。 */
    private fun code(file: File): String = stripComments(file.readText())

    @Test
    fun `every file that flags truncation also declares _meta`() {
        assertTrue("工具目录里没扫到 .kt 文件：${toolsDir.absolutePath}", ktFiles.isNotEmpty())

        val offenders = ktFiles
            .filter { code(it).contains("truncated = true") }
            .filterNot { code(it).contains("markTruncated") }
            .map { it.name }

        assertTrue(
            "这些文件手写了截断却没调 markTruncated，模型只会看到散文、拿不到可机读的 _meta：" +
                "${offenders.joinToString(", ")}（目录 ${toolsDir.absolutePath}）",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `known truncation sites are still covered`() {
        // 点名两份已在生产里截断的文件。它们若从规则一的视野里消失（例如把
        // `truncated = true` 改成别的写法却没接 _meta），这条会红。
        val known = listOf("ConversationTools.kt", "MemoryTools.kt")
        known.forEach { name ->
            val file = toolsDir.resolve(name)
            assertTrue("源码不存在：${file.absolutePath}", file.isFile)
            val source = code(file)
            assertTrue(
                "$name 曾会截断输出，现在既没 markTruncated 也没了截断标记，请确认不是漏接",
                source.contains("markTruncated"),
            )
        }
    }

    @Test
    fun `hints point at tools that actually exist in this repo`() {
        val local = toolsDir.resolve("local")
        val nested = if (local.isDirectory) {
            local.listFiles { file -> file.isFile && file.name.endsWith(".kt") }.orEmpty().toList()
        } else {
            emptyList()
        }
        val all = (ktFiles + nested).joinToString("\n") { it.readText() }
        val declaredTools = Regex("name\\s*=\\s*\"([a-z_]+)\"").findAll(all)
            .map { it.groupValues[1] }
            .toSet()

        // 会话侧 hint 里点名的两个工具必须真的在 tools 包里被注册
        assertTrue("conversation_search 不在工具注册里", "conversation_search" in declaredTools)
        assertTrue("read_conversation 不在工具注册里", "read_conversation" in declaredTools)
        assertTrue("memory_tool 不在工具注册里", "memory_tool" in declaredTools)
        assertTrue(
            "memory_tool 的 read/search action 应在描述里出现（hint 才可执行）",
            all.contains("`read`") && all.contains("`search`"),
        )
    }

    @Test
    fun `the guard actually sees a truncation call when it is there`() {
        // 守卫自身的最小反证：剥注释不能把代码里的调用一起吃掉，也不能被注释骗过
        assertTrue(
            "代码里的 truncated = true 必须被看见",
            stripComments("x = 1\nif (t) truncated = true\n").contains("truncated = true"),
        )
        assertTrue(
            "注释里的不该被看见",
            !stripComments("// truncated = true\n").contains("truncated = true"),
        )
    }

    /** 去掉行注释与块注释（Kotlin）；字符串里的 `//` 用「前面引号个数为偶」判掉。 */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, "")
            .lines()
            .joinToString("\n") { line ->
                val cut = (0..line.length)
                    .filter { line.startsWith("//", it) }
                    .firstOrNull { at -> line.take(at).count { ch -> ch == '"' } % 2 == 0 }
                if (cut == null) line else line.take(cut)
            }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
    }
}
