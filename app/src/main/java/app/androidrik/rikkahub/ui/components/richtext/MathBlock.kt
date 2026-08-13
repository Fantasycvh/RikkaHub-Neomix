package app.androidrik.rikkahub.ui.components.richtext

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import app.androidrik.rikkahub.ui.context.LocalSettings

/** 判定公式块是否是需要 TikZ 渲染的图表代码（tikzcd/tikzpicture/pgfplots/xymatrix/CD） */
private val DIAGRAM_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),  // tikzpicture / tikzcd / tikztiming...
    Regex("""\\begin\{axis\}"""),     // pgfplots
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private fun isDiagramLatex(latex: String): Boolean =
    DIAGRAM_PATTERNS.any { it.containsMatchIn(latex) }

/** 去掉 Markdown 块公式的 $$ / \[...\] 外层包裹（对齐 LatexText.processLatex 行为） */
private fun stripDollarWrappers(latex: String): String {
    val t = latex.trim()
    return when {
        t.startsWith("$$") && t.endsWith("$$") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 -> t.substring(2, t.length - 2).trim()
        else -> t
    }
}

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    val proceededLatex = latex
    LatexText(
        latex = proceededLatex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    // 图表公式块（$$...$$ 内嵌 \begin{tikzpicture}/\begin{tikzcd} 等）→ 走纯 Java TikZ 渲染矢量图；
    // 其余数学公式 → 走 jlatexmath
    val settings = LocalSettings.current.displaySetting
    val proceededLatex = latex
    if (settings.enableDiagramRendering && isDiagramLatex(proceededLatex)) {
        TikzJax(
            code = stripDollarWrappers(proceededLatex),
            modifier = modifier,
        )
        return
    }
    Box(
        modifier = modifier.padding(8.dp)
    ) {
        LatexText(
            latex = proceededLatex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(
                    rememberScrollState()
                ),
        )
    }
}
