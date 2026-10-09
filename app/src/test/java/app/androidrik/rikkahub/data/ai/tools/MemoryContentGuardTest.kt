package app.androidrik.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MemoryContentGuard` 的判据测试。
 *
 * 两组同等重要：
 * - **该拦的要拦**（且必须过校验位/Luhn，而不是「像数字就拦」）；
 * - **不该拦的不能拦**（订单号、16 位随机数、没有校验位的编号）——误拦会让用户的正当
 *   记录静默丢失，比漏拦更糟（提示词条款是主约束，这里只是兜底）。
 */
class MemoryContentGuardTest {

    // ---- 中国居民身份证 ----

    @Test
    fun `valid chinese id is refused`() {
        assertEquals(
            MemoryContentViolation.ID_NUMBER,
            findForbiddenMemoryContent("用户的身份证号是 11010519491231002X，请记住"),
        )
    }

    @Test
    fun `chinese id with lowercase check char is refused`() {
        assertEquals(
            MemoryContentViolation.ID_NUMBER,
            findForbiddenMemoryContent("id: 11010519491231002x"),
        )
    }

    @Test
    fun `chinese id with wrong check digit is not refused`() {
        // 校验位不符 ⇒ 多半是订单号/流水号这类数字串，不能拦
        assertNull(findForbiddenMemoryContent("流水号 110105194912310021 已提交"))
    }

    @Test
    fun `legacy 15 digit chinese id is out of scope`() {
        // 已知覆盖缺口（与上游同款）：15 位旧号无校验位可判，硬拦会误伤大量 15 位数字
        assertNull(findForbiddenMemoryContent("老证件 110105491231002"))
    }

    // ---- SSN ----

    @Test
    fun `plausible ssn is refused`() {
        assertEquals(
            MemoryContentViolation.SOCIAL_SECURITY_NUMBER,
            findForbiddenMemoryContent("his SSN is 078-05-1120"),
        )
    }

    @Test
    fun `reserved ssn ranges are not refused`() {
        assertNull(findForbiddenMemoryContent("编号 000-12-1234"))
        assertNull(findForbiddenMemoryContent("编号 666-12-1234"))
        assertNull(findForbiddenMemoryContent("编号 912-12-1234"))
        assertNull(findForbiddenMemoryContent("版本 123-00-1234"))
        assertNull(findForbiddenMemoryContent("序列 123-45-0000"))
    }

    @Test
    fun `nine plain digits are not an ssn`() {
        assertNull(findForbiddenMemoryContent("编号 123456789 已完成"))
    }

    // ---- 银行卡 ----

    @Test
    fun `known bin cards passing luhn are refused`() {
        val cards = listOf(
            "4111111111111111" to "Visa",
            "5555555555554444" to "Mastercard",
            "2223000048400011" to "Mastercard 2 系",
            "378282246310005" to "Amex",
            "6212345678901232" to "UnionPay",
            "3530111333300000" to "JCB",
            "6011111111111117" to "Discover",
        )
        cards.forEach { (card, label) ->
            assertEquals(
                "$label 卡号应被拦下",
                MemoryContentViolation.BANK_CARD_NUMBER,
                findForbiddenMemoryContent("卡号 $card"),
            )
        }
    }

    @Test
    fun `card with spaces or dashes is still refused`() {
        assertEquals(
            MemoryContentViolation.BANK_CARD_NUMBER,
            findForbiddenMemoryContent("card 4111 1111 1111 1111"),
        )
        assertEquals(
            MemoryContentViolation.BANK_CARD_NUMBER,
            findForbiddenMemoryContent("card 4111-1111-1111-1111"),
        )
    }

    @Test
    fun `luhn invalid card is not refused`() {
        assertNull(findForbiddenMemoryContent("编号 4111111111111112"))
    }

    @Test
    fun `luhn valid number with unknown bin is not refused`() {
        // 16 位、Luhn 通过，但前缀不在任何卡组织范围内 ⇒ 典型的订单号/哈希片段，不能拦
        assertNull(findForbiddenMemoryContent("订单号 1000000000000008 已支付"))
    }

    @Test
    fun `numbers outside the 15-19 digit window are not refused`() {
        // 已知覆盖缺口（与上游同款，见 MemoryContentGuard 类注释）：13/14 位旧卡号不覆盖，
        // 换取「短数字串不被误拦」——它们本身也过不了长度窗口
        assertNull(findForbiddenMemoryContent("编号 411111111111"))        // 12 位
        assertNull(findForbiddenMemoryContent("编号 4222222222222"))      // 13 位 Visa（Luhn 通过但不覆盖）
        assertNull(findForbiddenMemoryContent("编号 30569309025904"))     // 14 位 Diners（同上）
        assertNull(findForbiddenMemoryContent("编号 4111111111111111111")) // 19 位但 Luhn 不通过
    }

    // ---- 组合与边界 ----

    @Test
    fun `plain text without identifiers passes`() {
        assertNull(findForbiddenMemoryContent("用户偏好简洁回复，周末更活跃。"))
        assertNull(findForbiddenMemoryContent("用户的手机号尾号是 8888，很重要"))
    }

    @Test
    fun `category text never contains the matched digits`() {
        // 回执里不得回显命中原文——这是「别把敏感号码写进对话历史/日志」的实现前提
        val violation = findForbiddenMemoryContent("卡号 4111111111111111")!!
        assertEquals("a bank card number", violation.category)
        assertEquals(false, violation.category.contains("4111"))
    }

    @Test
    fun `multiple identifiers report the first matching category`() {
        val text = "身份证 11010519491231002X 与 SSN 078-05-1120"
        assertEquals(MemoryContentViolation.ID_NUMBER, findForbiddenMemoryContent(text))
    }
}
