package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SillyTavern 提示词预设导入测试（`PromptPresetSerializer` 的 ST 分支）。
 *
 * 背景：此前 ST 预设文件只在**角色卡导入**处被明确拒绝（`assistant_importer_is_preset`），
 * 因为当时没有「预设」这个概念可承接。现在有了容器，故新增导入。
 *
 * ST 结构（`openai.js:377-378`）：`prompts`（对象数组）+ `prompt_order`（有序 identifier，
 * 按 character_id 分组）。**顺序即优先级**。
 */
class SillyTavernPresetImportTest {

    private fun preset(
        identifier: String,
        content: String,
        name: String? = null,
        role: String? = null,
    ): String {
        val namePart = name?.let { "\"name\": \"$it\"," } ?: ""
        val rolePart = role?.let { "\"role\": \"$it\"," } ?: ""
        return """{"identifier": "$identifier", $namePart$rolePart "content": ${
            if (content.isEmpty()) "\"\"" else "\"$content\""
        }}"""
    }

    private fun stJson(
        prompts: List<String>,
        order: List<String>,
    ): String = """
        {
          "prompts": [${prompts.joinToString(",")}],
          "prompt_order": [{"character_id": 100000, "order": [${order.joinToString(",") { "{\"identifier\": \"$it\", \"enabled\": true}" }}]}]
        }
    """.trimIndent()

    // 需要访问 serializer 的 ST 分支；它是 private，故通过公开 import 路径测不到，
    // 这里直接测同包内的可见实现（internal 可见性）。
    private fun parse(json: String, name: String? = "test") =
        parseSillyTavernPreset(json, name)

    // region 基本解析

    @Test
    fun `imports entries in prompt_order sequence`() {
        val json = stJson(
            prompts = listOf(
                preset("main", "MAIN_CONTENT", name = "Main Prompt"),
                preset("jailbreak", "JAIL_CONTENT", name = "Jailbreak"),
            ),
            order = listOf("main", "jailbreak"),
        )
        val result = parse(json)
        assertNotNull(result)
        assertEquals(2, result!!.entries.size)
        assertEquals(listOf("MAIN_CONTENT", "JAIL_CONTENT"), result.entries.map { it.content })
    }

    @Test
    fun `order drives priority descending so display equals applied`() {
        val json = stJson(
            prompts = listOf(preset("a", "A"), preset("b", "B"), preset("c", "C")),
            order = listOf("a", "b", "c"),
        )
        val result = parse(json)!!
        // 第一个（顺序最前）优先级最高
        assertEquals(listOf(2, 1, 0), result.entries.map { it.priority })
    }

    @Test
    fun `prompt_order sequence wins over prompts array order`() {
        // prompts 里 a 在前，但 order 里 b 在前 → 结果应以 order 为准
        val json = stJson(
            prompts = listOf(preset("a", "A"), preset("b", "B")),
            order = listOf("b", "a"),
        )
        val result = parse(json)!!
        assertEquals(listOf("B", "A"), result.entries.map { it.content })
    }

    // endregion

    // region 跳过运行时占位槽位

    @Test
    fun `empty content placeholders are skipped`() {
        // chatHistory / worldInfoBefore 等是 ST 运行时填充的槽位，导入应为空 → 必须跳过
        val json = stJson(
            prompts = listOf(
                preset("main", "MAIN"),
                preset("chatHistory", ""),
                preset("worldInfoBefore", ""),
                preset("charDescription", ""),
                preset("jailbreak", "JAIL"),
            ),
            order = listOf("main", "worldInfoBefore", "charDescription", "chatHistory", "jailbreak"),
        )
        val result = parse(json)!!
        assertEquals("only real content should be kept", 2, result.entries.size)
        assertEquals(listOf("MAIN", "JAIL"), result.entries.map { it.content })
        // 优先级要按**保留下来的**条目重新连续赋值（否则会出现空洞）
        assertEquals(listOf(1, 0), result.entries.map { it.priority })
    }

    @Test
    fun `whitespace-only content is skipped`() {
        val json = stJson(
            prompts = listOf(preset("a", "   "), preset("b", "B")),
            order = listOf("a", "b"),
        )
        assertEquals(listOf("B"), parse(json)!!.entries.map { it.content })
    }

    // endregion

    // region role 与名称

    @Test
    fun `role is preserved and defaults to user`() {
        val json = stJson(
            prompts = listOf(
                preset("s", "S", role = "system"),
                preset("u", "U", role = "user"),
                preset("a", "A", role = "assistant"),
                preset("n", "N"),                       // 无 role
            ),
            order = listOf("s", "u", "a", "n"),
        )
        val roles = parse(json)!!.entries.map { it.role }
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            roles,
        )
    }

    @Test
    fun `name falls back to identifier when missing`() {
        val json = stJson(
            prompts = listOf(preset("my_identifier", "X")),
            order = listOf("my_identifier"),
        )
        assertEquals("my_identifier", parse(json)!!.entries.single().name)
    }

    @Test
    fun `explicit name is preferred`() {
        val json = stJson(
            prompts = listOf(preset("id1", "X", name = "Display Name")),
            order = listOf("id1"),
        )
        assertEquals("Display Name", parse(json)!!.entries.single().name)
    }

    @Test
    fun `preset name comes from file name`() {
        val json = stJson(prompts = listOf(preset("a", "A")), order = listOf("a"))
        assertEquals("MyPreset", parse(json, "MyPreset")!!.name)
    }

    // endregion

    // region 拒绝非预设文件（沿用「默认值 + ignoreUnknownKeys」防线）

    @Test
    fun `json without prompts is rejected`() {
        assertNull(parse("""{"foo": 1}"""))
    }

    @Test
    fun `json without prompt_order is rejected`() {
        assertNull(parse("""{"prompts": [{"identifier": "a", "content": "x"}]}"""))
    }

    @Test
    fun `character card is rejected`() {
        val card = """{"spec": "chara_card_v2", "data": {"name": "Alice"}}"""
        assertNull(parse(card))
    }

    @Test
    fun `malformed json is rejected without throwing`() {
        assertNull(parse("{not json"))
    }

    @Test
    fun `preset with all-empty content yields an empty entry list but still parses`() {
        // 全占位符的预设是合法文件（只是没有可导入内容），不应被当成「非预设」拒绝
        val json = stJson(
            prompts = listOf(preset("chatHistory", ""), preset("worldInfoBefore", "")),
            order = listOf("chatHistory", "worldInfoBefore"),
        )
        val result = parse(json)
        assertNotNull(result)
        assertTrue(result!!.entries.isEmpty())
    }

    // endregion

    // region 去重

    @Test
    fun `duplicate identifiers in order are deduplicated`() {
        val json = stJson(
            prompts = listOf(preset("a", "A")),
            order = listOf("a", "a"),
        )
        assertEquals(1, parse(json)!!.entries.size)
    }

    @Test
    fun `identifier in order but missing from prompts is ignored`() {
        val json = stJson(prompts = listOf(preset("a", "A")), order = listOf("a", "ghost"))
        val result = parse(json)!!
        assertEquals(1, result.entries.size)
        assertFalse(result.entries.any { it.content == "ghost" })
    }

    // endregion
}
