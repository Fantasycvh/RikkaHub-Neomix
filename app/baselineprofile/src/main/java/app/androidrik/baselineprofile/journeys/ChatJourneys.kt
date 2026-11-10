package app.androidrik.baselineprofile.journeys

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import app.androidrik.baselineprofile.PerfConfig
import app.androidrik.baselineprofile.fixtures.FixtureSpec

/**
 * 可复用的 journey 片段。只依赖 App 通过 `testTag` 暴露的 resource-id
 * （`RouteActivity` 已开启 `testTagsAsResourceId`），不依赖文案，因此换语言也能跑。
 *
 * 现有 tag：`chat_input`、`chat_send_button`、`chat_row:<key>`（见 app 模块 ChatInput.kt / ChatList.kt）。
 * 会话列表/抽屉没有 tag，所以本管线不跨对话导航：**依赖 App 冷启动恢复 lastConversationId**
 * （`RouteActivity` 行为），打开的就是 fixture 所在的对话。
 */

/** 启动并进入聊天页：`startActivityAndWait()` + 等输入框（放进 measureBlock，计测量）。 */
internal fun MacrobenchmarkScope.openChat() {
    startActivityAndWait()
    check(device.wait(Until.hasObject(By.res("chat_input")), 20_000)) {
        "聊天页没有在 20s 内出现（chat_input）：App 卡启动页、或首次配置未完成？"
    }
}

/** setup 阶段用：回桌面再进入聊天页（setupBlock 不计入测量）。 */
internal fun MacrobenchmarkScope.startToChat() {
    pressHome()
    openChat()
}

/**
 * 在已打开的对话里上下甩动消息列表。
 *
 * 每轮都重新查找列表节点（Compose 的 LazyColumn 节点会被重建），并吞掉节点失效异常，
 * 避免列表在测量中途被回收导致整条基准失败——滚动 journey 关心的是帧，不是单次操作成功。
 */
internal fun MacrobenchmarkScope.scrollMessages(rounds: Int = PerfConfig.scrollRounds()) {
    repeat(rounds) {
        val list = findScrollable() ?: error("找不到消息列表（scrollable 节点），无法测滚动")
        runCatching { list.setGestureMargin(device.displayWidth / 5) }
        // 先往上翻（看更早的消息），再往下翻（回到底部）：两个方向都要覆盖，贴底逻辑也会被走到。
        repeat(2) { direction ->
            val target = findScrollable()
            if (target != null) {
                runCatching {
                    if (direction == 0) target.fling(Direction.UP) else target.fling(Direction.DOWN)
                }
            }
        }
        device.waitForIdle()
    }
}

/**
 * 输入一条文本并点发送，等到这条用户气泡出现。
 *
 * 只测「输入法 → 输入框状态 → 发送按钮 → 气泡上屏」这一段；**不等模型回复**：本仓没有接假 provider，
 * 流式渲染的帧时间需要后续加 `fake_llm`（参考 Miffan `scripts/perf/fake_llm.py`）再测。
 */
internal fun MacrobenchmarkScope.sendMessage(text: String = FixtureSpec.sendText(1)) {
    val input = device.wait(Until.findObject(By.res("chat_input")), 10_000)
        ?: error("找不到聊天输入框（chat_input）")
    input.click()
    input.text = text
    device.waitForIdle()
    val send = device.wait(Until.findObject(By.res("chat_send_button")), 5_000)
        ?: error("找不到发送按钮（chat_send_button）")
    check(send.isEnabled) {
        "发送按钮不可用：被测 App 没有可用模型；发送 journey 需要先配好任意一个模型（地址可以无效）。"
    }
    send.click()
    check(device.wait(Until.hasObject(By.textContains(FixtureSpec.SEND_MARKER)), 15_000)) {
        "发送后 15s 内没有等到用户气泡（textContains(${FixtureSpec.SEND_MARKER})）"
    }
    device.waitForIdle()
}

/** 找滚动容器：聊天消息列表是页面上唯一的 scrollable（抽屉关闭时）。 */
private fun MacrobenchmarkScope.findScrollable() =
    device.wait(Until.findObject(By.scrollable(true)), 5_000)
