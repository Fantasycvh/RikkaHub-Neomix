package app.androidrik.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.FileImport
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.UsageDisplayMode
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FileUtils
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.data.model.ChatAppearance
import app.androidrik.rikkahub.data.model.withChatAppearance
import app.androidrik.rikkahub.ui.theme.ChatAppearanceRegistry
import app.androidrik.rikkahub.ui.theme.rememberChatAppearance
import app.androidrik.rikkahub.ui.theme.resolveChatAppearance
import app.androidrik.rikkahub.ui.pages.setting.components.ChatAppearanceSettings
import app.androidrik.rikkahub.ui.pages.setting.components.AppSkinSettings
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.data.model.AppSkinOverrides
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.rememberChatFontFamily
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.io.File
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.ui.text.style.TextOverflow
import app.androidrik.rikkahub.data.datastore.AvatarFrameStyle
import app.androidrik.rikkahub.data.datastore.ChainOfThoughtStyle
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.datastore.AvatarFrameParams
import app.androidrik.rikkahub.data.datastore.ChainOfThoughtCustomStyle
import app.androidrik.rikkahub.data.datastore.AvatarFrameScheme
import app.androidrik.rikkahub.data.datastore.ChainOfThoughtScheme
import app.androidrik.rikkahub.ui.components.ai.useCropLauncher
import app.androidrik.rikkahub.ui.components.ui.drawAvatarFrameParams
import app.androidrik.rikkahub.ui.pages.setting.components.AvatarFrameParamsSheet
import app.androidrik.rikkahub.ui.pages.setting.components.ChainOfThoughtCustomSheet
import app.androidrik.rikkahub.ui.pages.setting.components.ExportDisplaySchemeSheet
import app.androidrik.rikkahub.ui.pages.setting.components.label
import app.androidrik.rikkahub.data.export.ExportDisplayScheme
import app.androidrik.rikkahub.data.export.previewExportModelLabel
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import org.koin.compose.koinInject

@Composable
fun SettingPreferencesUIPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val appearance = rememberChatAppearance(displaySetting)
    val chatFontFamily = rememberChatFontFamily(displaySetting)
    val skinSpacing = LocalAppSkin.current.skin.spacing

    fun updateAppearance(transform: (ChatAppearance) -> ChatAppearance) {
        displaySetting = displaySetting.withChatAppearance(transform(appearance))
        vm.updateSettings { current ->
            current.copy(displaySetting = current.displaySetting.withChatAppearance(
                transform(resolveChatAppearance(current.displaySetting, ChatAppearanceRegistry.shared.presets.value))
            ))
        }
    }

    fun updateDisplaySetting(transform: (DisplaySetting) -> DisplaySetting) {
        displaySetting = transform(displaySetting)
        vm.updateSettings { current -> current.copy(displaySetting = transform(current.displaySetting)) }
    }

    // 输入栏背景图选择：复用助手背景的 managed 目录（backgrounds/），防「文件管理」
    // 清理误删（同 #2127 语义）。选图后先过 ImageCropDialog 自由裁剪（aspectRatio=null，
    // 用户自行决定取景——输入栏高度随内容变化，固定比例反而不贴），再落盘。
    // 声明顺序：局部函数须先声明后引用。
    val filesManager: FilesManager = koinInject()
    val inputBackgroundFailedMsg = stringResource(R.string.setting_display_page_input_background_failed)
    val launchInputBackgroundCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            val saved = filesManager.createBackgroundImage(croppedUri)
            if (saved != null) {
                updateDisplaySetting { current -> current.copy(chatInputBackgroundImage = saved.toString()) }
            } else {
                toaster.show(inputBackgroundFailedMsg, type = ToastType.Error)
            }
        },
    )
    val inputBackgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        launchInputBackgroundCrop(uri)
    }
    // 头像框素材：不裁剪——框素材是带透明外圈的正方形 PNG，裁剪反而破坏取景
    val avatarFrameImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val saved = filesManager.createAvatarFrameImage(uri)
        if (saved != null) {
            updateDisplaySetting { current -> current.copy(avatarFrameImagePath = saved.toString()) }
        } else {
            toaster.show(inputBackgroundFailedMsg, type = ToastType.Error)
        }
    }
    var showFrameParamsSheet by remember { mutableStateOf(false) }
    var showCotCustomSheet by remember { mutableStateOf(false) }
    // 方案删除二次确认（分立列表，各自持有一个待删项）
    var deletingFrameScheme by remember { mutableStateOf<AvatarFrameScheme?>(null) }
    var deletingCotScheme by remember { mutableStateOf<ChainOfThoughtScheme?>(null) }
    // 导出显示（提供商 / 模型）编辑面板与方案删除确认
    var showExportDisplaySheet by remember { mutableStateOf(false) }
    var deletingExportScheme by remember { mutableStateOf<ExportDisplayScheme?>(null) }

    val schemeSavedMsg = stringResource(R.string.setting_personalization_scheme_saved)
    val importSuccessMsg = stringResource(R.string.setting_display_page_custom_font_import_success)
    val importFailedMsg = stringResource(R.string.setting_display_page_custom_font_import_failed)
    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    importCustomChatFontInternal(context, uri)
                }
            }.onSuccess { importedFont ->
                updateAppearance { a -> a.copy(typography = a.typography.copy(
                    family = ChatFontFamily.CUSTOM,
                    customFontPath = importedFont.relativePath,
                    customFontName = importedFont.displayName,
                )) }
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }.onFailure { error ->
                toaster.show(importFailedMsg.format(error.message.orEmpty()), type = ToastType.Error)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_ui))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(skinSpacing.pageDp.dp),
            verticalArrangement = Arrangement.spacedBy(skinSpacing.sectionDp.dp)
        ) {
            item(key = "app_skin") {
                AppSkinSettings(displaySetting,
                    onSelect = { id -> updateDisplaySetting { it.copy(appSkinId = id) } },
                    onEdit = { before, after -> updateDisplaySetting { current ->
                        current.copy(appSkinOverrides = current.appSkinOverrides.withEdits(before, after))
                    } },
                    onReset = { updateDisplaySetting { it.copy(appSkinId = null, appSkinOverrides = AppSkinOverrides()) } },
                    onClearOverrides = { updateDisplaySetting { it.copy(appSkinOverrides = AppSkinOverrides()) } })
            }
            item(key = "chat_appearance") {
                ChatAppearanceSettings(
                    display = displaySetting,
                    appearance = appearance,
                    onSelectPreset = { id ->
                        displaySetting = displaySetting.copy(chatAppearancePresetId = id)
                        vm.updateSettings { current -> current.copy(displaySetting = current.displaySetting.copy(chatAppearancePresetId = id)) }
                    },
                    onChange = ::updateAppearance,
                )
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_message_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_user_avatar_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showUserAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showUserAvatar = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_assistant_bubble_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appearance.assistantBubble.visible,
                                onCheckedChange = {
                                    updateAppearance { a -> a.copy(assistantBubble = a.assistantBubble.copy(visible = it)) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_bubble_opacity_title)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Slider(
                                    value = appearance.userBubble.opacity,
                                    onValueChange = {
                                        updateAppearance { a -> a.copy(userBubble = a.userBubble.copy(opacity = it), assistantBubble = a.assistantBubble.copy(opacity = it)) }
                                    },
                                    valueRange = 0f..1.0f,
                                    steps = 8,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(text = "${(appearance.userBubble.opacity * 100).toInt()}%")
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_chat_list_model_icon_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelIcon,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showModelIcon = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_model_name_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_model_name_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showModelName,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showModelName = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_generation_source_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_generation_source_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showGenerationSource,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showGenerationSource = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_datetime_in_message_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showDateTimeInMessage,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showDateTimeInMessage = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_token_usage_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showTokenUsage,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showTokenUsage = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_thinking_content_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showThinkingContent,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showThinkingContent = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_whole_turn_folding_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_whole_turn_folding_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.wholeTurnFolding,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(wholeTurnFolding = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_collapse_thinking_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.wholeTurnFolding) {
                                    // 决策 4：整轮折叠开着时，思考收起由它接管
                                    stringResource(R.string.setting_display_page_auto_collapse_thinking_taken_over)
                                } else {
                                    stringResource(R.string.setting_display_page_auto_collapse_thinking_desc)
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoCloseThinking,
                                // 决策 4：整轮折叠开时置灰（由整轮折叠接管）
                                enabled = !displaySetting.wholeTurnFolding,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(autoCloseThinking = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_latex_rendering_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableLatexRendering,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(enableLatexRendering = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_diagram_rendering_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_diagram_rendering_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableDiagramRendering,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(enableDiagramRendering = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_chat_font_family_title)) },
                        supportingContent = {
                            Select(
                                options = ChatFontFamily.entries,
                                selectedOption = appearance.typography.family,
                                onOptionSelected = { family ->
                                    if (family == ChatFontFamily.CUSTOM && displaySetting.chatCustomFontPath.isBlank()) {
                                        fontPickerLauncher.launch(CustomFontMimeTypesUI)
                                    } else {
                                        updateAppearance { a -> a.copy(typography = a.typography.copy(family = family)) }
                                    }
                                },
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(),
                                optionToString = { it.labelUI() },
                                optionLeading = { family ->
                                    Text(
                                        text = "Aa",
                                        fontFamily = family.toFontFamilyUI(chatFontFamily),
                                    )
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_custom_font_title)) },
                        supportingContent = {
                            Text(
                                if (displaySetting.chatCustomFontName.isNotBlank()) {
                                    displaySetting.chatCustomFontName
                                } else {
                                    stringResource(R.string.setting_display_page_custom_font_not_imported)
                                }
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { fontPickerLauncher.launch(CustomFontMimeTypesUI) }
                                ) {
                                    Icon(
                                        HugeIcons.FileImport,
                                        contentDescription = stringResource(
                                            R.string.setting_display_page_custom_font_import
                                        )
                                    )
                                }
                                if (displaySetting.chatCustomFontPath.isNotBlank()) {
                                    IconButton(
                                        onClick = {
                                            deleteCustomChatFontInternal(context, displaySetting.chatCustomFontPath)
                                            updateAppearance { a -> a.copy(typography = a.typography.copy(
                                                family = ChatFontFamily.DEFAULT, customFontPath = "", customFontName = "",
                                            )) }
                                        }
                                    ) {
                                        Icon(
                                            HugeIcons.Delete02,
                                            contentDescription = stringResource(
                                                R.string.setting_display_page_custom_font_remove
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_font_size_title)) },
                        supportingContent = {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = appearance.typography.sizeRatio,
                                        onValueChange = {
                                            updateAppearance { a -> a.copy(typography = a.typography.copy(sizeRatio = it)) }
                                        },
                                        valueRange = 0.5f..2f,
                                        steps = 11,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(appearance.typography.sizeRatio * 100).toInt()}%")
                                }
                                MarkdownBlock(
                                    content = stringResource(R.string.setting_display_page_font_size_preview),
                                    style = LocalTextStyle.current.copy(
                                        fontSize = LocalTextStyle.current.fontSize * appearance.typography.sizeRatio,
                                        lineHeight = LocalTextStyle.current.lineHeight * appearance.typography.sizeRatio,
                                        fontFamily = chatFontFamily
                                    )
                                )
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_display_page_personalization_title)) },
                ) {
                    // 头像框：作用于聊天页双侧头像（用户+助手），预设绘制零资源
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_avatar_frame_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AvatarFrameStyle.entries.forEach { style ->
                                        FilterChip(
                                            selected = displaySetting.avatarFrameStyle == style,
                                            onClick = {
                                                updateDisplaySetting { current -> current.copy(avatarFrameStyle = style) }
                                                when (style) {
                                                    AvatarFrameStyle.CUSTOM_PARAMS -> showFrameParamsSheet = true
                                                    AvatarFrameStyle.CUSTOM_IMAGE -> avatarFrameImagePicker.launch("image/*")
                                                    else -> Unit
                                                }
                                            },
                                            label = { Text(avatarFrameLabel(style)) },
                                        )
                                    }
                                }
                                when (displaySetting.avatarFrameStyle) {
                                    AvatarFrameStyle.CUSTOM_IMAGE -> Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = displaySetting.avatarFrameImagePath
                                                .ifBlank { stringResource(R.string.setting_display_page_input_background_none) },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { avatarFrameImagePicker.launch("image/*") }) {
                                            Text(stringResource(R.string.setting_display_page_avatar_frame_pick_image))
                                        }
                                        if (displaySetting.avatarFrameImagePath.isNotBlank()) {
                                            TextButton(onClick = {
                                                updateDisplaySetting { current -> current.copy(avatarFrameImagePath = "") }
                                            }) {
                                                Text(stringResource(R.string.setting_display_page_avatar_frame_clear_image))
                                            }
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        },
                    )
                    // 输入栏背景图：与玻璃/模糊互斥（渲染端自动降级），managed 目录存图
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_input_background_title)) },
                        supportingContent = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = displaySetting.chatInputBackgroundImage
                                        .ifBlank { stringResource(R.string.setting_display_page_input_background_none) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { inputBackgroundPicker.launch("image/*") }) {
                                    Text(stringResource(R.string.setting_display_page_input_background_choose))
                                }
                                if (displaySetting.chatInputBackgroundImage.isNotBlank()) {
                                    TextButton(onClick = {
                                        updateDisplaySetting { current -> current.copy(chatInputBackgroundImage = "") }
                                    }) {
                                        Text(stringResource(R.string.setting_display_page_input_background_clear))
                                    }
                                }
                            }
                        },
                    )
                    // 思维链容器样式
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_cot_style_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SegmentedPill(
                                    options = ChainOfThoughtStyle.entries.toList(),
                                    selectedOption = displaySetting.chainOfThoughtStyle,
                                    onOptionSelected = {
                                        updateDisplaySetting { current -> current.copy(chainOfThoughtStyle = it) }
                                        if (it == ChainOfThoughtStyle.CUSTOM) showCotCustomSheet = true
                                    },
                                    optionToString = { cotStyleLabel(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (displaySetting.chainOfThoughtStyle == ChainOfThoughtStyle.CUSTOM) {
                                    TextButton(onClick = { showCotCustomSheet = true }) {
                                        Text(stringResource(R.string.setting_display_page_avatar_frame_edit_params))
                                    }
                                }
                            }
                        },
                    )
                }
            }

            // 已保存的方案（分立列表）：点行应用并切到自定义档，尾部按钮删除
            if (settings.avatarFrameSchemes.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_display_page_avatar_frame_schemes_title)) },
                    ) {
                        settings.avatarFrameSchemes.forEach { scheme ->
                            item(
                                onClick = {
                                    updateDisplaySetting { current -> current.copy(
                                            avatarFrameStyle = AvatarFrameStyle.CUSTOM_PARAMS,
                                            avatarFrameParams = scheme.params,
                                        ) }
                                },
                                headlineContent = { Text(scheme.name) },
                                leadingContent = { AvatarFrameSchemePreview(scheme.params) },
                                trailingContent = {
                                    IconButton(onClick = { deletingFrameScheme = scheme }) {
                                        Icon(HugeIcons.Delete02, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (settings.cotStyleSchemes.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = { Text(stringResource(R.string.setting_display_page_cot_schemes_title)) },
                    ) {
                        settings.cotStyleSchemes.forEach { scheme ->
                            item(
                                onClick = {
                                    updateDisplaySetting { current -> current.copy(
                                            chainOfThoughtStyle = ChainOfThoughtStyle.CUSTOM,
                                            chainOfThoughtCustomStyle = scheme.style,
                                        ) }
                                },
                                headlineContent = { Text(scheme.name) },
                                leadingContent = { CotSchemePreview(scheme.style) },
                                trailingContent = {
                                    IconButton(onClick = { deletingCotScheme = scheme }) {
                                        Icon(HugeIcons.Delete02, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_wrap_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(codeBlockAutoWrap = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_code_block_auto_collapse_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(codeBlockAutoCollapse = it) }
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_line_numbers_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showLineNumbers,
                                onCheckedChange = {
                                    updateDisplaySetting { current -> current.copy(showLineNumbers = it) }
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_usage_display_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_usage_mode_title)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.setting_display_page_usage_mode_desc))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    UsageDisplayMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.usageDisplayMode == mode,
                                            onClick = {
                                                vm.updateSettings(settings.copy(usageDisplayMode = mode))
                                            },
                                            label = {
                                                Text(
                                                    when (mode) {
                                                        UsageDisplayMode.USED -> stringResource(R.string.setting_provider_page_usage_used)
                                                        UsageDisplayMode.REMAINING -> stringResource(R.string.setting_provider_page_usage_remaining)
                                                    }
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }

            // 导出显示（提供商 / 模型）：全局配置，PNG / Markdown / HTML 共用；JSON 归档不受影响
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_display_page_export_model_title)) },
                ) {
                    item(
                        onClick = { showExportDisplaySheet = true },
                        headlineContent = {
                            Text(stringResource(R.string.setting_display_page_export_model_mode_title))
                        },
                        supportingContent = {
                            // 多个条件内容必须 Column 包一层：ListItem 的 supportingContent 只渲染首个直接子节点
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(
                                        R.string.setting_display_page_export_model_summary,
                                        settings.exportDisplayConfig.mode.label(),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = previewExportModelLabel(settings.exportDisplayConfig)
                                        .ifBlank {
                                            stringResource(R.string.setting_display_page_export_model_preview_hidden)
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.setting_display_page_export_model_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = {
                            TextButton(onClick = { showExportDisplaySheet = true }) {
                                Text(stringResource(R.string.setting_display_page_export_model_edit))
                            }
                        },
                    )
                }
            }

            // 已保存的导出显示方案：点行套用，尾部按钮删除（方案不是真相源，删档不改当前配置）
            if (settings.exportDisplaySchemes.isNotEmpty()) {
                item {
                    CardGroup(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        title = {
                            Text(stringResource(R.string.setting_display_page_export_model_schemes_title))
                        },
                    ) {
                        settings.exportDisplaySchemes.forEach { scheme ->
                            item(
                                onClick = {
                                    vm.updateSettings { it.copy(exportDisplayConfig = scheme.config) }
                                },
                                headlineContent = { Text(scheme.name) },
                                supportingContent = {
                                    Text(
                                        text = previewExportModelLabel(scheme.config).ifBlank {
                                            stringResource(R.string.setting_display_page_export_model_preview_hidden)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    IconButton(onClick = { deletingExportScheme = scheme }) {
                                        Icon(HugeIcons.Delete02, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFrameParamsSheet) {
        val current = displaySetting.avatarFrameParams ?: AvatarFrameParams()
        AvatarFrameParamsSheet(
            params = current,
            onSave = {
                updateDisplaySetting { current -> current.copy(avatarFrameParams = it) }
                showFrameParamsSheet = false
            },
            onSaveScheme = { name, params ->
                vm.updateSettings {
                    it.copy(avatarFrameSchemes = it.avatarFrameSchemes + AvatarFrameScheme(name = name, params = params))
                }
                toaster.show(schemeSavedMsg)
            },
            onDismiss = { showFrameParamsSheet = false },
        )
    }
    if (showCotCustomSheet) {
        val current = displaySetting.chainOfThoughtCustomStyle ?: ChainOfThoughtCustomStyle()
        ChainOfThoughtCustomSheet(
            style = current,
            onSave = {
                updateDisplaySetting { current -> current.copy(
                        chainOfThoughtCustomStyle = it,
                        // 保存即代表处于自定义档语义；档位仍由用户显式切换
                    ) }
                showCotCustomSheet = false
            },
            onSaveScheme = { name, style ->
                vm.updateSettings {
                    it.copy(cotStyleSchemes = it.cotStyleSchemes + ChainOfThoughtScheme(name = name, style = style))
                }
                toaster.show(schemeSavedMsg)
            },
            onDismiss = { showCotCustomSheet = false },
        )
    }

    deletingFrameScheme?.let { scheme ->
        SchemeDeleteDialog(
            name = scheme.name,
            onConfirm = {
                vm.updateSettings { settings ->
                    settings.copy(avatarFrameSchemes = settings.avatarFrameSchemes.filterNot { it.id == scheme.id })
                }
                deletingFrameScheme = null
            },
            onDismiss = { deletingFrameScheme = null },
        )
    }
    deletingCotScheme?.let { scheme ->
        SchemeDeleteDialog(
            name = scheme.name,
            onConfirm = {
                vm.updateSettings { settings ->
                    settings.copy(cotStyleSchemes = settings.cotStyleSchemes.filterNot { it.id == scheme.id })
                }
                deletingCotScheme = null
            },
            onDismiss = { deletingCotScheme = null },
        )
    }
    if (showExportDisplaySheet) {
        ExportDisplaySchemeSheet(
            config = settings.exportDisplayConfig,
            onSave = { config ->
                vm.updateSettings { it.copy(exportDisplayConfig = config) }
                showExportDisplaySheet = false
            },
            onSaveScheme = { name, config ->
                vm.updateSettings {
                    // 同名 = 覆盖更新（用户改完方案可以直接存回原名，不会攒出一堆同名档）
                    val existing = it.exportDisplaySchemes.find { scheme -> scheme.name == name }
                    if (existing != null) {
                        it.copy(
                            exportDisplaySchemes = it.exportDisplaySchemes.map { scheme ->
                                if (scheme.id == existing.id) scheme.copy(config = config) else scheme
                            }
                        )
                    } else {
                        it.copy(
                            exportDisplaySchemes = it.exportDisplaySchemes +
                                ExportDisplayScheme(name = name, config = config)
                        )
                    }
                }
                toaster.show(schemeSavedMsg)
            },
            onDismiss = { showExportDisplaySheet = false },
        )
    }
    deletingExportScheme?.let { scheme ->
        SchemeDeleteDialog(
            name = scheme.name,
            onConfirm = {
                vm.updateSettings { settings ->
                    settings.copy(
                        exportDisplaySchemes = settings.exportDisplaySchemes.filterNot { it.id == scheme.id }
                    )
                }
                deletingExportScheme = null
            },
            onDismiss = { deletingExportScheme = null },
        )
    }
}

@Composable
private fun SchemeDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_personalization_scheme_delete_title)) },
        text = { Text(stringResource(R.string.setting_personalization_scheme_delete_message, name)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.setting_personalization_scheme_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun AvatarFrameSchemePreview(params: AvatarFrameParams) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = Modifier.size(32.dp)) {
        drawAvatarFrameParams(params, primary, tertiary)
    }
}

/** 缩略示意：容器色圆角块 + 左缘强调色竖线（对应思维链自定义的三项核心观感）。 */
@Composable
private fun CotSchemePreview(style: ChainOfThoughtCustomStyle) {
    val container = style.containerColorHex.toComposeColorOrNull()
        ?: MaterialTheme.colorScheme.surfaceContainerLow
    val accent = style.accentColorHex.toComposeColorOrNull() ?: MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(32.dp)) {
        drawRoundRect(color = container, size = size, cornerRadius = CornerRadius(6.dp.toPx()))
        drawLine(
            color = accent,
            start = Offset(7.dp.toPx(), 7.dp.toPx()),
            end = Offset(7.dp.toPx(), size.height - 7.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private val CustomFontMimeTypesUI = arrayOf(
    "font/*",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

private val CustomFontExtensionsUI = setOf("ttf", "otf", "ttc")

private data class ImportedChatFontUI(
    val relativePath: String,
    val displayName: String,
)

@Composable
private fun ChatFontFamily.labelUI(): String = when (this) {
    ChatFontFamily.DEFAULT -> stringResource(R.string.setting_display_page_chat_font_family_default)
    ChatFontFamily.SERIF -> stringResource(R.string.setting_display_page_chat_font_family_serif)
    ChatFontFamily.MONOSPACE -> stringResource(R.string.setting_display_page_chat_font_family_monospace)
    ChatFontFamily.CUSTOM -> stringResource(R.string.setting_display_page_chat_font_family_custom)
}

private fun ChatFontFamily.toFontFamilyUI(customFontFamily: FontFamily): FontFamily = when (this) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> customFontFamily
}

private fun importCustomChatFontInternal(context: Context, uri: Uri): ImportedChatFontUI {
    val displayName = FileUtils.getFileNameFromUri(context, uri)?.takeIf { it.isNotBlank() } ?: "custom_font"
    val extension = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it in CustomFontExtensionsUI }
        ?: "ttf"
    val fontDir = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
    val targetFile = File(fontDir, "chat_font.${System.currentTimeMillis()}.$extension")
    val tempFile = File(fontDir, "chat_font_import.tmp")

    try {
        tempFile.delete()
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open selected font")

        runCatching {
            Typeface.createFromFile(tempFile)
        }.onFailure { error ->
            throw IllegalArgumentException(error.message ?: "Invalid font file", error)
        }

        replaceCustomChatFontInternal(fontDir, tempFile, targetFile)
    } catch (error: Throwable) {
        tempFile.delete()
        throw error
    }

    val relativePath = FileUtils.getRelativePathInFilesDir(context.filesDir, targetFile)
        ?: "${FileFolders.FONTS}/${targetFile.name}"
    return ImportedChatFontUI(relativePath = relativePath, displayName = displayName)
}

private fun replaceCustomChatFontInternal(fontDir: File, tempFile: File, targetFile: File) {
    val existingFiles = fontDir.listFiles { file ->
        file.isFile && file.name.startsWith("chat_font.") && file != tempFile
    }?.toList().orEmpty()
    val backups = existingFiles.map { file ->
        file to File(fontDir, "previous_${file.name}").also { it.delete() }
    }

    try {
        backups.forEach { (file, backup) ->
            check(file.renameTo(backup)) { "Unable to prepare existing font for replacement" }
        }
        check(tempFile.renameTo(targetFile)) { "Unable to save selected font" }
        backups.forEach { (_, backup) -> backup.delete() }
    } catch (error: Throwable) {
        tempFile.delete()
        backups.forEach { (file, backup) ->
            if (!file.exists() && backup.exists()) {
                backup.renameTo(file)
            }
        }
        throw error
    }
}

private fun deleteCustomChatFontInternal(context: Context, relativePath: String) {
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return
    val fontFile = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return
    if (fontFile.path.startsWith("${filesDir.path}${File.separator}")) {
        fontFile.delete()
    }
}

@Composable
private fun avatarFrameLabel(style: AvatarFrameStyle): String = stringResource(
    when (style) {
        AvatarFrameStyle.NONE -> R.string.setting_display_page_avatar_frame_none
        AvatarFrameStyle.RING -> R.string.setting_display_page_avatar_frame_ring
        AvatarFrameStyle.GRADIENT -> R.string.setting_display_page_avatar_frame_gradient
        AvatarFrameStyle.DOUBLE -> R.string.setting_display_page_avatar_frame_double
        AvatarFrameStyle.DASHED -> R.string.setting_display_page_avatar_frame_dashed
        AvatarFrameStyle.GLOW -> R.string.setting_display_page_avatar_frame_glow
        AvatarFrameStyle.CUSTOM_IMAGE -> R.string.setting_display_page_avatar_frame_image
        AvatarFrameStyle.CUSTOM_PARAMS -> R.string.setting_display_page_avatar_frame_custom
    }
)

@Composable
private fun cotStyleLabel(style: ChainOfThoughtStyle): String = stringResource(
    when (style) {
        ChainOfThoughtStyle.CARD -> R.string.setting_display_page_cot_style_card
        ChainOfThoughtStyle.QUOTE -> R.string.setting_display_page_cot_style_quote
        ChainOfThoughtStyle.MINIMAL -> R.string.setting_display_page_cot_style_minimal
        ChainOfThoughtStyle.CUSTOM -> R.string.setting_display_page_cot_style_custom
    }
)
