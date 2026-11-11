package app.androidrik.rikkahub.data.ai.tools.local

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import app.androidrik.ai.core.TOOL_NO_APPROVAL
import app.androidrik.ai.core.Tool
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.phoneuse.PhoneUseManager
import app.androidrik.rikkahub.phoneuse.isFloatingChatWindowOn
import app.androidrik.tts.provider.TTSManager

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val phoneUseManager: PhoneUseManager,
    private val filesManager: FilesManager,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val chartDisplayTool by lazy { buildChartDisplayTool() }

    val todoWriteTool by lazy { buildTodoWriteTool() }

    val phoneUseTool by lazy {
        buildPhoneUseTool(
            ownPackageName = context.packageName,
            manager = phoneUseManager,
            isFloatingWindowOn = { isFloatingChatWindowOn(context, settingsStore.settingsFlow.value) },
            isDeviceLocked = {
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                    !context.getSystemService(PowerManager::class.java).isInteractive
            },
            userBlockedPackages = {
                settingsStore.settingsFlow.value.displaySetting.phoneUseBlockedPackages
            },
            saveScreenshot = { bytes ->
                // 截图可能含敏感屏幕内容：落独立目录，不进备份（见 FileFolders.PHONE_USE）
                filesManager.createChatFilesByByteArrays(
                    byteArrays = listOf(bytes),
                    displayName = "screenshot.jpg",
                    mimeType = "image/jpeg",
                    folder = FileFolders.PHONE_USE,
                ).first().toString()
            },
        )
    }

    /**
     * @param phoneUseApproval 助手的 Phone Use 审批开关；false 时整个工具不做审批
     * @param allowPhoneUse 来源门控：定时任务/子代理/Web 远程会话传 false，装配期直接不给
     */
    fun getTools(
        options: List<LocalToolOption>,
        phoneUseApproval: Boolean = true,
        allowPhoneUse: Boolean = true,
    ): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) {
            tools.add(chartDisplayTool)
        }
        if (options.contains(LocalToolOption.TodoPlan)) {
            tools.add(todoWriteTool)
        }
        // 不支持的渠道和系统版本上，之前在别处开过（比如导入的备份）也不提供
        if (options.contains(LocalToolOption.PhoneUse) && allowPhoneUse && PhoneUseManager.isSupported) {
            tools.add(if (phoneUseApproval) phoneUseTool else phoneUseTool.copy(needsApproval = TOOL_NO_APPROVAL))
        }
        return tools
    }
}
