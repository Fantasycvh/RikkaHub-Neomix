package app.androidrik.rikkahub.service

import app.androidrik.ai.ui.UIMessageAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskSkillResolveTest {
    private val bodies = mapOf(
        "daily-digest" to "Summarize updates with top 3 risks.",
        "review" to "Verify every number and file path.",
        "compress" to "Output a compact 5-bullet briefing.",
    )

    @Test
    fun `resolves single and stacked leading skills and strips prefix before buildTaskPrompt`() {
        val resolved = resolveScheduledTaskPrompt(
            rawPrompt = "/daily-digest /review 检查今日构建产物",
            skillExists = bodies::containsKey,
            readSkillBody = bodies::get,
        )

        assertEquals("检查今日构建产物", resolved.effectivePrompt)
        assertEquals(
            listOf(
                UIMessageAnnotation.InvokedSkill("daily-digest", bodies.getValue("daily-digest")),
                UIMessageAnnotation.InvokedSkill("review", bodies.getValue("review")),
            ),
            resolved.invokedSkills,
        )

        val built = buildTaskPrompt(
            prompt = resolved.effectivePrompt,
            withGoal = true,
            goal = "保障主分支每日构建绿色",
            memoryAvailable = true,
        )
        assertTrue(built.startsWith("## 长期目标\n保障主分支每日构建绿色"))
        assertTrue(built.endsWith("检查今日构建产物"))
    }

    @Test
    fun `keeps raw skill tag as effective prompt when body text is empty`() {
        val resolved = resolveScheduledTaskPrompt(
            rawPrompt = "/daily-digest",
            skillExists = bodies::containsKey,
            readSkillBody = bodies::get,
        )

        assertEquals("/daily-digest", resolved.effectivePrompt)
        assertEquals(1, resolved.invokedSkills.size)
        assertEquals("daily-digest", resolved.invokedSkills.single().name)
    }

    @Test
    fun `supports colliding builtin command name as skill in scheduled prompts`() {
        val resolved = resolveScheduledTaskPrompt(
            rawPrompt = "/compress 汇总今日 PR",
            skillExists = bodies::containsKey,
            readSkillBody = bodies::get,
        )

        assertEquals("汇总今日 PR", resolved.effectivePrompt)
        assertEquals(listOf("compress"), resolved.invokedSkills.map { it.name })
    }

    @Test
    fun `falls back to original prompt for ordinary slash paths or unreadable skills`() {
        val pathPrompt = resolveScheduledTaskPrompt(
            rawPrompt = "/var/log/syslog 检查异常",
            skillExists = bodies::containsKey,
            readSkillBody = bodies::get,
        )
        assertEquals("/var/log/syslog 检查异常", pathPrompt.effectivePrompt)
        assertTrue(pathPrompt.invokedSkills.isEmpty())

        val unreadable = resolveScheduledTaskPrompt(
            rawPrompt = "/daily-digest 汇总",
            skillExists = bodies::containsKey,
            readSkillBody = { null },
        )
        assertEquals("/daily-digest 汇总", unreadable.effectivePrompt)
        assertTrue(unreadable.invokedSkills.isEmpty())
    }
}
