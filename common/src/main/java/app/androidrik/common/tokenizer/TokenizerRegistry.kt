package app.androidrik.common.tokenizer

import java.io.InputStream

/**
 * 分词器注册表：**真 tokenizer 的唯一入口**（未安装时返回 null，调用方退回启发式）。
 *
 * 为什么要这层：
 * 1. **加载是异步且可能失败的**（456KB gz、10 万条词表）。启动时后台预热，预热完成前所有调用
 *    返回 null ⇒ 调用方走原有启发式，**行为不中断**；
 * 2. 词表数据来自 `common` 的 Java 资源，Android 与 JVM 单测都按 classpath 读得到，
 *    **不需要 Context/AssetManager**（也就不必把 Context 灌进工具层）；
 * 3. 加载失败（OOM、资源缺失）时自动退回启发式，而不是让"估算"这种非关键路径把请求打崩。
 *
 * 本类只做"装机"，不含估算口径——口径在 `app/utils/ContextLength.kt` 与
 * `ai/util/ToolSchemaSize.kt` 各自收口（那里原本就是全仓估算的唯一出口）。
 */
object TokenizerRegistry {

    /** 内置词表资源路径（`common/src/main/resources/…`，构建期由 tiktoken 官方表转换）。 */
    const val CL100K_BASE_RESOURCE = "/tokenizer/cl100k_base.bin.gz"

    private class Installation(val tokenizer: ByteLevelBpe) {
        val cache = TokenCountCache()
    }

    @Volatile
    private var installation: Installation? = null

    private val tokenizer: ByteLevelBpe? get() = installation?.tokenizer

    /** 冷启动失败原因（诊断用；成功时为 null）。 */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** 已装好的分词器；**null = 尚未预热或预热失败**（调用方退回启发式）。 */
    val current: ByteLevelBpe? get() = tokenizer

    /** 是否已就绪（UI 想提示"计数为精确值"时可用）。 */
    val isReady: Boolean get() = tokenizer != null

    /**
     * 从 classpath 资源装载（幂等）。
     *
     * @param resourcePath 词表资源路径，默认 [CL100K_BASE_RESOURCE]
     * @param keepDecodeTable 是否保留解码表（生产只编码，默认 false）
     * @return true = 本次装载成功（或此前已装好）；false = 失败（已记入 [lastError]，调用方继续用启发式）
     */
    fun installFromClasspath(
        resourcePath: String = CL100K_BASE_RESOURCE,
        keepDecodeTable: Boolean = false,
    ): Boolean {
        if (tokenizer != null) return true
        // ⚠️ 返回值必须是**内层 install 的结果**：包一层 runCatching 求 isSuccess 会把
        // "install 失败但没抛" 误报成成功（首版踩过：ok=true 而 current=null）
        return runCatching {
            val stream: InputStream = TokenizerRegistry::class.java.getResourceAsStream(resourcePath)
                ?: error("tokenizer resource not found: $resourcePath")
            install(stream, keepDecodeTable)
        }.onFailure { lastError = it }.getOrDefault(false)
    }

    /** 从一个 gz 流装载（幂等）。 */
    fun install(stream: InputStream, keepDecodeTable: Boolean = false): Boolean {
        if (tokenizer != null) return true
        return runCatching {
            synchronized(this) {
                if (tokenizer == null) {
                    installation = Installation(ByteLevelBpe.load(stream, keepDecodeTable))
                    lastError = null
                }
            }
        }.onFailure { lastError = it }.isSuccess
    }

    /**
     * 计 token 数（**未预热返回 null**）。带一层有界缓存：同一段文本（系统提示、记忆条目、
     * 工具 schema）在每次请求里都会被重复计数，缓存直接省掉重复分词。
     */
    fun count(text: String): Int? {
        val installed = installation ?: return null
        if (text.isEmpty()) return 0
        return installed.cache.count(text) {
            runCatching { installed.tokenizer.count(text) }.getOrNull()
        }
    }

    /** 卸载（低内存时可选调用；下次 [count] 会返回 null 直到重新装载）。 */
    fun release() {
        synchronized(this) {
            val previous = installation
            installation = null
            previous?.cache?.invalidate()
        }
    }

    /**
     * 当前安装的缓存快照（预览日志/诊断用）；未安装时全零，重新安装会重置统计。
     * 非空文本的每次查找计一次 hit 或 miss；超大文本和计算失败计 miss，但不保留。
     * release 前已经开始的 count 可返回旧实例的精确值，但不会重新填充已失效的缓存。
     */
    fun cacheStats(): TokenCountCacheStats = synchronized(this) {
        installation?.cache?.snapshot() ?: TokenCountCacheStats(0, 0, 0, 0)
    }

    /** 缓存条目数（兼容现有诊断/测试调用）。 */
    internal fun cacheSize(): Int = cacheStats().entries
}
