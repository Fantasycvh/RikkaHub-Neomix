package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.WCAG_AA_CONTRAST
import app.androidrik.rikkahub.ui.theme.shapeForRadius
import app.androidrik.rikkahub.ui.theme.toBrush
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import app.androidrik.rikkahub.ui.theme.wcagContrastRatio
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

@Composable
fun AppSkinHeroCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalAppSkin.current.skin
    val hero = skin.hero
    val gradient = hero.gradientFor(LocalDarkMode.current) ?: return
    val colors = remember(gradient) { gradient.colors.map { requireNotNull(it.toComposeColorOrNull()) } }
    val brush = remember(gradient.direction, colors) { gradient.direction.toBrush(colors) }
    val decoration = Color.White.copy(alpha = hero.decorationOpacity)
    val scheme = MaterialTheme.colorScheme
    val foreground = remember(colors, decoration, scheme.onSurface, scheme.onSurfaceVariant) {
        appSkinHeroForeground(colors, decoration, scheme.onSurface, scheme.onSurfaceVariant)
    }
    val shape = skin.shapes.shapeForRadius(hero.radiusDp)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(brush)
            .drawWithCache {
                val largeCircle = Offset(if (rtl) 48.dp.toPx() else size.width - 48.dp.toPx(), 16.dp.toPx())
                val smallCircle = Offset(if (rtl) 88.dp.toPx() else size.width - 88.dp.toPx(), 36.dp.toPx())
                val largeRadius = 90.dp.toPx()
                val smallRadius = 54.dp.toPx()
                onDrawBehind {
                    if (decoration.alpha > 0f) {
                        drawCircle(decoration, largeRadius, largeCircle)
                        drawCircle(decoration, smallRadius, smallCircle)
                    }
                    if (foreground.scrim.alpha > 0f) drawRect(foreground.scrim)
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = hero.paddingHorizontalDp.dp, vertical = hero.paddingVerticalDp.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground.title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = foreground.title, style = MaterialTheme.typography.titleLarge)
            Text(description, color = foreground.description, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(
            Lucide.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp).rotate(if (rtl) 180f else 0f),
            tint = foreground.title,
        )
    }
}

internal data class AppSkinHeroForeground(
    val title: Color,
    val description: Color,
    val scrim: Color = Color.Transparent,
)

internal fun appSkinHeroForeground(
    stops: List<Color>,
    decoration: Color,
    title: Color,
    description: Color,
): AppSkinHeroForeground {
    // Per-channel bounds cover interpolated colors, even between stops of different hues.
    val darkest = Color(stops.minOf { it.red }, stops.minOf { it.green }, stops.minOf { it.blue })
    val brightestStop = Color(stops.maxOf { it.red }, stops.maxOf { it.green }, stops.maxOf { it.blue })
    val brightest = decoration.compositeOver(decoration.compositeOver(brightestStop))
    val lowestLuminance = darkest.luminance()
    val highestLuminance = brightest.luminance()

    fun readable(ink: Color): Boolean {
        if (ink.alpha < 1f) return false
        val luminance = ink.luminance()
        val nearestBackground = when {
            luminance <= lowestLuminance -> darkest
            luminance >= highestLuminance -> brightest
            else -> return false
        }
        return wcagContrastRatio(ink, nearestBackground) >= WCAG_AA_CONTRAST
    }

    if (readable(title) && readable(description)) return AppSkinHeroForeground(title, description)

    val fallback = when {
        readable(Color.White) -> Color.White
        readable(Color.Black) -> Color.Black
        // This alpha keeps white text above AA even over white after both decorations.
        else -> return AppSkinHeroForeground(Color.White, Color.White, scrim = Color.Black.copy(alpha = 0.56f))
    }
    return AppSkinHeroForeground(
        title = title.takeIf(::readable) ?: fallback,
        description = description.takeIf(::readable) ?: fallback,
    )
}
