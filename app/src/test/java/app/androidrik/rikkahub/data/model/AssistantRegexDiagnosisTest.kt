package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 正则规则的编辑期体检（`diagnoseRegexRule`）。
 *
 * 每条断言对应一个**真实会发生的**配置错误，而不是为了凑覆盖率：
 *
 * - 编译不过/没勾作用域 ⇒ `replaceRegexes` 静默跳过，用户以为生效了；
 * - 替换结果还能被同一条正则命中 ⇒ 非视觉规则在工具循环里**每步再应用一次**
 *   （`GenerationHandler` 每步结束都把 `messages` 换成已替换版本），并随消息落库；
 * - 只读显示（`visualOnly = true`）的同款规则**不报**：它每次渲染都从落库原文重算，不会累积。
 */
class AssistantRegexDiagnosisTest {

    private fun rule(
        find: String,
        replace: String = "",
        scopes: Set<AssistantAffectScope> = setOf(AssistantAffectScope.ASSISTANT),
        visualOnly: Boolean = false,
    ) = AssistantRegex(
        id = Uuid.random(),
        findRegex = find,
        replaceString = replace,
        affectingScope = scopes,
        visualOnly = visualOnly,
    )

    @Test
    fun `a blank rule is not diagnosed`() {
        assertTrue(diagnoseRegexRule(rule(find = "")).isEmpty())
        assertTrue(diagnoseRegexRule(AssistantRegex(id = Uuid.random())).isEmpty())
    }

    @Test
    fun `a pattern that does not compile is reported`() {
        assertEquals(
            setOf(RegexDiagnosisIssue.INVALID_PATTERN),
            diagnoseRegexRule(rule(find = "([unclosed", replace = "x")),
        )
    }

    @Test
    fun `a rule without any scope never applies`() {
        val issues = diagnoseRegexRule(rule(find = "\\bfoo\\b", replace = "bar", scopes = emptySet()))

        assertTrue(RegexDiagnosisIssue.NO_SCOPE in issues)
        // 作用域为空时根本无法「累积」：不报第二条，避免一次弹三条让用户无从下手
        assertTrue(RegexDiagnosisIssue.AMPLIFIES_OUTPUT !in issues)
    }

    @Test
    fun `a replacement that matches its own pattern is flagged`() {
        // roadmap 里记的样本：零宽前瞻 + 前缀，每应用一次就多一个【AI】
        val issues = diagnoseRegexRule(rule(find = "^(?=\\S)", replace = "【AI】"))
        assertTrue(issues.toString(), RegexDiagnosisIssue.AMPLIFIES_OUTPUT in issues)

        // 最朴素的一种：把 a 换成 aa
        assertTrue(diagnoseRegexRule(rule(find = "a", replace = "aa")).contains(RegexDiagnosisIssue.AMPLIFIES_OUTPUT))
    }

    @Test
    fun `an idempotent rule is not flagged`() {
        // 邮箱打码：替换结果 [EMAIL] 里没有邮箱，再应用一次也不会变
        assertTrue(
            "替换结果不再命中 ⇒ 幂等",
            diagnoseRegexRule(rule(find = "\\b\\w+@\\w+\\.\\w+\\b", replace = "[EMAIL]")).isEmpty(),
        )
        // 零宽匹配 + 空替换：真正幂等（这里不靠「能否命中」判，而是真的再算一遍）
        assertTrue(diagnoseRegexRule(rule(find = "^", replace = "")).isEmpty())
        // 换成自己：命中但结果不变
        assertTrue(diagnoseRegexRule(rule(find = "x", replace = "x")).isEmpty())
    }

    @Test
    fun `visual only rules never accumulate so they are not flagged`() {
        val issues = diagnoseRegexRule(rule(find = "^(?=\\S)", replace = "【AI】", visualOnly = true))

        assertTrue("仅显示的规则每次从原文重算，不会累积：$issues", issues.isEmpty())
    }

    @Test
    fun `user scoped non visual rules are applied once so they are not flagged`() {
        // USER 侧只在 preprocessUserInputParts 里应用一次，文本落库后不再重算
        val issues = diagnoseRegexRule(
            rule(find = "^(?=\\S)", replace = "【AI】", scopes = setOf(AssistantAffectScope.USER)),
        )

        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun `a replacement referencing an unknown group does not crash the diagnosis`() {
        // 运行时会抛（replaceRegexes 吞掉并返回原文），体检不该因此崩掉。
        // 用 `\d` 让样例串真能命中，异常路径才走得到。
        val issues = diagnoseRegexRule(rule(find = "\\d", replace = "\$1"))
        assertEquals(emptySet<RegexDiagnosisIssue>(), issues)
    }
}
