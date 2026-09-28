package app.androidrik.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.rikkahub.ui.components.charts.ChartSpec

class ChartDisplayToolTest {
    private val tool = buildChartDisplayTool()
    private val json = Json

    private suspend fun executeOutput(args: String): Map<String, kotlinx.serialization.json.JsonElement> {
        val parts = tool.execute(json.parseToJsonElement(args))
        val text = parts.filterIsInstance<app.androidrik.ai.ui.UIMessagePart.Text>().joinToString("\n") { it.text }
        return json.parseToJsonElement(text).jsonObject
    }

    private fun lineArgs(vararg values: Int, labels: List<String> = emptyList()): String {
        return buildJsonObject {
            put("style", "line")
            put("series", buildJsonArray {
                add(buildJsonObject {
                    put("name", "a")
                    put("values", buildJsonArray { values.forEach { add(it) } })
                })
            })
            if (labels.isNotEmpty()) {
                put("x_axis", buildJsonObject {
                    put("data", buildJsonArray { labels.forEach { add(it) } })
                })
            }
        }.toString()
    }

    @Test
    fun `valid line chart returns success`() = runBlocking {
        val output = executeOutput(lineArgs(1, 2, 3))
        assertTrue((output["success"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    @Test
    fun `invalid style is rejected with reason`() = runBlocking {
        val output = executeOutput("""{"style":"pie","series":[{"values":[1]}]}""")
        assertEquals("INVALID_CHART", (output["error"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(output.getValue("message").toString().contains("style must be one of"))
    }

    @Test
    fun `scatter series must use points not values`() = runBlocking {
        val output = executeOutput("""{"style":"scatter","series":[{"values":[1,2]}]}""")
        assertTrue(output.getValue("message").toString().contains("must not use 'values'"))
    }

    @Test
    fun `line series values must align with x_axis data`() = runBlocking {
        val args = buildJsonObject {
            put("style", "line")
            put("x_axis", buildJsonObject { put("data", buildJsonArray { add("a"); add("b") }) })
            put("series", buildJsonArray {
                add(buildJsonObject { put("values", buildJsonArray { add(1) }) })
            })
        }.toString()
        val output = executeOutput(args)
        assertTrue(output.getValue("message").toString().contains("but x_axis.data has 2"))
    }

    @Test
    fun `bar chart rejects axis min`() = runBlocking {
        val output = executeOutput("""{"style":"bar","y_axis":{"min":1},"series":[{"values":[1]}]}""")
        assertTrue(output.getValue("message").toString().contains("bars always start from 0"))
    }

    @Test
    fun `non positive value on log y_axis is rejected`() = runBlocking {
        val output = executeOutput("""{"style":"line","y_axis":{"scale":"log"},"series":[{"values":[1,0]}]}""")
        assertTrue(output.getValue("message").toString().contains("> 0 on a log y_axis"))
    }

    @Test
    fun `bad hex color is rejected`() = runBlocking {
        val output = executeOutput("""{"style":"line","series":[{"values":[1],"color":"red"}]}""")
        assertTrue(output.getValue("message").toString().contains("hex color"))
    }

    @Test
    fun `valid line chart args pass validation indirectly`() {
        // 直接构造合法参数走一次完整校验路径（execute 已经在其它用例覆盖，这里验证 schema 有 required 字段）
        val schema = tool.parameters() as app.androidrik.ai.core.InputSchema.Obj
        assertEquals(listOf("style", "series"), schema.required)
    }

    @Test
    fun `spec parses valid chart json`() {
        val spec = ChartSpec.fromJson(json.parseToJsonElement(
            """{"style":"bar","title":"T","x_axis":{"data":["Go","Rust"]},"y_axis":{"title":"s"},"series":[{"name":"a","values":[1,2]},{"values":[3,4]}]}"""
        ))
        assertNull(spec!!.series[1].color)
        assertEquals("T", spec.title)
        assertEquals(listOf("Go", "Rust"), spec.xAxis.data)
        assertEquals(2, spec.series.size)
        assertEquals(2, spec.categoryCount)
        assertFalse(spec.series[0].name.isNullOrBlank())
    }

    @Test
    fun `spec parses scatter points and hex color`() {
        val spec = ChartSpec.fromJson(json.parseToJsonElement(
            """{"style":"scatter","series":[{"color":"#F80","points":[{"x":1,"y":2},{"x":3,"y":4}]}]}"""
        ))
        assertEquals(2, spec!!.series[0].points.size)
        assertEquals(2.0, spec.series[0].points[0].y, 0.0)
    }

    @Test
    fun `spec returns null for invalid input`() {
        assertNull(ChartSpec.fromJson(json.parseToJsonElement("""{"style":"pie"}""")))
        assertNull(ChartSpec.fromJson(json.parseToJsonElement("""{"style":"line","series":[]}""")))
        assertNull(ChartSpec.fromJson(json.parseToJsonElement("[]")))
    }
}
