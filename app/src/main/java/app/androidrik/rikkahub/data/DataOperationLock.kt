package app.androidrik.rikkahub.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会改写或删除 `cacheDir`/数据库文件的长任务。
 *
 * 备份与恢复会写 `cacheDir/backup_*.zip`、`restore_*`、`backup_db_snapshot.db`；
 * 存储管理会清理这些白名单文件 ⇒ 两者必须互斥（见 `docs/design/design-storage-management.md` §5.2 红线 5）。
 */
enum class DataOperation {
    BACKUP,
    RESTORE,
    STORAGE_CLEANUP,
}

/**
 * 进程级互斥闸门。
 *
 * - 备份/恢复用 [runWaiting]：清理通常只需几百毫秒，等它结束再开始，避免让用户看到「备份失败」；
 * - 存储清理用 [runImmediate]：备份可能跑几分钟，抢不到就立刻返回 null，由 UI 提示稍后再试；
 * - [active] 供 UI 显示「备份/恢复进行中」并禁用清理按钮。
 */
object DataOperationLock {

    private val mutex = Mutex()

    private val _active = MutableStateFlow<DataOperation?>(null)
    val active: StateFlow<DataOperation?> = _active.asStateFlow()

    suspend fun <T> runWaiting(operation: DataOperation, block: suspend () -> T): T =
        mutex.withLock {
            _active.value = operation
            try {
                block()
            } finally {
                _active.value = null
            }
        }

    /** 抢不到锁返回 null（不排队、不抛异常）。 */
    suspend fun <T> runImmediate(operation: DataOperation, block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        _active.value = operation
        return try {
            block()
        } finally {
            _active.value = null
            mutex.unlock()
        }
    }
}
