package app.androidrik.workspace

import java.io.File

/**
 * Rootfs 内 apt 源文件的统一改写器。
 *
 * Ubuntu 24.04 (noble) 的 ubuntu-base 镜像里 `/etc/apt/sources.list` 只剩一段「源已搬家」的
 * 注释，真正的源在 `/etc/apt/sources.list.d/ubuntu.sources`（deb822 格式，arm64 指向
 * `ports.ubuntu.com/ubuntu-ports`）。只改 sources.list 等于什么都没改，apt 仍然直连国外
 * 官方站，国内网络下 `apt-get install` 会挂到超时，被 AI 当成「环境不可用」。
 * 因此这里两种格式一起处理，并且沿用文件里原本的资源池目录：`ubuntu`（amd64）与
 * `ubuntu-ports`（其余架构）内容不同，换错目录会直接找不到包。
 */
object AptSources {

    /** `etc/` 下记录当前生效镜像站的标记文件，内容形如 `explicit http://mirrors.tuna.tsinghua.edu.cn` */
    private const val MARKER = "rikkahub-mirror"
    private const val BACKUP_SUFFIX = ".orig"

    /** Ubuntu 官方仓库主机名（amd64 走 archive，其余架构走 ports，security 是第三个别名） */
    private val OFFICIAL_URL =
        Regex("""https?://(?:archive|ports|security)\.ubuntu\.com(/[A-Za-z0-9._-]+)?""")

    /** 装机后 apt 的默认落点：国内可达性最好的站点之一。base 镜像没有 ca-certificates，只能用 http。 */
    const val DEFAULT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn"

    /** 当前生效的镜像站根地址；从未改写过时返回 null。 */
    fun appliedMirror(linuxDir: File): String? = markerValue(linuxDir)?.get(1)

    /** 是否由用户显式配置过（而不是装机默认）。默认改写不得覆盖用户的选择。 */
    fun isExplicit(linuxDir: File): Boolean = markerValue(linuxDir)?.get(0) == KIND_EXPLICIT

    /**
     * 把所有 apt 源文件里的官方仓库地址换成 [mirrorRoot]，返回是否发生了写入。
     *
     * 以首次改写留下的 `.orig` 备份为基准重放，所以换站是「从官方源重新改一次」而不是
     * 在上一家的结果上继续替换，重复点「一键配置」或事后回退都不会越改越乱。
     */
    fun rewriteTo(linuxDir: File, mirrorRoot: String, explicit: Boolean): Boolean {
        val root = mirrorRoot.trimEnd('/')
        val kind = if (explicit) KIND_EXPLICIT else KIND_DEFAULT
        if (markerValue(linuxDir)?.joinToString(" ") == "$kind $root") return false

        var changed = false
        sourceFiles(linuxDir).forEach { file ->
            if (rewriteFile(file, root)) changed = true
        }
        markerFile(linuxDir)?.writeText("$kind $root\n")
        return changed
    }

    /**
     * rootfs 里的源是否指向 `ubuntu-ports`（即非 amd64 架构）。
     *
     * 测速要拼出真实存在的仓库路径，猜错目录会全线 404；以文件内容为准比按设备 ABI 猜可靠。
     */
    fun usesPortsRepo(linuxDir: File): Boolean {
        val texts = sourceFiles(linuxDir).flatMap { file ->
            listOfNotNull(
                runCatching { File(file.parentFile, file.name + BACKUP_SUFFIX).readText() }.getOrNull(),
                runCatching { file.readText() }.getOrNull(),
            )
        }
        if (texts.none { it.isNotBlank() }) return true
        return texts.any { it.contains("ubuntu-ports") || it.contains("ports.ubuntu.com") }
    }

    /** 源里声明的第一个发行版代号（noble / jammy ...），测速路径要用；读不到时回落到 [fallback]。 */
    fun detectSuite(linuxDir: File, fallback: String): String {
        sourceFiles(linuxDir).forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("#")) return@forEach
                // deb822: `Suites: noble noble-updates ...`
                if (trimmed.startsWith("Suites:")) {
                    trimmed.removePrefix("Suites:").trim().tokenSequence().firstOrNull()?.let { return it }
                }
                // one-line: `deb http://host/ubuntu noble main ...`
                if (trimmed.startsWith("deb ") || trimmed.startsWith("deb-src ")) {
                    trimmed.tokenSequence().getOrNull(2)?.let { return it }
                }
            }
        }
        return fallback
    }

    private fun markerValue(linuxDir: File): List<String>? = runCatching {
        markerFile(linuxDir)?.readText()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }
    }.getOrNull()?.takeIf { it.size >= 2 }

    private fun markerFile(linuxDir: File): File? {
        val aptDir = File(linuxDir, "etc/apt")
        if (!aptDir.isDirectory) return null
        return File(aptDir, MARKER)
    }

    private fun sourceFiles(linuxDir: File): List<File> {
        val aptDir = File(linuxDir, "etc/apt")
        val single = File(aptDir, "sources.list")
        val listD = File(aptDir, "sources.list.d")
        val fragments = listD.listFiles()?.filter {
            it.isFile && (it.name.endsWith(".list") || it.name.endsWith(".sources"))
        }.orEmpty()
        return (listOf(single).filter { it.isFile } + fragments.sortedBy { it.name })
    }

    /** 单个源文件的改写；内容真正变化才写，并保留一份官方原始内容作为重放基准。 */
    private fun rewriteFile(file: File, root: String): Boolean {
        if (!file.isFile) return false
        val backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
        val current = runCatching { file.readText() }.getOrNull() ?: return false
        // 有备份时从备份重放，避免「镜像站的镜像站」；没备份则当前内容就是官方原始内容
        val base = if (backup.isFile) runCatching { backup.readText() }.getOrNull() ?: current else current
        val rewritten = replaceOfficialHosts(base, root)
        if (rewritten == current) return false
        if (!backup.isFile) runCatching { file.copyTo(backup, overwrite = false) }
        file.writeText(rewritten)
        return true
    }

    private fun replaceOfficialHosts(text: String, root: String): String {
        val lines = text.lineSequence().map { line ->
            // 注释行保持原样：用户可能自己取消注释，也避免把示例里的官方地址当成生效源
            if (line.trimStart().startsWith("#")) return@map line
            OFFICIAL_URL.replace(line) { match ->
                val repoPath = match.groupValues[1]
                if (repoPath.isBlank()) match.value else "$root$repoPath"
            }
        }.toList()
        return lines.joinToString("\n") + if (text.endsWith("\n")) "\n" else ""
    }

    private fun String.tokenSequence(): List<String> = split(Regex("\\s+")).filter { it.isNotBlank() }

    private const val KIND_DEFAULT = "default"
    private const val KIND_EXPLICIT = "explicit"
}
