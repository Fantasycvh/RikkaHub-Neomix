package app.androidrik.rikkahub.ui.pages.setting.search

import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen

/**
 * 设置项搜索索引（人工维护）。
 *
 * 新增/改名设置项时在这里补一条，否则搜索不到。条目是「页面 + 分组 + 标题」三元组：
 * - [SettingSearchEntry.route] 决定点结果跳到哪一页；
 * - [SettingSearchEntry.pageTitle] / [SettingSearchEntry.section] 用于结果列表显示「页面 › 分组」；
 * - [SettingSearchEntry.keywords] 补中英别名与拼音，让「theme」「zhuti」「皮肤」都能命中主题。
 *
 * 顺序 = 页面顺序（匹配同分时靠这个保持分组稳定），不要按字母排序。
 * 只放用户能改/能点的设置项；只读展示行、提示文案不放进来。
 */
object SettingSearchIndex {

    private fun entry(
        title: Int,
        route: Screen,
        pageTitle: Int = title,
        description: Int? = null,
        section: Int? = null,
        keywords: List<String> = emptyList(),
    ) = SettingSearchEntry(
        id = "%d/%d".format(route.hashCode(), title),
        title = title,
        description = description,
        pageTitle = pageTitle,
        section = section,
        route = route,
        keywords = keywords,
    )

    /** 全部可搜索设置项。 */
    val entries: List<SettingSearchEntry> = listOf(
        entry(
            title = R.string.about_page_title,
            route = Screen.SettingAbout,
            keywords = listOf("关于", "关于我们")
        ),
        entry(
            title = R.string.about_page_github,
            route = Screen.SettingAbout,
            pageTitle = R.string.about_page_title
        ),
        entry(
            title = R.string.about_page_license,
            route = Screen.SettingAbout,
            pageTitle = R.string.about_page_title
        ),
        entry(
            title = R.string.setting_files_page_title,
            route = Screen.SettingFiles,
            keywords = listOf("聊天存储", "文件管理", "chat storage", "wenjian")
        ),
        entry(
            title = R.string.setting_storage_page_title,
            route = Screen.SettingStorage,
            keywords = listOf(
                "存储管理", "存储空间", "缓存", "清理缓存", "storage", "cache", "huancun"
            )
        ),
        entry(
            title = R.string.setting_page_kb_title,
            route = Screen.SettingKnowledgeBase,
            keywords = listOf("知识库", "知识库管理", "rag", "knowledge base", "zhishiku")
        ),
        entry(
            title = R.string.setting_mcp_page_title,
            route = Screen.SettingMcp,
            keywords = listOf("mcp", "工具服务器")
        ),
        entry(
            title = R.string.setting_mcp_page_dynamic_schema_title,
            description = R.string.setting_mcp_page_dynamic_schema_desc,
            route = Screen.SettingMcp,
            pageTitle = R.string.setting_mcp_page_title
        ),
        entry(
            title = R.string.setting_page_media_and_speech,
            route = Screen.SettingMedia,
            keywords = listOf("图片", "图像", "视频", "语音生成", "媒体")
        ),
        entry(
            title = R.string.backup_page_s3_endpoint,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_s3_access_key_id,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_s3_secret_access_key,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_s3_bucket,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_s3_path_style,
            description = R.string.backup_page_s3_path_style_desc,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_s3_region,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.backup_page_test_connection,
            description = R.string.setting_media_page_upload_test_hint,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_asset_storage
        ),
        entry(
            title = R.string.setting_model_page_title,
            route = Screen.SettingModels
        ),
        entry(
            title = R.string.setting_model_page_enable_auto_title,
            description = R.string.setting_model_page_enable_auto_title_desc,
            route = Screen.SettingModels,
            pageTitle = R.string.setting_model_page_title,
            section = R.string.setting_model_page_title_model
        ),
        entry(
            title = R.string.setting_model_page_auto_compress_enable,
            description = R.string.setting_model_page_auto_compress_enable_desc,
            route = Screen.SettingModels,
            pageTitle = R.string.setting_model_page_title,
            section = R.string.setting_model_page_auto_compress
        ),
        entry(
            title = R.string.setting_model_page_enable_suggestion,
            route = Screen.SettingModels,
            pageTitle = R.string.setting_model_page_title,
            section = R.string.setting_model_page_context_length_override
        ),
        entry(
            title = R.string.settings,
            route = Screen.Setting,
            keywords = listOf("设置", "settings")
        ),
        entry(
            title = R.string.setting_page_color_mode,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_general_settings,
            keywords = listOf("深色", "浅色", "dark mode", "light mode", "夜间模式", "yese")
        ),
        entry(
            title = R.string.setting_page_preferences,
            description = R.string.setting_page_preferences_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_general_settings
        ),
        entry(
            title = R.string.setting_page_assistant,
            description = R.string.setting_page_assistant_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_general_settings
        ),
        entry(
            title = R.string.setting_page_extensions,
            description = R.string.setting_page_extensions_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_general_settings
        ),
        entry(
            title = R.string.setting_page_default_model,
            description = R.string.setting_page_default_model_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_ai_and_generation,
            keywords = listOf("模型", "model", "默认模型")
        ),
        entry(
            title = R.string.setting_page_providers,
            description = R.string.setting_page_providers_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_ai_and_generation,
            keywords = listOf("api key", "apikey", "服务商", "provider")
        ),
        entry(
            title = R.string.setting_page_media_and_speech,
            description = R.string.setting_page_media_and_speech_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_ai_and_generation
        ),
        entry(
            title = R.string.setting_page_search_service,
            description = R.string.setting_page_search_service_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_ai_and_generation,
            keywords = listOf("联网搜索", "web search", "搜索服务", "联网")
        ),
        entry(
            title = R.string.setting_page_kb,
            description = R.string.setting_page_kb_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_ai_and_generation,
            keywords = listOf("知识库", "rag", "knowledge base", "向量")
        ),
        entry(
            title = R.string.setting_page_mcp,
            description = R.string.setting_page_mcp_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_connection_and_data
        ),
        entry(
            title = R.string.setting_page_web_server,
            description = R.string.setting_page_web_server_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_connection_and_data
        ),
        entry(
            title = R.string.setting_page_data_backup,
            description = R.string.setting_page_data_backup_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_connection_and_data,
            keywords = listOf("备份", "backup", "导出", "导入", "还原")
        ),
        entry(
            title = R.string.setting_page_chat_storage,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_connection_and_data,
            keywords = listOf("存储", "清理", "storage", "占空间")
        ),
        entry(
            title = R.string.setting_page_about,
            description = R.string.setting_page_about_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_about,
            keywords = listOf("关于", "版本", "about", "version")
        ),
        entry(
            title = R.string.setting_page_request_logs,
            description = R.string.setting_page_request_logs_desc,
            route = Screen.Setting,
            pageTitle = R.string.settings,
            section = R.string.setting_page_about,
            keywords = listOf("日志", "log", "请求记录")
        ),
        entry(
            title = R.string.setting_page_preferences_general,
            route = Screen.SettingPreferencesGeneral,
            keywords = listOf("通用", "general", "保活", "护栏")
        ),
        entry(
            title = R.string.setting_page_preferences_app_identity,
            route = Screen.SettingPreferencesAppIdentity,
            keywords = listOf("应用名", "显示名", "改名", "图标", "桌面", "快捷方式", "app name", "icon", "shortcut", "yingyongming")
        ),
        entry(
            title = R.string.setting_page_app_identity_display_name,
            description = R.string.setting_page_app_identity_display_name_desc,
            route = Screen.SettingPreferencesAppIdentity,
            pageTitle = R.string.setting_page_preferences_app_identity
        ),
        entry(
            title = R.string.setting_page_app_identity_launcher,
            route = Screen.SettingPreferencesAppIdentity,
            pageTitle = R.string.setting_page_preferences_app_identity
        ),
        entry(
            title = R.string.setting_page_app_identity_shortcut,
            description = R.string.setting_page_app_identity_shortcut_desc,
            route = Screen.SettingPreferencesAppIdentity,
            pageTitle = R.string.setting_page_preferences_app_identity
        ),
        entry(
            title = R.string.setting_display_page_create_new_conversation_on_start_title,
            description = R.string.setting_display_page_create_new_conversation_on_start_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_send_on_enter_title,
            description = R.string.setting_display_page_send_on_enter_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_show_message_jumper_title,
            description = R.string.setting_display_page_show_message_jumper_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_message_jumper_position_title,
            description = R.string.setting_display_page_message_jumper_position_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_enable_auto_scroll_title,
            description = R.string.setting_display_page_enable_auto_scroll_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_use_app_icon_style_loading_indicator_title,
            description = R.string.setting_display_page_use_app_icon_style_loading_indicator_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_enable_blur_effect_title,
            description = R.string.setting_display_page_enable_blur_effect_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_enable_glass_theme_title,
            description = R.string.setting_display_page_enable_glass_theme_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_enable_top_bar_blur_title,
            description = R.string.setting_display_page_enable_top_bar_blur_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_enable_message_generation_haptic_effect_title,
            description = R.string.setting_display_page_enable_message_generation_haptic_effect_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_skip_crop_image_title,
            description = R.string.setting_display_page_skip_crop_image_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_paste_long_text_as_file_title,
            description = R.string.setting_display_page_paste_long_text_as_file_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_paste_long_text_threshold_title,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_volume_key_scroll_title,
            description = R.string.setting_display_page_volume_key_scroll_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_display_page_volume_key_scroll_ratio,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general
        ),
        entry(
            title = R.string.setting_retry_title,
            description = R.string.setting_retry_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_retry_delays_title,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_retry_keep_partial_title,
            description = R.string.setting_retry_keep_partial_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_retry_after_partial_title,
            description = R.string.setting_retry_after_partial_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_retry_auto_continue_title,
            description = R.string.setting_retry_auto_continue_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_retry_partial_chars_title,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_retry_title
        ),
        entry(
            title = R.string.setting_display_page_tts_only_read_quoted_title,
            description = R.string.setting_display_page_tts_only_read_quoted_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_tts_settings
        ),
        entry(
            title = R.string.setting_display_page_tts_read_outside_brackets_title,
            description = R.string.setting_display_page_tts_read_outside_brackets_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_tts_settings
        ),
        entry(
            title = R.string.setting_display_page_auto_play_tts_title,
            description = R.string.setting_display_page_auto_play_tts_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_tts_settings
        ),
        entry(
            title = R.string.setting_page_guardrails_loop_guard,
            description = R.string.setting_page_guardrails_loop_guard_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_rule_reminder,
            description = R.string.setting_page_guardrails_rule_reminder_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_rule_reminder_level,
            description = R.string.setting_page_guardrails_rule_reminder_level_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_turn_budget_chars,
            description = R.string.setting_page_guardrails_turn_budget_chars_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_turn_budget_steps,
            description = R.string.setting_page_guardrails_turn_budget_steps_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_subagent_concurrency,
            description = R.string.setting_page_guardrails_subagent_concurrency_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_page_guardrails_task_duration,
            description = R.string.setting_page_guardrails_task_duration_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_page_guardrails_title
        ),
        entry(
            title = R.string.setting_keep_alive_title,
            description = R.string.setting_keep_alive_desc,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_keep_alive_title
        ),
        entry(
            title = R.string.setting_keep_alive_notification_missing,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_keep_alive_title
        ),
        entry(
            title = R.string.setting_keep_alive_battery_request,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_keep_alive_title
        ),
        entry(
            title = R.string.setting_keep_alive_battery_ok,
            route = Screen.SettingPreferencesGeneral,
            pageTitle = R.string.setting_page_preferences_general,
            section = R.string.setting_keep_alive_title
        ),
        entry(
            title = R.string.setting_page_preferences_network,
            route = Screen.SettingPreferencesNetwork,
            keywords = listOf("网络", "network", "代理", "proxy", "翻墙", "科学上网", "daili")
        ),
        entry(
            title = R.string.setting_page_preferences_notification,
            route = Screen.SettingPreferencesNotification,
            keywords = listOf("通知", "notification", "推送", "tongzhi")
        ),
        entry(
            title = R.string.setting_display_page_notification_message_generated,
            description = R.string.setting_display_page_notification_message_generated_desc,
            route = Screen.SettingPreferencesNotification,
            pageTitle = R.string.setting_page_preferences_notification
        ),
        entry(
            title = R.string.setting_display_page_live_update_notification,
            description = R.string.setting_display_page_live_update_notification_desc,
            route = Screen.SettingPreferencesNotification,
            pageTitle = R.string.setting_page_preferences_notification
        ),
        entry(
            title = R.string.setting_page_preferences,
            route = Screen.SettingPreferences,
            keywords = listOf("偏好", "preferences", "通用设置")
        ),
        entry(
            title = R.string.setting_page_preferences_theme,
            description = R.string.setting_page_preferences_theme_desc,
            route = Screen.SettingPreferences,
            pageTitle = R.string.setting_page_preferences
        ),
        entry(
            title = R.string.setting_page_preferences_notification,
            description = R.string.setting_page_preferences_notification_desc,
            route = Screen.SettingPreferences,
            pageTitle = R.string.setting_page_preferences
        ),
        entry(
            title = R.string.setting_page_preferences_general,
            description = R.string.setting_page_preferences_general_desc,
            route = Screen.SettingPreferences,
            pageTitle = R.string.setting_page_preferences
        ),
        entry(
            title = R.string.setting_page_preferences_ui,
            description = R.string.setting_page_preferences_ui_desc,
            route = Screen.SettingPreferences,
            pageTitle = R.string.setting_page_preferences
        ),
        entry(
            title = R.string.setting_page_preferences_network,
            description = R.string.setting_page_preferences_network_desc,
            route = Screen.SettingPreferences,
            pageTitle = R.string.setting_page_preferences
        ),
        entry(
            title = R.string.setting_page_preferences_theme,
            route = Screen.SettingPreferencesTheme,
            keywords = listOf("主题", "theme", "配色", "颜色", "动态配色", "zhuti")
        ),
        entry(
            title = R.string.setting_page_dynamic_color,
            description = R.string.setting_page_dynamic_color_desc,
            route = Screen.SettingPreferencesTheme,
            pageTitle = R.string.setting_page_preferences_theme,
            keywords = listOf("主题色", "theme color", "取色")
        ),
        entry(
            title = R.string.setting_display_page_amoled_dark_mode_title,
            description = R.string.setting_display_page_amoled_dark_mode_desc,
            route = Screen.SettingPreferencesTheme,
            pageTitle = R.string.setting_page_preferences_theme,
            keywords = listOf("暗色", "dark", "省电", "amoled")
        ),
        entry(
            title = R.string.setting_page_preferences_ui,
            route = Screen.SettingPreferencesUI,
            keywords = listOf("界面", "ui", "外观", "字体")
        ),
        entry(
            title = R.string.setting_display_page_show_user_avatar_title,
            description = R.string.setting_display_page_show_user_avatar_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_assistant_bubble_title,
            description = R.string.setting_display_page_show_assistant_bubble_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_bubble_opacity_title,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_chat_list_model_icon_title,
            description = R.string.setting_display_page_chat_list_model_icon_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_model_name_title,
            description = R.string.setting_display_page_show_model_name_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_generation_source_title,
            description = R.string.setting_display_page_show_generation_source_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_datetime_in_message_title,
            description = R.string.setting_display_page_show_datetime_in_message_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_token_usage_title,
            description = R.string.setting_display_page_show_token_usage_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_thinking_content_title,
            description = R.string.setting_display_page_show_thinking_content_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_whole_turn_folding_title,
            description = R.string.setting_display_page_whole_turn_folding_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_auto_collapse_thinking_title,
            description = R.string.setting_display_page_auto_collapse_thinking_taken_over,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_enable_latex_rendering_title,
            description = R.string.setting_display_page_enable_latex_rendering_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_enable_diagram_rendering_title,
            description = R.string.setting_display_page_enable_diagram_rendering_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_chat_font_family_title,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_custom_font_title,
            description = R.string.setting_display_page_custom_font_not_imported,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_font_size_title,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_message_display_settings
        ),
        entry(
            title = R.string.setting_display_page_code_block_auto_wrap_title,
            description = R.string.setting_display_page_code_block_auto_wrap_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_code_display_settings
        ),
        entry(
            title = R.string.setting_display_page_code_block_auto_collapse_title,
            description = R.string.setting_display_page_code_block_auto_collapse_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_code_display_settings
        ),
        entry(
            title = R.string.setting_display_page_show_line_numbers_title,
            description = R.string.setting_display_page_show_line_numbers_desc,
            route = Screen.SettingPreferencesUI,
            pageTitle = R.string.setting_page_preferences_ui,
            section = R.string.setting_page_code_display_settings
        ),
        entry(
            title = R.string.setting_provider_page_title,
            route = Screen.SettingProvider,
            keywords = listOf("ai 提供商", "服务商", "api", "provider", "tigongshang")
        ),
        entry(
            title = R.string.setting_page_search_title,
            route = Screen.SettingSearch,
            keywords = listOf("搜索服务", "联网搜索", "web search", "sousuo")
        ),
        entry(
            title = R.string.speech_page_title,
            route = Screen.SettingSpeech,
            keywords = listOf("语音", "tts", "asr", "朗读", "听写", "语音识别", "语音合成", "yuyin")
        ),
        entry(
            title = R.string.setting_page_web_server,
            route = Screen.SettingWeb,
            keywords = listOf("网页服务", "web", "服务器")
        ),
        entry(
            title = R.string.setting_page_web_server_port,
            description = R.string.setting_page_web_server_port_desc,
            route = Screen.SettingWeb,
            pageTitle = R.string.setting_page_web_server
        ),
        entry(
            title = R.string.setting_page_web_server_localhost_only,
            description = R.string.setting_page_web_server_localhost_only_desc,
            route = Screen.SettingWeb,
            pageTitle = R.string.setting_page_web_server
        ),
        entry(
            title = R.string.setting_page_web_server_jwt_enable,
            description = R.string.setting_page_web_server_jwt_enable_desc,
            route = Screen.SettingWeb,
            pageTitle = R.string.setting_page_web_server
        ),
        entry(
            title = R.string.setting_page_web_server_password,
            description = R.string.setting_page_web_server_password_desc,
            route = Screen.SettingWeb,
            pageTitle = R.string.setting_page_web_server
        ),
        entry(
            title = R.string.setting_tts_page_default_playback_speed,
            description = R.string.setting_tts_page_default_playback_speed_description,
            route = Screen.SettingSpeech,
            pageTitle = R.string.speech_page_title,
            section = R.string.speech_tab_tts,
            keywords = listOf("语速", "播放速度", "朗读速度", "tts speed", "playback speed")
        ),
        entry(
            title = R.string.setting_tts_page_add_provider,
            route = Screen.SettingSpeech,
            pageTitle = R.string.speech_page_title,
            section = R.string.speech_tab_tts,
            keywords = listOf("新增语音引擎", "语音合成", "朗读", "tts", "文字转语音", "yuyin")
        ),
        entry(
            title = R.string.setting_asr_page_add_provider,
            route = Screen.SettingSpeech,
            pageTitle = R.string.speech_page_title,
            section = R.string.speech_tab_asr,
            keywords = listOf("语音识别", "听写", "录音转文字", "asr", "speech recognition")
        ),
        entry(
            title = R.string.setting_page_search_add_provider,
            route = Screen.SettingSearch,
            pageTitle = R.string.setting_page_search_title,
            keywords = listOf("新增搜索服务", "搜索提供商", "联网搜索", "web search", "搜索引擎")
        ),
        entry(
            title = R.string.setting_page_search_result_size,
            route = Screen.SettingSearch,
            pageTitle = R.string.setting_page_search_title,
            section = R.string.setting_page_search_common_options,
            keywords = listOf("搜索结果条数", "返回数量", "result size", "top-k")
        ),
        entry(
            title = R.string.setting_provider_page_add_provider,
            route = Screen.SettingProvider,
            pageTitle = R.string.setting_provider_page_title,
            keywords = listOf("新增提供商", "接入", "add provider", "tigongshang")
        ),
        entry(
            title = R.string.setting_page_export_retention,
            description = R.string.setting_page_export_retention_desc,
            route = Screen.SettingFiles,
            pageTitle = R.string.setting_files_page_title,
            keywords = listOf("留存", "保留天数", "过期", "清理", "retention", "ttl")
        ),
        entry(
            title = R.string.setting_files_page_clean_title,
            description = R.string.setting_files_page_clean_confirmation,
            route = Screen.SettingFiles,
            pageTitle = R.string.setting_files_page_title,
            keywords = listOf("清空", "删除全部", "清理缓存", "clean all")
        ),
        entry(
            title = R.string.setting_files_page_folder_upload,
            route = Screen.SettingFiles,
            pageTitle = R.string.setting_files_page_title,
            keywords = listOf("聊天文件", "附件", "上传文件", "upload")
        ),
        entry(
            title = R.string.setting_files_page_folder_exports,
            route = Screen.SettingFiles,
            pageTitle = R.string.setting_files_page_title,
            keywords = listOf("导出文件", "产物", "exports")
        ),
        entry(
            title = R.string.setting_network_proxy_title,
            route = Screen.SettingPreferencesNetwork,
            pageTitle = R.string.setting_page_preferences_network,
            keywords = listOf("代理", "代理类型", "proxy", "proxy type", "翻墙", "科学上网", "daili")
        ),
        entry(
            title = R.string.setting_provider_page_proxy_host,
            route = Screen.SettingPreferencesNetwork,
            pageTitle = R.string.setting_page_preferences_network,
            section = R.string.setting_network_proxy_title,
            keywords = listOf("代理地址", "服务器地址", "host", "proxy host")
        ),
        entry(
            title = R.string.setting_provider_page_proxy_port,
            route = Screen.SettingPreferencesNetwork,
            pageTitle = R.string.setting_page_preferences_network,
            section = R.string.setting_network_proxy_title,
            keywords = listOf("代理端口", "port", "proxy port")
        ),
        entry(
            title = R.string.setting_provider_page_proxy_username,
            route = Screen.SettingPreferencesNetwork,
            pageTitle = R.string.setting_page_preferences_network,
            section = R.string.setting_network_proxy_title,
            keywords = listOf("代理账号", "账号", "username", "proxy user")
        ),
        entry(
            title = R.string.setting_provider_page_proxy_password,
            route = Screen.SettingPreferencesNetwork,
            pageTitle = R.string.setting_page_preferences_network,
            section = R.string.setting_network_proxy_title,
            keywords = listOf("代理密码", "password", "proxy password", "proxy pass")
        ),
        entry(
            title = R.string.setting_page_kb_add,
            route = Screen.SettingKnowledgeBase,
            pageTitle = R.string.setting_page_kb_title,
            keywords = listOf("新建", "创建知识库", "知识库", "rag", "knowledge base", "zhishiku")
        ),
        entry(
            title = R.string.setting_page_kb_name,
            route = Screen.SettingKnowledgeBase,
            pageTitle = R.string.setting_page_kb_title,
            keywords = listOf("知识库名称", "名称", "name", "kb name")
        ),
        entry(
            title = R.string.setting_media_page_add_provider,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_providers,
            keywords = listOf("新增生图服务商", "生视频", "图像生成", "视频生成", "image gen", "video gen")
        ),
        entry(
            title = R.string.setting_media_page_edit_provider,
            route = Screen.SettingMedia,
            pageTitle = R.string.setting_page_media_and_speech,
            section = R.string.setting_media_page_providers,
            keywords = listOf("编辑", "edit provider")
        ),
        entry(
            title = R.string.theme_name_sakura,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("樱花", "粉色", "sakura", "pink")
        ),
        entry(
            title = R.string.theme_name_ocean,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("海洋", "蓝色", "ocean", "blue")
        ),
        entry(
            title = R.string.theme_name_spring,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("春天", "绿色", "spring", "green", "草地")
        ),
        entry(
            title = R.string.theme_name_autumn,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("秋天", "黄色", "autumn", "yellow", "秋季")
        ),
        entry(
            title = R.string.theme_name_black,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("黑色", "纯黑", "black", "dark")
        ),
        entry(
            title = R.string.theme_name_minimal,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("极简", "白色", "minimal", "white")
        ),
        entry(
            title = R.string.theme_name_claude,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_preset_themes,
            keywords = listOf("claude", "克劳德")
        ),
        entry(
            title = R.string.setting_theme_page_add_theme,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_custom_themes,
            keywords = listOf("新建主题", "创建主题", "add theme", "自定义主题")
        ),
        entry(
            title = R.string.setting_theme_page_import_theme,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            section = R.string.setting_theme_page_custom_themes,
            keywords = listOf("导入主题", "导入json", "import theme", "json")
        ),
        entry(
            title = R.string.setting_theme_page_theme_name,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            keywords = listOf("名称", "主题名", "name")
        ),
        entry(
            title = R.string.setting_theme_page_primary_color,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            keywords = listOf("主色调", "主要颜色", "primary", "color")
        ),
        entry(
            title = R.string.setting_theme_page_secondary_color,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            keywords = listOf("次要色", "辅助色", "secondary", "color")
        ),
        entry(
            title = R.string.setting_theme_page_tertiary_color,
            route = Screen.SettingTheme,
            pageTitle = R.string.setting_page_theme_setting,
            keywords = listOf("第三种颜色", "tertiary", "color")
        ),
    )
}
