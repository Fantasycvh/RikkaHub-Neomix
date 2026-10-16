package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory

/**
 * 「整理建议 → 给人看的一句话」的文本函数。
 *
 * **为什么单独一个文件、而不是塞进 `MemoryConsolidationScheduler.kt`**：
 * 调度器有一条**架构守卫**（`MemoryConsolidationSchedulerTest`「the scheduler only knows memory ids
 * and the worker interface」）禁止它的源码里出现 `AssistantMemory` / `MemoryEntity` / `MemoryRepository`
 * 等存储符号——契约是「调度器只认 `memoryId` + worker 接口」。
 * 本函数要看**目标当前的内容**才能说清「将改掉什么」，天然需要 `AssistantMemory`
 * ⇒ 它属于文本层，不属于调度层。（这条正是被那道守卫当场抓出来的：
 * 第一版写进了调度器文件，测试立即变红。）
 */

/**
 * 二次确认页上「**这条建议会做什么**」的一行（纯函数，好单测）。
 *
 * 为什么单独把这些摆出来而不是复用 [MemoryConsolidationProposal.describe]：
 * 确认页的责任是让人**在不可逆动作之前**看清后果，所以：
 * - `edit` / `delete` 要显示**目标当前的内容**（[target]），否则用户只看到「改成什么」，
 *   看不到「将改掉什么」——删除尤其如此（删掉的内容在应用后就没处看了）；
 * - `create` 没有目标，只看新内容；
 * - 文案用 `now → after` 的形式，符合「改前 / 改后」的直觉。
 *
 * [target] 为 null（目标已被删掉）时明说「目标已不存在」——这一批应用时会被判 stale，
 * 提前告诉用户比让他按完再看到「0 条应用」好。
 */
fun proposeChangeSummary(
    proposal: MemoryConsolidationProposal,
    target: AssistantMemory?,
    maxContent: Int = 80,
): String {
    fun preview(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length > maxContent) flat.take(maxContent) + "…" else flat
    }

    val id = proposal.targetId.orEmpty()
    return when (proposal.action) {
        "create" -> "[new] ${preview(proposal.content)}"
        "edit" -> if (target == null) {
            "[edit] $id — target no longer exists"
        } else {
            "[edit] $id — “${preview(target.content)}” → “${preview(proposal.content)}”"
        }

        "delete" -> if (target == null) {
            "[delete] $id — target no longer exists"
        } else {
            "[delete] $id — “${preview(target.content)}”"
        }

        else -> "[${proposal.action}] ${preview(proposal.content)}"
    }
}

/**
 * 把「用户已拒绝的建议」渲染成给模型看的提示行（纯函数，好单测）。
 *
 * 输出刻意**只带动作与目标**、内容只给短摘要：这几行的作用是让模型**认得出自己上一轮提过什么**，
 * 而不是让它重新审阅那些内容（内容该由被拒记录本身保留）。
 */
fun refusedChangeLines(
    refused: List<MemoryConsolidationProposal>,
    maxContent: Int = 60,
): List<String> = refused.map { proposal ->
    val flat = proposal.content.replace('\n', ' ').trim()
    val preview = if (flat.length > maxContent) flat.take(maxContent) + "…" else flat
    val target = proposal.targetId?.let { " $it" }.orEmpty()
    if (preview.isBlank()) "${proposal.action}$target" else "${proposal.action}$target → $preview"
}
