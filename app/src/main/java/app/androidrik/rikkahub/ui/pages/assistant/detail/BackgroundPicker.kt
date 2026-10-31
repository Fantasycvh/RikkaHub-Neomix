package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.components.ai.useCropLauncher
import app.androidrik.rikkahub.ui.components.ui.FormItem
import org.koin.compose.koinInject

@Composable
fun BackgroundPicker(
    modifier: Modifier = Modifier,
    background: String?,
    backgroundOpacity: Float = 1.0f,
    onUpdate: (String?) -> Unit
) {
    val filesManager: FilesManager = koinInject()
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    // 背景裁剪按**设备屏幕比例**锁框：裁出来的就是全屏会显示的部分（ContentScale.Crop 下
    // 其余像素本来就被裁掉）；长边上限 = 屏幕长边，4K 原图由此降采样成 1:1 像素的 JPEG，
    // 避免每帧解码一张远超屏幕分辨率的位图
    val metrics = LocalContext.current.resources.displayMetrics
    val screenRatio = metrics.widthPixels.toFloat() / metrics.heightPixels
    val screenLongEdge = maxOf(metrics.widthPixels, metrics.heightPixels)
    val launchBackgroundCrop = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            // 背景图与头像一样是**持久配置**，落 backgrounds/ 专用目录而非 upload/——
            // 否则「设置 → 文件管理」清理聊天文件会把正在使用的背景一并删掉（#2127 同款坑）。
            // 失败时保持原背景不变（与头像静默行为一致）。
            val localUri = filesManager.createBackgroundImage(croppedUri)
            if (localUri != null) {
                onUpdate(localUri.toString())
            }
        },
        aspectRatio = screenRatio,
        maxResultSize = screenLongEdge,
    )

    // GetContent 而非 PickVisualMedia：保留文件浏览入口（同 UIAvatar 的说明——
    // 系统 Photo Picker 无目录浏览、照片多时翻找慢；上游 4b36640a 亦已 revert 回此形态）
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // 与头像同款归一化：先进程内裁剪（HEIF/EXIF 原生支持），再落盘
        uri?.let(launchBackgroundCrop)
    }

    val previewOpacity = backgroundOpacity.coerceIn(0f, 1f)

    FormItem(
        modifier = modifier,
        label = {
            Text(stringResource(R.string.assistant_page_chat_background))
        },
        description = {
            Text(stringResource(R.string.assistant_page_chat_background_desc))
        }
    ) {
        Button(
            onClick = {
                showPickOption = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (background != null) {
                    stringResource(R.string.assistant_page_change_background)
                } else {
                    stringResource(R.string.assistant_page_select_background)
                }
            )
        }

        if (background != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_background_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        onUpdate(null)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_remove))
                }
            }

            AsyncImage(
                model = background,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(previewOpacity)
            )
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_from_gallery))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_enter_image_url))
                    }
                    if (background != null) {
                        Button(
                            onClick = {
                                showPickOption = false
                                onUpdate(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.assistant_page_remove_background))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/image.jpg") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdate(urlInput.trim())
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}
