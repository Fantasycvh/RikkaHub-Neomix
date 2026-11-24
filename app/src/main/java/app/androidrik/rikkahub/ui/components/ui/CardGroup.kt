package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastForEach
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import app.androidrik.rikkahub.data.model.AppSkinDividerStyle
import app.androidrik.rikkahub.data.model.AppSkinGroupStyle
import app.androidrik.rikkahub.data.model.AppSkinGroups
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.shapeForRadius
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull

private val CardGroupCorner @Composable get() = LocalAppSkin.current.skin.shapes.cardGroupOuterDp.dp
private val CardGroupItemSpacing @Composable get() = LocalAppSkin.current.skin.spacing.groupItemDp.dp
private val CardGroupInnerCorner @Composable get() = LocalAppSkin.current.skin.shapes.cardGroupInnerDp.dp

private data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: (@Composable () -> Unit)?,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    /** 不为空时是表单项，标题行下方接这块内容 */
    val formContent: (@Composable ColumnScope.() -> Unit)? = null,
    /** 行尾「当前值」位：与 trailingContent 同时给出时并排渲染（值在前） */
    val trailingValue: (@Composable () -> Unit)? = null,
    /** 折叠段内成员；折叠时整段隐藏 */
    val advanced: Boolean = false,
    /** 不为空时是折叠段标题行（纯数据标记，渲染在 composable 上下文构建） */
    val advancedHead: AdvancedHead? = null,
)

private data class AdvancedHead(
    val title: String,
    val hint: String?,
    val expanded: Boolean,
    val memberCount: Int,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        trailingValue: (@Composable () -> Unit)? = null,
        headlineContent: @Composable () -> Unit,
    )

    /** 表单项：标题行下方带一块自定义内容（输入框、滑块等），不传标题时整项都是自定义内容 */
    fun formItem(
        modifier: Modifier = Modifier,
        headlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    )

    /**
     * 低频收「高级」折叠段：标题行 + 默认隐藏的成员 items。
     * 状态由调用方持有（rememberSaveable），本组件纯受控；首页不放放宽保护/删数据控件。
     */
    fun advancedGroup(
        title: String,
        hint: String? = null,
        expanded: Boolean,
        onToggle: (Boolean) -> Unit,
        content: CardGroupScope.() -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        trailingValue: (@Composable () -> Unit)?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
                trailingValue = trailingValue,
            )
        )
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = null,
                formContent = content,
            )
        )
    }

    override fun advancedGroup(
        title: String,
        hint: String?,
        expanded: Boolean,
        onToggle: (Boolean) -> Unit,
        content: CardGroupScope.() -> Unit,
    ) {
        val nested = CardGroupScopeImpl()
        nested.content()
        // 标题行只带纯数据（title/hint/expanded），composable 内容在 CardGroupListItem 渲染时构建
        items.add(
            CardGroupItem(
                onClick = { onToggle(!expanded) },
                modifier = Modifier,
                overlineContent = null,
                headlineContent = null,
                supportingContent = null,
                leadingContent = null,
                trailingContent = null,
                colors = null,
                advancedHead = AdvancedHead(title, hint, expanded, nested.items.size),
            )
        )
        items.addAll(nested.items.map { it.copy(advanced = true) })
    }
}

/** 分组里第 index 项的形状，给不方便走 CardGroup 的列表（比如可拖拽排序的）用 */
@Composable
fun cardGroupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) CardGroupCorner else CardGroupInnerCorner
    val bottom = if (index == count - 1) CardGroupCorner else CardGroupInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 列表项开头带圆形底色的图标 */
@Composable
fun CardGroupIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    contentDescription: String? = null,
) {
    val shapes = LocalAppSkin.current.skin.shapes
    val tile = shapes.iconTile
    val shape = tile?.let { shapes.shapeForRadius(it.radiusDp) } ?: CircleShape
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Box(
        modifier = modifier
            .size((tile?.sizeDp ?: 40f).dp)
            .background(animatedContainerColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size((tile?.iconSizeDp ?: 20f).dp),
            tint = animatedContentColor,
        )
    }
}

@Composable
private fun CardGroupFormItem(
    item: CardGroupItem,
    formContent: @Composable ColumnScope.() -> Unit,
    count: Int,
    index: Int,
    flat: Boolean = true,
) {
    val spacing = LocalAppSkin.current.skin.spacing
    Surface(
        modifier = item.modifier.fillMaxWidth(),
        shape = if (flat) cardGroupItemShape(index, count) else RoundedCornerShape(0.dp),
        color = if (flat) CustomColors.listItemColors.containerColor else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.formHorizontalDp.dp, vertical = spacing.formVerticalDp.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.formGapDp.dp),
        ) {
            if (item.headlineContent != null || item.leadingContent != null || item.trailingContent != null) {
                Row(
                    // 和 ListItem 一致：带说明文字时，两侧内容靠上对齐
                    verticalAlignment = if (item.supportingContent != null) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item.leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.headlineContent?.let { headline ->
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                        }
                        item.supportingContent?.let { supporting ->
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                            }
                        }
                    }
                    item.trailingContent?.invoke()
                }
            }
            formContent()
        }
    }
}

@Composable
private fun CardGroupListItem(
    item: CardGroupItem,
    count: Int,
    index: Int,
    flat: Boolean = true,
) {
    val isFirst = index == 0
    val isLast = index == count - 1

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val topCorner by animateDpAsState(
        targetValue = if (!flat) 0.dp else if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (!flat) 0.dp else if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )

    val head = item.advancedHead
    val trailing: (@Composable () -> Unit)? = when {
        head != null -> AdvancedChevron(head.expanded)
        item.trailingValue == null -> item.trailingContent
        item.trailingContent == null -> item.trailingValue
        // 值 chip 与操作并排：值在前、操作靠右
        else -> MergedTrailing(requireNotNull(item.trailingValue), requireNotNull(item.trailingContent))
    }
    ListItem(
        headlineContent = if (head != null) {
            { Text(head.title) }
        } else item.headlineContent ?: {},
        modifier = item.modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topCorner,
                    topEnd = topCorner,
                    bottomStart = bottomCorner,
                    bottomEnd = bottomCorner,
                )
            )
            .then(
                if (item.onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = item.onClick,
                    )
                } else Modifier
            ),
        overlineContent = item.overlineContent,
        supportingContent = if (head != null) {
            { Text(head.hint ?: "${head.memberCount} 项") }
        } else item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = trailing,
        colors = item.colors ?: if (flat) CustomColors.listItemColors else ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun CardGroupRow(item: CardGroupItem, count: Int, index: Int, flat: Boolean) {
    val formContent = item.formContent
    if (formContent != null) {
        CardGroupFormItem(item = item, formContent = formContent, count = count, index = index, flat = flat)
    } else {
        CardGroupListItem(item = item, count = count, index = index, flat = flat)
    }
}

@Composable
private fun CardGroupDivider(groups: AppSkinGroups) {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (groups.divider == AppSkinDividerStyle.INSET) groups.dividerInsetDp.dp else 0.dp),
        thickness = groups.dividerWidthDp.dp,
        color = groups.dividerColorHex.toComposeColorOrNull() ?: MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 折叠段标题行的 chevron（展开旋转 180 度）。 */
@Composable
private fun AdvancedChevron(expanded: Boolean): @Composable () -> Unit {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    return { Icon(imageVector = Lucide.ChevronDown, contentDescription = null, modifier = Modifier.rotate(rotation)) }
}

/** 值 chip + 操作并排的 trailing。 */
@Composable
private fun MergedTrailing(
    value: @Composable () -> Unit,
    action: @Composable () -> Unit,
): @Composable () -> Unit {
    return {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            value()
            action()
        }
    }
}

/** 行尾「当前值」chip：emphasized=true 用于开/选中态，false 中性（一行放不下时省略号截断）。 */
@Composable
fun CardGroupValueChip(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val container by animateColorAsState(
        targetValue = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val content = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .widthIn(max = 160.dp)
            .background(container, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
                    Box(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        // 折叠段：标题行恒可见，advanced 成员在收起时整段隐藏（CardGroup 是 Column 实现，无 Lazy 宿主）
        val visible = ArrayList<CardGroupItem>(scope.items.size)
        var hiding = false
        scope.items.fastForEach { item ->
            val head = item.advancedHead
            if (head != null) {
                hiding = !head.expanded
                visible.add(item)
            } else if (!item.advanced || !hiding) {
                visible.add(item)
            }
        }
        val count = visible.size
        val groups = LocalAppSkin.current.skin.groups
        if (groups.style == AppSkinGroupStyle.FLAT) {
            visible.fastForEachIndexed { index, item ->
                CardGroupRow(item = item, count = count, index = index, flat = true)
                if (index != count - 1) {
                    Spacer(modifier = Modifier.height(CardGroupItemSpacing))
                }
            }
        } else {
            // 整组容器形态：行不再自带底色/圆角，由容器统一裁剪并插入分隔线。
            val borderColor = groups.borderColorHex.toComposeColorOrNull() ?: MaterialTheme.colorScheme.outlineVariant
            val containerShape = RoundedCornerShape(CardGroupCorner)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape),
                shape = containerShape,
                color = groups.containerColorHex.toComposeColorOrNull() ?: MaterialTheme.colorScheme.surfaceBright,
                border = groups.borderWidthDp.takeIf { it > 0f }?.let { BorderStroke(it.dp, borderColor) },
            ) {
                Column(modifier = Modifier.padding(groups.containerPaddingDp.dp)) {
                    visible.fastForEachIndexed { index, item ->
                        CardGroupRow(item = item, count = count, index = index, flat = false)
                        if (index != count - 1 && groups.divider != AppSkinDividerStyle.NONE) {
                            CardGroupDivider(groups)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
            var advancedExpanded by remember { mutableStateOf(false) }
            CardGroup(
                modifier = Modifier.padding(top = 16.dp),
                title = { Text("IA") },
            ) {
                item(
                    headlineContent = { Text("生成时动效") },
                    trailingValue = { CardGroupValueChip("开", emphasized = true) },
                )
                item(
                    headlineContent = { Text("主题") },
                    trailingValue = { CardGroupValueChip("动态色") },
                )
                advancedGroup(
                    title = "高级",
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = it },
                ) {
                    item(
                        headlineContent = { Text("页面顶栏玻璃") },
                        trailingValue = { CardGroupValueChip("开", emphasized = true) },
                    )
                }
            }
        }
    }
}
