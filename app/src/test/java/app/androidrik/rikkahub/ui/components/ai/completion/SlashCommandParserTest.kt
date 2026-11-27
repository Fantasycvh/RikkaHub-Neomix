package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import app.androidrik.rikkahub.ui.components.ai.mergeCompletionLists
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandParserTest {
    private val installedSkills = setOf("review", "summarize", "compress", "daily-digest")

    @Test
    fun `parses builtin commands with and without arguments`() {
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.NEW, "")),
            parseSendTimeSlashInput("/new", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.NEW, "")),
            parseSendTimeSlashInput("  /NEW  ", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.COMPRESS, "保留所有代码块")),
            parseSendTimeSlashInput("/compress 保留所有代码块", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.GOAL, "clear")),
            parseSendTimeSlashInput("/goal clear", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.NOTE, "第一行\n第二行")),
            parseSendTimeSlashInput("/note\n第一行\n第二行", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        for (cmd in BuiltinSlashCommand.entries) {
            val result = parseSendTimeSlashInput("/${cmd.commandName}", allowBuiltinCommands = true, skillExists = { false })
            assertEquals(SendSlashParseResult.ClientCommand(SlashCommandCall(cmd, "")), result)
        }
    }

    @Test
    fun `builtin command takes priority over colliding skill while explicit skill namespace disambiguates`() {
        assertEquals(
            SendSlashParseResult.ClientCommand(SlashCommandCall(BuiltinSlashCommand.COMPRESS, "精简")),
            parseSendTimeSlashInput("/compress 精简", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("compress"), "审查这里"),
            parseSendTimeSlashInput("/skill:compress 审查这里", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("compress", "review"), "双重检查"),
            parseSendTimeSlashInput("/skill:compress /review 双重检查", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
    }

    @Test
    fun `parses chained skills preserves deduplicated order and stops at file paths`() {
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("review", "summarize"), "分析这段日志"),
            parseSendTimeSlashInput("/review /summarize /review 分析这段日志", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("review"), "/etc/hosts 看看这个文件"),
            parseSendTimeSlashInput("/review /etc/hosts 看看这个文件", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("review", "daily-digest"), ""),
            parseSendTimeSlashInput("/review /daily-digest", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
    }

    @Test
    fun `reports unknown explicit skill but leaves ordinary slash text plain`() {
        assertEquals(
            SendSlashParseResult.UnknownExplicitSkill("missing"),
            parseSendTimeSlashInput("/skill:missing hello", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.UnknownExplicitSkill("nope"),
            parseSendTimeSlashInput("/review /skill:nope hello", allowBuiltinCommands = true, skillExists = installedSkills::contains),
        )
        for (plain in listOf("/etc/hosts 是什么", "/2026 年度计划", "hello /new", "https://example.com")) {
            assertEquals(
                plain,
                SendSlashParseResult.Plain,
                parseSendTimeSlashInput(plain, allowBuiltinCommands = true, skillExists = installedSkills::contains),
            )
        }
    }

    @Test
    fun `disabling builtin commands lets colliding skill resolve directly`() {
        assertEquals(
            SendSlashParseResult.SkillChain(listOf("compress"), "处理每日报告"),
            parseSendTimeSlashInput("/compress 处理每日报告", allowBuiltinCommands = false, skillExists = installedSkills::contains),
        )
        assertEquals(
            SendSlashParseResult.Plain,
            parseSendTimeSlashInput("/new 每天早上提醒我", allowBuiltinCommands = false, skillExists = installedSkills::contains),
        )
    }

    @Test
    fun `slash command completion only triggers at input start and respects availability`() = runBlocking {
        val emptyProvider = SlashCommandCompletionProvider(SlashCommandAvailability(hasMessages = false))
        val midSentence = emptyProvider.complete(ChatCompletionContext("请帮我 /comp", TextRange(9)))
        assertNull(midSentence)

        val explicitSkill = emptyProvider.complete(ChatCompletionContext("/skill:comp", TextRange(11)))
        assertNull(explicitSkill)

        val bare = emptyProvider.complete(ChatCompletionContext("/", TextRange(1)))!!
        val labels = bare.items.map { it.label }
        assertTrue("/new" in labels)
        assertTrue("/compress" in labels)
        assertTrue("/inspect" in labels)
        assertTrue("/retry" !in labels)
        assertTrue("/fork" !in labels)
        assertTrue("/continue" !in labels)
        assertTrue("/stop" !in labels)

        val busyProvider = SlashCommandCompletionProvider(
            SlashCommandAvailability(hasMessages = true, isGenerating = true, hasGoal = true),
        )
        val busyBare = busyProvider.complete(ChatCompletionContext("/", TextRange(1)))!!
        val merged = mergeCompletionLists(listOf(busyBare))!!
        assertEquals("/stop", merged.items.first().label)
    }

    @Test
    fun `argument stage completion builds executable items for compress goal and note`() = runBlocking {
        val provider = SlashCommandCompletionProvider(SlashCommandAvailability(hasMessages = true, hasGoal = true))

        val compressArg = provider.complete(ChatCompletionContext("/compress 保留代码块", TextRange(15)))!!
        assertEquals(TextRange(0, 15), compressArg.replacementRange)
        assertEquals(
            ChatCompletionEffect.ExecuteCommand(SlashCommandCall(BuiltinSlashCommand.COMPRESS, "保留代码块")),
            compressArg.items.single().effect,
        )

        val goalClear = provider.complete(ChatCompletionContext("/goal cl", TextRange(8)))!!
        assertTrue(goalClear.items.any {
            it.effect == ChatCompletionEffect.ExecuteCommand(SlashCommandCall(BuiltinSlashCommand.GOAL, "clear"))
        })

        val noteArg = provider.complete(ChatCompletionContext("/note 稍后跟进", TextRange(10)))!!
        assertEquals(
            ChatCompletionEffect.ExecuteCommand(SlashCommandCall(BuiltinSlashCommand.NOTE, "稍后跟进")),
            noteArg.items.single().effect,
        )
    }

    @Test
    fun `mergeCompletionLists groups commands before skills without truncating past eight items`() {
        val commands = (1..6).map { i ->
            ChatCompletionItem(
                label = "/cmd$i",
                insertText = "",
                sortScore = 10 - i,
                key = "cmd:$i",
                group = ChatCompletionGroup.COMMAND,
            )
        }
        val skills = (1..6).map { i ->
            ChatCompletionItem(
                label = "/skill$i",
                insertText = "",
                sortScore = 100 - i,
                key = "skill:$i",
                group = ChatCompletionGroup.SKILL,
            )
        }
        val merged = mergeCompletionLists(
            listOf(
                ChatCompletionList("skill", TextRange(0, 1), skills),
                ChatCompletionList("cmd", TextRange(0, 1), commands),
            ),
        )!!
        assertEquals(12, merged.items.size)
        assertTrue(merged.items.take(6).all { it.group == ChatCompletionGroup.COMMAND })
        assertTrue(merged.items.drop(6).all { it.group == ChatCompletionGroup.SKILL })
    }
}
