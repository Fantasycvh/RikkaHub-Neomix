package app.androidrik.rikkahub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台任务时长的展示格式（生产函数 [formatTaskDuration]）。
 *
 * 界面上的「8m55s 后自动终止」直接来自它，所以边界要钉死：**向上取整**（剩余 0.4s 不能显示 0s，
 * 否则用户会以为任务已经停了）、负数按 0 处理、小时/分钟/秒三档切换。
 */
class WorkspaceTaskDurationTest {

    @Test
    fun `seconds only`() {
        assertEquals("12s", formatTaskDuration(12_000))
        assertEquals("0s", formatTaskDuration(0))
    }

    @Test
    fun `rounds up so a running task never shows zero`() {
        // 还剩 400ms：显示 1s 而不是 0s
        assertEquals("1s", formatTaskDuration(400))
        assertEquals("1m00s", formatTaskDuration(59_500))
    }

    @Test
    fun `minutes and hours`() {
        assertEquals("5m30s", formatTaskDuration(330_000))
        assertEquals("59m59s", formatTaskDuration(3_599_000))
        assertEquals("1h00m", formatTaskDuration(3_600_000))
        assertEquals("2h05m", formatTaskDuration(7_500_000))
    }

    @Test
    fun `negative input is clamped`() {
        assertEquals("0s", formatTaskDuration(-5_000))
    }
}
