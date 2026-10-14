package app.androidrik.rikkahub.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息节点分页加载**必须先把 CancellationException 抛出去**（吸收上游 923ad3d91，Fixes #2151）。
 *
 * ## 失效形态
 *
 * CancellationException 是 IllegalStateException 的**子类**。loadMessageNodes 在
 * database.withTransaction + while (true) 里按页读节点；catch 顺序若是
 * SQLiteBlobTooBigException -> IllegalStateException（且没有 CancellationException 分支），
 * 那么协程被取消时**不会抛出**，而是落进 IllegalStateException 分支执行
 * offset += pageSize 后 continue ⇒ **在事务内无限循环**、事务永不释放、加载永不返回。
 *
 * ## 为什么用源码扫描
 *
 * 这是 **catch 顺序**的语法级约束：行为测试要构造「分页读取期间恰好取消」，需要真实 Room 事务
 * 与协程调度，而本模块没有 instrumented 环境。源码扫描能直接钉住顺序、失效时立刻变红。
 * 局限：它只证明**顺序**，不能证明运行时取消真的会传播（那需要真机）。
 */
class LoadMessageNodesCancellationTest {

    private fun repoSource(): String {
        val file = File("src/main/java/app/androidrik/rikkahub/data/repository/ConversationRepository.kt")
        assertTrue("源文件不存在：" + file.absolutePath, file.isFile)
        return file.readText()
    }

    /** 只取 loadMessageNodes 的函数体，避免文件里别处的 catch 顺序干扰判据。 */
    private fun loadMessageNodesBody(): String {
        val text = repoSource()
        val start = text.indexOf("private suspend fun loadMessageNodes(")
        assertTrue("未找到 loadMessageNodes", start >= 0)
        val end = text.indexOf("repairCorruptedCandidates", start)
        return text.substring(start, if (end > start) end else text.length)
    }

    /**
     * 剥掉行注释与块注释。
     *
     * ⚠️ **必须先剥再断言**：本仓反复踩过这个坑——说明性文字里的 continue / offset += pageSize
     * 会被当成代码判据（本函数自己的注释第一版正因此误红）。
     * 这里不用正则，逐字符扫描即可（两条注释语法、无嵌套块注释），避免转义层数过多出错。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    @Test
    fun cancellationIsRethrownBeforeIllegalStateCatch() {
        val body = stripComments(loadMessageNodesBody())

        val cancellation = body.indexOf("catch (e: CancellationException)")
        val illegalState = body.indexOf("catch (e: IllegalStateException)")
        val sqliteBlob = body.indexOf("catch (e: SQLiteBlobTooBigException)")

        assertTrue("必须有 CancellationException 分支，否则取消会被吞掉并无限循环", cancellation >= 0)
        assertTrue("必须有 IllegalStateException 分支（原有容错语义）", illegalState >= 0)
        assertTrue(
            "CancellationException 必须排在 IllegalStateException 之前（它是子类，顺序反了就被吞掉）",
            cancellation < illegalState,
        )
        assertTrue("SQLiteBlobTooBigException 容错分支应保留", sqliteBlob >= 0)
    }

    @Test
    fun cancellationBranchActuallyRethrows() {
        val body = stripComments(loadMessageNodesBody())
        val cancellation = body.indexOf("catch (e: CancellationException)")
        assertTrue("缺少 CancellationException 分支", cancellation >= 0)

        val snippet = body.substring(cancellation, minOf(cancellation + 400, body.length))
        val nextCatch = snippet.indexOf("catch (", 1)
        val branch = if (nextCatch > 0) snippet.substring(0, nextCatch) else snippet

        assertTrue(
            "CancellationException 分支必须 throw e（吞掉或继续循环都等于把取消变成死循环）",
            branch.contains("throw e"),
        )
        assertTrue(
            "该分支里不许再 continue（会把取消变成「跳过这一页接着读」）",
            !branch.contains("continue"),
        )
        assertTrue(
            "该分支里不许再 offset += pageSize",
            !branch.contains("offset += pageSize"),
        )
    }
}
