package app.androidrik.rikkahub.ui.components.richtext

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import me.rerere.bettertext.markdown.Markdown
import me.rerere.bettertext.markdown.MarkdownComponents
import me.rerere.bettertext.markdown.MarkdownStreamParser
import me.rerere.bettertext.markdown.MarkdownTheme
import me.rerere.bettertext.markdown.parseMarkdown
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MarkdownProjectionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    /** Calls only the old public parser API: the same driver can run against the archived AAR app. */
    @Test fun exportParserSnapshot() {
        check(context.applicationContext.javaClass == Application::class.java)
        val run = InstrumentationRegistry.getArguments().getString("reportRun", "manual")
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        val samples = listOf("~abc~", "~~strike~~ **bold** *italic*", "# 标题\n\n正文😀\n\n第二段",
            "- one\n- [x] two\n\n> quote", "```kotlin\nval x = 1\n```", "```kotlin\nval x = 1",
            "[early][ref]\n\na\n\nb\n\nc\n\n[ref]: https://example.com \"title\"",
            "<details open>\n<summary>More</summary>\n\n**body**\n\n</details>",
            "| A | B |\n|:--|--:|\n| 1 | 2 |", "inline \\(x\\)\r\n\r\n\\[y\n+z\\]")
        val rows = JSONArray()
        for (sample in samples) for (streaming in listOf(false, true)) for (softBreak in listOf(false, true)) {
            val parsed = parseMarkdown(sample, softBreakAsLineBreak = softBreak, streaming = streaming)
            assertEquals(sample, parsed.source)
            rows.put(JSONObject().put("source", sample).put("streaming", streaming).put("soft_break", softBreak)
                .put("nodes", parsed.blocks.toString()))
        }
        File(context.filesDir, "longtext-p2/$run.json").apply {
            parentFile!!.mkdirs()
            check(!exists())
            writeText(rows.toString())
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "P2 parser snapshot: ${rows.length()} cases\n") })
    }

    @Test fun independentlyRenderedUnitsMatchWholeDocumentPixels() {
        val samples = listOf(
            "# First\n\nA **bold** paragraph.\n\n## Second\n\n- one\n- two\n\n> quote\n\n---\n\nlast",
            "[id]: https://example.com\n\n# First\n\n[link][id]\n\n```kotlin\nval x = 1\n```\n\n![alt](https://image)\n\n\\[x\\]",
            "before\n\n<div>\n\nfirst\n\n### Nested\n\n</div>\n\n# After",
        )
        val source = mutableStateOf(samples.first())
        val units = mutableStateOf(false)
        val theme = MarkdownTheme(blockSpacing = 9.dp)
        val components = MarkdownComponents(
            codeBlock = { Text("code: ${it.code}") }, image = { image, _ -> Text("image: ${image.alt}") },
            mathBlock = { Text("math: $it") },
        )
        compose.setContent {
            val document = remember(source.value) { MarkdownStreamParser().parseRenderDocument(source.value) }
            Box(Modifier.width(320.dp).background(Color.White).testTag("render")) {
                val style = TextStyle(fontSize = 14.sp, color = Color.Black)
                if (units.value) Column(verticalArrangement = Arrangement.spacedBy(theme.blockSpacing)) {
                    document.units.filter { it.blocks.isNotEmpty() }.forEach {
                        Markdown(unit = it, modifier = Modifier.fillMaxWidth(), style = style, theme = theme, components = components)
                    }
                } else Markdown(document = parseMarkdown(source.value), modifier = Modifier.fillMaxWidth(),
                    style = style, theme = theme, components = components)
            }
        }
        for (sample in samples) {
            compose.runOnIdle { source.value = sample; units.value = false }
            val whole = compose.onNodeWithTag("render").captureToImage().toPixelMap()
            compose.runOnIdle { units.value = true }
            val split = compose.onNodeWithTag("render").captureToImage().toPixelMap()
            assertEquals(whole.width, split.width)
            assertEquals(whole.height, split.height)
            for (y in 0 until whole.height) for (x in 0 until whole.width) {
                assertEquals("pixel $x,$y", whole[x, y], split[x, y])
            }
        }
    }
}
