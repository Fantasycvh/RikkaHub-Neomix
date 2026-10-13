package app.androidrik.rikkahub.ui.pages.setting.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「连接测试必须显式传 `reasoningLevel = AUTO`」的**接线守卫**（源码扫描，与
 * `MemoryConsolidationWiringTest` / `ThinkingDegenerationWiringTest` 同款做法）。
 *
 * ## 为什么需要这条守卫
 *
 * `TextGenerationParams.reasoningLevel` 的默认值是 **`OFF`**（`ai/.../Provider.kt`），
 * 而 `OFF` 会被 provider 翻译成「**显式关闭思考**」。对 GLM-5.3 这类**始终思考**的模型，
 * 服务端直接拒绝并返回 1210「该模型始终思考，不支持关闭思考」⇒ 界面上的「测试连接」
 * 报错，而用户的配置其实**完全正确**（上游 issue #2141）。
 *
 * 连接测试的语义是「用与聊天相同的方式发一次请求」，所以三个入口（非流式 / 流式 /
 * 工具调用）都必须显式取 `AUTO`——上游 `ebbcf6377` 修的正是这三处。
 *
 * ## 为什么是源码扫描而不是行为断言
 *
 * `ProviderConnectionTester` 是 `@Composable`，依赖 Koin / `ProviderManager` / 真实网络，
 * JVM 单测够不着；而这条改动的失效形态**没有任何编译或运行时报错**——少传一个参数
 * 只是悄悄退回默认 `OFF`，只有拿一个「始终思考」的模型去连才会暴露。
 * 与 fork 既有的「接线断了就静默失效」一族（见 `ThinkingDegenerationWiringTest` 开头）
 * 完全同型。
 *
 * 断言取**等量**而非「至少一个」：将来若再加第四个测试入口（例如流式 + 工具），
 * 点数与参数数会一起变，等量断言能逼着新入口也带上它。
 */
class ProviderConnectionTesterReasoningTest {

    private val relativePath =
        "app/androidrik/rikkahub/ui/pages/setting/components/ProviderConnectionTester.kt"

    private fun read(relative: String): String {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `every connection test sends reasoning at AUTO`() {
        val source = read(relativePath)

        val paramsCount = source.windowed("TextGenerationParams(".length)
            .count { it == "TextGenerationParams(" }
        val autoCount = source.windowed("reasoningLevel = ReasoningLevel.AUTO".length)
            .count { it == "reasoningLevel = ReasoningLevel.AUTO" }

        assertTrue(
            "连接测试的构造点应不止一处（非流式 / 流式 / 工具）；" +
                "若这里退化成 0，说明扫描路径或文件位置变了，本守卫已失效",
            paramsCount >= 3,
        )
        assertEquals(
            "每个 TextGenerationParams 都必须显式传 reasoningLevel = AUTO：" +
                "默认值是 OFF（= 显式关闭思考），对「始终思考」的模型会被服务端拒绝" +
                "（错误码 1210），用户会以为自己的配置有问题（上游 #2141）",
            paramsCount,
            autoCount,
        )
    }

    @Test
    fun `the guard still points at a file that has the three test entries`() {
        // 防「假绿」：文件被移走/改名后，上面的计数会双零而 assertEquals(0, 0) 照样通过。
        // 故再钉一次「这确实是连接测试那个文件、且三个入口都还在」。
        val source = read(relativePath)

        assertTrue("不是连接测试文件（找不到非流式用例）", source.contains("nonStreamingState = UiState.Loading"))
        assertTrue("流式用例不见了", source.contains("streamingState = UiState.Loading"))
        assertTrue("工具调用用例不见了", source.contains("toolsState = UiState.Loading"))
    }
}
