package app.androidrik.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 头像模型（随 Assistant 落盘于 Settings 与备份 JSON）。
 *
 * 显式钉住历史默认的全限定 `@SerialName`（见 `SettingsJsonMigrator` V0b 说明）：
 * 既与既有用户落盘数据逐字节兼容，又防止日后类移动/重命名破坏反序列化。
 */
@Serializable
sealed class Avatar {
    @Serializable
    @SerialName("app.androidrik.rikkahub.data.model.Avatar.Dummy")
    data object Dummy : Avatar()

    @Serializable
    @SerialName("app.androidrik.rikkahub.data.model.Avatar.Emoji")
    data class Emoji(val content: String) : Avatar()

    @Serializable
    @SerialName("app.androidrik.rikkahub.data.model.Avatar.Image")
    data class Image(val url: String) : Avatar()
}
