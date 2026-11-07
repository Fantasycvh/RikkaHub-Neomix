package app.androidrik.rikkahub.data.plugin.revision

import app.androidrik.ai.core.Tool
import app.androidrik.rikkahub.data.ai.tools.ToolSource
import app.androidrik.rikkahub.data.ai.tools.acceptPluginToolSegments
import app.androidrik.rikkahub.data.ai.tools.approvalBaseline
import app.androidrik.rikkahub.data.ai.tools.approvalDiagnostic
import app.androidrik.rikkahub.data.ai.tools.timeoutSummary
import app.androidrik.rikkahub.data.plugin.js.JsPluginDefinition
import app.androidrik.rikkahub.data.plugin.js.pluginToolName

/**
 * JS 插件修订的**激活前预检**（R1，设计稿 §四 1/2/3/5；第 4 项钩子 smoke 留待 R2+）。
 *
 * 预检全绿才允许激活（拍板 ④：预检拦截）；任何一项失败都给出逐项报告，且**不动**
 * 修订历史双指针、不写生效定义——last-good 继续服务。
 *
 * 注意：这是**开发体验闸门**，不是安全边界——外部 JS 的安全模型仍是宿主调用点校验
 * （能力授权/网络白名单/超时），与本机制叠加而非被替代。
 */
class PluginPrecheck(
    private val syntaxChecker: JsSyntaxChecker,
    /** 当前装配面上已存在的工具名（跨段重名 dry-run 的对照集）；空集 = 只查段内唯一。 */
    private val existingToolNames: () -> Set<String> = { emptySet() },
) {
    suspend fun checkJs(definition: JsPluginDefinition): PrecheckReport {
        val items = mutableListOf<PrecheckItemResult>()

        // 1. manifest 校验（既有 validate：id/名称/schema/网络域/超时/段内工具名唯一）
        val manifestErrors = definition.manifest.validate()
        items += PrecheckItemResult(
            name = "manifest",
            passed = manifestErrors.isEmpty(),
            detail = manifestErrors.joinToString("; ").ifEmpty { "ok" },
        )

        // 2. JS 语法检查（compile-no-execute；不执行任何插件代码）
        val syntaxError = syntaxChecker.compile(definition.source)
        items += PrecheckItemResult(
            name = "syntax",
            passed = syntaxError == null,
            detail = syntaxError ?: "ok",
        )

        // 3. 工具闸门 dry-run（P2a 纯函数）：JS 路径工具名经 pluginToolName 命名空间化，
        //    跨源重名结构性不可能；此处实际防的是段内重复（manifest.validate 已查声明名，
        //    这里用最终模型可见名再查一遍）与未来形态的跨段重名。
        if (definition.manifest.tools.isNotEmpty()) {
            val segment = ToolSegmentForPrecheck(definition)
            val (_, rejected) = acceptPluginToolSegments(
                existingSegments = emptyList(),
                pluginSegments = listOf(segment),
            )
            val conflict = rejected.singleOrNull()?.conflictingNames
            items += PrecheckItemResult(
                name = "tool-gate",
                passed = conflict == null,
                detail = conflict?.joinToString(", ") { "工具重名: $it" } ?: "ok",
            )
        }

        // 5. 画像报告（P2b 口径）：修订后工具段的审批/超时画像，写进报告供开发者对照基线
        val profile = buildString {
            if (definition.manifest.tools.isNotEmpty()) {
                val tools = definition.manifest.tools.map {
                    Tool(
                        name = pluginToolName(definition.id, it.name),
                        description = it.description,
                        parameters = { null },
                        execute = { emptyList() },
                    )
                }
                val source = ToolSource.Plugin(definition.id)
                append("plugin:${definition.id} ${tools.size}tools")
                append(" appr=${approvalDiagnostic(approvalBaseline(source), tools)}")
                append(" to=${timeoutSummary(tools)}")
            }
        }

        return PrecheckReport(passed = items.all { it.passed }, items = items, profile = profile)
    }
}

/** 预检内部的段构造（仅借 [acceptPluginToolSegments] 的名字判定，不产生可执行工具）。 */
private fun ToolSegmentForPrecheck(definition: JsPluginDefinition) =
    app.androidrik.rikkahub.data.ai.tools.ToolSegment(
        source = ToolSource.Plugin(definition.id),
        tools = definition.manifest.tools.map {
            Tool(
                name = pluginToolName(definition.id, it.name),
                description = it.description,
                parameters = { null },
                execute = { emptyList() },
            )
        },
    )

/** JS 源码语法检查器；返回 null = 可编译，否则为错误文本。实现不得执行源码。 */
fun interface JsSyntaxChecker {
    suspend fun compile(source: String): String?
}

/** 预检拒绝异常：[report] 为完整报告，message 为面向用户的紧凑失败文本。 */
class PrecheckRejectedException(val report: PrecheckReport) :
    IllegalStateException(report.failureText().ifEmpty { "precheck failed" })
