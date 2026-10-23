package app.androidrik.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import app.androidrik.rikkahub.data.db.dao.ManagedFileDAO
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity

class FilesRepository(
    private val dao: ManagedFileDAO,
) {
    suspend fun insert(file: ManagedFileEntity): ManagedFileEntity {
        val id = dao.insert(file)
        return file.copy(id = id)
    }

    suspend fun update(file: ManagedFileEntity) {
        dao.update(file)
    }

    /** Display names are labels, never physical paths. Duplicate labels are safe. */
    suspend fun rename(id: Long, displayName: String): Boolean {
        val name = displayName.trim()
        require(name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { "文件名不能为空或包含路径分隔符/控制字符" }
        return dao.renameDisplayName(id, name, System.currentTimeMillis()) > 0
    }

    suspend fun getById(id: Long): ManagedFileEntity? = dao.getById(id)

    suspend fun getByPath(relativePath: String): ManagedFileEntity? = dao.getByPath(relativePath)

    fun listByFolder(folder: String): Flow<List<ManagedFileEntity>> = dao.listByFolder(folder)

    suspend fun deleteById(id: Long): Int = dao.deleteById(id)

    suspend fun deleteByPath(relativePath: String): Int = dao.deleteByPath(relativePath)

    suspend fun deleteByFolder(folder: String): Int = dao.deleteByFolder(folder)
}
