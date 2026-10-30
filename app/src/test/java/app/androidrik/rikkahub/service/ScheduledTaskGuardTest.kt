package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务执行层的**接线守卫**（docs/design/design-scheduled-ai-tasks.md §六/§九）。
 *
 * 几条不变量在源码层钉死，因为单独看每条都「编译通过、行为看着也对」，破坏后是静默的：
 *
 * 1. 执行只许走 `ChatService.sendMessage`（FIFO 排队）——一旦有人「顺手」改成
 *    `sendInterrupting`，定时任务就获得了打断用户当前轮次的资格（设计明令禁止）。
 * 2. 执行器必须在认领**之前**做 idle 门控预检——放在认领之后会让「让路」变成
 *    「已推进却没执行」，任务被白吃一轮。
 * 3. 执行器是三个触发入口（闹钟 / 一次性唤醒作业 / 周期 sweep）的**唯一**执行实现，
 *    worker 与服务都只允许委托给它；闹钟主路径必须存在（否则回到 Doze 下到点不跑）。
 *
 * 另外两条是 2026-10-30 真机事故（「后台触发了但没有回复」）的回归守卫：
 * 4. 注入前必须 **等真实设置**（`awaitLoaded`）并 **载入 DB 会话**（`ensureConversationLoaded`）；
 * 5. 记 OK 必须同时看到**新的 assistant 消息**，不能让静默收场冒充成功。
 */
class ScheduledTaskGuardTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun executor() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskExecutor.kt")
    private fun sweeper() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskSweeper.kt")
    private fun alarms() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskAlarms.kt")
    private fun runService() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunService.kt")
    private fun manifest() = source("src/main/AndroidManifest.xml")

    @Test
    fun `the runner injects via sendMessage only`() {
        val runner = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunner.kt")
        assertTrue(
            "执行层必须通过 ChatService.sendMessage 注入（FIFO 排队，不插队）",
            "chatService.sendMessage(" in runner,
        )
        assertTrue(
            "定时任务不允许打断用户当前轮次（sendInterrupting 是明令禁止路径）",
            "sendInterrupting" !in runner,
        )
    }

    @Test
    fun `idle gate is checked before claiming`() {
        val executor = executor()
        val gateIndex = executor.indexOf("if (task.idleOnly && isConversationBusy")
        val claimIndex = executor.indexOf("taskRepository.claim(task)")
        assertTrue("执行器里必须存在 idle 门控预检", gateIndex >= 0)
        assertTrue("执行器里必须存在认领调用", claimIndex >= 0)
        assertTrue(
            "idle 门控必须在认领之前（否则让路变成白吃一轮）",
            gateIndex < claimIndex,
        )
    }

    @Test
    fun `single trigger execution core is shared by worker and run service`() {
        assertTrue(
            "worker 必须委托 ScheduledTaskExecutor（触发入口不许各写一份执行逻辑）",
            "executor.executeDueTasks(" in sweeper(),
        )
        assertTrue(
            "闹钟执行服务必须委托 ScheduledTaskExecutor",
            "executor.executeDueTasks(" in runService(),
        )
        assertTrue(
            "单任务失败不喂给 WorkManager 重试：状态已记账，下个周期自然重查",
            "Result.success()" in sweeper(),
        )
        assertTrue(
            "CancellationException 必须重新抛出（吞掉会让协程取消失效）",
            "catch (e: CancellationException)" in executor(),
        )
    }

    @Test
    fun `alarm is the primary wake path and falls back to inexact`() {
        val alarms = alarms()
        assertTrue(
            "主唤醒必须是 setAlarmClock（闹钟语义：Doze/应用待机都不压制，且无需特殊权限）",
            "setAlarmClock" in alarms,
        )
        assertTrue(
            "setAlarmClock 失败时必须退化为 setExactAndAllowWhileIdle",
            "setExactAndAllowWhileIdle" in alarms,
        )
        assertTrue(
            "未授予「闹钟和提醒」时必须继续退化为 setAndAllowWhileIdle，而不是直接不排",
            "setAndAllowWhileIdle" in alarms,
        )
        assertTrue(
            "闹钟到点必须拉起执行服务（接收器里不做执行）",
            "ScheduledTaskRunService.start(" in alarms,
        )
        assertTrue(
            "服务启动被 ROM 拒绝时必须有兜底作业",
            "OneTimeWorkRequestBuilder<ScheduledTaskSweeperWorker>()" in alarms,
        )
    }

    @Test
    fun `run service protects generation with foreground service and wakelock`() {
        val service = runService()
        assertTrue(
            "执行服务必须是前台服务（否则后台生成会被 cached-app freezer 冻住）",
            "startForeground(" in service,
        )
        assertTrue(
            "执行期间必须持 partial wake lock（Doze 熄屏下 CPU 不能睡）",
            "PARTIAL_WAKE_LOCK" in service,
        )
        assertTrue(
            "前台类型必须是 dataSync（与 manifest 声明一致，Android 14+ 必须显式给出）",
            "FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service,
        )
    }

    @Test
    fun `manifest declares alarm receiver and dataSync run service`() {
        val manifest = manifest()
        assertTrue(
            "必须声明 SCHEDULE_EXACT_ALARM（否则 setExactAndAllowWhileIdle 抛 SecurityException）",
            "android.permission.SCHEDULE_EXACT_ALARM" in manifest,
        )
        assertTrue(
            "必须声明闹钟接收器（exported=false）",
            ".service.ScheduledTaskAlarmReceiver" in manifest,
        )
        assertTrue(
            "必须声明执行服务（exported=false）",
            ".service.ScheduledTaskRunService" in manifest,
        )
        assertTrue(
            "执行服务的 foregroundServiceType 必须是 dataSync",
            "foregroundServiceType=\"dataSync\"" in manifest,
        )
    }

    @Test
    fun `runner readies settings and conversation before injecting`() {
        val runner = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunner.kt")
        val sendIndex = runner.indexOf("chatService.sendMessage(")
        val awaitSettingsIndex = runner.indexOf("settingsStore.awaitLoaded()")
        val loadConversationIndex = runner.indexOf("chatService.ensureConversationLoaded(")
        assertTrue("执行层里必须存在 sendMessage 注入", sendIndex >= 0)
        assertTrue(
            "冷启动必须先等真实设置就位（Settings.dummy() 会让 findModelById 恒 null）",
            awaitSettingsIndex >= 0,
        )
        assertTrue(
            "后台入口必须先按 DB 载入会话（否则 saveConversation 会整行覆盖历史）",
            loadConversationIndex >= 0,
        )
        assertTrue("等待设置必须在注入之前", awaitSettingsIndex < sendIndex)
        assertTrue("载入会话必须在注入之前", loadConversationIndex < sendIndex)
    }

    @Test
    fun `silent no-op is never recorded as OK`() {
        val runner = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunner.kt")
        assertTrue(
            "必须核对本轮是否真的产出了 assistant 消息（静默 no-op 不能记 OK）",
            "producedReply" in runner,
        )
        assertTrue(
            "结果判定必须走 evaluateRunStatus 纯函数（便于单测钉住状态语义）",
            "evaluateRunStatus(" in runner,
        )
    }
}
