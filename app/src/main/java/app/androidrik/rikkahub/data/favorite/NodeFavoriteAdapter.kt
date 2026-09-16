package app.androidrik.rikkahub.data.favorite

import app.androidrik.rikkahub.data.db.entity.FavoriteEntity
import app.androidrik.rikkahub.data.model.FavoriteMeta
import app.androidrik.rikkahub.data.model.FavoriteType
import app.androidrik.rikkahub.data.model.NodeFavoriteRef
import app.androidrik.rikkahub.data.model.NodeFavoriteTarget
import app.androidrik.rikkahub.data.model.buildFavoritePreview
import app.androidrik.rikkahub.utils.JsonInstant

object NodeFavoriteAdapter : FavoriteAdapter<NodeFavoriteTarget> {
    override val type: FavoriteType = FavoriteType.NODE

    override fun buildRefKey(target: NodeFavoriteTarget): String {
        return buildRefKey(target.conversationId.toString(), target.nodeId.toString())
    }

    fun buildRefKey(conversationId: String, nodeId: String): String = "node:$conversationId:$nodeId"

    override fun buildFavoriteEntity(
        target: NodeFavoriteTarget,
        existing: FavoriteEntity?,
        now: Long
    ): FavoriteEntity {
        val ref = NodeFavoriteRef(
            conversationId = target.conversationId,
            nodeId = target.nodeId,
        )
        // **必须保留既有标签**：本函数每次从 target 重建 meta，若不显式带上 `existing.tagIds`，
        // 「取消收藏 → 重新收藏」会静默丢掉标签（设计稿 §五 的坑）。
        // 注：`decodeMeta` 用 runCatching 解析，旧行/坏 JSON 退化为 null → 空标签，安全。
        val existingTags = existing?.let { decodeMeta(it)?.tagIds }.orEmpty()
        val meta = FavoriteMeta(
            title = target.conversationTitle.ifBlank { null },
            subtitle = target.nodeId.toString(),
            previewText = target.node.buildFavoritePreview(),
            tagIds = existingTags,
        )

        return FavoriteEntity(
            id = existing?.id ?: buildRefKey(target),
            type = type.value,
            refKey = buildRefKey(target),
            refJson = JsonInstant.encodeToString(ref),
            snapshotJson = "",
            metaJson = JsonInstant.encodeToString(meta),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
    }

    fun decodeRef(entity: FavoriteEntity): NodeFavoriteRef? {
        if (entity.type != type.value) return null
        return runCatching {
            JsonInstant.decodeFromString<NodeFavoriteRef>(entity.refJson)
        }.getOrNull()
    }

    fun decodeMeta(entity: FavoriteEntity): FavoriteMeta? {
        if (entity.type != type.value) return null
        val rawMeta = entity.metaJson ?: return null
        return runCatching {
            JsonInstant.decodeFromString<FavoriteMeta>(rawMeta)
        }.getOrNull()
    }
}
