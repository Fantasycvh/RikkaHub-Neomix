package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.builtinSkinChatLink
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.ChatBubble
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.theme.ChatAppearanceRegistry
import app.androidrik.rikkahub.ui.theme.ChatLayoutRegistry
import app.androidrik.rikkahub.ui.theme.AppSkinRegistry
import app.androidrik.rikkahub.ui.theme.effectiveChatPresetId

@Composable
fun ChatAppearanceSettings(
    display: DisplaySetting,
    appearance: ChatAppearance,
    onSelectPreset: (String?) -> Unit,
    onDetachFromSkin: (String?) -> Unit,
    onFollowSkin: () -> Unit,
    onChange: ((ChatAppearance) -> ChatAppearance) -> Unit,
) {
    val presets by ChatAppearanceRegistry.shared.presets.collectAsState()
    val layouts by ChatLayoutRegistry.shared.layouts.collectAsState()
    val skins by AppSkinRegistry.shared.skins.collectAsState()
    var editingUser by remember { mutableStateOf<Boolean?>(null) }
    var editingTypography by remember { mutableStateOf(false) }
    val skinName = skins.firstOrNull { it.id == display.appSkinId }?.name
    val hasLink = builtinSkinChatLink.containsKey(display.appSkinId)
    val effectiveId = effectiveChatPresetId(display)
    CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("聊天外观") }) {
        if (hasLink || skinName != null) {
            item(headlineContent = { Text("与皮肤联动") }, supportingContent = {
                Column {
                    val schemeName = presets.firstOrNull { it.id == effectiveId }?.name
                    Text(when {
                        !hasLink -> "皮肤「$skinName」无推荐聊天方案"
                        display.chatAppearanceDetached -> "独立于皮肤「$skinName」"
                        else -> "跟随皮肤「$skinName」" + (schemeName?.let { " · $it" } ?: "")
                    })
                }
            }, trailingContent = {
                if (hasLink) TextButton(onClick = {
                    if (display.chatAppearanceDetached) onFollowSkin() else onDetachFromSkin(effectiveId)
                }) { Text(if (display.chatAppearanceDetached) "跟随皮肤" else "独立设置") }
            })
        }
        item(headlineContent = { Text("外观方案") }, supportingContent = {
            Column {
                Select(
                    options = listOf<String?>(null) + presets.map { it.id },
                    // 绑定态展示生效方案（皮肤推荐遮蔽覆写槽），否则选完看似无效
                    selectedOption = effectiveId.takeIf { id -> presets.any { it.id == id } },
                    onOptionSelected = onSelectPreset,
                    optionToString = { id -> presets.firstOrNull { it.id == id }?.name ?: "自定义" },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (effectiveId != null && presets.none { it.id == effectiveId }) {
                    Text("方案暂不可用，已回退到自定义外观")
                }
            }
        })
        item(headlineContent = { Text("消息布局") }, supportingContent = {
            Column {
                Select(options = layouts.map { it.id },
                    selectedOption = appearance.layout.rendererId.takeIf { id -> layouts.any { it.id == id } } ?: "builtin.standard",
                    onOptionSelected = { value -> onChange { it.copy(layout = it.layout.copy(rendererId = value)) } },
                    optionToString = { id -> layouts.firstOrNull { it.id == id }?.name ?: "标准" })
                if (layouts.none { it.id == appearance.layout.rendererId }) Text("布局暂不可用，已回退到标准布局")
            }
        })
        item(headlineContent = { Text("显示消息头部") }, trailingContent = {
            Switch(checked = appearance.layout.showHeader, onCheckedChange = { value ->
                onChange { it.copy(layout = it.layout.copy(showHeader = value)) }
            })
        })
        item(headlineContent = { Text("正文最大宽度") }, supportingContent = {
            Column {
                Text("${(appearance.layout.contentWidthFraction * 100).toInt()}%")
                Slider(value = appearance.layout.contentWidthFraction, valueRange = 0.35f..1f, onValueChange = { value ->
                    onChange { it.copy(layout = it.layout.copy(contentWidthFraction = value)) }
                })
            }
        })
        item(headlineContent = { Text("消息之间的间距") }, supportingContent = {
            AppearanceSlider(appearance.layout.listSpacingDp, 0f..40f) { value ->
                onChange { it.copy(layout = it.layout.copy(listSpacingDp = value)) }
            }
        })
        item(headlineContent = { Text("列表边距") }, supportingContent = {
            AppearanceSlider(appearance.layout.listPaddingDp, 0f..40f) { value ->
                onChange { it.copy(layout = it.layout.copy(listPaddingDp = value)) }
            }
        })
        item(headlineContent = { Text("正文字体渐变") }, supportingContent = {
            Text(if (appearance.typography.gradient == null) "跟随主题文字色" else "已启用")
        }, trailingContent = {
            TextButton(onClick = { editingTypography = true }) { Text("编辑") }
        })
        item(headlineContent = { Text("用户消息靠右") }, trailingContent = {
            Switch(checked = appearance.layout.userAlignEnd, onCheckedChange = { value ->
                onChange { it.copy(layout = it.layout.copy(userAlignEnd = value)) }
            })
        })
        item(headlineContent = { Text("消息内部间距") }, supportingContent = {
            AppearanceSlider(appearance.layout.messageGapDp, 0f..32f) { value ->
                onChange { it.copy(layout = it.layout.copy(messageGapDp = value)) }
            }
        })
        item(headlineContent = { Text("用户气泡底色") }, trailingContent = {
            Switch(checked = appearance.userBubble.visible, onCheckedChange = { value ->
                onChange { it.copy(userBubble = it.userBubble.copy(visible = value)) }
            })
        })
        // CardGroup uses Column; every supporting slot has exactly one direct child.
        listOf(true, false).forEach { user ->
            val bubble = if (user) appearance.userBubble else appearance.assistantBubble
            val name = if (user) "用户" else "助手"
            fun change(transform: (ChatBubble) -> ChatBubble) = onChange {
                if (user) it.copy(userBubble = transform(it.userBubble))
                else it.copy(assistantBubble = transform(it.assistantBubble))
            }
            item(headlineContent = { Text("${name}气泡材质") }, supportingContent = {
                Text(if (!bubble.visible) "无色" else bubble.stack?.let { "${it.layers.size} 个图层${if (it.glass) " · 玻璃背景" else ""}" } ?: bubble.material.label())
            }, trailingContent = {
                TextButton(onClick = { editingUser = user }) { Text("编辑") }
            })
            item(headlineContent = { Text("${name}气泡圆角") }, supportingContent = {
                AppearanceSlider(bubble.geometry.radiusDp, 0f..48f) { value ->
                    change { it.copy(geometry = it.geometry.copy(radiusDp = value)) }
                }
            })
            item(headlineContent = { Text("${name}气泡内边距") }, supportingContent = {
                AppearanceSlider(bubble.geometry.paddingHorizontalDp, 0f..32f) { value ->
                    change { it.copy(geometry = it.geometry.copy(paddingHorizontalDp = value, paddingVerticalDp = value)) }
                }
            })
            item(headlineContent = { Text("${name}气泡尾角") }, supportingContent = {
                Column {
                    val tail = bubble.geometry.tailCornerDp
                    Text(
                        if (tail == null) "对称四角"
                        else "${tail.toInt()} dp（收紧对齐侧下角）"
                    )
                    Slider(
                        value = tail ?: bubble.geometry.radiusDp,
                        valueRange = 0f..bubble.geometry.radiusDp.coerceAtLeast(1f),
                        onValueChange = { value ->
                            change {
                                it.copy(geometry = it.geometry.copy(
                                    // 收到与圆角同值即视为对称，避免残留无视觉差异的尾角值
                                    tailCornerDp = value.takeIf { v -> v < bubble.geometry.radiusDp - 0.5f },
                                ))
                            }
                        },
                    )
                }
            })
            item(headlineContent = { Text("${name}气泡最大宽度") }, supportingContent = {
                Column {
                    Text("${(bubble.geometry.maxWidthFraction * 100).toInt()}%")
                    Slider(
                        value = bubble.geometry.maxWidthFraction,
                        valueRange = 0.5f..1f,
                        onValueChange = { value ->
                            change { it.copy(geometry = it.geometry.copy(maxWidthFraction = value)) }
                        },
                    )
                }
            })
        }
        item(headlineContent = { Text("助手气泡不透明度") }, supportingContent = {
            Column {
                Text("${(appearance.assistantBubble.opacity * 100).toInt()}%")
                Slider(value = appearance.assistantBubble.opacity, onValueChange = { value ->
                    onChange { it.copy(assistantBubble = it.assistantBubble.copy(opacity = value)) }
                }, valueRange = 0f..1f)
            }
        })
    }
    editingUser?.let { user ->
        BubbleMaterialEditor(
            title = if (user) "用户气泡" else "助手气泡",
            initial = if (user) appearance.userBubble else appearance.assistantBubble,
            onSave = { value ->
                onChange { if (user) it.copy(userBubble = value) else it.copy(assistantBubble = value) }
                editingUser = null
            },
            onDismiss = { editingUser = null },
        )
    }
    if (editingTypography) {
        TypographyGradientEditor(initial = appearance.typography.gradient, onSave = { gradient ->
            onChange { it.copy(typography = it.typography.copy(gradient = gradient)) }
            editingTypography = false
        }, onDismiss = { editingTypography = false })
    }
}

@Composable
private fun AppearanceSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("${value.toInt()} dp")
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
