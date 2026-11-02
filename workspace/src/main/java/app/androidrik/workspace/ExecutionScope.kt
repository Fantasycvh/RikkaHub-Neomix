package app.androidrik.workspace

/**
 * 统一归属令牌（P3，design-agent-task-runtime §5.2）。
 *
 * 后台命令、agent run、定时 run 共用同一种「谁发起的」身份，wire 格式与账本一致：
 * - `turn:<token>`：对话轮；本轮取消时收掉整轮起的进程；
 * - `run:<runId>`：手动/定时/子代理 run；run 取消时收掉它起的 shell；
 * - `sched:<runId>`：定时注入轮（历史格式，等价于一个 turn scope）。
 *
 * ## 为什么有 `parent` 而不是只用一个字符串
 *
 * 级联取消需要回答「B 是不是 A 的子 scope」。把父链放在**内存**里，而不是拼进 wire：
 * wire 已经落库（`agent_run.scope_token`）并被任务树按前缀匹配，改格式会让历史数据失配；
 * 而级联只发生在同一进程存活期内，父链不需要持久化。于是 `parse()` 出来的旧令牌没有父，
 * 只有显式 [child] 构造的 scope 才有级联关系。
 *
 * 空 wire 直接拒绝：宁可让调用方立刻失败，也不能产生一个「匹配一切空 owner」的 scope。
 */
data class ExecutionScope(
    val wire: String,
    val parent: ExecutionScope? = null,
) {
    init {
        require(wire.isNotBlank()) { "ExecutionScope.wire must not be blank" }
    }

    /** [this] 是否为 [other] 的祖先（含自身）。取消 scope 时按这个判断级联。 */
    fun covers(other: ExecutionScope?): Boolean {
        var cursor = other
        while (cursor != null) {
            if (cursor.wire == wire) return true
            cursor = cursor.parent
        }
        return false
    }

    /** 在 [this] 下开一个子 scope（级联取消时随父一起被收掉）。 */
    fun child(wire: String): ExecutionScope = ExecutionScope(wire, this)

    companion object {
        /** 把历史/外部传入的 wire 字符串还原成 scope；空白返回 null（= 无归属）。 */
        fun parse(raw: String?): ExecutionScope? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::ExecutionScope)

        fun turn(token: String, parent: ExecutionScope? = null): ExecutionScope =
            ExecutionScope("turn:$token", parent)

        fun run(runId: String, parent: ExecutionScope? = null): ExecutionScope =
            ExecutionScope("run:$runId", parent)

        fun scheduled(runId: String, parent: ExecutionScope? = null): ExecutionScope =
            ExecutionScope("sched:$runId", parent)
    }
}
