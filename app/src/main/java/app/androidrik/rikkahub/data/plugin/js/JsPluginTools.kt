package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.plugin.api.PluginCapability
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Builds host tools without tying their policy checks to the JavaScript runtime. */
internal fun createJsPluginTools(
    pluginId: String,
    manifest: JsPluginManifest,
    capabilityGranted: (PluginCapability) -> Boolean,
    executeTool: suspend (String, JsonElement) -> JsonElement,
): List<Tool> = manifest.tools.map { declaration ->
    Tool(
        name = pluginToolName(pluginId, declaration.name),
        description = declaration.description,
        parameters = {
            InputSchema.Obj(
                properties = declaration.parameters.getValue("properties").jsonObject,
                required = declaration.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        },
        // External scripts always require host approval; the manifest cannot disable it.
        needsApproval = { true },
        executionTimeoutMillis = manifest.timeoutMs + 6000,
        execute = { arguments ->
            check(capabilityGranted(PluginCapability.TOOL_REGISTER)) { "插件已关闭或 TOOL_REGISTER 已撤销" }
            validateToolArguments(declaration.parameters, arguments)
            val value = executeTool(declaration.name, arguments)
            listOf(UIMessagePart.Text(value.toString()))
        },
    )
}
