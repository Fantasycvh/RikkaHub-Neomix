package app.androidrik.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.androidrik.rikkahub.data.ai.memoryScoreBucket
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.AssistantMemory
import kotlin.random.Random

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        private const val TAG = "MemoryRepository"

        const val GLOBAL_MEMORY_ID = "__global__"

        /** 助手记忆条数上限：超限时删除最旧记录（turbo 06baee4） */
        const val MAX_MEMORY_COUNT = 200

        /**
         * 主题词（`description`）的最大长度：与上游提示词里的「150 字符以内」对齐。
         *
         * 它是清单行的固定成本：一条记忆的清单行 ≈ 8(id) + 150(主题) + 10(日期) 字符，
         * 200 条上限下最坏 ≈ 3.4 万字符（而清单预算只有 8K token 的 1/4）。
         */
        const val MAX_DESCRIPTION_LENGTH = 150

        /**
         * 对外 opaque id 的字符集与长度：8 位小写十六进制（32 bit）。
         *
         * 为什么够用：字面是随机 token，模型无法从它推断记忆条数（根治上游 #1830 的
         * 「编号被当成总数」）；删除后也不产生可感知的空洞，**永远不需要重排**。
         * 32 bit 在「单助手 ≤200 条」量级下碰撞概率可忽略，且仍有唯一性校验兜底。
         */
        private const val PUBLIC_ID_LENGTH = 8
        private const val PUBLIC_ID_ALPHABET = "0123456789abcdef"
    }

    // ---- 映射 ----

    private fun MemoryEntity.toModel() = AssistantMemory(
        id = publicId.ifBlank { id.toString() }, // 极端情况（空串）按自增 id 兜底，保证 id 始终可用
        content = content,
        importance = importance,
        description = description,
        sortKey = id,
        updatedAt = updatedAt,
        readCount = readCount,
        lastReadAt = lastReadAt,
    )

    /**
     * 生成唯一 public id：随机采样 + 查重，最多重试若干次，避免极端碰撞。
     * 只在新建时调用。
     */
    private suspend fun generatePublicId(): String {
        repeat(16) {
            val candidate = buildString(PUBLIC_ID_LENGTH) {
                repeat(PUBLIC_ID_LENGTH) { append(PUBLIC_ID_ALPHABET[Random.nextInt(PUBLIC_ID_ALPHABET.length)]) }
            }
            if (!memoryDAO.publicIdExists(candidate)) return candidate
        }
        error("Failed to generate a unique memory public id after 16 attempts")
    }

    // ---- 读 ----

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toModel() }

    /**
     * 按对外 opaque id 取单条（供审批卡片展示「将要修改/删除哪条」；不存在返回 null）。
     *
     * 兼容旧引用：历史消息里的 `memory_tool` 调用可能用旧的自增编号（如 "12"），
     * 这里在 token 查不到且入参是纯数字时，回退按自增 id 查一次。
     */
    suspend fun getMemoryById(id: String): AssistantMemory? =
        findEntityByPublicId(id)?.toModel()

    // ---- 写 ----

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    /**
     * 按对外 opaque id 更新内容（id 兼容旧的自增编号引用）。
     *
     * **同时刷新 `updatedAt`**：它是时效衰减的时间基准，不刷新就等于「改了没用」——
     * 刚维护过的记忆仍按旧时间被算成陈旧，最后被淘汰。
     *
     * [importance] 为 null 时保留原值（模型只说改内容、不该顺手把重要度重置掉）；
     * [description] 同理为 null 时保留原值。
     */
    suspend fun updateContent(
        id: String,
        content: String,
        importance: Float? = null,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
    ): AssistantMemory {
        val old = requireNotNull(findEntityByPublicId(id)) { "Memory record $id not found" }
        val newMemory = old.copy(
            content = content,
            importance = importance?.coerceIn(0f, 1f) ?: old.importance,
            description = description?.let { normalizeDescription(it) } ?: old.description,
            updatedAt = now,
        )
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    /** 只改重要度（记忆页里用户覆盖模型给的值）。同时刷新 `updatedAt`，口径同 [updateContent]。 */
    suspend fun updateImportance(
        id: String,
        importance: Float,
        now: Long = System.currentTimeMillis(),
    ): AssistantMemory? {
        val old = findEntityByPublicId(id) ?: return null
        val newMemory = old.copy(importance = importance.coerceIn(0f, 1f), updatedAt = now)
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    /**
     * 新增记忆。**同内容幂等**——已存在完全相同的内容时直接返回既有记录、不重复插入。
     *
     * 为什么需要：一轮生成里注入模型的 `<memories>` 是**生成开始时的快照**（`ChatService` 取一次），
     * 工具循环中新建的记忆当轮不可见 → 模型会在后续 step 里对同一事实反复 `create`，
     * 造成同内容多条（实测一次「保存我的偏好」可建 6 条）。
     * 这里做去重而非"每个 step 重新注入记忆列表"：后者会让 system prompt 每步变化，
     * 反而打掉前缀缓存（见 design-auto-compaction 的前缀稳定性约束）。
     *
     * [importance] 由模型在工具调用时给（见 `docs/design/design-memory-scoring.md` §四）；
     * 缺失/越界时回落到 [MemoryEntity.DEFAULT_IMPORTANCE]。
     */
    suspend fun addMemory(
        assistantId: String,
        content: String,
        importance: Float? = null,
        description: String? = null,
        now: Long = System.currentTimeMillis(),
    ): AssistantMemory {
        val normalized = content.trim()
        // 幂等检查：同助手下已有相同内容则直接复用（避免工具循环重复创建）
        memoryDAO.getMemoriesOfAssistant(assistantId)
            .firstOrNull { it.content.trim() == normalized }
            ?.let { existing ->
                Log.i(TAG, "addMemory: duplicate content, reuse #${existing.publicId}")
                // 内容没变但模型补了主题词时顺手补上（只在原本为空时补，不清空既有 description）
                if (!description.isNullOrBlank() && existing.description.isBlank()) {
                    val withDescription = existing.copy(description = normalizeDescription(description))
                    memoryDAO.updateMemory(withDescription)
                    return withDescription.toModel()
                }
                return existing.toModel()
            }

        val newMemory = MemoryEntity(
            assistantId = assistantId,
            content = normalized,
            publicId = generatePublicId(),
            importance = importance?.coerceIn(0f, 1f) ?: MemoryEntity.DEFAULT_IMPORTANCE,
            description = description?.let { normalizeDescription(it) } ?: "",
            updatedAt = now,
        )
        val insertedId = memoryDAO.insertMemory(newMemory).toInt()
        enforceMaxCount(assistantId, now)
        return newMemory.copy(id = insertedId).toModel()
    }

    /**
     * 按对外 opaque id 取全文 **并记一次读取遥测**（`memory_read` 的唯一入口）。
     *
     * 遥测与取数合在一个方法里：分开写的话调用方漏一处就静默没有读数（而读数一旦缺失，
     * 「这条记忆到底有没有起作用」就永远答不了）。读不到时返回 null、不计数。
     */
    suspend fun readMemory(id: String, now: Long = System.currentTimeMillis()): AssistantMemory? {
        val entity = findEntityByPublicId(id) ?: return null
        memoryDAO.recordRead(entity.id, now)
        return entity.copy(readCount = entity.readCount + 1, lastReadAt = now).toModel()
    }

    /**
     * 把一个助手的独立记忆整批复制到另一个助手（「复制助手时同时复制记忆」，上游 `282b85f2`）。
     *
     * **不复用 [addMemory]**：那是「逐条内容去重 + 逐条淘汰 + 幂等」的单条写入路径，
     * 复制是**批量**语义（源侧内容本就互不相同，逐条插入还会反复触发 200 条上限扫描）。
     * 但三条不变式照搬：
     * - **新的 publicId**：对外 opaque id 不能两个助手共用——否则审批卡片/删除会指到别人那条；
     * - **保留 importance**：它是记忆自身的属性，注入打分靠它，丢了就退化成纯时效衰减；
     * - **updatedAt 取当下**：时效衰减的时间基准；沿用源时间会让复制来的记忆一上来就被当成陈旧。
     *
     * @return 实际复制条数（源助手没有独立记忆时为 0）。
     */
    suspend fun copyMemories(
        fromAssistantId: String,
        toAssistantId: String,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (fromAssistantId == toAssistantId) return 0
        val memories = memoryDAO.getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return 0

        val usedIds = mutableSetOf<String>()
        val copies = memories.map { memory ->
            var publicId = generatePublicId()
            // generatePublicId 只查库，查不到本次批次内刚生成的 id ⇒ 这里补一道批内去重
            while (!usedIds.add(publicId)) publicId = generatePublicId()
            MemoryEntity(
                assistantId = toAssistantId,
                content = memory.content,
                publicId = publicId,
                importance = memory.importance,
                description = memory.description,
                updatedAt = now,
            )
        }
        memoryDAO.insertMemories(copies)
        enforceMaxCount(toAssistantId, now)
        Log.i(TAG, "copyMemories: copied ${copies.size} memory(ies) from $fromAssistantId to $toAssistantId")
        return copies.size
    }

    /**
     * 200 条上限：超限按**分数最低**淘汰，防记忆无限膨胀（turbo 06baee4）。
     * 用分数（而非旧的「最旧」）：与入选口径一致——否则一条「重要但很久没改」的记忆
     * 会被新写入的次要记忆挤掉，与打分校准的意图相悖。
     */
    private suspend fun enforceMaxCount(assistantId: String, now: Long) {
        val all = memoryDAO.getMemoriesOfAssistant(assistantId)
        if (all.size <= MAX_MEMORY_COUNT) return
        val excess = all.size - MAX_MEMORY_COUNT
        val victims = all
            .sortedWith(
                compareBy<MemoryEntity> { memoryScoreBucket(it.toModel(), now) }
                    .thenBy { it.id } // 同桶内先淘汰更旧的，保证确定性
            )
            .take(excess)
        victims.forEach { memoryDAO.deleteMemory(it.id) }
        Log.i(TAG, "evicted $excess lowest-score memory(ies) over $MAX_MEMORY_COUNT cap")
    }

    /** 按对外 opaque id 删除（id 兼容旧的自增编号引用）。 */
    suspend fun deleteMemory(id: String) {
        val entity = findEntityByPublicId(id) ?: return
        memoryDAO.deleteMemory(entity.id)
    }

    /** token 优先，其次兼容旧的自增编号引用（历史消息里可能是 "12"）。 */
    private suspend fun findEntityByPublicId(id: String): MemoryEntity? {
        memoryDAO.getMemoryByPublicId(id)?.let { return it }
        val numericId = id.toIntOrNull() ?: return null
        return memoryDAO.getMemoryById(numericId)
    }

    /**
     * 主题词的规范化：去空白、**截断到 [MAX_DESCRIPTION_LENGTH]**。
     *
     * 截断而非拒绝：主题词是「清单行」的显示字段，过长只是挤占索引预算（每行都是固定成本），
     * 没必要因此让整条记忆写入失败。
     */
    private fun normalizeDescription(raw: String): String = raw.trim().take(MAX_DESCRIPTION_LENGTH)
}
