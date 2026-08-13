package app.androidrik.rikkahub.tikz

/**
 * 纯 Kotlin TikZ 子集渲染器：TikZ 源码 → SVG 字符串。
 *
 * 覆盖 MVP 子集：
 * - `\node[style] (name) at (x,y) {text}` / `\coordinate (name) at (x,y)`
 * - `\draw[style] (p) -- (q) ...` / `circle (r)` / `rectangle (x2,y2)`
 * - 坐标：直角 `(x,y)`、极坐标 `(angle:radius)`、命名节点 `(A)`、锚点 `(A.north)`
 * - 样式：颜色名/#hex、draw、fill、thick/very thick、dashed、箭头 ->/<-/<->
 * - 中文文本原样输出
 *
 * 坐标约定：TikZ y 轴向上，SVG y 轴向下（渲染时翻转）；1 TikZ 单位 = 100 SVG 单位。
 */
object TikzRenderer {

    private const val SCALE = 100.0

    /** 渲染 TikZ 源码为 SVG 字符串；失败时返回异常（runCatching 包装）。 */
    fun render(code: String, darkMode: Boolean = false): Result<String> = runCatching {
        val trimmed = code.trim()
        val ctx = ParseContext()
        val elements = if (trimmed.contains("\\begin{tikzcd}")) {
            parseTikzcd(trimmed, ctx)
        } else {
            parseCommands(extractBody(trimmed), ctx)
        }
        if (elements.isEmpty()) {
            throw IllegalArgumentException("No renderable TikZ elements found")
        }
        val svg = renderSvg(elements, ctx)
        // 深色模式：默认黑色线条/文本改为白色（命名颜色如 red/blue 深色下仍可见，保持不变）
        if (darkMode) svg.replace("black", "white") else svg
    }

    // ---- 坐标 / 样式 AST ----

    private data class Point(val x: Double, val y: Double)

    private enum class NodeShape { RECTANGLE, CIRCLE, ELLIPSE }

    private enum class Arrow { NONE, END, START, BOTH }

    private data class Style(
        val draw: Boolean = true,
        val stroke: String = "black",
        val fill: String? = null,
        val strokeWidth: Double = 1.0,
        val dashed: Boolean = false,
        val arrow: Arrow = Arrow.NONE,
        val shape: NodeShape = NodeShape.RECTANGLE,
    )

    private sealed interface Element

    private data class NodeElement(
        val name: String?,
        val at: Point,
        val style: Style,
        val shape: NodeShape,
        val text: String,
    ) : Element

    private data class CoordinateElement(val name: String, val at: Point) : Element

    private data class DrawElement(
        val style: Style,
        val points: List<Point> = emptyList(),
        val circleRadius: Double? = null,
        val rectCorner: Point? = null,
    ) : Element

    private class ParseContext {
        val coordinates = mutableMapOf<String, Point>()
    }

    // ---- 预处理 ----

    private fun extractBody(code: String): String {
        val trimmed = code.trim()
        val beginMarker = "\\begin{tikzpicture}"
        val beginIdx = trimmed.indexOf(beginMarker)
        val endIdx = trimmed.lastIndexOf("\\end{tikzpicture}")
        return when {
            beginIdx >= 0 && endIdx > beginIdx -> {
                var bodyStart = beginIdx + beginMarker.length
                // 跳过可选的 [options]
                if (bodyStart < trimmed.length && trimmed[bodyStart] == '[') {
                    val close = trimmed.indexOf(']', bodyStart)
                    if (close >= 0) bodyStart = close + 1
                }
                trimmed.substring(bodyStart, endIdx)
            }
            else -> trimmed
        }
    }

    // ---- tikzcd 交换图 ----

    private data class ArrowSpec(val dir: String, val label: String?, val prime: Boolean)

    private val ARROW_REGEX = Regex("""\\arrow\s*\[([^\]]*)\]""")

    /** tikzcd 布局参数（TikZ 单位） */
    private const val TIKZCD_COL_SEP = 2.0
    private const val TIKZCD_ROW_SEP = 1.5

    private fun parseTikzcd(code: String, ctx: ParseContext): List<Element> {
        var body = code.substringAfter("\\begin{tikzcd}").substringBefore("\\end{tikzcd}")
        if (body.startsWith("[")) {
            val close = body.indexOf(']')
            if (close >= 0) body = body.substring(close + 1)
        }

        val elements = mutableListOf<Element>()
        // 按 \\ 分行（注意：\\arrow 是单个反斜杠，不会被 split("\\\\") 误伤）
        val rows = body.split("\\\\")
        rows.forEachIndexed { row, rowStr ->
            if (rowStr.isBlank()) return@forEachIndexed
            val cells = rowStr.split("&")
            cells.forEachIndexed { col, cellStr ->
                val x = col * TIKZCD_COL_SEP
                val y = -row * TIKZCD_ROW_SEP
                val text = cellStr.replace(ARROW_REGEX, "").trim()
                elements.add(NodeElement(null, Point(x, y), Style(draw = false), NodeShape.RECTANGLE, text))
                // 解析该单元格的所有 \arrow
                ARROW_REGEX.findAll(cellStr).forEach { m ->
                    val spec = parseArrow(m.groupValues[1])
                    val (dr, dc) = dirToDelta(spec.dir)
                    val from = Point(x, y)
                    val to = Point((col + dc) * TIKZCD_COL_SEP, -(row + dr) * TIKZCD_ROW_SEP)
                    elements.add(DrawElement(Style(arrow = Arrow.END), points = listOf(from, to)))
                    spec.label?.let { lbl ->
                        val mid = Point((from.x + to.x) / 2, (from.y + to.y) / 2)
                        elements.add(NodeElement(null, mid, Style(draw = false), NodeShape.RECTANGLE, lbl))
                    }
                }
            }
        }
        return elements
    }

    private fun parseArrow(spec: String): ArrowSpec {
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val dir = parts.firstOrNull() ?: "r"
        val label = parts.firstOrNull { it.startsWith("\"") }?.trim('"')?.takeIf { it.isNotEmpty() }
        val prime = parts.any { it == "'" }
        return ArrowSpec(dir, label, prime)
    }

    private fun dirToDelta(dir: String): Pair<Int, Int> {
        var dr = 0
        var dc = 0
        for (ch in dir) {
            when (ch) {
                'r' -> dc += 1
                'l' -> dc -= 1
                'u' -> dr -= 1
                'd' -> dr += 1
            }
        }
        return dr to dc
    }

    // ---- 命令解析 ----

    private fun parseCommands(body: String, ctx: ParseContext): List<Element> {
        val commands = splitCommands(body)
        // 第一遍：收集所有命名坐标（\coordinate + 顶层 \node + path 内 node）
        commands.forEach { cmd ->
            parseCoordinate(cmd, ctx)
            collectNodeName(cmd, ctx)
            collectDrawNodeNames(cmd, ctx)
        }
        // 第二遍：生成可渲染元素（parseDraw 可能返回多个：折线 + path 内 node）
        val elements = mutableListOf<Element>()
        commands.forEach { cmd ->
            val el = parseNode(cmd, ctx)
            if (el != null) {
                elements.add(el)
            } else {
                elements.addAll(parseDraw(cmd, ctx))
            }
        }
        return elements
    }

    /** 按分号分割命令，忽略空段与行尾注释（% 到行尾） */
    private fun splitCommands(body: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inComment = false
        for (ch in body) {
            when {
                inComment -> if (ch == '\n') inComment = false
                ch == '%' -> inComment = true
                ch == ';' -> { result.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(ch)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result.filter { it.isNotEmpty() && it.startsWith("\\") }
    }

    private fun parseCoordinate(cmd: String, ctx: ParseContext) {
        val m = Regex("""\\coordinate\s*\(([^)]+)\)\s+at\s+\(([^)]+)\)""").find(cmd) ?: return
        val name = m.groupValues[1].trim()
        val point = parseCoordinatePoint(m.groupValues[2], ctx)
        ctx.coordinates[name] = point
    }

    /** 第一遍收集 \node 的 name 坐标，供后续 \draw (A) 引用（at 缺省时用原点兜底） */
    private fun collectNodeName(cmd: String, ctx: ParseContext) {
        val m = Regex("""\\node\s*(\[[^\]]*\])?\s*\(([^)]+)\)\s*(?:at\s+\(([^)]+)\))?""").find(cmd) ?: return
        val name = m.groupValues[2].trim()
        val atRaw = m.groupValues[3]
        val point = if (atRaw.isNotBlank()) parseCoordinatePoint(atRaw, ctx) else Point(0.0, 0.0)
        ctx.coordinates[name] = point
    }

    private fun parseNode(cmd: String, ctx: ParseContext): Element? {
        // 组1=选项 组2=名字 组3=at坐标 组4=文本；at 坐标可选，缺省原点 (0,0)
        val m = Regex("""\\node\s*(\[[^\]]*\])?\s*(?:\(([^)]+)\))?\s*(?:at\s+\(([^)]+)\))?\s*\{([^}]*)\}""").find(cmd) ?: return null
        val styleStr = m.groupValues[1].orEmpty()
        val name = m.groupValues[2].ifBlank { null }
        val atRaw = m.groupValues[3]
        val at = if (atRaw.isNotBlank()) parseCoordinatePoint(atRaw, ctx) else Point(0.0, 0.0)
        val text = m.groupValues[4]
        val style = parseStyle(styleStr)
        name?.let { ctx.coordinates[it] = at }
        return NodeElement(name, at, style, style.shape, text)
    }

    private fun parseDraw(cmd: String, ctx: ParseContext): List<Element> {
        val m = Regex("""\\(?:draw|path)\s*(\[[^\]]*\])?(.*)""").find(cmd) ?: return emptyList()
        val styleStr = m.groupValues[1].orEmpty()
        val body = m.groupValues[2].trim()
        val style = parseStyle(styleStr)

        // circle (r) —— 不含 node，正则快速返回
        Regex("""\(([^)]+)\)\s+circle\s+\(([^)]+)\)""").find(body)?.let {
            val center = parseCoordinatePoint(it.groupValues[1], ctx)
            val radius = it.groupValues[2].toDoubleOrNull() ?: return emptyList()
            return listOf(DrawElement(style, circleRadius = radius, points = listOf(center)))
        }
        // rectangle (x2,y2)
        Regex("""\(([^)]+)\)\s+rectangle\s+\(([^)]+)\)""").find(body)?.let {
            val a = parseCoordinatePoint(it.groupValues[1], ctx)
            val b = parseCoordinatePoint(it.groupValues[2], ctx)
            return listOf(DrawElement(style, rectCorner = b, points = listOf(a)))
        }

        // 折线 + path 内 node
        val elements = mutableListOf<Element>()
        val points = scanPath(body, ctx) { name, at, nodeStyleStr, text ->
            val nodeStyle = parseStyle(nodeStyleStr)
            name?.let { ctx.coordinates[it] = at }
            elements.add(NodeElement(name, at, nodeStyle, nodeStyle.shape, text))
        }
        // 只有 ≥2 点才画折线（T1：只有 node 无线段时不输出 path）
        if (points.size >= 2) {
            elements.add(DrawElement(style, points = points))
        }
        return elements
    }

    /** 第一遍收集 path 内 node 的 name 坐标，供后续 (A)/(A.east) 引用 */
    private fun collectDrawNodeNames(cmd: String, ctx: ParseContext) {
        if (!cmd.startsWith("\\draw") && !cmd.startsWith("\\path")) return
        val m = Regex("""\\(?:draw|path)\s*(\[[^\]]*\])?(.*)""").find(cmd) ?: return
        val body = m.groupValues[2].trim()
        // circle/rectangle 不含 node，跳过扫描（避免把 circle 半径 (1) 误当坐标解析）
        if (body.contains("circle") || body.contains("rectangle")) return
        scanPath(body, ctx) { name, at, _, _ ->
            name?.let { ctx.coordinates[it] = at }
        }
    }

    /**
     * 顺序扫描路径 body：识别坐标 `(x,y)` 与 `node [opts] (name) {text}`。
     * node 出现的位置 = 路径累加到的当前点（cur）；返回所有折线坐标。
     */
    private fun scanPath(
        body: String,
        ctx: ParseContext,
        onNode: (name: String?, at: Point, styleStr: String, text: String) -> Unit,
    ): List<Point> {
        val points = mutableListOf<Point>()
        var cur = Point(0.0, 0.0)
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == '(' -> {
                    val close = body.indexOf(')', i)
                    if (close < 0) { i++; continue }
                    val raw = body.substring(i + 1, close)
                    val p = parseCoordinatePoint(raw, ctx)
                    points.add(p)
                    cur = p
                    i = close + 1
                }
                body.startsWith("node", i) -> {
                    i += 4
                    var styleStr = ""
                    if (i < body.length && body[i] == '[') {
                        val close = body.indexOf(']', i)
                        if (close >= 0) { styleStr = body.substring(i + 1, close); i = close + 1 }
                    }
                    while (i < body.length && body[i].isWhitespace()) i++
                    var name: String? = null
                    if (i < body.length && body[i] == '(') {
                        val close = body.indexOf(')', i)
                        if (close >= 0) { name = body.substring(i + 1, close).trim(); i = close + 1 }
                    }
                    while (i < body.length && body[i].isWhitespace()) i++
                    var text = ""
                    if (i < body.length && body[i] == '{') {
                        val close = body.indexOf('}', i)
                        if (close >= 0) { text = body.substring(i + 1, close); i = close + 1 }
                    }
                    onNode(name, cur, styleStr, text)
                }
                else -> i++
            }
        }
        return points
    }

    private fun parseCoordinatePoint(raw: String, ctx: ParseContext): Point {
        val s = raw.trim()
        // 极坐标 (angle:radius)
        if (s.contains(':')) {
            val (angle, radius) = s.split(':', limit = 2)
            val a = angle.toDoubleOrNull() ?: 0.0
            val r = radius.toDoubleOrNull() ?: 0.0
            val rad = Math.toRadians(a)
            return Point(r * kotlin.math.cos(rad), r * kotlin.math.sin(rad))
        }
        // 直角 (x,y)
        if (s.contains(',')) {
            val parts = s.split(',')
            val x = parts[0].trim().toDoubleOrNull() ?: 0.0
            val y = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
            return Point(x, y)
        }
        // 命名节点 (A) 或锚点 (A.north)
        val anchor = s.split('.', limit = 2)
        val base = ctx.coordinates[anchor[0]] ?: throw IllegalArgumentException("Unknown coordinate: $s")
        val anchorName = anchor.getOrNull(1)?.trim()
        return when (anchorName) {
            "north" -> base.copy(y = base.y + 0.3)
            "south" -> base.copy(y = base.y - 0.3)
            "east" -> base.copy(x = base.x + 0.3)
            "west" -> base.copy(x = base.x - 0.3)
            else -> base
        }
    }

    // ---- 样式解析 ----

    private val COLOR_NAMES = mapOf(
        "red" to "red", "blue" to "blue", "green" to "green", "black" to "black",
        "white" to "white", "gray" to "gray", "grey" to "gray", "orange" to "orange",
        "purple" to "purple", "yellow" to "yellow", "pink" to "pink", "brown" to "brown",
        "cyan" to "cyan", "magenta" to "magenta",
    )

    private fun parseStyle(raw: String): Style {
        var style = Style()
        if (raw.isBlank()) return style
        val inner = raw.removePrefix("[").removeSuffix("]")
        inner.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            when {
                token == "draw" -> style = style.copy(draw = true)
                token == "fill" -> style = style.copy(fill = "black")
                token.startsWith("fill=") -> style = style.copy(fill = resolveColor(token.substringAfter("=")))
                token == "dashed" -> style = style.copy(dashed = true)
                token == "dotted" -> style = style.copy(dashed = true)
                token == "circle" -> style = style.copy(shape = NodeShape.CIRCLE)
                token == "ellipse" -> style = style.copy(shape = NodeShape.ELLIPSE)
                token == "->" -> style = style.copy(arrow = Arrow.END)
                token == "<-" -> style = style.copy(arrow = Arrow.START)
                token == "<->" -> style = style.copy(arrow = Arrow.BOTH)
                token == "thick" -> style = style.copy(strokeWidth = 2.0)
                token == "very thick" -> style = style.copy(strokeWidth = 3.0)
                token == "ultra thick" -> style = style.copy(strokeWidth = 4.0)
                token == "thin" -> style = style.copy(strokeWidth = 0.7)
                token == "rectangle" -> style = style.copy(shape = NodeShape.RECTANGLE)
                else -> {
                    // 纯颜色名 / #hex 作为 stroke
                    val c = resolveColor(token)
                    if (c != null) style = style.copy(stroke = c)
                }
            }
        }
        return style
    }

    private fun resolveColor(token: String): String? {
        if (token.startsWith("#")) return token
        // 支持 red!30 形式（颜色 + 透明度），忽略 ! 后部分
        val base = token.substringBefore('!')
        return COLOR_NAMES[base.lowercase()]
    }

    // ---- SVG 生成 ----

    private const val PADDING = 0.5
    private const val NODE_HALF_W = 0.42
    private const val NODE_HALF_H = 0.28
    private const val NODE_CIRCLE_R = 0.22

    private fun renderSvg(elements: List<Element>, ctx: ParseContext): String {
        // 收集所有坐标计算边界（TikZ 坐标系，y 向上）
        val allPoints = mutableListOf<Point>()
        elements.forEach { el ->
            when (el) {
                is NodeElement -> {
                    val halfW = if (el.shape == NodeShape.CIRCLE) NODE_CIRCLE_R else NODE_HALF_W
                    val halfH = if (el.shape == NodeShape.CIRCLE) NODE_CIRCLE_R else NODE_HALF_H
                    allPoints.add(Point(el.at.x - halfW, el.at.y - halfH))
                    allPoints.add(Point(el.at.x + halfW, el.at.y + halfH))
                }
                is CoordinateElement -> allPoints.add(el.at)
                is DrawElement -> {
                    allPoints.addAll(el.points)
                    el.rectCorner?.let { allPoints.add(it) }
                    el.circleRadius?.let { r ->
                        el.points.firstOrNull()?.let { c ->
                            allPoints.add(Point(c.x - r, c.y - r))
                            allPoints.add(Point(c.x + r, c.y + r))
                        }
                    }
                }
            }
        }
        val minX = allPoints.minOf { it.x }
        val maxX = allPoints.maxOf { it.x }
        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        val width = ((maxX - minX) + 2 * PADDING) * SCALE
        val height = ((maxY - minY) + 2 * PADDING) * SCALE

        // TikZ → SVG 坐标转换（y 翻转）
        fun sx(x: Double): Double = (x - minX + PADDING) * SCALE
        fun sy(y: Double): Double = (maxY - y + PADDING) * SCALE

        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${fmt(width)} ${fmt(height)}">""")
        sb.append('\n')

        // 先画 draw（底层），再画 node（上层）
        elements.forEach { el ->
            when (el) {
                is DrawElement -> sb.append(renderDraw(el, ::sx, ::sy)).append('\n')
                is NodeElement -> sb.append(renderNode(el, ::sx, ::sy)).append('\n')
                is CoordinateElement -> Unit
            }
        }

        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmt(v: Double): String {
        val rounded = Math.round(v * 100.0) / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private fun renderDraw(el: DrawElement, sx: (Double) -> Double, sy: (Double) -> Double): String {
        val style = el.style
        val stroke = "stroke=\"${style.stroke}\" stroke-width=\"${style.strokeWidth}\""
        val dash = if (style.dashed) " stroke-dasharray=\"5,5\"" else ""
        val fill = style.fill?.let { " fill=\"$it\"" } ?: " fill=\"none\""

        return when {
            el.circleRadius != null -> {
                val c = el.points.first()
                """<circle cx="${fmt(sx(c.x))}" cy="${fmt(sy(c.y))}" r="${fmt(el.circleRadius * SCALE)}" $stroke$fill$dash/>"""
            }
            el.rectCorner != null -> {
                val a = el.points.first()
                val b = el.rectCorner
                val x = minOf(a.x, b.x); val yTop = maxOf(a.y, b.y)
                val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                """<rect x="${fmt(sx(x))}" y="${fmt(sy(yTop))}" width="${fmt(w * SCALE)}" height="${fmt(h * SCALE)}" $stroke$fill$dash/>"""
            }
            else -> {
                val d = buildString {
                    el.points.forEachIndexed { i, p ->
                        append(if (i == 0) "M " else " L ")
                        append(fmt(sx(p.x))).append(',').append(fmt(sy(p.y)))
                    }
                }
                val sb = StringBuilder()
                sb.append("""<path d="$d" $stroke$fill$dash/>""")
                // 箭头：AndroidSVG 不支持 <marker>，改为在端点直接画三角形
                val arrowSize = 7.0
                if (el.points.size >= 2) {
                    if (style.arrow == Arrow.END || style.arrow == Arrow.BOTH) {
                        sb.append('\n').append(arrowHead(el.points.last(), el.points[el.points.size - 2], style, sx, sy, arrowSize))
                    }
                    if (style.arrow == Arrow.START || style.arrow == Arrow.BOTH) {
                        sb.append('\n').append(arrowHead(el.points.first(), el.points[1], style, sx, sy, arrowSize))
                    }
                }
                sb.toString()
            }
        }
    }

    /** 在 [tip] 端点画实心三角形箭头（沿 [tip]→[from] 反方向张开） */
    private fun arrowHead(
        tip: Point,
        from: Point,
        style: Style,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
        size: Double,
    ): String {
        val tipX = sx(tip.x); val tipY = sy(tip.y)
        val fromX = sx(from.x); val fromY = sy(from.y)
        val angle = Math.atan2(tipY - fromY, tipX - fromX)
        val a1 = angle + Math.PI * 0.8
        val a2 = angle - Math.PI * 0.8
        val p1x = tipX - size * Math.cos(a1); val p1y = tipY - size * Math.sin(a1)
        val p2x = tipX - size * Math.cos(a2); val p2y = tipY - size * Math.sin(a2)
        return """<path d="M ${fmt(p1x)},${fmt(p1y)} L ${fmt(tipX)},${fmt(tipY)} L ${fmt(p2x)},${fmt(p2y)} Z" fill="${style.stroke}"/>"""
    }

    private fun renderNode(el: NodeElement, sx: (Double) -> Double, sy: (Double) -> Double): String {
        val style = el.style
        val cx = sx(el.at.x)
        val cy = sy(el.at.y)
        val stroke = if (style.draw) "stroke=\"${style.stroke}\" stroke-width=\"${style.strokeWidth}\"" else ""
        val fill = style.fill?.let { " fill=\"$it\"" } ?: " fill=\"none\""
        val textColor = " fill=\"${style.stroke}\""
        val shapeSvg = when (el.shape) {
            NodeShape.CIRCLE -> """<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(NODE_CIRCLE_R * SCALE)}" $stroke$fill/>"""
            NodeShape.ELLIPSE -> """<ellipse cx="${fmt(cx)}" cy="${fmt(cy)}" rx="${fmt(NODE_HALF_W * SCALE)}" ry="${fmt(NODE_HALF_H * SCALE)}" $stroke$fill/>"""
            NodeShape.RECTANGLE -> """<rect x="${fmt(cx - NODE_HALF_W * SCALE)}" y="${fmt(cy - NODE_HALF_H * SCALE)}" width="${fmt(2 * NODE_HALF_W * SCALE)}" height="${fmt(2 * NODE_HALF_H * SCALE)}" rx="2" $stroke$fill/>"""
        }
        // 文本：y 直接定位到近似垂直中心（fontSize 14 的一半略多）；AndroidSVG 不支持 dominant-baseline/dy em
        val text = if (el.text.isNotBlank()) {
            """<text x="${fmt(cx)}" y="${fmt(cy + 5.0)}" text-anchor="middle" font-size="14"$textColor>${escapeXml(el.text)}</text>"""
        } else ""
        return "$shapeSvg\n$text"
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
