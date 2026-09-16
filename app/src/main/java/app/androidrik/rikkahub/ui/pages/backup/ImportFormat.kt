package app.androidrik.rikkahub.ui.pages.backup

import androidx.annotation.StringRes
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R

/**
 * 备份页的**导入格式描述符**：每种导入格式的单一真相源。
 *
 * 取代此前散落的写法——4 张手写卡片 + `importType: String` + `importType == "x"` 的
 * spinner 判断（三处各写一遍，新增格式要改多处且极易漏）。现在**新增一种格式 = 加一个
 * [ImportFormatSpec] + 一个解析分支**。
 *
 * ## 为什么不是 `ImportSerializer<T>` 注册表
 * 曾考虑与 `ExportSerializer<T>` 同族的接口 + 注册表，但**形状不匹配**：
 * `ExportSerializer` 是「1 个文件 → 1 个对象」，而这四种导入分别是
 * 「整个 DB」/「N 个会话 + providers」/「仅 providers」/「1 个会话」。
 * 硬套需要再包一层抽象，收益不如描述符 + 共享管线（见设计稿 §八 与实测结论）。
 */
enum class ImportFormat {
    /** 本应用**整库备份** zip（覆盖式恢复、不可逆、需重启）。 */
    LOCAL_BACKUP,

    /** 本应用**单个会话**归档（zip / 兼容阶段 1 裸 JSON）。 */
    CONVERSATION,

    /** Chatbox 导出（providers + 多个会话）。 */
    CHATBOX,

    /** Cherry Studio 备份（**仅 providers**，不含会话）。 */
    CHERRY_STUDIO,
}

/**
 * 一种导入格式的静态描述。
 *
 * @param mimes 传给 SAF `OpenDocument` 的 MIME 过滤器。
 * @param titleRes 卡片标题。
 * @param descRes 卡片副标题。
 * @param needsAssistant 是否需要用户**先选目标助手**再导入。
 *   `true` 的两个格式都涉及"会话归属"；`LOCAL_BACKUP` 是整库覆盖、`CHERRY_STUDIO` 无会话，
 *   结构上都不适用按会话归属（这也是"三处共用助手选择"实际只能落到两处的原因）。
 * @param needsPrePickConfirm 是否需要**在选文件之前**先弹一次确认。
 *   仅整库恢复需要——它覆盖当前全部数据且不可逆，先确认再让用户去挑文件。
 * @param needsRestartDialog 完成后是否提示重启。
 *   整库恢复与 providers 类导入需要（设置/DB 已变），单会话导入不需要。
 */
data class ImportFormatSpec(
    val format: ImportFormat,
    val mimes: List<String>,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    val needsAssistant: Boolean,
    val needsPrePickConfirm: Boolean,
    val needsRestartDialog: Boolean,
)

/**
 * 全部导入格式（UI 按此顺序渲染卡片）。
 *
 * `CONVERSATION` 放最前：它是本应用自家格式，也是唯一能**无损往返**的（Markdown/HTML/PNG
 * 只出不能回填）；其余属"从别的应用搬进来"。
 */
val ImportFormatSpecs: List<ImportFormatSpec> = listOf(
    ImportFormatSpec(
        format = ImportFormat.CONVERSATION,
        // 阶段 2 容器是 zip；仍接受阶段 1 的裸 JSON（解析侧按 zip 魔数自动判别）
        mimes = listOf("application/zip", "application/json"),
        titleRes = R.string.backup_page_import_conversation,
        descRes = R.string.backup_page_import_conversation_desc,
        needsAssistant = true,
        needsPrePickConfirm = false,
        needsRestartDialog = false,
    ),
    ImportFormatSpec(
        format = ImportFormat.LOCAL_BACKUP,
        mimes = listOf("application/zip"),
        titleRes = R.string.backup_page_local_backup_import,
        descRes = R.string.backup_page_import_desc,
        needsAssistant = false,
        needsPrePickConfirm = true,   // 覆盖且不可逆 → 先确认
        needsRestartDialog = true,
    ),
    ImportFormatSpec(
        format = ImportFormat.CHATBOX,
        mimes = listOf("application/json"),
        titleRes = R.string.backup_page_import_from_chatbox,
        descRes = R.string.backup_page_import_chatbox_desc,
        // 会话需归属到某个助手 → 本轮补上这一缺口（原先隐式用当前助手）
        needsAssistant = true,
        needsPrePickConfirm = false,
        needsRestartDialog = true,
    ),
    ImportFormatSpec(
        format = ImportFormat.CHERRY_STUDIO,
        mimes = listOf("application/zip"),
        titleRes = R.string.backup_page_import_from_cherry_studio,
        descRes = R.string.backup_page_import_cherry_studio_desc,
        // 只导入 providers，无会话 → 无需选助手
        needsAssistant = false,
        needsPrePickConfirm = false,
        needsRestartDialog = true,
    ),
)

/** 按 format 取描述符（UI 分发用；避免再写 `when (format)`）。 */
fun specOf(format: ImportFormat): ImportFormatSpec =
    ImportFormatSpecs.first { it.format == format }

/**
 * 合并 providers：**导入的排在前面**，当前的接在后面。
 *
 * 抽出来的原因：chatbox 与 cherry 两处原本各写一遍同样的 `imported + current`。
 * 顺序有意义——列表靠前者在 UI 上更靠前，且同名 provider 的解析按首个命中。
 *
 * **有意不去重**：这是既有行为（重复导入同一文件会产生重复 provider 条目），
 * 去重需要定义"什么算同一个 provider"（id? baseUrl? apiKey?），属独立决策，本轮只记录不改。
 */
fun mergeProviders(
    current: List<ProviderSetting>,
    imported: List<ProviderSetting>,
): List<ProviderSetting> = imported + current
