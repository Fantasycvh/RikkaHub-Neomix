package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.service.KeepAliveService
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesGeneralPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_general))
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
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc)) },
                        trailingContent = {
                            Switch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = { createNewConversationOnStart = it }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_send_on_enter_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.sendOnEnter,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(sendOnEnter = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_show_message_jumper_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.showMessageJumper,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.showMessageJumper) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_display_page_message_jumper_position_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = displaySetting.messageJumperOnLeft,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                    }
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_auto_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableAutoScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableAutoScroll = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_title)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_display_page_use_app_icon_style_loading_indicator_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.useAppIconStyleLoadingIndicator,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(useAppIconStyleLoadingIndicator = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_blur_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableBlurEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableBlurEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_glass_theme_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_glass_theme_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableGlassTheme,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableGlassTheme = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_top_bar_blur_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_top_bar_blur_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableTopBarBlur,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableTopBarBlur = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableMessageGenerationHapticEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_skip_crop_image_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.skipCropImage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_as_file_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.pasteLongTextAsFile,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(pasteLongTextAsFile = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.pasteLongTextAsFile) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_paste_long_text_threshold_title)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.pasteLongTextThreshold.toFloat(),
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(pasteLongTextThreshold = it.toInt()))
                                        },
                                        valueRange = 100f..10000f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${displaySetting.pasteLongTextThreshold}")
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVolumeKeyScroll,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVolumeKeyScroll = it))
                                }
                            )
                        },
                    )
                    if (displaySetting.enableVolumeKeyScroll) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_display_page_volume_key_scroll_ratio)) },
                            supportingContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Slider(
                                        value = displaySetting.volumeKeyScrollRatio,
                                        onValueChange = {
                                            updateDisplaySetting(displaySetting.copy(volumeKeyScrollRatio = it))
                                        },
                                        valueRange = 0.25f..1.0f,
                                        steps = 2,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(text = "${(displaySetting.volumeKeyScrollRatio * 100).toInt()}%")
                                }
                            }
                        )
                    }
                }
            }

            item {
                var retrySetting by remember(settings) { mutableStateOf(settings.retrySetting) }
                // 间隔序列以文本编辑；仅合法值才落盘
                var delaysText by remember(settings) {
                    mutableStateOf(retrySetting.delaysSeconds.joinToString(","))
                }
                // #1812 半截字数阈值以文本编辑；仅合法值落盘
                var partialCharsText by remember(settings) {
                    mutableStateOf(retrySetting.partialOutputMinChars.toString())
                }

                fun updateRetrySetting(setting: RetrySetting) {
                    retrySetting = setting
                    vm.updateSettings(settings.copy(retrySetting = setting))
                }

                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_retry_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_retry_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_retry_desc)) },
                        trailingContent = {
                            Switch(
                                checked = retrySetting.enabled,
                                onCheckedChange = { enabled ->
                                    updateRetrySetting(retrySetting.copy(enabled = enabled))
                                }
                            )
                        },
                    )
                    if (retrySetting.enabled) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_retry_delays_title)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.setting_retry_delays_desc))
                                    val parsed = RetrySetting.parseDelays(delaysText)
                                    OutlinedTextField(
                                        value = delaysText,
                                        onValueChange = {
                                            delaysText = it
                                            RetrySetting.parseDelays(it)?.let { delays ->
                                                updateRetrySetting(retrySetting.copy(delaysSeconds = delays))
                                            }
                                        },
                                        isError = parsed == null,
                                        supportingText = if (parsed == null) {
                                            { Text(stringResource(R.string.setting_retry_delays_invalid)) }
                                        } else {
                                            {
                                                Text(
                                                    stringResource(R.string.setting_retry_delays_title) +
                                                        ": ${parsed.size} · " +
                                                        parsed.sum().toString() + "s"
                                                )
                                            }
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_retry_keep_partial_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_retry_keep_partial_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = retrySetting.keepPartialOnFailure,
                                    onCheckedChange = { keep ->
                                        updateRetrySetting(retrySetting.copy(keepPartialOnFailure = keep))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_retry_after_partial_title)) },
                            supportingContent = { Text(stringResource(R.string.setting_retry_after_partial_desc)) },
                            trailingContent = {
                                Switch(
                                    checked = retrySetting.retryAfterPartial,
                                    onCheckedChange = { retry ->
                                        updateRetrySetting(retrySetting.copy(retryAfterPartial = retry))
                                    }
                                )
                            },
                        )
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_retry_partial_chars_title)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.setting_retry_partial_chars_desc))
                                    OutlinedTextField(
                                        value = partialCharsText,
                                        onValueChange = { text ->
                                            // 仅数字、最多 5 位（阈值上限 99999）
                                            partialCharsText = text.filter(Char::isDigit).take(5)
                                            partialCharsText.toIntOrNull()?.let { parsedChars ->
                                                updateRetrySetting(
                                                    retrySetting.copy(partialOutputMinChars = parsedChars.coerceAtLeast(1))
                                                )
                                            }
                                        },
                                        supportingText = {
                                            Text(
                                                stringResource(
                                                    R.string.setting_retry_partial_chars_applied,
                                                    retrySetting.partialOutputMinChars
                                                )
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item {
                KeepAliveSettingGroup()
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_tts_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_only_read_quoted_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadQuoted,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadQuoted = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_tts_read_outside_brackets_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.ttsOnlyReadOutsideBrackets,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(ttsOnlyReadOutsideBrackets = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_auto_play_tts_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.autoPlayTTSAfterGeneration,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoPlayTTSAfterGeneration = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 后台保活设置分组：开关（SharedPreferences keep_alive）+ 前置条件引导。
 *
 * 电池优化白名单/通知权限状态在每次 ON_RESUME 后重新计算——用户从系统设置
 * 授权返回时状态自动刷新，无需手动重建页面。
 */
@Composable
private fun KeepAliveSettingGroup() {
    val context = LocalContext.current
    var enabled by rememberSharedPreferenceBoolean(KeepAliveService.PREF_KEY, false)

    // 从系统设置页授权返回后刷新前置条件状态
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationGranted = remember(resumeTick) { KeepAliveService.hasNotificationPermission(context) }
    val batteryIgnored = remember(resumeTick) { KeepAliveService.isIgnoringBatteryOptimizations(context) }

    CardGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.setting_keep_alive_title)) },
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_keep_alive_title)) },
            supportingContent = { Text(stringResource(R.string.setting_keep_alive_desc)) },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        KeepAliveService.setEnabled(context, checked)
                        when {
                            checked && notificationGranted -> KeepAliveService.start(context)
                            !checked -> KeepAliveService.stop(context)
                            // 开启但缺通知权限：不启动，由下面的引导行提示
                        }
                    }
                )
            },
        )
        if (enabled && !notificationGranted) {
            item(
                headlineContent = {
                    Text(
                        stringResource(R.string.setting_keep_alive_notification_missing),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        if (enabled && notificationGranted && !batteryIgnored) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_keep_alive_battery_request)) },
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(KeepAliveService.batteryOptimizationIntent(context))
                    }
                },
            )
        }
        if (enabled && batteryIgnored) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_keep_alive_battery_ok)) },
            )
        }
    }
}
