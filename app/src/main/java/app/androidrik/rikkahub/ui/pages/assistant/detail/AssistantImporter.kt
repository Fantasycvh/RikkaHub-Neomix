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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.buildCardSystemPrompt
import app.androidrik.rikkahub.data.export.ExportSerializer
import app.androidrik.rikkahub.data.export.SillyTavernCharacterBook
import app.androidrik.rikkahub.data.export.parseSillyTavernCharacterBook
import app.androidrik.rikkahub.data.export.sha256Of
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.pages.backup.components.DuplicateImportRecord
import app.androidrik.rikkahub.ui.pages.backup.components.formatImportDate
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.ImageUtils
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.rikkahub.R
import kotlinx.serialization.json.decodeFromJsonElement
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
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var isLoading by remember { mutableStateOf(false) }
    // 重复导入的中间态：命中后停在这里等确认（§6.3），确认才落库
    var pendingDuplicate by remember { mutableStateOf<Pair<TavernCardImport, DuplicateImportRecord>?>(null) }

    // 解析 → 查重 → 未命中直接提交 / 命中挂起等确认（允许继续，§6.3）
    fun importUri(uri: Uri) {
        isLoading = true
        scope.launch {
            try {
                runCatching {
                    val result = parseTavernCardFromUri(
                        context = context,
                        uri = uri,
                        filesManager = filesManager,
                    )
                    val duplicate = settingsStore.findCardDuplicate(result.sourceKey)
                    if (duplicate == null) {
                        settingsStore.commitCardImportExtras(result)
                        onImport(result.assistant)
                    } else {
                        pendingDuplicate = result to DuplicateImportRecord(
                            title = duplicate.title,
                            importedAt = duplicate.importedAt,
                        )
                    }
                }.onFailure { exception ->
                    exception.printStackTrace()
                    toaster.show(exception.message ?: resources.getString(R.string.assistant_importer_import_failed))
                }
            } finally {
                isLoading = false
            }
        }
    }

    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importUri) }

    val pngPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importUri) }

    pendingDuplicate?.let { (result, record) ->
        CardDuplicateConfirmDialog(
            record = record,
            onConfirm = {
                pendingDuplicate = null
                scope.launch {
                    settingsStore.commitCardImportExtras(result)
                    onImport(result.assistant)
                }
            },
            onDismiss = { pendingDuplicate = null },
        )
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

/**
 * 角色卡解析结果：助手本体 + 可选拆分出的世界书（`data.character_book`）。
 *
 * 书已预绑进 [assistant] 的 `lorebookIds`——调用方只需负责把两者落库
 * （设计稿 `docs/design/design-card-lorebook-import.md` §二 决策 1/3）。
 *
 * [sourceKey] 是**卡内容指纹**（重复导入检测的锚点，§六）；null = 无法计算（跳过检测，
 * fail-open 与对话导入旧版文件无 `sourceId` 的降级同款）。
 */
internal data class TavernCardImport(
    val assistant: Assistant,
    val lorebook: Lorebook? = null,
    val sourceKey: String? = null,
)

/**
 * 卡内容指纹（§6.1）：`"card:" + sha256(卡数据 JSON)` 的原始 hex（前缀在落键时再加）。
 *
 * hash 的对象是**卡数据本身**（V2/V3 的 `data` 对象、V1 老卡的整个顶层）而非整个文件——
 * 免费性质：同一张卡的 PNG 与 .json 两种载体互认（PNG 的 tEXt chunk 里就是同一段 JSON）。
 *
 * kotlinx 对同一解析结果的 `toString()` 输出稳定；跨工具重排过键序的同一张卡会得到
 * 不同指纹 → **不报重复**（漏报可接受、误报不可接受）。计算失败返回 null（跳过检测）。
 */
internal fun tavernCardSourceKey(cardJson: JsonObject): String? = runCatching {
    sha256Of(cardJson.toString().toByteArray(Charsets.UTF_8))
}.getOrNull()

private interface TavernCardParser {
    val specName: String
    fun parse(context: Context?, json: JsonObject, background: String?): TavernCardImport
}

// V1/V2/V3 共用：data 对象（V2/V3 为 json["data"]，V1 老卡为顶层 json）→ TavernCardImport
private fun parseCardData(context: Context?, data: JsonObject, background: String?): TavernCardImport {
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

    // 内嵌世界书拆分（V1 卡无此字段，天然为 null）：条目为空不建书（决策 4）。
    // 书本身坏了不拦整个卡导入——ST 同款行为（card 照常导入，仅 toast 书失败）；
    // 此层无 toast 通道，降级为「无书」而非静默丢助手。
    val lorebook = data["character_book"]?.let { bookJson ->
        runCatching {
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<SillyTavernCharacterBook>(bookJson)
        }.getOrNull()?.let { book -> parseSillyTavernCharacterBook(cardName = name, book = book) }
    }

    val assistant = Assistant(
        name = name,
        presetMessages = if (firstMessage != null) listOf(UIMessage.assistant(firstMessage)) else emptyList(),
        systemPrompt = prompt,
        background = background,
        cardDescription = description,
        cardPersonality = personality,
        cardScenario = scenario,
        cardExampleDialogue = example,
        cardSystemPrompt = system,
        lorebookIds = lorebook?.let { setOf(it.id) } ?: emptySet(),
    )
    return TavernCardImport(assistant, lorebook, sourceKey = tavernCardSourceKey(data))
}

private class CharaCardV2Parser : TavernCardParser {
    override val specName: String = "chara_card_v2"

    override fun parse(context: Context?, json: JsonObject, background: String?): TavernCardImport {
        val data = json["data"]?.jsonObject
            ?: error(context?.getString(R.string.assistant_importer_missing_data_field) ?: "Missing data field")
        return parseCardData(context, data, background)
    }
}

private class CharaCardV3Parser : TavernCardParser {
    override val specName: String = "chara_card_v3"

    override fun parse(context: Context?, json: JsonObject, background: String?): TavernCardImport {
        val data = json["data"]?.jsonObject
            ?: error(context?.getString(R.string.assistant_importer_missing_data_field) ?: "Missing data field")
        return parseCardData(context, data, background)
    }
}

// 老版 SillyTavern V1：顶层即角色字段（name/description/first_mes...），无 spec/data 包裹
private class CharaCardV1Parser : TavernCardParser {
    override val specName: String = "chara_card_v1"

    override fun parse(context: Context?, json: JsonObject, background: String?): TavernCardImport =
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
): TavernCardImport {
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
internal fun parseAssistantFromJsonText(context: Context?, jsonText: String): TavernCardImport {
    val json = runCatching {
        Json.parseToJsonElement(jsonText).jsonObject
    }.getOrElse {
        error(context?.getString(R.string.assistant_importer_invalid_json) ?: "Invalid JSON")
    }
    return parseAssistantFromJson(context = context, json = json, background = null)
}

/** 卡导入在 `importedSources` 里的键空间前缀（与对话导入的 UUID 键隔离，§6.2）。 */
private const val CARD_SOURCE_KEY_PREFIX = "card:"

/**
 * 查重：该卡此前导入过的记录（null = 未导入过 / [sourceKey] 为 null 跳过检测）。
 *
 * 复用 `Settings.importedSources`（对话导入 §十 的存储，零新迁移）。
 */
internal suspend fun SettingsStore.findCardDuplicate(sourceKey: String?): ImportSourceRecord? =
    sourceKey?.let { key -> settingsFlowRaw.first().importedSources[CARD_SOURCE_KEY_PREFIX + key] }

/**
 * 来源记录 upsert（**纯函数**，单测直测）：成功导入后写入/刷新 `card:<指纹>` → 记录。
 *
 * 注意键必须是**卡指纹**而非助手 id——助手每次导入都换新 id，记它永远查不到（§10.4.1 同坑）。
 */
internal fun upsertCardSourceRecord(
    existing: Map<String, ImportSourceRecord>,
    sourceKey: String?,
    assistantName: String,
    now: Long = System.currentTimeMillis(),
): Map<String, ImportSourceRecord> =
    if (sourceKey == null) existing
    else existing + (
        CARD_SOURCE_KEY_PREFIX + sourceKey to ImportSourceRecord(
            importedAt = now,
            title = assistantName.ifBlank { null },
        )
        )

/**
 * 追加一个助手（及其拆分出的世界书 + 来源记录）到设置（**唯一实现**：助手页导入与对话内代码块导入共用）。
 *
 * 抽出来是为了避免两处各写一遍 `assistants + lorebooks + importedSources`——将来若要加
 * 去重/排序/刷新，只改这里一处。三者在**同一次原子写**里（设计稿 §五.4）。
 */
internal suspend fun SettingsStore.appendAssistantWithLorebook(result: TavernCardImport) {
    update { settings ->
        settings.copy(
            assistants = settings.assistants + result.assistant,
            lorebooks = result.lorebook?.let { book -> settings.lorebooks + book } ?: settings.lorebooks,
            importedSources = upsertCardSourceRecord(settings.importedSources, result.sourceKey, result.assistant.name),
        )
    }
}

/**
 * 助手页路径的落库动作（§6.4）：书 + 来源记录**同一次原子写**；
 * 助手本体不经这里（走 `onImport` 的 EditState 草稿，用户点保存才落库）。
 */
internal suspend fun SettingsStore.commitCardImportExtras(result: TavernCardImport) {
    update { settings ->
        settings.copy(
            lorebooks = result.lorebook?.let { book -> settings.lorebooks + book } ?: settings.lorebooks,
            importedSources = upsertCardSourceRecord(settings.importedSources, result.sourceKey, result.assistant.name),
        )
    }
}

/** 仅追加助手（无书；如 `AssistantVM.addAssistant` 的手工创建路径）。 */
internal suspend fun SettingsStore.appendAssistant(assistant: Assistant) {
    appendAssistantWithLorebook(TavernCardImport(assistant))
}

// endregion

/** 从 URI 解析角色卡（PNG 落背景图 / JSON 读文本），**只解析不落库**——查重发生在落库之前（§6.3）。 */
private suspend fun parseTavernCardFromUri(
    context: Context,
    uri: Uri,
    filesManager: FilesManager,
): TavernCardImport {
    val mime = withContext(Dispatchers.IO) { filesManager.getFileMimeType(uri) }
    val (jsonString, backgroundStr) = withContext(Dispatchers.IO) {
        when (mime) {
            "image/png" -> {
                val result = ImageUtils.getTavernCharacterMeta(context, uri)
                result.map { base64Data ->
                    val json = String(Base64.decode(base64Data, Base64.DEFAULT))
                    // P1-7：背景图落盘失败时 createChatFilesByContents 返回空列表
                    // （文件名/MIME/流打开失败）。旧代码 `.first()` 抛
                    // NoSuchElementException（文案只有 "List is empty."）；这里改成
                    // 明确失败信息，由下方 catch 显示给用户，且**不会**带着空 background
                    // 继续导入出一份无背景的助手配置。
                    val bg = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                        ?: error("Failed to save character card background image")
                    json to bg.toString()
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
    return if (backgroundStr == null) {
        // 纯 JSON 文本：与对话内代码块走同一条文本入口（解析规则单点）
        parseAssistantFromJsonText(context = context, jsonText = jsonString)
    } else {
        // PNG 卡：背景图已在上面落盘，需把 background 交给解析层
        val json = Json.parseToJsonElement(jsonString).jsonObject
        parseAssistantFromJson(context = context, json = json, background = backgroundStr)
    }
}

/**
 * 重复导入确认对话框（§6.3，**允许继续**而非阻止）。
 *
 * 助手页与对话内代码块两个入口共用；文案复用对话导入的通用键
 * （`backup_page_import_duplicate_warning/hint`，措辞本就不绑定会话）。
 */
@Composable
internal fun CardDuplicateConfirmDialog(
    record: DuplicateImportRecord,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fallbackName = stringResource(R.string.assistant_importer_duplicate_fallback_name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_importer_duplicate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(
                        R.string.backup_page_import_duplicate_warning,
                        record.title?.takeIf { it.isNotBlank() } ?: fallbackName,
                        formatImportDate(record.importedAt),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.backup_page_import_duplicate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
