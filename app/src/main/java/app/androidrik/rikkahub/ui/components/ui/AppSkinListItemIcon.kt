package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.androidrik.rikkahub.ui.theme.LocalAppSkin

@Composable
fun AppSkinListItemIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (LocalAppSkin.current.skin.shapes.iconTile == null) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = modifier)
    } else {
        CardGroupIcon(icon = icon, modifier = modifier, contentDescription = contentDescription)
    }
}
