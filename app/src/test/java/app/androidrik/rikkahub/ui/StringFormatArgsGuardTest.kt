package app.androidrik.rikkahub.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 资源串格式化实参守卫（源码级扫描）。
 *
 * **起因（2026-10-05 真机崩溃）**：`prompt_debug_diverged_at`（`分叉于第 %1$d 条：%2$s`）的调用点
 * 只传了一个实参（缺 `%1$d`）⇒ `Resources.getString` 把 String 交给 `%d` ⇒
 * `IllegalFormatConversionException: d != java.lang.String`，整页崩溃。编译期与现有单测都拦不住
 * ——`stringResource(...)` 的实参个数对不对，只有 Android Lint 的 `StringFormatMatches` 会查，
 * 而我们的发布流程不跑 lint。
 *
 * 本测试把该检查固化成**纯 JVM 源码扫描**（不需要 Robolectric / 资源编译）：
 * 1. **实参个数**：调用点传了 ≥1 个实参时，实参个数必须 ≥ 各 locale 里出现的最大占位符编号
 *    （少传必崩；多传不崩，多余的会被忽略）。**0 实参调用不检查**——本仓既有「无参取串、
 *    稍后 `.format(...)`」的模板用法（如 `SettingMcpPage` 的 `parseErrorMsg.format(...)`），
 *    无参调用不做格式化，永远不会崩。
 * 2. **类型方向**：`%N$d` 位置上不得传「一眼是字符串」的表达式（字符串字面量 / `…Preview` /
 *    `.name` / `toString` / `joinToString` / `format`）。`%s` 收 Int 是安全的（Formatter 会
 *    toString），故只查 `d` 方向。
 */
class StringFormatArgsGuardTest {

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到资源目录，工作目录=${File(".").absolutePath}")
    }

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    /** key → (最大占位符编号, 各位要求类型, 该要求来自哪个 locale) */
    private data class Requirement(val need: Int, val types: Map<Int, Char>, val locale: String)

    private fun requirements(): Map<String, Requirement> {
        val out = mutableMapOf<String, Requirement>()
        val stringRegex = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        resRoot().listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .sortedBy { it.name }
            .forEach { dir ->
                val xml = dir.resolve("strings.xml").takeIf { it.isFile }?.readText() ?: return@forEach
                stringRegex.findAll(xml).forEach { m ->
                    val key = m.groupValues[1]
                    val value = m.groupValues[2]
                    val numbered = Regex("""%(\d+)\$([a-zA-Z])""").findAll(value).toList()
                    val unnumbered = Regex("""%(?![%\d])([a-zA-Z])""").findAll(value).count()
                    val need = maxOf(
                        numbered.maxOfOrNull { it.groupValues[1].toInt() } ?: 0,
                        unnumbered,
                    )
                    if (need == 0) return@forEach
                    val types = numbered.associate { it.groupValues[1].toInt() to it.groupValues[2][0] }
                    val cur = out[key]
                    if (cur == null) {
                        out[key] = Requirement(need, types, dir.name)
                    } else {
                        val merged = cur.types.toMutableMap()
                        types.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                        out[key] = Requirement(maxOf(cur.need, need), merged,
                            if (need > cur.need) dir.name else cur.locale)
                    }
                }
            }
        return out
    }

    /** 抓 `stringResource(` / `getString(` 的第一个位置参数是 `R.string.x` 的调用。 */
    private val callRegex = Regex("""(?:stringResource|getString)\(\s*R\.string\.([A-Za-z0-9_]+)""")

    /** 从 `(` 之后开始按括号平衡取整调用体（跳过字符串字面量）。 */
    private fun callBody(source: String, from: Int): String {
        var i = from
        var depth = 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(from, i)
                }
                '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\') i++
                        i++
                    }
                }
            }
            i++
        }
        return source.substring(from)
    }

    private fun argsOf(body: String): List<String> =
        body.split(',')
            .let { parts -> splitTopLevel(body) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 按顶层逗号切分（不切括号内、字符串内的逗号）。 */
    private fun splitTopLevel(body: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '"' -> {
                    sb.append(c)
                    i++
                    while (i < body.length && body[i] != '"') {
                        sb.append(body[i])
                        if (body[i] == '\\') {
                            i++
                            if (i < body.length) sb.append(body[i])
                        }
                        i++
                    }
                    if (i < body.length) sb.append(body[i])
                }
                c == '(' || c == '{' || c == '[' -> {
                    depth++
                    sb.append(c)
                }
                c == ')' || c == '}' || c == ']' -> {
                    depth--
                    sb.append(c)
                }
                c == ',' && depth == 0 -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    private fun looksLikeString(expr: String): Boolean =
        expr.startsWith("\"") ||
            expr.contains("Preview") ||
            expr.contains(".name") ||
            expr.contains("toString") ||
            expr.contains("joinToString") ||
            expr.contains(".format(")

    @Test
    fun `format args match the placeholders declared by every locale`() {
        val requirements = requirements()
        assertTrue("未解析到任何占位符，正则或资源路径失效", requirements.isNotEmpty())

        val failures = mutableListOf<String>()
        srcRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                callRegex.findAll(source).forEach { m ->
                    val key = m.groupValues[1]
                    val req = requirements[key] ?: return@forEach
                    val args = argsOf(callBody(source, m.range.last + 1))
                    val where = "${file.name}:${source.substring(0, m.range.first).count { it == '\n' } + 1}"
                    when {
                        // 0 实参 = 模板用法（取串后自行 .format），不做格式化，永不崩
                        args.isEmpty() -> Unit
                        args.size < req.need -> failures +=
                            "$where R.string.$key 传了 ${args.size} 个实参，但占位符需要 ${req.need} 个" +
                                "（最高编号来自 ${req.locale}）⇒ 运行期必崩"
                        else -> req.types.forEach { (pos, type) ->
                            if (type == 'd' && pos <= args.size && looksLikeString(args[pos - 1])) {
                                failures +=
                                    "$where R.string.$key 第 $pos 位要 %d，却传了像字符串的表达式 " +
                                        "`${args[pos - 1].take(60)}`"
                            }
                        }
                    }
                }
            }

        assertTrue(
            "资源串格式化实参不匹配（真机会抛 IllegalFormatConversionException）：\n" +
                failures.joinToString("\n") { " - $it" },
            failures.isEmpty(),
        )
    }
}
