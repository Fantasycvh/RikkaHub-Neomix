package app.androidrik.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     * （吸收上游 `3b346748`；解压失败仅记日志——内置技能缺失不该让应用不可用。）
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    /**
     * 列出所有可用技能：用户技能 + 内置技能，同名时用户技能覆盖内置技能
     * （覆盖项仍可编辑/删除——删掉用户副本后内置技能重新生效）。
     */
    fun listSkills(): List<SkillMetadata> = mergeWithBuiltinSkills(
        local = listSkillsIn(getSkillsDir(), builtin = false),
        builtin = listBuiltinSkills(),
    )

    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> {
        return root.listFiles()
            // 跳过隐藏目录（原子写入的 staging/backup 残留形如 .name.staging.N.tmp）
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir, builtin)
            }
            ?: emptyList()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // 目录不存在时 deleteRecursively 也返回 true，需提前拦截，避免误清理（内置技能的）启用状态
        if (!skillDir.exists()) return@withContext false
        val deleted = skillDir.deleteRecursively()
        // 删除的是覆盖内置技能的同名用户技能时，内置技能会重新生效，保留启用状态
        if (deleted && listBuiltinSkills().none { it.name == name }) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    /**
     * 保存单个技能文件（技能详情页的编辑器走这里）。
     *
     * 上游 `9f02586d`：原实现直接 `target.writeText(content)`，**IO 失败（mkdtemp 不成、
     * 磁盘写满、路径异常）会抛异常**——调用方 `SkillDetailVM.saveFile` 在 IO 协程里没有
     * try/catch，异常直接冒到 viewModelScope ⇒ **保存失败 = 应用崩溃**；而且写到一半失败
     * 会留下半截文件（原内容已毁）。
     *
     * 改成「同目录临时文件 + rename」：写失败时原文件完好，异常转成返回 false 由 UI 提示。
     * **与上游的一处差异**：这里检查 `renameTo` 的返回值（上游忽略）——该调用本就是为了
     * 把「保存失败」如实报出去，忽略返回值会让失败静默成功。
     */
    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return writeFileAtomically(target, content)
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                // #1901：分类是 SKILL.md 里的手写可选字段；纯标量故 `.get` 即可读到
                // （issue 正文给的块式 `tags:` 列表用 `.get` 会读到 null，故标签不在本次范围）
                category = frontmatter["category"]?.takeIf { it.isNotBlank() },
                skillDir = skillDir,
                builtin = builtin,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }

    /**
     * 读取技能目录下**全部文件**的原始字节（相对路径 → 内容），供「覆盖前保留既有内容」使用。
     *
     * 为什么需要：`saveSkillFilesAtomically` 是**整目录替换**——AI 用 `save_skill` 覆盖同名技能时，
     * 若只提交 `SKILL.md`，用户原有的附属文件（示例、脚本、模板）会被无声删掉。
     * 技能目录很小（通常 <1MB），全量读回内存的开销可忽略。
     *
     * @return 技能不存在时返回 null（与「存在但目录为空」区分）
     */
    fun readSkillFilesBytes(skillName: String): Map<String, ByteArray>? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        if (!skillDir.isDirectory) return null
        val root = skillDir.toPath()
        val files = LinkedHashMap<String, ByteArray>()
        skillDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relative = root.relativize(file.toPath()).toString().replace('\\', '/')
                files[relative] = file.readBytes()
            }
        return files
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    /** #1901 单分类：SKILL.md frontmatter 的可选手写字段；只在管理页做组织维度，不进 systemPrompt。 */
    val category: String? = null,
    val skillDir: File,
    /** 内置技能：随 APK 发布解压到 filesDir/builtin_skills，只读、不可删除/编辑 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
