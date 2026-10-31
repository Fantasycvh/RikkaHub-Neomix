package app.androidrik.rikkahub.ui.pages.extensions.skills

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.files.FileUtils
import app.androidrik.rikkahub.data.files.SkillFrontmatterParser
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.files.SkillInstaller
import app.androidrik.rikkahub.data.files.SkillMetadata
import kotlin.collections.iterator

/** 单个 zip 条目最大未压缩大小（技能文件通常 <1MB，32MB 覆盖一切合法场景） */
private const val MAX_ZIP_ENTRY_SIZE = 32L * 1024 * 1024
/** zip 解压总量上限，防 zip bomb */
private const val MAX_ZIP_TOTAL_SIZE = 256L * 1024 * 1024
/** GitHub 下载响应最大字符数（约 4MB） */

class SkillsVM(
    private val skillManager: SkillManager,
    private val context: Application,
) : ViewModel() {
    private val skillInstaller by lazy { SkillInstaller(skillManager) }
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun getSkillsDir() = skillManager.getSkillsDir()

    fun importSkillFromFile(context: Context, uri: Uri, onResult: (Boolean, String) -> Unit) {
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run {
                        withContext(Dispatchers.Main) { onResult(false, appContext.getString(R.string.skills_page_cannot_read_file)) }
                        return@launch
                    }

                val importedNames = if (isZipFile(fileName, bytes)) {
                    importSkillsFromZip(bytes)
                } else {
                    importSkillMarkdown(bytes)
                }

                _skills.value = skillManager.listSkills()
                withContext(Dispatchers.Main) {
                    onResult(true, importedNames.joinToString())
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: appContext.getString(R.string.backup_page_unknown_error)) }
            }
        }
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            skillInstaller.installFromGitHub(repoUrl)
                .onSuccess { skill ->
                    _skills.value = skillManager.listSkills()
                    withContext(Dispatchers.Main) { onResult(true, skill.name) }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) { onResult(false, e.message ?: context.getString(R.string.backup_page_unknown_error)) }
                }
        }
    }

    private fun importSkillMarkdown(bytes: ByteArray): List<String> {
        val content = bytes.toString(Charsets.UTF_8)
        val frontmatter = SkillFrontmatterParser.parse(content)
        val name = frontmatter["name"]?.trim()
        if (name.isNullOrBlank()) {
            error(context.getString(R.string.skills_page_skill_md_missing_name))
        }
        if (frontmatter["description"].isNullOrBlank()) {
            error(context.getString(R.string.skills_page_skill_md_missing_description))
        }
        val saved = skillManager.saveSkill(name, content) ?: error(context.getString(R.string.skills_page_save_failed))
        return listOf(saved.name)
    }

    private fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        // [FIX] zip bomb 防护（移植自 rikkahub-turbo f2a84d9）：技能 zip 来自用户导入，
        // 恶意/损坏 zip 可声明极小压缩体积解压出 GB 级内容（readBytes 全量载入内存）→ OOM。
        // 限制单文件与总解压量。
        var totalUncompressedBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        if (entry.size > MAX_ZIP_ENTRY_SIZE) {
                            continue
                        }
                        if (totalUncompressedBytes + entry.size > MAX_ZIP_TOTAL_SIZE) {
                            error(context.getString(R.string.skills_page_zip_too_large))
                        }
                        val path = normalizeZipEntryPath(entry.name)
                        if (path != null) {
                            val content = zipInput.readBytes()
                            totalUncompressedBytes += content.size
                            if (totalUncompressedBytes > MAX_ZIP_TOTAL_SIZE) {
                                error(context.getString(R.string.skills_page_zip_too_large))
                            }
                            files[path] = content
                        }
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            error(context.getString(R.string.skills_page_zip_no_skill_md))
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val importedNames = mutableListOf<String>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.toString(Charsets.UTF_8)
                ?: error(context.getString(R.string.skills_page_read_failed_path, skillMdPath))
            val frontmatter = SkillFrontmatterParser.parse(skillContent)
            val name = frontmatter["name"]?.trim()
            if (name.isNullOrBlank()) {
                error(context.getString(R.string.skills_page_skill_md_missing_name_path, skillMdPath))
            }
            if (frontmatter["description"].isNullOrBlank()) {
                error(context.getString(R.string.skills_page_skill_md_missing_description_path, skillMdPath))
            }

            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) {
                    "SKILL.md"
                } else {
                    relativePath
                }
                skillFiles[targetPath] = content
            }

            val saved = skillManager.saveSkillFileBytesAtomically(name, skillFiles)
            if (!saved) {
                error(context.getString(R.string.skills_page_save_failed_name, name))
            }
            importedNames += name
        }
        return importedNames.distinct()
    }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }
}
