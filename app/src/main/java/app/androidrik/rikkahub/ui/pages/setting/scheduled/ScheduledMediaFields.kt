package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.net.Uri
import app.androidrik.mediagen.model.ImageRole
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.withRequired
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaOperation
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaModelPicker
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaThumbnail
import app.androidrik.rikkahub.ui.pages.mediacreation.presets
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import java.io.File
import kotlin.math.roundToInt

/**
 * 媒体模板每轮最多锁几张素材：图片编辑的参考图上限；首帧视频只需要一张。
 */
private const val MAX_TEMPLATE_ASSETS = 4

internal fun ScheduledTaskFormState.mediaAction(
    catalog: List<MediaModelOption>,
    uploadFingerprint: String?,
): ScheduledMediaAction? {
    if (!authorizeMedia) return null
    val option = catalog.firstOrNull { it.ref == mediaModel } ?: return null
    val fingerprint = mediaConfigurationFingerprint ?: return null
    val params = MediaCreationParams(
        count = mediaCount.takeIf { option.model.kind == MediaKind.IMAGE },
        resolution = mediaResolution,
    ).withRequired(option.capabilities, option.presets().defaults)
    return ScheduledMediaAction(
        model = option.ref,
        params = params,
        configurationFingerprint = fingerprint,
        templatePrompt = prompt.trim().takeIf { mediaTemplate },
        operation = if (mediaTemplate) mediaOperation else ScheduledMediaOperation.TEXT,
        templateAssets = if (mediaTemplate) mediaAssets else emptyList(),
        uploadFingerprint = if (mediaTemplate && mediaOperation != ScheduledMediaOperation.TEXT) uploadFingerprint else null,
    ).takeIf { it.permits(option) }
}

@Composable
internal fun ScheduledMediaFields(
    form: ScheduledTaskFormState,
    catalog: List<MediaModelOption>,
    onImportAssets: (List<Uri>) -> Unit,
    uploadFingerprint: String?,
) {
    val nav = LocalNavController.current
    var picking by remember { mutableStateOf(false) }
    val candidates = catalog.filter { it.visible && it.permitsBoundedCount && acceptsOperation(it, form) }
    val selected = catalog.firstOrNull { it.ref == form.mediaModel }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onImportAssets(listOfNotNull(uri))
    }
    val imagesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_TEMPLATE_ASSETS)
    ) { uris -> onImportAssets(uris) }

    if (!form.mediaTemplate) {
        FormItem(
            label = { Text("Allow scheduled image generation") },
            tail = {
                Switch(checked = form.authorizeMedia, onCheckedChange = {
                    form.authorizeMedia = it
                    form.mediaConfigurationFingerprint = if (it) selected?.authorizationFingerprint() else null
                })
            },
        )
        if (!form.authorizeMedia) return
    }
    if (form.mediaTemplate) {
        // 生成操作决定模型范围和素材要求，切换后原有选择和授权都不再成立
        FormItem(label = { Text("Generation operation") }) {
            SegmentedPill(
                options = ScheduledMediaOperation.entries,
                selectedOption = form.mediaOperation,
                onOptionSelected = { operation ->
                    if (form.mediaOperation != operation) {
                        form.mediaOperation = operation
                        form.mediaModel = null
                        form.mediaAssets = emptyList()
                        form.mediaResolution = null
                        form.authorizeMedia = false
                        form.mediaConfigurationFingerprint = null
                    }
                },
                optionToString = {
                    when (it) {
                        ScheduledMediaOperation.TEXT -> "Text prompt"
                        ScheduledMediaOperation.IMAGE_EDIT -> "Image edit"
                        ScheduledMediaOperation.FIRST_FRAME -> "First-frame video"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.mediaOperation != ScheduledMediaOperation.TEXT) {
            TemplateAssetRow(
                form = form,
                allowMultiple = form.mediaOperation == ScheduledMediaOperation.IMAGE_EDIT,
                onPick = {
                    // 编辑操作一次可以选多张参考图，首帧固定一张
                    if (form.mediaOperation == ScheduledMediaOperation.IMAGE_EDIT) {
                        imagesPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
            )
        }
    }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { "${it.name} · ${it.providerName}" } ?: "Select a media model")
    }
    selected?.let { model ->
        if (model.model.kind == MediaKind.IMAGE) {
            FormItem(label = { Text(if (form.mediaTemplate) "Images per run" else "Maximum images per run") }) {
                androidx.compose.material3.Slider(
                    value = form.mediaCount.toFloat(),
                    onValueChange = {
                        val count = it.roundToInt()
                        if (form.mediaCount != count) {
                            if (form.mediaTemplate) form.authorizeMedia = false
                            form.mediaCount = count
                        }
                    },
                    valueRange = 1f..4f, steps = 2,
                )
                Text(form.mediaCount.toString())
            }
        }
        val resolutions = model.presets().resolutions
        if (resolutions.isNotEmpty()) {
            OutlinedButton(onClick = {
                val choices = if (model.model.kind == MediaKind.IMAGE) listOf(null) + resolutions else resolutions
                form.mediaResolution = choices[(choices.indexOf(form.mediaResolution) + 1) % choices.size]
                if (form.mediaTemplate) form.authorizeMedia = false
            }, modifier = Modifier.fillMaxWidth()) {
                Text(form.mediaResolution ?: "Default resolution")
            }
        }
    }
    if (form.mediaTemplate) {
        FormItem(
            label = { Text("Authorize one generation per scheduled run") },
            tail = {
                Switch(checked = form.authorizeMedia, onCheckedChange = {
                    form.authorizeMedia = it
                    form.mediaConfigurationFingerprint = if (it) selected?.authorizationFingerprint() else null
                })
            },
        )
    }
    if (selected != null && form.authorizeMedia &&
        form.mediaConfigurationFingerprint != selected.authorizationFingerprint()
    ) {
        Text("Model configuration changed. Turn authorization off and on to approve it again.")
    }
    if (picking) {
        MediaModelPicker(
            models = candidates,
            selectedModelId = form.mediaModel?.id,
            onSelect = { option ->
                form.mediaModel = option.ref
                form.mediaResolution = null
                if (form.mediaTemplate) form.authorizeMedia = false
                form.mediaConfigurationFingerprint = if (form.authorizeMedia) option.authorizationFingerprint() else null
                picking = false
            },
            onManage = { picking = false; nav.navigate(Screen.SettingMedia) },
            onDismiss = { picking = false },
        )
    }
}

// TEXT 仍是纯文本门控；带素材的操作按能力挑模型
private fun acceptsOperation(option: MediaModelOption, form: ScheduledTaskFormState): Boolean =
    if (!form.mediaTemplate) {
        option.model.kind == MediaKind.IMAGE
    } else {
        when (form.mediaOperation) {
            ScheduledMediaOperation.TEXT -> option.supportsTextTemplate
            ScheduledMediaOperation.IMAGE_EDIT ->
                option.model.kind == MediaKind.IMAGE && option.capabilities.imageRoles.isNotEmpty()
            ScheduledMediaOperation.FIRST_FRAME ->
                option.model.kind == MediaKind.VIDEO && ImageRole.FIRST_FRAME in option.capabilities.imageRoles
        }
    }

/** 模板素材行：已锁定素材的缩略图 + 添加入口。FIRST_FRAME 只允许一张。 */
@Composable
private fun TemplateAssetRow(
    form: ScheduledTaskFormState,
    allowMultiple: Boolean,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val maxAssets = if (allowMultiple) MAX_TEMPLATE_ASSETS else 1
    FormItem(label = { Text("Template assets") }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(form.mediaAssets, key = { it.path }) { asset ->
                    Box {
                        MediaThumbnail(
                            file = File(context.filesDir, asset.path),
                            isVideo = false,
                            modifier = Modifier.size(64.dp),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable {
                                    form.mediaAssets = form.mediaAssets.filterNot { it.path == asset.path }
                                    if (form.mediaTemplate) form.authorizeMedia = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = onPick, enabled = form.mediaAssets.size < maxAssets) {
                Text("Add")
            }
        }
        val hint = when {
            form.mediaOperation == ScheduledMediaOperation.FIRST_FRAME && form.mediaAssets.isEmpty() ->
                "Add a first-frame image to authorize first-frame video."

            form.mediaOperation == ScheduledMediaOperation.IMAGE_EDIT && form.mediaAssets.isEmpty() ->
                "Add at least one reference image to authorize image edit."

            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
