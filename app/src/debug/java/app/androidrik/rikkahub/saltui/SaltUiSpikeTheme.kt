package app.androidrik.rikkahub.saltui

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import com.moriafly.salt.ui.Item
import com.moriafly.salt.ui.ItemDivider
import com.moriafly.salt.ui.ItemSwitcher
import com.moriafly.salt.ui.SaltColors
import com.moriafly.salt.ui.SaltConfigs
import com.moriafly.salt.ui.SaltDimens
import com.moriafly.salt.ui.SaltDynamicColors
import com.moriafly.salt.ui.SaltShapes
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.saltTextStyles

@Composable
internal fun SaltUiSpikeTheme(light: ColorScheme, dark: ColorScheme, content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    val spacing = LocalAppSkin.current.skin.spacing
    // This fixture has no parent SaltTheme. The stable overload inherits MaterialType.None.
    SaltTheme(
        configs = SaltConfigs.default(LocalDarkMode.current, LocalIndication.current),
        dynamicColors = SaltDynamicColors(light.toSaltColors(), dark.toSaltColors()),
        textStyles = saltTextStyles(
            main = typography.bodyLarge,
            sub = typography.bodySmall,
            paragraph = typography.bodyMedium,
            largeTitle = typography.headlineSmall,
        ),
        dimens = SaltDimens.default(item = 56.dp, itemIcon = 24.dp, padding = spacing.formHorizontalDp.dp, subPadding = 12.dp),
        shapes = SaltShapes(shapes.small, shapes.medium, shapes.large),
        content = content,
    )
}

private fun ColorScheme.toSaltColors() = SaltColors.defaultLight(
    highlight = primary,
    text = onSurface,
    subText = onSurfaceVariant,
    background = background,
    subBackground = surfaceContainerLow,
    popup = surfaceContainerHigh,
    stroke = outlineVariant,
    onHighlight = onPrimary,
    success = primary,
    warning = tertiary,
    error = error,
)

@Composable
internal fun SaltSpikeItem(
    title: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Item(
        text = title,
        sub = detail,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
    )
}

@Composable
internal fun SaltSpikeSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ItemSwitcher(
        state = checked,
        onChange = onCheckedChange,
        text = title,
        sub = detail,
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
internal fun SaltSpikeDivider(indent: Dp) {
    ItemDivider(startIndent = indent)
}
