package app.androidrik.rikkahub.data.ai.tools

/**
 * 记忆写入前的**代码级兜底拦截**：证件号 / 卡号这类硬标识不能只靠提示词约束
 * （提示词是第一道，这里是第二道；来源=上游 #2130 的 `MemoryContentGuard`，本仓适配）。
 *
 * 三条规则（都要求**通过各自的校验算法**，不是「像数字就拦」）：
 * - 中国居民身份证：18 位 + **GB 11643 校验位**
 * - 美国 SSN：`\d{3}-\d{2}-\d{4}`，排除 000/666/9xx 区号与 00 组号、0000 序号
 * - 银行卡：15–19 位 + **Luhn 校验** + **已知 BIN 前缀**（见下）
 *
 * **为什么卡号要加 BIN 白名单**（与上游的差异）：纯 Luhn 会让约 **10%** 的随机
 * 15–19 位数字（订单号、哈希片段、时间戳拼接）被误拦；叠上已知卡组织前缀后，
 * 随机数字的误报降到约 **2%** 量级。代价是**冷门卡组织或 13/14 位旧卡号不在覆盖内**
 * （护栏是兜底、提示词才是主约束，漏拦比误拦可接受）。
 *
 * **不覆盖**：护照号、社保卡、驾照等（无校验位可判，硬拦会大量误伤——交给提示词条款）。
 *
 * 纯函数、无 Android 依赖 ⇒ 可 JVM 单测。
 */
internal enum class MemoryContentViolation(val category: String) {
    /** 中国居民身份证（带校验位） */
    ID_NUMBER("an ID number"),

    /** 美国社会安全号 */
    SOCIAL_SECURITY_NUMBER("a social security number"),

    /** 银行卡号（Luhn + 已知 BIN） */
    BANK_CARD_NUMBER("a bank card number"),
}

/** 命中时返回类别，否则 null。**返回类别而非命中原文**——原文不得进对话历史/日志。 */
internal fun findForbiddenMemoryContent(text: String): MemoryContentViolation? = when {
    chineseIdRegex.findAll(text).any { isValidChineseId(it.value) } -> MemoryContentViolation.ID_NUMBER
    ssnRegex.containsMatchIn(text) -> MemoryContentViolation.SOCIAL_SECURITY_NUMBER
    paymentCardRegex.findAll(text).any { candidate ->
        val digits = candidate.value.filter(Char::isDigit)
        hasRecognisedCardBin(digits) && isLuhnValid(digits)
    } -> MemoryContentViolation.BANK_CARD_NUMBER

    else -> null
}

// ---- 身份证 ----

/** 18 位、出生日期形状正确（月份/日期的宽松形状，主要靠校验位定真伪） */
private val chineseIdRegex =
    Regex("""(?<!\d)[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx](?!\d)""")

private val chineseIdWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
private const val CHINESE_ID_CHECK_CHARS = "10X98765432"

private fun isValidChineseId(id: String): Boolean {
    val sum = chineseIdWeights.indices.sumOf { (id[it] - '0') * chineseIdWeights[it] }
    return CHINESE_ID_CHECK_CHARS[sum % 11] == id.last().uppercaseChar()
}

// ---- SSN ----

/** 排除官方保留段（000/666/9xx 区号、00 组号、0000 序号），减少对「x3-x2-x4」式编号的误伤 */
private val ssnRegex = Regex("""(?<![\d-])(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}(?![\d-])""")

// ---- 银行卡 ----

/** 15–19 位数字（允许空格/短横线分隔；13/14 位旧卡号不覆盖，见类注释） */
private val paymentCardRegex = Regex("""(?<![\d-])\d(?:[ -]?\d){14,18}(?![\d-])""")

/**
 * 已知卡组织 BIN 前缀（覆盖 Visa / Mastercard（含 2 系）/ Amex / UnionPay / JCB /
 * Discover / Diners）。识不出前缀就**不拦**——宁可漏，不误伤订单号。
 */
private fun hasRecognisedCardBin(digits: String): Boolean {
    if (digits.length < 15 || digits.length > 19) return false
    return when {
        digits.startsWith("4") -> true // Visa
        digits.startsWith("62") -> true // UnionPay
        digits.startsWith("6011") -> true // Discover
        bin(digits, 2) in 51..55 -> true // Mastercard
        bin(digits, 4) in 2221..2720 -> true // Mastercard 2 系
        bin(digits, 2) == 34 || bin(digits, 2) == 37 -> true // Amex
        bin(digits, 4) in 3528..3589 -> true // JCB
        bin(digits, 3) in 644..649 -> true // Discover
        bin(digits, 2) == 65 -> true // Discover
        bin(digits, 3) in 300..305 -> true // Diners
        bin(digits, 2) == 36 || bin(digits, 2) == 38 || bin(digits, 2) == 39 -> true // Diners
        else -> false
    }
}

private fun bin(digits: String, length: Int): Int =
    if (digits.length < length) -1 else digits.take(length).toIntOrNull() ?: -1

private fun isLuhnValid(digits: String): Boolean {
    var sum = 0
    digits.reversed().forEachIndexed { index, char ->
        var digit = char - '0'
        if (index % 2 == 1) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
    }
    return sum % 10 == 0
}
