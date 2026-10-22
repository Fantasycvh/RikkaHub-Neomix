package app.androidrik.rikkahub.data.plugin.js

import android.content.Context
import android.util.Log
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.rikkahub.data.ai.transformers.TransformerContext
import app.androidrik.rikkahub.data.plugin.PluginInputTransformer
import app.androidrik.rikkahub.data.plugin.PluginOutputTransformer
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TAG = "JsPlugin"

/**
 * 把一份 [JsPluginDefinition] 物化成插件,复用同一 `PluginManager`(与内置/规则插件一致)。
 *
 * 语义与规则插件对齐:
 * - 输入钩子 `/onInput` 处理**最新一条**用户消息,输出钩子 `/onOutput` 处理最新助手消息;
 * - 钩子抛错 / 超时 → 保留原文并打日志,**绝不**让一条坏脚本打断对话;
 * - 网络能力在调用点校验:撤销 `NETWORK` 后 `rikka.http()` 立即失败。
 */
class JsPlugin(
    val definition: JsPluginDefinition,
    private val context: Context,
    private val gateway: PluginHttpGateway,
    private val networkGranted: () -> Boolean,
) : PluginInputTransformer, PluginOutputTransformer {

    override val id: String = definition.id
    override val displayName: String = definition.manifest.name
    override val description: String = definition.manifest.description
    override val version: String = definition.manifest.version
    override val author: String = definition.manifest.author
    override val capabilities: Set<PluginCapability> = definition.manifest.capabilities
    override val enabledByDefault: Boolean = true

    @Volatile
    private var runtime: JsPluginRuntime? = null
    private val runtimeLock = Any()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformLatest(ctx, messages, MessageRole.USER, JsHook.ON_INPUT)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = transformLatest(ctx, messages, MessageRole.ASSISTANT, JsHook.ON_OUTPUT)

    override suspend fun onDisable() {
        synchronized(runtimeLock) {
            runtime?.close()
            runtime = null
        }
    }

    private fun obtainRuntime(): JsPluginRuntime = synchronized(runtimeLock) {
        runtime ?: JsPluginRuntime(
            context = context,
            definition = definition,
            gateway = gateway,
            networkGranted = networkGranted,
        ).also { runtime = it }
    }

    private suspend fun transformLatest(
        ctx: TransformerContext,
        messages: List<UIMessage>,
        role: MessageRole,
        hook: JsHook,
    ): List<UIMessage> {
        if (hook !in definition.manifest.hooks) return messages
        val index = messages.indexOfLast { it.role == role }
        if (index < 0) return messages

        val message = messages[index]
        val payloadCtx = buildJsonObject {
            put("role", role.name.lowercase())
            put("messageCount", messages.size)
            putJsonObject("assistant") {
                put("name", ctx.assistant.name)
                put("id", ctx.assistant.id.toString())
            }
            putJsonObject("model") {
                put("name", ctx.model.displayName)
            }
        }

        var changed = false
        val newParts = message.parts.map { part ->
            if (part !is UIMessagePart.Text || part.text.isEmpty()) {
                part
            } else {
                val original = part.text
                val result = runCatching {
                    obtainRuntime().runHook(
                        hook = hook,
                        payload = buildJsonObject {
                            put("text", original)
                            put("ctx", payloadCtx)
                        },
                    ).getOrThrow()
                }.onFailure { error ->
                    Log.w(TAG, "plugin $id hook ${hook.jsName} failed, keep original text", error)
                }
                val text = result.getOrNull()
                if (text != null && text != original) {
                    changed = true
                    // 诊断日志:真机验证"脚本确实改写并落盘"(与规则/内置插件同款可观测性)
                    Log.d(TAG, "plugin $id hook=${hook.jsName} applied ${original.length}->${text.length} chars")
                    part.copy(text = text)
                } else {
                    part
                }
            }
        }
        if (!changed) return messages
        return messages.toMutableList().apply {
            this[index] = message.copy(parts = newParts)
        }
    }
}

/** 供日志/诊断读取的钩子名集合。 */
val JsPluginDefinition.hookNames: Set<String> get() = manifest.hooks.map { it.jsName }.toSet()

/** 供 UI 预览:把 `ctx` 形状拼成一行说明。 */
val JsPluginDefinition.payloadShape: String
    get() = "{ text, ctx: { role, messageCount, assistant, model } }"
