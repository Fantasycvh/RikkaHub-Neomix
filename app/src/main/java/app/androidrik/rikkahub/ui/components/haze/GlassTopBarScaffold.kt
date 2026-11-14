package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.ui.hooks.rememberAmoledDarkMode

/**
 * 聊天域之外的页面级玻璃 Scaffold（方向 C，design-ui-overhaul-research.md §5.C）。
 *
 * 与 [androidx.compose.material3.Scaffold] 的差异：content 全屏铺底、TopAppBar 悬浮在内容
 * 上方——列表内容滚动时从顶栏下方穿过，顶栏区域的玻璃/模糊层让穿底内容呈现磨砂效果
 * （M3 Scaffold 的 content 与 topBar 上下排布，内容到不了顶栏底下，做不了穿底）。
 *
 * 结构纪律（玻璃父子拓扑，见液态玻璃清单）：顶栏玻璃层只采样 content 侧注册的
 * [hazeState]——本组件内部把 content 包进采样源 Box，页面无需关心；顶栏自身绝不成为
 * 采样源。门控与聊天域玻璃同源：
 * - 用户开关 [DisplaySetting.enablePageGlassTheme]（效果类型 [DisplaySetting.pageGlassEffectType]）；
 * - AMOLED 纯黑主题自动禁用（玻璃在纯黑上无意义）；
 * - SDK/前后台分级在 [liquidGlassChromeEffect] 内部（GLASS<T33 退 BLUR、<S31 无效果）。
 *
 * 页面迁移契约：
 * 1. `Scaffold` → 本组件，补 `displaySetting = LocalSettings.current.displaySetting`，
 *    `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` 挪到本组件的 modifier 上；
 * 2. topBar 的颜色用 [pageGlassTopBarColors] 包一层——玻璃开启时容器转透明、由玻璃层铺底，
 *    关闭时返回原色（视觉与原 Scaffold 顶栏一致）；
 * 3. content 收到的 [PaddingValues] 已含顶栏实测高度，照旧拼 skinSpacing 使用；
 *    本组件不处理底部 inset，页面原 topBar 之下的导航栏 padding 由内容自理（与原用法一致）。
 */
@Composable
fun GlassTopBarScaffold(
    displaySetting: DisplaySetting,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    bottomBar: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    snackbarHost: (@Composable () -> Unit)? = null,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val glassOn = displaySetting.enablePageGlassTheme && !rememberAmoledDarkMode().value
    val hazeState = rememberRecoverableHazeState(userEnabled = glassOn)
    val glassModifier = if (glassOn) {
        Modifier.liquidGlassChromeEffect(
            hazeState = hazeState,
            effectType = displaySetting.pageGlassEffectType,
            readabilityMode = displaySetting.liquidGlassReadabilityMode,
        )
    } else {
        Modifier
    }

    Surface(color = containerColor, modifier = Modifier.fillMaxSize()) {
        SubcomposeLayout { constraints ->
            // 关键：外层 Surface(fillMaxSize) 传来的是固定高度约束（min=max=屏高），直接量
            // 会让 LargeFlexibleTopAppBar 被迫撑满整屏（contentPadding 被顶出屏幕）。
            // 放松 minHeight 让 topBar 按自然高度取值（Scaffold 同款处理）。
            val loose = constraints.copy(minHeight = 0)
            val bottomBarPlaceable = bottomBar?.let {
                subcompose(BottomSlot) { it() }.first().measure(loose)
            }
            val fabPlaceable = floatingActionButton?.let {
                subcompose(FabSlot) { it() }.first().measure(loose)
            }
            val snackbarPlaceable = snackbarHost?.let {
                subcompose(SnackbarSlot) { it() }.first().measure(loose)
            }
            val topBarPlaceable = subcompose(TopBarSlot) {
                CompositionLocalProvider(LocalPageGlassTheme provides glassOn) {
                    Box {
                        if (glassOn) {
                            Box(Modifier.matchParentSize().clipToBounds().then(glassModifier))
                        }
                        topBar()
                    }
                }
            }.first().measure(loose)
            val contentPlaceable = subcompose(ContentSlot) {
                // source 只在玻璃开启时注册（关闭时 no-op）；内部包一层 Box 作为采样源，
                // 页面无需感知——源覆盖 content 全区，顶栏穿底的部分自然被采样
                val sourceModifier = if (glassOn) Modifier.hazeSourceCompat(hazeState) else Modifier
                // 与 Scaffold 的 contentWindowInsets（systemBars）语义对齐：顶栏高度之外的
                // 左右/下 inset 照旧由 contentPadding 提供（页面原先依赖它做导航栏留白）
                val bars = WindowInsets.systemBars.asPaddingValues()
                // bottomBar 存在时其自身负责导航栏 inset（Scaffold 同语义），bottom 取其高度
                val bottomPad = bottomBarPlaceable?.height?.toDp() ?: bars.calculateBottomPadding()
                Box(modifier = sourceModifier) {
                    content(
                        PaddingValues(
                            top = topBarPlaceable.height.toDp(),
                            start = bars.calculateStartPadding(layoutDirection),
                            end = bars.calculateEndPadding(layoutDirection),
                            bottom = bottomPad,
                        ),
                    )
                }
            }.first().measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                contentPlaceable.place(0, 0)
                topBarPlaceable.place(0, 0)
                bottomBarPlaceable?.place(0, constraints.maxHeight - bottomBarPlaceable.height)
                // FAB/Snackbar 摆放与 Scaffold 近似：都让位 bottomBar，FAB 右下 16dp、snackbar 底部居中
                fabPlaceable?.let {
                    it.place(
                        constraints.maxWidth - it.width - 16.dp.toPx().toInt(),
                        constraints.maxHeight - (bottomBarPlaceable?.height ?: 0) - it.height - 16.dp.toPx().toInt(),
                    )
                }
                snackbarPlaceable?.let {
                    it.place((constraints.maxWidth - it.width) / 2, constraints.maxHeight - (bottomBarPlaceable?.height ?: 0) - it.height)
                }
            }
        }
    }
}

/** 顶栏玻璃开启时注入 content 侧（见 [GlassTopBarScaffold]），页面顶栏经 [pageGlassTopBarColors] 读取。 */
val LocalPageGlassTheme = staticCompositionLocalOf { false }

/**
 * 页面 TopAppBar 的容器色包装：玻璃开启时转透明（玻璃层负责铺底与着色），
 * 关闭/AMOLED 时原样返回（视觉与迁移前的 Scaffold 顶栏一致）。
 */
@Composable
fun pageGlassTopBarColors(fallback: TopAppBarColors): TopAppBarColors {
    return if (LocalPageGlassTheme.current) TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent,
        scrolledContainerColor = Color.Transparent,
    ) else fallback
}

private const val TopBarSlot = "glass-scaffold-topbar"
private const val ContentSlot = "glass-scaffold-content"
private const val BottomSlot = "glass-scaffold-bottombar"
private const val FabSlot = "glass-scaffold-fab"
private const val SnackbarSlot = "glass-scaffold-snackbar"
