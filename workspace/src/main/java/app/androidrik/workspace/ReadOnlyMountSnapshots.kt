package app.androidrik.workspace

import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PRoot 的 `-b` 没有只读绑定。只读挂载一律使用**私有快照副本**，绝不绑定原件
 * （design-readonly-mount-snapshots §3.2，移植自 Yuihub 3.0.0 同名类）。
 *
 * ## 快速指纹：每条命令不再全量哈希（审计 #19）
 *
 * 每条 shell 命令都要确认「源有没有变、已发布代有没有被 shell 污染」。原实现对**源**做一次全量
 * SHA-256（[inspect]），再对**已发布代**做一次全量 SHA-256（`inspectPublished`）：/upload 上限
 * 1GB 时每条命令付出秒级 CPU/IO，而且整段都在存储目录的全局锁里，所有工作区命令被串行化。
 *
 * 现在改为两段式：
 *
 * 1. [quickSignature]：只做 stat 遍历（相对路径 + 类型 + 大小 + mtime + 可执行位 + symlink 目标
 *    字符串），**不读文件内容**。源的签名与 [sourceCache] 里上次的签名一致 ⇒ 直接复用上次的全量
 *    指纹，0 字节内容读取；只有签名变化才重算内容哈希并更新缓存。
 * 2. 已发布代在发布瞬间记下自己的快速签名（[Snapshot.publishedQuick]）。复用前只比较「当前代的
 *    快速签名 == 发布时记录值」，不再全量复验代目录内容（脏代判定的最终确认交给下一次发布时的
 *    全量 `copied == fingerprint`，而不是每条命令都读一遍）。
 *
 * 锁粒度不变（[storageLocks] 仍做「比较 + 决定复用/新建 + 拷贝」的单飞），但两个快速签名的 stat
 * 遍历都移到锁外，锁内只剩内存比较；需要全量哈希/拷贝的慢路径（源真的变了）仍在锁内，因为单飞
 * 的语义就是「同一源同一时刻只算一次、只拷一份」。
 *
 * **残余风险（刻意接受，换取常态路径 0 内容读取）**：
 * - 快速签名不读内容，**同大小、同 mtime 的原地内容改写不会触发换代**，直到该文件下一次被写入/
 *   替换（大小或 mtime 变化）。/upload 是用户上传目录、shell 只读视图，这个近似可接受；真正需要
 *   内容级判定的场景由「复制后全量复验」兜底（复制出来的树必须与本次指纹完全一致才发布）。
 * - 源树里的 symlink 目标字符串变化会触发重算；但 symlink 指向的内部子树内容变化**不在**快速签名
 *   覆盖范围内（遍历刻意不跟随 symlink，以避免环/逃逸判定重复做两遍）。
 *
 * ## 代际回收：租赁（审计 #20）
 *
 * 原实现里已发布代**永不删除**：shell 在快照里 `ln -s` 或写文件后，复用校验必然失败 ⇒ 每条命令
 * 都新拷一整代；64 代 / 1GB 预算用尽后所有带 /upload 的命令永久报 "budget exhausted"，只能清
 * 应用数据（shell 工具链级 DoS）。
 *
 * 生产路径改用 [prepareLeased]：返回的租约持有期间，本次下发的各代引用计数（[pins]）> 0，不会被
 * 回收；命令结束（前台命令返回 / 后台任务进程退出）后 `close()` 减计数，归零且**不再是任何 key 的
 * 当前代**的代目录立即删除。每次 [prepareLeased] 开始时还会回收「不在 [current] 且无租约」的孤儿代
 * （崩溃遗留、源被删后 stale key 的孤儿代）；stale 映射只在**挂载源真的消失**时清理——存储目录
 * 是全局共享的，不能按「本次 mounts 未出现」就清（不同工作区的挂载表可以不同）。
 * 预算 [requireCapacity] 语义不变（租约中的代照常计入字节/代数），但先回收再判预算，脏代泄漏
 * 不会再导致永久 exhausted。
 *
 * 语义澄清：shell 对快照的写入**在进程内可见**（同代复用不重拷），换代或重启后消失——这是
 * 「只读视图」的可接受近似。被污染的代不会作为下一次同步的目标（发布新代后由回收收走旧代）。
 *
 * [prepare]（旧 API）保留但语义是「**无租约、代永久保留**」：它不会回收任何东西，**仅供没有生命
 * 周期的调用方与测试使用**；生产接线一律走 [prepareLeased]，否则 #20 的预算泄漏会重现。
 *
 * 本类保护的是「经挂载点的原件」，不是「对抗 PRoot 任意宿主路径访问」。
 *
 * public：存储目录常量由 app 侧 DI（RepositoryModule）引用；prepare/prepareLeased 由 WorkspaceManager 调用。
 */
class ReadOnlyMountSnapshots(
    private val maxStorageBytes: Long = 1024L * 1024 * 1024,
    private val maxGenerations: Int = 64,
) {
    private data class Fingerprint(val revision: String, val bytes: Long)

    /** 源树的缓存：快速签名 → 该签名下的全量指纹（签名一致时指纹可直接复用） */
    private data class CachedSource(val quick: String, val fingerprint: Fingerprint)

    private data class Snapshot(
        val fingerprint: Fingerprint,
        val source: File,
        /** 发布瞬间该代目录的快速签名；之后每次复用前与它比较，判断 shell 是否污染了这一代 */
        val publishedQuick: String,
        /** 该代对应的只读挂载源（原件），stale key 判定用它确认「源是否真的没了」 */
        val mountSource: File,
    )

    /** 只读挂载的规划结果：锁外算好的源真实路径、快速签名与当前代采样 */
    private data class SnapshotPlan(
        val source: Path,
        val quick: String,
        val key: String,
        /** 锁外采样到的当前代；锁内会确认它仍是同一个 [Snapshot] 实例才采用下面的快速签名 */
        val previous: Snapshot?,
        val previousQuick: String?,
    )

    /** 本次要处理的一个挂载：可写挂载原样透传（snapshot 为 null），只读挂载带快照规划 */
    private data class Work(val mount: WorkspaceBindMount, val snapshot: SnapshotPlan?)

    /** 解析结果：下发给 shell 的挂载 + 命中的代目录（透传挂载没有代） */
    private class Resolved(val mount: WorkspaceBindMount, val generation: Path?)

    private val current = ConcurrentHashMap<String, Snapshot>()

    /** 源 → 快速签名 + 全量指纹缓存。key = 源的真实路径（plan 阶段已 toRealPath） */
    private val sourceCache = ConcurrentHashMap<String, CachedSource>()

    /** 已发布代的租约计数，key = 代目录绝对路径。只在对应存储目录的锁内读写 */
    private val pins = ConcurrentHashMap<String, Int>()

    /** 实际读取过的内容字节数（含复制时的读取），供 #19 验收测试证明「源未变时 0 内容读取」 */
    private val contentBytesRead = AtomicLong()

    @get:VisibleForTesting
    internal val contentBytesReadForTest: Long get() = contentBytesRead.get()

    /**
     * [prepareLeased] 的租约：`close()` 时把本次下发各代的引用计数减一；归零且已被取代的代目录
     * 立即删除。幂等（重复 close 无副作用）。前台命令在 `finally` 里 close，后台任务在进程退出
     * （reaper 线程收尾）后 close。
     */
    inner class Leased internal constructor(
        val mounts: List<WorkspaceBindMount>,
        private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    /**
     * 把 [mounts] 里的只读挂载替换为快照挂载（readOnly=false），其余原样透传。
     *
     * **语义 =「无租约、代永久保留」：仅供没有生命周期的调用方与测试使用。** 返回的代永远不会被
     * 回收，源每次变化都会新开一代——这正是审计 #20 的预算泄漏形态；生产路径请用 [prepareLeased]。
     *
     * 内容不变时复用同代（不重拷）；快速签名不变时连指纹也不重算（见类注释）。
     *
     * @throws IllegalArgumentException 源不是目录 / 源与存储嵌套 / symlink 逃逸或环 /
     *   复制中源变化 / 预算耗尽；由调用方决定错误呈现（本仓：向上传播，工具错误文本收场）
     */
    fun prepare(
        mounts: List<WorkspaceBindMount>,
        directory: File,
    ): List<WorkspaceBindMount> {
        val directoryPath = directory.canonicalFile.toPath()
        // 锁外：realpath + 快速签名（stat 遍历），避免把每条命令的元数据遍历塞进全局锁（#19）
        val work = plan(mounts, directoryPath)
        return synchronized(lockFor(directory)) {
            requireStorageLocked(directory, directoryPath)
            work.map { materializeLocked(directoryPath, it).mount }
        }
    }

    /**
     * 同 [prepare]，但返回**带引用计数的租约**：调用方必须在命令/任务生命周期结束时 `close()`。
     * 租约持有期间这些代不会被回收；本方法开始时顺带回收孤儿代与本次 mounts 里已消失的 stale key
     * （审计 #20）。每代在一次 [prepareLeased] 里最多计一次（同一代被多个挂载命中时按目录去重）。
     */
    fun prepareLeased(
        mounts: List<WorkspaceBindMount>,
        directory: File,
    ): Leased {
        val directoryPath = directory.canonicalFile.toPath()
        val work = plan(mounts, directoryPath)
        return synchronized(lockFor(directory)) {
            requireStorageLocked(directory, directoryPath)
            collectGarbageLocked(directoryPath, work.mapNotNull { it.snapshot?.key }.toSet())
            val resolved = work.map { materializeLocked(directoryPath, it) }
            val generations = resolved.mapNotNull { it.generation }.distinctBy { it.toFile().absolutePath }
            // 引用计数在锁内 +1，保证租约返回前这些代不可能被并发 GC 收走
            generations.forEach { generation ->
                pins.merge(generation.toFile().absolutePath, 1) { existing, delta -> existing + delta }
            }
            Leased(resolved.map { it.mount }) { releaseGenerations(directory, generations) }
        }
    }

    // ---------------------------------------------------------------- 规划（锁外）

    private fun plan(mounts: List<WorkspaceBindMount>, directoryPath: Path): List<Work> =
        mounts.filter { !it.readOnly || Files.exists(it.source.toPath(), NOFOLLOW_LINKS) }.map { mount ->
            if (!mount.readOnly) return@map Work(mount, null)
            val source = mount.source.toPath().toRealPath()
            require(Files.isDirectory(source, NOFOLLOW_LINKS)) {
                "Read-only mount source is not a directory: ${mount.target}"
            }
            require(!directoryPath.startsWith(source) && !source.startsWith(directoryPath)) {
                "Read-only mount source overlaps snapshot storage: ${mount.target}"
            }
            val key = "${directoryPath.toFile().absolutePath}\u0000${mount.source.absolutePath}\u0000${mount.target}"
            // 锁外采样当前代 + 它的快速签名：这是每次命令都要做的 stat 遍历，移出锁后其它工作区
            // 命令不再被它阻塞。锁内只在确认仍是同一个 Snapshot 实例后使用这个采样值。
            val previous = current[key]
            Work(
                mount = mount,
                snapshot = SnapshotPlan(
                    source = source,
                    quick = quickSignature(source),
                    key = key,
                    previous = previous,
                    previousQuick = previous?.let { quickSignatureOrNull(it.source.toPath()) },
                ),
            )
        }

    // ---------------------------------------------------------------- 决策（锁内）

    private fun materializeLocked(directoryPath: Path, work: Work): Resolved {
        val plan = work.snapshot ?: return Resolved(work.mount, null)
        // 慢路径（仅快速签名变化时）才重算全量指纹；锁内算保证并发 prepare 不会重复哈希同一棵树
        val fingerprint = fingerprintFor(plan.source, plan.quick)
        val published = current[plan.key]
        val publishedQuick = when {
            published == null -> null
            published === plan.previous -> plan.previousQuick
            // 极罕见：锁外采样后、拿锁前有并发 prepare 已经换了代；锁内重算一次保证基于最新代判定
            else -> quickSignatureOrNull(published.source.toPath())
        }
        // 复用判定（#19/#20）：源指纹一致 + 已发布代快速签名与发布时一致（= 没被 shell 污染）
        if (published != null &&
            published.fingerprint == fingerprint &&
            published.publishedQuick == publishedQuick &&
            !Files.isSymbolicLink(published.source.toPath()) &&
            published.source.canonicalFile == published.source.absoluteFile
        ) {
            return Resolved(
                work.mount.copy(source = published.source, readOnly = false),
                published.source.toPath(),
            )
        }
        requireCapacity(directoryPath, fingerprint.bytes)
        val generation = Files.createTempDirectory(directoryPath, GENERATION_PREFIX)
        try {
            val copied = inspect(plan.source, generation)
            // 复制后全量复验：这是「内容级正确性」的最终确认（快速签名只是常态路径的短路）
            require(copied == fingerprint) {
                "Read-only mount changed while creating snapshot: ${work.mount.target}; retry"
            }
            val snapshot = Snapshot(
                fingerprint = copied,
                source = generation.toFile(),
                publishedQuick = quickSignature(generation),
                mountSource = plan.source.toFile(),
            )
            current[plan.key] = snapshot
            return Resolved(work.mount.copy(source = snapshot.source, readOnly = false), generation)
        } catch (error: Throwable) {
            discard(generation)
            throw error
        }
    }

    /**
     * 取全量指纹。快速签名与缓存一致 ⇒ 直接复用上次的指纹（0 字节内容读取，审计 #19 的核心）；
     * 不一致才重新做一次内容哈希并刷新缓存。
     */
    private fun fingerprintFor(source: Path, quick: String): Fingerprint {
        // plan 里已 toRealPath()，其字符串形式就是稳定的真实路径 key
        val cacheKey = source.toString()
        val cached = sourceCache[cacheKey]
        if (cached != null && cached.quick == quick) return cached.fingerprint
        val fingerprint = inspect(source)
        sourceCache[cacheKey] = CachedSource(quick, fingerprint)
        return fingerprint
    }

    // ---------------------------------------------------------------- 快速签名（不读内容）

    /**
     * 快速签名：SHA-256 over 确定性遍历的**元数据**摘要，绝不读文件内容。
     *
     * 记录：相对路径 + 类型（directory/file/link/other）+ 文件大小 + mtime(ms) + 可执行位 +
     * symlink 目标字符串。目录项排序与 [visit] 一致。遍历**不跟随** symlink，因此天然无环，也不需要
     * 逃逸判定（逃逸/环在真正建立快照时由 [visit] fail-closed）。
     */
    private fun quickSignature(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        visitQuick(root, "", digest)
        return digest.digest().toHex()
    }

    private fun visitQuick(path: Path, relative: String, digest: MessageDigest) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isSymbolicLink ->
                digest.record("link", relative, Files.readSymbolicLink(path).toString())

            attributes.isDirectory -> {
                digest.record("directory", relative)
                Files.newDirectoryStream(path).use { children ->
                    children.toList().sortedBy { it.fileName.toString() }.forEach { child ->
                        val name = child.fileName.toString()
                        visitQuick(child, if (relative.isEmpty()) name else "$relative/$name", digest)
                    }
                }
            }

            attributes.isRegularFile -> digest.record(
                "file",
                relative,
                attributes.size().toString(),
                attributes.lastModifiedTime().toMillis().toString(),
                Files.isExecutable(path).toString(),
            )

            // 设备/FIFO/socket 等：BasicFileAttributes 不便跨平台区分具体类型，只记确定的状态字段。
            // 这类节点在真正建代时会被 inspect() 以「unsupported file」拒绝（fail-closed），
            // 快速签名只负责「有变化就重算」。
            else -> digest.record(
                "other",
                relative,
                attributes.size().toString(),
                attributes.lastModifiedTime().toMillis().toString(),
            )
        }
    }

    private fun quickSignatureOrNull(root: Path): String? = try {
        quickSignature(root)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    // ---------------------------------------------------------------- 全量指纹 + 复制

    private fun inspect(source: Path, destination: Path? = null): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        visit(source, source, destination, "", mutableSetOf(), digest) { count ->
            contentBytesRead.addAndGet(count.toLong())
            bytes += count
            require(bytes <= maxStorageBytes) { "Read-only snapshot exceeds storage budget ($maxStorageBytes bytes)" }
        }
        return Fingerprint(digest.digest().toHex(), bytes)
    }

    private fun visit(
        sourceRoot: Path,
        source: Path,
        destination: Path?,
        relative: String,
        ancestors: MutableSet<Path>,
        digest: MessageDigest,
        accountBytes: (Int) -> Unit,
    ) {
        val resolved = source.toRealPath()
        require(resolved.startsWith(sourceRoot)) { "Read-only mount symlink escapes source: $relative" }
        val attributes = Files.readAttributes(resolved, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isDirectory -> {
                require(ancestors.add(resolved)) { "Read-only mount contains a symlink cycle: $relative" }
                try {
                    if (destination != null && relative.isNotEmpty()) Files.createDirectory(destination)
                    digest.record("directory", relative)
                    Files.newDirectoryStream(resolved).use { children ->
                        children.toList().sortedBy { it.fileName.toString() }.forEach { child ->
                            val name = child.fileName.toString()
                            visit(
                                sourceRoot, child, destination?.resolve(name),
                                if (relative.isEmpty()) name else "$relative/$name", ancestors, digest,
                                accountBytes,
                            )
                        }
                    }
                } finally {
                    ancestors.remove(resolved)
                }
            }

            attributes.isRegularFile -> {
                val contentDigest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(resolved, READ, NOFOLLOW_LINKS).use { input ->
                    val output = destination?.let { Files.newOutputStream(it, CREATE_NEW, WRITE, NOFOLLOW_LINKS) }
                    output.use {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            accountBytes(count)
                            output?.write(buffer, 0, count)
                            contentDigest.update(buffer, 0, count)
                        }
                    }
                }
                val executable = Files.isExecutable(resolved)
                if (destination != null) {
                    destination.toFile().setExecutable(executable, false)
                    Files.setLastModifiedTime(destination, attributes.lastModifiedTime())
                }
                digest.record(
                    "file", relative, executable.toString(),
                    attributes.lastModifiedTime().toString(), contentDigest.digest().toHex(),
                )
            }

            else -> throw IllegalArgumentException("Read-only mount contains an unsupported file: $relative")
        }
    }

    // ---------------------------------------------------------------- 预算 / 回收

    private fun requireCapacity(directory: Path, newBytes: Long) {
        var bytes = 0L
        var generations = 0
        Files.newDirectoryStream(directory).use { children ->
            children.forEach { child ->
                generations++
                Files.walk(child).use { paths ->
                    paths.forEach { path ->
                        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        if (attributes.isRegularFile) bytes += attributes.size()
                    }
                }
            }
        }
        require(generations < maxGenerations && bytes <= maxStorageBytes - newBytes) {
            "Read-only snapshot storage budget exhausted ($maxGenerations generations / $maxStorageBytes bytes); " +
                "retained snapshots may still be used by terminals or background processes"
        }
    }

    /**
     * 回收孤儿代（审计 #20）：先清掉本次 mounts 里已不存在的 stale key，再删掉「不在 [current] 且没有
     * 租约」的代目录（崩溃遗留、mount 被移除后的孤儿、被换代后无人引用的旧代）。调用时必须持有该存储
     * 目录的锁；只碰 [GENERATION_PREFIX] 前缀的目录（不是本类创建的目录不越权删）。
     */
    private fun collectGarbageLocked(directoryPath: Path, expectedKeys: Set<String>) {
        val storagePrefix = directoryPath.toFile().absolutePath + "\u0000"
        // stale key 只清「源真的没了」的那些：本存储目录是全局共享的，不同工作区的挂载表
        // 可以不同（/upload vs SSH 工作区的挂载）——按「不在本次 expectedKeys」就清会把别的
        // 工作区的代删掉，导致它们每条命令都重拷一份（#20 修好后的性能回归）。
        current.keys.removeAll { key ->
            key.startsWith(storagePrefix) && key !in expectedKeys &&
                current[key]?.let { !Files.exists(it.mountSource.toPath(), NOFOLLOW_LINKS) } == true
        }
        val live = current.values.mapTo(HashSet()) { it.source.absolutePath }
        Files.newDirectoryStream(directoryPath).use { children ->
            children.forEach { child ->
                if (!child.fileName.toString().startsWith(GENERATION_PREFIX)) return@forEach
                val path = child.toFile().absolutePath
                if (path in live) return@forEach
                if ((pins[path] ?: 0) > 0) return@forEach
                runCatching { discard(child) }
            }
        }
    }

    /**
     * 租约释放：按代目录减引用计数；归零且已不是任何 key 的当前代 ⇒ 立即删除。
     * 终端/后台进程「可能还在用旧代」现在由租约表达，而不是靠永不删除。
     */
    private fun releaseGenerations(directory: File, generations: List<Path>) {
        if (generations.isEmpty()) return
        synchronized(lockFor(directory)) {
            for (generation in generations) {
                val path = generation.toFile().absolutePath
                val previous = pins[path] ?: continue
                if (previous > 1) {
                    pins[path] = previous - 1
                    continue
                }
                pins.remove(path)
                if (current.values.any { it.source.absolutePath == path }) continue
                runCatching { discard(generation) }
            }
        }
    }

    private fun MessageDigest.record(vararg fields: String) {
        fields.forEach { field ->
            update(field.toByteArray(Charsets.UTF_8))
            update(0.toByte())
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun lockFor(directory: File): Any = storageLocks.getOrPut(directory.absolutePath) { Any() }

    private fun requireStorageLocked(directory: File, directoryPath: Path) {
        require(!Files.isSymbolicLink(directory.toPath())) { "Snapshot storage must not be a symlink" }
        Files.createDirectories(directoryPath)
    }

    // 只丢弃未发布的暂存树 / 已无人引用的代；Files.walk 不跟随 symlink
    private fun discard(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    companion object {
        const val DIRECTORY = "read-only-snapshots"

        /** 代目录前缀；回收只处理自己创建的目录（`Files.createTempDirectory` 的前缀） */
        private const val GENERATION_PREFIX = "generation-"

        private val storageLocks = ConcurrentHashMap<String, Any>()
    }
}
