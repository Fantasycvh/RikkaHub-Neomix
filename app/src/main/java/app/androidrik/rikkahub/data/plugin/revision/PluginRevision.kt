package app.androidrik.rikkahub.data.plugin.revision

import kotlinx.serialization.Serializable

/**
 * 插件开发者修订通道的数据模型（R1，设计稿
 * [design-plugin-dev-revision-channel.md](../../../../../../docs/design/drafts/design-plugin-dev-revision-channel.md) §三）。
 *
 * 拍板边界：修订对象=仅插件资产；来源=仅本地（IMPORT/EDITOR/ADB）；生效=代次切换；
 * 失败=预检拦截（不过不占 revisionNo、不动双指针）。
 */
@Serializable
enum class RevisionOrigin {
    /** 应用内编辑器（R2）。 */
    EDITOR,

    /** 既有导入流程（URL/文本导入）。 */
    IMPORT,

    /** ADB inbox（R3）。 */
    ADB,
}

/** 单项预检结果；[detail] 面向开发者，失败时必须可定位（错误文本原样透传）。 */
@Serializable
data class PrecheckItemResult(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** 一次预检的完整报告；[profile] 为修订后各段审批/超时画像（P2b 诊断口径）。 */
@Serializable
data class PrecheckReport(
    val passed: Boolean,
    val items: List<PrecheckItemResult>,
    val profile: String = "",
) {
    /** 面向用户可见报错的紧凑文本（Toast/对话框直接展示）。 */
    fun failureText(): String =
        items.filterNot { it.passed }.joinToString("; ") { "${it.name}: ${it.detail}" }
}

/**
 * 一个插件的一次修订。[source] 为 JS 全文（manifest 块注释内嵌），经 [kind] 区分载体
 * （R1 仅 "js"；规则插件接入时扩展）。[precheck] 是激活时的报告快照（通过才有 revisionNo）。
 */
@Serializable
data class PluginRevision(
    val pluginId: String,
    val revisionNo: Long,
    val kind: String,
    val source: String,
    val origin: RevisionOrigin,
    val createdAt: Long,
    val precheck: PrecheckReport,
)

/**
 * 插件的修订历史与双指针。active = 当前生效（与 PluginManager 激活实例对应）；
 * lastGood = 上一个成功服务的修订（回退目标）。
 */
@Serializable
data class PluginRevisionState(
    val pluginId: String,
    val activeRevisionNo: Long? = null,
    val lastGoodRevisionNo: Long? = null,
    val revisions: List<PluginRevision> = emptyList(),
)

/** 修订历史上限（滚动丢弃最旧；active/lastGood 引用的条目不丢）。 */
const val MAX_REVISIONS_PER_PLUGIN = 10

/**
 * 把一次预检通过的新修订记入历史（纯函数，单测锚定）：
 * revisionNo = 历史最大值 + 1；激活成功 ⇒ lastGood=旧 active、active=新；
 * 超上限从**最旧端**滚动丢弃，但 active/lastGood 引用的条目绝不丢——极端情况下
 * （双指针都钉在很老的修订上）容量可能短暂超限至多 2 条，这是有意为之的保守取舍。
 */
fun recordActivatedRevision(
    state: PluginRevisionState,
    revision: PluginRevision,
): PluginRevisionState {
    require(revision.revisionNo > (state.revisions.maxOfOrNull { it.revisionNo } ?: 0L)) {
        "revisionNo must be monotonically increasing"
    }
    val base = state.copy(
        revisions = state.revisions + revision,
        lastGoodRevisionNo = state.activeRevisionNo ?: state.lastGoodRevisionNo,
        activeRevisionNo = revision.revisionNo,
    )
    if (base.revisions.size <= MAX_REVISIONS_PER_PLUGIN) return base
    val pinned = setOfNotNull(base.activeRevisionNo, base.lastGoodRevisionNo)
    var toDrop = base.revisions.size - MAX_REVISIONS_PER_PLUGIN
    val retained = base.revisions.toMutableList()
    val oldestFirst = retained.listIterator()
    while (toDrop > 0 && oldestFirst.hasNext()) {
        if (oldestFirst.next().revisionNo !in pinned) {
            oldestFirst.remove()
            toDrop--
        }
    }
    return base.copy(revisions = retained)
}

/** 修订历史文件名净化：pluginId 受严格正则约束（允许点），此处再兜底一层（防御性，非安全边界）。 */
fun revisionFileName(pluginId: String): String =
    pluginId.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json"
