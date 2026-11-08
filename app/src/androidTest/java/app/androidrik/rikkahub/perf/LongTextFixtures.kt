package app.androidrik.rikkahub.perf

import java.security.MessageDigest

/** Versioned, offline fixtures. Length is Unicode code points (all fixture characters are BMP). */
internal object LongTextFixtures {
    const val VERSION = 1
    val kinds = setOf("prose", "dense", "mixed", "code", "list", "table")
    val sizes = setOf(10_000, 50_000, 100_000)
    private const val SENTENCE = "清晨的风从河面吹来，街道逐渐苏醒。旅人记录沿途的见闻，思考故事中的人物、动机与转折。"

    fun text(kind: String, length: Int): String {
        require(kind in kinds && length in sizes)
        if (kind == "dense") return SENTENCE.repeat(length / SENTENCE.length + 1).take(length)
        val unit = when (kind) {
            "prose" -> "$SENTENCE$SENTENCE\n\n"
            "mixed" -> "## 旅途记录\n\n$SENTENCE **继续前行**，参考[说明](https://example.invalid/note)。\n\n" +
                "> 记住每个细节。\n\n- 观察环境\n- 记录变化\n\n" +
                "```kotlin\nval route = listOf(1, 2, 3)\nprintln(route.sum())\n```\n\n" +
                "公式：\$x^2+y^2=1\$。\n\n| 地点 | 时间 |\n|---|---|\n| 河边 | 清晨 |\n\n"
            "code" -> "val step = 42 // deterministic baseline\n"
            "list" -> "- $SENTENCE\n"
            "table" -> "| 河边的观察 | 清晨 | $SENTENCE |\n"
            else -> error(kind)
        }
        val prefix = when (kind) {
            "code" -> "```kotlin\n"
            "table" -> "| 地点 | 时间 | 描述 |\n|---|---|---|\n"
            else -> ""
        }
        val suffix = if (kind == "code") "\n```\n" else "\n\n"
        val available = length - prefix.length - suffix.length
        // Repeat whole Markdown units; padding never cuts through a fence, link or formula.
        val body = unit.repeat(available / unit.length)
        val padding = "文".repeat(available - body.length)
        return prefix + body + padding + suffix
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
