package app.androidrik.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.hooks.rememberAmoledDarkMode
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesThemePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var amoledDarkMode by rememberAmoledDarkMode()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    var showSeedDialog by remember { mutableStateOf(false) }
    // 动态取色实际生效条件（种子色入口只在生效时出现，Android 12 以下动态取色本来就不工作）
    val dynamicColorActive = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_theme))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_dynamic_color_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                            )
                        },
                    )
                    if (dynamicColorActive) {
                        item(
                            onClick = { showSeedDialog = true },
                            headlineContent = { Text(stringResource(R.string.setting_page_dynamic_color_seed)) },
                            supportingContent = {
                                Text(
                                    text = settings.dynamicColorSeedColor
                                        ?.let { "#%06X".format(it.toInt() and 0xFFFFFF) }
                                        ?: stringResource(R.string.setting_page_dynamic_color_seed_follow)
                                )
                            },
                            trailingContent = {
                                val seed = settings.dynamicColorSeedColor
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            seed?.let { Color(it.toInt()) }
                                                ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        )
                                )
                            },
                        )
                    }
                    item(
                        onClick = { navController.navigate(Screen.SettingTheme) },
                        headlineContent = { Text(stringResource(R.string.setting_page_theme_setting)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_theme_setting_desc)) },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_amoled_dark_mode_desc)) },
                        trailingContent = {
                            Switch(
                                checked = amoledDarkMode,
                                onCheckedChange = { amoledDarkMode = it }
                            )
                        },
                    )
                }
            }
        }
    }

    if (showSeedDialog) {
        // 草稿从当前设置播种；ColorPickerRow 拖动即改草稿，「确认」才落库，「跟随壁纸」清空
        var seedDraft by remember(showSeedDialog) {
            mutableStateOf(settings.dynamicColorSeedColor?.let { Color(it.toInt()) })
        }
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text(stringResource(R.string.setting_page_dynamic_color_seed)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = seedDraft
                            ?.let { "#%06X".format(it.toArgb() and 0xFFFFFF) }
                            ?: stringResource(R.string.setting_page_dynamic_color_seed_follow),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ColorPickerRow(
                        color = seedDraft ?: Color(0xFF6750A4),
                        onColorChange = { seedDraft = it },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings(
                        settings.copy(
                            dynamicColorSeedColor = seedDraft
                                ?.toArgb()
                                ?.toLong()
                                ?.and(0xFFFFFFFFL)
                        )
                    )
                    showSeedDialog = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.updateSettings(settings.copy(dynamicColorSeedColor = null))
                    showSeedDialog = false
                }) { Text(stringResource(R.string.setting_page_dynamic_color_seed_follow)) }
            },
        )
    }
}
