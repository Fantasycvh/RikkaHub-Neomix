package app.androidrik.common.settings

import kotlin.uuid.Uuid

/**
 * 「列表 + 启停」家族（A 类）的统一**形态**。
 *
 * 适用家族：聊天提供商、搜索服务、ASR、TTS、助手——各家族保留自己的**语义**
 * （助手停用=归档、搜索停用=不注册工具、TTS=系统兜底……），本接口只统一
 * 「有稳定 id、可启停」这件事，让选中解析与内置回填可以共享一处契约。
 *
 * **不接**的家族：B 类「条目开关」（Model/预设/模式注入/世界书/子代理预设/MCP 工具）
 * ——它们的启停逻辑就是 `copy(enabled=…)` + 一个 Switch，无可共享内容，别抽象。
 */
interface Enableable {
    val id: Uuid
    val enabled: Boolean
}

/**
 * 「选中解析」契约：**选中项被停用时不静默回退**。
 *
 * - 选中项存在且启用 → 返回它
 * - 选中项存在但停用 → 返回 null（用户既然关了它，就不该悄悄改用另一个可能计费的
 *   提供商；由消费路径如实提示「不可用/请配置」）
 * - 选中项不存在（被删/未选）→ 回退到第一个启用的
 * - 一个启用的都没有 → 返回 null
 *
 * 注意：助手的选中解析（`getCurrentAssistant`）**不走这里**——助手停用=归档
 * （既有会话照常可用），语义是「还能不能用」之外的另一回事。
 */
fun <T : Enableable> resolveSelected(items: List<T>, selectedId: Uuid?): T? {
    val selected = selectedId?.let { id -> items.find { it.id == id } }
    if (selected != null) return selected.takeIf { it.enabled }
    return items.firstOrNull { it.enabled }
}

/**
 * 内置项回填（墓碑感知）：缺了的内置项补回来，**但跳过墓碑**——用户显式删除过的
 * 内置项不再回填，否则读路径每次把删掉的加回来，等于删不掉。
 *
 * 这个共享实现让「`ifEmpty { DEFAULT }` 整体复活」陷阱（chat 提供商 2026-09-09、
 * TTS 2026-10-01 两次踩过：把内置项全删光后列表为空，ifEmpty 又把它们整体加回来）
 * 在构造上不可能再犯——这里**没有** ifEmpty 兜底，列表该空就空。
 *
 * [clone] 用于把内置种子复制成新实例（各家族的 `copyProvider()` / `copy()`），
 * 避免读路径共享同一个种子实例。
 */
fun <T : Enableable> refillBuiltIns(
    existing: List<T>,
    defaults: List<T>,
    removedIds: Set<Uuid> = emptySet(),
    clone: (T) -> T = { it },
): List<T> =
    existing + defaults.filter { default ->
        existing.none { it.id == default.id } && default.id !in removedIds
    }.map(clone)
