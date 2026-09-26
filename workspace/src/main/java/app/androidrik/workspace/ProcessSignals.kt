package app.androidrik.workspace

import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 进程终止与「进程树」定位。
 *
 * ## 为什么需要这个文件（真机实测结论，别按直觉改回去）
 *
 * 本仓原来用 `Process.destroyForcibly()` / `destroy()` 终止命令。**在 Android 14（小米 8）真机上
 * 这两个方法对 PRoot 子进程与「有子进程的 shell」完全无效**——实测 `kill` 后 20 秒，`ps` 里
 * proot、bash、sleep 三个进程**全都还在**，`Process.waitFor()` 永不返回（后台任务因此卡在 RUNNING，
 * 同步 shell 的「超时」也只是返回给调用方，命令继续在跑）。对照实验：
 *
 * | 场景 | 结果 |
 * |---|---|
 * | `Os.kill(pid, SIGKILL)`（相同 pid、相同命令形状） | **进程立刻死亡** ✓ |
 * | `Process.destroyForcibly()` | 20s 后进程仍在 ✗ |
 * | `Process.destroy()`（SIGTERM） | 20s 后进程仍在 ✗ |
 *
 * 所以**必须自己发信号**：先用 [findRootPidByMarker] 在 `/proc` 里按命令行标记定位进程，
 * 再用 [killProcessTree] 从叶子往根逐个 `Os.kill(SIGKILL)`。
 *
 * ## 为什么不能只杀「直接子进程」
 *
 * 杀 proot 只解决一半：实测 `Os.kill` 杀掉一个 shell 后，它的**子进程会变成孤儿（PPID=1）继续跑**。
 * 后台任务里这意味着「终止任务」之后 `pip install` 还在跑。故必须按 `/proc/<pid>/stat` 的父子关系
 * **自底向上杀整棵树**。
 *
 * ## 为什么用「命令行标记」而不是 pid
 *
 * Android 的 `java.lang.Process` **没有公开 pid**（`Process.pid()` 不在 SDK API 里，编译期就不可用）。
 * 于是每条命令在启动时带一个唯一标记（`rikkahub-task-<id>`，作为 argv 里的 `$0` 槽位），
 * 终止时按该标记在 `/proc/<pid>/cmdline` 里找回 pid——这也是 toybox `pkill -f` 的做法（真机实测可用）。
 * 只在**自己的后代**里匹配，避免误杀同 uid 的无关进程。
 */
const val TASK_MARKER_PREFIX = "rikkahub-task-"

/** `/proc` 的一行进程信息。 */
data class ProcNode(val pid: Int, val ppid: Int)

/**
 * `/proc` 访问抽象：生产实现读真 `/proc`，单测注入假表（本文件全是纯逻辑，可上 JVM 单测）。
 */
interface ProcFs {
    /** 进程表快照（pid/ppid）。 */
    fun nodes(): List<ProcNode>

    /** 该 pid 的完整命令行（空/读不到返回 null）。 */
    fun cmdline(pid: Int): String?

    /** 发 SIGKILL；进程已退出返回 false。 */
    fun kill(pid: Int): Boolean
}

/** 真实 `/proc` 实现。 */
object RealProcFs : ProcFs {

    override fun nodes(): List<ProcNode> {
        val entries = File(PROC).listFiles() ?: return emptyList()
        val result = ArrayList<ProcNode>(entries.size)
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = try {
                File(entry, "stat").readText()
            } catch (_: Exception) {
                // 权限不足（他人 uid）或进程刚退出：跳过即可，进程表本来就是不稳定的快照
                continue
            }
            val node = parseProcStat(stat) ?: continue
            result += ProcNode(pid = pid, ppid = node)
        }
        return result
    }

    override fun cmdline(pid: Int): String? = try {
        File(PROC, "$pid/cmdline").readText().replace('\u0000', ' ').trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    override fun kill(pid: Int): Boolean = try {
        Os.kill(pid, OsConstants.SIGKILL)
        true
    } catch (_: ErrnoException) {
        false
    } catch (_: Exception) {
        false
    }

    private const val PROC = "/proc"
}

/**
 * 解析 `/proc/<pid>/stat` 里 `ppid` 字段。
 *
 * 格式 `pid (comm) state ppid ...`；**comm 可能含空格与括号**（如 "(a b)"），所以必须从**最后一个**
 * `") "` 之后开始切字段，不能按第一个空格切——这是 /proc 解析最经典的坑。
 *
 * @return ppid，格式异常返回 null
 */
internal fun parseProcStat(text: String): Int? {
    val afterComm = text.substringAfterLast(") ", "")
    if (afterComm.isEmpty()) return null
    // afterComm = "state ppid pgrp ..." ⇒ 第 2 个字段是 ppid
    return afterComm.trim().split(' ').getOrNull(1)?.toIntOrNull()
}

/** 当前进程 pid（Android 侧调用；单测不经过这里）。 */
fun currentPid(): Int = AndroidProcess.myPid()

/** 生成一个进程树标记。 */
fun newTaskMarker(scope: String): String = "$TASK_MARKER_PREFIX$scope-${java.util.UUID.randomUUID().toString().take(8)}"

/**
 * 在**自己的后代**里找出命令行含 [marker] 的进程。
 *
 * 只考虑 [selfPid] 的子树：标记是随机串，理论上不会撞车，但「只在自家子树里找」把误杀的可能性
 * 直接归零（同 uid 的其它进程组、其它应用进程都不在候选里）。
 */
fun findRootPidByMarker(fs: ProcFs, selfPid: Int, marker: String): Int? {
    val nodes = fs.nodes()
    val candidates = collectSubtree(nodes, selfPid) ?: return null
    for (pid in candidates) {
        if (fs.cmdline(pid)?.contains(marker) == true) return pid
    }
    return null
}

/** [rootPid] 的全部子孙（不含自身）。 */
fun descendantsOf(fs: ProcFs, rootPid: Int): List<Int> =
    collectSubtree(fs.nodes(), rootPid).orEmpty()

/**
 * 从叶子往根杀整棵进程树，返回按执行顺序被处理的 pid。
 *
 * **必须自底向上**：先杀父进程会把子孙变成孤儿（PPID=1），之后按父子关系就再也找不到它们
 * （真机实测到的孤儿泄漏）。
 */
fun killProcessTree(fs: ProcFs, rootPid: Int, alsoRoot: Boolean = true): List<Int> {
    val ordered = descendantsOf(fs, rootPid).reversed()
    val targets = if (alsoRoot) ordered + rootPid else ordered
    return targets.filter { fs.kill(it) }
}

/**
 * 按标记定位并杀掉整棵进程树；返回被杀的进程数（0 = 没找到，通常是命令已自行结束）。
 */
fun killProcessTreeByMarker(fs: ProcFs, selfPid: Int, marker: String): Int {
    val root = findRootPidByMarker(fs, selfPid, marker) ?: return 0
    return killProcessTree(fs, root).size
}

/** 广度优先收集 [rootPid] 的子孙；[rootPid] 自身不存在时返回 null。 */
private fun collectSubtree(nodes: List<ProcNode>, rootPid: Int): List<Int>? {
    val childrenByParent = HashMap<Int, MutableList<Int>>()
    var rootFound = false
    for (node in nodes) {
        if (node.pid == rootPid) rootFound = true
        childrenByParent.getOrPut(node.ppid) { mutableListOf() } += node.pid
    }
    if (!rootFound) return null

    val result = ArrayList<Int>()
    val queue = ArrayDeque<Int>().apply { add(rootPid) }
    val seen = HashSet<Int>().apply { add(rootPid) }
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (child in childrenByParent[current].orEmpty()) {
            if (!seen.add(child)) continue // 防御 pid 复用/环
            result += child
            queue += child
        }
    }
    return result
}
