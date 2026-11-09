package app.androidrik.rikkahub.ui.pages.chat

// 批次 7 拆出：DrawerActions / DrawerBottomActions / DrawerEntry / DrawerAction / DrawerPanelSwitcher
// 自 ChatDrawer.kt 拆出（同包，private→internal）

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.ui.Tooltip
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.AppSkinPanelSurface
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.TransactionHistory

@Composable
internal fun DrawerBottomActions(navController: Navigator) {
    Row(
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        DrawerAction(
            icon = {
                Icon(HugeIcons.LookTop, stringResource(R.string.assistant_page_title))
            },
            label = { Text(stringResource(R.string.assistant_page_title)) },
            onClick = { navController.navigate(Screen.Assistant) },
        )
        DrawerAction(
            icon = {
                Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
            },
            label = { Text(stringResource(R.string.favorite_page_title)) },
            onClick = { navController.navigate(Screen.Favorite) },
        )
        DrawerAction(
            icon = { Icon(HugeIcons.ChartColumn, stringResource(R.string.chat_drawer_statistics)) },
            label = { Text(stringResource(R.string.chat_drawer_statistics)) },
            onClick = { navController.navigate(Screen.Stats) },
        )
        Spacer(Modifier.weight(1f))
        DrawerAction(
            icon = { Icon(HugeIcons.Settings03, stringResource(R.string.settings)) },
            label = { Text(stringResource(R.string.settings)) },
            onClick = { navController.navigate(Screen.Setting) },
        )
    }
}

@Composable
internal fun DrawerActions(navController: Navigator) {
    Column {
        // 聊天历史入口，行尾是搜索入口（吸收上游 660571449：搜索不再单独占一行）
        DrawerEntry(
            icon = HugeIcons.TransactionHistory,
            text = stringResource(R.string.chat_page_history),
            onClick = { navController.navigate(Screen.History) },
            trailingIcon = HugeIcons.Search01,
            trailingDescription = stringResource(R.string.chat_page_search_chats),
            onTrailingClick = { navController.navigate(Screen.MessageSearch) },
        )

        // 媒体创作与翻译并列，翻译位于右侧。
        Row(modifier = Modifier.fillMaxWidth()) {
            DrawerEntry(
                icon = HugeIcons.Image02,
                text = stringResource(R.string.media_creation_title),
                onClick = { navController.navigate(Screen.MediaCreationSessions) },
                modifier = Modifier.weight(1f),
            )
            DrawerEntry(
                icon = HugeIcons.LanguageCircle,
                text = stringResource(R.string.chat_page_menu_ai_translator),
                onClick = { navController.navigate(Screen.Translator) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DrawerEntry(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    trailingDescription: String? = null,
    onTrailingClick: () -> Unit = {},
) {
    val panel = LocalAppSkin.current.skin.drawer.item
    AppSkinPanelSurface(panel = panel,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        defaultShape = MaterialTheme.shapes.medium,
        defaultColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = (panel.paddingHorizontalDp ?: 8f).dp, vertical = (panel.paddingVerticalDp ?: 10f).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((panel.gapDp ?: 12f).dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalContentColor.current,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailingIcon != null) {
                // 不用 IconButton：它的 48dp 最小尺寸会把这一行撑得比其他行高
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onTrailingClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = trailingDescription,
                        modifier = Modifier.size(18.dp),
                        tint = if (panel.background != null || panel.contentColorHex != null) LocalContentColor.current.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

@Composable
internal fun DrawerPanelSwitcher(
    showFiles: Boolean,
    onSwitch: (Boolean) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        SegmentedButton(
            selected = !showFiles,
            onClick = { onSwitch(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text(
                text = stringResource(R.string.chat_drawer_panel_chats),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        SegmentedButton(
            selected = showFiles,
            onClick = { onSwitch(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text(
                text = stringResource(R.string.chat_drawer_panel_files),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
