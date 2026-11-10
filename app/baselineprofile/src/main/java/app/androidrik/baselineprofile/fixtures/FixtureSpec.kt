package app.androidrik.baselineprofile.fixtures

/**
 * Fixture 契约：校验用的文本标记 + 造数据用的确定性强内容。
 *
 * 为什么用「无障碍文本标记」而不是数据库标记：本模块是 `com.android.test`，编译期拿不到 App 的
 * Room/仓库类；运行期被测变体非 debuggable，也没有 `run-as` 权限去写 App 私有目录。所以只能
 * 通过 UI 写入、通过文本校验——这是本仓「不改 App 代码」约束下的上限，限制与后续方案见 README。
 *
 * 文本必须 **确定性**（无随机、无时间戳）：同一份 fixture 跑两次，渲染的布局与字形都一致，
 * 这样两版实现之间的帧时间才可比。
 */
internal object FixtureSpec {

    /** 造出来的对话里每条消息都以它开头；校验只需要 `textContains(MARKER)`。 */
    const val MARKER = "perf-fixture"

    /** 发送 journey 用的短消息标记（与 fixture 标记区分，避免把 fixture 当成刚发的那条）。 */
    const val SEND_MARKER = "perf-send"

    /** 默认造几条消息：一条不足以验证列表虚拟化，太多则造数据慢（每发一条都要等一次失败的请求）。 */
    const val DEFAULT_SEED_MESSAGES = 4

    /**
     * 第 [index] 条 fixture 消息：覆盖 Markdown 重路径（标题/列表/表格/代码块/行内与块级公式/链接），
     * 单条约 1–1.5 KB，4 条合计约 5 KB 文本 + 大量排版节点，足够把列表与文字排版打满。
     */
    fun seedText(index: Int): String = buildString {
        append(MARKER).append(" 第 ").append(index + 1).append(" 段：渲染管线压力样本\n\n")
        repeat(4) { round ->
            append("## 小节 ").append(round + 1).append("\n\n")
            append("段落：行内 `code` 与 **粗体**、*斜体*、[链接](https://example.com) 混排，")
            append("再加一句长文本用来触发换行与断词：The quick brown fox jumps over the lazy dog. ")
            append("中文长句也不该出现整段跳字或行高异常。\n\n")
            append("- 列表项 A，带 `inline` 代码\n- 列表项 B\n- 列表项 C\n\n")
            append("| 列 1 | 列 2 |\n| --- | --- |\n| a | b |\n| c | d |\n\n")
            append("```kotlin\nfun sample").append(round).append("(x: Int): Int = x * ").append(round + 2).append("\n```\n\n")
            append("行内公式 \$E = mc^2\$，块级公式：\n\n\$\$\nx^2 + y^2 = z^2\n\$\$\n\n")
        }
    }

    /** 发送 journey 的正文：短文本，只关心输入法 → 发送按钮 → 气泡的管线开销。 */
    fun sendText(index: Int): String = "$SEND_MARKER 第 $index 条：输入与发送路径冒烟文本"
}
