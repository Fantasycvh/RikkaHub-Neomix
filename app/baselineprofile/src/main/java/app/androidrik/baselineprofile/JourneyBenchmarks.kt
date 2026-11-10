package app.androidrik.baselineprofile

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.androidrik.baselineprofile.fixtures.FixtureSpec
import app.androidrik.baselineprofile.fixtures.FixtureVerifier
import app.androidrik.baselineprofile.journeys.openChat
import app.androidrik.baselineprofile.journeys.scrollMessages
import app.androidrik.baselineprofile.journeys.sendMessage
import app.androidrik.baselineprofile.journeys.startToChat
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 通用 journey 基准（对应 Miffan `7442ea738` 系列的「fixture + 旅程」部分，本仓自建）：
 *
 * 1. [coldStartToChat]：冷启动到聊天页（启动指标 + 帧）；
 * 2. [longConversationScroll]：打开长对话并上下滚动（帧）；
 * 3. [sendAndMeasure]：输入并发送一条消息（帧，**不含模型回复流**）。
 *
 * 与 [StartupBenchmarks] 的分工：那边只测启动、不要求任何数据；这边测「有数据 + 有交互」的帧时间，
 * 所以 `@BeforeClass` 会先校验 fixture（见 [FixtureVerifier]），数据不满足会直接失败。
 *
 * 运行：`app/baselineprofile/perf/run_bench.sh --serial <serial>`，参数见脚本与模块 README.md。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class JourneyBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val runner = BenchmarkRunner(rule)

    /** 冷启动 → 聊天页可见。指标：TTID（TTFD 需要 App 调 reportFullyDrawn，本仓未接）+ 帧。 */
    @Test
    fun coldStartToChat() = runner.startup {
        openChat()
    }

    /**
     * 打开长对话并滚动。App 冷启动会恢复上次打开的对话（`RouteActivity` 的 lastConversationId），
     * 所以 fixture 对话就是「打开的长对话」；setup 只负责把 App 带到前台，测量从滚动开始。
     */
    @Test
    fun longConversationScroll() = runner.frames(setup = { startToChat() }) {
        scrollMessages()
    }

    /**
     * 输入 + 发送：测输入框状态更新、发送按钮、用户气泡上屏的帧时间。
     * 不等待模型回复（本管线不接假 provider），因此不覆盖流式渲染路径。
     */
    @Test
    fun sendAndMeasure() = runner.frames(setup = { startToChat() }) {
        sendMessage(FixtureSpec.sendText(99))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun prepareFixture() = FixtureVerifier.prepare()
    }
}
