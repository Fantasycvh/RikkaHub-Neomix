package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.model.MediaCreationParams
import app.androidrik.rikkahub.data.model.withRequired
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaModelPicker
import app.androidrik.rikkahub.ui.pages.mediacreation.presets
import kotlin.math.roundToInt

internal fun ScheduledTaskFormState.mediaAction(catalog: List<MediaModelOption>): ScheduledMediaAction? {
    if (!authorizeMedia) return null
    val option = catalog.firstOrNull { it.ref == mediaModel } ?: return null
    val fingerprint = mediaConfigurationFingerprint ?: return null
    val params = MediaCreationParams(
        count = mediaCount.takeIf { option.model.kind == MediaKind.IMAGE },
        resolution = mediaResolution,
    ).withRequired(option.capabilities, option.presets().defaults)
    return ScheduledMediaAction(option.ref, params, fingerprint, templatePrompt = prompt.trim().takeIf { mediaTemplate })
        .takeIf { it.permits(option) }
}

@Composable
internal fun ScheduledMediaFields(form: ScheduledTaskFormState, catalog: List<MediaModelOption>) {
    val nav = LocalNavController.current
    var picking by remember { mutableStateOf(false) }
    val candidates = catalog.filter {
        it.visible && it.permitsBoundedCount &&
            if (form.mediaTemplate) it.supportsTextTemplate else it.model.kind == MediaKind.IMAGE
    }
    val selected = catalog.firstOrNull { it.ref == form.mediaModel }
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
