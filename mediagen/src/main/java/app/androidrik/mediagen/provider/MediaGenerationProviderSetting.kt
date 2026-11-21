package app.androidrik.mediagen.provider

import app.androidrik.common.http.ProviderProxy
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

/**
 * 厂商级配置：只保存凭据和该厂商下的模型列表，同一份配置可以同时提供图像和视频模型。
 */
@Serializable
sealed class MediaGenerationProviderSetting {
    abstract val id: Uuid
    abstract val name: String
    abstract val apiKey: String
    abstract val baseUrl: String
    abstract val models: List<MediaGenerationModel>
    open val credentialRef: Uuid? get() = null

    /**
     * per-provider 代理（M3a-1 parity，对齐旧链路 `ProviderSetting.proxy`）：[ProviderProxy.Inherit]
     * 跟随全局、[ProviderProxy.None] 显式直连、Http/Socks5 自身配置优先。旧备份没有该字段，
     * 反序列化落默认值 [ProviderProxy.Inherit]，行为与升级前一致。
     */
    abstract val proxy: ProviderProxy

    /**
     * 多 key 轮询列表（M3a-1 parity，对齐旧链路 `ProviderSetting.ApiKey.apiKeys`）：非空时以
     * [effectiveMediaApiKeys] 为准，空列表回退旧单 key 字段（单 key 用户零迁移）。
     */
    abstract val apiKeys: List<String>

    // What our adapter implements, not every API offered by this vendor.
    abstract val supportedKinds: Set<MediaKind>

    /**
     * 下载产出地址 [url] 时要带上的请求头。多数厂商给的是带签名的地址，不需要额外的头；
     * OpenRouter 的视频要凭 API Key 从它自己的接口取。
     */
    open fun downloadHeaders(url: String): Map<String, String> = emptyMap()

    abstract fun copyProvider(
        id: Uuid = this.id,
        name: String = this.name,
        apiKey: String = this.apiKey,
        baseUrl: String = this.baseUrl,
        proxy: ProviderProxy = this.proxy,
        apiKeys: List<String> = this.apiKeys,
        models: List<MediaGenerationModel> = this.models,
    ): MediaGenerationProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenAI",
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.openai.com/v1",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        /**
         * 供应商级自定义请求头（M3b-1 目录映射载体：legacy `ProviderSetting.OpenAI.customHeaders`
         * 运行期映射时借进来逐条下发，指纹也按它算）。旧备份缺字段落空列表。
         */
        val customHeaders: List<app.androidrik.common.http.CustomHeader> = emptyList(),
        /**
         * 引用的旧 AI 供应商凭据 id（`ProviderSetting.ApiKey`）：设置后 baseUrl/apiKey/proxy/apiKeys
         * 在目录解析时取自被引用者，改 key 不用同步两处配置；被引用者被删或换端点后，引用它的模型
         * 相应失效或以新地址生效。null = 本地显式配置（无法共用鉴权时保留这种形态）。
         */
        override val credentialRef: Uuid? = null,
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "gpt-image-2.5-sunburst", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "gpt-image-2.5-flare", kind = MediaKind.IMAGE),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    /**
     * 百炼按业务空间分配域名：[baseUrl] 里的 `{WorkspaceId}` 会被替换成 [workspaceId]。换地域时改 [baseUrl] 里的地域段；
     * 不含占位符的地址（如旧的 `https://dashscope.aliyuncs.com/api/v1`）原样使用，此时不需要 [workspaceId]。
     */
    @Serializable
    @SerialName("aliyun")
    data class Aliyun(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Aliyun",
        override val apiKey: String = "",
        val workspaceId: String = "",
        override val baseUrl: String = "https://$WORKSPACE_PLACEHOLDER.cn-beijing.maas.aliyuncs.com/api/v1",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "qwen-image-3.0", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "wan3.0-video", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE, MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }

        companion object {
            const val WORKSPACE_PLACEHOLDER = "{WorkspaceId}"
        }
    }

    @Serializable
    @SerialName("volcengine")
    data class Volcengine(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Volcengine",
        override val apiKey: String = "",
        override val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "doubao-seedream-5-0-pro-260628", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "doubao-seedance-2-5-260628", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE, MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    @Serializable
    @SerialName("minimax")
    data class MiniMax(
        override val id: Uuid = Uuid.random(),
        override val name: String = "MiniMax",
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.minimaxi.com/v2",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "MiniMax-H3", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    @Serializable
    @SerialName("openrouter")
    data class OpenRouter(
        override val id: Uuid = Uuid.random(),
        override val name: String = "OpenRouter",
        override val apiKey: String = "",
        override val baseUrl: String = "https://openrouter.ai/api/v1",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "openai/gpt-image-2.5-sunburst", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "openai/gpt-image-2.5-flare", kind = MediaKind.IMAGE),
            MediaGenerationModel(modelId = "google/veo-3.1", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        // 仅图片已接入（OpenRouterMediaGenerationProvider 走生成端点）；veo 等视频能力待厂商接口后接
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE)

        // 密钥只发给 baseUrl 所在的站点，结果地址指向别处时原样下载
        override fun downloadHeaders(url: String): Map<String, String> {
            val target = url.toHttpUrlOrNull() ?: return emptyMap()
            val base = baseUrl.toHttpUrlOrNull() ?: return emptyMap()
            if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) return emptyMap()
            return mapOf("Authorization" to "Bearer ${firstMediaApiKey()}")
        }

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    /**
     * Google (Gemini) 生图：AI Studio API key 与 Vertex AI（服务账号）两种形态共用一套
     * generateContent IMAGE 输出模态协议（M3a-4 平移旧链路 `GoogleProvider.generateImage`）。
     * Vertex 字段与旧链路 `ProviderSetting.Google` 同名同义。凭据引用由 app 层解析。
     */
    @Serializable
    @SerialName("google")
    data class Google(
        override val id: Uuid = Uuid.random(),
        override val name: String = "Google",
        override val apiKey: String = "",
        override val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        /** 供应商级自定义请求头（M3b-1 目录映射载体，同 OpenAI）。 */
        val customHeaders: List<app.androidrik.common.http.CustomHeader> = emptyList(),
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "",
        var serviceAccountEmail: String = "",
        var location: String = "us-central1",
        var projectId: String = "",
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "gemini-2.5-flash-image", kind = MediaKind.IMAGE),
        ),
        override val credentialRef: Uuid? = null,
    ) : MediaGenerationProviderSetting() {
        // 仅图片已接入；generateContent 同步返回结果
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.IMAGE)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    /**
     * 硅基流动的视频接口：提交后返回 requestId，再轮询 /video/status 取结果。
     * 结果地址是公网 CDN 地址，下载不需要额外的请求头。
     */
    @Serializable
    @SerialName("siliconflow")
    data class SiliconFlow(
        override val id: Uuid = Uuid.random(),
        override val name: String = "SiliconFlow",
        override val apiKey: String = "",
        override val baseUrl: String = "https://api.siliconflow.cn/v1",
        override val proxy: ProviderProxy = ProviderProxy.Inherit,
        override val apiKeys: List<String> = emptyList(),
        override val models: List<MediaGenerationModel> = listOf(
            MediaGenerationModel(modelId = "Wan-AI/Wan2.2-T2V-A14B", kind = MediaKind.VIDEO),
            MediaGenerationModel(modelId = "Wan-AI/Wan2.2-I2V-14B-720P", kind = MediaKind.VIDEO),
        ),
    ) : MediaGenerationProviderSetting() {
        override val supportedKinds: Set<MediaKind>
            get() = setOf(MediaKind.VIDEO)

        override fun copyProvider(
            id: Uuid,
            name: String,
            apiKey: String,
            baseUrl: String,
            proxy: ProviderProxy,
            apiKeys: List<String>,
            models: List<MediaGenerationModel>,
        ): MediaGenerationProviderSetting {
            return this.copy(
                id = id,
                name = name,
                apiKey = apiKey,
                baseUrl = baseUrl,
                proxy = proxy,
                apiKeys = apiKeys,
                models = models,
            )
        }
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Aliyun::class,
                Volcengine::class,
                MiniMax::class,
                OpenRouter::class,
                SiliconFlow::class,
            )
        }
    }
}

/**
 * 多 key 统一读取（M3a-1 parity，对齐旧链路 `ProviderSetting.effectiveApiKeys`）：
 * [MediaGenerationProviderSetting.apiKeys] 非空时按行合并（[app.androidrik.common.http.KeyRoulette.next]
 * 的切分口径），空列表回退旧单 key 字段——单 key 用户零迁移。
 */
val MediaGenerationProviderSetting.effectiveMediaApiKeys: String
    get() {
        val keys = apiKeys.filter { it.isNotBlank() }.distinct()
        return if (keys.isNotEmpty()) keys.joinToString("\n") else apiKey
    }

/** [effectiveMediaApiKeys] 的首个 key（顺序模式语义：列表顺序即优先级）。 */
fun MediaGenerationProviderSetting.firstMediaApiKey(): String =
    effectiveMediaApiKeys.split("[\\s,]+".toRegex()).firstOrNull { it.isNotBlank() } ?: apiKey
