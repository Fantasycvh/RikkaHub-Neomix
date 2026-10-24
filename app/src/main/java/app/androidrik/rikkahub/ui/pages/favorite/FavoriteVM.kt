package app.androidrik.rikkahub.ui.pages.favorite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.FavoriteEntity
import app.androidrik.rikkahub.data.favorite.NodeFavoriteAdapter
import app.androidrik.rikkahub.data.model.FavoriteMeta
import app.androidrik.rikkahub.data.model.FavoriteType
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.data.repository.FavoriteRepository
import app.androidrik.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** 收藏列表的排序方式（在 VM 侧做——SQLite 的 `ORDER BY` **不能参数化**）。 */
enum class FavoriteSort {
    /** 收藏时间倒序（默认，= 原行为）。 */
    CREATED_DESC,

    /** 按会话标题升序（同标题时按时间倒序兜底 → 排序稳定）。 */
    TITLE_ASC,
}

data class NodeFavoriteListItem(
    val id: String,
    val refKey: String,
    val conversationId: Uuid,
    val nodeId: Uuid,
    val conversationTitle: String,
    val preview: String,
    val createdAt: Long,
    /** 该收藏的标签 id（**存 id 而非名** → 标签改名无需批量更新收藏）。 */
    val tagIds: List<Uuid> = emptyList(),
)

class FavoriteVM(
    private val favoriteRepository: FavoriteRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val sortFlow = MutableStateFlow(FavoriteSort.CREATED_DESC)

    /** 待撤销的删除（时间序，见 [removeFavorite]）；`PendingUndo.expiresAt` 过期即视为放弃撤销。 */
    private var pendingUndoes = mutableListOf<PendingUndo>()
    private val pendingUndoesMutex = Mutex()

    /** 当前排序方式。 */
    val sort: StateFlow<FavoriteSort> = sortFlow.asStateFlow()

    fun setSort(newSort: FavoriteSort) {
        sortFlow.value = newSort
    }

    /** 标签池（持久化在 Settings，照 `assistantTags` 的单 key JSON 列表写法）。 */
    val tagPool: StateFlow<List<Tag>> = settingsStore.settingsFlowRaw
        .map { it.favoriteTags }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 列表项：收藏 + 排序。
     *
     * 排序在 VM 侧而非 DAO：SQLite 不允许参数化 `ORDER BY`（列名不能是绑定参数），
     * 而集合规模小（几十条），侧排序成本可忽略。见设计稿 §六.4。
     */
    val nodeFavorites = combine(
        favoriteRepository.listByType(FavoriteType.NODE),
        sortFlow,
    ) { favorites, sort ->
        favorites
            .mapNotNull { entity ->
                val ref = NodeFavoriteAdapter.decodeRef(entity) ?: return@mapNotNull null
                val meta = NodeFavoriteAdapter.decodeMeta(entity)
                NodeFavoriteListItem(
                    id = entity.id,
                    refKey = entity.refKey,
                    conversationId = ref.conversationId,
                    nodeId = ref.nodeId,
                    conversationTitle = meta?.title.orEmpty(),
                    preview = meta?.previewText ?: "",
                    createdAt = entity.createdAt,
                    tagIds = meta?.tagIds.orEmpty(),
                )
            }
            .let { items ->
                when (sort) {
                    FavoriteSort.CREATED_DESC -> items.sortedByDescending { it.createdAt }
                    FavoriteSort.TITLE_ASC -> items.sortedWith(
                        compareBy({ it.conversationTitle.lowercase() }, { -it.createdAt })
                    )
                }
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 删除收藏（挂起，保证先删完再弹 snackbar）。
     *
     * 实体进 [pendingUndoes] 撤销队列：**一个 snackbar 被下一条删除抢占时，它的撤销协程只会收到
     * Dismissed**——若只在 ActionPerformed 时恢复「自己那一条」，被抢占的删除就静默丢失（真机复现：
     * 连删两条 → 点最后一条的「撤销」→ 只有最后一条回来）。撤销时统一恢复队列里未过期的全部条目。
     */
    suspend fun removeFavorite(entity: FavoriteEntity) {
        favoriteRepository.deleteByRefKey(entity.refKey)
        pendingUndoesMutex.withLock {
            pruneExpiredLocked()
            pendingUndoes += PendingUndo(entity, System.currentTimeMillis() + UNDO_RETENTION_MS)
        }
    }

    suspend fun getEntityByRefKey(refKey: String): FavoriteEntity? =
        favoriteRepository.getByRefKey(refKey)

    /** 撤销删除：恢复队列中**全部**未过期条目（见 [removeFavorite] 的抢占说明）。 */
    suspend fun undoFavoriteDeletes() {
        val entities = pendingUndoesMutex.withLock {
            pruneExpiredLocked()
            pendingUndoes.also { pendingUndoes = mutableListOf() }.map { it.entity }
        }
        entities.forEach { favoriteRepository.upsert(it) }
    }

    private fun pruneExpiredLocked() {
        val now = System.currentTimeMillis()
        pendingUndoes.removeAll { it.expiresAt < now }
    }

    private class PendingUndo(val entity: FavoriteEntity, val expiresAt: Long)

    companion object {
        /** 撤销条目保留时长：须大于 snackbar 展示时长（Short ≈ 4s），过期条目在下次触碰队列时修剪。 */
        private const val UNDO_RETENTION_MS = 10_000L
    }

    // ---- 标签（批次 2）----

    /** 给某收藏设置标签（写回 `meta_json` → **零迁移**）。 */
    suspend fun setFavoriteTags(refKey: String, tagIds: List<Uuid>) {
        val entity = favoriteRepository.getByRefKey(refKey) ?: return
        val existing = NodeFavoriteAdapter.decodeMeta(entity)
        val updated = FavoriteMeta(
            // **必须保留既有 meta 字段**：meta 是完整 blob，重建时漏字段即丢数据
            title = existing?.title,
            subtitle = existing?.subtitle,
            previewText = existing?.previewText,
            tagIds = tagIds,
        )
        favoriteRepository.upsert(
            entity.copy(
                metaJson = JsonInstant.encodeToString(updated),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    // ---- 标签池管理 ----

    suspend fun addTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val current = settingsStore.settingsFlowRaw.first().favoriteTags
        // 同名去重（不区分大小写）
        if (current.any { it.name.equals(trimmed, ignoreCase = true) }) return
        settingsStore.update { it.copy(favoriteTags = current + Tag(id = Uuid.random(), name = trimmed)) }
    }

    /**
     * 重命名标签。
     *
     * 因为收藏存的是 **tag id**，改名只需改标签池一处 —— **无需批量更新收藏**（这是选 id 而非
     * 名值存储的直接收益，见设计稿 §五 的取舍）。
     */
    suspend fun renameTag(id: Uuid, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val current = settingsStore.settingsFlowRaw.first().favoriteTags
        if (current.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return
        settingsStore.update {
            it.copy(favoriteTags = current.map { tag -> if (tag.id == id) tag.copy(name = trimmed) else tag })
        }
    }

    /** 删除标签：从标签池移除，并**从所有收藏上摘掉该 id**（否则会留下悬空引用）。 */
    suspend fun deleteTag(id: Uuid) {
        val current = settingsStore.settingsFlowRaw.first().favoriteTags
        settingsStore.update { it.copy(favoriteTags = current.filterNot { tag -> tag.id == id }) }
        // 摘除悬空引用（收藏数少，逐条处理即可）
        favoriteRepository.listByType(FavoriteType.NODE).first().forEach { entity ->
            val meta = NodeFavoriteAdapter.decodeMeta(entity) ?: return@forEach
            if (id !in meta.tagIds) return@forEach
            val updated = meta.copy(tagIds = meta.tagIds.filterNot { it == id })
            favoriteRepository.upsert(
                entity.copy(
                    metaJson = JsonInstant.encodeToString(updated),
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}
