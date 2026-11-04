package app.androidrik.plugin.manager

/** 一次启用拥有的贡献。安装必须同步且短小，关闭后延迟回调不能再安装贡献。 */
class PluginResources internal constructor() : AutoCloseable {
    private var open = true
    private val handles = mutableListOf<AutoCloseable>()

    @get:Synchronized
    val isActive: Boolean get() = open

    /** 安装与撤销互斥；关闭后不再调用 install。资源按安装的逆序释放。 */
    @Synchronized
    fun register(install: () -> AutoCloseable): Boolean {
        if (!open) return false
        handles += install()
        return true
    }

    @Synchronized
    internal fun invalidate() {
        open = false
    }

    override fun close() {
        val pending = synchronized(this) {
            open = false
            handles.asReversed().toList().also { handles.clear() }
        }
        var failure: Throwable? = null
        pending.forEach { handle ->
            try {
                handle.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

/** 注册实例的释放句柄；同 id 重装后，旧句柄不会卸载新实例。 */
class PluginRegistration internal constructor(private val release: suspend () -> Boolean) {
    suspend fun unregister(): Boolean = release()
}
