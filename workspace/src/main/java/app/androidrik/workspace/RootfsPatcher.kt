package app.androidrik.workspace

import java.io.File
import java.nio.file.Files

/**
 * Android 系统证书库的候选路径。
 *
 * `conscrypt` 的 apex 排第一：Android 14+ 把 CA 库移到了 apex，`/system/etc/security/cacerts`
 * 在部分版本上只是兼容软链。三个都扫，**同名按先到先得合并**（见 ensureCaCertificates 里的
 * `seen`）—— 真机上 `/system/...` 与 apex 就是同一批 145 张，不去重会让 bundle 翻倍。
 */
private val DEFAULT_CA_SOURCE_DIRS = listOf(
    "/apex/com.android.conscrypt/cacerts",
    "/system/etc/security/cacerts",
    "/system/etc/cacerts",
)

class RootfsPatcher {
    fun patch(
        linuxDir: File,
        options: RootfsPatchOptions = RootfsPatchOptions(),
    ) {
        // 互斥到类级：前台命令（ProotShellRunner.start 每条命令都 patch）、后台任务启动、
        // 终端页各自 new 的实例都可能并发 patch 同一个 rootfs——这些读-改-写非原子的
        // 关键文件（ca bundle / resolv.conf / group / hosts）交错写入会留下截断的
        // ca-certificates.crt（容器内 https 全部**静默**失败）或重复行。
        // patch 是幂等且毫秒级的（不变即不写），串行化代价可忽略。
        synchronized(RootfsPatcher::class.java) {
            patchLocked(linuxDir, options)
        }
    }

    private fun patchLocked(
        linuxDir: File,
        options: RootfsPatchOptions,
    ) {
        val etcDir = File(linuxDir, "etc")
        if (!etcDir.isDirectory) return

        // 证书放最前且**放在任何短路之前**：ubuntu-base 裸镜像不含 ca-certificates，
        // 不补的话容器内一切 https 都是静默失败（git/pip/npm 报"证书验证失败"、curl 报 000），
        // 而 apt 装证书本身又依赖网络 ⇒ 这里直接复用 **Android 系统证书库**离线生成，
        // 新装的与**已存在的** rootfs 都会在下一次会话启动时自愈（ProotShellRunner 每次 start 都调 patch）。
        ensureCaCertificates(etcDir, options.caSourceDirs)
        ensureRootfsDns(etcDir, options.nameservers)
        ensureHosts(etcDir, options.hostname)
        ensureHostname(etcDir, options.hostname)
        ensureLocale(etcDir, options.locale)
        ensureGroupNames(etcDir, options.groupIds.ifEmpty { currentSupplementaryGroupIds() })
        ensureTempDirs(linuxDir)
    }

    /**
     * 用 Android 系统证书库离线生成 rootfs 的 CA bundle。
     *
     * 产出两样东西，缺一不可（不同工具读不同路径）：
     *  - `etc/ssl/certs/ca-certificates.crt`：Debian/Ubuntu 系 curl/openssl/git 的默认信任链
     *  - `etc/ssl/certs/<hash>.0`：Android 原有哈希名逐张拷贝，给按目录查找的调用方
     *
     * **与参考实现（Yuihub `8a3a37ac`）的唯一偏差**：它用「bundle 非空就短路」，
     * 本仓改成「**内容变了才写**」。理由是 patch 在本仓是**每次会话启动**都跑的
     * （`ProotShellRunner.start` 第 68 行），若按"非空即返回"，证书库就永远停在第一次
     * 生成的快照 —— Android 侧更新 CA（系统升级/cons?rypt apex 更新）后 rootfs 仍信旧库，
     * 而这类失败的形态恰恰是**静默**的。按内容比对既保留幂等（不变就不写），
     * 又不把"每次读几个文件"的成本变成"每次写一个文件"。
     */
    private fun ensureCaCertificates(etcDir: File, sourceDirs: List<String>) {
        val sslCertsDir = File(etcDir, "ssl/certs")
        val sources = sourceDirs.map(::File).filter { it.isDirectory }
        if (sources.isEmpty()) return

        val builder = StringBuilder()
        // **跨来源按文件名去重**（真机验出来的：不去重会让每张证书进 bundle 两遍）。
        // Android 14+ 上 `/system/etc/security/cacerts` 与 apex 那份**就是同一批证书**
        // （145 张的来源目录被读了两遍 ⇒ bundle 里 290 个 BEGIN CERTIFICATE、体积翻倍）。
        // 用文件名而不是内容做键：Android 的 `<subject_hash>.<n>` 名本身由内容派生，
        // 同名即同一张；且**先到先得**——列表里 apex 排第一，正是 Android 14+ 的活库。
        val seen = mutableSetOf<String>()
        var count = 0
        sources.forEach { dir ->
            // 按名字排序：产物**确定性**（同一套输入永远得到逐字节相同的 bundle），
            // 否则"内容变了才写"会被目录枚举顺序抖动成每次都写。
            val certs = dir.listFiles { file -> file.isFile && file.name.endsWith(CA_SUFFIX) }
                ?.sortedBy { it.name }
                .orEmpty()
            certs.forEach { cert ->
                runCatching {
                    val text = cert.readText()
                    if (!text.contains(CA_PEM_MARKER)) return@runCatching
                    if (!seen.add(cert.name)) return@runCatching
                    // 逐证书副本：Android 的哈希名对 openssl 有效，直接沿用
                    val target = File(sslCertsDir, cert.name)
                    if (!target.exists()) {
                        sslCertsDir.mkdirs()
                        target.writeText(text)
                    }
                    builder.append(text)
                    if (!text.endsWith("\n")) builder.append('\n')
                    count++
                }
            }
        }
        // 一张都没读到就什么都不做：宁可保持"缺证书"的原样，也不留一个空 bundle
        // （空文件会让"非空即短路"式的判断误以为已就绪，反倒更难查）
        if (count == 0) return

        val content = builder.toString()
        val bundle = File(sslCertsDir, CA_BUNDLE_NAME)
        if (bundle.isFile && bundle.readText() == content) return

        sslCertsDir.mkdirs()
        bundle.writeText(content)
    }

    private fun ensureRootfsDns(
        etcDir: File,
        nameservers: List<String>,
    ) {
        val resolvConf = File(etcDir, "resolv.conf")
        val shouldWrite = when {
            Files.isSymbolicLink(resolvConf.toPath()) -> true
            !resolvConf.exists() -> true
            !resolvConf.isFile -> true
            else -> resolvConf.readText()
                .lineSequence()
                .filter { it.trimStart().startsWith("nameserver ") }
                .none { line ->
                    val server = line.trim().removePrefix("nameserver").trim()
                    server.isNotBlank() && server !in LOCAL_RESOLVERS
                }
        }
        if (!shouldWrite) return

        if (resolvConf.exists() || Files.isSymbolicLink(resolvConf.toPath())) {
            resolvConf.delete()
        }

        val servers = nameservers
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_DNS_SERVERS)
            .ifEmpty { DEFAULT_DNS_SERVERS }

        resolvConf.writeText(
            buildString {
                appendLine("# Generated by RikkaHub workspace.")
                servers.forEach { appendLine("nameserver $it") }
                appendLine("options edns0 trust-ad")
            }
        )
    }

    private fun ensureHosts(etcDir: File, hostname: String) {
        val hosts = File(etcDir, "hosts")
        val lines = if (hosts.isFile) hosts.readLines() else emptyList()
        val hasIpv4Localhost = lines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "127.0.0.1" && "localhost" in normalized.drop(1)
        }
        val hasIpv6Localhost = lines.any { line ->
            val normalized = line.substringBefore('#').trim().split(WHITESPACE_REGEX)
            normalized.firstOrNull() == "::1" && "localhost" in normalized.drop(1)
        }
        if (hasIpv4Localhost && hasIpv6Localhost) return

        hosts.parentFile?.mkdirs()
        hosts.appendText(
            buildString {
                if (hosts.exists() && hosts.length() > 0 && !hosts.readText().endsWith('\n')) {
                    appendLine()
                }
                if (!hasIpv4Localhost) {
                    append("127.0.0.1 localhost")
                    if (hostname.isNotBlank() && hostname != "localhost") {
                        append(" ")
                        append(hostname)
                    }
                    appendLine()
                }
                if (!hasIpv6Localhost) {
                    appendLine("::1 localhost ip6-localhost ip6-loopback")
                }
            }
        )
    }

    private fun ensureHostname(etcDir: File, hostname: String) {
        val target = File(etcDir, "hostname")
        if (target.isFile && target.readText().trim().isNotBlank()) return
        target.writeText("${hostname.ifBlank { DEFAULT_HOSTNAME }}\n")
    }

    private fun ensureLocale(etcDir: File, locale: String) {
        val defaultDir = File(etcDir, "default").apply { mkdirs() }
        val target = File(defaultDir, "locale")
        val lines = if (target.isFile) target.readLines().toMutableList() else mutableListOf()
        if (lines.any { it.trim().startsWith("LANG=") }) return
        lines += "LANG=$locale"
        target.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun ensureGroupNames(etcDir: File, groupIds: List<Long>) {
        val target = File(etcDir, "group")
        if (!target.exists()) {
            target.writeText("root:x:0:\n")
        }
        val lines = target.readLines().toMutableList()
        val existingIds = lines.mapNotNull { line ->
            line.split(':').getOrNull(2)?.toLongOrNull()
        }.toSet()
        val existingNames = lines.mapNotNull { line ->
            line.substringBefore(':').takeIf { it.isNotBlank() }
        }.toSet()
        val additions = groupIds
            .filter { it > 0 && it !in existingIds }
            .distinct()
            .map { id ->
                val baseName = "android_gid_$id"
                val name = if (baseName in existingNames) "${baseName}_workspace" else baseName
                "$name:x:$id:"
            }
        if (additions.isEmpty()) return

        target.appendText(
            buildString {
                if (target.length() > 0 && !target.readText().endsWith('\n')) {
                    appendLine()
                }
                additions.forEach { appendLine(it) }
            }
        )
    }

    private fun ensureTempDirs(linuxDir: File) {
        listOf("tmp", "var/tmp", "root").forEach { path ->
            File(linuxDir, path).mkdirs()
        }
        listOf(File(linuxDir, "tmp"), File(linuxDir, "var/tmp")).forEach { dir ->
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
        File(linuxDir, "root").apply {
            setReadable(true, true)
            setWritable(true, true)
            setExecutable(true, true)
        }
    }

    private fun currentSupplementaryGroupIds(): List<Long> {
        val status = File("/proc/self/status")
        if (!status.isFile) return emptyList()
        val groups = status.readLines().firstOrNull { it.startsWith("Groups:") } ?: return emptyList()
        return groups
            .removePrefix("Groups:")
            .trim()
            .split(WHITESPACE_REGEX)
            .mapNotNull { it.toLongOrNull() }
    }

    private companion object {
        private const val CA_BUNDLE_NAME = "ca-certificates.crt"
        private const val CA_SUFFIX = ".0"
        private const val CA_PEM_MARKER = "BEGIN CERTIFICATE"
        private const val MAX_DNS_SERVERS = 3
        private const val DEFAULT_HOSTNAME = "localhost"
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val LOCAL_RESOLVERS = setOf(
            "127.0.0.1",
            "127.0.0.53",
            "::1",
        )
        private val DEFAULT_DNS_SERVERS = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "223.5.5.5",
        )
    }
}

data class RootfsPatchOptions(
    val nameservers: List<String> = emptyList(),
    /**
     * CA 证书来源目录。默认取 Android 系统证书库（conscrypt apex 优先，新系统上
     * `/system/etc/security/cacerts` 可能只是兼容软链）。测试注入临时目录。
     */
    val caSourceDirs: List<String> = DEFAULT_CA_SOURCE_DIRS,
    val hostname: String = "localhost",
    val locale: String = "C.UTF-8",
    val groupIds: List<Long> = emptyList(),
)
