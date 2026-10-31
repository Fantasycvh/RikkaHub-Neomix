package app.androidrik.rikkahub.ui.pages.translator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import app.androidrik.rikkahub.data.repository.TranslationHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val historyRepository: TranslationHistoryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    // 翻译状态
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    // 输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    // 翻译结果
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    // 翻译目标语言
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage: StateFlow<Locale> = _targetLanguage

    /**
     * 翻译历史（最近 [TranslationHistoryRepository.MAX_HISTORY_COUNT] 条，倒序）。
     * 页面不可见时停订 5s，避免后台一直持有数据库查询。
     */
    val history: StateFlow<List<TranslationHistoryEntity>> =
        historyRepository.history().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 当前任务
    private var currentJob: Job? = null

    // 划词弹窗进窗写入一次源文本：守卫放 VM 而不是 rememberSaveable——进程死亡重建后
    // rememberSaveable 会恢复「已初始化」，而 VM 是新的，输入会永远空着
    private var inputInitialized = false

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /** 划词弹窗的进窗写入（幂等）；完整翻译页不经过这里 */
    fun initializeInputText(text: String) {
        if (inputInitialized) return
        inputInitialized = true
        _inputText.value = text
    }

    fun updateTargetLanguage(language: Locale) {
        _targetLanguage.value = language
    }

    fun translate(settingsOverride: Settings? = null) {
        val sourceText = _inputText.value
        if (sourceText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        // 先建 job 再启动：finally 里要拿 currentJob 比较，才能避免「被新任务替换掉时
        // 旧任务把新任务的 translating 改回 false」以及把取消当错误弹出来
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // settingsOverride：划词弹窗改模型后 settingsStore 还没写完，直接带上本次要用的值
            val settingsSnapshot = settingsOverride ?: settings.value
            val targetLanguage = _targetLanguage.value
            try {
                generationHandler.translateText(
                    settings = settingsSnapshot,
                    sourceText = sourceText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation in real-time
                    _translatedText.value = translatedText
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // 只有拿到非空最终结果才留档：取消 / 报错 / 空响应都不该进历史
                val result = _translatedText.value
                if (result.isNotBlank()) {
                    runCatching {
                        historyRepository.add(
                            sourceText = sourceText,
                            translatedText = result,
                            targetLanguage = targetLanguage.toLanguageTag(),
                            modelId = settingsSnapshot.translateModeId.toString(),
                        )
                    }.onFailure { error ->
                        // 历史落库失败不该让用户以为翻译失败：只记日志，不弹 toast
                        Log.w(TAG, "failed to save translation history", error)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                errorFlow.emit(e)
            } finally {
                if (currentJob == coroutineContext[Job]) {
                    _translating.value = false
                }
            }
        }
        currentJob = job
        job.start()
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        currentJob = null
        _translating.value = false
    }

    /** 把一条历史放回输入框 / 结果区，并切回当时的目标语言（方便再翻或改写） */
    fun restoreHistory(entry: TranslationHistoryEntity) {
        cancelTranslation()
        _inputText.value = entry.sourceText
        _translatedText.value = entry.translatedText
        _targetLanguage.value = Locale.forLanguageTag(entry.targetLanguage)
    }

    fun deleteHistory(entry: TranslationHistoryEntity) {
        viewModelScope.launch {
            historyRepository.delete(entry.id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clear()
        }
    }
}
