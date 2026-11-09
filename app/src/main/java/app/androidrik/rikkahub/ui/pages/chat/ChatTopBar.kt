package app.androidrik.rikkahub.ui.pages.chat

// 批次 7 拆出：TopBar + ContextUsageReadout 自 ChatPage.kt 拆出（同包，private→internal）

import app.androidrik.rikkahub.ui.components.ui.appSkinTextSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Arrangement
import app.androidrik.rikkahub.ui.theme.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.ai.ContextUsage
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.components.haze.TopBarCapsule
import app.androidrik.rikkahub.ui.components.ui.ExpressivePopup
import app.androidrik.rikkahub.ui.components.ui.ExpressivePopupAlignment
import app.androidrik.rikkahub.ui.components.haze.liquidGlassChromeEffect
import app.androidrik.rikkahub.ui.hooks.EditStateContent
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.pages.taskcenter.ConversationTasksSheet
import app.androidrik.rikkahub.utils.CONTEXT_USAGE_WARNING_RATIO
import app.androidrik.rikkahub.utils.formatContextLength
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ListTree
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share01

/**
 * 标题旁的上下文占用读数：状态点 + `已用/上限（余 X）`。
 *
 * 与输入栏指示器**分工**：这里给精确数字、那里只给状态（2026-10-02 决定）。
 * 颜色落在状态点、常态浅蓝，达共用的警戒阈值（[CONTEXT_USAGE_WARNING_RATIO]）才切红。
 * 读数不可省略（左侧助手/模型名可省略）。
 */
@Composable
internal fun ContextUsageReadout(
    usedTokens: Int,
    windowTokens: Int,
) {
    val fraction = if (windowTokens > 0) (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f) else 0f
    val isWarning = windowTokens > 0 && fraction >= CONTEXT_USAGE_WARNING_RATIO
    val remaining = (windowTokens - usedTokens).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isWarning) MaterialTheme.colorScheme.error else ContextStatusDotNormal),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(
                R.string.context_capacity_titlebar_readout,
                formatContextLength(usedTokens),
                formatContextLength(windowTokens),
                formatContextLength(remaining),
            ),
            maxLines = 1,
            color = if (isWarning) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(0.65f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = appSkinTextSize(8.sp)),
        )
    }
}

@Composable
internal fun TopBar(
    settings: Settings,
    conversation: Conversation,
    contextUsage: ContextUsage,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    bigScreen: Boolean,
    previewMode: Boolean,
    hazeState: HazeState,
    glassHazeState: HazeState,
    showCapsuleBackground: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onExport: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var showTasks by remember(conversation.id) { mutableStateOf(false) }
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }
    // 顶栏 chrome 两套共存（上游 b40d7a273）：CAPSULES 时整条效果让位给浮动胶囊
    val skin = LocalAppSkin.current.skin
    val panel = skin.topBar.panel
    val barShape = panel.shape(RectangleShape)
    val barInk = panel.ink(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceContainerLow)
    val barBorder = panel.border()
    val capsuleMode = skin.topBarMode(settings.displaySetting) == TopBarChromeStyle.CAPSULES

    // 右侧操作按钮：FULL_BAR 直挂 actions，CAPSULES 由胶囊包裹（背景随滚动淡入）。
    // 「本会话任务」保留一级直达，其余收进「更多」二级展开菜单，压缩顶栏占位
    var showMoreMenu by remember { mutableStateOf(false) }
    val topBarActions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { showTasks = true }) { Icon(Lucide.ListTree, stringResource(R.string.conversation_tasks_title)) }
        Box {
            IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                Icon(HugeIcons.MoreVertical, "More")
            }
            ExpressivePopup(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
                alignment = ExpressivePopupAlignment.End,
                // 顶栏菜单项少、文本短，比 ExpressivePopup 默认的 280dp 再收窄一档
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_page_more_menu_options)) },
                    onClick = {
                        showMoreMenu = false
                        onClickMenu()
                    },
                    leadingIcon = {
                        Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, null)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.common_export)) },
                    onClick = {
                        showMoreMenu = false
                        onExport()
                    },
                    leadingIcon = { Icon(HugeIcons.Share01, null) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_page_new_chat)) },
                    onClick = {
                        showMoreMenu = false
                        onNewChat()
                    },
                    leadingIcon = { Icon(HugeIcons.MessageAdd01, null) }
                )
            }
        }
    }

    TopAppBar(
        modifier = Modifier.then(if (!capsuleMode && panel.radiusDp != null) Modifier.clip(barShape) else Modifier).then(
            // 与输入栏同款机制（BackgroundEffectType BLUR/GLASS 二选一）：消费 hazeState
            // （背景 + 穿底内容）——列表经 contentPadding 从顶栏底下穿过，穿过的部分被
            // 实时模糊/玻璃化（上游截图同款效果）。顶栏是悬浮层，采样聊天内容不踩
            // 「blur 容器含可滚动子节点」坑。胶囊档不挂整条效果（按钮各自带胶囊背景）。
            if (settings.displaySetting.enableTopBarBlur && !capsuleMode && panel.background == null) Modifier.liquidGlassChromeEffect(
                hazeState = hazeState,
                effectType = settings.displaySetting.topBarEffectType,
                readabilityMode = settings.displaySetting.liquidGlassReadabilityMode,
            ) else Modifier
        ).then(if (!capsuleMode) Modifier.appSkinPanelBackground(panel, MaterialTheme.colorScheme.surfaceContainerLow, barInk, barShape)
            .then(if (barBorder != null) Modifier.border(barBorder, barShape) else Modifier) else Modifier),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
            titleContentColor = barInk, navigationIconContentColor = barInk, actionIconContentColor = barInk),
        navigationIcon = {
            if (!bigScreen) {
                if (capsuleMode) {
                    TopBarCapsule(
                        hazeState = hazeState,
                        displaySetting = settings.displaySetting,
                        showBackground = showCapsuleBackground,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    ) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(HugeIcons.Menu03, "Messages")
                        }
                    }
                } else {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(HugeIcons.Menu03, "Messages")
                    }
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column(Modifier.padding(horizontal = (panel.paddingHorizontalDp ?: 0f).dp,
                    vertical = (panel.paddingVerticalDp ?: 0f).dp), verticalArrangement = Arrangement.spacedBy((panel.gapDp ?: 0f).dp)) {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                // 助手/模型可省略，右侧占用读数是精确读数、不可省略
                                modifier = Modifier.weight(1f, fill = false),
                                color = LocalContentColor.current.copy(0.65f),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = appSkinTextSize(8.sp),
                                )
                            )
                            ContextUsageReadout(
                                usedTokens = contextUsage.usedTokens,
                                windowTokens = contextUsage.windowTokens,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            if (capsuleMode) {
                TopBarCapsule(
                    hazeState = hazeState,
                    displaySetting = settings.displaySetting,
                    showBackground = showCapsuleBackground,
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                    content = topBarActions,
                )
            } else {
                topBarActions()
            }
        },
    )
    if (showTasks) ConversationTasksSheet(conversation.id) { showTasks = false }
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
