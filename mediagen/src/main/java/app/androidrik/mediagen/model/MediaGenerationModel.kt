package app.androidrik.mediagen.model

import app.androidrik.common.http.CustomBody
import app.androidrik.common.http.CustomHeader
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** 模型产出的媒体类型。 */
@Serializable
enum class MediaKind {
    IMAGE,
    VIDEO,
}

/**
 * 厂商下的一个模型。[modelId] 是下发给接口的模型名，[kind] 决定适配器走图像还是视频协议。
 *
 * [customHeaders]/[customBodies] 是模型级请求定制（M3a-2 parity，对齐旧链路
 * `Model.customHeaders/customBodies`）：headers 逐条加到请求，bodies 按键合并进顶层
 * 请求体（[app.androidrik.common.http.mergeCustomBody]，custom 值覆盖公共字段）。
 * 旧备份没有这两个字段，反序列化落默认空列表。
 */
@Serializable
data class MediaGenerationModel(
    val modelId: String,
    val kind: MediaKind,
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
)
