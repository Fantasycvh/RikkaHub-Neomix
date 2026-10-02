package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.entriesForDisplay
import app.androidrik.rikkahub.data.model.sortedForDisplay
import kotlin.uuid.Uuid

/**
 * 扩展管理的**两级**选择器：预设与世界书都先列「容器」，进入容器才看到其条目。
 *
 * 为什么两级：容器（预设/世界书）及其条目是**两个不同的绑定单位**——
 * - 预设：绑定到**条目**（`modeInjectionIds` 是条目 id 集合），所以详情里逐条开关；
 *   另有**整绑**（`promptPresetIds` 是预设 id 集合，助手专属、不随会话覆盖）= 容器页开关，
 *   全部条目生效，与条目级勾选是**并集**；
 * - 世界书：绑定到**整本**（`lorebookIds` 是世界书 id 集合），所以详情里条目只读展示。
 *
 * 一级平铺会把「哪个条目属于哪个预设」这层结构丢掉，用户看到几十个孤立条目无法定位。
 *
 * 本文件同时服务两处调用方（对话内扩展面板 `ExtensionSelector`、助手详情
 * `AssistantExtensionsPage`）——**共用同一实现以防两份 UI 漂移**（历史教训：侧栏与工作区
 * 详情页各写一份文件列表，改一处漏一处）。
 */
class ExtensionPickerNavigation {
    internal var presetId by mutableStateOf<Uuid?>(null)
        private set
    internal var worldbookId by mutableStateOf<Uuid?>(null)
        private set

    val isDetail: Boolean get() = presetId != null || worldbookId != null

    internal fun openPreset(id: Uuid) { back(); presetId = id }
    internal fun openWorldbook(id: Uuid) { back(); worldbookId = id }

    /** 回到容器列表（切换 tab、返回键、目标被删除时都归位到这里）。 */
    fun back() { presetId = null; worldbookId = null }

    internal fun presets(items: List<PromptPreset>) = pickerPage(items, presetId) { it.id }
    internal fun worldbooks(items: List<Lorebook>) = pickerPage(items, worldbookId) { it.id }
}

/** 二级页面的根数据是**容器**，永远不是拍平后的 `Settings.modeInjections`。 */
internal sealed interface ExtensionPickerPage<out T> {
    data class Containers<T>(val items: List<T>) : ExtensionPickerPage<T>
    data class Detail<T>(val item: T) : ExtensionPickerPage<T>
}

private fun <T> pickerPage(items: List<T>, id: Uuid?, itemId: (T) -> Uuid): ExtensionPickerPage<T> =
    items.find { itemId(it) == id }?.let { ExtensionPickerPage.Detail(it) }
        ?: ExtensionPickerPage.Containers(items)

@Composable
internal fun PickerBreadcrumb(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text("/ $title", modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun PickerManageButton(
    onManage: () -> Unit,
    label: String = stringResource(R.string.extension_content_manage),
) {
    TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
internal fun PresetPickerContent(
    presets: List<PromptPreset>,
    navigation: ExtensionPickerNavigation,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    /** 预设级绑定（`promptPresetIds`，**助手专属**、不随会话覆盖）：null = 隐藏整绑开关（会话作用域） */
    selectedPresetIds: Set<Uuid>? = null,
    onTogglePreset: ((Uuid, Boolean) -> Unit)? = null,
) {
    when (val page = navigation.presets(presets)) {
        is ExtensionPickerPage.Containers -> LazyColumn(modifier = modifier) {
            if (onTogglePreset != null) {
                item(key = "preset-bind-hint") {
                    Text(
                        text = stringResource(R.string.extension_picker_preset_bind_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            items(page.items, key = { it.id }) { preset ->
                ListItem(
                    headlineContent = {
                        Text(preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) })
                    },
                    supportingContent = {
                        Column {
                            if (preset.description.isNotBlank()) Text(preset.description)
                            Text(stringResource(R.string.prompt_page_entries_count_format, preset.entries.size))
                            Text(
                                stringResource(
                                    R.string.extension_picker_selected_format,
                                    preset.entries.count { it.id in selectedIds },
                                    preset.entries.size,
                                )
                            )
                            if (!preset.enabled) {
                                Text(stringResource(R.string.extension_picker_preset_globally_disabled))
                            }
                        }
                    },
                    trailingContent = {
                        // 整绑开关 = promptPresetIds（全部条目对该助手生效，与条目级勾选是并集）。
                        // 行点击仍进入详情逐条勾选；会话作用域（onTogglePreset == null）不显示。
                        if (onTogglePreset != null) {
                            Switch(
                                checked = preset.id in selectedPresetIds.orEmpty(),
                                onCheckedChange = { onTogglePreset(preset.id, it) },
                            )
                        } else {
                            Text("›")
                        }
                    },
                    modifier = Modifier.clickable { navigation.openPreset(preset.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { PickerManageButton(onManage) }
        }

        is ExtensionPickerPage.Detail -> Column(modifier = modifier) {
            val preset = page.item
            PickerBreadcrumb(
                preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) },
                navigation::back,
            )
            if (preset.id in selectedPresetIds.orEmpty()) {
                Text(
                    text = stringResource(R.string.extension_picker_preset_bound_all),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (preset.entries.isEmpty()) {
                ExtensionEmptyState(
                    message = stringResource(R.string.prompt_page_mode_injection_empty),
                    buttonText = stringResource(R.string.extension_content_manage),
                    onAction = onManage,
                )
            } else {
                // 这些开关只改**已有绑定 id**，绝不改条目的全局 enabled。
                // 不做聚合开关：多个回调基于同一份父快照会互相覆盖（同批次的已知陷阱）。
                key(preset.id) {
                    ModeInjectionsContent(
                        modeInjections = preset.entries.sortedForDisplay(),
                        selectedIds = selectedIds,
                        onToggle = onToggle,
                        onManage = onManage,
                    )
                }
            }
        }
    }
}

@Composable
internal fun WorldbookPickerContent(
    lorebooks: List<Lorebook>,
    navigation: ExtensionPickerNavigation,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val page = navigation.worldbooks(lorebooks)) {
        is ExtensionPickerPage.Containers -> LazyColumn(modifier = modifier) {
            items(page.items, key = { it.id }) { book ->
                ListItem(
                    headlineContent = {
                        Text(book.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                    },
                    supportingContent = {
                        Column {
                            if (book.description.isNotBlank()) Text(book.description)
                            Text(stringResource(R.string.prompt_page_entries_count_format, book.entries.size))
                            if (!book.enabled) {
                                Text(stringResource(R.string.extension_picker_lorebook_globally_disabled))
                            }
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = book.id in selectedIds,
                                onCheckedChange = { onToggle(book.id, it) },
                            )
                            Text("›", modifier = Modifier.padding(start = 8.dp))
                        }
                    },
                    modifier = Modifier.clickable { navigation.openWorldbook(book.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { PickerManageButton(onManage) }
        }

        is ExtensionPickerPage.Detail -> Column(modifier = modifier) {
            val book = page.item
            PickerBreadcrumb(
                book.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) },
                navigation::back,
            )
            Text(
                stringResource(R.string.extension_picker_lorebook_whole_book_hint),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PickerManageButton(onManage, stringResource(R.string.extension_picker_manage_lorebook_entries))
            key(book.id) {
                LazyColumn {
                    val entries = book.entriesForDisplay()
                    if (entries.isEmpty()) {
                        item {
                            ExtensionEmptyState(stringResource(R.string.prompt_page_entries_count_format, 0))
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(entry.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        if (entry.enabled) stringResource(R.string.extension_picker_globally_enabled)
                                        else stringResource(R.string.extension_picker_globally_disabled)
                                    )
                                    Text(stringResource(R.string.prompt_page_priority_format, entry.priority))
                                    Text("${entry.position} · ${entry.role}")
                                    if (entry.constantActive) {
                                        Text(stringResource(R.string.extension_picker_always_active))
                                    }
                                    if (entry.keywords.isNotEmpty()) {
                                        Text(
                                            stringResource(
                                                R.string.prompt_page_keywords_format,
                                                entry.keywords.joinToString(),
                                            )
                                        )
                                    }
                                    if (entry.secondaryKeywords.isNotEmpty()) {
                                        Text(
                                            stringResource(
                                                R.string.prompt_page_secondary_keywords_format,
                                                entry.secondaryKeywords.joinToString(),
                                            )
                                        )
                                    }
                                    if (entry.content.isNotBlank()) Text(entry.content, maxLines = 3)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}
