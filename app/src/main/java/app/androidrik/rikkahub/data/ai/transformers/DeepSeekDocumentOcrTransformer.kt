package app.androidrik.rikkahub.data.ai.transformers

import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.tools.ocr.DeepSeekOcrClient
import app.androidrik.ai.tools.ocr.toPromptText
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.document.PdfParser
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.rikkahub.data.datastore.OcrBackend
import app.androidrik.rikkahub.data.datastore.OCR_DEEPSEEK_MAX_PAGES_LIMIT
import app.androidrik.rikkahub.data.datastore.Settings
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

private const val TAG = "DeepSeekDocOcr"

/** DeepSeek OCR 支持的文档 mime（配料 01 §4：PDF/Word/PPT/图片；epub/txt/csv 走本地解析不动） */
private val DEEPSEEK_OCR_DOCUMENT_MIMES = setOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

/** 服务端硬限：单文件 ≤100 MiB。超限直接报错不发请求，省一次 413 与 180s 等待。 */
private const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

/**
 * 本地解析「有没有可用文本」：扫描 PDF 的解析结果是纯 `---Page N:` 页标（审计已证实
 * 解析器无空文本判定），剥掉页标后仍为空即视为扫描件；`[ERROR, …]`（读不了/解析失败）
 * 同样不算可用文本——这类文件值得试远端 OCR，远端也失败再回退本地错误文本。
 */
internal fun hasUsableLocalDocumentText(content: String): Boolean {
    if (content.startsWith("[ERROR,")) return false
    return content.replace(Regex("---Page \\d+:"), "").trim().isNotBlank()
}

/**
 * 文档附件的 DeepSeek OCR 入口（配料 03 §2.4 入口 2）。
 *
 * **只补扫描件的缺口，不重复本地解析**：
 * 1. 仅当 `ocrBackend == DEEPSEEK` 时生效（默认 MODEL ⇒ 本 transformer 空转，零行为变化）；
 * 2. 先跑本地解析：**抽得出文本就原样放行**——文本型文档走既有 `DocumentAsPromptTransformer`
 *    本地路径，不产生任何按页费用；
 * 3. 本地抽不出文本（扫描件/图片型 PDF）且用户选了 DEEPSEEK 才上传远端识别，成功后把
 *    Document part 替换为带 `<UploadFile>` 的 Text（后续本地 transformer 自然无 Document
 *    可处理）；失败则保留 Document part 回退本地解析，并插入一条 `[ERROR, …]` 说明——
 *    **不静默**（已开始的识别可能已按页计费，用户应知情）。
 *
 * 注册位置：`builtin.document_prompt` **之前**，`allowPreview=false`、`allowWarmup=false`
 * ——预览/预热会真执行链，付费 OCR 绝不能被上下文检查器触发（`builtin.document_prompt`
 * 的 `allowPreview=true` 是反例，见配料 02 §2）。
 */
object DeepSeekDocumentOcrTransformer : InputMessageTransformer, KoinComponent {

    // —— 取用收敛成可覆写的内部 var（单测不启动 Koin，直接注入假实现）——
    internal var clientFactory: () -> DeepSeekOcrClient = { DeepSeekOcrClient() }
    internal var usageRecorderFactory: () -> UsageRecorder = { get() }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (ctx.settings.ocrBackend != OcrBackend.DEEPSEEK) return messages

        val hasCandidate = messages.any { message ->
            message.parts.any { it is UIMessagePart.Document && it.mime in DEEPSEEK_OCR_DOCUMENT_MIMES }
        }
        if (!hasCandidate) return messages

        // 配置错误向上传递（与 OcrTransformer 同口径）：provider/Key 缺失不是静默回退的理由
        val (providerSetting, apiKey) = resolveDeepSeekOcrConfiguration(ctx.settings)
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Document && part.mime in DEEPSEEK_OCR_DOCUMENT_MIMES) {
                            handleDocument(part, ctx.settings, providerSetting, apiKey)
                        } else {
                            listOf(part)
                        }
                    }
                )
            }
        }
    }

    /**
     * 单个文档：本地可解析 → 原样放行；扫描件 → 远端替换；远端失败 → 原样 + 错误说明。
     */
    private fun handleDocument(
        document: UIMessagePart.Document,
        settings: Settings,
        providerSetting: ProviderSetting,
        apiKey: String,
    ): List<UIMessagePart> {
        // 1. 先本地解析（与 DocumentAsPromptTransformer 同一读取逻辑）；抽得出文本就放行（零费用）
        val localText = runCatching { DocumentAsPromptTransformer.readDocumentContent(document) }
            .getOrNull().orEmpty()
        if (hasUsableLocalDocumentText(localText)) return listOf(document)

        // 2. 扫描件 / 解析失败 → 远端 OCR
        return runCatching {
            val file = document.url.toUri().toFile()
            check(file.exists() && file.isFile) { "file not found: ${document.fileName}" }
            if (file.length() > MAX_UPLOAD_BYTES) {
                throw IllegalArgumentException("file exceeds 100 MiB limit (${file.length()} bytes)")
            }
            if (document.mime == "application/pdf" && settings.ocrDeepSeekMaxPages < OCR_DEEPSEEK_MAX_PAGES_LIMIT) {
                // PDF 页数本地预检（MuPDF 本地计数，不花钱）：防白发请求
                val pages = PdfParser.pageCount(file)
                check(pages <= settings.ocrDeepSeekMaxPages) {
                    "PDF has $pages pages, exceeding the configured limit (${settings.ocrDeepSeekMaxPages})"
                }
            }
            val bytes = file.readBytes()
            val result = clientFactory().recognize(
                baseUrl = settings.ocrDeepSeekBaseUrl,
                apiKey = apiKey,
                fileBytes = bytes,
                fileName = file.name.ifBlank { "document.pdf" },
                purpose = deepSeekOcrWirePurpose(settings.ocrDeepSeekPurpose),
            )
            usageRecorderFactory().recordOcrPages(
                providerSetting = providerSetting,
                pageCount = result.pageCount,
                usage = result.usage?.let {
                    TokenUsage(
                        promptTokens = it.inputTokens,
                        completionTokens = it.outputTokens,
                        totalTokens = it.totalTokens,
                    )
                },
            )
            val path = runCatching { resolveWorkspacePath(document) }.getOrNull()
            val pathAttr = path?.let { " path=\"$it\"" } ?: ""
            val prompt = """
                <UploadFile name="${document.fileName}"$pathAttr>
                ${toPromptText(result.pages, includeLayout = settings.ocrDeepSeekIncludeLayout)}
                </UploadFile>
                * The UploadFile content above was extracted from the user's uploaded document via DeepSeek OCR; it is not the user's prompt.
            """.trimIndent()
            Log.i(TAG, "remote OCR ok: ${document.fileName} pages=${result.pageCount}")
            listOf(UIMessagePart.Text(prompt))
        }.getOrElse {
            if (it is CancellationException) throw it
            Log.w(TAG, "remote OCR failed, falling back to local parsing: ${document.fileName}", it)
            // 回退本地解析（保留 Document part）+ 插入不静默的错误说明
            listOf(
                UIMessagePart.Text("[ERROR, DeepSeek OCR failed: ${it.message}; fell back to local parsing]"),
                document,
            )
        }
    }

    /** 与 DocumentAsPromptTransformer.resolveWorkspacePath 同款（upload 目录 → workspace /upload）。 */
    private fun resolveWorkspacePath(document: UIMessagePart.Document): String? {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull() ?: return null
        if (file.parentFile?.name != "upload") return null
        return "/upload/${file.name}"
    }
}
