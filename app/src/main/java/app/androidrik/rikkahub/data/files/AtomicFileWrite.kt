package app.androidrik.rikkahub.data.files

import android.util.Log
import java.io.File

private const val TAG = "AtomicFileWrite"

/**
 * 临时文件 + rename 的原子写入；**任何失败都返回 false 而非抛出**。
 *
 * 出处：上游 `9f02586d` 修的是「技能保存失败导致崩溃」——`target.writeText(content)` 在
 * IO 失败（mkdirs 不成、磁盘写满、路径异常）时抛异常，而调用方 `SkillDetailVM.saveFile`
 * 在 IO 协程里没有 try/catch ⇒ 异常冒到 viewModelScope，**保存失败 = 应用崩溃**；
 * 且直接写目标会留下半截文件（原内容已毁）。
 *
 * **与上游的一处差异**：这里检查 `renameTo` 的返回值（上游忽略）。本函数存在的意义就是
 * 「把保存失败如实报出去」，忽略返回值会让失败静默变成成功。
 *
 * 顶层函数而非 `SkillManager` 成员：便于 JVM 单测用临时文件直接驱动（管理器需要 Context）。
 */
internal fun writeFileAtomically(target: File, content: String): Boolean {
    val parent = target.parentFile ?: return false
    val tempFile = parent.resolve(".${target.name}.tmp")
    return try {
        if (!parent.exists() && !parent.mkdirs()) return false
        tempFile.writeText(content)
        tempFile.renameTo(target)
    } catch (e: Exception) {
        Log.w(TAG, "failed to save ${target.absolutePath}", e)
        false
    } finally {
        if (tempFile.exists()) tempFile.delete()
    }
}
