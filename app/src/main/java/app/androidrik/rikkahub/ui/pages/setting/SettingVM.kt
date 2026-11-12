package app.androidrik.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    /**
     * 设置写入的**取消安全** scope（与进程同寿命）。
     *
     * 为什么不能用 `viewModelScope`：设置页的文本域用「本地权威 + 去抖单向写出 + 离页补写」
     * （`rememberPersistedTextFieldState`，AGENTS「TextField Host Rules」第 4 条）。去抖窗口
     * 内按返回时，补写发生在组合销毁、ViewModel 也可能 `onCleared()` 的时刻；
     * `viewModelScope` 会被取消 ⇒ 最后几个字符静默丢失。AppScope 保证仍能落盘
     *（`AssistantDetailVM` / `SettingWebPage` 是仓内同款先例）。
     */
    private val appScope: AppScope,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        appScope.launch {
            settingsStore.update(settings)
        }
    }

    /** 读-改-写：并发/快速连点时基于**最新**快照修改，不会用过期 settings 覆盖别人的改动。 */
    fun updateSettings(transform: (Settings) -> Settings) {
        appScope.launch {
            settingsStore.update(transform)
        }
    }
}
