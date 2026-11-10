package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩摘要的机械痕迹附录（NMoi D296 判据，本仓落地）：
 * 工具名去重保序 / 沙箱路径提取 / 文档与媒体句柄（不展开 base64）/ 12 条与 600 字上限 /
 * 预算不够整节不接 / 重压缩不叠加。
 */
class CompressionTracesTest {

    private fun toolMessage(
        name: String,
        input: String = "{}",
        outputText: String? = null,
        outputParts: List<UIMessagePart> = emptyList(),
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call$name",
                toolName = name,
                input = input,
                output = if (outputText != null) listOf(UIMessagePart.Text(outputText)) else outputParts,
            )
        ),
    )

    private fun userMessage(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())

    // ===== 收集：工具名 =====

    @Test
    fun `collect keeps tool names in first-seen order without duplicates`() {
        val bundle = collectTraceBundle(
            listOf(
                toolMessage("read_file", """{"path":"/workspace/a.kt"}"""),
                toolMessage("workspace_shell", """{"command":"ls"}"""),
                toolMessage("read_file", """{"path":"/workspace/b.kt"}"""),
            )
        )
        assertEquals(listOf("read_file", "workspace_shell"), bundle.toolNames)
    }

    // ===== 收集：沙箱文件路径 =====

    @Test
    fun `collect extracts sandbox paths from tool input and output`() {
        val bundle = collectTraceBundle(
            listOf(
                toolMessage("read_file", """{"path":"/workspace/src/Main.kt"}"""),
                // 输出里的路径同样算：模型常从 ls/cat 的输出里拿到文件名，参数里未必有
                toolMessage("workspace_shell", """{"command":"cat build.log"}""", outputText = "wrote /tmp/build.log ok"),
            )
        )
        val handles = bundle.entries.map { it.handle }
        assertTrue("工具参数里的沙箱路径必须收到：$handles", handles.contains("/workspace/src/Main.kt"))
        assertTrue("工具输出里的沙箱路径必须收到：$handles", handles.contains("/tmp/build.log"))
        val byHandle = bundle.entries.associateBy { it.handle }
        assertEquals(listOf("read_file"), byHandle.getValue("/workspace/src/Main.kt").tools)
        assertEquals(listOf("workspace_shell"), byHandle.getValue("/tmp/build.log").tools)
    }

    @Test
    fun `collect ignores paths outside the sandbox and relative paths`() {
        val bundle = collectTraceBundle(
            listOf(
                toolMessage(
                    "workspace_shell",
                    """{"command":"cat /etc/passwd","path":"src/main.kt","url":"https://example.com/a"}""",
                ),
            )
        )
        assertTrue("非沙箱/相对路径一律不收：${bundle.entries}", bundle.entries.isEmpty())
        assertEquals(listOf("workspace_shell"), bundle.toolNames) // 工具名照收
    }

    // ===== 收集：文档 / 媒体句柄 =====

    @Test
    fun `collect carries document and media handles without expanding base64`() {
        val bundle = collectTraceBundle(
            listOf(
                userMessage(
                    UIMessagePart.Document(
                        url = "file:///data/user/0/app/files/report.pdf",
                        fileName = "report.pdf",
                    ),
                    UIMessagePart.Image(url = "data:image/png;base64,AAAABBBBCCCC"),
                    UIMessagePart.Audio(url = "file:///data/user/0/app/files/voice.webm"),
                )
            )
        )
        val document = bundle.entries.single { it.kind == TraceKind.DOCUMENT }
        assertEquals("file:///data/user/0/app/files/report.pdf", document.handle)
        assertEquals("report.pdf", document.label)

        val media = bundle.entries.filter { it.kind == TraceKind.MEDIA }
        assertEquals(2, media.size)
        val inline = media.single { it.handle.startsWith("data:image/png") }
        assertTrue("内联 base64 只留 mime 头：${inline.handle}", inline.handle.contains("内联"))
        assertFalse("base64 正文绝不展开：${inline.handle}", inline.handle.contains("AAAABBBB"))
        assertEquals("音频", media.single { it.handle.endsWith("voice.webm") }.label)
    }

    @Test
    fun `render prints documents and media with readable labels`() {
        val text = renderTraceSection(
            collectTraceBundle(
                listOf(
                    userMessage(
                        UIMessagePart.Document(url = "file:///d/report.pdf", fileName = "report.pdf"),
                        UIMessagePart.Image(url = "file:///d/photo.png"),
                    )
                )
            )
        )
        assertNotNull("有文档/媒体就不该返回 null", text)
        val rendered = text!!
        assertTrue(rendered, rendered.contains("文档 report.pdf (file:///d/report.pdf)"))
        assertTrue(rendered, rendered.contains("媒体 图片 (file:///d/photo.png)"))
    }

    // ===== 渲染：上限 =====

    @Test
    fun `render returns null for empty bundle and never writes a no-trace line`() {
        assertNull(renderTraceSection(collectTraceBundle(listOf(userMessage(UIMessagePart.Text("hi"))))))
        assertNull(renderTraceSection(TraceBundle()))
    }

    @Test
    fun `render caps the list at 12 entries and 600 chars`() {
        val entries = (0 until 30).map {
            TraceEntry(TraceKind.FILE, "/tmp/f$it.txt", tools = listOf("read_file"))
        }
        val text = renderTraceSection(TraceBundle(listOf("read_file"), entries))!!
        val listed = text.lines().count { it.startsWith("- ") }
        assertEquals("条数上限", TRACE_MAX_ENTRIES, listed)
        assertTrue("字符上限：${text.length}", text.length <= TRACE_MAX_CHARS)
        assertTrue("必须写明还有几条没列出：$text", text.contains("（其余 18 条未列出）"))
        assertTrue("工具行必须留", text.contains("工具: read_file"))
    }

    @Test
    fun `render caps tool names and notes the rest`() {
        val names = (0 until TRACE_MAX_TOOL_NAMES + 3).map { "tool_$it" }
        val text = renderTraceSection(TraceBundle(toolNames = names))!!
        assertTrue(text, text.contains("工具: tool_0"))
        assertTrue(text, text.contains("等 3 种"))
        assertFalse("超出的工具名不能再列", text.contains("tool_${TRACE_MAX_TOOL_NAMES + 2}"))
    }

    @Test
    fun `render stops before exceeding the char budget instead of cutting a handle`() {
        val longHandle = "/workspace/" + "a".repeat(TRACE_MAX_HANDLE_CHARS)
        val entries = List(12) { TraceEntry(TraceKind.FILE, longHandle + "$it", tools = listOf("read_file")) }
        val text = renderTraceSection(TraceBundle(listOf("read_file"), entries))!!
        assertTrue("字符上限：${text.length}", text.length <= TRACE_MAX_CHARS)
        // 每条句柄要么完整出现，要么整条不出现（不能出现截半个路径的行）
        text.lines().filter { it.startsWith("- ") }.forEach { line ->
            assertTrue("被切坏的句柄：$line", line.contains(longHandle))
        }
    }

    // ===== 追加：预算 / 重压缩 =====

    @Test
    fun `attach appends the section after the draft body`() {
        val summary = "# Primary Request\n- 改 Main.kt"
        val out = attachTraceSection(
            summary = summary,
            priorSummary = "",
            messages = listOf(toolMessage("write_file", """{"path":"/workspace/Main.kt"}""")),
            targetTokens = 2000,
        )
        assertTrue("正文必须保留且在前：$out", out.startsWith(summary))
        assertTrue("附录头行", out.contains(TRACE_SECTION_HEADER))
        assertTrue("路径", out.contains("/workspace/Main.kt"))
        assertTrue("工具名", out.contains("write_file"))
    }

    @Test
    fun `attach leaves the draft untouched when the trace budget is zero`() {
        val summary = "# Summary\n- body"
        assertEquals(
            summary,
            attachTraceSection(
                summary = summary,
                priorSummary = "",
                messages = listOf(toolMessage("write_file", """{"path":"/workspace/Main.kt"}""")),
                targetTokens = 0,
            ),
        )
    }

    @Test
    fun `attach leaves the draft untouched when there are no traces`() {
        val summary = "# Summary\n- body"
        assertEquals(summary, attachTraceSection(summary, "", listOf(userMessage(UIMessagePart.Text("hi"))), 2000))
    }

    @Test
    fun `recompression keeps a single trace section and carries old entries forward`() {
        val first = attachTraceSection(
            summary = "# Round 1",
            priorSummary = "",
            messages = listOf(toolMessage("write_file", """{"path":"/workspace/old.kt"}""")),
            targetTokens = 2000,
        )
        val second = attachTraceSection(
            summary = "# Round 2",
            priorSummary = first,
            messages = listOf(toolMessage("read_file", """{"path":"/workspace/new.kt"}""")),
            targetTokens = 2000,
        )
        assertTrue("正文换成新一轮摘要", second.startsWith("# Round 2"))
        assertEquals("痕迹只有一段", 1, second.split(TRACE_SECTION_HEADER).size - 1)
        assertTrue("本段条目", second.contains("/workspace/new.kt"))
        assertTrue("旧段条目接力（压缩前的路径压完还能回取）", second.contains("/workspace/old.kt"))
        // 本段在前、旧段在后：越近的痕迹越可能还要用
        assertTrue(second.indexOf("/workspace/new.kt") < second.indexOf("/workspace/old.kt"))
    }

    @Test
    fun `strip removes the appendix and keeps the body`() {
        val first = attachTraceSection(
            summary = "# Body text",
            priorSummary = "",
            messages = listOf(toolMessage("read_file", """{"path":"/tmp/a.txt"}""")),
            targetTokens = 2000,
        )
        assertEquals("# Body text", stripTraceSection(first))
        assertEquals("没有附录时原样返回", "plain", stripTraceSection("plain"))
    }

    // ===== 解析：读得回自己写的格式 =====

    @Test
    fun `parse round-trips what render produced`() {
        val bundle = TraceBundle(
            toolNames = listOf("read_file", "workspace_shell"),
            entries = listOf(
                TraceEntry(TraceKind.FILE, "/workspace/Main.kt", tools = listOf("read_file", "edit_file")),
                TraceEntry(TraceKind.FILE, "/tmp/build.log", tools = listOf("workspace_shell")),
                TraceEntry(TraceKind.DOCUMENT, "file:///d/report.pdf", "report.pdf"),
                TraceEntry(TraceKind.MEDIA, "file:///d/voice.webm", "音频"),
            ),
        )
        val rendered = renderTraceSection(bundle)!!
        val parsed = parseTraceBundle(rendered)
        assertEquals(bundle.toolNames, parsed.toolNames)
        assertEquals(bundle.entries, parsed.entries)
    }

    @Test
    fun `parse ignores unrecognized lines instead of guessing`() {
        val parsed = parseTraceBundle(
            """
            $TRACE_SECTION_HEADER
            工具: read_file
            - Something else
            - 文件 /workspace/a.kt (read_file)
            （其余 3 条未列出）
            """.trimIndent()
        )
        assertEquals(listOf("read_file"), parsed.toolNames)
        assertEquals(1, parsed.entries.size)
        assertEquals("/workspace/a.kt", parsed.entries.single().handle)
    }

    @Test
    fun `handle sanitize drops sentence punctuation and truncates overlong handles`() {
        assertEquals("/tmp/a.txt", sanitizeHandle("/tmp/a.txt."))
        assertEquals("/tmp/a.txt", sanitizeHandle("/tmp/a.txt，"))
        val long = sanitizeHandle("/workspace/" + "x".repeat(TRACE_MAX_HANDLE_CHARS * 2))
        assertEquals(TRACE_MAX_HANDLE_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", sanitizeHandle("  "))
    }
}
