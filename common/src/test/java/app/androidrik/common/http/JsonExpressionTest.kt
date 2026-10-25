package app.androidrik.common.http

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonExpressionTest {
    private val root = buildJsonObject { put("a", 1) }

    @Test
    fun `evaluate simple path expression`() {
        assertEquals("1", evaluateJsonExpr("a", root))
    }

    @Test
    fun `evaluate oversized input returns empty without crash`() {
        // 回归：超长/深嵌套表达式曾触发 StackOverflowError（Error 捕不到）
        val long = "(".repeat(600) + "1" + ")".repeat(600)
        assertEquals("", evaluateJsonExpr(long, root))
    }

    @Test
    fun `evaluate invalid syntax returns empty`() {
        assertEquals("", evaluateJsonExpr("a +*", root))
    }

    @Test
    fun `evaluate missing field returns empty`() {
        assertEquals("", evaluateJsonExpr("missing.field", root))
    }

    @Test
    fun `evaluate json number is locale independent`() {
        // 回归 #2245：逗号小数 locale（波兰语等）下 "%.2f" 产出 "5,00"，toDouble 回读失败
        // 导致余额表达式整体抛错、显示为空
        val root2 = buildJsonObject { put("c", 5.0); put("u", 2.5) }
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pl-PL"))
            assertEquals("5", evaluateJsonExpr("c", root2))
            assertEquals("2.5", evaluateJsonExpr("c - u", root2))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
