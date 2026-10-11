package app.androidrik.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String
)

object EpubParser {
    fun parse(file: File): String {
        return try {
            ZipFile(file).use { zip ->
                val opfPath = findOpfPath(zip)
                    ?: return "Unable to find OPF file in EPUB"
                val opfDir = opfPath.substringBeforeLast('/', "")

                val opfEntry = zip.getEntry(opfPath)
                    ?: return "Unable to read OPF file in EPUB"
                val (manifest, spine) = zip.getInputStream(opfEntry).use { parseOpf(it) }

                val result = StringBuilder()
                for (itemId in spine) {
                    val item = manifest[itemId] ?: continue
                    if (!item.mediaType.contains("html")) continue

                    val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
                    val entry = zip.getEntry(itemPath) ?: continue
                    val content = zip.getInputStream(entry).use { parseXhtml(it) }
                    if (content.isNotBlank()) {
                        result.append(content)
                        result.append("\n\n")
                    }
                }

                result.toString().trim().ifEmpty { "No readable content found in EPUB file" }
            }
        } catch (e: Exception) {
            "Error parsing EPUB file: ${e.message}"
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(containerEntry).use { stream ->
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    return@use parser.getAttributeValue(null, "full-path")
                }
                parser.next()
            }
            null
        }
    }

    private fun parseOpf(inputStream: InputStream): Pair<Map<String, ManifestItem>, List<String>> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val manifest = mutableMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        val href = parser.getAttributeValue(null, "href") ?: ""
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id.isNotEmpty()) {
                            manifest[id] = ManifestItem(id, href, mediaType)
                        }
                    }

                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref") ?: ""
                        if (idref.isNotEmpty()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            parser.next()
        }

        return manifest to spine
    }

    /** 旧接口的行为：解析失败当"没有内容"（附件路径保持原样） */
    private fun parseXhtml(inputStream: InputStream): String = parseXhtmlOrNull(inputStream).orEmpty()

    /**
     * 抽一节正文；**解析失败返回 null**（而不是空串）。
     *
     * 分离出来的原因：旧实现把失败静默成空串，"这一节是空的"和"这一节读不了"就分不出来了——
     * 附件路径无所谓，但工具层要把真实原因告诉模型（空 ≠ 失败）。
     */
    private fun parseXhtmlOrNull(inputStream: InputStream): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            // 关掉 DOCDECL 处理只是加固：**有的实现不支持这个 feature 并抛异常**
            // （JVM 上的 XmlPull 实现就是如此）。为此把整篇文档作废不划算，所以失败就跳过。
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            parser.setInput(inputStream, "UTF-8")

            val result = StringBuilder()
            val tagStack = ArrayDeque<String>()
            var inBody = false
            var listCounter = 0

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.lowercase()
                        tagStack.addLast(tag)

                        when (tag) {
                            "body" -> inBody = true
                            "ol" -> listCounter = 0
                            "li" -> {
                                val parentTag = tagStack.dropLast(1).lastOrNull()
                                if (parentTag == "ol") {
                                    listCounter++
                                    result.append("$listCounter. ")
                                } else {
                                    result.append("- ")
                                }
                            }

                            "br" -> result.append("\n")
                            "img" -> {
                                if (inBody) {
                                    val alt = parser.getAttributeValue(null, "alt")
                                    if (!alt.isNullOrBlank()) {
                                        result.append("[image: $alt]")
                                    }
                                }
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) {
                                    val level = tag[1].digitToInt()
                                    result.append("${"#".repeat(level)} ")
                                }
                            }

                            "strong", "b" -> {
                                if (inBody) result.append("**")
                            }

                            "em", "i" -> {
                                if (inBody) result.append("*")
                            }

                            "hr" -> {
                                if (inBody) result.append("\n---\n")
                            }

                            "blockquote" -> {
                                if (inBody) result.append("> ")
                            }
                        }
                    }

                    XmlPullParser.TEXT -> {
                        if (inBody) {
                            val text = parser.text
                                ?.replace('\n', ' ')
                                ?.replace('\r', ' ')
                                ?.replace("\\s+".toRegex(), " ")
                            if (!text.isNullOrBlank()) {
                                result.append(text)
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.lowercase()
                        if (tagStack.isNotEmpty()) tagStack.removeLast()

                        when (tag) {
                            "body" -> inBody = false
                            "p", "div" -> {
                                if (inBody) result.append("\n\n")
                            }

                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (inBody) result.append("\n\n")
                            }

                            "li" -> {
                                if (inBody) result.append("\n")
                            }

                            "ul", "ol" -> {
                                if (inBody) result.append("\n")
                            }

                            "br" -> {}
                            "strong", "b" -> {
                                if (inBody) result.append("**")
                            }

                            "em", "i" -> {
                                if (inBody) result.append("*")
                            }

                            "blockquote" -> {
                                if (inBody) result.append("\n")
                            }
                        }
                    }
                }
                try {
                    parser.next()
                } catch (_: Exception) {
                    break
                }
            }

            result.toString()
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        } catch (e: Exception) {
            null
        }
    }

    /** 可读章节数（spine 里 mediaType 含 html 的条目） */
    fun sectionCount(file: File): Int = ZipFile(file).use { zip -> readableSections(zip).size }

    /**
     * 只抽第 [startSection] 章起、最多 [sectionCount] 章（1 基，只数**可读章节**）。
     *
     * 与 `parse` 的差别：`parse` 出于"给附件用"的考虑会把抽不出的章节跳过（返回串也可能是一句错误
     * 文案）；这里则明确区分"没有这一章"（空片段）与"读不了这本书"（抛异常），因为工具层要把
     * 这两种情况如实告诉模型。
     */
    fun parseSections(file: File, startSection: Int, sectionCount: Int): DocumentSlice {
        ZipFile(file).use { zip ->
            val sections = readableSections(zip)
            val total = sections.size
            val start = startSection.coerceAtLeast(1)
            if (start > total) return DocumentSlice("", start, start - 1, total)
            val end = (start + sectionCount - 1).coerceAtMost(total)

            val result = StringBuilder()
            for (index in (start - 1)..(end - 1)) {
                val (label, entry) = sections[index]
                val content = zip.getInputStream(entry).use { parseXhtmlOrNull(it) }
                    ?: throw IllegalStateException("Unable to parse section: $label")
                if (content.isNotBlank()) {
                    result.append("[").append(label).append("]\n")
                    result.append(content)
                    result.append("\n\n")
                }
            }
            return DocumentSlice(
                text = result.toString().trim(),
                firstUnit = start,
                lastUnit = end,
                totalUnits = total,
            )
        }
    }

    /** spine 顺序里的可读章节（[label] 用条目名，便于模型引用） */
    private fun readableSections(zip: ZipFile): List<Pair<String, java.util.zip.ZipEntry>> {
        val opfPath = findOpfPath(zip) ?: throw IllegalStateException("Unable to find OPF file in EPUB")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opfEntry = zip.getEntry(opfPath) ?: throw IllegalStateException("Unable to read OPF file in EPUB")
        val (manifest, spine) = zip.getInputStream(opfEntry).use { parseOpf(it) }
        return spine.mapNotNull { itemId ->
            val item = manifest[itemId] ?: return@mapNotNull null
            if (!item.mediaType.contains("html")) return@mapNotNull null
            val itemPath = if (opfDir.isEmpty()) item.href else "$opfDir/${item.href}"
            val entry = zip.getEntry(itemPath) ?: return@mapNotNull null
            entry.name to entry
        }
    }
}
