package app.androidrik.rikkahub.ui.pages.setting

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import app.androidrik.rikkahub.ui.context.LocalSettings
import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import app.androidrik.rikkahub.ui.components.ui.AppFilledTonalButton as FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Tick01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.PresetThemes
import app.androidrik.rikkahub.data.model.THEME_CONTRAST_LEVELS
import app.androidrik.rikkahub.ui.theme.ThemeAppearanceSnapshot
import app.androidrik.rikkahub.data.model.ThemePaletteStyle
import app.androidrik.rikkahub.data.model.ThemeRoleOverrides
import app.androidrik.rikkahub.ui.theme.ThemeSnapshot
import app.androidrik.rikkahub.data.model.ThemeModeRoleOverrides
import app.androidrik.rikkahub.ui.theme.WCAG_AA_CONTRAST
import app.androidrik.rikkahub.ui.theme.toAppearanceSnapshot
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import app.androidrik.rikkahub.ui.theme.toHexCode
import app.androidrik.rikkahub.ui.theme.wcagContrastRatio
import app.androidrik.rikkahub.ui.theme.withAppearanceSnapshot
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.androidrik.rikkahub.data.files.BubbleTextureStore
import app.androidrik.rikkahub.data.files.AppearanceArchive
import app.androidrik.rikkahub.data.files.readLimited
import app.androidrik.rikkahub.ui.theme.textureIds
import app.androidrik.rikkahub.ui.theme.exportTheme
import app.androidrik.rikkahub.ui.theme.importTheme
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

private val themeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val clipboardManager = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val exportSuccessMsg = stringResource(R.string.setting_theme_page_export_success)
    val importSuccessMsg = stringResource(R.string.setting_theme_page_import_success)

    var showEditSheet by remember { mutableStateOf(false) }
    var editingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var deletingTheme by remember { mutableStateOf<CustomTheme?>(null) }
    val context = LocalContext.current
    val skinSpacing = LocalAppSkin.current.skin.spacing
    val textureStore = remember(context) { BubbleTextureStore(context.applicationContext) }
    var pendingThemeArchive by remember { mutableStateOf<ByteArray?>(null) }
    var importedThemeJson by remember { mutableStateOf("") }
    var archiveBusy by remember { mutableStateOf(false) }
    val saveThemeArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes = pendingThemeArchive
        pendingThemeArchive = null
        if (uri != null && bytes != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法保存主题包") }
                toaster.show(exportSuccessMsg, type = ToastType.Success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toaster.show("主题包保存失败：${failure.message.orEmpty()}") }
        }
    }
    val openThemeArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            archiveBusy = true
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        textureStore.importTheme(readLimited(it, AppearanceArchive.MAX_TOTAL + 1024 * 1024))
                    } ?: error("无法读取主题包")
                }
                importedThemeJson = themeJson.encodeToString(ThemeSnapshot.serializer(), snapshot)
                showImportDialog = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toaster.show("主题包读取失败：${failure.message.orEmpty()}") }
            finally { archiveBusy = false }
        }
    }


    GlassTopBarScaffold(displaySetting = LocalSettings.current.displaySetting,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_theme_setting)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = pageGlassTopBarColors(CustomColors.topBarColors)
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 键盘弹起时让列表内容上移，避免遮住覆写槽位输入框
                .imePadding(),
            contentPadding = innerPadding + PaddingValues(skinSpacing.pageDp.dp),
            verticalArrangement = Arrangement.spacedBy(skinSpacing.sectionDp.dp),
        ) {
            item(key = "theme_texture_archive") {
                TextButton(enabled = !archiveBusy, onClick = { openThemeArchive.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(if (archiveBusy) "正在读取主题包…" else "导入主题包（含图片纹理）")
                }
            }
            if (settings.dynamicColor) {
                item("dynamicColorHint") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_dynamic_color_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!settings.dynamicColor) {
                item("presetThemes") {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_preset_themes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(appSkinRoundedShape(20f))
                                .background(MaterialTheme.colorScheme.surfaceBright)
                        ) {
                            PresetThemeButtonGroup(
                                themeId = settings.themeId,
                                modifier = Modifier.fillMaxWidth(),
                                onChangeTheme = {
                                    vm.updateSettings(settings.copy(themeId = it))
                                }
                            )
                        }
                    }
                }

                item("customThemesHeader") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_theme_page_custom_themes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { showImportDialog = true }
                            ) {
                                Icon(HugeIcons.FileImport, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.setting_theme_page_import_theme))
                            }
                            FilledTonalButton(
                                onClick = {
                                    editingTheme = null
                                    showEditSheet = true
                                }
                            ) {
                                Icon(HugeIcons.PlusSign, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.setting_theme_page_add_theme))
                            }
                        }
                    }
                }

                if (settings.customThemes.isEmpty()) {
                    item("emptyCustomThemes") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.setting_theme_page_no_custom_themes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(settings.customThemes, key = { it.id }) { theme ->
                    CustomThemeItem(
                        theme = theme,
                        isSelected = settings.themeId == theme.id,
                        onSelect = {
                            vm.updateSettings(settings.copy(themeId = theme.id))
                        },
                        onExport = export@ {
                            if (archiveBusy || pendingThemeArchive != null) return@export
                            archiveBusy = true
                            // 快照格式（design-custom-theme.md §4.3）：色板 + 外观子集；导入端双兼容旧裸主题
                            val snapshot = ThemeSnapshot(theme = theme, appearance = settings.displaySetting.toAppearanceSnapshot())
                            scope.launch {
                                try {
                                    if (snapshot.textureIds().isNotEmpty()) {
                                        pendingThemeArchive = withContext(Dispatchers.IO) { textureStore.exportTheme(snapshot) }
                                        saveThemeArchive.launch("theme.neomix.zip")
                                    } else {
                                        val json = themeJson.encodeToString(ThemeSnapshot.serializer(), snapshot)
                                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("theme", json)))
                                        toaster.show(exportSuccessMsg, type = ToastType.Success)
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { pendingThemeArchive = null; toaster.show("主题导出失败：${failure.message.orEmpty()}") }
                                finally { archiveBusy = false }
                            }
                        },
                        onEdit = {
                            editingTheme = theme
                            showEditSheet = true
                        },
                        onDelete = {
                            deletingTheme = theme
                        }
                    )
                }
            }

            // 角色覆写是全局层（作用于动态取色/预设/自定义任意色源），不受 dynamicColor 开关影响。
            // onChange 走 transform 式读-改-写：每个按键都是一次 settings 更新，整对象写会丢更新
            // （真机实测：hex 连续输入被过期快照打回 null）
            item("roleOverrides") {
                RoleOverridesSection(
                    overrides = settings.displaySetting.themeRoleOverrides,
                    onChange = { transform ->
                        vm.updateSettings { s ->
                            s.copy(
                                displaySetting = s.displaySetting.copy(
                                    themeRoleOverrides = transform(s.displaySetting.themeRoleOverrides)
                                )
                            )
                        }
                    },
                )
            }
        }
    }

    if (showEditSheet) {
        CustomThemeEditSheet(
            theme = editingTheme,
            onDismiss = { showEditSheet = false },
            onSave = { theme ->
                val newThemes = if (editingTheme != null) {
                    settings.customThemes.map { if (it.id == theme.id) theme else it }
                } else {
                    settings.customThemes + theme
                }
                vm.updateSettings(
                    settings.copy(
                        customThemes = newThemes,
                        themeId = theme.id
                    )
                )
                showEditSheet = false
            }
        )
    }

    if (showImportDialog) {
        ImportThemeDialog(
            initialJson = importedThemeJson,
            onDismiss = { showImportDialog = false; importedThemeJson = "" },
            onImport = { theme, appearance ->
                val importedTheme = theme.copy(id = Uuid.random().toString())
                vm.updateSettings { s ->
                    s.copy(
                        customThemes = s.customThemes + importedTheme,
                        themeId = importedTheme.id,
                        displaySetting = if (appearance != null) {
                            s.displaySetting.withAppearanceSnapshot(appearance)
                        } else {
                            s.displaySetting
                        },
                    )
                }
                showImportDialog = false
                importedThemeJson = ""
                toaster.show(importSuccessMsg, type = ToastType.Success)
            }
        )
    }

    RikkaConfirmDialog(
        show = deletingTheme != null,
        title = stringResource(R.string.setting_theme_page_delete_theme_title),
        confirmText = stringResource(android.R.string.ok),
        dismissText = stringResource(android.R.string.cancel),
        onConfirm = {
            deletingTheme?.let { theme ->
                val newThemes = settings.customThemes.filter { it.id != theme.id }
                // 默认主题跟随预设列表首项（与 PreferencesStore 的默认口径一致，勿写死 id）
                val newThemeId = if (settings.themeId == theme.id) PresetThemes[0].id else settings.themeId
                vm.updateSettings(settings.copy(customThemes = newThemes, themeId = newThemeId))
            }
            deletingTheme = null
        },
        onDismiss = { deletingTheme = null },
        text = {
            Text(stringResource(R.string.setting_theme_page_delete_theme_message))
        }
    )
}

@Composable
private fun CustomThemeItem(
    theme: CustomTheme,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.generateColorScheme(darkMode)

    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(appSkinRoundedShape(16f))
            .clickable { onSelect() },
        headlineContent = { Text(theme.name.ifEmpty { "Unnamed" }) },
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Canvas(
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(40.dp)
                ) {
                    drawRect(color = scheme.primaryContainer, size = size)
                    drawRect(
                        color = scheme.secondaryContainer,
                        size = size,
                        topLeft = Offset(x = size.width / 2, y = 0f)
                    )
                    drawRect(
                        color = scheme.tertiaryContainer,
                        size = size,
                        topLeft = Offset(x = size.width / 2, y = size.height / 2)
                    )
                    drawCircle(
                        color = scheme.primary,
                        radius = if (isSelected) 10.dp.toPx() else 6.dp.toPx(),
                        center = Offset(x = size.width / 2, y = size.height / 2)
                    )
                }
                if (isSelected) {
                    Icon(
                        HugeIcons.Tick01,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        trailingContent = {
            Row {
                IconButton(onClick = onExport) {
                    Icon(HugeIcons.Copy01, null)
                }
                IconButton(onClick = onEdit) {
                    Icon(HugeIcons.Edit02, null)
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        HugeIcons.Delete02,
                        null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        colors = CustomColors.listItemColors,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomThemeEditSheet(
    theme: CustomTheme?,
    onDismiss: () -> Unit,
    onSave: (CustomTheme) -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    var currentTheme by remember {
        mutableStateOf(theme ?: CustomTheme())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = if (theme == null) stringResource(R.string.setting_theme_page_create_theme)
                else stringResource(R.string.setting_theme_page_edit_theme),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = currentTheme.name,
                    onValueChange = { currentTheme = currentTheme.copy(name = it) },
                    label = { Text(stringResource(R.string.setting_theme_page_theme_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_primary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = Color(currentTheme.primaryColorArgb.toInt()),
                    onColorChange = {
                        currentTheme = currentTheme.copy(primaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_secondary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = if (currentTheme.secondaryColorArgb != null) {
                        Color(currentTheme.secondaryColorArgb!!.toInt())
                    } else {
                        Color(currentTheme.generateColorScheme(false).secondary.toArgb())
                    },
                    onColorChange = {
                        currentTheme = currentTheme.copy(secondaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_tertiary_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                ColorPickerRow(
                    color = if (currentTheme.tertiaryColorArgb != null) {
                        Color(currentTheme.tertiaryColorArgb!!.toInt())
                    } else {
                        Color(currentTheme.generateColorScheme(false).tertiary.toArgb())
                    },
                    onColorChange = {
                        currentTheme = currentTheme.copy(tertiaryColorArgb = it.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                )

                Text(
                    text = stringResource(R.string.setting_theme_page_palette_style),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemePaletteStyle.entries.forEach { style ->
                        FilterChip(
                            selected = (currentTheme.paletteStyle ?: ThemePaletteStyle.TONAL_SPOT) == style,
                            onClick = { currentTheme = currentTheme.copy(paletteStyle = style) },
                            label = { Text(paletteStyleLabel(style)) },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.setting_theme_page_contrast_level),
                    style = MaterialTheme.typography.titleSmall,
                )
                SegmentedPill(
                    options = THEME_CONTRAST_LEVELS.toList(),
                    selectedOption = CustomTheme.normalizeContrastLevel(currentTheme.contrastLevel),
                    onOptionSelected = { currentTheme = currentTheme.copy(contrastLevel = it) },
                    optionToString = { contrastLevelLabel(it) },
                    modifier = Modifier.fillMaxWidth(),
                )

                ThemePreview(currentTheme)
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSave(currentTheme) },
                    enabled = currentTheme.name.isNotBlank()
                ) {
                    Text(stringResource(R.string.setting_theme_page_save))
                }
            }
        }
    }
}

@Composable
private fun ImportThemeDialog(
    initialJson: String = "",
    onDismiss: () -> Unit,
    onImport: (CustomTheme, ThemeAppearanceSnapshot?) -> Unit,
) {
    var jsonText by remember { mutableStateOf(initialJson) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 快照携带外观子集时显示「同时应用外观」勾选（默认不勾：主题色共享是常态，外观子集是侵入性操作）
    var hasAppearance by remember { mutableStateOf(runCatching { themeJson.decodeFromString<ThemeSnapshot>(initialJson).appearance != null }.getOrDefault(false)) }
    var applyAppearance by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_theme_page_import_theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        errorMessage = null
                        // 双格式探测：快照（theme + appearance）优先，旧裸 CustomTheme 兼容
                        val snapshot = runCatching {
                            themeJson.decodeFromString<ThemeSnapshot>(it)
                        }.getOrNull()
                        hasAppearance = snapshot?.appearance != null
                        if (!hasAppearance) applyAppearance = false
                    },
                    label = { Text("JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { msg -> { Text(msg) } },
                )
                if (hasAppearance) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = applyAppearance,
                            onCheckedChange = { applyAppearance = it }
                        )
                        Text(stringResource(R.string.setting_theme_page_apply_appearance))
                    }
                    Text(
                        text = stringResource(R.string.setting_theme_page_apply_appearance_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val snapshot = runCatching {
                            themeJson.decodeFromString<ThemeSnapshot>(jsonText)
                        }.getOrNull()
                        val theme = snapshot?.theme
                            ?: themeJson.decodeFromString<CustomTheme>(jsonText)
                        onImport(theme, snapshot?.appearance?.takeIf { applyAppearance })
                    } catch (e: Exception) {
                        errorMessage = e.message
                    }
                },
                enabled = jsonText.isNotBlank()
            ) {
                Text(stringResource(R.string.setting_theme_page_import_theme))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun paletteStyleLabel(style: ThemePaletteStyle): String = stringResource(
    when (style) {
        ThemePaletteStyle.TONAL_SPOT -> R.string.setting_theme_page_palette_tonal_spot
        ThemePaletteStyle.MONOCHROME -> R.string.setting_theme_page_palette_monochrome
        ThemePaletteStyle.NEUTRAL -> R.string.setting_theme_page_palette_neutral
        ThemePaletteStyle.VIBRANT -> R.string.setting_theme_page_palette_vibrant
        ThemePaletteStyle.EXPRESSIVE -> R.string.setting_theme_page_palette_expressive
        ThemePaletteStyle.FIDELITY -> R.string.setting_theme_page_palette_fidelity
        ThemePaletteStyle.CONTENT -> R.string.setting_theme_page_palette_content
        ThemePaletteStyle.RAINBOW -> R.string.setting_theme_page_palette_rainbow
        ThemePaletteStyle.FRUIT_SALAD -> R.string.setting_theme_page_palette_fruit_salad
    }
)

@Composable
private fun contrastLevelLabel(level: Double): String = stringResource(
    when {
        level <= 0.0 -> R.string.setting_theme_page_contrast_standard
        level < 1.0 -> R.string.setting_theme_page_contrast_medium
        else -> R.string.setting_theme_page_contrast_high
    }
)

/**
 * 高级角色覆写区块（BiliPai ThemeRoleOverrides 语义）：全局层，作用于任意色源。
 * 浅/深色各五个槽位，null = 跟随主题；文字槽与背景算 WCAG 对比度，<4.5 警告不阻断。
 */
@Composable
private fun RoleOverridesSection(
    overrides: ThemeRoleOverrides,
    // transform 语义：在**最新**的 ThemeRoleOverrides 基础上做修改（配合 SettingsStore
    // 的读-改-写，逐键输入不丢更新）
    onChange: ((ThemeRoleOverrides) -> ThemeRoleOverrides) -> Unit,
) {
    // 页签默认落在当前明暗模式，方便对照实际效果（current 须在 remember 外读，lambda 非 Composable）
    val initialDarkTab = LocalDarkMode.current
    var darkTab by remember { mutableStateOf(initialDarkTab) }

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_theme_page_role_overrides),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(appSkinRoundedShape(20f))
                .background(MaterialTheme.colorScheme.surfaceBright)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.setting_theme_page_role_overrides_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = overrides.enabled,
                    onCheckedChange = { onChange { o -> o.copy(enabled = it) } },
                )
            }
            if (overrides.enabled) {
                SegmentedPill(
                    options = listOf(false, true),
                    selectedOption = darkTab,
                    onOptionSelected = { darkTab = it },
                    optionToString = {
                        if (it) stringResource(R.string.setting_theme_page_role_overrides_mode_dark)
                        else stringResource(R.string.setting_theme_page_role_overrides_mode_light)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 槽位编辑放 ModalBottomSheet：列表 item 会因键盘弹起被裁出视口而销毁重建
                // （remember 丢失 → 焦点掉 → IME 收起再弹，逐字符回退/光标漂移，真机实测）；
                // sheet 的 Column+verticalScroll 子项常驻，与本页主题编辑 sheet 同款可靠结构
                var showSlotSheet by remember { mutableStateOf(false) }
                FilledTonalButton(
                    onClick = { showSlotSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            R.string.setting_theme_page_role_edit_slots,
                            listOf(
                                (if (darkTab) overrides.dark else overrides.light).backgroundHex,
                                (if (darkTab) overrides.dark else overrides.light).primaryTextHex,
                                (if (darkTab) overrides.dark else overrides.light).secondaryTextHex,
                                (if (darkTab) overrides.dark else overrides.light).controlAccentHex,
                                (if (darkTab) overrides.dark else overrides.light).userBubbleHex,
                            ).count { it != null },
                        )
                    )
                }
                if (showSlotSheet) {
                    RoleOverrideEditSheet(
                        overrides = overrides,
                        dark = darkTab,
                        onChange = { modeTransform ->
                            onChange { o ->
                                val newMode = modeTransform(if (darkTab) o.dark else o.light)
                                if (darkTab) o.copy(dark = newMode) else o.copy(light = newMode)
                            }
                        },
                        onDismiss = { showSlotSheet = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoleOverrideEditSheet(
    overrides: ThemeRoleOverrides,
    dark: Boolean,
    onChange: ((ThemeModeRoleOverrides) -> ThemeModeRoleOverrides) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (dark) {
                    stringResource(R.string.setting_theme_page_role_overrides_mode_dark)
                } else {
                    stringResource(R.string.setting_theme_page_role_overrides_mode_light)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            RoleOverrideModeEditor(
                mode = if (dark) overrides.dark else overrides.light,
                onChange = onChange,
            )
        }
    }
}

@Composable
private fun RoleOverrideModeEditor(
    mode: ThemeModeRoleOverrides,
    onChange: ((ThemeModeRoleOverrides) -> ThemeModeRoleOverrides) -> Unit,
) {
    // 对比度预警的兜底色取当前色板对应角色；若编辑的是非当前明暗页签，兜底色来自另一
    // 模式——仅影响预警精度（两 hex 均显式设置时结果精确），不影响覆写本身。
    val scheme = MaterialTheme.colorScheme
    val effectiveBackground = mode.backgroundHex.toComposeColorOrNull() ?: scheme.background

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RoleSlotRow(
            label = stringResource(R.string.setting_theme_page_role_background),
            value = mode.backgroundHex,
            fallbackColor = scheme.background,
            onValueChange = { onChange { m -> m.copy(backgroundHex = it) } },
        )
        RoleSlotRow(
            label = stringResource(R.string.setting_theme_page_role_primary_text),
            value = mode.primaryTextHex,
            fallbackColor = scheme.onBackground,
            onValueChange = { onChange { m -> m.copy(primaryTextHex = it) } },
        )
        ContrastHint(
            foreground = mode.primaryTextHex.toComposeColorOrNull() ?: scheme.onBackground,
            background = effectiveBackground,
        )
        RoleSlotRow(
            label = stringResource(R.string.setting_theme_page_role_secondary_text),
            value = mode.secondaryTextHex,
            fallbackColor = scheme.onSurfaceVariant,
            onValueChange = { onChange { m -> m.copy(secondaryTextHex = it) } },
        )
        ContrastHint(
            foreground = mode.secondaryTextHex.toComposeColorOrNull() ?: scheme.onSurfaceVariant,
            background = effectiveBackground,
        )
        RoleSlotRow(
            label = stringResource(R.string.setting_theme_page_role_control_accent),
            value = mode.controlAccentHex,
            fallbackColor = scheme.primary,
            onValueChange = { onChange { m -> m.copy(controlAccentHex = it) } },
        )
        RoleSlotRow(
            label = stringResource(R.string.setting_theme_page_role_user_bubble),
            value = mode.userBubbleHex,
            fallbackColor = scheme.primaryContainer,
            onValueChange = { onChange { m -> m.copy(userBubbleHex = it) } },
        )
    }
}

@Composable
private fun ContrastHint(foreground: Color, background: Color) {
    val ratio = wcagContrastRatio(foreground, background)
    if (ratio < WCAG_AA_CONTRAST) {
        Text(
            text = stringResource(R.string.setting_theme_page_role_contrast_warning, ratio),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun RoleSlotRow(
    label: String,
    value: String?,
    fallbackColor: Color,
    onValueChange: (String?) -> Unit,
) {
    // 本地编辑态：按键先落本地、解析成功才提交（commit-only-valid）——若直接拿 DataStore
    // 回环的受控值喂 TextField，异步写-回流滞后于 IME，逐字符被回退、光标漂移（真机实测）。
    // remember(value)：模型变化（提交成功/清除）时本地随之归位；非法中间态只留本地不动模型。
    var text by remember(value) { mutableStateOf(value ?: "") }
    val parsed = text.toComposeColorOrNull()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
        ) {
            drawCircle(color = parsed ?: fallbackColor)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                when {
                    input.isBlank() -> onValueChange(null)
                    input.toComposeColorOrNull() != null -> onValueChange(input)
                    // 非法中间态：仅本地显示错误样式，不动已提交值
                }
            },
            modifier = Modifier.weight(1.4f),
            singleLine = true,
            placeholder = { Text("#RRGGBB") },
            isError = text.isNotBlank() && parsed == null,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        if (value != null) {
            TextButton(onClick = { onValueChange(null) }) {
                Text(stringResource(R.string.setting_theme_page_role_clear))
            }
        }
    }
}

@Composable
internal fun ColorPickerRow(
    color: Color,
    onColorChange: (Color) -> Unit,
) {
    val hsl = remember(color) {
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    }
    var hue by remember(color) { mutableFloatStateOf(hsl[0]) }
    var saturation by remember(color) { mutableFloatStateOf(hsl[1]) }
    var lightness by remember(color) { mutableFloatStateOf(hsl[2]) }
    var hslCode by remember(color) { mutableStateOf(formatHslCode(hsl[0], hsl[1], hsl[2])) }
    var hslCodeError by remember(color) { mutableStateOf(false) }
    var hexCode by remember(color) { mutableStateOf(color.toHexCode()) }
    var hexCodeError by remember(color) { mutableStateOf(false) }

    fun updateColor(newHue: Float, newSaturation: Float, newLightness: Float) {
        hue = newHue
        saturation = newSaturation
        lightness = newLightness
        hslCode = formatHslCode(newHue, newSaturation, newLightness)
        hslCodeError = false
        onColorChange(Color(ColorUtils.HSLToColor(floatArrayOf(newHue, newSaturation, newLightness))))
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                drawCircle(color = color)
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("H", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = hue,
                        onValueChange = {
                            updateColor(it, saturation, lightness)
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = saturation,
                        onValueChange = {
                            updateColor(hue, it, lightness)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("L", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp))
                    Slider(
                        value = lightness,
                        onValueChange = {
                            updateColor(hue, saturation, it)
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = hslCode,
                onValueChange = { value ->
                    hslCode = value
                    val parsedHsl = parseHslCode(value)
                    hslCodeError = parsedHsl == null
                    if (parsedHsl != null) {
                        hue = parsedHsl[0]
                        saturation = parsedHsl[1]
                        lightness = parsedHsl[2]
                        onColorChange(Color(ColorUtils.HSLToColor(parsedHsl)))
                    }
                },
                label = { Text("HSL") },
                placeholder = { Text("hsl(267 36% 48%)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = hslCodeError,
                supportingText = if (hslCodeError) {
                    { Text("Use hsl(267 36% 48%)") }
                } else {
                    null
                },
            )
            OutlinedTextField(
                value = hexCode,
                onValueChange = { value ->
                    hexCode = value
                    val parsedHex = value.toComposeColorOrNull()
                    hexCodeError = parsedHex == null
                    if (parsedHex != null) {
                        val parsedHsl = FloatArray(3).also { ColorUtils.colorToHSL(parsedHex.toArgb(), it) }
                        hue = parsedHsl[0]
                        saturation = parsedHsl[1]
                        lightness = parsedHsl[2]
                        onColorChange(parsedHex)
                    }
                },
                label = { Text("HEX") },
                placeholder = { Text("#6750A4") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = hexCodeError,
                supportingText = if (hexCodeError) {
                    { Text("Use #RRGGBB") }
                } else {
                    null
                },
            )
        }
    }
}

private val hslNumberRegex = Regex("""[-+]?\d*\.?\d+""")

private fun parseHslCode(value: String): FloatArray? {
    val values = buildList {
        for (match in hslNumberRegex.findAll(value)) {
            add(match.value.toFloatOrNull() ?: return null)
            if (size == 3) break
        }
    }

    if (values.size != 3) return null

    val hue = values[0].coerceIn(0f, 360f)
    val saturation = parseHslPercentOrFraction(values[1]) ?: return null
    val lightness = parseHslPercentOrFraction(values[2]) ?: return null

    return floatArrayOf(hue, saturation, lightness)
}

private fun parseHslPercentOrFraction(value: Float): Float? {
    if (!value.isFinite()) return null
    return if (value > 1f) {
        (value / 100f).coerceIn(0f, 1f)
    } else {
        value.coerceIn(0f, 1f)
    }
}

private fun formatHslCode(hue: Float, saturation: Float, lightness: Float): String {
    return "hsl(${hue.roundToInt()} ${(saturation * 100).roundToInt()}% ${(lightness * 100).roundToInt()}%)"
}

@Composable
private fun ThemePreview(theme: CustomTheme) {
    val darkMode = LocalDarkMode.current
    val scheme = theme.generateColorScheme(darkMode)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_theme_page_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(appSkinRoundedShape(16f))
                .background(scheme.surface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ColorSwatch(scheme.primary, "P")
            ColorSwatch(scheme.secondary, "S")
            ColorSwatch(scheme.tertiary, "T")
            ColorSwatch(scheme.primaryContainer, "PC")
            ColorSwatch(scheme.secondaryContainer, "SC")
            ColorSwatch(scheme.surface, "Sf")
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
        ) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
