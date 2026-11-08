package app.androidrik.rikkahub.ui.components.ai

// 批次 7 拆出：ContextUsage data class + ContextUsageRingButton 自 ChatInput.kt 拆出（同包，private→internal）

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androidrik.rikkahub.utils.CONTEXT_USAGE_WARNING_RATIO
import kotlin.math.roundToInt
import app.androidrik.rikkahub.ui.components.motion.MotionTokens

/** 会话上下文占用数据：由 ChatPage 用 [app.androidrik.rikkahub.utils.estimateWindowTokens] 组装传入。 */
data class ContextUsage(
    val usedTokens: Int,
    val windowTokens: Int,
)

/**
 * 已使用上下文占用：**圆环 + 环内整数百分比**；点击打开上下文容量面板。
 * 达到窗口 80%（CONTEXT_USAGE_WARNING_RATIO）变红警告——阈值与面板、标题旁读数共用同一常量。
 *
 * 只保留圆环形态（原「细条 + 百分比」已移除，见 `docs/roadmap.md` 2026-10-21 条目）；
 * 精确数值在标题旁读数与面板里，圆环内只给整数。
 */
@Composable
internal fun ContextUsageRingButton(
    usedTokens: Int,
    windowTokens: Int,
    onClick: () -> Unit,
) {
    val fraction = if (windowTokens > 0) {
        (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isWarning = windowTokens > 0 && fraction >= CONTEXT_USAGE_WARNING_RATIO
    val barColor = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val textColor = if (isWarning) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val percent = (fraction * 100f).roundToInt()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(MotionTokens.EMPHASIZED_MS),
        label = "contextUsageFraction",
    )

    // 外层容器高度与相邻图标按钮（ToggleSurface 的 40dp，含内边距）**一致**：
    // 整行做紧凑化后，邻居统一收到 40dp（Search/Reasoning/Memory 均为 8dp 内边距 + 24dp 图标）；
    // 行内对齐方式是 CenterVertically（见图标行注释）。
    Box(
        modifier = Modifier.height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(percent = 50))
                .clickable { onClick() }
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 圆环：百分比放在环内（空间紧，只给整数；精确数值在标题旁读数与面板里）
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2f
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (animatedFraction > 0f) {
                        drawArc(
                            color = barColor,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedFraction,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Text(
                    text = percent.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = textColor,
                    maxLines = 1,
                )
            }
        }
    }

}
