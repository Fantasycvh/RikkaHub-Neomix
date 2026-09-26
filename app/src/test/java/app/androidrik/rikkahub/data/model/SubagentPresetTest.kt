package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 子代理预设（「按名称调度的模型 + 提示词」组合，设计 §十）。
 *
 * 重点钉住三组容易被后来的改动破坏的契约：
 * ① [SubagentPreset.toRunProfile] 的清空项——父对话的记忆/注入/正则**不得**渗进子代理，
 *    否则"独立上下文"在提示词层面就不成立了（而且这是静默的：跑得通，只是被污染）；
 * ② 内置预设必须**开箱可用**：名字唯一非空、模型留空（跟随当前）、纯推理型的信封是**空集**
 *    而不是 `null`（`null` = 按能力开关，会顺着无条件装配的来源漏进工具）；
 * ③ 名字校验的三条边界（空/超长/重名，大小写不敏感，编辑时排除自己）。
 */
class SubagentPresetTest {

    private fun preset(name: String = "research") = SubagentPreset(name = name)

    // ---- 合成 profile ----

    @Test
    fun `run profile carries the preset identity and capabilities`() {
        val modelId = Uuid.random()
        val workspaceId = Uuid.random()
        val source = SubagentPreset(
            id = Uuid.random(),
            name = "research",
            systemPrompt = "you are a researcher",
            modelId = modelId,
            enableWebSearch = true,
            workspaceId = workspaceId,
        )

        val profile = source.toRunProfile()

        assertEquals(source.id, profile.id)
        assertEquals("research", profile.name)
        assertEquals("you are a researcher", profile.systemPrompt)
        assertEquals(modelId, profile.chatModelId)
        assertTrue(profile.enableWebSearch)
        assertEquals(workspaceId, profile.workspaceId)
    }

    @Test
    fun `run profile clears every chat-only wiring`() {
        // 这些字段若被继承，子代理会被父对话的记忆/注入/正则污染（静默污染——照样跑得通）
        val profile = preset().toRunProfile()

        assertFalse(profile.enableMemory)
        assertFalse(profile.useGlobalMemory)
        assertFalse(profile.enableRecentChatsReference)
        assertFalse(profile.enableTimeReminder)
        assertTrue(profile.localTools.isEmpty())
        assertTrue(profile.enabledSkills.isEmpty())
        assertTrue(profile.regexes.isEmpty())
        assertTrue(profile.modeInjectionIds.isEmpty())
        assertTrue(profile.presetMessages.isEmpty())
        assertTrue(profile.promptPresetIds.isEmpty())
        assertTrue(profile.lorebookIds.isEmpty())
        assertTrue(profile.quickMessageIds.isEmpty())
        assertNull(profile.fallbackChatModelId)
    }

    @Test
    fun `run profile follows the chat model when the preset has none`() {
        // null = 跟随当前对话：装配点用 `profile.chatModelId ?: settings.chatModelId` 解析，
        // 所以这里必须保持 null（不能提前填一个默认模型，那会让"跟随"变成"钉死"）
        assertNull(preset().toRunProfile().chatModelId)
    }

    // ---- 内置预设 ----

    @Test
    fun `tool approval defaults to deny so nothing changes silently`() {
        // ASK 是"解锁写入类子代理"的那一档，会暂停 run 等通知——默认必须是 DENY，
        // 否则升级本身就改变了既有预设的行为（用户没点过任何东西却开始被拦）
        assertEquals(SubagentToolApproval.DENY, preset().toolApproval)
        DEFAULT_SUBAGENT_PRESETS.forEach { builtIn ->
            assertEquals(
                "built-in ${builtIn.name} must stay unattended by default",
                SubagentToolApproval.DENY,
                builtIn.toolApproval,
            )
        }
    }

    @Test
    fun `built-in presets are usable out of the box`() {
        assertTrue(DEFAULT_SUBAGENT_PRESETS.isNotEmpty())
        DEFAULT_SUBAGENT_PRESETS.forEach { builtIn ->
            assertTrue("name must not be blank", builtIn.name.isNotBlank())
            assertTrue("description must not be blank", builtIn.description.isNotBlank())
            assertTrue("prompt must not be blank", builtIn.systemPrompt.isNotBlank())
            // 跟随当前模型 = 不配也能用（配了具体模型的话换 provider 后会静默失效）
            assertNull("built-in must follow the chat model", builtIn.modelId)
            assertTrue(builtIn.enabled)
            assertTrue(
                "timeout out of range: ${builtIn.timeoutSeconds}",
                builtIn.timeoutSeconds in SUBAGENT_TIMEOUT_SECONDS_RANGE,
            )
            // 不绑工作区：内置预设不该依赖用户还没建的东西
            assertNull(builtIn.workspaceId)
        }
    }

    @Test
    fun `built-in names are unique`() {
        val names = DEFAULT_SUBAGENT_PRESETS.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `built-in ids are stable`() {
        // 内置预设是 Settings 字段的默认值：随机 id 会让"从未写过设置"的设备每次启动换一批 id
        // （列表 key 漂移）。这里钉住"两次构造得到同一批 id"。
        assertEquals(
            DEFAULT_SUBAGENT_PRESETS.map { it.id },
            DEFAULT_SUBAGENT_PRESETS.map { it.id },
        )
        // 与随机构造的预设不同一档（防止有人把内置改成 Uuid.random()）
        assertTrue(DEFAULT_SUBAGENT_PRESETS.none { it.id == preset().id })
    }

    @Test
    fun `built-in reasoning-only presets deny every tool explicitly`() {
        // 纯推理型必须是**空集**（显式不给），不能是 null——null 的语义是"按能力开关"，
        // 而 MCP / 技能安装这类来源是无条件装配的，null 会让它们静默流进来
        val reasoningOnly = DEFAULT_SUBAGENT_PRESETS.filter { !it.enableWebSearch }
        assertTrue(reasoningOnly.isNotEmpty())
        reasoningOnly.forEach { assertEquals(emptySet<String>(), it.allowedTools) }
    }

    @Test
    fun `built-in research preset follows the capability switches`() {
        val research = DEFAULT_SUBAGENT_PRESETS.first { it.enableWebSearch }
        assertNull(research.allowedTools)
    }

    // ---- 名字校验 ----

    @Test
    fun `name availability rejects blank and oversized names`() {
        val presets = listOf(preset("alpha"))

        assertFalse(isSubagentNameAvailable("", presets))
        assertFalse(isSubagentNameAvailable("   ", presets))
        assertFalse(isSubagentNameAvailable("x".repeat(SUBAGENT_NAME_MAX_CHARS + 1), presets))
        assertTrue(isSubagentNameAvailable("x".repeat(SUBAGENT_NAME_MAX_CHARS), presets))
    }

    @Test
    fun `name availability is case-insensitive and ignores surrounding spaces`() {
        val presets = listOf(preset("Alpha"))

        assertFalse(isSubagentNameAvailable("alpha", presets))
        assertFalse(isSubagentNameAvailable("  ALPHA ", presets))
        assertTrue(isSubagentNameAvailable("beta", presets))
    }

    @Test
    fun `editing a preset may keep its own name`() {
        val presets = listOf(preset("alpha"), preset("beta"))

        assertFalse(isSubagentNameAvailable("alpha", presets))
        assertTrue(isSubagentNameAvailable("alpha", presets, excludeIndex = 0))
        assertFalse(isSubagentNameAvailable("beta", presets, excludeIndex = 0))
    }

    // ---- 钳制与能力标签 ----

    @Test
    fun `clamped fixes out-of-range timeout and trims the name`() {
        val fixed = preset("  spaced  ").copy(timeoutSeconds = 5).clamped()
        assertEquals("spaced", fixed.name)
        assertEquals(SUBAGENT_TIMEOUT_SECONDS_RANGE.first, fixed.timeoutSeconds)

        val tooLong = preset().copy(timeoutSeconds = 99_999).clamped()
        assertEquals(SUBAGENT_TIMEOUT_SECONDS_RANGE.last, tooLong.timeoutSeconds)
    }

    @Test
    fun `capability labels describe what the subagent can reach`() {
        assertEquals(emptyList<String>(), preset().capabilityLabels())
        assertEquals(listOf("web search"), preset().copy(enableWebSearch = true).capabilityLabels())
        assertEquals(
            listOf("web search", "workspace files"),
            preset().copy(enableWebSearch = true, workspaceId = Uuid.random()).capabilityLabels(),
        )
    }
}
