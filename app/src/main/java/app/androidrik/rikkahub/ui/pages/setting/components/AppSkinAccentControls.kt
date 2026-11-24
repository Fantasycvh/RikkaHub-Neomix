package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.AppSkinHero
import app.androidrik.rikkahub.data.model.AppSkinIconTile
import app.androidrik.rikkahub.data.model.AppSkinShapes
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.data.model.GradientDirection
import app.androidrik.rikkahub.ui.components.ui.AppSkinHeroCard
import app.androidrik.rikkahub.ui.components.ui.AppSkinListItemIcon
import app.androidrik.rikkahub.ui.components.ui.AppTextButton
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.toHexCode
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.Lucide
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Time02

@Composable
internal fun SkinIconTileControls(shapes: AppSkinShapes, onChange: (AppSkinShapes) -> Unit) {
    val tile = shapes.iconTile
    val preview = LocalAppSkin.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalAppSkin provides preview.copy(skin = preview.skin.copy(shapes = shapes))) {
            AppSkinListItemIcon(Lucide.BellRing, contentDescription = null)
        }
        SkinEditorToggle(
            label = stringResource(R.string.setting_skin_icon_tile),
            checked = tile != null,
            onCheckedChange = { onChange(shapes.copy(iconTile = if (it) AppSkinIconTile() else null)) },
            modifier = Modifier.weight(1f),
        )
    }
    if (tile == null) return
    SkinSlider(stringResource(R.string.setting_skin_icon_tile_size), tile.sizeDp, 32f..56f) {
        onChange(shapes.copy(iconTile = tile.copy(sizeDp = it).normalized()))
    }
    SkinSlider(stringResource(R.string.setting_skin_icon_tile_radius), tile.radiusDp, 0f..tile.sizeDp / 2f) {
        onChange(shapes.copy(iconTile = tile.copy(radiusDp = it)))
    }
    SkinSlider(stringResource(R.string.setting_skin_icon_tile_icon_size), tile.iconSizeDp, 16f..28f) {
        onChange(shapes.copy(iconTile = tile.copy(iconSizeDp = it)))
    }
}

@Composable
internal fun SkinHeroControls(hero: AppSkinHero, continuous: Boolean, onChange: (AppSkinHero) -> Unit) {
    Text(stringResource(R.string.setting_skin_hero), style = MaterialTheme.typography.titleMedium)
    SkinEditorToggle(stringResource(R.string.setting_skin_hero_visible), hero.visible) {
        onChange(hero.copy(visible = it))
    }

    if (hero.visible) {
        val inherited = LocalAppSkin.current
        val skin = inherited.skin.copy(hero = hero.normalized(), shapes = inherited.skin.shapes.copy(continuous = continuous))
        CompositionLocalProvider(LocalAppSkin provides inherited.copy(skin = skin)) {
            AppSkinHeroCard(
                title = stringResource(R.string.setting_page_feature_shortcut_title),
                description = stringResource(R.string.setting_page_feature_shortcut_desc),
                icon = HugeIcons.Time02,
                onClick = null,
            )
        }
        if (hero.lightGradient == null && hero.darkGradient == null) {
            Text(stringResource(R.string.setting_skin_hero_needs_gradient), color = MaterialTheme.colorScheme.error)
        }
    }
    if (hero.visible || hero.lightGradient != null || hero.darkGradient != null) {
        SkinHeroGradientEditor(stringResource(R.string.setting_skin_hero_light_gradient), hero.lightGradient) {
            onChange(hero.copy(lightGradient = it))
        }
        SkinHeroGradientEditor(stringResource(R.string.setting_skin_hero_dark_gradient), hero.darkGradient) {
            onChange(hero.copy(darkGradient = it))
        }
    }
    if (hero.visible) {
        SkinSlider(stringResource(R.string.setting_skin_hero_radius), hero.radiusDp, 0f..48f) {
            onChange(hero.copy(radiusDp = it))
        }
        SkinSlider(stringResource(R.string.setting_skin_hero_horizontal_padding), hero.paddingHorizontalDp, 0f..32f) {
            onChange(hero.copy(paddingHorizontalDp = it))
        }
        SkinSlider(stringResource(R.string.setting_skin_hero_vertical_padding), hero.paddingVerticalDp, 0f..24f) {
            onChange(hero.copy(paddingVerticalDp = it))
        }
        SkinSlider(stringResource(R.string.setting_skin_hero_decoration_opacity), hero.decorationOpacity, 0f..0.2f) {
            onChange(hero.copy(decorationOpacity = it))
        }
    }
    if (hero != AppSkinHero()) {
        AppTextButton(onClick = { onChange(AppSkinHero()) }) { Text(stringResource(R.string.setting_skin_hero_reset)) }
    }
}

@Composable
private fun SkinHeroGradientEditor(label: String, gradient: AppearanceGradient?, onChange: (AppearanceGradient?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SkinEditorToggle(label, gradient != null) { enabled ->
        onChange(if (enabled) AppearanceGradient(listOf(scheme.secondaryContainer.toHexCode(), scheme.tertiaryContainer.toHexCode())) else null)
    }
    if (gradient == null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        gradient.colors.take(3).forEachIndexed { index, color ->
            AppearanceColorField(stringResource(R.string.setting_skin_hero_color_stop, index + 1), color, allowEmpty = false) { hex ->
                val colors = gradient.colors.toMutableList()
                colors[index] = hex.orEmpty()
                onChange(AppearanceGradient(colors, GradientDirection.DIAGONAL))
            }
        }
        if (gradient.colors.size <= 3) {
            SkinEditorToggle(stringResource(R.string.setting_skin_hero_third_color), gradient.colors.size == 3) { add ->
                val colors = if (add) gradient.colors + scheme.primaryContainer.toHexCode() else gradient.colors.take(2)
                onChange(AppearanceGradient(colors, GradientDirection.DIAGONAL))
            }
        } else {
            Text(stringResource(R.string.setting_skin_hero_extra_stops), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun AppSkinHero.isValidForEditor(): Boolean {
    val gradients = listOfNotNull(lightGradient, darkGradient)
    return (!visible || gradients.isNotEmpty()) && gradients.all { it.normalizedOrNull() == it }
}

@Composable
private fun SkinEditorToggle(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
