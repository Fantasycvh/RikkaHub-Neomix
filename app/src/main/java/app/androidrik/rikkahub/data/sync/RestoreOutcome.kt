package app.androidrik.rikkahub.data.sync

/**
 * 一次整库恢复的结果。
 *
 * @param workspaceFilesRestored 恢复的工作区文件数（0 = 未包含/未恢复）；调用方据此提示「rootfs 需重装」
 * @param settingsWarnings 设置里**被降级丢弃**的条目（如原版 3.1.0 的 `chatgpt`/`grok` 提供商、
 *   `volcengine` TTS）。为空表示设置完整恢复；非空时调用方应提示用户哪些设置没带过来
 *   （见 [app.androidrik.rikkahub.data.datastore.migration.SettingsCompatDecoder]）。
 * @param mergedConversations merge 模式下**新插入**的会话数（overwrite 模式恒 0）
 * @param skippedConversations merge 模式下因同 id **跳过保留本地**的会话数（overwrite 模式恒 0）
 */
data class RestoreOutcome(
    val workspaceFilesRestored: Int = 0,
    val settingsWarnings: List<String> = emptyList(),
    val mergedConversations: Int = 0,
    val skippedConversations: Int = 0,
)
