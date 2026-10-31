package app.androidrik.workspace

/**
 * Rootfs 下载源：一个镜像站加上它在 cdimage 目录树下的前缀。
 *
 * 各镜像对 `ubuntu-cdimage` 的挂载路径不同（官方直接挂在域名根下，镜像站普遍带
 * `/ubuntu-cdimage` 前缀），因此前缀随镜像走，文件名随目标架构走。
 */
data class RootfsMirror(
    val id: String,
    val displayName: String,
    private val baseUrl: String,
) {
    fun urlFor(fileName: String): String = "$baseUrl/$fileName"
}

/**
 * 一份可安装的 Rootfs 发行版镜像清单。
 *
 * 目前只提供 Ubuntu Base LTS：它是纯用户态最小镜像（无内核、无 systemd），
 * 与 PRoot 的 ptrace 拦截模型匹配，且 apt 生态比 Alpine 完整得多。
 * 文件名里的架构段必须与设备 primary ABI 一致，否则 rootfs 里的二进制根本跑不起来。
 */
object RootfsCatalog {

    /** Ubuntu Base 24.04 LTS (Noble Numbat)，24.04.4 为当前最新 point release */
    const val UBUNTU_NOBLE_RELEASE = "24.04.4"

    /** 发行版代号，apt 源与镜像测速路径都要用 */
    const val UBUNTU_CODENAME = "noble"

    private const val UBUNTU_BASE_DIR = "ubuntu-base/releases/24.04/release"

    /** 官方源排在首位：镜像站全部测速失败时它仍可作为兜底 */
    val MIRRORS: List<RootfsMirror> = listOf(
        RootfsMirror(
            id = "official",
            displayName = "Ubuntu 官方 (cdimage.ubuntu.com)",
            baseUrl = "https://cdimage.ubuntu.com/$UBUNTU_BASE_DIR",
        ),
        RootfsMirror(
            id = "tuna",
            displayName = "清华大学 TUNA",
            baseUrl = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/$UBUNTU_BASE_DIR",
        ),
        RootfsMirror(
            id = "aliyun",
            displayName = "阿里云",
            baseUrl = "https://mirrors.aliyun.com/ubuntu-cdimage/$UBUNTU_BASE_DIR",
        ),
        RootfsMirror(
            id = "nju",
            displayName = "南京大学",
            baseUrl = "https://mirrors.nju.edu.cn/ubuntu-cdimage/$UBUNTU_BASE_DIR",
        ),
        RootfsMirror(
            id = "bfsu",
            displayName = "北外镜像站",
            baseUrl = "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/$UBUNTU_BASE_DIR",
        ),
        RootfsMirror(
            id = "huawei",
            displayName = "华为云",
            baseUrl = "https://mirrors.huaweicloud.com/ubuntu-cdimage/$UBUNTU_BASE_DIR",
        ),
    )

    /**
     * 把 Android ABI 名映射到 Ubuntu 的架构标识。
     *
     * 只覆盖 workspace 模块实际打包的 ABI（arm64-v8a / x86_64）；其余架构没有
     * PRoot 原生支持，返回 null 让调用方回落到 arm64 而不是猜一个错误文件名。
     */
    fun archSlugForAbi(abi: String): String? = when (abi) {
        "arm64-v8a", "aarch64" -> "arm64"
        "x86_64", "amd64" -> "amd64"
        else -> null
    }

    fun fileNameForAbi(abi: String): String? = archSlugForAbi(abi)?.let { arch ->
        "ubuntu-base-$UBUNTU_NOBLE_RELEASE-base-$arch.tar.gz"
    }

    /** 下载与镜像测速共用的 UA; 部分镜像站/CDN 会对 Java 默认 UA 回 403 */
    const val DOWNLOAD_USER_AGENT = "RikkaHub-Rootfs"

    /** 设备 ABI 对应的默认下载地址（官方源） */
    fun defaultUrlForAbi(abi: String): String? =
        fileNameForAbi(abi)?.let { MIRRORS.first().urlFor(it) }
}
