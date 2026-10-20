package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.buildCardSystemPrompt
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.ImageUtils
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.rikkahub.R
import org.koin.compose.koinInject

@Composable
fun AssistantImporter(
    modifier: Modifier = Modifier,
    onUpdate: (Assistant) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        SillyTavernImporter(onImport = onUpdate)
    }
}

@Composable
private fun SillyTavernImporter(
    onImport: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: resources.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            isLoading = true
            scope.launch {
                try {
                    runCatching {
                        importAssistantFromUri(
                            context = context,
                            uri = uri,
                            onImport = onImport,
                            toaster = toaster,
                            filesManager = filesManager,
                        )
                    }.onFailure { exception ->
                        exception.printStackTrace()
                        toaster.show(exception.message ?: resources.getString(R.string.assistant_importer_import_failed))
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = {
                pngPickerLauncher.launch(arrayOf("image/png"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_png))
        }

        OutlinedButton(
            onClick = {
                jsonPickerLauncher.launch(arrayOf("application/json"))
            },
            enabled = !isLoading
        ) {
            AutoAIIcon(name = "tavern", modifier = Modifier.padding(end = 8.dp))
            Text(text = if (isLoading) stringResource(R.string.assistant_importer_importing) else stringResource(R.string.assistant_importer_import_tavern_json))
        }
    }
}

// region Parsing Strategy

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context?, json: JsonObject, background: String?): Assistant
}

// V1/V2/V3 共用：data 对象（V2/V3 为 json["data"]，V1 老卡为顶层 json）→ Assistant
private fun parseCardData(context: Context?, data: JsonObject, background: String?): Assistant {
    val name = data["name"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: error(context?.getString(R.string.assistant_importer_missing_name_field) ?: "Missing name field")
    val firstMessage = data["first_mes"]?.jsonPrimitiveOrNull?.contentOrNull
    val system = data["system_prompt"]?.jsonPrimitiveOrNull?.contentOrNull
    val description = data["description"]?.jsonPrimitiveOrNull?.contentOrNull
    val personality = data["personality"]?.jsonPrimitiveOrNull?.contentOrNull
    val scenario = data["scenario"]?.jsonPrimitiveOrNull?.contentOrNull
    val example = data["mes_example"]?.jsonPrimitiveOrNull?.contentOrNull

    // 渲染走共享纯函数（与既有内联 buildString 逐字节等价），同时**保留分区零件**——
    // 此前只留压平后的字符串，槽位信息永久丢失（无法单独查看/编辑）。见 §6.2 ④。
    val prompt = buildCardSystemPrompt(
        name = name,
        cardSystemPrompt = system,
        description = description,
        personality = personality,
        scenario = scenario,
        exampleDialogue = example,
    )

    return Assistant(
        name = name,
        presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
        systemPrompt = prompt,
        background = background,
        cardDescription = description,
        cardPersonality = personality,
        cardScenario = scenario,
        cardExampleDialogue = example,
        cardSystemPrompt = system,
    )
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context?, json: JsonObject, background: String?): Assistant {
        val data = json["data"]?.jsonObject
            ?: error(context?.getString(R.string.assistant_importer_missing_data_field) ?: "Missing data field")
        return parseCardData(context, data, background)
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context?, json: JsonObject, background: String?): Assistant {
        val data = json["data"]?.jsonObject
            ?: error(context?.getString(R.string.assistant_importer_missing_data_field) ?: "Missing data field")
        return parseCardData(context, data, background)
    }
}

// 老版 SillyTavern V1：顶层即角色字段（name/description/first_mes...），无 spec/data 包裹
private class CharaCardV1Parser : TavernCardParser {
    override val specName: String = "chara_card_v1"

    override fun parse(context: Context?, json: JsonObject, background: String?): Assistant =
        parseCardData(context, json, background)
}

private val TAVERN_PARSERS: Map<String, TavernCardParser> = listOf(
    CharaCardV2Parser(),
    CharaCardV3Parser(),
    CharaCardV1Parser()
).associateBy { it.specName }

internal fun parseAssistantFromJson(
    context: Context?,
    json: JsonObject,
    background: String?,
): Assistant {
    val spec = json["spec"]?.jsonPrimitive?.contentOrNull
    val parser = if (spec != null) {
        TAVERN_PARSERS[spec]
            ?: error(
                context?.getString(R.string.assistant_importer_unsupported_spec, spec)
                    ?: "Unsupported spec: $spec"
            )
    } else {
        // 无 spec：先排除 SillyTavern 预设文件（Prompt Preset：prompts+prompt_order 结构，
        // 常见张冠李戴下载为 .json 且非角色卡），给明确指引而非 "缺 spec"
        if (json["prompts"] is kotlinx.serialization.json.JsonArray && json["prompt_order"] != null) {
            error(
                context?.getString(R.string.assistant_importer_is_preset)
                    ?: "This is a SillyTavern preset file, not a character card."
            )
        }
        // SillyTavern V1 老卡（顶层即字段，能识别出 name 就按 V1 解析）；
        // 否则视为非角色卡文件，报友好错误（B）
        if (json["name"]?.jsonPrimitiveOrNull?.contentOrNull != null) {
            TAVERN_PARSERS["chara_card_v1"]
        } else {
            null
        }
    } ?: error(
        context?.getString(R.string.assistant_importer_missing_spec_field)
            ?: "Cannot recognize character card: missing \"spec\" field"
    )
    return parser.parse(context = context, json = json, background = background)
}

/**
 * 从**文本**解析助手（#1942：对话内 JSON 代码块；将来剪贴板导入也走这里）。
 *
 * 与文件导入共用 [parseAssistantFromJson]，只是入口不同——**解析规则只有一处**，
 * 所以「酒馆预设文件不是角色卡」「缺 spec」「未知 spec」这些友好指引对新入口自动生效。
 *
 * 只额外负责一件事：把「JSON 本身不合法」也转成可读错误（文件导入原先是直接抛
 * SerializationException，文案不可读）。
 */
internal fun parseAssistantFromJsonText(context: Context?, jsonText: String): Assistant {
    val json = runCatching {
        Json.parseToJsonElement(jsonText).jsonObject
    }.getOrElse {
        error(context?.getString(R.string.assistant_importer_invalid_json) ?: "Invalid JSON")
    }
    return parseAssistantFromJson(context = context, json = json, background = null)
}

/**
 * 追加一个助手到设置（**唯一实现**：助手页导入与对话内代码块导入共用）。
 *
 * 抽出来是为了避免两处各写一遍 `assistants + assistant`——将来若要加去重/排序/刷新，
 * 只改这里一处。
 */
internal suspend fun SettingsStore.appendAssistant(assistant: Assistant) {
    update { settings -> settings.copy(assistants = settings.assistants + assistant) }
}

/**
 * 从文本导入助手并落库（非助手页场景的一站式入口，如对话内 JSON 代码块）。
 *
 * 不需要 ViewModel：落库走 [SettingsStore.update] 的一次原子写。失败时抛异常，
 * 异常文案已是本地化可读内容（见 [parseAssistantFromJsonText] 与其调用的解析层）。
 */
internal suspend fun importAssistantFromJsonText(
    context: Context,
    settingsStore: SettingsStore,
    jsonText: String,
): Assistant {
    val assistant = parseAssistantFromJsonText(context, jsonText)
    settingsStore.appendAssistant(assistant)
    return assistant
}

// endregion

private suspend fun importAssistantFromUri(
    context: Context,
    uri: Uri,
    onImport: (Assistant) -> Unit,
    toaster: ToasterState,
    filesManager: FilesManager,
) {
    try {
        val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
        val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
            when (mime) {
                "image/png" -> {
                    val result = ImageUtils.getTavernCharacterMeta(context, uri)
                    result.map { base64Data ->
                        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                        val bg = filesManager.createChatFilesByContents(listOf(uri)).first().toString()
                        json to bg
                    }.getOrElse { throw it }
                }

                "application/json" -> {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()
                        .use { it?.readText() }
                        ?: error(context.getString(R.string.assistant_importer_read_json_failed))
                    json to null
                }

                else -> error(context.getString(R.string.assistant_importer_unsupported_file_type, mime ?: "unknown"))
            }
        }
        val assistant = if (backgroundStr == null) {
            // 纯 JSON 文本：与对话内代码块走同一条文本入口（解析规则单点）
            parseAssistantFromJsonText(context = context, jsonText = jsonString)
        } else {
            // PNG 卡：背景图已在上面落盘，需把 background 交给解析层
            val json = Json.parseToJsonElement(jsonString).jsonObject
            parseAssistantFromJson(context = context, json = json, background = backgroundStr)
        }
        onImport(assistant)
    } catch (exception: Exception) {
        exception.printStackTrace()
        toaster.show(
            message = exception.message ?: context.getString(R.string.assistant_importer_import_failed),
            type = ToastType.Error
        )
    }
}
