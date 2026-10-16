package app.androidrik.rikkahub.data.ai.tools

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按需检索（`memory_tool action="search"`）的**接线守卫**。
 *
 * 起因：检索的价值全在链路上，而链断了是**静默失效**——动作声明了但装配没接 `onSearch`，
 * 模型会收到「查不到任何记录」的空结果，看起来像「记忆库里真的没有」，而不是像坏了。
 * 判据本身有单测（[MemoryRepositoryTest]），这里只钉「有没有接上」。
 *
 * 背景（真机度量，`docs/peer-apps-benchmark.md` §11.3 E）：注入是预算截断的，50 条库 /
 * 8000 token 下 **23 条完全不出现**；换清单层判据不改覆盖度 ⇒ 检索是唯一的取回通道，
 * 所以它断掉的代价比一般工具高。
 */
class MemorySearchWiringTest {

    @Test
    fun `search action is declared in the tool schema`() {
        val tools = read("app/androidrik/rikkahub/data/ai/tools/MemoryTools.kt")
        assertTrue("action 枚举必须有 search", tools.contains("add(\"search\")"))
        assertTrue("未知动作的报错要列出 search（否则模型不知道有这个动作）", tools.contains("read, search]"))
        assertTrue("必须有 query 参数", tools.contains("put(\"query\""))
        assertTrue("必须有 limit 参数", tools.contains("put(\"limit\""))
        assertTrue("说明书要点名「清单不列全」这条存在理由", tools.contains("does not list"))
    }

    @Test
    fun `search reaches the repository from both assembly sites`() {
        val factory = read("app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt")
        assertTrue(
            "聊天路径必须把 search 接到仓库（不接=永远返回空结果）",
            factory.contains("memoryRepository.searchMemories("),
        )

        val consolidator = read("app/androidrik/rikkahub/data/ai/MemoryConsolidationGenerationAgent.kt")
        assertTrue(
            "整理 agent 同样需要检索：它的记忆库快照也是预算截断的（4000 token），" +
                "看不到快照之外的条目就会重复 create / 漏 retire",
            consolidator.contains("memoryRepository.searchMemories("),
        )
    }

    @Test
    fun `search never requires approval`() {
        val factory = read("app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt")
        assertTrue(
            "search 与 read 同类（只读），必须显式放行——落进 else 兜底会变成审批轰炸",
            factory.contains("\"read\", \"search\" -> false"),
        )
    }

    @Test
    fun `search does not write read telemetry`() {
        // 口径钉死：检索返回 N 条不代表 N 条都被用了（见 §11.3 E5——正是靠 read_count 只由
        // read 写入，才能回答「清单层到底有没有被用」）。仓库里只有 readMemory 能调 recordRead。
        val repository = read("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        val searchBody = repository.substringAfter("suspend fun searchMemories(")
            .substringBefore("suspend fun ")
        assertTrue("searchMemories 不得调 recordRead", !searchBody.contains("recordRead"))
        assertTrue("searchMemories 不得调 readMemory", !searchBody.contains("readMemory("))
    }

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative.removePrefix("app/src/main/java/"))
        assertTrue("源码不存在：${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
