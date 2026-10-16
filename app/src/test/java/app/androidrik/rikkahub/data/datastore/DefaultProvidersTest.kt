package app.androidrik.rikkahub.data.datastore

import app.androidrik.ai.provider.ProviderSetting
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/credits", provider.balanceOption.apiPath)
        assertEquals("balance", provider.balanceOption.resultPath)
    }

    @Test
    fun `default new api provider should use token usage endpoint for balance`() {
        val newApiProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "New API" }

        assertEquals(1, newApiProviders.size)

        val provider = newApiProviders.single()
        assertTrue(provider.balanceOption.enabled)
        assertEquals("/api/usage/token/", provider.balanceOption.apiPath)
        assertEquals("(data.total_granted - data.total_used) / 500000", provider.balanceOption.resultPath)
        assertTrue(provider.balanceOption.sources.isEmpty())
    }

    @Test
    fun `default agent router provider should use token usage endpoint for balance`() {
        val agentRouterProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Agent Router" }

        assertEquals(1, agentRouterProviders.size)

        val provider = agentRouterProviders.single()
        assertEquals("https://agentrouter.org/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertFalse(provider.balanceOption.enabled)
        assertEquals("/api/usage/token/", provider.balanceOption.apiPath)
        assertEquals("(data.total_granted - data.total_used) / 500000", provider.balanceOption.resultPath)
    }

    /**
     * **出厂默认里不得再有上游作者自己的中转服务**（2026-10-16 移除）。
     *
     * 那个内置 `RikkaHub` 供应商指向 `https://api.rikka-ai.com/v1`，并自带一个 `auto` 免费模型
     * —— 对 fork 来说这是「把上游的收费/限流服务当成我们的出厂默认」。
     *
     * 这条守卫同时盯**两个方向**：
     * 1. 数据面：`DEFAULT_PROVIDERS` 里没有它；
     * 2. 源码面：全仓不得再出现那个域名（连注释里的死代码地址也一起扫，
     *    因为「留在包里但没人用」同样是把上游地址继续编进产物）。
     */
    @Test
    fun `no shipped provider points at the upstream relay`() {
        val upstreamHost = "rikka-ai.com"
        assertTrue(
            "出厂默认里不得再有 RikkaHub 供应商",
            DEFAULT_PROVIDERS.none { it.name.equals("RikkaHub", ignoreCase = true) },
        )
        val offenders = DEFAULT_PROVIDERS.filter { provider ->
            provider.toString().contains(upstreamHost)
        }
        assertTrue("出厂默认里不得再有指向 $upstreamHost 的供应商：$offenders", offenders.isEmpty())
        // `DEFAULT_AUTO_MODEL_ID` 现在**只是一个「未配置」哨兵**，不再由任何出厂供应商提供。
        // 若哪天有人又把它挂回某个供应商，那说明这个常量的语义被改回去了 —— 必须显式确认。
        assertTrue(
            "AUTO 哨兵不该再由任何出厂供应商提供（它现在表示「用户还没选模型」）",
            DEFAULT_PROVIDERS.none { provider ->
                provider.models.any { it.id == DEFAULT_AUTO_MODEL_ID }
            },
        )

        val roots = listOf(File("src/main"), File("app/src/main"))
        val root = roots.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val hits = root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { stripComments(it.readText(), isKotlin = it.extension == "kt").contains(upstreamHost) }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("源码里不得再出现上游中转域名：$hits", hits.isEmpty())
    }

    /**
     * 去掉注释再扫描。
     *
     * **为什么必须去注释**：这条守卫的靶子是「**会被编进产物**的上游地址」（代码与配置），
     * 而注释是文档。第一版没去注释，于是它把**我用来解释这次移除的 kdoc 自己**判成了违规
     * —— 那会逼着后来的人「别写清楚为什么删」，正好是反效果。
     * （Kotlin 注释不进 dex，XML 注释也不进资源表。）
     *
     * **第二版差点变成一个假守卫**（值得记着）：第一版用 `//[^\n]*` 一刀切，
     * 而**字符串里的 `https://` 也含 `//`** ⇒ `"https://api.<host>"` 会在 `//` 处被截断，
     * 于是这条守卫**恰好漏掉了它唯一要防的东西**（代码里的 URL）。
     * 所以行注释只在「该 `//` 之前双引号个数为偶数」时才切——即它确实在字符串之外。
     * XML 不走这条规则（属性里 `http://` 会被误切），只去 `<!-- -->`。
     */
    private fun stripComments(text: String, isKotlin: Boolean): String {
        if (!isKotlin) return text.replace(XML_COMMENT, "")
        return text.replace(BLOCK_COMMENT, "")
            .lines()
            .joinToString("\n") { line ->
                // 只枚举**真实存在**的 `//` 位置，并限定在行长内：用
                // `generateSequence(idx) { it + 1 }` 会在「没有一处满足条件」时一路加到 Int 溢出，
                // 然后拿 Int.MIN_VALUE 去 take() 而抛异常（踩过一次）。
                val cut = (0..line.length)
                    .filter { line.startsWith("//", it) }
                    .firstOrNull { at -> line.take(at).count { ch -> ch == '"' } % 2 == 0 }
                if (cut == null) line else line.take(cut)
            }
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val XML_COMMENT = Regex("<!--[\\s\\S]*?-->")
    }
}
