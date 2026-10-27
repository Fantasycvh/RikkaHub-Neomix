package app.androidrik.rikkahub.data.ai

import android.util.Log
import app.androidrik.rikkahub.data.files.writeFileAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P0 净化观察的**持久计数器**（`design-context-purification.md` §7 的「持久化缺口」）。
 *
 * 背景：P0 dry-run 的命中只写 Logcat `purge.dryrun`（main 环形缓冲，滚动即丢）与
 * [PromptDebugState] 的内存 LRU（进程死即丢）⇒ §12 已定 1 的两周双口径（**命中会话占比** /
 * **命中会话残留字符量中位数**）与零误杀判定**根本采集不到**。本对象把观察数据落到
 * `filesDir/purge-observation.json`（原子写，见 `data/files/AtomicFileWrite.kt`），只需一次
 * 进程生命周期就能结算决策门。
 *
 * 口径（与 §12 已定 1 对齐）：
 * - **请求** = 一次真实组装（工具循环的每一步都算一次）；**命中请求** = 该次 dry-run 有决策；
 * - **会话** = `debugKey`（会话 id）；一个会话只计一次，**残留量取该会话内单次请求的最大
 *   `savedChars`**（同一窗口在每步都会重复命中，累加会把量放大 N 倍——取最大值是保守下界）；
 * - **命中会话占比** = 命中会话数 / 可计会话数；**可计会话**排除「护栏停止过的会话」——
 *   §14 已证续写路径的复述判据会假阳性，这类会话的命中率不可信，先整体排除（保守口径，
 *   无法区分真/假阳性，宁可少算）；
 * - **中位数** = 各命中会话的残留量中位数（排序取中，偶数取中间两数平均）；
 * - 另记：决策条数、`savedChars` 累计与单次最大值、翻转次数（§4.1 观察项）、
 *   以及按 `provider 名/modelId` 分组的小计数。
 *
 * 约束：
 * - 只留本机、不进备份（`filesDir` 根目录下的独立 json；备份条目选的是 db/附件/工作区等）；
 * - 会话表有上限（[MAX_SESSIONS]，超出按最后活跃时间批量淘汰最旧的）：观察期足够长，
 *   不能无界增长；淘汰后该会话若再出现会重新计数（占比口径的已知近似，量级不影响 1% 决策门）；
 * - 读改写都在同一把锁里、整体原子替换：进程被杀最坏丢最后一次写，不会读到半截 JSON
 *   （解析失败按空表重建并告警）；
 * - 写入失败不抛（观察数据不能影响生成）：[writeFileAtomically] 返回 false 只记日志。
 */
internal object PurgeObservationStore {
    const val FILE_NAME = "purge-observation.json"
    private const val TAG = "PurgeObservation"
    private const val VERSION = 1

    /** 会话表上限。观察期两周内正常使用远达不到；到了也不影响决策门量级。 */
    private const val MAX_SESSIONS = 2000

    /** [summary]/[clear] 等待写盘队列排空的超时；超时只影响一致性，不影响正确性 */
    private const val FLUSH_TIMEOUT_SECONDS = 2L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 单线程写盘：`record` 在**生成协程**里被调用（可能是主线程），不能同步做整表读改写
     * （观察期后 json 可达百 KB 级）。串行队列保证「读改写」不交错，`summary` 先 flush
     * 队列再读，得到一致快照。daemon：进程退出时允许丢掉最后一次写（最坏丢一条观察）。
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "purge-observation-writer").apply { isDaemon = true }
    }
    private val lock = Any()

    @Serializable
    internal data class GroupStats(
        var requests: Long = 0,
        var hitRequests: Long = 0,
        var decisions: Long = 0,
        var savedCharsSum: Long = 0,
    )

    @Serializable
    internal data class SessionStats(
        var hitRequests: Long = 0,
        /** 该会话内单次请求的最大残留量（保守下界，见类注释口径说明） */
        var maxSavedChars: Int = 0,
        /** 该会话出现过护栏停止（§14 假阳性来源）⇒ 不进入命中率/中位数 */
        var guardStopped: Boolean = false,
        var lastSeenAtMillis: Long = 0,
    )

    @Serializable
    internal data class Data(
        val version: Int = VERSION,
        var requests: Long = 0,
        var hitRequests: Long = 0,
        var decisions: Long = 0,
        var flips: Long = 0,
        var savedCharsSum: Long = 0,
        var savedCharsMax: Int = 0,
        var groups: MutableMap<String, GroupStats> = mutableMapOf(),
        var sessions: MutableMap<String, SessionStats> = mutableMapOf(),
    )

    /** 决策门需要的汇总（§12 已定 1 的双口径 + 排除口径）。 */
    internal data class Summary(
        val requests: Long,
        val hitRequests: Long,
        val decisions: Long,
        val flips: Long,
        val savedCharsSum: Long,
        val savedCharsMax: Int,
        /** 可计会话数（已排除护栏停止过的会话） */
        val eligibleSessions: Int,
        val hitSessions: Int,
        val guardStoppedSessions: Int,
        /** 命中会话占比；可计会话为 0 时为 0 */
        val hitSessionRatio: Double,
        /** 命中会话残留量中位数（无命中会话为 0） */
        val hitSessionSavedCharsMedian: Int,
        val groups: Map<String, GroupStats>,
    )

    /**
     * 记录一次真实组装（dry-run）。
     *
     * @param sessionKey 会话 id（`debugKey`）
     * @param groupKey `provider 名/modelId`，用于按渠道分组观察（§7）
     * @param maxSessions 测试专用覆盖；生产不传
     */
    fun record(
        filesDir: File,
        sessionKey: String,
        groupKey: String,
        result: ContextPurifier.Result,
        flipped: Boolean,
        maxSessions: Int = MAX_SESSIONS,
    ) {
        val hit = result.decisions.isNotEmpty()
        writer.execute {
            synchronized(lock) {
                val file = File(filesDir, FILE_NAME)
                val data = load(file)
                data.requests++
                if (hit) data.hitRequests++
                data.decisions += result.decisions.size
                if (flipped) data.flips++
                data.savedCharsSum += result.savedChars
                if (result.savedChars > data.savedCharsMax) data.savedCharsMax = result.savedChars

                val now = System.currentTimeMillis()
                val session = data.sessions[sessionKey]
                if (session == null) {
                    evictIfNeeded(data, maxSessions)
                    data.sessions[sessionKey] = SessionStats(
                        hitRequests = if (hit) 1 else 0,
                        maxSavedChars = if (hit) result.savedChars else 0,
                        lastSeenAtMillis = now,
                    )
                } else {
                    if (hit) {
                        session.hitRequests++
                        if (result.savedChars > session.maxSavedChars) session.maxSavedChars = result.savedChars
                    }
                    session.lastSeenAtMillis = now
                }

                val group = data.groups.getOrPut(groupKey) { GroupStats() }
                group.requests++
                if (hit) {
                    group.hitRequests++
                    group.decisions += result.decisions.size
                }
                group.savedCharsSum += result.savedChars
                save(file, data)
            }
        }
    }

    /**
     * 标记会话出现过护栏停止（§14：续写复述判据的假阳性来源）。
     *
     * 只置标记不改计数：命中率与中位数在 [summary] 里排除这些会话。**没有返回值**——
     * 观察数据不能影响生成主流程。
     */
    fun markGuardStopped(filesDir: File, sessionKey: String) {
        writer.execute {
            synchronized(lock) {
                val file = File(filesDir, FILE_NAME)
                val data = load(file)
                val session = data.sessions.getOrPut(sessionKey) { SessionStats() }
                session.guardStopped = true
                session.lastSeenAtMillis = System.currentTimeMillis()
                save(file, data)
            }
        }
    }

    /** 汇总；`null` = 还没有任何观察数据（UI 显示「尚无数据」）。 */
    fun summary(filesDir: File): Summary? {
        // 先把已排队的写盘任务跑完，保证结算读到的是一条完整记录（测试也因此不需要等待）
        runCatching { writer.submit {}.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        synchronized(lock) {
            val data = load(File(filesDir, FILE_NAME))
            if (data.requests == 0L) return null
            val guardStopped = data.sessions.count { it.value.guardStopped }
            val eligible = data.sessions.values.filter { !it.guardStopped }
            val hitSessions = eligible.filter { it.hitRequests > 0 }
            val median = medianOf(hitSessions.map { it.maxSavedChars })
            return Summary(
                requests = data.requests,
                hitRequests = data.hitRequests,
                decisions = data.decisions,
                flips = data.flips,
                savedCharsSum = data.savedCharsSum,
                savedCharsMax = data.savedCharsMax,
                eligibleSessions = eligible.size,
                hitSessions = hitSessions.size,
                guardStoppedSessions = guardStopped,
                hitSessionRatio = if (eligible.isEmpty()) 0.0 else hitSessions.size.toDouble() / eligible.size,
                hitSessionSavedCharsMedian = median,
                groups = data.groups.toMap(),
            )
        }
    }

    /** 测试/诊断用：清空本机持久数据。 */
    internal fun clear(filesDir: File) {
        runCatching { writer.submit {}.get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        synchronized(lock) {
            File(filesDir, FILE_NAME).delete()
        }
    }

    // ---- 内部：读写 ----

    private fun load(file: File): Data {
        if (!file.isFile) return Data()
        return runCatching { json.decodeFromString(Data.serializer(), file.readText()) }
            .getOrElse {
                // 损坏（半截写、手工改动）：告警后按空表重建；下一次 save 会整体覆盖
                Log.w(TAG, "failed to read ${file.absolutePath}, resetting observation data", it)
                Data()
            }
    }

    private fun save(file: File, data: Data) {
        val ok = writeFileAtomically(file, json.encodeToString(Data.serializer(), data))
        if (!ok) Log.w(TAG, "failed to persist ${file.absolutePath} (observation continues in-memory only)")
    }

    /** 会话表超过上限时按最后活跃时间批量淘汰最旧的 10%（避免每次插入都排序）。 */
    private fun evictIfNeeded(data: Data, maxSessions: Int) {
        if (maxSessions <= 0 || data.sessions.size < maxSessions) return
        val batch = (maxSessions / 10).coerceAtLeast(1)
        data.sessions.entries
            .sortedBy { it.value.lastSeenAtMillis }
            .take(batch)
            .forEach { data.sessions.remove(it.key) }
    }

    private fun medianOf(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
