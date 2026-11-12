package app.androidrik.rikkahub.ui.pages.assistant.detail

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.ui.theme.LocalDarkMode

private val jsonLenient = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
}

@Composable
fun CustomHeaders(headers: List<CustomHeader>, onUpdate: (List<CustomHeader>) -> Unit) {
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_headers))
        Spacer(Modifier.height(8.dp))

        headers.forEachIndexed { index, header ->
            // 旧写法 `var headerName by remember(header.name)` 把持久态投影当 key：
            // 每次落盘回灌就重建缓冲（打字中被重置、trim 把尾随空格吃掉）。改成本地权威 +
            // 去抖单向写出。列表条目没有 id，resetKey 用「下标 + 当前长度」：只有增删会
            // 改变这个组合（本列表不支持重排），既不会因值回灌而重建，也能在删除后让被顶
            // 上来的那一行重新按持久值初始化。
            val rowKey = index to headers.size
            val nameState = rememberPersistedTextFieldState(
                externalValue = header.name,
                resetKey = rowKey,
                onCommit = { name ->
                    val updated = headers.toMutableList()
                    val current = updated.getOrNull(index) ?: return@rememberPersistedTextFieldState
                    updated[index] = current.copy(name = name.trim())
                    onUpdate(updated)
                },
            )
            val valueState = rememberPersistedTextFieldState(
                externalValue = header.value,
                resetKey = rowKey,
                onCommit = { value ->
                    val updated = headers.toMutableList()
                    val current = updated.getOrNull(index) ?: return@rememberPersistedTextFieldState
                    updated[index] = current.copy(value = value.trim())
                    onUpdate(updated)
                },
            )

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                state = nameState,
                                label = { Text(stringResource(R.string.assistant_page_header_name)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                state = valueState,
                                label = { Text(stringResource(R.string.assistant_page_header_value)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedHeaders = headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            onUpdate(updatedHeaders)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_header)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedHeaders = headers.toMutableList()
                updatedHeaders.add(CustomHeader("", ""))
                onUpdate(updatedHeaders)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_header))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_header))
        }
    }
}

@Composable
fun CustomBodies(customBodies: List<CustomBody>, onUpdate: (List<CustomBody>) -> Unit) {
    val resources = LocalResources.current
    Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.assistant_page_custom_bodies))
        Spacer(Modifier.height(8.dp))

        customBodies.forEachIndexed { index, body ->
            // 同 headers：本地权威 + 去抖单向写出。resetKey 用「下标 + 当前长度」
            // （条目没有 id，但本列表只支持追加/删除，不支持重排）。
            val rowKey = index to customBodies.size
            val keyState = rememberPersistedTextFieldState(
                externalValue = body.key,
                resetKey = rowKey,
                onCommit = { key ->
                    val updated = customBodies.toMutableList()
                    val current = updated.getOrNull(index) ?: return@rememberPersistedTextFieldState
                    updated[index] = current.copy(key = key.trim())
                    onUpdate(updated)
                },
            )
            // JSON 值：输入过程中**保留用户原文**，只在提交时解析一次；解析失败不写持久层。
            val initialBodyValue = remember(body.value) {
                jsonLenient.encodeToString(JsonElement.serializer(), body.value)
            }
            val valueState = rememberPersistedTextFieldState(
                externalValue = initialBodyValue,
                resetKey = rowKey,
                onCommit = { text ->
                    val parsed = runCatching { jsonLenient.parseToJsonElement(text) }.getOrNull()
                    if (parsed != null) {
                        val updated = customBodies.toMutableList()
                        val current = updated.getOrNull(index) ?: return@rememberPersistedTextFieldState
                        updated[index] = current.copy(value = parsed)
                        onUpdate(updated)
                    }
                },
            )
            val bodyValueString = valueState.text.toString()
            // 错误提示从本地文本算（不回写、不重排文本），无错误时不出 supportingText。
            val jsonParseError = remember(bodyValueString) {
                runCatching { jsonLenient.parseToJsonElement(bodyValueString) }.exceptionOrNull()?.let { e ->
                    resources.getString(
                        R.string.assistant_page_invalid_json,
                        e.message?.take(100) ?: ""
                    )
                }
            }

            CardGroup {
                item(
                    supportingContent = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                state = keyState,
                                label = { Text(stringResource(R.string.assistant_page_body_key)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            // 桥接回 legacy value 重载：state 版 AppOutlinedTextField 没有
                            // visualTransformation，而 JSON 语法高亮只能走 value 路径。value 的
                            // 源是**本地同步** TextFieldState（无异步回灌，故不会抖动），
                            // 去抖提交 / 离页补写仍由 hook 负责。
                            OutlinedTextField(
                                value = bodyValueString,
                                onValueChange = { valueState.setTextAndPlaceCursorAtEnd(it) },
                                label = { Text(stringResource(R.string.assistant_page_body_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                isError = jsonParseError != null,
                                supportingText = {
                                    if (jsonParseError != null) {
                                        Text(jsonParseError)
                                    }
                                },
                                minLines = 3,
                                maxLines = 5,
                                visualTransformation = HighlightCodeVisualTransformation(
                                    language = "json",
                                    highlighter = LocalCodeHighlighter.current,
                                    darkMode = LocalDarkMode.current
                                ),
                                textStyle = LocalTextStyle.current.merge(fontFamily = JetbrainsMono),
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val updatedBodies = customBodies.toMutableList()
                            updatedBodies.removeAt(index)
                            onUpdate(updatedBodies)
                        }) {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.assistant_page_delete_body)
                            )
                        }
                    },
                    headlineContent = {},
                )
            }
        }

        Button(
            onClick = {
                val updatedBodies = customBodies.toMutableList()
                updatedBodies.add(CustomBody("", JsonPrimitive("")))
                onUpdate(updatedBodies)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.assistant_page_add_body))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.assistant_page_add_body))
        }
    }
}
