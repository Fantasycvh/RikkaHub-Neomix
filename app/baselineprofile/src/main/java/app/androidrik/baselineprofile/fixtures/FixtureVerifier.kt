package app.androidrik.baselineprofile.fixtures

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.androidrik.baselineprofile.PerfConfig

/**
 * Fixture 前置：启动 App → 校验 fixture 是否就位（可选地用 UI 造数据）→ 彻底停掉 App。
 *
 * 本仓局限：`com.android.test` 模块既拿不到 App 的类，也没有权限直接改 App 数据库；能做的只有
 * 「通过 UI 写入 + 通过无障碍文本校验」。任何一步不满足都会 **直接失败并打印操作指引**——
 * 宁可让基准跑不起来，也不要在错误的数据集上产出看起来正常的指标。
 *
 * 调用点：基准类的 `@BeforeClass`（发生在 `measureRepeated` 之前，不计入测量）。
 */
internal object FixtureVerifier {
    private const val LAUNCH_TIMEOUT_MS = 30_000L
    private const val VERIFY_TIMEOUT_MS = 10_000L

    fun prepare() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        launch(device)
        waitForComposer(device)
        if (!isFixtureVisible(device) && PerfConfig.uiSeeding()) {
            seed(device)
        }
        if (!isFixtureVisible(device)) {
            error(missingFixtureHelp())
        }
        // 校验只读 UI，读完后强制停掉进程：测量从冷启动/Splash 开始，避免带着校验时的进程与缓存。
        device.executeShellCommand("am force-stop ${PerfConfig.targetPackage}")
    }

    /** 用 monkey 拉起桌面图标（不依赖主 Activity 类名）。 */
    fun launch(device: UiDevice) {
        device.executeShellCommand(
            "monkey -p ${PerfConfig.targetPackage} -c android.intent.category.LAUNCHER 1"
        )
    }

    fun waitForComposer(device: UiDevice) {
        check(device.wait(Until.hasObject(By.res("chat_input")), LAUNCH_TIMEOUT_MS)) {
            "启动后 30s 内没有出现聊天输入框（testTag=chat_input）。" +
                "确认被测 App 不是首次安装的引导状态，见 app/baselineprofile/README.md。"
        }
    }

    fun isFixtureVisible(device: UiDevice): Boolean =
        device.wait(Until.hasObject(By.textContains(FixtureSpec.MARKER)), VERIFY_TIMEOUT_MS)

    /**
     * 实验性造数据（`-e seed=ui` 才启用）：往输入框 setText 一条长 Markdown 并点发送，重复若干次。
     *
     * 限制（README 同样写明）：
     * - 不写 Room：只能走 UI，因此 App 必须处于可交互状态，且首次安装的引导必须已经完成；
     * - 需要先配好任意一个模型（地址可以是无效的）：离线时请求会快速失败，但用户消息会落库，
     *   fixture 的用途是「有长文本可滚动」，不依赖模型回复；
     * - 消息标题由 App 从正文自动生成，校验只认正文标记，不依赖标题。
     */
    private fun seed(device: UiDevice) {
        var sent = 0
        repeat(PerfConfig.seedMessages(FixtureSpec.DEFAULT_SEED_MESSAGES)) { index ->
            val input = device.wait(Until.findObject(By.res("chat_input")), 10_000)
            if (input == null) {
                // 输入框没出现（例如被键盘/弹窗挡住）就跳过这一轮，由末尾的 sent 计数统一报错。
                return@repeat
            }
            input.click()
            input.text = FixtureSpec.seedText(index)
            device.waitForIdle()
            val send = device.wait(Until.findObject(By.res("chat_send_button")), 5_000)
                ?: error("找不到发送按钮（testTag=chat_send_button），无法用 UI 造数据")
            check(send.isEnabled) {
                "发送按钮不可用：请先在被测 App 里配好任意一个模型（地址可以无效），" +
                    "或按 README 的「手工造数据」路径准备 fixture。"
            }
            send.click()
            sent++
            // 等输入框回到空闲（发送被接受）即可；不等待模型回复——本管线不接假 provider。
            device.wait(Until.hasObject(By.res("chat_input")), 5_000)
            device.waitForIdle()
        }
        check(sent > 0) { "UiAutomator 造数据一轮都没成功，见 README.md 的 Fixture 一节" }
    }

    private fun missingFixtureHelp(): String = buildString {
        append("没有找到 fixture 对话（正文里应含标记「").append(FixtureSpec.MARKER).append("」）。\n")
        append("三种就位方式（详见 app/baselineprofile/README.md）：\n")
        append("1) 推荐：手工在被测 App 里发 4 条左右长 Markdown 消息，每条正文以「")
        append(FixtureSpec.MARKER).append("」开头；\n")
        append("2) 实验性：加 -Pandroid.testInstrumentationRunnerArguments.seed=ui，让本模块用 UiAutomator 自己造；\n")
        append("3) 后续：像参考实现那样在 app 里加 perfFixture 专用广播接收器（需要改 app 代码，本任务未做）。")
    }
}
