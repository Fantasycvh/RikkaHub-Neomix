package app.androidrik.plugin.api

import kotlinx.serialization.Serializable

/**
 * 插件能力声明(借鉴 BiliPai `PluginCapability`)。
 *
 * 设计原则(见 `docs/PLUGIN_SYSTEM_DESIGN_DRAFT.md` §1.3):
 * - 插件只声明**当前版本实际使用**的能力,宿主在插件中心展示;
 * - 能力是"授权"而非"提示":调用点必须显式校验(如 ChatService 只在持有
 *   [PROMPT_TRANSFORM] 时才把插件挂进输入管线),不能只靠安装期一次。
 *
 * 本 fork 的插件为**源码级原生插件**(编译进 APK),没有外部包安装期,
 * 因此默认信任声明;用户可在插件中心撤销个别能力。
 */
@Serializable
enum class PluginCapability {
    /** 访问远程服务(HTTP 等)。 */
    NETWORK,

    /** 读取本地会话/消息历史摘要。 */
    HISTORY_READ,

    /** 读写会话内容(例如注入提示词、修改消息)。 */
    CONVERSATION_MUTATE,

    /** 向模型注册工具(经宿主工具装配点下发给模型)。 */
    TOOL_REGISTER,

    /** 参与输入侧消息转换管线([PROMPT_TRANSFORM] 对应 InputMessageTransformer)。 */
    PROMPT_TRANSFORM,

    /** 参与输出侧消息转换管线(流式展示 / 生成结束处理)。 */
    OUTPUT_TRANSFORM,

    /** 读写插件自有的本地设置或缓存。 */
    STORAGE,
}
