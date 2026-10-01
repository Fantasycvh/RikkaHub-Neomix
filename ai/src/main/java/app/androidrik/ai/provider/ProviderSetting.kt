package app.androidrik.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/**
 * 单个提供商的代理配置。
 *
 * 语义（两层代理设计，见 `docs/design-proxy-two-layer.md`）：
 * - [Inherit]：跟随全局代理设置（**新 provider 的默认值**；app 层不动 client，由共享 client 的
 *   ProxySelector 决定走向）——ai 模块无需知道全局设置是否存在。
 * - [None]：**显式直连**（即使 app 配了全局代理也不走）。语义由旧「无代理」收窄而来。
 * - [Http] / [Socks5]：自身配置优先，覆盖全局。
 *
 * 向后兼容：旧数据里显式序列化的 `"none"` 仍反序列化为 [None]（直连），故既有 provider 行为
 * 不变；只有新建 provider（默认 [Inherit]）或用户手动改选才继承全局。
 */
@Serializable
sealed class ProviderProxy {
    /** 跟随全局代理（新默认）——app 层共享 client 的 ProxySelector 生效。 */
    @Serializable
    @SerialName("inherit")
    object Inherit : ProviderProxy()

    /** 显式直连（屏蔽全局代理）。 */
    @Serializable
    @SerialName("none")
    object None : ProviderProxy()

    @Serializable
    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProviderProxy()

    /** SOCKS5 代理（认证在 socket 层，需 app 层的 JVM Authenticator 配合，见设计文档 §三）。 */
    @Serializable
    @SerialName("socks5")
    data class Socks5(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProviderProxy()
}

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径（单接口模式）
    val resultPath: String = "data.total_usage", // 余额获取JSON路径（单接口模式）
    val sources: List<BalanceSource> = emptyList(), // 多接口模式：各数据源
    val expression: String = "", // 多接口模式：最终表达式，如 "quota - usage"
)

@Serializable
data class BalanceSource(
    val name: String, // 变量名，供 expression 引用（如 "quota"、"usage"）
    val apiPath: String, // API 路径（如 "/api/user/self"）
    val resultPath: String, // JSON 取值路径（如 "quota"）
)

/**
 * 生成失败自动重试配置。
 *
 * [delaysSeconds] 的长度即重试次数（不含首次请求），值即每次重试前的等待秒数，
 * 可表达任意阶梯节奏。默认值含义：前 5 次每 2 秒重试一次，之后 5s/10s/20s/60s/120s。
 */
@Serializable
data class RetrySetting(
    val enabled: Boolean = false,
    val delaysSeconds: List<Int> = DEFAULT_DELAYS_SECONDS,
    /** #1812：半截输出失败时保留已流式内容为草稿（默认开）；false 则照旧清空重试 */
    val keepPartialOnFailure: Boolean = true,
    /** #1812：半截输出失败后仍自动重试（默认关，给想要全自动的用户）；开时 keepPartialOnFailure 不生效 */
    val retryAfterPartial: Boolean = false,
    /** #1812：半截输出判定阈值——过滤 reasoning 后可见 Text 字符数 ≥ 该值视为「已产生有效输出」 */
    val partialOutputMinChars: Int = 50,
) {
    companion object {
        val DEFAULT_DELAYS_SECONDS = listOf(2, 2, 2, 2, 2, 5, 10, 20, 60, 120)

        /**
         * 解析逗号分隔的秒数序列（UI 输入），非法返回 null。
         * 允许空白；每项必须是非负整数。
         */
        fun parseDelays(text: String): List<Int>? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(',', ',', '，').map { it.trim() }
            if (parts.any { it.isEmpty() }) return null
            return parts.map {
                it.toIntOrNull()?.takeIf { v -> v >= 0 } ?: return null
            }
        }
    }
}

@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
data class ApiKeyEntry(
    val key: String,
    val enabled: Boolean = true,
    /**
     * 稳定 id：UI 列表的 item key 用它而不是 [key] 的哈希。
     *
     * 为什么需要：编辑器（`ProviderApiKeysEditor`）的 LazyColumn 若以内容哈希作 key，
     * 每敲一个字符 key 就变 ⇒ Compose 视为「旧行销毁 + 新行创建」⇒ 焦点丢失（IME 收起）
     * + `animateItem` 播放消失/出现动画（界面抖动）——每个字符都抖一次（历史 bug）。
     *
     * 兼容性：参与序列化；旧 JSON 缺此字段时用默认随机值 ⇒ 进程内稳定（settings 在内存中
     * 是同一批对象），首次写回后落盘、此后跨进程也稳定。两个空 key 行也不会 key 冲突
     * （旧方案 `"".hashCode()` 恒等，添加两个空行会触发 Compose duplicate-key 崩溃）。
     */
    val id: String = Uuid.random().toString(),
) {
    /**
     * id **不参与** equals/hashCode。
     *
     * id 是 UI 列表的稳定锚点，但解析旧数据时它只能取随机默认值——若参与相等性，
     * 「同一份内容重新解析前后」会被误判为变化，任何依赖 equals 的 diff/缓存/测试
     * 断言都会被这个随机性污染（GenerationRetryTest 实测踩到：轮转副本与预期列表
     * key 全同却因 id 不同而不等）。业务身份就是 `key + enabled`。
     */
    override fun equals(other: Any?): Boolean =
        other is ApiKeyEntry && other.key == key && other.enabled == enabled

    override fun hashCode(): Int = 31 * key.hashCode() + enabled.hashCode()

    companion object {
        /** 从旧单 key 字段（空白/逗号分隔）拆出结构化列表 */
        fun fromLegacy(legacy: String): List<ApiKeyEntry> =
            legacy.split("[\\s,]+".toRegex())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { ApiKeyEntry(it) }
    }
}

/** 多 key 统一读取：enabled 过滤后回退旧单 key 字段（备份兼容，单 key 用户零迁移） */
val ProviderSetting.effectiveApiKeys: String
    get() {
        val apiKeys = when (this) {
            is ProviderSetting.OpenAI -> this.apiKeys
            is ProviderSetting.Google -> this.apiKeys
            is ProviderSetting.Claude -> this.apiKeys
        }
        val keys = apiKeys.filter { it.enabled }.map { it.key }.filter { it.isNotBlank() }.distinct()
        return if (keys.isNotEmpty()) {
            keys.joinToString("\n")
        } else {
            when (this) {
                is ProviderSetting.OpenAI -> this.apiKey
                is ProviderSetting.Google -> this.apiKey
                is ProviderSetting.Claude -> this.apiKey
            }
        }
    }

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val proxy: ProviderProxy
    abstract val balanceOption: BalanceOption
    abstract val customHeaders: List<CustomHeader>
    /** per-provider 重试配置覆盖；null = 跟随全局 Settings.retrySetting */
    abstract val retryOption: RetrySetting?

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        proxy: ProviderProxy = this.proxy,
        balanceOption: BalanceOption = this.balanceOption,
        customHeaders: List<CustomHeader> = this.customHeaders,
        retryOption: RetrySetting? = this.retryOption,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.Inherit,
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        override val retryOption: RetrySetting? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
        var includeHistoryReasoning: Boolean = true,
        var responsesPath: String = "/responses",
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            retryOption: RetrySetting?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                proxy = proxy,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                retryOption = retryOption,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.Inherit,
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        override val retryOption: RetrySetting? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        var vertexAI: Boolean = false,
        var useServiceAccount: Boolean = false,
        var privateKey: String = "", // only for vertex AI service account
        var serviceAccountEmail: String = "", // only for vertex AI service account
        var location: String = "us-central1", // only for vertex AI service account
        var projectId: String = "", // only for vertex AI service account
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            retryOption: RetrySetting?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                proxy = proxy,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                retryOption = retryOption
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.Inherit,
        override val balanceOption: BalanceOption = BalanceOption(),
        override val customHeaders: List<CustomHeader> = emptyList(),
        override val retryOption: RetrySetting? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var apiKeys: List<ApiKeyEntry> = emptyList(),
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(models = models.filter { it.id != model.id })
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            customHeaders: List<CustomHeader>,
            retryOption: RetrySetting?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                proxy = proxy,
                balanceOption = balanceOption,
                customHeaders = customHeaders,
                retryOption = retryOption,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    companion object {
        /**
         * 提供商**类型**（= API 格式）及其**展示名**。
         *
         * **展示名必须显式写死，绝不能用 `KClass.simpleName`**：release 构建经 R8 混淆后
         * `ProviderSetting$OpenAI` → `it9`、`$Google` → `ft9`、`$Claude` → `bt9`
         * （见 `app/build/outputs/mapping/release/mapping.txt`），于是正式版里类型选择器
         * 显示的是 "it9"/"ft9" 这种短名，而 debug 不混淆、看着完全正常——**只在正式版暴露**。
         * 把展示名写进本列表，等于新增类型时强制补齐它的展示名。
         */
        val Types: List<ProviderType> by lazy {
            listOf(
                ProviderType(OpenAI::class, "OpenAI"),
                ProviderType(Google::class, "Google"),
                ProviderType(Claude::class, "Claude"),
            )
        }
    }
}

/**
 * 提供商类型（API 格式）描述符：类 + 展示名。
 *
 * @param type 用于类型切换（[ProviderConfigure] 的 SegmentedButton）
 * @param displayName **用户可见**的名称，见 [ProviderSetting.Types] 的注释（勿用类名）
 */
data class ProviderType(
    val type: KClass<out ProviderSetting>,
    val displayName: String,
)

/**
 * 实例 → 类型展示名（唯一来源是 [ProviderSetting.Types]，避免两处各写一份漂移）。
 *
 * 典型用途：模型详情页的 provider-override 标签——那里同样不能显示混淆类名。
 */
val ProviderSetting.providerTypeDisplayName: String
    get() = ProviderSetting.Types.firstOrNull { it.type == this::class }?.displayName ?: "Unknown"

