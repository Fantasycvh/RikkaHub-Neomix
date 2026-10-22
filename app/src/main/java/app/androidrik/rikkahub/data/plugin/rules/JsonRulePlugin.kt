package app.androidrik.rikkahub.data.plugin.rules

import android.util.Log
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.transformers.TransformerContext
import app.androidrik.rikkahub.data.plugin.PluginInputTransformer
import app.androidrik.rikkahub.data.plugin.PluginOutputTransformer

/**
 * 把一份 [JsonRuleDefinition] 物化成一个可注册插件。
 *
 * 为什么物化而不是另起一套规则注册表(不学 BiliPai 五套管理并存):
 * 物化后 JSON 规则插件与内置插件走**同一个** [app.androidrik.plugin.manager.PluginManager]——
 * 同一套启用开关、能力授权、插件中心展示与生命周期,没有第五套存储。
 *
 * 能力校验在宿主侧完成:只有插件启用且持有对应能力时,
 * ChatService 才会把它挂进输入/输出管线(见 `buildInputTransformerChain`)。
 */
class JsonRulePlugin(
    val definition: JsonRuleDefinition,
) : PluginInputTransformer, PluginOutputTransformer {

    override val id: String = definition.id
    override val displayName: String = definition.name
    override val description: String = definition.description
    override val version: String = definition.version
    override val author: String = definition.author
    override val capabilities: Set<app.androidrik.plugin.api.PluginCapability> =
        setOf(definition.requiredCapability)

    /** 规则插件默认启用:用户导入时已确认过能力。 */
    override val enabledByDefault: Boolean = true

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> =
        if (definition.target == RuleTarget.INPUT) {
            transformMessages(ctx, messages, role = MessageRole.USER)
        } else {
            messages
        }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> =
        if (definition.target == RuleTarget.OUTPUT) {
            transformMessages(ctx, messages, role = MessageRole.ASSISTANT)
        } else {
            messages
        }

    /**
     * 对指定角色的每条文本 part 依次应用全部规则。
     *
     * 上下文摊平成 `field -> value` 表:引擎按路径取值,新增字段只需在这里多传一个键。
     */
    private fun transformMessages(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        role: MessageRole,
    ): List<UIMessage> {
        val (result, matchedRules) = applyRulesToMessages(
            messages = messages,
            role = role,
            scope = definition.scope,
            rules = definition.rules,
            assistantName = ctx.assistant.name,
            assistantId = ctx.assistant.id.toString(),
            modelName = ctx.model.displayName,
        )
        // 诊断日志:真机验证"规则确实被求值并命中"(与内置插件同款可观测性)
        Log.d(
            TAG,
            "rule plugin ${definition.id}: messages=${messages.size} matched=$matchedRules",
        )
        return result
    }
}

/**
 * 规则施加的纯函数(不依赖 Android,可 JVM 直测)。
 *
 * @return 变换后的消息列表 与 命中的规则次数(诊断用)
 */
internal fun applyRulesToMessages(
    messages: List<UIMessage>,
    role: MessageRole,
    scope: RuleScope,
    rules: List<JsonRule>,
    assistantName: String,
    assistantId: String,
    modelName: String,
): Pair<List<UIMessage>, Int> {
    if (messages.none { it.role == role }) return messages to 0

    // scope=LAST 时只处理最新一条目标角色消息:避免 append 类动作在历史消息上反复叠加
    val targets: Set<Int> = when (scope) {
        RuleScope.LAST -> setOf(messages.indexOfLast { it.role == role })
        RuleScope.ALL -> messages.indices.filter { messages[it].role == role }.toSet()
    }

    val baseValues = mapOf(
        "role" to role.name.lowercase(),
        "messageCount" to messages.size.toString(),
        "assistant.name" to assistantName,
        "assistant.id" to assistantId,
        "model.name" to modelName,
    )

    var matchedRules = 0
    val result = messages.mapIndexed { index, message ->
        if (index !in targets || message.role != role) {
            message
        } else {
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Text || part.text.isEmpty()) {
                        part
                    } else {
                        var text = part.text
                        rules.forEach { rule ->
                            val values = baseValues + ("text" to text)
                            if (RuleEngine.matches(rule.condition, values)) {
                                matchedRules++
                                text = RuleEngine.apply(rule.action, text)
                            }
                        }
                        part.copy(text = text)
                    }
                },
            )
        }
    }
    return result to matchedRules
}

private const val TAG = "JsonRulePlugin"
