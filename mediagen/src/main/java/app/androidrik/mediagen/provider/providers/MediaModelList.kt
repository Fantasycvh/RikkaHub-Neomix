package app.androidrik.mediagen.provider.providers

import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI 兼容 `GET /models` 响应里的模型 id（`data[].id`）。多数厂商都返回这个结构
 * （OpenAI、硅基流动、MiniMax……），所以解析放在这里共用，只负责取 id，不判断用途。
 */
internal fun parseModelIds(root: JsonObject): List<String> =
    (root["data"] as? JsonArray).orEmpty()
        .mapNotNull { element -> (element as? JsonObject)?.string("id") }
        .filter { it.isNotBlank() }

/**
 * OpenAI 的图像模型 id 特征：含 `image` 或以 `dall-e` 开头（如 `gpt-image-1`、`dall-e-3`）。
 */
private fun String.isOpenAiImageModelId(): Boolean =
    lowercase().let { it.contains("image") || it.startsWith("dall-e") }

/**
 * 从 OpenAI 兼容模型列表里挑出图像模型。
 *
 * 一个都没匹配上时**返回全部**：兼容端点/中转站常把模型改成任意名字（如 `flux-dev`），
 * 与其空手而归，不如交给用户在勾选对话框里筛——选中之前不会写进配置，误判无副作用。
 */
internal fun openAiImageModels(root: JsonObject): List<MediaGenerationModel> =
    parseModelIds(root).distinct().let { ids ->
        ids.filter { it.isOpenAiImageModelId() }.ifEmpty { ids }
    }.map { MediaGenerationModel(modelId = it, kind = MediaKind.IMAGE, displayName = it) }

/**
 * 硅基流动的视频模型 id 特征：含 `video` / `t2v` / `i2v`（如 `Wan-AI/Wan2.2-T2V-A14B`、
 * `lightricks/LTX-Video`）。只在「`type` 参数未生效、接口返回了全部模型」时用来兜底过滤。
 */
private fun String.isVideoModelId(): Boolean =
    lowercase().let { it.contains("video") || it.contains("t2v") || it.contains("i2v") }

/**
 * 硅基流动的 `type` 参数若被忽略（部分自建/中转地址），返回列表里会混着聊天模型；
 * 此时至少按 id 特征筛一遍。筛不出任何视频模型时同样返回全部，交给用户勾选。
 */
internal fun siliconFlowVideoModels(root: JsonObject): List<MediaGenerationModel> =
    parseModelIds(root).distinct().let { ids ->
        ids.filter { it.isVideoModelId() }.ifEmpty { ids }
    }.map { MediaGenerationModel(modelId = it, kind = MediaKind.VIDEO, displayName = it) }
