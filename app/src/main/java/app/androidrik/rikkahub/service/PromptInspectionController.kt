package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.ai.forMemoryResume
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.data.repository.MemoryFileRepository

// 拼装预览/预热控制器（批次 8 从 ChatService 拆出）：单飞、预热缓存与组装重活。
// KDoc 中的线程契约（快照在调用方上下文读、重活经 withPromptInspectionDispatcher）逐字保留。

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.ai.PromptInspectionLabels
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.data.ai.PromptInspectionTiming
import app.androidrik.rikkahub.data.ai.inspectPrompt
import app.androidrik.rikkahub.data.ai.refineTokens
import app.androidrik.rikkahub.data.ai.transformers.TransformerChainRegistry
import app.androidrik.rikkahub.data.ai.transformers.TransformerUse
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.api.PluginCapability

/**
 * 拼装预览与预热（批次 8 从 ChatService 拆出）：[inspectPromptAssembly] 供拼装预览页与
 * 容量面板调用；[prewarmPromptTokenCounts] 进入会话时预热精确计数。
 */
internal class PromptInspectionController(
    private val context: android.content.Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val pluginManager: PluginManager,
    private val mcpManager: McpManager,
    private val transformerRegistry: TransformerChainRegistry,
    private val chatToolFactory: ChatToolFactory,
    private val memoryRepository: MemoryRepository,
    private val memoryFiles: MemoryFileRepository,
    /** 会话状态流访问器（ChatService.getConversationFlow；snapshot 读取须在调用方上下文/Main）。 */
    private val conversationFlow: (Uuid) -> StateFlow<Conversation>,
) {
    // ---- 拼装预览（只读） ----

    internal class InspectionReference(private val value: Any?) {
        override fun equals(other: Any?): Boolean = other is InspectionReference && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    internal val inspectionSingleFlight = InspectionSingleFlight<List<Any>, PromptInspection?>(appScope)

    /** 已预热过的会话及其内容签名；同一签名不重复预热。 */
    internal val prewarmedTokenCounts = java.util.concurrent.ConcurrentHashMap<Uuid, String>()

    /** 每个会话的预热任务；预览打开时据此 join（无插件）或取消（有插件，避免抢核）。 */
    internal val warmJobs = java.util.concurrent.ConcurrentHashMap<Uuid, Job>()

    /** 该会话的预热是否与预览共享同一次计算。 */
    internal val warmSharedWithPreview = java.util.concurrent.ConcurrentHashMap<Uuid, Boolean>()

    /**
     * 进入会话时**只预热精确 token 计数**（不组装、不跑插件、不产生副作用）。
     *
     * 动机：拼装预览首次打开的大头是分词（长会话实测 12.7s），而进会话本身总要做一次加载。
     * 趁这个空档把同一批文本单元算热（[conversationTokenTexts] 与预览分段取法一致），
     * 用户随后打开预览/容量面板时就是缓存命中。
     *
     * 走 [appScope]：离开会话也不至于把已投入的计算丢掉；同一会话内容未变时不重复跑。
     */
    internal fun prewarmPromptTokenCounts(conversationId: Uuid, labels: PromptInspectionLabels) {
        warmJobs[conversationId]?.cancel()
        // 同步决定是否共享：预览可能在任务真正开跑前就点了进来，不能等 job 里再写这个标志
        val shared = !hasInputPluginTransformers()
        warmSharedWithPreview[conversationId] = shared
        val job = appScope.launch {
            val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull() ?: return@launch
            val conversation = runCatching { conversationFlow(conversationId).value }.getOrNull()
                ?: return@launch
            val signature = "${conversation.messageNodes.size}:${conversation.currentMessages.lastOrNull()?.id}"
            // 空会话没有可预热的文本：启动时给「新聊天」白跑一次组装纯属浪费（实测 4.3s）
            if (conversation.currentMessages.isEmpty()) return@launch
            if (prewarmedTokenCounts[conversationId] == signature) return@launch
            // 没有输入插件时，预热与预览跑的是**同一次**计算（同 key、同 labels）：
            // 用户随后点开预览直接 join 这一份，而不是自己再算一遍。
            Log.d(PERF_TAG, "prewarm start shared=$shared")
            runCatching {
                if (shared) {
                    runSharedInspection(
                        inspectionKey(conversationId, settings, conversation, labels),
                        settings, conversation, labels,
                    )
                } else {
                    // 有插件时预热不跑插件（不替用户触发副作用），只把无插件的那部分算热
                    val preview = assemblePromptInspection(
                        settings = settings,
                        conversation = conversation,
                        labels = labels,
                        approximateTokens = true,
                        includePluginTransformers = false,
                    ) ?: return@launch
                    preview.refineTokens()
                }
            }
            prewarmedTokenCounts[conversationId] = signature
        }
        warmJobs[conversationId] = job
    }

    internal fun hasInputPluginTransformers(): Boolean =
        pluginManager.plugins.value.any { it.enabled && it.hasCapability(PluginCapability.PROMPT_TRANSFORM) }

    internal fun inspectionKey(
        conversationId: Uuid,
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
    ): List<Any> = listOf(conversationId, labels)

    /**
     * 组装期间会读插件注册表与 MCP 状态，但它们**每次重发都是新对象**，放进共享 key 会让
     * 预热与预览永远对不上（实测 join 落空、白跑两遍）。改为：key 只按稳定对象，算完再比对这些
     * 易变状态的**引用**，变了就重算一次。
     */
    private companion object {
        const val PERF_TAG = "PromptInspectionPerf"
    }

    internal inner class AssemblyState(
        val settings: Any?,
        val conversation: Any?,
        val plugins: Any?,
        val mcp: Any?,
    ) {
        fun matchesCurrent() =
            settings === currentSettingsSnapshot &&
                conversation === currentConversationSnapshot &&
                plugins === pluginManager.plugins.value &&
                mcp === mcpManager.syncingStatus.value
    }

    /** 结果回来时用于比对的"当前快照"（由调用方在计算前写入，避免把大对象塞进 key）。 */
    @Volatile private var currentSettingsSnapshot: Any? = null
    @Volatile private var currentConversationSnapshot: Any? = null

    internal fun currentAssemblyState(settings: Settings, conversation: Conversation): AssemblyState {
        currentSettingsSnapshot = settings
        currentConversationSnapshot = conversation
        return AssemblyState(settings, conversation, pluginManager.plugins.value, mcpManager.syncingStatus.value)
    }

    /** 共享的精确计算：命中在飞任务；结果回来若易变状态已变，则再算一次（最多两次）。 */
    internal suspend fun runSharedInspection(
        key: List<Any>,
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
    ): PromptInspection? {
        repeat(2) { attempt ->
            val state = currentAssemblyState(settings, conversation)
            val result = inspectionSingleFlight.run(key) {
                assemblePromptInspection(settings, conversation, labels, approximateTokens = false)
            }
            if (state.matchesCurrent() || attempt == 1) return result
            Log.d(PERF_TAG, "inspection state changed during assembly, recomputing")
        }
        return null
    }


    /**
     * 组装一次「这次请求会发什么」的只读预览，按来源分段。
     *
     * 不执行脚本等未审核为只读的输入插件、OCR 或工作区环境探测；省略项随结果返回。
     * 与真实发送共用 [buildInternalMessages] 与
     * 同一个 transformer 链、同一套工具装配（[chatToolFactory]），因此预览与实际请求一致
     * ——避免预览自己另写一套组装而随时间漂移（`docs/peer-apps-benchmark.md` §6.7 ③）。
     *
     * 与 [handleMessageComplete] 的差异**仅**在于：不跑自动压缩（预览不产生副作用）、
     * 不注入重试/回退等生成期逻辑。
     *
     * **线程**：快照（settings / conversation）在**调用方上下文**读取，其余重活（transformer 链 +
     * 逐段真 BPE 分词 + 记忆读取 + 工具装配）经 [withPromptInspectionDispatcher] 在
     * `Dispatchers.Default` 上执行——调用方可以安全地在 Main / 组合上下文里直接 await，
     * 返回值会回到调用方线程（UI 状态就地更新即可）。新增调用方**不要**再自行 `withContext`。
     */
    internal suspend fun inspectPromptAssembly(
        conversationId: Uuid,
        labels: PromptInspectionLabels,
        /** 非 null 时走渐进式：先回一版近似计数，再回精确版本（预览页首屏秒开用）。 */
        onPartial: ((PromptInspection) -> Unit)? = null,
        /** 仅用于诊断日志：哪个入口发起的组装。 */
        caller: String = "unknown",
    ): PromptInspection? {
        Log.d(PERF_TAG, "inspect request caller=$caller")
        // [并发] 快照必须在调用方上下文（Main）读：getConversationFlow 会 getOrCreateSession，
        // 其 computeIfAbsent 里 `_sessionsVersion.value++` 是非原子 RMW（与其它线程的自增并发
        // 可能丢版本号 ⇒ 会话列表不刷新）；该 session 的 scope/回调（appScope = Main）也按
        // 「在主线程创建」的既有约定设计。
        val settings = settingsStore.settingsFlow.first()
        val conversation = conversationFlow(conversationId).value

        // 引用快照避免在 Main 对整段历史做深度 hash；只合并在飞任务，完成立即移除。
        val key = inspectionKey(conversationId, settings, conversation, labels)
        if (onPartial == null) {
            return runSharedInspection(key, settings, conversation, labels)
        }
        // 预热与预览兼容（无输入插件、且预热在飞）：直接 join，省掉第二次组装
        if (warmSharedWithPreview[conversationId] == true && warmJobs[conversationId]?.isActive == true) {
            Log.d(PERF_TAG, "joined warm task")
            val joined = runSharedInspection(key, settings, conversation, labels)
            Log.d(PERF_TAG, "joined warm task finished ok=${joined != null}")
            if (joined != null) onPartial(joined)
            return joined
        }
        // 不兼容时别让预热继续抢核：已填的缓存条目不会因取消丢失
        warmJobs[conversationId]?.cancel()
        Log.d(PERF_TAG, "join skipped shared=${warmSharedWithPreview[conversationId]} active=${warmJobs[conversationId]?.isActive}")
        // 渐进式路径**不并入在飞任务**：每个调用方都要拿到自己的中间结果。
        val fast = assemblePromptInspection(settings, conversation, labels, approximateTokens = true)
            ?: return null
        onPartial(fast)
        // 回调在调用方上下文（Main）触发：VM 直接写 Compose 状态，不必自己切线程
        val refineStarted = android.os.SystemClock.elapsedRealtime()
        val exact = withPromptInspectionDispatcher { fast.refineTokens() }
        Log.d(PERF_TAG, "refineMs=${android.os.SystemClock.elapsedRealtime() - refineStarted}")
        onPartial(exact)
        return exact
    }

    /** 预览的重活主体：助手/模型解析、记忆读取、工具装配、transformer 链与分段。 */
    internal suspend fun assemblePromptInspection(
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
        approximateTokens: Boolean,
        includePluginTransformers: Boolean = true,
    ): PromptInspection? = withPromptInspectionDispatcher {
        val timing = app.androidrik.rikkahub.data.ai.PromptInspectionTiming()
        val tokenStatsBefore = app.androidrik.common.tokenizer.TokenizerRegistry.cacheStats()
        try {
            val baseAssistant = settings.getAssistantById(conversation.assistantId)
                ?: settings.getCurrentAssistant()
            val assistant = (conversation.upstreamConfig?.applyTo(baseAssistant) ?: baseAssistant)
                .forMemoryResume(conversation.currentMessages.lastOrNull(), conversation.memoryDisabled)
            // `return@withPromptInspectionDispatcher`：helper 的 block 不是 inline lambda，
            // 不能用裸 `return null`。
            val baseModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: return@withPromptInspectionDispatcher null
            val model = conversation.upstreamConfig?.applyToModel(baseModel) ?: baseModel

            val memories = if (conversation.memoryDisabled || assistant.memoryMode == MemoryMode.FILES) {
                // 与实发一致：隐身对话不加载记忆（预览也要显示「没有记忆段」）
                emptyList()
            } else if (assistant.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            }
            timing.mark("memories")
            val tools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversation.id.toString(),
                memoryDisabled = conversation.memoryDisabled,
            )

            timing.mark("tools")
            val transformerChains = transformerRegistry.snapshot(
                pluginManager.plugins.value,
                if (includePluginTransformers) TransformerUse.PREVIEW else TransformerUse.WARMUP,
            )
            inspectPrompt(
                context = context,
                settings = settings,
                assistant = assistant,
                model = model,
                conversation = conversation,
                memories = memories,
                memoryFiles = if (assistant.enableMemory && !conversation.memoryDisabled && assistant.memoryMode == MemoryMode.FILES) {
                    memoryFiles.list(assistant.memoryId())
                } else {
                    emptyList()
                },
                tools = tools,
                inputTransformers = transformerChains.inputTransformers,
                omittedTransformers = transformerChains.omittedInputs,
                labels = labels,
                timing = timing,
                approximateTokens = approximateTokens,
            )
        } finally {
            val after = app.androidrik.common.tokenizer.TokenizerRegistry.cacheStats()
            // 仅耗时/数量，不记录正文。全局缓存增量可能含同期其它请求，仅供诊断。
            Log.d(PERF_TAG, timing.summary() +
                " cacheHitsDelta=${after.hits - tokenStatsBefore.hits}" +
                " cacheMissesDelta=${after.misses - tokenStatsBefore.misses}" +
                " cacheEntries=${after.entries} cacheTextBytes=${after.retainedTextBytes}")
        }
    }
}
