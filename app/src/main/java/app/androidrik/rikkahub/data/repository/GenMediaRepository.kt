package app.androidrik.rikkahub.data.repository

import androidx.paging.PagingSource
import app.androidrik.rikkahub.data.db.dao.GenMediaDAO
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun insertIfMissing(media: GenMediaEntity) = dao.insertIfMissing(
        media.path, media.modelId, media.prompt, media.createAt, media.type, media.sourcePaths,
    )

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
