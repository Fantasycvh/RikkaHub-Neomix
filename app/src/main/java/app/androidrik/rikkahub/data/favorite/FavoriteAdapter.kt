package app.androidrik.rikkahub.data.favorite

import app.androidrik.rikkahub.data.db.entity.FavoriteEntity
import app.androidrik.rikkahub.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
