package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **前台服务退出必须带 `startId`**（源码守卫，2026-10-16 随上游 `72747fc9e` 的教训加）。
 *
 * ## 为什么
 *
 * 这两个服务都由 `startForegroundService()` 启动（`KeepAliveService.start()` / `RikkaHubApp.kt:318`），
 * 系统因此要求**每一次启动都必须履约一次 `startForeground()`**。裸 `stopSelf()` 会把
 * **更新的、还没履约的那次启动**一起停掉 ⇒ 系统抛
 * `ForegroundServiceDidNotStartInTimeException`，**进程崩溃**。
 * `stopSelf(startId)` 只在「自己就是最新一次启动」时才停，把机会让给排队的更新启动。
 *
 * 上游是在 `ChatGenerationForegroundService` 里发现并修掉这个竞态的（`72747fc9e`），
 * 而**本仓没有那个类** —— 对应物是 `KeepAliveService` 与 `WebServerService`，两处都有同款写法。
 * 这类缺陷**单测照不出来、源码扫描才拦得住**，所以在这里钉一条。
 */
class ForegroundServiceStopSelfTest {

    @Test
    fun `foreground services never call a bare stopSelf`() {
        val targets = listOf(
            "service/KeepAliveService.kt",
            "service/WebServerService.kt",
        )
        val root = listOf(File("src/main/java/app/androidrik/rikkahub"), File("app/src/main/java/app/androidrik/rikkahub"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")

        targets.forEach { relative ->
            val file = root.resolve(relative)
            assertTrue("源码不存在：${file.absolutePath}", file.isFile)
            val code = stripComments(file.readText())
            assertTrue(
                "$relative 里出现了裸 stopSelf()：前台服务退出必须用 stopSelf(startId)，" +
                    "否则会停掉尚未 startForeground 的新启动并崩溃",
                !code.contains("stopSelf()"),
            )
        }
    }

    @Test
    fun `the guard actually sees stopSelf when it is there`() {
        // 守卫自身的最小反证：剥注释不能把代码里的调用也一起吃掉
        val code = stripComments("foo()\n    stopSelf()\n// stopSelf() 只是注释\n")
        assertTrue("裸 stopSelf() 必须被看见", code.contains("stopSelf()"))
        assertTrue("但注释里的不该被看见", !stripComments("// stopSelf()\n").contains("stopSelf()"))
    }

    /** 去掉行注释与块注释（Kotlin）。字符串里的 `//` 用「前面引号个数为偶」判掉。 */
    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT, "")
            .lines()
            .joinToString("\n") { line ->
                val cut = (0..line.length)
                    .filter { line.startsWith("//", it) }
                    .firstOrNull { at -> line.take(at).count { ch -> ch == '"' } % 2 == 0 }
                if (cut == null) line else line.take(cut)
            }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
    }
}
