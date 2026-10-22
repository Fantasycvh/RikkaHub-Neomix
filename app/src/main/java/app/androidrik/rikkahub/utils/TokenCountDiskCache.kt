package app.androidrik.rikkahub.utils

import app.androidrik.common.tokenizer.TokenizerRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 精确 token 计数的**持久化缓存**：跨进程重启复用上次算过的结果。
 *
 * 为什么需要：预览/预热的耗时大头是逐段真 BPE 分词（本次会话实测 8s），而同一段历史文本在
 * 多次启动之间几乎不变。内存里的 [app.androidrik.common.tokenizer.TokenCountCache] 只能跨"这一次
 * 进程"复用，冷启动一切归零。
 *
 * 设计取舍：
 * - **键是文本的 64 位哈希**（SHA-256 前 8 字节）。不存原文：1.5MB 文本换成一个 12 字节条目。
 *   2 万条目时 64 位碰撞概率约 1e-11，可接受；真要更稳就把前缀长度一起进哈希。
 * - **只在真 tokenizer 就绪时写入**：否则会把字符启发式的值当成精确值固化下来（这是最危险的
 *   一种错误——之后每次启动都"命中"一个错的数）。读取不做此限制（键里带 vocabSize 版本）。
 * - **文件带词表版本号**：词表变了（升级/换 tokenizer）直接丢弃旧文件，绝不串味。
 * - 写入节流：每 3 秒最多落盘一次，后台线程做，不阻塞计数。
 * - 上限 2 万条，满了整体重建（简单，且命中率主要来自最近的会话）。
 */
object TokenCountDiskCache {
    private const val MAGIC = 0x52544343 // "RTCC"
    private const val FORMAT_VERSION = 1
    private const val MAX_ENTRIES = 20_000
    private const val SAVE_INTERVAL_MS = 3_000L
    private const val ENTRY_BYTES = 12 // 8 字节哈希 + 4 字节计数

    private val lock = Any()
    private var file: File? = null
    private var vocabSize: Int = -1
    private var entries: HashMap<Long, Int> = HashMap(4096)
    private val dirty = AtomicBoolean(false)
    private var lastSaveAt = 0L
    private var saving = false

    /** 只读诊断：当前已加载/已缓存的条目数。 */
    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * 挂上缓存文件并加载。**在主线程调用一次**（实测 2 万条约 10ms，可接受）。
     * 词表版本不一致或文件损坏时静默丢弃。
     */
    fun attach(dir: File) {
        val target = File(dir, "token_counts.v1.bin")
        val current = TokenizerRegistry.current?.vocabSize ?: return
        synchronized(lock) {
            file = target
            vocabSize = current
            entries = load(target, current)
            lastSaveAt = System.currentTimeMillis()
        }
    }

    /** tokenizer 安装完成后再调一次：把刚就绪的词表版本对齐（首次安装前 attach 会被忽略）。 */
    fun onTokenizerReady() {
        val target = file ?: return
        val current = TokenizerRegistry.current?.vocabSize ?: return
        synchronized(lock) {
            if (vocabSize == current) return
            vocabSize = current
            entries = load(target, current)
        }
    }

    fun get(text: String): Int? {
        if (text.isEmpty() || text.length < 64) return null // 短文本 BPE 本身就快，不值得哈希
        synchronized(lock) {
            if (entries.isEmpty()) return null
            return entries[hash(text)]
        }
    }

    fun put(text: String, tokens: Int) {
        if (text.length < 64) return
        // 只有真 tokenizer 的结果才是"精确值"；启发式兜底值绝不落盘
        if (TokenizerRegistry.current == null) return
        synchronized(lock) {
            if (file == null) return
            entries[hash(text)] = tokens
            if (entries.size > MAX_ENTRIES) entries = HashMap(entries) // 简单重建，不做精细淘汰
            dirty.set(true)
        }
        maybeSave()
    }

    /** 立即落盘（切后台/退出前可调用；节流逻辑不会阻止显式调用）。 */
    fun flush() {
        if (!dirty.compareAndSet(true, false)) return
        val snapshot: List<Map.Entry<Long, Int>>
        val target: File
        val vocab: Int
        synchronized(lock) {
            target = file ?: run { dirty.set(true); return }
            if (saving) { dirty.set(true); return }
            saving = true
            vocab = vocabSize
            snapshot = entries.entries.toList()
        }
        Thread({
            try {
                write(target, vocab, snapshot)
                synchronized(lock) { lastSaveAt = System.currentTimeMillis() }
            } catch (_: Throwable) {
                dirty.set(true)
            } finally {
                synchronized(lock) { saving = false }
            }
        }, "token-count-cache-save").apply { isDaemon = true }.start()
    }

    private fun maybeSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_INTERVAL_MS) return
        flush()
    }

    private fun hash(text: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value
    }

    private fun load(target: File, vocab: Int): HashMap<Long, Int> {
        val result = HashMap<Long, Int>(4096)
        if (!target.isFile) return result
        try {
            DataInputStream(target.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return result
                if (input.readInt() != FORMAT_VERSION) return result
                if (input.readInt() != vocab) return result // 词表变了：旧值一律不可信
                val count = input.readInt()
                if (count < 0 || count > MAX_ENTRIES * 2) return result
                repeat(count) {
                    val key = input.readLong()
                    val tokens = input.readInt()
                    if (tokens >= 0) result[key] = tokens
                }
            }
        } catch (_: Throwable) {
            result.clear() // 损坏就当没有，不要因为缓存把功能弄坏
        }
        return result
    }

    private fun write(target: File, vocab: Int, snapshot: List<Map.Entry<Long, Int>>) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).buffered().let { raw ->
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(vocab)
                out.writeInt(snapshot.size)
                snapshot.forEach { (key, tokens) ->
                    out.writeLong(key)
                    out.writeInt(tokens)
                }
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
