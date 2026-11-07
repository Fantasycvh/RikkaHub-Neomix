package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.TOOL_NO_APPROVAL
import app.androidrik.ai.core.Tool

/**
 * 来源级审批策略（P2b，设计稿 §16.3 拍板 2：**默认值等价现状，无行为差异**）。
 */
internal enum class ApprovalPolicy(val label: String) {
    /** 该来源的工具不配审批闭包（引用 [TOOL_NO_APPROVAL]），恒不拦。 */
    NONE("none"),

    /** 由助手开关/入参分档决定（记忆按 action、生图按开关、日历/ask_user 恒拦等）。 */
    DYNAMIC("dynamic"),

    /** MCP 提供方工具自带审批闭包，原样透传。 */
    PROVIDER("provider"),

    /** 折叠桥跟随被调底层工具的审批（折叠不得成为审批绕行）。 */
    FOLLOWS_TARGET("follows-target"),

    /** 插件自定审批闭包（受信任；宿主执行边界与超时上限不因此放宽）。 */
    PLUGIN_DEFINED("plugin-defined"),
}

/** 一个来源的审批基线：策略 + 依据（进诊断与设计文档的「来源 → 审批基线」表）。 */
internal data class ApprovalBaseline(val policy: ApprovalPolicy, val rationale: String)

/**
 * 「来源 → 审批基线」表（P2b，拍板 2）。
 *
 * 单一真源：exhaustive when 覆盖全部 [ToolSource]——新增来源不写基线就编译不过。
 * 本表只**描述**既有行为，不改任何判定路径：行为判定仍在各工具的 `needsApproval` 闭包，
 * 执行点在 `GenerationHandler.markPendingApprovals`（每次调用前逐次求值 + 恒开审批诊断行），
 * 外层超时/门控/预算宿主强制不变。诊断出口按它对照实测闭包：基线为 [ApprovalPolicy.NONE]
 * 的段却测得动态审批闭包 ⇒ 视为异常信号（见 [approvalDiagnostic] 的 `!` 标记）。
 */
internal fun approvalBaseline(source: ToolSource): ApprovalBaseline = when (source) {
    ToolSource.Memory -> ApprovalBaseline(
        ApprovalPolicy.DYNAMIC,
        "按 action 分档 memoryToolNeedsApproval：create 独立开关；edit/delete/complete/drop 跟随 modify 开关；read/search 恒不拦",
    )
    ToolSource.Search -> ApprovalBaseline(ApprovalPolicy.NONE, "搜索只读，无可否决副作用")
    ToolSource.Local -> ApprovalBaseline(
        ApprovalPolicy.DYNAMIC,
        "calendar/ask_user 恒拦，其余本地工具（时间/剪贴板/TTS 等）不拦",
    )
    ToolSource.Image -> ApprovalBaseline(ApprovalPolicy.DYNAMIC, "跟随 assistant.imageGenApproval")
    ToolSource.TaskManage -> ApprovalBaseline(ApprovalPolicy.DYNAMIC, "按动作分档 taskManageNeedsApproval")
    ToolSource.Conversation -> ApprovalBaseline(ApprovalPolicy.NONE, "会话检索/读取只读")
    ToolSource.Workspace -> ApprovalBaseline(
        ApprovalPolicy.DYNAMIC,
        "按工具名分档；workspace_task_status 只读不拦，workspace_shell 恒拦",
    )
    ToolSource.SkillInstall -> ApprovalBaseline(
        ApprovalPolicy.NONE,
        "现状不拦（安装有 SkillInstaller 自身校验）；如需默认拦截属行为变更，走独立授权",
    )
    ToolSource.Skills -> ApprovalBaseline(ApprovalPolicy.NONE, "技能内容读取只读")
    ToolSource.Knowledge -> ApprovalBaseline(ApprovalPolicy.NONE, "知识库检索只读")
    ToolSource.Subagent -> ApprovalBaseline(
        ApprovalPolicy.DYNAMIC,
        "按预设 requireApproval 分档；dispatch 外层超时自管（runner 看门狗）",
    )
    ToolSource.McpDynamic -> ApprovalBaseline(
        ApprovalPolicy.FOLLOWS_TARGET,
        "mcp_tool_call 跟随被调工具审批（折叠不得成为审批绕行）",
    )
    is ToolSource.McpServer -> ApprovalBaseline(ApprovalPolicy.PROVIDER, "透传 MCP server 的 tool.needsApproval")
    is ToolSource.Plugin -> ApprovalBaseline(
        ApprovalPolicy.PLUGIN_DEFINED,
        "受信任插件自定闭包；宿主执行边界/超时不放宽，外部 JS 另有执行时宿主审批叠加",
    )
}

/**
 * 单段审批诊断（P2b）：基线策略 + 实测动态闭包计数。
 *
 * - 基线 [ApprovalPolicy.NONE] 且实测无闭包 → `none`
 * - 基线 [ApprovalPolicy.NONE] 但实测有闭包 → `none(Ndyn!)`：**与基线矛盾的异常信号**，
 *   提示有人给「不该有审批」的来源配了闭包（或引用了字面量 `{ false }` 而非规范实例）
 * - 其余策略 → `<policy>(N/Mdyn)`，N 按**引用**识别默认闭包
 *   （[TOOL_NO_APPROVAL]；逻辑等价字面量宁可多报不漏报）
 */
internal fun approvalDiagnostic(baseline: ApprovalBaseline, tools: List<Tool>): String {
    val dynamic = tools.count { it.needsApproval !== TOOL_NO_APPROVAL }
    return when {
        baseline.policy == ApprovalPolicy.NONE && dynamic == 0 -> "none"
        baseline.policy == ApprovalPolicy.NONE -> "none(${dynamic}dyn!)"
        else -> "${baseline.policy.label}($dynamic/${tools.size}dyn)"
    }
}
