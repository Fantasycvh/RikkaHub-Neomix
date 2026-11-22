package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.model.Folder
import kotlin.uuid.Uuid

/** 会话迁移到指定助手的执行结果（给 toast 用：moved/skip 计数）。 */
data class AssistantMoveResult(
    val moved: Int,
    val skipped: Int,
)

/**
 * 「文件夹跟随」解析器（设计稿 `design-conversation-batch-assistant-migration.md` D1）。
 *
 * 文件夹隶属助手（FolderEntity.assistant_id），folderId 不能跨助手字面保留——否则会话
 * 既不在新助手的未归档列表（`folder_id = ''` 过滤）也不在任何可见文件夹里，直接从抽屉消失。
 * 解析规则：
 * - 源未归档 → 目标未归档；
 * - 源文件夹在目标助手下有**精确同名**文件夹 → 归并（指向已存在的目标文件夹）；
 * - 没有同名 → 在目标助手下克隆一个同名文件夹（sortIndex 追加末尾，原文件夹不动）；
 * - 源文件夹已不存在（迁移中途被删）→ 按未归档落，不阻塞整批。
 *
 * 同一源 folderId 只解析一次（缓存）；多个源文件夹同名时归并到**同一个**目标文件夹
 * （[createdByName] 保证刚克隆出来的名字能被后续同名源命中）。
 *
 * 纯逻辑抽出来是为了单测：决策不依赖 DB，依赖由三个挂起回调注入。
 */
internal class FolderFollowResolver(
    private val loadSourceFolder: suspend (Uuid) -> Folder?,
    private val findTargetByName: suspend (String) -> Uuid?,
    private val createTargetFolder: suspend (String) -> Uuid,
) {
    private val resolvedBySource = mutableMapOf<Uuid, Uuid?>()
    private val createdByName = mutableMapOf<String, Uuid>()

    /** 返回目标 folderId；null = 未归档。调用方串行调用（无并发）。 */
    suspend fun resolve(sourceFolderId: Uuid?): Uuid? {
        if (sourceFolderId == null) return null
        if (resolvedBySource.containsKey(sourceFolderId)) return resolvedBySource[sourceFolderId]
        val target = when (val source = loadSourceFolder(sourceFolderId)) {
            null -> null
            else -> createdByName[source.name]
                ?: findTargetByName(source.name)?.also { createdByName[source.name] = it }
                ?: createTargetFolder(source.name).also { createdByName[source.name] = it }
        }
        resolvedBySource[sourceFolderId] = target
        return target
    }
}
