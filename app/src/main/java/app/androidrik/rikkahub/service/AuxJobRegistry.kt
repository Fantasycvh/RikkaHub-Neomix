package app.androidrik.rikkahub.service

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * 辅助生成任务（标题 / 建议）的**单飞**注册表（借鉴 rikkahub-agent `AuxJobRegistry`）。
 *
 * 背景：回复完成后标题与建议是 fire-and-forget；用户「停止 → 重发 → 重新生成」连点时，
 * 会有多个并发请求打同一个后端（本地 llama.cpp 单槽还会把主会话的 prompt cache 顶掉），
 * 云端接口也会白烧 token。这里按 `(conversationId, kind)` **cancel-and-replace**：
 * 同键只保留最新一个任务；任务结束（完成或取消）自动注销，不泄漏。
 *
 * 与 [ChatService.launchWithConversationReference] 组合使用，保持会话引用计数不变。
 */
internal class AuxJobRegistry {

    enum class Kind { TITLE, SUGGESTION }

    private data class Key(val conversationId: Uuid, val kind: Kind)

    private val jobs = ConcurrentHashMap<Key, Job>()

    /**
     * 注册新任务；同键旧任务被取消（cancel-and-replace）。
     *
     * @return 是否顶掉了旧任务（观测/测试用）
     */
    fun replace(conversationId: Uuid, kind: Kind, job: Job): Boolean {
        val key = Key(conversationId, kind)
        val previous = jobs.put(key, job)
        previous?.cancel()
        job.invokeOnCompletion {
            // 只在「当前注册的仍是自己」时移除，避免把后来者顶掉（ABA 防护）
            jobs.remove(key, job)
        }
        return previous != null
    }

    /** 新生成 / 重新生成前清理该会话的所有在飞辅助任务。 */
    fun cancelAll(conversationId: Uuid) {
        Kind.entries.forEach { kind ->
            jobs.remove(Key(conversationId, kind))?.cancel()
        }
    }

    fun isActive(conversationId: Uuid, kind: Kind): Boolean =
        jobs[Key(conversationId, kind)]?.isActive == true
}
