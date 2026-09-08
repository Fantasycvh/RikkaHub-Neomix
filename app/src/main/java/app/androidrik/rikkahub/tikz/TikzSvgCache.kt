package app.androidrik.rikkahub.tikz

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * TikZ 渲染结果缓存（SVG 内容寻址）。
 *
 * native 编译 ~3-6s/图；同一段 TikZ 源码的产物确定（TeX 确定性输出，
 * 真机验证 SHA-256 与宿主逐字节一致），以 sha256(code) 为键落盘 SVG：
 * - 聊天历史回滚重渲染：秒开（读文件 vs 重编译）
 * - darkMode 切换：同一 SVG（native 产物为黑笔画矢量，暗色由渲染层衬白底
 *   处理，无需双版本）
 * - 导出（HTML/图片）直接复用缓存文件
 *
 * 缓存目录 tikz_cache 无上限增长防护：单图 SVG 通常 1-10KB，
 * 千图级 ~10MB，POC 阶段不做 LRU；后续可挂到设置的存储清理。
 */
object TikzSvgCache {
    private const val TAG = "TikzSvgCache"
    private const val DIR = "tikz_cache"

    // 渲染链路版本号：引擎（dvisvgm→mupdf）、字体资产（DroidSansFallback→Noto Sans SC）、
    // wrapDocument（pgfplots 按需加载/every node font/selectfont 包装 zhfont）演进后，
    // 旧键必须失效。递增此常量即整体重置缓存。
    private const val CACHE_VERSION = "v4"
    private const val VERSION_MARKER = "tikz_cache_" + CACHE_VERSION

    /**
     * 渲染链路版本变化时自动清空整个缓存目录（不依赖手动清理）：
     * 目录内写版本标记文件 [VERSION_MARKER]，每次访问对比；不一致则整目录删除重建。
     * 这样 CACHE_VERSION 递增 = 旧 SVG 文件 + 旧键全部一次性清除，杜绝"旧渲染残留
     * 占位导致直接加载旧结果"（实测：5b063217 改了渲染输出但漏了递增版本 → 旧 v3
     * 键命中坏 SVG）。已存在的旧目录（无标记或不匹配）也一并清空。
     */
    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        val marker = File(d, VERSION_MARKER)
        if (marker.isFile) return d
        // 版本不一致或首次创建：整目录清空（旧文件不再残留占位）
        runCatching {
            if (d.exists()) {
                d.listFiles()?.forEach { it.deleteRecursively() }
            } else {
                d.mkdirs()
            }
            marker.writeText("1")
        }
        return d
    }

    private fun key(code: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest("$CACHE_VERSION:".toByteArray(Charsets.UTF_8) + code.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(context: Context, code: String): File =
        File(dir(context), "${key(code)}.svg")

    /** 读缓存；未命中返回 null */
    fun get(context: Context, code: String): String? = runCatching {
        val f = fileFor(context, code)
        if (f.isFile) f.readText() else null
    }.getOrNull()

    /** 写缓存（native 渲染成功后调用）；失败静默（缓存只影响性能） */
    fun put(context: Context, code: String, svg: String) {
        runCatching {
            dir(context).mkdirs()
            val tmp = File(dir(context), "${key(code)}.tmp")
            tmp.writeText(svg)
            // 原子替换：避免并发渲染写到一半被读
            if (!tmp.renameTo(fileFor(context, code))) {
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "cache put failed: ${it.message}") }
    }

    /** 缓存文件路径（导出复用；不存在返回 null） */
    fun file(context: Context, code: String): File? =
        fileFor(context, code).takeIf { it.isFile }
}