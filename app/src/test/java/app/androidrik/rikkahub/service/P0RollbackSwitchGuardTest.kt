package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 回滚开关的**接线守卫**（设计附录 C）。
 *
 * 这类开关的失效方式全是静默的：设置页里拨了、值也持久化了，但消费点没读它，
 * 用户以为回滚了、实际仍是新行为（或反过来）。四个开关跨设置页、Extensions 入口、
 * `SubagentRunner` 三个文件，故用源码钉住「每一端都真的读了同一个字段」。
 *
 * 行为层能证伪的是默认值与 DataStore 往返（`PreferencesStoreTest` + 真机
 * `P0RollbackSwitchInstrumentedTest`）；这里只钉消费点，避免 UI 重构时把判断删掉。
 */
class P0RollbackSwitchGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun source(relative: String): String = locate(relative, "app/$relative")
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `settings page writes all four rollback flags`() {
        val page = source("src/main/java/app/androidrik/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt")
        for (field in listOf(
            "taskCenterEnabled",
            "namespacedNotifications",
            "dynamicRunGate",
            "killShellTasksOnRunCancel",
        )) {
            assertTrue(
                "设置页缺少 $field 开关（附录 C 的回滚面不完整）",
                "settings.copy($field = it)" in page,
            )
        }
    }

    @Test
    fun `task center entry obeys taskCenterEnabled`() {
        val extensions = source("src/main/java/app/androidrik/rikkahub/ui/pages/extensions/ExtensionsPage.kt")
        assertTrue(
            "Extensions 的任务中心入口必须受 taskCenterEnabled 控制",
            "settings.taskCenterEnabled" in extensions,
        )
    }

    @Test
    fun `subagent runner obeys notification gate and shell kill switches`() {
        val runner = source("src/main/java/app/androidrik/rikkahub/service/SubagentRunner.kt")
        assertEquals(
            "动态闸回滚必须读 dynamicRunGate（否则改设置永远立即生效，回滚是假的）",
            1,
            runner.countOccurrences("settings.dynamicRunGate"),
        )
        // 2026-11-07 拆分后审批通知块在 SubagentApprovalNotifications.kt，回滚分支随迁
        val approvalNotifications = source("src/main/java/app/androidrik/rikkahub/service/SubagentApprovalNotifications.kt")
        assertTrue(
            "审批通知回滚必须读 namespacedNotifications",
            "settingsStore.settingsFlow.value.namespacedNotifications" in runner + approvalNotifications,
        )
        assertTrue(
            "取消级联回滚必须读 killShellTasksOnRunCancel",
            "settingsStore.settingsFlow.value.killShellTasksOnRunCancel" in runner,
        )
        assertEquals(
            "审批通知 id 必须统一走 approvalNotificationId 回滚分支",
            0,
            runner.countOccurrences("notificationId = NotificationIds.subagentApproval(runId),"),
        )
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
