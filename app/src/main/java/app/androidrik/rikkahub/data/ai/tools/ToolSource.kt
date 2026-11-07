package app.androidrik.rikkahub.data.ai.tools

/**
 * 工具段来源的**类型化单一真源**（P2a，设计稿 §16.4 拍板 3）。
 *
 * 此前段名只存在于 [ToolSegment.source] 字符串里，信封判断、折叠提升、schema 诊断各写各的
 * 字面量——同一份"来源清单"散落多处，随时可能漂移。统一后：
 *
 * - [segmentName] 是**对外契约**（子代理信封 `resolveSubagentEnvelope` 按它判断能力开关、
 *   折叠提升按名求交集、schema 诊断按段统计），序列化值与历史字符串**逐字符相同**；
 *   改动它会影响信封语义——这是 [ChatToolFactory.createToolSegments] 的既有约定。
 * - 固定来源用 data object（枚举式穷举）；参数化来源（MCP 服务器名、插件 id）用 data class。
 *   enum 常量无法携带运行时参数，故以 sealed 类型枚举落地——这是拍板 3「类型化枚举」的
 *   Kotlin 语义等价物，固定来源部分仍是穷举封闭的。
 */
internal sealed interface ToolSource {
    /** 对外契约段名；与历史字符串逐字符相同，**勿改**（含 `mcp:` / `plugin:` 前缀与连字符）。 */
    val segmentName: String

    data object Memory : ToolSource { override val segmentName get() = "memory" }

    data object Search : ToolSource { override val segmentName get() = "search" }

    data object Local : ToolSource { override val segmentName get() = "local" }

    data object Image : ToolSource { override val segmentName get() = "image" }

    data object TaskManage : ToolSource { override val segmentName get() = "task_manage" }

    data object Conversation : ToolSource { override val segmentName get() = "conversation" }

    data object Workspace : ToolSource { override val segmentName get() = "workspace" }

    data object SkillInstall : ToolSource { override val segmentName get() = "skill-install" }

    data object Skills : ToolSource { override val segmentName get() = "skills" }

    data object Knowledge : ToolSource { override val segmentName get() = "knowledge" }

    data object Subagent : ToolSource { override val segmentName get() = "subagent" }

    /** 折叠档元工具段（`get_tool_map` + `mcp_tool_call`）。 */
    data object McpDynamic : ToolSource { override val segmentName get() = "mcp-dynamic" }

    /** 按服务器成段的 MCP 原生档；净化名去重仍发生在装配点（段内 `usedNames`）。 */
    data class McpServer(val serverName: String) : ToolSource {
        override val segmentName get() = "mcp:$serverName"
    }

    /** 插件工具段；[pluginId] 为插件实例 id（`RikkaPlugin.id`）。 */
    data class Plugin(val pluginId: String) : ToolSource {
        override val segmentName get() = "plugin:$pluginId"
    }
}
