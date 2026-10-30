package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Setting07
import me.rerere.hugeicons.stroke.Time02
import app.androidrik.rikkahub.R

@Composable
fun ConversationSystemPromptButton(
    customSystemPrompt: String?,
    onSystemPromptChange: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable(customSystemPrompt) {
        mutableStateOf(customSystemPrompt ?: "")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
        ) {
            Icon(
                imageVector = HugeIcons.Setting07,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (!customSystemPrompt.isNullOrBlank()) {
                    stringResource(R.string.chat_page_conversation_system_prompt) + " ✎"
                } else {
                    stringResource(R.string.chat_page_conversation_system_prompt)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_page_conversation_system_prompt_hint)) },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (!customSystemPrompt.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                editText = ""
                                onSystemPromptChange(null)
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_conversation_system_prompt_clear))
                        }
                    }
                    TextButton(
                        onClick = {
                            onSystemPromptChange(editText.ifBlank { null })
                            expanded = false
                        },
                    ) {
                        Text(stringResource(R.string.chat_page_conversation_system_prompt_save))
                    }
                }
            }
        }
    }
}

/**
 * 会话长期目标（Conversation.goal）的编辑入口（定时任务 P2，设计稿 §七「会话内入口」）。
 *
 * 目标是**会话的属性**：写了目标，该会话的定时任务表单才会出现「附带长期目标」开关，
 * 注入时拼在 prompt 头部。交互与上面的系统提示按钮同款（点开就地展开长文本编辑），
 * 不再弹对话框——同一排两个入口，形态一致才不被当成两个功能体系。
 */
@Composable
fun ConversationGoalButton(
    goal: String?,
    onGoalChange: (String?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable(goal) {
        mutableStateOf(goal ?: "")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextButton(
            onClick = { expanded = !expanded },
        ) {
            Icon(
                imageVector = HugeIcons.CheckList,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = if (!goal.isNullOrBlank()) {
                    stringResource(R.string.chat_page_conversation_goal) + " ✎"
                } else {
                    stringResource(R.string.chat_page_conversation_goal)
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_page_conversation_goal_hint)) },
                    minLines = 3,
                    maxLines = 8,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (!goal.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                editText = ""
                                onGoalChange(null)
                            },
                        ) {
                            Text(stringResource(R.string.chat_page_conversation_system_prompt_clear))
                        }
                    }
                    TextButton(
                        onClick = {
                            onGoalChange(editText.ifBlank { null })
                            expanded = false
                        },
                    ) {
                        Text(stringResource(R.string.chat_page_conversation_system_prompt_save))
                    }
                }
            }
        }
    }
}

/**
 * 「为此会话创建定时任务」入口（定时任务 P2，设计稿 §七）。跳到整页创建表单，
 * 目标已由本会话预选（Screen.SettingScheduledTaskCreate(conversationId)）。
 * 与目标/系统提示按钮同排同形态；图标与设置页「定时任务」入口同款（Time02）。
 */
@Composable
fun CreateScheduledTaskButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = HugeIcons.Time02,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(R.string.scheduled_task_create_for_conversation),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
