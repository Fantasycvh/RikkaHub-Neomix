package app.androidrik.rikkahub.data.model

/**
 * 会话自定义顺序的**位置值编码**（设计稿 `docs/design/design-conversation-manual-order.md` §二）。
 *
 * 为什么是「整数大间距」而不是浮点：定点写回、无精度零头、日志/备份里可读；
 * 代价只有「间距耗尽」，而它的触发时机完全可预测（同一间隙连续取中值约 10 次）。
 *
 * 档位（置顶/非置顶）分**两个独立编号空间**，档内 `null` 与非 `null` **不混用**：
 * 整档全 null = 自动模式（按 `update_at`，即历史行为，且**零写入**）；
 * 一旦需要（首次拖拽 / 档位变动需要）就整档物化为 [STEP] 步长序列。
 * 全部都是纯函数，便于把「顺序算术」的错误挡在单测里（DB/UI 层只做接线）。
 */
object ConversationOrdering {

    /** 位置值步长。 */
    const val STEP: Long = 1_000L

    /** 起始值：双向各留约 10^12 次操作的余量（实际永不触界）。 */
    const val ORIGIN: Long = 1_000_000L

    /**
     * 整档物化：按**给定顺序**生成 `ORIGIN, ORIGIN+STEP, …`。
     *
     * 顺序的入参通常就是「当前显示顺序」（首次拖拽时按现状物化 ⇒ 用户看到的列表不动，
     * 只是内部有了可插入的编号空间）。
     */
    fun materialize(count: Int): List<Long> = List(count) { ORIGIN + it * STEP }

    /** 档位最前（新会话 / 置顶进来的会话落在首位）：`min - STEP`；空档从 [ORIGIN] 起步。 */
    fun topSlot(existing: List<Long>): Long {
        val min = existing.minOrNull() ?: return ORIGIN
        require(min > Long.MIN_VALUE + STEP) { "位置值下溢，需要先归一化" }
        return min - STEP
    }

    /** 档位最后：`max + STEP`（仅用于尾部插入；当前 UI 不需要，留给将来）。 */
    fun bottomSlot(existing: List<Long>): Long {
        val max = existing.maxOrNull() ?: return ORIGIN
        require(max < Long.MAX_VALUE - STEP) { "位置值上溢，需要先归一化" }
        return max + STEP
    }

    /**
     * 插入到 [prev] 与 [next] 之间的中值。
     *
     * 返回 null = **间距耗尽**（`next - prev <= 1`，已无可插入的整数），
     * 调用方应整档归一化后重试（设计稿 §三.5：只在提交时归一化，绝不在拖拽过程中改数据）。
     */
    fun midpoint(prev: Long, next: Long): Long? {
        val gap = next - prev
        if (gap <= 1) return null
        return prev + gap / 2
    }

    /**
     * 插入到最前（拖到列表首位）：`first - STEP`；接近下溢时返回 null（需归一化）。
     *
     * **先比较、后相减**：若先算 `first - STEP` 再判断，溢出已经发生（回绕成大正数），
     * 判断永远为假——这是本函数最初写错的地方，由 `ConversationOrderingTest` 抓到。
     */
    fun slotBefore(first: Long): Long? =
        if (first < Long.MIN_VALUE + STEP) null else first - STEP
}

/** 拖拽落点的目标位置（由 UI 的 from/to 索引换算，见 [orderUpdateFor]）。 */
sealed interface OrderPlacement {
    /** 落在整档最前。 */
    data object Top : OrderPlacement

    /** 落在 [prev] 与 [next] 之间。 */
    data class Between(val prev: Long, val next: Long) : OrderPlacement

    /** 落在整档最后。 */
    data class Bottom(val last: Long) : OrderPlacement
}

/**
 * 把 [movedId] 按邻居锚点插回档内顺序（纯函数）。
 *
 * 锚点语义是**易错点**（真机实测把两处都写反过，表现为"拖动生效但总是差一位"）：
 * [afterId] 指「拖完之后应排在移动项**之后**」的那条 ⇒ 移动项要插到**它前面**；
 * [beforeId] 相反，插到它**后面**。两者都为 null（或都不在本档内）时插到末尾。
 */
fun desiredTierOrder(
    tierIds: List<String>,
    movedId: String,
    beforeId: String?,
    afterId: String?,
): List<String> {
    val desired = tierIds.filterNot { it == movedId }.toMutableList()
    val anchorIndex = when {
        afterId != null -> desired.indexOf(afterId).takeIf { it >= 0 }
        beforeId != null -> desired.indexOf(beforeId).takeIf { it >= 0 }?.plus(1)
        else -> null
    } ?: desired.size
    desired.add(anchorIndex.coerceIn(0, desired.size), movedId)
    return desired
}

/**
 * 由目标顺序 + 现有位置值推出落点（纯函数）。
 *
 * 「落在档位最末」必须是 [OrderPlacement.Bottom]：早期把 `next == null` 归到 [OrderPlacement.Top]，
 * 于是拖到列表底部的会话会拿到"整档最前"的位置值，松手后跳到首位。
 */
fun placementOf(
    desired: List<String>,
    movedId: String,
    existingValues: Map<String, Long>,
): OrderPlacement {
    val from = desired.indexOf(movedId)
    if (from <= 0) return OrderPlacement.Top
    val prev = desired.getOrNull(from - 1)?.let(existingValues::get)
    val next = desired.getOrNull(from + 1)?.let(existingValues::get)
    return when {
        // 上方邻居没有位置值（档位只被半物化）⇒ 退化为最前，下次拖拽顺带整档归一化
        prev == null -> OrderPlacement.Top
        next == null -> OrderPlacement.Bottom(prev)
        prev < next -> OrderPlacement.Between(prev, next)
        // 邻居值顺序异常（理论不该出现）⇒ 放到 prev 之后，至少不动别人的相对顺序
        else -> OrderPlacement.Bottom(prev)
    }
}

/** 一次拖拽要写库的结果：要么只写一行（[Single]），要么整档重排（[Renormalize]）。 */
sealed interface OrderWrite {
    /** 只更新一条会话的位置值（常规路径，最省）。 */
    data class Single(val conversationId: String, val sortOrder: Long) : OrderWrite

    /** 整档重排（首次物化 / 间距耗尽）：按 [orderedIds] 顺序写入 [values]。 */
    data class Renormalize(val orderedIds: List<String>, val values: List<Long>) : OrderWrite
}

/**
 * 由「档内现状 + 目标落点」算出这次拖拽的**写库方案**（纯函数，决策表的可测落点）。
 *
 * @param existingValues 该档**已物化**的位置值（升序；未物化时调用方传空表并走 [materialize] 路径）
 */
fun planOrderWrite(
    movedId: String,
    placement: OrderPlacement,
    existingValues: List<Long>,
    orderedIds: List<String>,
): OrderWrite {
    val ordered = existingValues.sorted()
    val slot: Long? = when (placement) {
        is OrderPlacement.Top -> ordered.firstOrNull()?.let(ConversationOrdering::slotBefore)
            ?: ConversationOrdering.ORIGIN
        is OrderPlacement.Between -> ConversationOrdering.midpoint(placement.prev, placement.next)
        is OrderPlacement.Bottom -> ConversationOrdering.bottomSlot(ordered)
    }
    // 间距耗尽（或极值触界）：整档按目标顺序重排，一次事务写回（行数 = 该档会话数）
    return if (slot == null) {
        OrderWrite.Renormalize(orderedIds, ConversationOrdering.materialize(orderedIds.size))
    } else {
        OrderWrite.Single(movedId, slot)
    }
}

/**
 * 会话**在档位之间移动**（置顶/取消置顶）时，位置值该怎么处理——设计稿 §一.6 定的是
 * 「保留原值」，但那只覆盖了「两边都物化」的情形；这里把四种组合一次定死。
 */
enum class TierMoveAction {
    /** 源有值 + 目标已物化：保留原值（能回到原来的相对位置，即设计稿要的"最小意外"）。 */
    KEEP_VALUE,

    /** 源有值 + 目标自动：先物化目标档再保留原值（之后它落在哪由该值决定，通常是最前）。 */
    MATERIALIZE_THEN_KEEP,

    /** 源无值 + 目标已物化：给它一个档位最前的位置（否则 null 会沉到已排序项之后）。 */
    MATERIALIZE_AND_PLACE_TOP,

    /** 两边都自动：什么都不做（零写入——没进过自定义顺序的用户完全不被打扰）。 */
    NOTHING,
}

/**
 * 档位移动的位置值决策（纯函数）。见 [TierMoveAction] 各枚举的说明。
 *
 * @param hasValue 被移动会话当前是否有位置值
 * @param destinationMaterialized 目标档是否已物化（该档存在非 null 位置值）
 */
fun decideTierMove(hasValue: Boolean, destinationMaterialized: Boolean): TierMoveAction =
    when {
        hasValue && destinationMaterialized -> TierMoveAction.KEEP_VALUE
        hasValue -> TierMoveAction.MATERIALIZE_THEN_KEEP
        destinationMaterialized -> TierMoveAction.MATERIALIZE_AND_PLACE_TOP
        else -> TierMoveAction.NOTHING
    }
