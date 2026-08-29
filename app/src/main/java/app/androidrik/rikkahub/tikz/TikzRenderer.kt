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

    /** SVG 最大像素尺寸：防异常坐标（10^12 级）产生巨型画布导致布局溢出 */
    private const val MAX_SVG_PIXELS = 20_000.0

    /** plot 采样坐标绝对值上限：防巨大有限值（如 10^12）撑爆画布 */
    private const val MAX_PLOT_ABS = 5_000.0

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

    private enum class NodeShape { RECTANGLE, CIRCLE, ELLIPSE, DIAMOND, STAR }

    /** 箭头尖端类型 */
    private enum class ArrowTip { TRIANGLE, STEALTH, LATEX }

    private data class Style(
        val draw: Boolean = true,
        val stroke: String = "black",
        val fill: String? = null,
        val strokeWidth: Double = 1.0,
        val dashed: Boolean = false,
        val arrowStart: ArrowTip? = null,
        val arrowEnd: ArrowTip? = null,
        val shape: NodeShape = NodeShape.RECTANGLE,
        val rounded: Boolean = false,
        /** 圆角半径（px）。裸 rounded corners 时按图形对角线比例计算；=NNpt 时用户显式指定 */
        val roundedRadius: Double = 5.0,
        val roundedRadiusExplicit: Boolean = false,
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

    private sealed interface Seg

    private data class MoveTo(val p: Point) : Seg

    private data class LineTo(val p: Point) : Seg

    private data class QuadTo(val c: Point, val p: Point) : Seg

    private data class CubicTo(val c1: Point, val c2: Point, val p: Point) : Seg

    /** 闭合当前子路径：从当前点连回子路径起点（p = 子路径起点，供几何计算） */
    private data class ClosePath(val p: Point) : Seg

    /** SVG 圆弧段：p = 终点（TikZ 坐标）；radius/largeArc/sweep 为 SVG A 命令参数 */
    private data class ArcTo(
        val p: Point,
        val radius: Double,
        val largeArc: Boolean,
        val sweep: Boolean,
    ) : Seg

    private data class DrawElement(
        val style: Style,
        val segs: List<Seg> = emptyList(),
        val circleCenter: Point? = null,
        val circleRadius: Double? = null,
        val rectStart: Point? = null,
        val rectCorner: Point? = null,
    ) : Element

    private class ParseContext {
        val coordinates = mutableMapOf<String, Point>()

        /** positioning 定位声明（第一遍收集，第二遍前统一求解） */
        val pendingPlacements = mutableListOf<PlacementSpec>()

        /** 声明为 circle 形状的节点名（求解定位时用圆半径而非矩形半宽） */
        val circleNodes = mutableSetOf<String>()
    }

    /**
     * positioning 库定位（`right=of a` / `above right=2cm of b` / 旧语法 `right of=a`）。
     *
     * @param dx/dy 方向（TikZ y 轴向上，above=+1）
     * @param distance 边到边距离（TikZ 单位）；null = 默认 node distance 1cm
     * @param refNode 参考节点名
     * @param legacy 旧语法（`right of=a`，无等号）：中心到中心语义
     */
    private data class PlacementSpec(
        val nodeName: String,
        val dx: Int,
        val dy: Int,
        val distance: Double?,
        val refNode: String,
        val legacy: Boolean,
        val styleStr: String,
    )

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
                    elements.add(DrawElement(Style(arrowEnd = ArrowTip.TRIANGLE), segs = listOf(MoveTo(from), LineTo(to))))
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
        // positioning 定位统一求解（支持链式依赖：T2 的 c 依赖 b，b 依赖 a）
        solvePlacements(ctx)
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

    /**
     * positioning 定位求解：按依赖顺序迭代（链式定位逐轮解锁），写入 ctx.coordinates。
     * 参考节点未定义或依赖成环的节点兜底原点。
     *
     * 语义：
     * - 新语法（`right=of a`）：边到边（border gap），TikZ positioning 库标准语义
     * - 旧语法（`right of=a`）：中心到中心（废弃写法的历史语义）
     * - 对角组合（`above right`）：沿 45° 方向的边到边投影
     * - 节点尺寸用绘制同款常量（NODE_HALF_W/H），保证定位与绘制自洽
     */
    private fun solvePlacements(ctx: ParseContext) {
        if (ctx.pendingPlacements.isEmpty()) return
        val pending = ctx.pendingPlacements.toMutableList()
        val maxRounds = pending.size + 1
        repeat(maxRounds) {
            if (pending.isEmpty()) return
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val spec = iter.next()
                val ref = ctx.coordinates[spec.refNode] ?: continue  // 依赖未定位，下一轮
                val pos = computePlacedPosition(spec, ref, ctx)
                ctx.coordinates[spec.nodeName] = pos
                iter.remove()
            }
        }
        // 依赖无法满足的（未定义参考节点/环）兜底原点
        pending.forEach { ctx.coordinates[it.nodeName] = Point(0.0, 0.0) }
        ctx.pendingPlacements.clear()
    }

    private fun computePlacedPosition(spec: PlacementSpec, ref: Point, ctx: ParseContext): Point {
        val (rhw, rhh) = halfSizesOf(spec.refNode, ctx)
        val (hw, hh) = halfSizesOf(spec.nodeName, ctx)
        val d = spec.distance ?: DEFAULT_NODE_DISTANCE
        val sx = spec.dx.toDouble()
        val sy = spec.dy.toDouble()
        return if (spec.legacy) {
            // 旧语法：中心到中心
            Point(ref.x + sx * d, ref.y + sy * d)
        } else if (spec.dx != 0 && spec.dy != 0) {
            // 对角：45° 方向边到边投影
            Point(
                ref.x + sx * ((rhw + d * 0.7071 + hw) * 0.7071 + 0.05),
                ref.y + sy * ((rhh + d * 0.7071 + hh) * 0.7071 + 0.05),
            )
        } else {
            // 纯水平/垂直：边到边
            Point(
                ref.x + sx * (rhw + d + hw),
                ref.y + sy * (rhh + d + hh),
            )
        }
    }

    /** 节点半尺寸估算（与 renderNode 绘制常量一致；circle 类用圆半径） */
    private fun halfSizesOf(nodeName: String, ctx: ParseContext): Pair<Double, Double> {
        // circle 类形状的判断依赖 style，求解阶段简化：按绘制默认矩形尺寸
        //（circle 节点在 positioning 布局中的误差可接受）
        val isCircleLike = ctx.circleNodes.contains(nodeName)
        return if (isCircleLike) {
            NODE_CIRCLE_R to NODE_CIRCLE_R
        } else {
            NODE_HALF_W to NODE_HALF_H
        }
    }

    private val DEFAULT_NODE_DISTANCE = 1.0

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
        val styleStr = m.groupValues[1].orEmpty()
        if (styleStr.contains("circle")) ctx.circleNodes.add(name)
        val placement = parsePlacement(styleStr, name)
        if (placement != null && atRaw.isBlank()) {
            // positioning 定位节点：坐标延后到 solvePlacements 统一求解
            ctx.coordinates[name] = Point(0.0, 0.0)
            ctx.pendingPlacements.add(placement)
            return
        }
        val point = if (atRaw.isNotBlank()) parseCoordinatePoint(atRaw, ctx) else Point(0.0, 0.0)
        ctx.coordinates[name] = point
    }

    /**
     * 解析 positioning 库定位语法（style 选项内）。
     * 新语法：`right=of a` / `above right=2cm of b`（边到边，默认 node distance 1cm）
     * 旧语法：`right of=a`（无等号，中心到中心，废弃写法兼容）
     * 不匹配返回 null。
     */
    private fun parsePlacement(styleStr: String, nodeName: String): PlacementSpec? {
        // 前置快速检查：新语法 "of " 与旧语法 "of=" 任一出现
        if (!styleStr.contains("of ") && !styleStr.contains("of=")) return null
        // 新语法：<dirs>=<dist> of <node>（dist 可缺省）
        PLACEMENT_NEW_REGEX.find(styleStr)?.let { m ->
            val dirs = m.groupValues[1]
            val distRaw = m.groupValues[2]
            val unit = m.groupValues[3]
            val ref = m.groupValues[4].trim()
            val (dx, dy) = parsePlacementDirs(dirs)
            if (dx == 0 && dy == 0) return null
            val distance = if (distRaw.isBlank()) null
            else (distRaw.toDoubleOrNull() ?: return null) * unitScale(unit)
            return PlacementSpec(nodeName, dx, dy, distance, ref, legacy = false, styleStr = styleStr)
        }
        // 旧语法：<dirs> of <node>（无等号）
        PLACEMENT_LEGACY_REGEX.find(styleStr)?.let { m ->
            val dirs = m.groupValues[1]
            val ref = m.groupValues[2].trim()
            val (dx, dy) = parsePlacementDirs(dirs)
            if (dx == 0 && dy == 0) return null
            return PlacementSpec(nodeName, dx, dy, null, ref, legacy = true, styleStr = styleStr)
        }
        return null
    }

    private val PLACEMENT_NEW_REGEX = Regex(
        """((?:above|below|left|right)(?:\s+(?:above|below|left|right))*)\s*=\s*(?:([0-9.]+)\s*(cm|mm|pt|em|ex)?\s+)?of\s+([A-Za-z0-9_.]+)"""
    )
    private val PLACEMENT_LEGACY_REGEX = Regex(
        """((?:above|below|left|right)(?:\s+(?:above|below|left|right))*)\s+of\s*=\s*([A-Za-z0-9_.]+)"""
    )

    private fun parsePlacementDirs(words: String): Pair<Int, Int> {
        var dx = 0
        var dy = 0
        if (words.contains("left")) dx = -1
        if (words.contains("right")) dx = 1
        if (words.contains("above")) dy = 1
        if (words.contains("below")) dy = -1
        return dx to dy
    }

    /** 距离单位 → TikZ 单位（1cm = 1.0） */
    private fun unitScale(unit: String): Double = when (unit) {
        "mm" -> 0.1
        "pt" -> 1.0 / 28.45274
        "em" -> 0.35
        "ex" -> 0.2
        else -> 1.0  // cm 或无单位
    }

    private fun parseNode(cmd: String, ctx: ParseContext): Element? {
        // 组1=选项 组2=名字 组3=at坐标 组4=文本；at 坐标可选：
        // 有名字且无 at 时用求解后的坐标（positioning 节点在 solvePlacements 已定位），
        // 无名字兜底原点
        val m = Regex("""\\node\s*(\[[^\]]*\])?\s*(?:\(([^)]+)\))?\s*(?:at\s+\(([^)]+)\))?\s*\{([^}]*)\}""").find(cmd) ?: return null
        val styleStr = m.groupValues[1].orEmpty()
        val name = m.groupValues[2].ifBlank { null }
        val atRaw = m.groupValues[3]
        val at = when {
            atRaw.isNotBlank() -> parseCoordinatePoint(atRaw, ctx)
            name != null -> ctx.coordinates[name] ?: Point(0.0, 0.0)
            else -> Point(0.0, 0.0)
        }
        val text = m.groupValues[4]
        val style = parseStyle(styleStr)
        name?.let { ctx.coordinates[it] = at }
        return NodeElement(name, at, style, style.shape, text)
    }

    private fun parseDraw(cmd: String, ctx: ParseContext): List<Element> {
        // 备选顺序：长词在前（filldraw 先于 fill），避免 \filldraw 被 fill 前缀抢走
        val m = Regex("""\\(draw|path|filldraw|fill)\s*(\[[^\]]*\])?(.*)""").find(cmd) ?: return emptyList()
        val cmdName = m.groupValues[1]
        val styleStr = m.groupValues[2].orEmpty()
        val body = m.groupValues[3].trim()
        var style = parseStyle(styleStr)
        // \fill：纯填充（无描边），裸颜色/默认黑作填充色；\filldraw：描边+填充（默认均黑）
        when (cmdName) {
            "fill" -> style = style.copy(
                fill = style.fill ?: (if (style.stroke != "black") style.stroke else "black"),
                draw = false,
            )
            "filldraw" -> style = style.copy(fill = style.fill ?: "black")
            else -> Unit  // draw/path 保持默认
        }

        // circle (r) —— 不含 node，正则快速返回
        Regex("""\(([^)]+)\)\s+circle\s+\(([^)]+)\)""").find(body)?.let {
            val center = parseCoordinatePoint(it.groupValues[1], ctx)
            val radius = it.groupValues[2].toDoubleOrNull() ?: return emptyList()
            return listOf(DrawElement(style, circleCenter = center, circleRadius = radius))
        }
        // rectangle (x2,y2)
        Regex("""\(([^)]+)\)\s+rectangle\s+\(([^)]+)\)""").find(body)?.let {
            val a = parseCoordinatePoint(it.groupValues[1], ctx)
            val b = parseCoordinatePoint(it.groupValues[2], ctx)
            return listOf(DrawElement(style, rectStart = a, rectCorner = b))
        }
        // grid —— (x1,y1) grid (x2,y2)：按 step（默认 1）生成垂直+水平网格线
        Regex("""\(([^)]+)\)\s+grid\s+\(([^)]+)\)""").find(body)?.let {
            val a = parseCoordinatePoint(it.groupValues[1], ctx)
            val b = parseCoordinatePoint(it.groupValues[2], ctx)
            val step = Regex("""step\s*=\s*([\d.]+)""").find(styleStr)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            if (step > 0) {
                return listOf(DrawElement(style, segs = gridSegs(a, b, step)))
            }
        }

        // 折线 + path 内 node
        val elements = mutableListOf<Element>()
        val segs = scanPath(body, ctx) { name, at, nodeStyleStr, text ->
            val nodeStyle = parseStyle(nodeStyleStr)
            name?.let { ctx.coordinates[it] = at }
            elements.add(NodeElement(name, at, nodeStyle, nodeStyle.shape, text))
        }
        // 只有 ≥2 段才画（T1：只有 node 无线段时不输出 path）
        if (segs.size >= 2) {
            elements.add(DrawElement(style, segs = segs))
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
     * 顺序扫描路径 body，产出 SVG 路径段序列（M/L/Q/C）。
     * 支持：坐标（绝对/相对 ++/＋/极坐标/命名节点）、`--`、`to[bend]`、`.. controls ..`、`node`。
     * 相对坐标语义：++ 更新当前点；+ 不更新（下一条线从锚点重新出发，用独立子路径）。
     */
    private fun scanPath(
        body: String,
        ctx: ParseContext,
        onNode: (name: String?, at: Point, styleStr: String, text: String) -> Unit,
    ): List<Seg> {
        val segs = mutableListOf<Seg>()
        var cur = Point(0.0, 0.0)
        var lastAbsolute = Point(0.0, 0.0)
        var hasPoint = false
        var subpathStart: Point? = null  // 最近 MoveTo 起点，供 cycle 闭合
        var pendingConnector: String? = null  // "line" / "bend" / "controls"
        var pendingBendAngle = 30.0
        var pendingC1: Point? = null
        var pendingC2: Point? = null
        var relativeUpdate = false  // ++
        var relativeKeep = false    // +
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                ch == '(' -> {
                    val close = body.indexOf(')', i)
                    if (close < 0) { i++; continue }
                    val raw = body.substring(i + 1, close)
                    var p = parseCoordinatePoint(raw, ctx)
                    if (relativeUpdate) {
                        p = Point(cur.x + p.x, cur.y + p.y)
                    } else if (relativeKeep) {
                        p = Point(lastAbsolute.x + p.x, lastAbsolute.y + p.y)
                    }
                    if (!hasPoint) {
                        segs.add(MoveTo(p))
                        hasPoint = true
                        subpathStart = p
                    } else if (relativeKeep) {
                        // + 独立子路径：回到锚点再画线
                        segs.add(MoveTo(lastAbsolute))
                        segs.add(LineTo(p))
                        subpathStart = lastAbsolute
                    } else if (pendingConnector != null) {
                        when (pendingConnector) {
                            "bend" -> segs.add(QuadTo(bendControl(cur, p, pendingBendAngle), p))
                            "controls" -> segs.add(CubicTo(pendingC1 ?: cur, pendingC2 ?: p, p))
                            else -> segs.add(LineTo(p))
                        }
                    } else {
                        // 无连接符的独立坐标 → 新子路径起点（TikZ：空格分隔的坐标另起线段，
                        // 如 (0,0)--(1,0) (3,0)--(4,0) 是两条分离线）
                        segs.add(MoveTo(p))
                        subpathStart = p
                    }
                    if (relativeUpdate) {
                        cur = p
                        lastAbsolute = p
                    } else if (!relativeKeep) {
                        cur = p
                        lastAbsolute = p
                    }
                    // relativeKeep 时 cur/lastAbsolute 不变
                    pendingConnector = null
                    pendingC1 = null
                    pendingC2 = null
                    relativeUpdate = false
                    relativeKeep = false
                    i = close + 1
                }
                body.startsWith("++", i) -> { relativeUpdate = true; i += 2 }
                body.startsWith("+", i) -> { relativeKeep = true; i += 1 }
                body.startsWith("--", i) -> { pendingConnector = "line"; i += 2 }
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
                body.startsWith("to", i) -> {
                    i += 2
                    while (i < body.length && body[i].isWhitespace()) i++
                    pendingConnector = "line"  // 无 bend 的 to 按直线
                    if (i < body.length && body[i] == '[') {
                        val close = body.indexOf(']', i)
                        if (close >= 0) {
                            val opts = body.substring(i + 1, close)
                            if (opts.contains("bend")) {
                                pendingConnector = "bend"
                                var angle = 30.0
                                Regex("""=([\d.]+)""").find(opts)?.let {
                                    angle = it.groupValues[1].toDoubleOrNull() ?: 30.0
                                }
                                pendingBendAngle = if (opts.contains("right")) -angle else angle
                            }
                            i = close + 1
                        }
                    }
                }
                body.startsWith("..", i) -> {
                    i += 2
                    while (i < body.length && body[i].isWhitespace()) i++
                    if (body.startsWith("controls", i)) {
                        i += 8
                        while (i < body.length && body[i].isWhitespace()) i++
                        if (i < body.length && body[i] == '(') {
                            val close = body.indexOf(')', i)
                            if (close >= 0) {
                                pendingC1 = parseCoordinatePoint(body.substring(i + 1, close), ctx)
                                i = close + 1
                            }
                        }
                        while (i < body.length && body[i].isWhitespace()) i++
                        if (body.startsWith("and", i)) {
                            i += 3
                            while (i < body.length && body[i].isWhitespace()) i++
                            if (i < body.length && body[i] == '(') {
                                val close = body.indexOf(')', i)
                                if (close >= 0) {
                                    pendingC2 = parseCoordinatePoint(body.substring(i + 1, close), ctx)
                                    i = close + 1
                                }
                            }
                        }
                        while (i < body.length && body[i].isWhitespace()) i++
                        if (body.startsWith("..", i)) i += 2
                        pendingConnector = "controls"
                    } else {
                        pendingConnector = "line"  // .. (p) .. 简单形式按直线
                    }
                }
                body.startsWith("arc", i) -> {
                    i += 3
                    while (i < body.length && body[i].isWhitespace()) i++
                    var startAngle = 0.0
                    var endAngle = 0.0
                    var radius = 0.0
                    if (i < body.length && body[i] == '(') {
                        // arc (start:end:radius)
                        val close = body.indexOf(')', i)
                        if (close >= 0) {
                            val parts = body.substring(i + 1, close).split(':')
                            if (parts.size == 3) {
                                startAngle = parts[0].trim().toDoubleOrNull() ?: 0.0
                                endAngle = parts[1].trim().toDoubleOrNull() ?: 0.0
                                radius = parts[2].trim().toDoubleOrNull() ?: 0.0
                            }
                            i = close + 1
                        }
                    } else if (i < body.length && body[i] == '[') {
                        // arc[start angle=.., end angle=.., radius=..]
                        val close = body.indexOf(']', i)
                        if (close >= 0) {
                            val opts = body.substring(i + 1, close)
                            Regex("""start\s*angle\s*=\s*([-\d.]+)""").find(opts)?.let {
                                startAngle = it.groupValues[1].toDoubleOrNull() ?: 0.0
                            }
                            Regex("""end\s*angle\s*=\s*([-\d.]+)""").find(opts)?.let {
                                endAngle = it.groupValues[1].toDoubleOrNull() ?: 0.0
                            }
                            Regex("""radius\s*=\s*([-\d.]+)""").find(opts)?.let {
                                radius = it.groupValues[1].toDoubleOrNull() ?: 0.0
                            }
                            i = close + 1
                        }
                    }
                    if (radius > 0) {
                        // TikZ 逆时针（y 上）；弧长 = (end-start) 归一化；sweep=0（y 翻转后方向不变）
                        var d = (endAngle - startAngle) % 360.0
                        if (d <= 0) d += 360.0
                        val p = arcEndPoint(cur, startAngle, endAngle, radius)
                        segs.add(ArcTo(p, radius * SCALE, d > 180.0, sweep = false))
                        cur = p
                        lastAbsolute = p
                    }
                    pendingConnector = null
                    pendingC1 = null
                    pendingC2 = null
                    relativeUpdate = false
                    relativeKeep = false
                }
                body.startsWith("plot", i) -> {
                    i += 4
                    while (i < body.length && body[i].isWhitespace()) i++
                    var domainStart = -5.0
                    var domainEnd = 5.0
                    var samples = 100
                    if (i < body.length && body[i] == '[') {
                        val close = body.indexOf(']', i)
                        if (close >= 0) {
                            val opts = body.substring(i + 1, close)
                            Regex("""domain\s*=\s*([-\d.]+)\s*:\s*([-\d.]+)""").find(opts)?.let {
                                domainStart = it.groupValues[1].toDoubleOrNull() ?: -5.0
                                domainEnd = it.groupValues[2].toDoubleOrNull() ?: 5.0
                            }
                            Regex("""samples\s*=\s*(\d+)""").find(opts)?.let {
                                samples = it.groupValues[1].toIntOrNull() ?: 100
                            }
                            i = close + 1
                        }
                    }
                    while (i < body.length && body[i].isWhitespace()) i++
                    if (i < body.length && body[i] == '(') {
                        // 平衡括号找坐标对结尾：表达式 {..} 内可能有函数括号 cos(...)，不能用 indexOf(')')
                        var close = -1
                        var depth = 0
                        for (j in i until body.length) {
                            when (body[j]) {
                                '(' -> depth++
                                ')' -> {
                                    depth--
                                    if (depth == 0) { close = j; break }
                                }
                            }
                        }
                        if (close >= 0) {
                            val raw = body.substring(i + 1, close)
                            val comma = raw.indexOf(',')
                            if (comma > 0) {
                                val first = raw.substring(0, comma).trim()
                                val second = raw.substring(comma + 1).trim()
                                // (\x, {expr})：\x 是采样参数；({expr1}, {expr2})：两轴都用参数求值
                                val xPart = if (first == "\\x") "x" else first
                                val yPart = if (first == "\\x") second else second
                                var open = false
                                var lastP: Point? = null
                                for (k in 0..samples) {
                                    val t = domainStart + (domainEnd - domainStart) * k / samples
                                    val px = plotCoord(xPart, t) ?: run { open = false; continue }
                                    val py = plotCoord(yPart, t) ?: run { open = false; continue }
                                    val p = Point(px, py)
                                    lastP = p
                                    if (!open) { segs.add(MoveTo(p)); open = true } else segs.add(LineTo(p))
                                }
                                lastP?.let { cur = it; lastAbsolute = it }
                            }
                            i = close + 1
                        }
                    }
                    pendingConnector = null
                    pendingC1 = null
                    pendingC2 = null
                    relativeUpdate = false
                    relativeKeep = false
                }
                body.startsWith("cycle", i) -> {
                    // 闭合当前子路径：连回最近 MoveTo 起点
                    i += 5
                    val start = subpathStart ?: cur
                    segs.add(ClosePath(start))
                    cur = start
                    lastAbsolute = start
                    pendingConnector = null
                    pendingC1 = null
                    pendingC2 = null
                    relativeUpdate = false
                    relativeKeep = false
                }
                else -> i++
            }
        }
        return segs
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
        // 命名节点 (A) 或锚点 (A.north) —— 未定义坐标容错：原点兜底不崩溃
        // （TikZ 语义上引用未定义坐标是用户错误，但渲染器不应整体失败显示源码）
        val anchor = s.split('.', limit = 2)
        val base = ctx.coordinates[anchor[0]] ?: Point(0.0, 0.0)
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
                token.startsWith("draw=") -> style = style.copy(
                    draw = true,
                    stroke = resolveColor(token.substringAfter("=")) ?: style.stroke,
                )
                token == "fill" -> style = style.copy(fill = "black")
                token.startsWith("fill=") -> style = style.copy(fill = resolveColor(token.substringAfter("=")))
                token == "dashed" -> style = style.copy(dashed = true)
                token == "dotted" -> style = style.copy(dashed = true)
                token == "circle" -> style = style.copy(shape = NodeShape.CIRCLE)
                token == "ellipse" -> style = style.copy(shape = NodeShape.ELLIPSE)
                token == "->" -> style = style.copy(arrowEnd = ArrowTip.TRIANGLE)
                token == "<-" -> style = style.copy(arrowStart = ArrowTip.TRIANGLE)
                token == "<->" -> style = style.copy(arrowStart = ArrowTip.TRIANGLE, arrowEnd = ArrowTip.TRIANGLE)
                token == "-stealth" -> style = style.copy(arrowEnd = ArrowTip.STEALTH)
                token == "-latex" -> style = style.copy(arrowEnd = ArrowTip.LATEX)
                token == "latex-" -> style = style.copy(arrowStart = ArrowTip.LATEX)
                token == "stealth->" -> style = style.copy(arrowStart = ArrowTip.STEALTH, arrowEnd = ArrowTip.TRIANGLE)
                token == "thick" -> style = style.copy(strokeWidth = 2.0)
                token == "very thick" -> style = style.copy(strokeWidth = 3.0)
                token == "ultra thick" -> style = style.copy(strokeWidth = 4.0)
                token == "thin" -> style = style.copy(strokeWidth = 0.7)
                token == "rectangle" -> style = style.copy(shape = NodeShape.RECTANGLE)
                token == "diamond" -> style = style.copy(shape = NodeShape.DIAMOND)
                token == "star" -> style = style.copy(shape = NodeShape.STAR)
                token == "rounded corners" -> style = style.copy(rounded = true)
                token.startsWith("rounded corners=") -> {
                    // 显式半径（pt 按 1.333 转 px；无单位直接当 px）
                    val raw = token.substringAfter("=").trim()
                    val r = if (raw.endsWith("pt")) {
                        (raw.removeSuffix("pt").toDoubleOrNull() ?: 5.0) * 1.333
                    } else {
                        raw.removeSuffix("cm").removeSuffix("mm").toDoubleOrNull() ?: 5.0
                    }
                    style = style.copy(rounded = true, roundedRadius = r, roundedRadiusExplicit = true)
                }
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
                    val (halfW, halfH) = when (el.shape) {
                        NodeShape.CIRCLE, NodeShape.DIAMOND, NodeShape.STAR -> NODE_CIRCLE_R to NODE_CIRCLE_R
                        else -> NODE_HALF_W to NODE_HALF_H
                    }
                    allPoints.add(Point(el.at.x - halfW, el.at.y - halfH))
                    allPoints.add(Point(el.at.x + halfW, el.at.y + halfH))
                }
                is CoordinateElement -> allPoints.add(el.at)
                is DrawElement -> {
                    el.segs.forEach { seg ->
                        when (seg) {
                            is MoveTo -> allPoints.add(seg.p)
                            is LineTo -> allPoints.add(seg.p)
                            is QuadTo -> { allPoints.add(seg.c); allPoints.add(seg.p) }
                            is CubicTo -> { allPoints.add(seg.c1); allPoints.add(seg.c2); allPoints.add(seg.p) }
                            is ArcTo -> allPoints.add(seg.p)
                            is ClosePath -> Unit
                        }
                    }
                    el.circleCenter?.let { c ->
                        el.circleRadius?.let { r ->
                            allPoints.add(Point(c.x - r, c.y - r))
                            allPoints.add(Point(c.x + r, c.y + r))
                        }
                    }
                    el.rectStart?.let { allPoints.add(it) }
                    el.rectCorner?.let { allPoints.add(it) }
                }
            }
        }
        val minX = allPoints.minOf { it.x }
        val maxX = allPoints.maxOf { it.x }
        val minY = allPoints.minOf { it.y }
        val maxY = allPoints.maxOf { it.y }
        val width = ((maxX - minX) + 2 * PADDING) * SCALE
        val height = ((maxY - minY) + 2 * PADDING) * SCALE

        // 尺寸上限校验：真机曾出现某个坐标被解析成巨大值（如 plot 表达式 10^12），
        // 导致 SVG height≈4.58e11（12 万公里画布），AndroidSVG/Compose 布局溢出 Int.MAX 崩溃。
        // 生成端直接拒绝，避免巨型 SVG 进入渲染/导出链路。
        if (!width.isFinite() || !height.isFinite() || width > MAX_SVG_PIXELS || height > MAX_SVG_PIXELS) {
            throw IllegalArgumentException("TikZ 图表尺寸异常 (${fmt(width)} x ${fmt(height)})，已拒绝渲染")
        }

        // TikZ → SVG 坐标转换（y 翻转）
        fun sx(x: Double): Double = (x - minX + PADDING) * SCALE
        fun sy(y: Double): Double = (maxY - y + PADDING) * SCALE

        val sb = StringBuilder()
        // 显式 width/height（= viewBox 尺寸）：部分 AndroidSVG 版本只有 viewBox 时 documentWidth/Height 为 0，
        // renderToPicture 的 Picture 宽度 0 会导致下游 scale 除零。显式尺寸规避。
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(width)}" height="${fmt(height)}" viewBox="0 0 ${fmt(width)} ${fmt(height)}">""")
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
        // 数值一律走 fmt（消除 .0 尾零）：AndroidSVG 1.4 对 "X.0" 数字格式有兼容性怪癖，
        // d 属性坐标与 stroke-width 必须同一套数字格式，避免埋雷。
        // draw=false（如 \fill 纯填充）时不输出 stroke 属性
        val stroke = if (style.draw) "stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.strokeWidth)}\"" else ""
        val dash = if (style.dashed) " stroke-dasharray=\"5,5\"" else ""
        val fill = style.fill?.let { " fill=\"$it\"" } ?: " fill=\"none\""
        // 圆角半径：显式值（rounded corners=NNpt）优先；裸 rounded corners 按图形对角线
        // 3% 且至少 2.5 倍线宽——保证高密度屏与任意图形尺寸下圆角可见（风格意图，非固定像素）
        val rPx = if (!style.rounded) 0.0
                  else if (style.roundedRadiusExplicit) style.roundedRadius
                  else maxOf(figureDiagonalPx(el, sx, sy) * 0.03, style.strokeWidth * 2.5)
        val rectRx = if (style.rounded) fmt(rPx) else "2"

        return when {
            el.circleRadius != null -> {
                val c = el.circleCenter!!
                """<circle cx="${fmt(sx(c.x))}" cy="${fmt(sy(c.y))}" r="${fmt(el.circleRadius * SCALE)}" $stroke$fill$dash/>"""
            }
            el.rectCorner != null -> {
                val a = el.rectStart!!
                val b = el.rectCorner!!
                val x = minOf(a.x, b.x); val yTop = maxOf(a.y, b.y)
                val w = kotlin.math.abs(b.x - a.x); val h = kotlin.math.abs(b.y - a.y)
                """<rect x="${fmt(sx(x))}" y="${fmt(sy(yTop))}" width="${fmt(w * SCALE)}" height="${fmt(h * SCALE)}" rx="$rectRx" $stroke$fill$dash/>"""
            }
            else -> {
                val d = if (style.rounded) renderRoundedD(el.segs, rPx, sx, sy)
                        else renderPlainD(el.segs, sx, sy)
                val sb = StringBuilder()
                sb.append("""<path d="$d" $stroke$fill$dash/>""")
                // 箭头：AndroidSVG 不支持 <marker>，手动算几何直接画 polygon（尖在端点，往回推 L，垂直扩 W）
                val endArrow = segmentArrowEnd(el.segs)
                if (endArrow != null && style.arrowEnd != null) {
                    sb.append('\n').append(
                        arrowHeadSvg(endArrow.first, endArrow.second, style.arrowEnd, style.stroke, style.strokeWidth, sx, sy)
                    )
                }
                val startArrow = segmentArrowStart(el.segs)
                if (startArrow != null && style.arrowStart != null) {
                    sb.append('\n').append(
                        arrowHeadSvg(startArrow.first, startArrow.second, style.arrowStart, style.stroke, style.strokeWidth, sx, sy)
                    )
                }
                sb.toString()
            }
        }
    }

    /** 提取路径所有点（MoveTo 起点 + 各段终点），用于箭头方向与边界；ClosePath 忽略（点=起点已含） */
    private fun pathPoints(segs: List<Seg>): List<Point> = segs.mapNotNull {
        when (it) {
            is MoveTo -> it.p
            is LineTo -> it.p
            is QuadTo -> it.p
            is CubicTo -> it.p
            is ArcTo -> it.p
            is ClosePath -> null
        }
    }

    /** 图形包围盒对角线（px，经 sx/sy 转换）：用于裸 rounded corners 的按比例半径 */
    private fun figureDiagonalPx(el: DrawElement, sx: (Double) -> Double, sy: (Double) -> Double): Double {
        val pts = mutableListOf<Point>()
        el.segs.forEach { seg ->
            when (seg) {
                is MoveTo -> pts.add(seg.p)
                is LineTo -> pts.add(seg.p)
                is QuadTo -> { pts.add(seg.c); pts.add(seg.p) }
                is CubicTo -> { pts.add(seg.c1); pts.add(seg.c2); pts.add(seg.p) }
                is ArcTo -> pts.add(seg.p)
                is ClosePath -> Unit  // 起点已在 MoveTo
            }
        }
        el.rectStart?.let { pts.add(it) }
        el.rectCorner?.let { pts.add(it) }
        el.circleCenter?.let { c ->
            el.circleRadius?.let { r ->
                pts.add(Point(c.x - r, c.y - r)); pts.add(Point(c.x + r, c.y + r))
            }
        }
        if (pts.isEmpty()) return 100.0
        val px = pts.map { Point(sx(it.x), sy(it.y)) }
        val xMin = px.minOf { it.x }; val xMax = px.maxOf { it.x }
        val yMin = px.minOf { it.y }; val yMax = px.maxOf { it.y }
        val w = xMax - xMin
        val h = yMax - yMin
        return kotlin.math.sqrt(w * w + h * h)
    }

    /** 普通路径渲染（无圆角）。Q 命令等价转 C（控制点重复）以规避 AndroidSVG 1.4 对 Q 的兼容问题 */
    private fun renderPlainD(segs: List<Seg>, sx: (Double) -> Double, sy: (Double) -> Double): String = buildString {
        segs.forEach { seg ->
            when (seg) {
                is MoveTo -> append("M ").append(fmt(sx(seg.p.x))).append(',').append(fmt(sy(seg.p.y)))
                is LineTo -> append(" L ").append(fmt(sx(seg.p.x))).append(',').append(fmt(sy(seg.p.y)))
                is QuadTo -> append(" C ").append(fmt(sx(seg.c.x))).append(',').append(fmt(sy(seg.c.y)))
                    .append(' ').append(fmt(sx(seg.c.x))).append(',').append(fmt(sy(seg.c.y)))
                    .append(' ').append(fmt(sx(seg.p.x))).append(',').append(fmt(sy(seg.p.y)))
                is CubicTo -> append(" C ").append(fmt(sx(seg.c1.x))).append(',').append(fmt(sy(seg.c1.y)))
                    .append(' ').append(fmt(sx(seg.c2.x))).append(',').append(fmt(sy(seg.c2.y)))
                    .append(' ').append(fmt(sx(seg.p.x))).append(',').append(fmt(sy(seg.p.y)))
                is ArcTo -> append(" A ").append(fmt(seg.radius)).append(' ').append(fmt(seg.radius))
                    .append(" 0 ").append(if (seg.largeArc) "1" else "0")
                    .append(' ').append(if (seg.sweep) "1" else "0")
                    .append(' ').append(fmt(sx(seg.p.x))).append(',').append(fmt(sy(seg.p.y)))
                is ClosePath -> append(" Z")
            }
        }
    }

    /**
     * 折线几何圆角（rounded corners）：每个内角处，沿两条边各缩进 r，
     * 用二次贝塞尔连接（控制点 = 角点本身），实现真正倒角（非 stroke-linejoin 近似）。
     * 先在 SVG px 坐标系（sx/sy 转换）做几何，r 直接是 px；控制点永远用 p1（左右转通用）；
     * 边短于 2r 时收缩 r = min(r, 边长/2)；零长度段跳过；含曲线段时退回普通渲染。
     */
    private fun renderRoundedD(segs: List<Seg>, rPx: Double, sx: (Double) -> Double, sy: (Double) -> Double): String {
        // 只允许"开头一个 MoveTo + 其余全 LineTo（可含末尾一个 ClosePath）"的单一折线子路径；
        // 若出现两次 MoveTo（分离的多子路径，如 \draw (0,0)--(1,0) (3,0)--(4,0)）直接退回普通渲染，
        // 避免把两条子路径用圆角错误连接。
        val closed = segs.lastOrNull() is ClosePath
        val bodySegs = if (closed) segs.dropLast(1) else segs
        val purePolyline = bodySegs.size >= 2 &&
            bodySegs.first() is MoveTo &&
            bodySegs.drop(1).all { it is LineTo }
        val rawPts = pathPoints(bodySegs)
        if (!purePolyline || rawPts.size < 3) {
            return renderPlainD(segs, sx, sy)
        }
        // 转换到 SVG px 坐标（含 y 翻转），几何在 px 坐标系计算
        val pts = rawPts.map { Point(sx(it.x), sy(it.y)) }
        return buildString {
            if (closed && pts.size >= 3) {
                // 闭合环形倒角：每个角由前一点 (i-1)%n 与下一点 (i+1)%n 决定，含起点角；
                // 输出从角 0 进入点开始，逐角倒角，最后连回角 0 进入点并 Z 闭合。
                val n = pts.size
                data class Corner(
                    val valid: Boolean, val px: Double, val py: Double,
                    val ax: Double, val ay: Double, val bx: Double, val by: Double,
                )
                val corners = (0 until n).map { i ->
                    val p0 = pts[(i - 1 + n) % n]
                    val p1 = pts[i]
                    val p2 = pts[(i + 1) % n]
                    val len1 = kotlin.math.sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y))
                    val len2 = kotlin.math.sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y))
                    if (len1 < 1e-9 || len2 < 1e-9) {
                        Corner(false, p1.x, p1.y, p1.x, p1.y, p1.x, p1.y)
                    } else {
                        val rr = minOf(rPx, minOf(len1, len2) / 2)
                        val ux = (p1.x - p0.x) / len1; val uy = (p1.y - p0.y) / len1
                        val vx = (p2.x - p1.x) / len2; val vy = (p2.y - p1.y) / len2
                        Corner(
                            true, p1.x, p1.y,
                            p1.x - ux * rr, p1.y - uy * rr,
                            p1.x + vx * rr, p1.y + vy * rr,
                        )
                    }
                }
                val start = corners[0]
                append("M ").append(fmt(if (start.valid) start.ax else start.px))
                    .append(',').append(fmt(if (start.valid) start.ay else start.py))
                for (i in 0 until n) {
                    val c = corners[i]
                    if (c.valid) {
                        // Q p1 b 等价转 C（控制点重复），规避 AndroidSVG 1.4 对 Q 的兼容问题
                        append(" C ").append(fmt(c.px)).append(',').append(fmt(c.py))
                            .append(' ').append(fmt(c.px)).append(',').append(fmt(c.py))
                            .append(' ').append(fmt(c.bx)).append(',').append(fmt(c.by))
                    } else {
                        append(" L ").append(fmt(c.px)).append(',').append(fmt(c.py))
                    }
                    val next = corners[(i + 1) % n]
                    append(" L ").append(fmt(if (next.valid) next.ax else next.px))
                        .append(',').append(fmt(if (next.valid) next.ay else next.py))
                }
                append(" Z")
            } else {
                append("M ").append(fmt(pts[0].x)).append(',').append(fmt(pts[0].y))
                for (i in 1 until pts.size - 1) {
                    val p0 = pts[i - 1]
                    val p1 = pts[i]
                    val p2 = pts[i + 1]
                    val len1 = kotlin.math.sqrt((p1.x - p0.x) * (p1.x - p0.x) + (p1.y - p0.y) * (p1.y - p0.y))
                    val len2 = kotlin.math.sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y))
                    if (len1 < 1e-9 || len2 < 1e-9) {
                        append(" L ").append(fmt(p1.x)).append(',').append(fmt(p1.y))
                        continue
                    }
                    val rr = minOf(rPx, minOf(len1, len2) / 2)
                    val ux = (p1.x - p0.x) / len1; val uy = (p1.y - p0.y) / len1
                    val vx = (p2.x - p1.x) / len2; val vy = (p2.y - p1.y) / len2
                    val ax = p1.x - ux * rr; val ay = p1.y - uy * rr
                    val bx = p1.x + vx * rr; val by = p1.y + vy * rr
                    append(" L ").append(fmt(ax)).append(',').append(fmt(ay))
                    // Q p1 b 等价转 C（控制点重复），规避 AndroidSVG 1.4 对 Q 的兼容问题
                    append(" C ").append(fmt(p1.x)).append(',').append(fmt(p1.y))
                        .append(' ').append(fmt(p1.x)).append(',').append(fmt(p1.y))
                        .append(' ').append(fmt(bx)).append(',').append(fmt(by))
                }
                append(" L ").append(fmt(pts.last().x)).append(',').append(fmt(pts.last().y))
            }
        }
    }

    /** 末段箭头：(尖, 指向线内的参考点)。直线用上一顶点，曲线用切线（Q: ctrl，C: c2）；cycle/arc 末尾无箭头 */
    private fun segmentArrowEnd(segs: List<Seg>): Pair<Point, Point>? {
        val last = segs.lastOrNull() ?: return null
        return when (last) {
            is MoveTo -> null
            is ClosePath -> null
            is ArcTo -> null
            is LineTo -> {
                val prev = secondLastPoint(segs) ?: return null
                last.p to prev
            }
            is QuadTo -> last.p to last.c
            is CubicTo -> last.p to last.c2
        }
    }

    /** 首段箭头：(尖, 指向线内的参考点)。尖为 MoveTo 起点，参考点为第一段切线方向 */
    private fun segmentArrowStart(segs: List<Seg>): Pair<Point, Point>? {
        val first = segs.firstOrNull() as? MoveTo ?: return null
        val second = segs.getOrNull(1) ?: return null
        val inside = when (second) {
            is LineTo -> second.p
            is QuadTo -> second.c
            is CubicTo -> second.c1
            is ArcTo -> second.p
            is MoveTo -> return null
            is ClosePath -> return null
        }
        return first.p to inside
    }

    private fun secondLastPoint(segs: List<Seg>): Point? {
        for (i in segs.size - 2 downTo 0) {
            val s = segs[i]
            return when (s) {
                is MoveTo -> s.p
                is LineTo -> s.p
                is QuadTo -> s.p
                is CubicTo -> s.p
                is ArcTo -> s.p
                is ClosePath -> continue
            }
        }
        return null
    }

    /** 计算 to[bend] 二次贝塞尔控制点（TikZ 坐标系，y 向上）：中点 + 垂直偏移 */
    private fun bendControl(from: Point, to: Point, angle: Double): Point {        val midX = (from.x + to.x) / 2
        val midY = (from.y + to.y) / 2
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-9) return Point(midX, midY)
        // 垂直方向（left = 逆时针）；angle 正 left / 负 right，offset 符号随 angle
        val nx = -dy / len
        val ny = dx / len
        val offset = len * 0.5 * kotlin.math.tan(Math.toRadians(angle / 2))
        return Point(midX + nx * offset, midY + ny * offset)
    }

    /** arc 终点（TikZ 坐标系，y 向上）：圆心 = 起点沿 start 角反向偏移 r，终点 = 圆心沿 end 角 */
    private fun arcEndPoint(startP: Point, startAngle: Double, endAngle: Double, r: Double): Point {
        val rad0 = Math.toRadians(startAngle)
        val rad1 = Math.toRadians(endAngle)
        val cx = startP.x - r * Math.cos(rad0)
        val cy = startP.y - r * Math.sin(rad0)
        return Point(cx + r * Math.cos(rad1), cy + r * Math.sin(rad1))
    }

    /** 生成网格线路径段（TikZ 坐标，y 向上）：按 step 从 (a) 到 (b) 生成垂直+水平线，含端点 */
    private fun gridSegs(a: Point, b: Point, step: Double): List<Seg> {
        val segs = mutableListOf<Seg>()
        val x0 = minOf(a.x, b.x); val x1 = maxOf(a.x, b.x)
        val y0 = minOf(a.y, b.y); val y1 = maxOf(a.y, b.y)
        var x = x0
        while (x <= x1 + 1e-9) {
            segs.add(MoveTo(Point(x, y0)))
            segs.add(LineTo(Point(x, y1)))
            x += step
        }
        var y = y0
        while (y <= y1 + 1e-9) {
            segs.add(MoveTo(Point(x0, y)))
            segs.add(LineTo(Point(x1, y)))
            y += step
        }
        return segs
    }

    /** plot 坐标分量求值：`x` 返回采样参数 t；`{expr}` 用 MathExpr 求值；非法/非有限返回 null（断开采样段） */
    private fun plotCoord(part: String, t: Double): Double? {
        if (part == "x") return t
        val expr = Regex("""\{([^}]*)\}""").find(part)?.groupValues?.get(1) ?: return null
        val v = MathExpr.evaluate(expr.replace("\\x", "x"), t)
        return if (v.isFinite() && kotlin.math.abs(v) <= MAX_PLOT_ABS) v else null
    }

    /**
     * 手动算几何画箭头（TikZ 坐标，尖在 tip，inside 指向线内）。
     * 三种形状：TRIANGLE 实心三角 / STEALTH 凹底 / LATEX 空心。
     */
    private fun arrowHeadSvg(
        tip: Point,
        inside: Point,
        shape: ArrowTip,
        color: String,
        strokeW: Double,
        sx: (Double) -> Double,
        sy: (Double) -> Double,
    ): String {
        val L = 10.0 + strokeW * 1.5  // 箭身长度
        val W = 5.0 + strokeW          // 半宽
        val tx = sx(tip.x); val ty = sy(tip.y)
        val ix = sx(inside.x); val iy = sy(inside.y)
        val dx = tx - ix
        val dy = ty - iy
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-9) return ""
        val ux = dx / len
        val uy = dy / len
        val nx = -uy
        val ny = ux
        val backX = tx + ux * L
        val backY = ty + uy * L
        val backRx = backX + nx * W
        val backRy = backY + ny * W
        val backLx = backX - nx * W
        val backLy = backY - ny * W
        return when (shape) {
            ArrowTip.TRIANGLE ->
                """<polygon points="${fmt(tx)},${fmt(ty)} ${fmt(backRx)},${fmt(backRy)} ${fmt(backLx)},${fmt(backLy)}" fill="$color"/>"""
            ArrowTip.STEALTH -> {
                val notchX = tx + ux * L * 0.7
                val notchY = ty + uy * L * 0.7
                """<polygon points="${fmt(tx)},${fmt(ty)} ${fmt(backRx)},${fmt(backRy)} ${fmt(notchX)},${fmt(notchY)} ${fmt(backLx)},${fmt(backLy)}" fill="$color"/>"""
            }
            ArrowTip.LATEX ->
                """<polygon points="${fmt(tx)},${fmt(ty)} ${fmt(backRx)},${fmt(backRy)} ${fmt(backLx)},${fmt(backLy)}" fill="none" stroke="$color" stroke-width="${fmt(strokeW)}"/>"""
        }
    }

    private fun renderNode(el: NodeElement, sx: (Double) -> Double, sy: (Double) -> Double): String {
        val style = el.style
        val cx = sx(el.at.x)
        val cy = sy(el.at.y)
        val stroke = if (style.draw) "stroke=\"${style.stroke}\" stroke-width=\"${fmt(style.strokeWidth)}\"" else ""
        val fill = style.fill?.let { " fill=\"$it\"" } ?: " fill=\"none\""
        val textColor = " fill=\"${style.stroke}\""
        val shapeSvg = when (el.shape) {
            NodeShape.CIRCLE -> """<circle cx="${fmt(cx)}" cy="${fmt(cy)}" r="${fmt(NODE_CIRCLE_R * SCALE)}" $stroke$fill/>"""
            NodeShape.ELLIPSE -> """<ellipse cx="${fmt(cx)}" cy="${fmt(cy)}" rx="${fmt(NODE_HALF_W * SCALE)}" ry="${fmt(NODE_HALF_H * SCALE)}" $stroke$fill/>"""
            NodeShape.RECTANGLE -> """<rect x="${fmt(cx - NODE_HALF_W * SCALE)}" y="${fmt(cy - NODE_HALF_H * SCALE)}" width="${fmt(2 * NODE_HALF_W * SCALE)}" height="${fmt(2 * NODE_HALF_H * SCALE)}" rx="2" $stroke$fill/>"""
            NodeShape.DIAMOND -> {
                val w = NODE_HALF_W * SCALE
                val h = NODE_HALF_H * SCALE
                """<polygon points="${fmt(cx)},${fmt(cy - h)} ${fmt(cx + w)},${fmt(cy)} ${fmt(cx)},${fmt(cy + h)} ${fmt(cx - w)},${fmt(cy)}" $stroke$fill/>"""
            }
            NodeShape.STAR -> {
                // 五角星：外半径 R，内半径 0.5R，10 个点交替
                val r = NODE_CIRCLE_R * SCALE * 1.3
                val pts = (0 until 10).joinToString(" ") { idx ->
                    val rr = if (idx % 2 == 0) r else r * 0.5
                    val a = Math.toRadians(-90.0 + idx * 36.0)
                    "${fmt(cx + rr * Math.cos(a))},${fmt(cy + rr * Math.sin(a))}"
                }
                """<polygon points="$pts" $stroke$fill/>"""
            }
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
