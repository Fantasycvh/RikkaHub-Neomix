package app.androidrik.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.androidrik.baselineprofile.journeys.openChat
import app.androidrik.baselineprofile.journeys.scrollMessages
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器：启动 → 聊天页 → 输入一段 Markdown 并发送 → 滚一下消息列表。
 *
 * 生成（任务名以 `./gradlew :app:baselineprofile:tasks --all` 为准，本仓由 app 的
 * `baselineProfile(project(":app:baselineprofile"))` 接线）：
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 * 生成后 `app/src/release/generated/baselineProfiles/baseline-prof.txt` 会被更新；
 * 生效程度用 [StartupBenchmarks]（none vs partial）对比。
 *
 * 覆盖范围只到「启动 + 一次 Markdown 发送 + 一次滚动」；带 fixture 的长对话 journey 由
 * [JourneyBenchmarks] 负责，不混进 profile 生成（生成过程对时序敏感，越短越稳）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = PerfConfig.targetPackage,
            // 启动 profile 会把生成的热路径放进 dex 布局优化（官方推荐保持 true）
            includeInStartupProfile = true,
        ) {
            pressHome()
            openChat()

            // 输入一条 Markdown 文本：覆盖 Markdown / 代码块 / 列表渲染路径
            val input = device.findObject(By.res(CHAT_INPUT))
            if (input != null) {
                input.click()
                input.text = SAMPLE_MARKDOWN
                device.waitForIdle()

                device.wait(Until.hasObject(By.res(CHAT_SEND_BUTTON)), 3_000)
                device.findObject(By.res(CHAT_SEND_BUTTON))?.click()
                device.waitForIdle()

                // 有回复就滚一下消息列表，把列表/滚动路径也写进 profile；没有回复也不影响生成。
                runCatching { scrollMessages(1) }
            }
        }
    }

    private companion object {
        const val CHAT_INPUT = "chat_input"
        const val CHAT_SEND_BUTTON = "chat_send_button"

        val SAMPLE_MARKDOWN = """
            # Hello RikkaHub

            这是一段 **Markdown** 文本，包含 *斜体*、`行内代码` 和列表：

            - 第一项
            - 第二项
            - [链接](https://github.com)

            > 引用块示例

            ```kotlin
            fun main() {
                println("Hello, world!")
            }
            ```
        """.trimIndent()
    }
}
