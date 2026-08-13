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
}
