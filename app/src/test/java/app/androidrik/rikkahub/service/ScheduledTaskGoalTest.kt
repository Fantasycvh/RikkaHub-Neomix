package app.androidrik.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 注入 prompt 组装（withGoal 目标语义，purrcat A4.2）的纯函数测试
 * （docs/design/design-scheduled-ai-tasks.md §四/§六.2）。
 *
 * 核心回归：开关没开 / 会话没写目标 / 目标是空白 → 一律原样注入，绝不能把
 * 「## 长期目标\nnull」这类垃圾拼进 prompt；开了开关且有目标 → 目标在头部、
 * prompt 在体，中间空行分隔。
 */
class ScheduledTaskGoalTest {

    @Test
    fun `goal prepended when withGoal and goal present`() {
        assertEquals(
            "## 长期目标\n每天整理一条记忆\n\n总结今天的进展",
            buildTaskPrompt(prompt = "总结今天的进展", withGoal = true, goal = "每天整理一条记忆"),
        )
    }

    @Test
    fun `prompt unchanged when switch off`() {
        assertEquals(
            "总结今天的进展",
            buildTaskPrompt(prompt = "总结今天的进展", withGoal = false, goal = "有目标也不拼"),
        )
    }

    @Test
    fun `prompt unchanged when goal is null or blank`() {
        // 每轮新建的会话没有 goal（null）；用户清空目标后是空白串——都不报错、原样注入
        assertEquals("p", buildTaskPrompt(prompt = "p", withGoal = true, goal = null))
        assertEquals("p", buildTaskPrompt(prompt = "p", withGoal = true, goal = "  \n "))
    }
}
