package app.androidrik.rikkahub.data.files

import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Skill 安装/保存共享逻辑：供管理页（SkillsVM）与 AI 工具（install_skill / save_skill）共用。
 *
 * - GitHub 拉取：从 SkillsVM 抽取（Contents API 递归列文件 + 限量下载 + 原子落盘）
 * - 保存：校验 frontmatter 后原子写入 SKILL.md
 */
/** Skill 安装/保存抽象：AI 工具与 UI 共用，便于测试替身 */
interface SkillSaver {
    suspend fun installFromGitHub(repoUrl: String): Result<SkillMetadata>
    suspend fun saveFromContent(name: String, description: String, content: String): Result<SkillMetadata>
}

class SkillInstaller(
    private val skillManager: SkillManager,
) : SkillSaver {
    /** GitHub 下载响应最大字符数（约 4MB）——防 OOM */
    private val maxDownloadChars = 4_000_000

    // ---- GitHub URL 解析（纯逻辑，可单测） ----

    data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    companion object {
    fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    // ---- 内容校验（纯逻辑，可单测） ----

    /** 校验 skill 名称/内容合法性；合法返回 null，否则返回错误信息 */
    fun validateSkillContent(name: String, description: String, content: String): String? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return "缺少 name 字段"
        if (description.isBlank()) return "缺少 description 字段"
        if (content.isBlank()) return "技能内容为空"
        if (trimmedName.contains('/') || trimmedName.contains('\\')) return "技能名不能包含路径分隔符"
        if (trimmedName == "." || trimmedName == "..") return "非法技能名"
        return null
    }

    /**
     * 由 name/description/body 生成完整 SKILL.md（frontmatter + body）。
     *
     * [preservedFrontmatterLines] 是**覆盖已有技能时**要原样保留的 frontmatter 行
     * （不含 name/description，由本函数生成）。见 [saveFromContent] 的说明。
     */
    fun buildSkillMarkdown(
        name: String,
        description: String,
        content: String,
        preservedFrontmatterLines: List<String> = emptyList(),
    ): String {
        val body = content.trim()
        return buildString {
            append("---\n")
            append("name: $name\n")
            append("description: $description\n")
            preservedFrontmatterLines.forEach { append("$it\n") }
            append("---\n\n")
            append(body)
            if (!body.endsWith("\n")) append('\n')
        }
    }

    }

    // ---- 安装 / 保存 ----

    /** 从 GitHub 安装技能：拉取 SKILL.md + 附属文件 → 原子落盘。成功返回 SkillMetadata。 */
    override suspend fun installFromGitHub(repoUrl: String): Result<SkillMetadata> = withContext(Dispatchers.IO) {
        try {
            val info = parseGitHubUrl(repoUrl)
                ?: return@withContext Result.failure(IllegalArgumentException("无效的 GitHub 仓库链接"))
            val files = mutableListOf<Pair<String, String>>() // relativePath -> downloadUrl
            val listed = listFilesRecursively(info.owner, info.repo, info.branch, info.path, info.path, files)
            if (!listed) return@withContext Result.failure(IllegalStateException("读取 GitHub 目录失败"))

            val skillMdEntry = files.find { it.first == "SKILL.md" }
                ?: return@withContext Result.failure(IllegalStateException("目录中未找到 SKILL.md"))
            val skillMdContent = downloadText(skillMdEntry.second)
                ?: return@withContext Result.failure(IllegalStateException("下载 SKILL.md 失败，请检查链接或网络"))

            val frontmatter = SkillFrontmatterParser.parse(skillMdContent)
            val name = frontmatter["name"]
            if (name.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("SKILL.md 格式错误：缺少 name 字段"))
            }
            val fileContents = LinkedHashMap<String, String>()
            for ((relativePath, downloadUrl) in files) {
                val content = downloadText(downloadUrl)
                    ?: return@withContext Result.failure(IllegalStateException("下载文件失败：$relativePath"))
                fileContents[relativePath] = content
            }
            val saved = skillManager.saveSkillFilesAtomically(name, fileContents)
            if (!saved) return@withContext Result.failure(IllegalStateException("保存失败"))
            val metadata = skillManager.listSkills().find { it.name == name }
                ?: return@withContext Result.failure(IllegalStateException("保存后未找到技能：$name"))
            Result.success(metadata)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 由 name/description/content 保存单个 SKILL.md。成功返回 SkillMetadata。
     *
     * **覆盖已有同名技能时会保留用户手写内容**（#1901 修复的两处数据丢失）：
     * 1. `name`/`description` 之外的 frontmatter 键（`category`、`tags`、`compatibility`…）
     *    ——原实现用模板拼串只写两行，用户手写的分类会被整段抹掉；
     * 2. 目录里的既有附属文件——`saveSkillFilesAtomically` 是**整目录替换**，只提交
     *    SKILL.md 会把示例/脚本一并删掉。
     *
     * 保留策略是**保守**的：只替换本函数能正确生成的两样（SKILL.md 的 body 与 name/description），
     * 其余一律原样带回，避免「AI 保存一次经验，用户的技能目录被清空」。
     */
    override suspend fun saveFromContent(name: String, description: String, content: String): Result<SkillMetadata> =
        withContext(Dispatchers.IO) {
            try {
                val trimmedName = name.trim()
                val error = validateSkillContent(trimmedName, description, content)
                if (error != null) return@withContext Result.failure(IllegalArgumentException(error))
                // 同名技能已存在时，取回其 frontmatter 其它键与全部附属文件
                val existingContent = skillManager.readSkillContent(trimmedName)
                val preservedFrontmatter = existingContent?.let {
                    otherFrontmatterEntryLines(it, excludeKeys = setOf("name", "description"))
                }.orEmpty()
                val md = buildSkillMarkdown(trimmedName, description, content, preservedFrontmatter)
                val files = skillManager.readSkillFilesBytes(trimmedName)?.toMutableMap() ?: mutableMapOf()
                files["SKILL.md"] = md.toByteArray()
                if (!skillManager.saveSkillFileBytesAtomically(trimmedName, files)) {
                    return@withContext Result.failure(IllegalStateException("保存失败"))
                }
                val saved = skillManager.listSkills().find { it.name == trimmedName }
                    ?: return@withContext Result.failure(IllegalStateException("保存后未找到技能：$trimmedName"))
                Result.success(saved)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ---- GitHub 拉取内部 ----

    private fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<Pair<String, String>>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadText(apiUrl) ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result.add(relativePath to downloadUrl)
                }

                "dir" -> {
                    val ok = listFilesRecursively(owner, repo, branch, itemPath, basePath, result)
                    if (!ok) return false
                }
            }
        }
        return true
    }

    private fun downloadText(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            if (connection.responseCode == 200) {
                val reader = connection.inputStream.bufferedReader()
                val buffer = CharArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (total < maxDownloadChars) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    val toAppend = minOf(read, maxDownloadChars - total)
                    sb.append(buffer, 0, toAppend)
                    total += toAppend
                }
                sb.toString()
            } else null
        } finally {
            connection.disconnect()
        }
    }
}
