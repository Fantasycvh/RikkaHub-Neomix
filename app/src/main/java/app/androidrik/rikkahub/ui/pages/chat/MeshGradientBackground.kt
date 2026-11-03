package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.androidrik.rikkahub.ui.components.motion.rememberSystemReduceMotion
import app.androidrik.rikkahub.ui.components.motion.rememberVisualWorkActive
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 限速时间源的帧间隔：33ms ≈ 30fps（缓慢漂移的视觉与 60/120fps 无区别）。 */
private const val FRAME_INTERVAL_MS = 33L

/**
 * 纯函数：`elapsedMillis` 时刻某光斑的相位。
 *
 * 与原 `infiniteRepeatable(tween(durationMillis * loops, LinearEasing))` 数学等价：
 * 原动画在 `durationMillis * loops` 内从 0 走到 `2π * loops` 后重置，故相位 = `2π * t / durationMillis`。
 */
internal fun phaseAtElapsed(elapsedMillis: Long, durationMillis: Int, loops: Int): Float {
    val period = durationMillis.toLong() * loops
    if (period <= 0L) return 0f
    val t = elapsedMillis % period
    return (2.0 * PI * loops * t / period).toFloat()
}

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部偏白)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 每个光斑用一个独立周期的无限动画,沿正弦/余弦轨迹缓慢漂移。
 *
 * 不依赖 Modifier.blur,全 API 级别可用,性能也更好。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val reduceMotion = rememberSystemReduceMotion()
    val visualWorkActive = rememberVisualWorkActive()
    val elapsedMillis = remember { mutableLongStateOf(0L) }

    // 30fps 限速时间源：原实现用 `rememberInfiniteTransition` 四条 `animateFloat`，每个 vsync
    // 都重组重算相位（120Hz 屏空闲即 120fps 计算 + 重组）。改为自驱累计时间、相位只在 Canvas
    // 绘制 lambda 里读取——状态更新只触发**重绘**、不触发重组；后台 / 失焦时协程取消，
    // 相位原地冻结，回前台从冻结处续跑（不跳变）。
    // 另：自驱循环不再自动遵循系统「移除动画」，由 [rememberSystemReduceMotion] 显式冻结。
    LaunchedEffect(visualWorkActive, reduceMotion) {
        if (reduceMotion || !visualWorkActive) return@LaunchedEffect
        while (isActive) {
            delay(FRAME_INTERVAL_MS)
            elapsedMillis.longValue += FRAME_INTERVAL_MS
        }
    }

    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        // 暗色:顶部深蓝,向下渐隐到接近纯黑
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        // 亮色:顶部偏蓝,向下渐隐到白
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    // 光斑配色(蓝 / 青 / 淡蓝 / 暖色)及浓度,亮暗各一套
    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val blobWarm = if (dark) Color(0xFF7C5F9E) else Color(0xFFFFC8D2)
    val alphaBlue = if (dark) 0.56f else 0.72f
    val alphaTeal = if (dark) 0.44f else 0.56f
    val alphaLightBlue = if (dark) 0.48f else 0.62f
    val alphaWarm = if (dark) 0.32f else 0.42f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 相位在绘制期从累计时间算：只触发重绘，不触发重组
            val elapsed = elapsedMillis.longValue
            val p1 = phaseAtElapsed(elapsed, 5_500, 20) // 1.15 对应 20 圈 (总长 110秒)
            val p2 = phaseAtElapsed(elapsed, 7_000, 1)  // 1.0  对应 1 圈
            val p3 = phaseAtElapsed(elapsed, 8_500, 10) // 0.9  对应 10 圈 (总长 85秒)
            val p4 = phaseAtElapsed(elapsed, 6_200, 10) // 1.1  对应 10 圈 (总长 62秒)
            val w = size.width
            val h = size.height
            // 光斑半径取屏幕较长边的比例,够大才柔
            val r = maxOf(w, h)

            // 光斑全部聚在顶部,向下渐隐,保留下半屏留白
            // 顶部蓝(主色,横向缓慢漂移)
            drawBlob(
                center = Offset(
                    w * 0.48f + sin(p1) * w * 0.38f,
                    h * 0.08f + cos(p1 * 1.15f) * h * 0.18f,
                ),
                radius = r * 0.36f,
                color = blobBlue,
                centerAlpha = alphaBlue,
            )
            // 左上青绿点缀
            drawBlob(
                center = Offset(
                    w * 0.18f + sin(p2 + PI.toFloat() * 0.55f) * w * 0.30f,
                    h * 0.24f + cos(p2) * h * 0.20f,
                ),
                radius = r * 0.28f,
                color = blobTeal,
                centerAlpha = alphaTeal,
            )
            // 右上淡蓝
            drawBlob(
                center = Offset(
                    w * 0.82f + sin(p3 + PI.toFloat() * 0.9f) * w * -0.34f,
                    h * 0.12f + cos(p3 * 0.9f) * h * 0.18f,
                ),
                radius = r * 0.30f,
                color = blobLightBlue,
                centerAlpha = alphaLightBlue,
            )
            // 暖色光斑给运动提供更明显的色彩参照
            drawBlob(
                center = Offset(
                    w * 0.58f + sin(p4 + PI.toFloat() * 1.25f) * w * 0.28f,
                    h * 0.34f + cos(p4 * 1.1f) * h * 0.16f,
                ),
                radius = r * 0.26f,
                color = blobWarm,
                centerAlpha = alphaWarm,
            )
        }

        content()
    }
}

/** 画一个柔光斑:中心有色,向外渐隐到透明。 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float = 0.75f,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}
