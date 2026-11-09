package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.data.model.ChatAppearanceDetails
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.ThemeRoleOverrides
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.component.get

// —— UI/外观与配置类型群（批次 4 拆出）：@Serializable 形状逐字保留 ——

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

/**
 * 玻璃效果方案二选一（对照上游 BackgroundEffectType）：模糊 / 真玻璃（hazeGlass）。
 * 输入框 / 顶栏 / 聊天列表玻璃层各有独立一份（inputEffectType / topBarEffectType /
 * listGlassEffectType），各自的开关（enableBlurEffect / enableTopBarBlur /
 * enableGlassTheme）只管开不开，开什么方案由各自字段决定。
 * GLASS 依赖 RuntimeShader，<T(33) 或后台 gate 时各消费点自动退回 blur 路线。
 */
@Serializable
enum class BackgroundEffectType {
    @SerialName("blur")
    BLUR,
    @SerialName("glass")
    GLASS,
}

/**
 * 玻璃/磨砂表面上的文字可读性模式（对照 BiliPai LiquidGlassReadabilityMode）：
 * - AUTO：自动适配，效果自身按主题铺底，视觉最「透」；
 * - STABLE_CONTENT：稳定内容色，效果表面加厚铺底/着色，文字对比度不随背景图变化。
 */
@Serializable
enum class LiquidGlassReadabilityMode {
    @SerialName("auto")
    AUTO,
    @SerialName("stable_content")
    STABLE_CONTENT,
}

/**
 * 顶栏样式二选一（上游 b40d7a273 顶栏浮动胶囊，本仓两套共存）：
 * - FULL_BAR（默认）：整条 TopAppBar 挂穿底模糊（liquidGlassChromeEffect，现状）；
 * - CAPSULES：TopAppBar 本身永远透明，菜单键与右侧操作键分别装进浮动胶囊
 *   （ChatTopBarCapsule），胶囊背景效果与输入框同款（enableBlurEffect/inputEffectType），
 *   仅当消息滚到标题栏下方时淡入。旧配置缺字段时默认 FULL_BAR，行为不变。
 */
@Serializable
enum class TopBarChromeStyle {
    @SerialName("full_bar")
    FULL_BAR,
    @SerialName("capsules")
    CAPSULES,
}

/**
 * 聊天页头像装饰框预设（轻量个性化，橘瓣灵感；见 design-light-personalization.md）。
 * 纯 Canvas 绘制、随主题取色，作用于聊天页用户/助手双侧头像。
 */
@Serializable
enum class AvatarFrameStyle {
    @SerialName("none") NONE,
    @SerialName("ring") RING,
    @SerialName("gradient") GRADIENT,
    @SerialName("double") DOUBLE,
    @SerialName("dashed") DASHED,
    @SerialName("glow") GLOW,
    /** 自定义图片框：用户导入的正方形透明中框素材（[DisplaySetting.avatarFrameImagePath]）。 */
    @SerialName("custom_image") CUSTOM_IMAGE,
    /** 自定义参数框：JSON 可导入导出的环参数（[DisplaySetting.avatarFrameParams]）。 */
    @SerialName("custom_params") CUSTOM_PARAMS,
}

/** 思维链容器视觉样式（轻量个性化）：卡片=现状，引用条=透明底+左缘主色竖线，极简=无框无线，自定义=JSON 样式。 */
@Serializable
enum class ChainOfThoughtStyle {
    @SerialName("card") CARD,
    @SerialName("quote") QUOTE,
    @SerialName("minimal") MINIMAL,
    @SerialName("custom") CUSTOM,
}

/**
 * 自定义参数框（头像框 CUSTOM_PARAMS 档）。kind 用 String 而非枚举：JSON 导入
 * 未知值按 ring 回退（lorebook 枚举大小写坑的规避），合法域 ring/gradient/double/dashed/glow。
 * hex 均为 `#RRGGBB`，非法值渲染端回退主题色。
 */
@Serializable
data class AvatarFrameParams(
    val kind: String = "ring",
    val colorHex: String = "#6750A4",
    /** 渐变第二色/双环内环色；null = 用 colorHex 的明暗变体。 */
    val secondaryColorHex: String? = null,
    val strokeWidthDp: Float = 2f,
    val dashDp: Float = 6f,
    val gapDp: Float = 4f,
)

/**
 * 思维链自定义样式（CUSTOM 档，JSON 可导入导出）。hex 非法/缺省回退主题派生色；
 * 与三档枚举并存——CUSTOM 档读这里，其余档忽略。
 */
@Serializable
data class ChainOfThoughtCustomStyle(
    /** 容器色；null = 主题 surfaceContainerLow/High（随折叠态）。 */
    val containerColorHex: String? = null,
    /** 强调色：时间线/引用条/收起控制条；null = 主题派生。 */
    val accentColorHex: String? = null,
    val cornerRadiusDp: Float = 16f,
    val timelineLineWidthDp: Float = 1f,
    val showTimeline: Boolean = true,
)

/** 头像框参数方案：命名预设，应用时写入 [DisplaySetting.avatarFrameParams] 并切到 CUSTOM_PARAMS 档。 */
@Serializable
data class AvatarFrameScheme(
    val id: String = Uuid.random().toString(),
    val name: String,
    val params: AvatarFrameParams = AvatarFrameParams(),
)

/** 思维链样式方案：命名预设，应用时写入 [DisplaySetting.chainOfThoughtCustomStyle] 并切到 CUSTOM 档。 */
@Serializable
data class ChainOfThoughtScheme(
    val id: String = Uuid.random().toString(),
    val name: String,
    val style: ChainOfThoughtCustomStyle = ChainOfThoughtCustomStyle(),
)

@Serializable
data class DisplaySetting(
    val chatAppearanceDetails: ChatAppearanceDetails = ChatAppearanceDetails(),
    val chatAppearancePresetId: String? = null,
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    /**
     * 消息末尾 NerdLine 显示「路由 · 模型」生成来源（#1805）。数据来自 UIMessage 的
     * modelNameSnapshot/providerNameSnapshot 双快照（存名不存 id，删模型/重建不悬空）；
     * 旧消息无快照时该条不显示。默认关（与头部 showModelName 信息量不同：这里是
     * 路由归因，供多中转核对与成本/质量定位）。
     */
    val showGenerationSource: Boolean = false,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    /**
     * 整轮折叠（`docs/design/design-whole-turn-folding.md`）：把一条 assistant 回复的
     * **思考 + 工具调用 + 过程解说**收进一个可展开块，只留最后一次工具调用之后的文本作最终回答。
     *
     * 默认关 = 旧行为（解说文本仍作为正文、思考链按“只显示最后 2 步”折叠）。开启后由它
     * **接管** [autoCloseThinking]（设置页把后者置灰）。
     */
    val wholeTurnFolding: Boolean = false,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    /** Animate content height while generating; old settings retain the existing behavior. */
    val enableGenerationSizeAnimation: Boolean = true,
    /** Standard-layout long messages can put parser-safe units in the chat list, including streaming. */
    val enableLongMessageLazyRendering: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableDiagramRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val inputEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val enableGlassTheme: Boolean = false,
    val listGlassEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val enableTopBarBlur: Boolean = false,
    val topBarEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    /** 顶栏 chrome 样式：整条穿底模糊（现状）或浮动胶囊（上游 b40d7a273，两套共存）。 */
    val topBarStyle: TopBarChromeStyle = TopBarChromeStyle.FULL_BAR,
    val liquidGlassReadabilityMode: LiquidGlassReadabilityMode = LiquidGlassReadabilityMode.AUTO,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    /**
     * 应用内显示名：留空 = 用默认 R.string.app_name；非空时用于应用内展示与
     * 最近任务窗口标题（RouteActivity 的 TaskDescription）。桌面名称不归它管。
     */
    val customAppName: String = "",
    /**
     * 桌面「名称 + 图标」预置的 activity-alias id（见 [app.androidrik.rikkahub.utils.LauncherAlias]）。
     * 空串 = 默认别名；切换由 PackageManager.setComponentEnabledSetting 完成，这里只存选择结果。
     */
    val launcherAliasId: String = "",
    /**
     * 角色覆写（全局层，对照 BiliPai ThemeRoleOverrides；见 `data/model/ThemeRoleOverrides.kt`（渲染应用在 ui/theme））。
     * enabled=false（默认）零行为变化；开启后叠加在任意色源之上。存这里而非 Settings 顶层：
     * 复用 display_setting 单 JSON key 不加键，且天然可进主题外观快照（design-custom-theme.md §4.2）。
     */
    val themeRoleOverrides: ThemeRoleOverrides = ThemeRoleOverrides(),
    /** 聊天页头像装饰框（用户+助手双侧，见 design-light-personalization.md）。 */
    val avatarFrameStyle: AvatarFrameStyle = AvatarFrameStyle.NONE,
    /** 聊天输入栏背景图（file:// 或 http url；空=无）。生效时输入栏底色转透明并与模糊效果互斥。 */
    val chatInputBackgroundImage: String = "",
    val chatInputBackgroundOpacity: Float = 0.35f,
    /** 思维链容器视觉样式（见 design-light-personalization.md）。 */
    val chainOfThoughtStyle: ChainOfThoughtStyle = ChainOfThoughtStyle.CARD,
    /** 头像框 CUSTOM_IMAGE 档的素材路径（managed avatar-frames/）。 */
    val avatarFrameImagePath: String = "",
    /** 头像框 CUSTOM_PARAMS 档的环参数（JSON 可导入导出）。 */
    val avatarFrameParams: AvatarFrameParams? = null,
    /** 思维链 CUSTOM 档的样式（JSON 可导入导出）。 */
    val chainOfThoughtCustomStyle: ChainOfThoughtCustomStyle? = null,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    // 默认远端目录与应用名一致（fork 更名 Neomix 后不再沿用上游的 rikkahub_plus_backups）
    val path: String = "neomix_backups",
) {
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

/** 全局代理类型（两层代理设计 §一）。 */
@Serializable
enum class ProxyType { NONE, HTTP, SOCKS5 }

/**
 * 全局代理设置（两层代理设计的「全局兜底层」）。
 *
 * 覆盖面（已盘点，见 docs/design/design-proxy-two-layer.md §四）：
 * - AI chat：provider 未自配（`ProviderProxy.Inherit`）时走这里；
 * - **只有走 DI 共享 client 的链路才受益**——搜索（`SearchService.init` 注入共享 client）、
 *   ASR（`koinInject<OkHttpClient>`）在列；TTS / MCP 各自 `OkHttpClient.Builder()` 自建、
 *   S3/WebDAV 走独立 Ktor client，**均不受全局层影响**。
 */
@Serializable
data class GlobalProxySetting(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** 是否可用（已选择类型且地址端口填写有效）。 */
    val isConfigured: Boolean
        get() = type != ProxyType.NONE && host.isNotBlank() && port in 1..65535
}
