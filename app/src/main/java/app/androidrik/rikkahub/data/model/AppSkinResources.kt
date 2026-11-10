package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Content hashes in the existing protected image store; never arbitrary archive paths. */
@Serializable
data class AppSkinResources(val logo: String? = null, val chatBackground: String? = null) {
    fun normalized() = copy(logo = logo?.takeIf(::isBubbleTextureId), chatBackground = chatBackground?.takeIf(::isBubbleTextureId))
}
