package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.SkillSaver

/**
 * 动态添加 Skill 工具（上游 #1031）：
 * - install_skill：从 GitHub 仓库/子目录安装 Skill（含 SKILL.md + 附属文件）
 * - save_skill：把对话中总结的经验/工作流保存为 Skill（自动生成 frontmatter）
 *
 * 安装/保存后不自动启用——用户需在助手设置里勾选（避免越权改变助手能力）。
 */
fun createSkillInstallTools(skillInstaller: SkillSaver): List<Tool> = listOf(
    Tool(
        name = "install_skill",
        description = """
            Install a skill from a GitHub repository or a subdirectory of a repository.
            The repository must contain a SKILL.md with 'name' and 'description' in its frontmatter.
            Use this when the user asks to install a skill from GitHub.
            Example: https://github.com/owner/repo or https://github.com/owner/repo/tree/main/skills/my-skill
            The skill is saved but NOT automatically enabled - user must enable it in assistant settings.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("github_url", buildJsonObject {
                        put("type", "string")
                        put("description", "GitHub repository URL or tree URL pointing to a skill directory containing SKILL.md")
                    })
                },
                required = listOf("github_url")
            )
        },
        execute = { args ->
            val url = args.jsonObject["github_url"]?.jsonPrimitive?.contentOrNull
                ?: error("github_url is required")
            skillInstaller.installFromGitHub(url)
                .fold(
                    onSuccess = { skill ->
                        listOf(
                            UIMessagePart.Text(
                                "Skill '${skill.name}' installed successfully. " +
                                    "It is NOT enabled yet - the user must enable it in the assistant's skill settings."
                            )
                        )
                    },
                    onFailure = { e -> error("Install skill failed: ${e.message}") }
                )
        }
    ),
    Tool(
        name = "save_skill",
        description = """
            Save a reusable skill from the conversation. The skill content should be written as a SKILL.md
            body (instructions/methodology/workflow), with a short name and description.
            Use this when the user asks to save their experience, workflow, or a reusable procedure as a skill,
            or when a useful pattern emerged that should be reusable later.
            The skill is saved but NOT automatically enabled - user must enable it in assistant settings.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Skill name (lowercase-hyphen, no path separators)")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "One-line description of what this skill does")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Full SKILL.md body content: instructions, steps, examples, workflow")
                    })
                },
                required = listOf("name", "description", "content")
            )
        },
        execute = { args ->
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                ?: error("name is required")
            val description = args.jsonObject["description"]?.jsonPrimitive?.contentOrNull
                ?: error("description is required")
            val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
                ?: error("content is required")
            skillInstaller.saveFromContent(name, description, content)
                .fold(
                    onSuccess = { skill ->
                        listOf(
                            UIMessagePart.Text(
                                "Skill '${skill.name}' saved successfully. " +
                                    "It is NOT enabled yet - the user must enable it in the assistant's skill settings."
                            )
                        )
                    },
                    onFailure = { e -> error("Save skill failed: ${e.message}") }
                )
        }
    )
)
