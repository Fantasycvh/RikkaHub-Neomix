package app.androidrik.workspace

import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** 被一键配置的包管理器。 */
enum class PackageManager { APT, NPM, PIP, GO }

/**
 * 一个候选镜像站。
 *
 * [url] 是最终写进配置文件（或回显给用户）的地址，[probeUrl] 是测速用的、确定存在的小文件。
 * 两者分开是因为各站的目录结构不同：apt 仓库要拼到 `dists/<suite>/Release`，pip 要拼到
 * 某个具体包的 simple 索引页。
 */
data class MirrorSite(
    val manager: PackageManager,
    val id: String,
    val displayName: String,
    val url: String,
    val probeUrl: String,
)

/** 某个管理器实际写入的配置：站点名 + 地址 + 测到的吞吐，供 UI 回显。 */
data class PackageMirrorPick(
    val manager: PackageManager,
    val displayName: String,
    val url: String,
    val bytesPerSecond: Long,
)

/** 一次「一键配置」的结果。 */
data class PackageMirrorOutcome(
    val setup: PackageMirrorSetup,
    val picks: List<PackageMirrorPick>,
)

/**
 * 写进 rootfs 的镜像配置摘要。
 *
 * 序列化后存在工作区记录上：既给设置页显示「当前配了什么」，也让系统提示词能告诉 AI
 * 哪些源已经是国内地址、遇到国外地址下不动时该换成哪一家。
 */
@Serializable
data class PackageMirrorSetup(
    val configuredAt: Long = 0,
    val aptRepoUrl: String = "",
    val npmRegistry: String = "",
    val pipIndexUrl: String = "",
    val goProxy: String = "",
) {
    val active: Boolean
        get() = aptRepoUrl.isNotBlank() ||
            npmRegistry.isNotBlank() ||
            pipIndexUrl.isNotBlank() ||
            goProxy.isNotBlank()
}

/**
 * 国内镜像源候选清单。
 *
 * 每个管理器都把官方源留在候选里兜底：国内站点偶发限流或维护时，测速会把官方源排回第一，
 * 配置逻辑因而不需要专门处理「所有国内站都不可用」。
 */
object PackageMirrorCatalog {

    /**
     * apt 镜像站。arm64 等非 amd64 架构的包在 `ubuntu-ports` 池里，与 `ubuntu` 池内容不同，
     * 换错目录 apt 会直接找不到包，因此按目标架构选池而不是统一写死。
     *
     * 站点根地址一律用 http：ubuntu-base 镜像里没装 ca-certificates，https 源会直接失效。
     */
    data class AptSite(val id: String, val displayName: String, val host: String) {
        fun repoUrl(ports: Boolean): String =
            "$host/" + if (ports) "ubuntu-ports" else "ubuntu"

        fun site(ports: Boolean, suite: String): MirrorSite {
            val repo = repoUrl(ports)
            return MirrorSite(
                manager = PackageManager.APT,
                id = id,
                displayName = displayName,
                url = repo,
                probeUrl = "$repo/dists/$suite/Release",
            )
        }
    }

    /**
     * 候选清单只收国内站：官方源本来就是默认值，把它当成「测速胜出的镜像源」既没有提升，
     * 还会让提示词谎称环境已优化。全部国内站不可达时该管理器直接不配，UI 与提示词里就都不出现。
     */
    val APT_SITES: List<AptSite> = listOf(
        AptSite("tuna", "清华大学 TUNA", "http://mirrors.tuna.tsinghua.edu.cn"),
        AptSite("aliyun", "阿里云", "http://mirrors.aliyun.com"),
        AptSite("ustc", "中国科学技术大学", "http://mirrors.ustc.edu.cn"),
        AptSite("nju", "南京大学", "http://mirrors.nju.edu.cn"),
        AptSite("huawei", "华为云", "http://mirrors.huaweicloud.com"),
        AptSite("tencent", "腾讯云", "http://mirrors.cloud.tencent.com"),
        AptSite("zju", "浙江大学", "http://mirrors.zju.edu.cn"),
        AptSite("bfsu", "北京外国语大学", "http://mirrors.bfsu.edu.cn"),
    )

    val NPM_SITES: List<MirrorSite> = listOf(
        site(PackageManager.NPM, "npmmirror", "npmmirror（阿里）", "https://registry.npmmirror.com/", "mime"),
    )

    val PIP_SITES: List<MirrorSite> = listOf(
        site(PackageManager.PIP, "aliyun", "阿里云", "https://mirrors.aliyun.com/pypi/simple/", "requests/"),
        site(PackageManager.PIP, "tuna", "清华大学 TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple/", "requests/"),
        site(PackageManager.PIP, "ustc", "中国科学技术大学", "https://mirrors.ustc.edu.cn/pypi/web/simple/", "requests/"),
        site(PackageManager.PIP, "nju", "南京大学", "https://mirrors.nju.edu.cn/pypi/web/simple/", "requests/"),
        site(PackageManager.PIP, "tencent", "腾讯云", "https://mirrors.cloud.tencent.com/pypi/simple/", "requests/"),
        site(PackageManager.PIP, "huawei", "华为云", "https://mirrors.huaweicloud.com/repository/pypi/simple/", "requests/"),
    )

    /** GOPROXY 只写代理主机，配置里再补 `,direct`，私有仓库仍能回落到 git 拉取。 */
    val GO_SITES: List<MirrorSite> = listOf(
        site(PackageManager.GO, "goproxy.cn", "七牛 goproxy.cn", "https://goproxy.cn", "github.com/pkg/errors/@v/list"),
        site(PackageManager.GO, "aliyun", "阿里云", "https://mirrors.aliyun.com/goproxy", "github.com/pkg/errors/@v/list"),
        site(PackageManager.GO, "goproxy.io", "goproxy.io", "https://goproxy.io", "github.com/pkg/errors/@v/list"),
    )

    fun aptSites(ports: Boolean, suite: String): List<MirrorSite> = APT_SITES.map { it.site(ports, suite) }

    private fun site(
        manager: PackageManager,
        id: String,
        displayName: String,
        url: String,
        probePath: String,
    ): MirrorSite = MirrorSite(
        manager = manager,
        id = id,
        displayName = displayName,
        url = url,
        probeUrl = url.trimEnd('/') + "/" + probePath,
    )
}

/**
 * 「一键配置国内镜像源」的执行者：并行测速 → 每个包管理器挑最快的一家 → 写进 rootfs。
 *
 * 只改配置文件、不装任何包；apt 索引能否真正刷新由调用方随后跑一次 `apt-get update` 验证。
 */
class PackageMirrorConfigurator(
    private val sampleBytes: Long = DEFAULT_SAMPLE_BYTES,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MS,
) {

    suspend fun configure(linuxDir: File): PackageMirrorOutcome = withContext(Dispatchers.IO) {
        val ports = AptSources.usesPortsRepo(linuxDir)
        val suite = AptSources.detectSuite(linuxDir, RootfsCatalog.UBUNTU_CODENAME)
        val candidates = PackageMirrorCatalog.aptSites(ports, suite) +
            PackageMirrorCatalog.NPM_SITES +
            PackageMirrorCatalog.PIP_SITES +
            PackageMirrorCatalog.GO_SITES

        val picks = probeAll(candidates)
        applyPicks(linuxDir, picks)

        PackageMirrorOutcome(
            setup = PackageMirrorSetup(
                configuredAt = System.currentTimeMillis(),
                aptRepoUrl = picks.urlOf(PackageManager.APT),
                npmRegistry = picks.urlOf(PackageManager.NPM),
                pipIndexUrl = picks.urlOf(PackageManager.PIP),
                goProxy = picks.urlOf(PackageManager.GO),
            ),
            picks = picks.map {
                PackageMirrorPick(it.site.manager, it.site.displayName, it.site.url, it.bytesPerSecond)
            },
        )
    }

    /** 并行测速，每个管理器取吞吐最高的一家；某家管理器全部不可用时它整体缺席。 */
    private suspend fun probeAll(candidates: List<MirrorSite>): List<ProbedSite> = coroutineScope {
        candidates.map { site ->
            async {
                val result = HttpProbe.probe(
                    url = site.probeUrl,
                    sampleBytes = sampleBytes,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                )
                result.bytesPerSecond to site
            }
        }.awaitAll()
    }.filter { (bytes, _) -> bytes > 0 }
        .groupBy { (_, site) -> site.manager }
        .mapNotNull { (manager, probed) ->
            probed.maxByOrNull { (bytes, _) -> bytes }?.let { (bytes, site) ->
                ProbedSite(manager, site, bytes)
            }
        }
        .sortedBy { it.manager.ordinal }

    private fun applyPicks(linuxDir: File, picks: List<ProbedSite>) {
        picks.forEach { pick ->
            when (pick.manager) {
                PackageManager.APT -> {
                    val root = PackageMirrorCatalog.APT_SITES.firstOrNull { it.id == pick.site.id }
                        ?.host
                        ?: URI(pick.site.url).let { "${it.scheme}://${it.host}" }
                    AptSources.rewriteTo(linuxDir, root, explicit = true)
                }

                PackageManager.NPM -> writeNpmrc(linuxDir, pick.site.url)
                PackageManager.PIP -> writePipConf(linuxDir, pick.site.url)
                PackageManager.GO -> writeGoProfile(linuxDir, pick.site.url)
            }
        }
    }

    private fun writeNpmrc(linuxDir: File, registry: String) {
        val rootDir = File(linuxDir, "root").apply { mkdirs() }
        File(rootDir, NPMRC).writeText(
            buildString {
                appendLine("# Generated by RikkaHub workspace: 国内镜像源")
                appendLine("registry=$registry")
                if (registry.contains("npmmirror.com")) {
                    // 大量 npm 包的安装脚本会从 GitHub Releases 拉预编译二进制，国内基本下不动；
                    // npmmirror 同步了这些目录，一并写进用户级 .npmrc 才能免掉每次传参
                    appendLine("disturl=https://npmmirror.com/mirrors/node/")
                    appendLine("electron_mirror=https://npmmirror.com/mirrors/electron/")
                    appendLine("sass_binary_site=https://npmmirror.com/mirrors/node-sass/")
                    appendLine("sharp_binary_host=https://npmmirror.com/mirrors/sharp")
                    appendLine("sharp_libvips_binary_host=https://npmmirror.com/mirrors/sharp-libvips")
                    appendLine("puppeteer_download_base_url=https://cdn.npmmirror.com/binaries/chrome-for-testing")
                }
            }
        )
    }

    private fun writePipConf(linuxDir: File, indexUrl: String) {
        val etcDir = File(linuxDir, "etc").apply { mkdirs() }
        val host = runCatching { URI(indexUrl).host }.getOrNull()
        File(etcDir, PIP_CONF).writeText(
            buildString {
                appendLine("# Generated by RikkaHub workspace: 国内镜像源")
                appendLine("[global]")
                appendLine("index-url = $indexUrl")
                if (!host.isNullOrBlank()) {
                    appendLine("trusted-host = $host")
                }
            }
        )
    }

    /**
     * Go 没有全局配置文件，靠 `/etc/profile.d` 注入环境变量。
     *
     * shell 工具与交互终端都以 `bash -l` 启动，会读 /etc/profile 并 source 这里的脚本。
     */
    private fun writeGoProfile(linuxDir: File, proxyUrl: String) {
        val profileD = File(File(linuxDir, "etc"), "profile.d").apply { mkdirs() }
        File(profileD, GO_PROFILE).writeText(
            buildString {
                appendLine("# Generated by RikkaHub workspace: 国内 Go module 代理")
                appendLine("export GOPROXY='$proxyUrl,direct'")
                appendLine("export GOSUMDB='sum.golang.google.cn'")
            }
        )
    }

    private fun List<ProbedSite>.urlOf(manager: PackageManager): String =
        firstOrNull { it.manager == manager }?.site?.url.orEmpty()

    /** 测速后的候选：站点 + 实测吞吐。 */
    private data class ProbedSite(
        val manager: PackageManager,
        val site: MirrorSite,
        val bytesPerSecond: Long,
    )

    private companion object {
        private const val DEFAULT_SAMPLE_BYTES = 64L * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val NPMRC = ".npmrc"
        private const val PIP_CONF = "pip.conf"
        private const val GO_PROFILE = "rikkahub-mirrors.sh"
    }
}
