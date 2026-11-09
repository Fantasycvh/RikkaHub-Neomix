package app.androidrik.rikkahub.ui.components.richtext

import app.androidrik.rikkahub.ui.components.ui.appSkinTextSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import me.rerere.bettertext.highlight.HighlightTextColorPalette
import me.rerere.bettertext.highlight.rememberHighlightedCode
import me.rerere.bettertext.text.BetterText

/**
 * 代码高亮文本（BetterText 版）。
 *
 * 语法高亮来自 BetterText 的 highlight.js Kotlin 移植；排版/选择来自 BetterText。
 * 保留旧 `CodeHighlightText` 的常用参数，便于替换调用点；[overflow] 仅
 * 在 [maxLines] 截断时生效（BetterText 用省略号）。
 */
@Composable
fun CodeHighlightText(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    colors: HighlightTextColorPalette = HighlightTextColorPalette.Default,
    fontSize: TextUnit = appSkinTextSize(12.sp),
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily = FontFamily.Monospace,
    @Suppress("UNUSED_PARAMETER") overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated = rememberHighlightedCode(code, language, colors)
    BetterText(
        text = annotated,
        modifier = modifier,
        style = LocalTextStyle.current.merge(
            TextStyle(
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                fontFeatureSettings = CODE_FONT_FEATURES,
            )
        ),
        maxLines = maxLines,
    )
}
