package app.androidrik.rikkahub.ui.pages.share.handler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import app.androidrik.rikkahub.data.files.FilesManager
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.utils.base64Encode
import app.androidrik.rikkahub.ui.navigation.navigateToChatPage
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, files: List<String>) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val filesManager: FilesManager = koinInject()
    val previews by produceState<List<Triple<String, String, String>>>(emptyList(), files) {
        value = withContext(Dispatchers.IO) {
            files.map { source ->
                val uri = source.toUri()
                Triple(source, runCatching { filesManager.getFileNameFromUri(uri) }.getOrNull() ?: "Attachment",
                    runCatching { filesManager.getFileMimeType(uri) }.getOrNull().orEmpty())
            }
        }
    }
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.share_handler_page_title))
                }
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = it + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = vm.shareText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )

                        previews.forEach { (source, name, mime) ->
                            if (mime.startsWith("image/")) {
                                AsyncImage(model = source, contentDescription = null, modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (vm.shareText.isBlank() && files.isEmpty()) {
                            Text("No readable text or attachments were shared.")
                        }
                    }
                }
            }

            items(if (vm.shareText.isBlank() && files.isEmpty()) emptyList() else settings.assistants, key = { it.id }) { assistant ->
                Surface(
                    onClick = {
                        scope.launch {
                            vm.updateAssistant(assistant.id)
                            navigateToChatPage(
                                navigator = navController,
                                initText = vm.shareText.base64Encode(),
                                initFiles = files.map { it.toUri() }
                            )
                        }
                    },
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = assistant.name.ifEmpty {
                                    stringResource(R.string.assistant_page_default_assistant)
                                },
                                maxLines = 1
                            )
                        },
                    )
                }
            }
        }
    }
}
