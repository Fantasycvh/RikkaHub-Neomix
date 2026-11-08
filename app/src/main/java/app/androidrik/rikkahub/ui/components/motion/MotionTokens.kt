package app.androidrik.rikkahub.ui.components.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 中央动效令牌（借鉴 BiliPai `AppMotionTokens.kt` 形状——object + 命名时长/缓动/弹簧，
 * 台账 `docs/references/other projects/bilipai.md` §2026-11-08 增量候选 1；本仓无 MIUIX
 * 双风格分支，不抄 resolveXxxSpec）。
 *
 * 收口原则：**令牌值 = 既有散点的 1:1 映射**（150/200/300/450/600/1000/120 都是仓内
 * 已有实测值），收口不改任何现有观感——先有单源，将来调手感只动这里。
 *
 * 使用约定：
 * - 新代码一律引用令牌，禁止字面量 `tween(300)` 这类散写（audit 时 grep `tween\(\d` 应零命中）；
 * - 环境型慢循环（Shimmer/DotLoading/旋转）时长由组件参数化或本组件常量自持，不强收；
 * - 默认缓动仍是 [FastOutSlowInEasing]（保持既有观感）；[EmphasizedEasing] 供新交互动效
 *   选用（M3 emphasized 曲线），不做全局替换。
 */
object MotionTokens {
    /** 交互即时反馈的下限（ChainOfThought 折叠刻意最短——长尺寸动画+反复测量是卡顿主因） */
    const val SNAP_MS = 120

    /** 微交互（开关滑块） */
    const val FAST_MS = 150

    /** 淡入淡出、小旋钮旋转 */
    const val QUICK_MS = 200

    /** 常规状态切换（颜色/容器过渡） */
    const val STANDARD_MS = 300

    /** 数据可视化推进（上下文用量环） */
    const val EMPHASIZED_MS = 450

    /** 呼吸脉冲单程 */
    const val PULSE_MS = 600

    /** 环境型慢循环（翻译呼吸灯） */
    const val AMBIENT_MS = 1000

    /** 全仓默认缓动（保持既有观感；不引入即此值） */
    val DefaultEasing: Easing = FastOutSlowInEasing

    /** 线性（环境型循环/进度类专用） */
    val LinearEasingToken: Easing = LinearEasing

    /** M3 emphasized 进出场曲线（新交互动效可选用，不全局替换） */
    val EmphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * 命名弹簧预设（判据借自 BiliPai AppMotionTokens 的 spring 族；新代码选用，不迁移既有
 * spring 调用——弹簧手感是逐处调出来的，批量换会改观感）。
 */
object MotionSprings {
    /** 大位移落座：低刚度 + 中弹性，物体「落到」位而不是「弹到」位 */
    fun <T> softLanding(): androidx.compose.animation.core.SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** 交互跟手：无弹跳 + 中刚度，指针松手即落定 */
    fun <T> interactiveSnap(): androidx.compose.animation.core.SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
