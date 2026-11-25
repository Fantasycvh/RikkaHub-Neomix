package app.androidrik.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.data.ai.compression.CompressionProgress

/**
 * 手动压缩后台任务的**单飞注册表 + 阶段进度状态**（design-manual-compress-background §4.1/§4.2）。
 *
 * 独立成类是为了 JVM 单测（§7 验收 1：单飞 cancel-and-replace、进度状态映射、
 * 「被顶掉的任务不得清掉继任者刚写入的状态」的清理竞态防护）——ChatService 构造器
 * 依赖太重，测不到这些边界。
 *
 * 与 [AuxJobRegistry] 的差别：手动压缩**不能**被「新生成前清场」误杀（压缩与生成是
 * 不同槽位，§4.4），故不复用其 Kind 枚举与 cancelAll 语义。
 */
internal class ManualCompressionRegistry {

    private val jobs = ConcurrentHashMap<Uuid, Job>()

    /** 在飞任务的参数快照（幂等判断用：§4.1「参数相同则幂等」） */
    private val activeParams = ConcurrentHashMap<Uuid, ManualCompressionParams>()

    private val _states = MutableStateFlow<Map<Uuid, CompressionProgress>>(emptyMap())
    val states: StateFlow<Map<Uuid, CompressionProgress>> = _states.asStateFlow()

    /**
     * 幂等判断：同会话同参数且任务仍在飞 → 调用方不重复起任务（§4.1「参数相同则幂等」）；
     * 参数不同或无在飞任务 → 正常走 cancel-and-replace。
     */
    fun isDuplicateOfActive(conversationId: Uuid, params: ManualCompressionParams): Boolean =
        isActive(conversationId) && activeParams[conversationId] == params

    /**
     * 注册新任务；同会话旧任务被取消（cancel-and-replace）。
     *
     * @return 是否顶掉了旧任务（观测/测试用）
     */
    fun replace(conversationId: Uuid, job: Job, params: ManualCompressionParams): Boolean {
        val previous = jobs.put(conversationId, job)
        previous?.cancel()
        activeParams[conversationId] = params
        job.invokeOnCompletion {
            // 只在「当前注册的仍是自己」时移除（ABA 防护，同 AuxJobRegistry）；
            // 注销后 map 里没有本会话的任务才清进度——被继任者顶掉时保留其刚写入的阶段
            val removed = jobs.remove(conversationId, job)
            if (removed) activeParams.remove(conversationId)
            if (jobs[conversationId] == null) {
                _states.update { it - conversationId }
            }
        }
        return previous != null
    }

    /** 用户取消（提示条取消按钮）：移除并取消任务，进度状态经 completion 回调收起 */
    fun cancel(conversationId: Uuid) {
        activeParams.remove(conversationId)
        jobs.remove(conversationId)?.cancel()
    }

    /**
     * 写入阶段进度。带任务身份校验：**只有仍是注册在册的任务**才能写——防止被顶掉的
     * 旧任务经迟到的 onProgress 复活状态条目（进度写入与 completion 清理竞态的另一半）。
     */
    fun updateProgress(conversationId: Uuid, job: Job, progress: CompressionProgress) {
        _states.update { current ->
            if (jobs[conversationId] === job) current + (conversationId to progress) else current
        }
    }

    fun isActive(conversationId: Uuid): Boolean = jobs[conversationId]?.isActive == true
}
