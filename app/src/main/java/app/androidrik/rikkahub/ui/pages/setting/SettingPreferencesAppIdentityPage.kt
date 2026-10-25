package app.androidrik.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick02
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ShortcutEntryActivity
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.LauncherAlias
import app.androidrik.rikkahub.utils.plus
import com.dokar.sonner.ToastType
import org.koin.androidx.compose.koinViewModel

/** 固定 id：重复创建时更新同一个已 pin 的快捷方式，而不是往桌面堆图标 */
private const val TAG = "AppIdentity"
private const val SHORTCUT_ID = "custom_app_entry"

/** 预置图标的预览（仅新图标是矢量；default/rikkahub 用 PNG 应用图标本身） */
private val variantPreviewColor = mapOf(
    "chat" to Color(0xFF4C8DF6),
    "notes" to Color(0xFF3FA96A),
    "assistant" to Color(0xFFA15FD6),
)
private val variantPreviewFg = mapOf(
    "chat" to R.drawable.ic_launcher_chat_fg,
    "notes" to R.drawable.ic_launcher_notes_fg,
    "assistant" to R.drawable.ic_launcher_assistant_fg,
)

@Composable
fun SettingPreferencesAppIdentityPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var showShortcutDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_app_identity))
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
                        headlineContent = { Text(stringResource(R.string.setting_page_app_identity_display_name)) },
                        supportingContent = {
                            Text(
                                displaySetting.customAppName.ifBlank {
                                    stringResource(R.string.setting_page_app_identity_display_name_desc)
                                }
                            )
                        },
                        trailingContent = { Text(stringResource(R.string.setting_page_app_identity_edit)) },
                        onClick = { showNameDialog = true },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_app_identity_launcher)) },
                ) {
                    LauncherAlias.variants.forEach { variant ->
                        item(
                            headlineContent = { Text(stringResource(variant.labelRes)) },
                            supportingContent = {
                                if (variant.id == LauncherAlias.DEFAULT_ID) {
                                    Text(stringResource(R.string.setting_page_app_identity_launcher_default))
                                }
                            },
                            leadingContent = { VariantPreviewIcon(variant) },
                            trailingContent = {
                                RadioButton(
                                    selected = displaySetting.launcherAliasId == variant.id,
                                    onClick = null,
                                )
                            },
                            onClick = {
                                if (displaySetting.launcherAliasId != variant.id) {
                                    // 前台直接切换（legado-with-MD3 同款做法，真实 Activity
                                    // 不会被 ROM 的组件开关 hook 拦）；RouteActivity 在
                                    // ON_STOP 还有一次幂等同步兜底，失败也会自愈
                                    runCatching { LauncherAlias.apply(context, variant.id) }
                                        .onFailure { Log.e(TAG, "apply variant failed", it) }
                                    updateDisplaySetting(displaySetting.copy(launcherAliasId = variant.id))
                                    toaster.show(message = context.getString(R.string.setting_page_app_identity_launcher_applied))
                                }
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_app_identity_launcher_note)) },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_app_identity_shortcut)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_app_identity_shortcut)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_app_identity_shortcut_desc)) },
                        onClick = { showShortcutDialog = true },
                    )
                }
            }
        }
    }

    if (showNameDialog) {
        DisplayNameDialog(
            initial = displaySetting.customAppName,
            onConfirm = { name ->
                updateDisplaySetting(displaySetting.copy(customAppName = name.trim()))
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false },
        )
    }

    if (showShortcutDialog) {
        ShortcutDialog(
            onConfirm = { name, iconUri ->
                showShortcutDialog = false
                val result = runCatching { createHomeScreenShortcut(context, name, iconUri) }
                result.exceptionOrNull()?.let {
                    Log.e(TAG, "create shortcut failed", it)
                }
                val ok = result.getOrDefault(false)
                toaster.show(
                    message = context.getString(
                        if (ok) R.string.setting_page_app_identity_shortcut_created
                        else R.string.setting_page_app_identity_shortcut_failed
                    ),
                    type = if (ok) ToastType.Success else ToastType.Error,
                )
            },
            onDismiss = { showShortcutDialog = false },
        )
    }
}

@Composable
private fun VariantPreviewIcon(variant: LauncherAlias.Variant) {
    val color = variantPreviewColor[variant.id]
    val fg = variantPreviewFg[variant.id]
    if (color != null && fg != null) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(fg),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        AsyncImage(
            model = variant.iconRes,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun DisplayNameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_app_identity_display_name)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                supportingText = { Text(stringResource(R.string.setting_page_app_identity_display_name_desc)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ShortcutDialog(
    onConfirm: (name: String, iconUri: Uri?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var iconUri by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        Log.d(TAG, "picker returned uri=$uri")
        if (uri != null) iconUri = uri
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_page_app_identity_shortcut)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_page_app_identity_shortcut_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AsyncImage(
                        model = iconUri ?: R.mipmap.ic_launcher,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    OutlinedButton(
                        onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    ) {
                        Text(stringResource(R.string.setting_page_app_identity_shortcut_pick))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, iconUri) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * Pin 一个自定义「名称 + 图标」的桌面快捷方式（唯一能接受用户任意图片的入口）。
 * 图标未选时用当前应用图标渲染成位图。id 固定：已 pin 时走 update 而非再次请求。
 */
private fun createHomeScreenShortcut(context: Context, name: String, iconUri: Uri?): Boolean {
    val bitmap = iconUri?.let { decodeSquareBitmap(context, it) } ?: run {
        Log.d(TAG, "icon: fallback to app icon (uri=$iconUri, decode failed or no uri)")
        renderAppIconBitmap(context)
    }
    val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
        .setShortLabel(name)
        // 用户自选图片用普通位图：createWithAdaptiveBitmap 会被桌面按自适应规范
        // 裁掉四周约 1/3 并加遮罩，照片几乎不可见（实测像被丢弃）
        .setIcon(IconCompat.createWithBitmap(bitmap))
        // 指向永不参与预置启停的中转入口：直接指向 RouteActivity 的话，
        // 切换预置后 RouteActivity 被禁用，快捷方式就打不开了
        .setIntent(Intent(context, ShortcutEntryActivity::class.java).setAction(Intent.ACTION_VIEW))
        .build()
    val pinned = ShortcutManagerCompat.getShortcuts(
        context,
        ShortcutManagerCompat.FLAG_MATCH_PINNED,
    ).any { it.id == SHORTCUT_ID }
    return if (pinned) {
        Log.d(TAG, "shortcut exists, updating")
        ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
    } else {
        val supported = ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        Log.d(TAG, "request pin: supported=$supported")
        supported && ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }
}

private fun decodeSquareBitmap(context: Context, uri: Uri, size: Int = 432): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    // 注意：inJustDecodeBounds=true 时 decodeStream 恒返回 null，成败只能看 outWidth；
    // 之前误把返回值当成功标志 ⇒ 任何图片都被判失败，永远回退默认应用图标
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.d(TAG, "decode bounds failed uri=$uri")
        return null
    }
    val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / size)
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    if (src == null) {
        Log.d(TAG, "decode second pass failed uri=$uri")
        return null
    }
    val side = minOf(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
    val result = if (side != size) Bitmap.createScaledBitmap(square, size, size, true) else square
    Log.d(TAG, "decode ok src=${src.width}x${src.height} result=${result.width}x${result.height}")
    return result
}

private fun renderAppIconBitmap(context: Context): Bitmap {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, 432, 432)
    drawable.draw(Canvas(bitmap))
    return bitmap
}
