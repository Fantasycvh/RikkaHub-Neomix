package app.androidrik.rikkahub.ui.components.haze

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import java.util.Collections
import java.util.WeakHashMap

/**
 * 液态玻璃/磨砂效果的「可恢复」门控层（设计借鉴 BiliPai `RecoverableVisualEffects`，实现按本仓重写）：
 *
 * 1. **SDK 分级**：blur 后端（RenderEffect）≥S(31)；玻璃后端（RuntimeShader）≥T(33)；
 *    回前台重建 HazeState ≥36。低版本自动退化为无效果而非闪退。
 * 2. **前后台 gate**：进后台关 blur——后台的 RenderEffect 失效是历史闪退源之一，也费电；
 *    回前台只在 ≥36 重建 state（进后台时界面不可见，重建无收益，还会把全屏重录成本
 *    推到回前台后的第一帧）。
 * 3. **成对门控**：`hazeSourceCompat` 在后端不允许的 SDK 上 source 侧也不注册，
 *    与 blur 消费侧的 SDK 判断成对出现。
 */

/** blur 后端依赖 RenderEffect：≥S(31) 可用。 */
internal fun shouldAllowRenderEffectBackedHazeEffect(sdkInt: Int): Boolean {
    return sdkInt >= Build.VERSION_CODES.S
}

/** 玻璃后端依赖 RuntimeShader（AGSL）：≥T(33) 可用。 */
internal fun shouldAllowRuntimeShaderBackedGlassEffect(sdkInt: Int): Boolean {
    return sdkInt >= Build.VERSION_CODES.TIRAMISU
}

/**
 * 只在「回前台」重建 HazeState：进后台时界面不可见，重建没有收益，反而让回前台后的
 * 第一帧额外承担一次全屏重录成本。
 */
internal fun shouldRecreateHazeStateOnForeground(sdkInt: Int): Boolean {
    return sdkInt >= 36
}

internal fun shouldEnableRecoverableBlur(
    userEnabled: Boolean,
    isAppInBackground: Boolean,
): Boolean {
    return userEnabled && !isAppInBackground
}

/**
 * Haze 2 移除了 [HazeState.blurEnabled]，前后台 gate 按 state 记录在 WeakHashMap 里
 * （state 不再被引用时条目自动回收），各 blur 消费点经 [recoverableBlurEnabled] 读取应用。
 */
private val recoverableBlurGates: MutableMap<HazeState, MutableState<Boolean>> =
    Collections.synchronizedMap(WeakHashMap())

@Composable
fun recoverableBlurEnabled(state: HazeState): Boolean {
    val gate = recoverableBlurGates[state] ?: return true
    return gate.value
}

/** blur/glass 的 source 侧成对门控：SDK 不支持 RenderEffect 时也不注册采样源。 */
fun Modifier.hazeSourceCompat(state: HazeState): Modifier {
    if (!shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)) return this
    return hazeSource(state)
}

/**
 * blur 消费侧成对门控：SDK 不允许时整体不挂 blur（而不是挂上后靠 haze 自行退化）；
 * 允许时把前后台 gate 写进 style（`blurEnabled`），进后台后该消费点自动停渲染。
 *
 * 与裸 [hazeBlur] 的区别只在 input 固定为 [state] 的 Sources——本仓的 blur 消费点
 * 都只采样单一 state，收进这里省得每处都要重复 gate 逻辑。
 */
@Composable
fun Modifier.recoverableHazeBlur(
    state: HazeState,
    style: HazeBlurStyle,
): Modifier {
    if (!shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)) return this
    val blurEnabled = recoverableBlurEnabled(state)
    val gatedStyle = remember(blurEnabled, style) {
        HazeBlurStyle { blurEnabled(blurEnabled) }.then(style)
    }
    return hazeBlur(
        input = HazeInput.Sources(state),
        style = gatedStyle,
    )
}

/**
 * 替代 [dev.chrisbanes.haze.rememberHazeState] 的可恢复版本：
 * 注册前后台 gate；回前台时按 SDK 门槛决定是否重建 state。
 *
 * [userEnabled] 是状态级的总闸（本仓各消费点还有自己的用户开关，两者相与才生效）。
 */
@Composable
fun rememberRecoverableHazeState(
    userEnabled: Boolean = true,
    sdkInt: Int = Build.VERSION.SDK_INT,
): HazeState {
    var isAppInBackground by remember { mutableStateOf(false) }
    var recreationKey by remember { mutableIntStateOf(0) }
    val shouldRecreateState = shouldRecreateHazeStateOnForeground(sdkInt)
    val hazeState = remember(recreationKey) { HazeState() }
    val blurGate = remember(hazeState) {
        mutableStateOf(userEnabled).also { recoverableBlurGates[hazeState] = it }
    }

    DisposableEffect(hazeState) {
        recoverableBlurGates[hazeState] = blurGate
        onDispose { recoverableBlurGates.remove(hazeState) }
    }

    DisposableEffect(shouldRecreateState) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isAppInBackground = false
                if (shouldRecreateState) recreationKey += 1
            }

            override fun onStop(owner: LifecycleOwner) {
                isAppInBackground = true
                // 进后台不重建：界面不可见，重录全屏 haze source 没有意义；
                // blurGate 会经 SideEffect 随 isAppInBackground 关掉。
            }
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SideEffect {
        blurGate.value = shouldEnableRecoverableBlur(
            userEnabled = userEnabled,
            isAppInBackground = isAppInBackground,
        )
    }

    return hazeState
}
