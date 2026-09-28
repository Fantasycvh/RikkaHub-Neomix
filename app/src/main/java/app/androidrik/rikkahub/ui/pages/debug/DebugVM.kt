package app.androidrik.rikkahub.ui.pages.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid

class DebugVM(
    private val settingsStore: SettingsStore,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    private val _conversationCount = MutableStateFlow<Int?>(null)
    val conversationCount: StateFlow<Int?> = _conversationCount.asStateFlow()

    /** 聊天记录中引用的助手 id → 对话数（null = 尚未扫描）。 */
    private val _conversationAssistants = MutableStateFlow<Map<Uuid, Int>?>(null)
    val conversationAssistants: StateFlow<Map<Uuid, Int>?> = _conversationAssistants.asStateFlow()

    init {
        refreshConversationCount()
        scanConversationAssistants()
    }

    fun refreshConversationCount() {
        viewModelScope.launch {
            _conversationCount.value = conversationRepository.countConversations()
        }
    }

    fun scanConversationAssistants() {
        viewModelScope.launch {
            _conversationAssistants.value = conversationRepository.countConversationsByAssistant()
        }
    }

    /**
     * 为「聊天记录引用了、但设置里已不存在」的助手补齐助手，使这些对话重新可见
     * （对话列表按助手过滤，没有对应助手就完全看不到）。
     *
     * **两类缺失分开处理**（上游 `b9c0d3b7` 只造占位；本仓多一类）：
     * - **内置助手 id**：缺失只可能是因为用户删过它（fork 的墓碑机制 `removedAssistantIds`
     *   会阻止回填）。这类**清掉墓碑**即可让读路径把内置助手的**真身**带回来（含系统提示、
     *   模型等完整配置）——比造一个「Recovered assistant N」占位好得多，而且占位会占住
     *   该 id、永久挡住真身回填。
     * - **其余 id**：无从得知原配置，只能造占位助手（上游行为）。
     *
     * @return 恢复结果；`settings` 尚未加载时返回 null
     */
    suspend fun recoverAssistantsFromConversations(): AssistantRecoveryResult? {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return null

        val byAssistant = conversationRepository.countConversationsByAssistant()
        _conversationAssistants.value = byAssistant

        val existingIds = settings.assistants.map { it.id }.toSet()
        val missing = byAssistant.filterKeys { it !in existingIds }
        if (missing.isEmpty()) return AssistantRecoveryResult(0, 0, 0)

        val plan = planAssistantRecovery(missing.keys)
        val placeholders = plan.placeholderIds.mapIndexed { index, id ->
            Assistant(id = id, name = "Recovered assistant ${index + 1}")
        }

        settingsStore.update(
            settings.copy(
                assistants = settings.assistants + placeholders,
                // 清墓碑 ⇒ 下一次读取时 refillDefaultAssistants 把内置助手原样补回
                removedAssistantIds = settings.removedAssistantIds - plan.builtInIds,
            )
        )
        return AssistantRecoveryResult(
            placeholders = placeholders.size,
            builtInRestored = plan.builtInIds.size,
            conversations = missing.values.sum(),
        )
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 创建一个超大的对话用于测试 CursorWindow 限制
     * @param sizeMB 目标大小（MB）
     */
    fun createOversizedConversation(sizeMB: Int = 3) {
        viewModelScope.launch {
            val targetSize = sizeMB * 1024 * 1024
            val messageNodes = mutableListOf<MessageNode>()
            var currentSize = 0

            // 生成大量消息直到达到目标大小
            var index = 0
            while (currentSize < targetSize) {
                // 生成一个包含大量文本的消息（约 100KB 每条）
                val largeText = buildString {
                    repeat(100) {
                        append("这是一段很长的测试文本，用于测试 CursorWindow 的大小限制。")
                        append("Row too big to fit into CursorWindow 错误通常发生在单行数据超过 2MB 时。")
                        append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
                        append("Index: $index, Block: $it. ")
                    }
                }

                val userMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(largeText)),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )
                val assistantMessage = UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("回复: $largeText")),
                    createdAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                )

                messageNodes.add(MessageNode.of(userMessage))
                messageNodes.add(MessageNode.of(assistantMessage))

                currentSize += largeText.length * 2 * 2 // 大约估算
                index++
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = DEFAULT_ASSISTANT_ID,
                title = "超大对话测试 (${sizeMB}MB)",
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    fun createConversationWithMessages(messageCount: Int = 1024) {
        viewModelScope.launch {
            val messageNodes = ArrayList<MessageNode>(messageCount)
            val timeZone = TimeZone.currentSystemDefault()
            repeat(messageCount) { index ->
                val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
                val message = UIMessage(
                    id = Uuid.random(),
                    role = role,
                    parts = listOf(UIMessagePart.Text(randomMessageText(index, role))),
                    createdAt = Clock.System.now().toLocalDateTime(timeZone),
                )
                messageNodes.add(MessageNode.of(message))
            }

            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = DEFAULT_ASSISTANT_ID,
                title = "${messageCount}条消息测试",
                messageNodes = messageNodes,
            )

            conversationRepository.insertConversation(conversation)
        }
    }

    private fun randomMessageText(index: Int, role: MessageRole): String {
        val fragments = listOf(
            "快速", "随机", "消息", "样例", "用于", "测试", "列表", "渲染", "滚动", "性能",
            "聊天", "对话", "内容", "结构", "验证", "分页", "顺序", "稳定", "系统",
        )
        val wordCount = Random.nextInt(6, 14)
        val prefix = if (role == MessageRole.USER) "用户" else "助手"
        val body = List(wordCount) { fragments.random() }.joinToString(" ")
        return "$prefix#${index + 1}: $body"
    }
}

/**
 * 数据恢复结果（[DebugVM.recoverAssistantsFromConversations]）。
 *
 * @param placeholders 新建的占位助手数（原配置不可考）
 * @param builtInRestored 清墓碑后由内置助手回填恢复的数量（配置完整）
 * @param conversations 因此重新可见的对话数
 */
data class AssistantRecoveryResult(
    val placeholders: Int,
    val builtInRestored: Int,
    val conversations: Int,
)

/**
 * 恢复方案：把「聊天记录引用了但设置里缺失」的助手 id 分成两拨。
 *
 * - [builtInIds]：内置助手的 id——**清墓碑让它自己回填**（真身带完整配置回来）
 * - [placeholderIds]：其余 id——原配置不可考，只能建占位
 *
 * 关键不变量：**内置 id 绝不出现在 [placeholderIds] 里**。若给内置 id 建了占位，
 * 那个占位会占住该 id，`refillDefaultAssistants` 见「已存在」便不再补真身 ⇒ 用户永久
 * 拿到一个空壳「Recovered assistant」。纯函数便于单测钉住这一点。
 */
internal data class AssistantRecoveryPlan(
    val placeholderIds: List<Uuid>,
    val builtInIds: Set<Uuid>,
)

internal fun planAssistantRecovery(
    missingIds: Collection<Uuid>,
    builtInIds: Collection<Uuid> = DEFAULT_ASSISTANTS_IDS,
): AssistantRecoveryPlan {
    val builtIn: Set<Uuid> = missingIds.filter { it in builtInIds }.toSet()
    return AssistantRecoveryPlan(
        placeholderIds = missingIds.filter { it !in builtInIds },
        builtInIds = builtIn,
    )
}
