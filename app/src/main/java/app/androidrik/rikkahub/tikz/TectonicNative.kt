package app.androidrik.rikkahub.tikz

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Native Tectonic 编译引擎（方案 B，docs/tikz-rendering-tectonic-postmortem.md 8.4）。
 *
 * 纯 Kotlin TikzRenderer 的完整性回退：子集渲染失败时用真 TeX 引擎编译（支持
 * positioning/pgfplots 等全部 TikZ 语法与中文）。引擎为 WSL 交叉编译的
 * `libtex_poc.so`（仅 arm64-v8a 打包），bundle 与中文字体从 assets 读取后常驻内存。
 *
 * 首次编译触发 format 重建（约 6-10s），结果持久化到 cacheDir/texfmt，后续 ~1s。
 */
object TectonicNative {
    private const val TAG = "TectonicNative"

    @Volatile
    private var loaded = false

    /** so 是否可用（仅打包了 arm64-v8a；其他 ABI 或加载失败时为 false） */
    val available: Boolean
        get() = runCatching { load() }.isSuccess

    private fun load() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                System.loadLibrary("tex_poc")
                loaded = true
            }
        }
    }

    // bundle + 字体 ~23MB 常驻内存；后续版本改为按需下载 + 磁盘缓存
    @Volatile private var bundleCache: ByteArray? = null
    @Volatile private var fontCache: ByteArray? = null

    private fun bundle(context: Context): ByteArray {
        bundleCache?.let { return it }
        synchronized(this) {
            if (bundleCache == null) {
                bundleCache = context.assets.open("tex/tectonic-bundle.tikzbundle").use { it.readBytes() }
            }
        }
        return bundleCache!!
    }

    private fun font(context: Context): ByteArray {
        fontCache?.let { return it }
        synchronized(this) {
            if (fontCache == null) {
                fontCache = context.assets.open("tex/simhei.ttf").use { it.readBytes() }
            }
        }
        return fontCache!!
    }

    /**
     * 编译 TikZ/LaTeX 源码为 PDF；失败返回 null（错误详情 [lastError]）。
     * 阻塞调用，须在 IO 线程执行。
     */
    fun compile(context: Context, texSource: String): ByteArray? {
        return try {
            load()
            val cacheDir = File(context.cacheDir, "texfmt").apply { mkdirs() }.absolutePath
            val pdf = compile(bundle(context), font(context), texSource.toByteArray(Charsets.UTF_8), cacheDir)
            if (pdf != null) Log.i(TAG, "native compile ok: ${pdf.size} bytes") else Log.w(TAG, "native compile failed: ${lastError()}")
            pdf
        } catch (t: Throwable) {
            Log.w(TAG, "native compile error", t)
            null
        }
    }

    fun lastErrorText(): String? = try {
        lastError()
    } catch (t: Throwable) {
        t.message
    }

    private external fun compile(bundle: ByteArray, font: ByteArray, tex: ByteArray, cacheDir: String): ByteArray?
    private external fun lastError(): String?
}
