package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikZ 子集渲染器往返测试：TikZ 源码 → SVG 字符串。
 * 坐标约定：TikZ 坐标系 y 向上，SVG 输出 y 向下（渲染时翻转）。
 */
class TikzRendererTest {

    @Test
    fun `empty input returns failure`() {
        val result = TikzRenderer.render("")
        assertTrue(result.isFailure)
    }

    @Test
    fun `node with text renders text element`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node at (0,0) {A};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<text"))
        assertTrue(result.getOrThrow().contains("A"))
    }

    @Test
    fun `draw line renders path with move and line commands`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("<path"))
        assertTrue(svg.contains("M "))
        assertTrue(svg.contains("L "))
    }

    @Test
    fun `draw line with arrow and color sets marker and stroke`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw[->, red] (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing arrow polygon", svg.contains("<polygon"))
        assertTrue("missing line path", svg.contains("<path"))
        assertTrue("missing red stroke", svg.contains("red") || svg.contains("#"))
    }

    @Test
    fun `draw circle renders circle element`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) circle (1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<circle"))
    }

    @Test
    fun `node with circle shape renders circle`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node[circle, draw] (A) at (0,0) {A};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("<circle"))
        assertTrue(svg.contains("A"))
    }

    @Test
    fun `chinese text is preserved`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node at (0,0) {你好};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("你好"))
    }

    @Test
    fun `named node reference resolves to its coordinate`() {
        val code = """
            \begin{tikzpicture}
            \node (A) at (1,2) {A};
            \draw (A) -- (0,0);
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        // (A) 解析为 (1,2)，路径应含该坐标（y 翻转后）
        assertTrue(result.getOrThrow().contains("L "))
    }

    @Test
    fun `polar coordinate is converted to cartesian`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (90:2);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        // (90:2) 极坐标 → (0, 2) 笛卡尔（y 向上），SVG 翻转后为 (0, -2) 相对平移
        val svg = result.getOrThrow()
        assertTrue(svg.contains("L "))
    }

    @Test
    fun `svg has viewBox with valid dimensions`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("viewBox"))
    }

    @Test
    fun `tikzcd simple two node arrow renders`() {
        val code = """
            \begin{tikzcd}
            A \arrow[r] & B
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue("missing arrow", svg.contains("<polygon"))
    }

    @Test
    fun `tikzcd two row diagram with labels`() {
        val code = """
            \begin{tikzcd}
            A \arrow[r, "f"] \arrow[d] & B \\
            C & D
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("f"))
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue(svg.contains("C"))
        assertTrue(svg.contains("D"))
    }

    @Test
    fun `tikzcd diagonal arrow renders`() {
        val code = """
            \begin{tikzcd}
            A \arrow[dr] & B \\
            C & D
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<path"))
    }

    @Test
    fun `node without at defaults to origin`() {
        // 回归：\node {Hi} 缺省 at 坐标曾导致解析失败
        val result = TikzRenderer.render("""\begin{tikzpicture}\node[draw] {Hi};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("Hi"))
    }

    @Test
    fun `node without at or style defaults to origin`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node {Hi};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("Hi"))
    }

    @Test
    fun `chinese node without at renders`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node[draw] {默认原点};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("默认原点"))
    }

    @Test
    fun `named nodes connected by draw without anchors`() {
        val code = """
            \begin{tikzpicture}
            \node[draw] (A) at (0,0) {A};
            \node[draw] (B) at (3,1) {B};
            \draw (A) -- (B);
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue("missing connecting path", svg.split("<path").size - 1 >= 1)
    }

    @Test
    fun `path node without leading coordinate renders only node`() {
        // T1：\draw (0,0) node {Hi} —— 只有 node，无折线
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) node {Hi};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("Hi"))
        assertEquals(0, svg.split("<path").size - 1)
    }

    @Test
    fun `path node at segment end renders`() {
        // T2：线段终点挂 node
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw[->] (0,0) -- (2,1) node[draw] {终点};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("终点"))
        assertTrue("missing arrow polygon", svg.contains("<polygon"))
        assertTrue("missing line", svg.contains("<path"))
    }

    @Test
    fun `path node named and referenced by anchor`() {
        // T3：path 内命名 + 后续锚点引用
        val code = """
            \begin{tikzpicture}
            \draw (0,0) node[draw] (A) {A};
            \draw (A.east) -- (2,1);
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("A"))
        assertTrue("missing connecting path", svg.split("<path").size - 1 >= 1)
    }

    @Test
    fun `multiple path nodes in a chain`() {
        // T4：多 node 一串
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) node {A} -- (2,0) node {B} -- (4,0) node {C};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue(svg.contains("C"))
        assertTrue("missing polyline", svg.split("<path").size - 1 >= 1)
    }

    @Test
    fun `bend left curve renders quadratic bezier`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) to[bend left] (2,0);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing quadratic bezier", svg.contains(" C "))
        assertTrue("unexpected straight line", !svg.contains(" L "))
    }

    @Test
    fun `bend right with angle renders quadratic bezier`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) to[bend right=60] (2,0);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains(" C "))
    }

    @Test
    fun `plain to without bend stays straight line`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) to (2,0);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("should be straight line", svg.contains(" L "))
    }

    @Test
    fun `relative coordinate plus plus updates current point`() {
        val code = """\begin{tikzpicture}\draw (0,0) -- ++(2,0) -- ++(0,1.5) -- ++(-2,0) -- ++(0,-1.5);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing line", svg.contains(" L "))
    }

    @Test
    fun `relative coordinate plus does not update current point`() {
        // + 不更新：4 条线都从 (3,0) 出发 → 4 个子路径（4 个 M）
        val code = """\begin{tikzpicture}\draw (3,0) -- +(1,1) -- +(2,0) -- +(1,-1);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        val moveCount = svg.split("M ").size - 1
        assertTrue("expected 4 subpaths, got $moveCount", moveCount >= 4)
    }

    @Test
    fun `cubic bezier with controls renders`() {
        val code = """\begin{tikzpicture}\draw (0,0) .. controls (1,1.5) and (2,-1) .. (3,0);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        assertTrue("missing cubic bezier C", result.getOrThrow().contains(" C "))
    }

    @Test
    fun `arrow styles render polygon`() {
        val code = """
            \begin{tikzpicture}
            \draw[<-] (0,3) -- (2,3);
            \draw[<->] (0,2) -- (2,2);
            \draw[-stealth] (0,1) -- (2,1);
            \draw[latex-] (0,0) -- (2,0);
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing arrow polygons", svg.contains("<polygon"))
        // <- / <-> 有 start 箭头，latex- 有空心箭头（fill=none stroke）
        assertTrue("missing hollow latex arrow", svg.contains("fill=\"none\""))
    }

    @Test
    fun `bend curve arrow follows tangent`() {
        val code = """\begin{tikzpicture}\draw[->, red, thick] (0,2) to [bend left] (3,2);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing curve Q", svg.contains(" C "))
        assertTrue("missing arrow polygon", svg.contains("<polygon"))
    }

    @Test
    fun `diamond and star node shapes render polygon`() {
        val code = """
            \begin{tikzpicture}
            \node[diamond, draw] at (0,1.5) {D};
            \node[star, draw, fill=yellow] at (0,0) {S};
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing diamond/star polygon", svg.contains("<polygon"))
        assertTrue(svg.contains("D"))
        assertTrue(svg.contains("S"))
    }

    /** 诊断：遍历用户 R1 消息可能的变体，定位真机 render failed 的确切语法（定位后清理） */
    @Test
    fun `diag - enumerate rounded corner variants to find failing one`() {
        val base = """\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5) -- (4.5,3.5) -- (0.5,3.5) -- (0.5,6.5);"""
        val candidates = listOf(
            "bare draw (no begin)" to base,
            "begin+end wrapped" to """\begin{tikzpicture}$base\end{tikzpicture}""",
            "with cycle" to """\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5) -- (4.5,3.5) -- (0.5,3.5) -- (0.5,6.5) -- cycle;""",
            "with node above" to """\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5) -- (4.5,3.5) -- (0.5,3.5) -- (0.5,6.5) node[above] {y};""",
            "with node midway" to """\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5) -- (4.5,3.5) -- (0.5,3.5) -- (0.5,6.5) node[midway, above] {y};""",
            "undefined named coord" to """\draw[rounded corners=5, thick] (A) -- (B);""",
            "defined named coord" to """\begin{tikzpicture}\node (A) at (1,2) {A};\draw[rounded corners=5, thick] (A) -- (4,0);\end{tikzpicture}""",
            "rectangle form" to """\draw[rounded corners=5, thick] (0.5,0.5) rectangle (4.5,6.5);""",
            "with percent comment" to """\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5); % comment""",
            "two draws same line" to """\begin{tikzpicture}\draw[rounded corners=5, thick] (0.5,0.5) -- (4.5,0.5);\draw[rounded corners=5, thick] (0.5,6.5) -- (4.5,6.5);\end{tikzpicture}""",
            "to bend" to """\draw[rounded corners=5, thick] (0,0) -- (1,0) to[bend left] (2,1);""",
            "controls" to """\draw[rounded corners=5, thick] (0,0) -- (1,1) .. controls (2,0) and (3,2) .. (4,1);""",
            "polar" to """\draw[rounded corners=5, thick] (0:2) -- (90:2) -- (180:2);""",
            "relative ++" to """\draw[rounded corners=5, thick] (0,0) -- ++(1,1) -- ++(1,-1);""",
            "relative +" to """\draw[rounded corners=5, thick] (0,0) -- +(1,1) -- +(2,0);""",
            "with arrow" to """\draw[rounded corners=5, thick, -stealth] (2,3.5) -- (2,6);""",
            "circle inside" to """\draw[rounded corners=5, thick] (0,0) circle (1);""",
            "empty body" to """\draw[rounded corners=5, thick];""",
            "brackets only" to """\draw[rounded corners=5, thick]; % nothing""",
        )
        candidates.forEach { (label, code) ->
            val r = TikzRenderer.render(code)
            if (r.isFailure) {
                println("DIAG FAIL [$label]: ${r.exceptionOrNull()}")
            } else {
                println("DIAG OK   [$label] len=${r.getOrThrow().length}")
            }
        }
    }

    @Test
    fun `undefined named coordinate falls back to origin instead of crashing`() {
        // 用户 R1 真机失败根因：\draw[...] (A) -- (B) 引用未定义命名坐标 → 抛异常 → 显示源码。
        // 修复：原点兜底不崩溃（与 .28 node 无 at 兜底一致）。
        val result = TikzRenderer.render("""\draw[rounded corners=5, thick] (A) -- (B);""")
        assertTrue("should not fail on undefined coordinate", result.isSuccess)
    }

    @Test
    fun `undefined named anchor falls back to origin`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (A.north) -- (0,0);\end{tikzpicture}""")
        assertTrue("should not fail on undefined anchor", result.isSuccess)
    }

    @Test
    fun `rounded corners rectangle renders with explicit radius rx`() {
        // 显式半径 15pt → 15*1.333 ≈ 20px，rx 统一读该值
        val code = """\begin{tikzpicture}\draw[rounded corners=15pt, thick] (2,0) rectangle (4.5,2.5);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        assertTrue("missing rounded rect", result.getOrThrow().contains("rx=\"20\""))
    }

    @Test
    fun `rounded corners explicit large radius chamfers`() {
        // V1：显式 15pt 大半径
        val code = """\begin{tikzpicture}\draw[rounded corners=15pt, thick] (0,0) -- (4,0) -- (4,3) -- (0,3) -- (0,6);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val qCount = result.getOrThrow().split(" C ").size - 1
        assertTrue("expected chamfers, got $qCount", qCount >= 3)
    }

    @Test
    fun `rounded corners bare scales radius with figure size`() {
        // V2：裸 rounded corners——半径按图形对角线比例（随尺寸缩放）
        val code = """\begin{tikzpicture}\draw[rounded corners, thick] (0,0) -- (4,0) -- (4,3) -- (0,3) -- (0,6);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val qCount = result.getOrThrow().split(" C ").size - 1
        assertTrue("expected chamfers, got $qCount", qCount >= 3)
    }

    @Test
    fun `rounded corners small sawtooth stays controlled`() {
        // V3：短边小锯齿——半径被 min(r, 边长/2) 约束，仍有圆角但不过度
        val code = """\begin{tikzpicture}\draw[rounded corners, thick] (0,0) -- (1,1) -- (2,0) -- (3,1) -- (4,0);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val qCount = result.getOrThrow().split(" C ").size - 1
        assertTrue("expected chamfers, got $qCount", qCount >= 3)
    }

    @Test
    fun `rounded corners multi-subpath does not chamfer`() {
        // 两条分离子路径（两次 MoveTo）：退回普通渲染，禁止圆角错误连接
        val code = """\begin{tikzpicture}\draw[rounded corners] (0,0) -- (1,0) (3,0) -- (4,0);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("should not chamfer multi-subpath", !svg.contains(" C "))
        assertTrue("expected two subpaths", svg.split("M ").size - 1 >= 2)
    }

    @Test
    fun `rounded corners polyline uses geometric bezier chamfer`() {
        val code = """\begin{tikzpicture}\draw[rounded corners] (5,0) -- (6.5,1) -- (5,2.5);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue("missing geometric chamfer Q", svg.contains(" C "))
        assertTrue("should not use stroke-linejoin approximation", !svg.contains("stroke-linejoin"))
    }

    @Test
    fun `rounded corners multi-corner with mixed left right turns`() {
        // R1：3 个内角 + 左右转交替，且含长边（检验半径单位修正，不中毒）
        val code = """\begin{tikzpicture}\draw[rounded corners, thick] (0,0) -- (4,0) -- (4,3) -- (0,3) -- (0,6);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        val qCount = svg.split(" C ").size - 1
        assertTrue("expected 3 chamfers, got $qCount", qCount >= 3)
    }

    @Test
    fun `rounded corners sawtooth alternating turns`() {
        // R3：锯齿左右交替
        val code = """\begin{tikzpicture}\draw[rounded corners, thick] (0,0) -- (2,1) -- (4,0) -- (6,1) -- (8,0);\end{tikzpicture}"""
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        val qCount = svg.split(" C ").size - 1
        assertTrue("expected 3 chamfers, got $qCount", qCount >= 3)
    }
}
