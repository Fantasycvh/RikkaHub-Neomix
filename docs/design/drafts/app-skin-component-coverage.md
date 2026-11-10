# AppSkin 组件覆盖清单

由 `python scripts/audit_app_skin.py --write-report` 生成；策略和逐项理由保存在
`scripts/app_skin_coverage_exceptions.json`。只更新本地草稿，不执行发布或推送。

## 基础控件入口

| 控件 | AppSkin 调用 | 保留原生调用 |
| --- | ---: | ---: |
| Button | 71 | 1 |
| OutlinedButton | 39 | 0 |
| TextButton | 401 | 3 |
| FilledTonalButton | 12 | 0 |
| OutlinedTextField | 280 | 0 |
| TextField | 15 | 0 |

调用名允许通过 import alias 保持原样；审计根据导入目标判定。统一封装内部的 Material 委托不计入页面调用。

| 区域 | AppSkin 调用 | 保留原生调用 |
| --- | ---: | ---: |
| startup/other | 0 | 4 |
| ui/activity | 3 | 0 |
| ui/components/ai | 48 | 0 |
| ui/components/files | 4 | 0 |
| ui/components/message | 22 | 0 |
| ui/components/sketch | 5 | 0 |
| ui/components/ui | 28 | 0 |
| ui/components/workspace | 1 | 0 |
| ui/floating | 9 | 0 |
| ui/pages/agentrun | 8 | 0 |
| ui/pages/assistant | 70 | 0 |
| ui/pages/backup | 51 | 0 |
| ui/pages/chat | 46 | 0 |
| ui/pages/debug | 14 | 0 |
| ui/pages/extensions | 90 | 0 |
| ui/pages/favorite | 10 | 0 |
| ui/pages/history | 2 | 0 |
| ui/pages/imggen | 6 | 0 |
| ui/pages/mediacreation | 18 | 0 |
| ui/pages/plugins | 20 | 0 |
| ui/pages/search | 3 | 0 |
| ui/pages/setting | 334 | 0 |
| ui/pages/subagent | 7 | 0 |
| ui/pages/taskcenter | 6 | 0 |
| ui/pages/translator | 13 | 0 |

## 继承 MaterialTheme 的宿主

这些宿主默认读取全局形状、字体、配色；数量不代表其所有内部样式已可配置。
显式形状优先，固定圆角另见下面的逐项清单。

| 宿主 | 调用数 |
| --- | ---: |
| Card | 119 |
| OutlinedCard | 13 |
| ElevatedCard | 1 |
| DropdownMenu | 24 |
| ExposedDropdownMenu | 2 |
| AlertDialog | 105 |
| BasicAlertDialog | 4 |
| ModalBottomSheet | 62 |
| TopAppBar | 16 |
| CenterAlignedTopAppBar | 0 |
| LargeTopAppBar | 0 |
| MediumTopAppBar | 0 |
| LargeFlexibleTopAppBar | 62 |
| MediumFlexibleTopAppBar | 0 |
| IconButton | 215 |
| FilledIconButton | 16 |
| FilledTonalIconButton | 12 |
| OutlinedIconButton | 0 |
| FilterChip | 44 |
| AssistChip | 2 |
| InputChip | 3 |
| SuggestionChip | 0 |
| ListItem | 28 |
| Slider | 30 |
| Switch | 177 |

## 已接入的共享宿主

共享宿主使用全局或子域 token；宿主行为、显式覆写和最小触控尺寸继续由组件控制。

| 宿主 | 调用数 |
| --- | ---: |
| CardGroup | 59 |
| FormItem | 176 |
| Select | 22 |
| SelectTextField | 15 |
| SegmentedPill | 13 |
| Tag | 44 |
| ToggleSurface | 5 |
| ExpressivePopup | 2 |
| ErrorCard | 2 |
| ChartCard | 5 |
| CodeHighlightText | 2 |
| HighlightCodeBlock | 7 |
| DiffView | 4 |

## 保留的形状及原生控件

每项注明来源、表达式和保留理由。新增或增加同类绕过会使审计失败；删除条目后也必须清理过期例外。

| 来源 | 表达式 | 理由 |
| --- | --- | --- |
| [StartupGate.kt:179](../../../app/src/main/java/app/androidrik/rikkahub/StartupGate.kt#L179) | `Button(...)` | Startup/recovery runs before settings are available; GateTheme must not depend on AppSkin loading. |
| [StartupGate.kt:117](../../../app/src/main/java/app/androidrik/rikkahub/StartupGate.kt#L117) | `TextButton(...)` | Startup/recovery runs before settings are available; GateTheme must not depend on AppSkin loading. |
| [StartupGate.kt:141](../../../app/src/main/java/app/androidrik/rikkahub/StartupGate.kt#L141) | `TextButton(...)` | Startup/recovery runs before settings are available; GateTheme must not depend on AppSkin loading. |
| [StartupGate.kt:150](../../../app/src/main/java/app/androidrik/rikkahub/StartupGate.kt#L150) | `TextButton(...)` | Startup/recovery runs before settings are available; GateTheme must not depend on AppSkin loading. |
| [ui/components/ai/AsrButton.kt:212](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ai/AsrButton.kt#L212) | `RoundedCornerShape(barWidth / 2)` | Audio waveform bar caps follow the bar width; these are visualization geometry. |
| [ui/components/ai/ReasoningPicker.kt:288](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ai/ReasoningPicker.kt#L288) | `RoundedCornerShape(999.dp)` | Small level-indicator tick; intrinsic fully round data mark, not a card or action surface. |
| [ui/components/charts/ChartCard.kt:151](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/charts/ChartCard.kt#L151) | `RoundedCornerShape(2.dp)` | Legend color mark geometry, independent of the surrounding skinned chart card. |
| [ui/components/charts/ChartTable.kt:108](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/charts/ChartTable.kt#L108) | `RoundedCornerShape(2.dp)` | Table column color mark geometry; does not control the skinned container. |
| [ui/components/drawer/ChatScalingDrawer.kt:208](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/drawer/ChatScalingDrawer.kt#L208) | `RoundedCornerShape(ChatDrawerSpec.CORNER_RADIUS_END * p)` | Gesture progress drives the outer navigation transform; drawer content has its own AppSkin slot. |
| [ui/components/haze/TopBarCapsule.kt:43](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/haze/TopBarCapsule.kt#L43) | `RoundedCornerShape(it.dp)` | Already reads the dedicated top-bar panel radius; explicit slot geometry wins. |
| [ui/components/message/ChatBubbleSurface.kt:49](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/message/ChatBubbleSurface.kt#L49) | `RoundedCornerShape(radius)` | Already reads the ChatAppearance bubble radius and continuous slice edges. |
| [ui/components/message/ChatBubbleSurface.kt:49](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/message/ChatBubbleSurface.kt#L49) | `RoundedCornerShape( topStart = if (segment.first) radius else 0.dp, topEnd = if (segment.first) radius else 0.dp, bottomStart = if (segment.last) radius else 0.dp, bottomEnd = if (segment.last) radius else 0.dp, )` | Already reads the ChatAppearance bubble radius and continuous slice edges. |
| [ui/components/message/MessageQuoteCard.kt:78](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/message/MessageQuoteCard.kt#L78) | `RoundedCornerShape(2.dp)` | Thin quote accent bar cap, retained as a structural marker. |
| [ui/components/ui/AppSkinComponents.kt:29](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L29) | `RoundedCornerShape(it.dp)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/AppSkinComponents.kt:34](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L34) | `RoundedCornerShape(LocalAppSkin.current.skin.shapes.scaledCornerRadius(defaultDp).dp)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/AppSkinComponents.kt:42](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L42) | `RoundedCornerShape(LocalAppSkin.current.skin.shapes.inputDp.dp)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/AppSkinComponents.kt:47](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L47) | `RoundedCornerShape(topStart = it, topEnd = it)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/AppSkinComponents.kt:56](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L56) | `RoundedCornerShape(radius.dp)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/AppSkinComponents.kt:56](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/AppSkinComponents.kt#L56) | `RoundedCornerShape((radius * .7f).dp)` | Token-to-shape adapter; values come from AppSkin, not a fixed host style. |
| [ui/components/ui/CardGroup.kt:150](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/CardGroup.kt#L150) | `RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)` | Already reads grouped-card tokens and animates the resolved corners. |
| [ui/components/ui/CardGroup.kt:257](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/CardGroup.kt#L257) | `RoundedCornerShape( topStart = topCorner, topEnd = topCorner, bottomStart = bottomCorner, bottomEnd = bottomCorner, )` | Already reads grouped-card tokens and animates the resolved corners. |
| [ui/components/ui/ChainOfThought.kt:98](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/ChainOfThought.kt#L98) | `RoundedCornerShape(it.dp)` | Explicit reasoning appearance override; otherwise uses global shape tokens and the reasoning panel slot. |
| [ui/components/ui/Favicon.kt:29](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/Favicon.kt#L29) | `RoundedCornerShape(25)` | Site identity image mask; an explicit caller shape remains supported. |
| [ui/components/ui/ImagePreviewDialog.kt:150](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/ImagePreviewDialog.kt#L150) | `RoundedCornerShape(50)` | Image-viewer counter contrast capsule; content overlay geometry is retained. |
| [ui/components/ui/Switch.kt:99](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/Switch.kt#L99) | `RoundedCornerShape(50)` | Switch track geometry stays proportional to the thumb and track dimensions. |
| [ui/pages/chat/ContextCapacitySheet.kt:234](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ContextCapacitySheet.kt#L234) | `RoundedCornerShape(50)` | Progress track end caps; action surfaces already use AppSkin button shapes. |
| [ui/pages/chat/ConversationList.kt:312](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/chat/ConversationList.kt#L312) | `RoundedCornerShape(50f)` | Default capsule fallback for the dedicated drawer-item slot; explicit panel radius wins. |
| [ui/theme/AppSkinPanel.kt:32](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinPanel.kt#L32) | `RoundedCornerShape(it.dp)` | Already reads the requested panel radius; host fallback applies only when it is absent. |
| [ui/theme/AppSkinTheme.kt:41](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L41) | `RoundedCornerShape(extraSmallDp.dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:41](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L41) | `RoundedCornerShape(smallDp.dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:42](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L42) | `RoundedCornerShape(mediumDp.dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:42](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L42) | `RoundedCornerShape(largeDp.dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:42](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L42) | `RoundedCornerShape(extraLargeDp.dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:43](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L43) | `RoundedCornerShape(scaledCornerRadius(20f).dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:44](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L44) | `RoundedCornerShape(scaledCornerRadius(32f).dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/AppSkinTheme.kt:45](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L45) | `RoundedCornerShape(scaledCornerRadius(48f).dp)` | Global token-to-Material shape adapter, including three derived Expressive tiers. |
| [ui/theme/BuiltinChatAppearancePlugin.kt:82](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/BuiltinChatAppearancePlugin.kt#L82) | `RoundedCornerShape(24.dp)` | Demonstration plugin renderer explicitly owns this badge shape; plugin renderers can override host defaults. |

## 审计边界与剩余项目

- 本清单检查应用 Kotlin 源码的六类基础控件入口与直接 RoundedCornerShape 构造；不把调用比例当成整个应用完成度。
- 页面结构尺寸、触控最小尺寸、语义状态色和内容数据保持宿主控制；不能把全部 dp/颜色机械改成皮肤参数。
- 自有 HTML、终端默认色/字号与画布工作区/新画纸已接入 embedded 契约，详见 [嵌入式渲染器说明](design-app-skin-embedded.md)。
- 远程网页、作者 CSS、ANSI/OSC 显式配色、已有画纸/笔画及公式/SVG 图形颜色保留自身内容协议。
- 自定义插件渲染器可显式覆盖形状/字体；只能约束已采用契约的宿主，不能强制未知插件遵守。
- 字体/图标/背景完整资源包及安装升级管理、整皮肤试穿撤销、全页面明暗/大字体/对比度/性能验收仍是后续工作。
