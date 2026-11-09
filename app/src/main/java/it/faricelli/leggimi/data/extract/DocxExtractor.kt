package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.UserFacingException
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File

object DocxExtractor : DocumentExtractor {
    private val HEADING_NAME = Regex("^(heading|titolo|überschrift|titre|título|encabezado)\\s*(\\d)$", RegexOption.IGNORE_CASE)

    override fun extract(file: File, displayName: String): ParsedDocument = openZip(file, "documento Word") { zip ->
        val doc = zip.readXml("word/document.xml")
            ?: throw UserFacingException("Il file non contiene un documento Word leggibile.")
        val styleLevels = zip.readXml("word/styles.xml")?.let(::headingLevelsByStyle).orEmpty()
        val coreTitle = zip.readXml("docProps/core.xml")?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.trim()

        val out = BlockListBuilder()
        var titleFromStyle: String? = null
        val body = doc.getElementsByTag("w:body").firstOrNull() ?: doc
        for (p in body.getElementsByTag("w:p")) {
            val text = paragraphText(p)
            val pPr = p.children().firstOrNull { it.tagName() == "w:pPr" }
            val styleId = pPr?.getElementsByTag("w:pStyle")?.firstOrNull()?.attr("w:val")
            val ownOutline = pPr?.getElementsByTag("w:outlineLvl")?.firstOrNull()?.attr("w:val")?.toIntOrNull()
            val level = when {
                styleId != null && styleLevels[styleId] == 0 -> {
                    if (titleFromStyle == null) titleFromStyle = text.trim()
                    1
                }
                ownOutline != null && ownOutline in 0..5 -> ownOutline + 1
                styleId != null -> styleLevels[styleId] ?: 0
                else -> 0
            }
            if (level > 0) out.heading(level, text) else out.paragraph(text)
        }
        val title = coreTitle?.takeIf { it.isNotEmpty() } ?: titleFromStyle?.takeIf { it.isNotEmpty() }
            ?: titleFromFileName(displayName)
        ParsedDocument(title, out.build())
    }

    /** styleId -> livello di titolo (0 = stile "Title", 1..6 = Heading N). */
    private fun headingLevelsByStyle(styles: org.jsoup.nodes.Document): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (style in styles.getElementsByTag("w:style")) {
            if (style.attr("w:type") != "paragraph") continue
            val id = style.attr("w:styleId").ifEmpty { continue }
            val name = style.getElementsByTag("w:name").firstOrNull()?.attr("w:val")?.trim().orEmpty()
            val outline = style.getElementsByTag("w:outlineLvl").firstOrNull()?.attr("w:val")?.toIntOrNull()
            val level = when {
                name.equals("title", true) || name.equals("titolo", true) -> 0
                HEADING_NAME.matchEntire(name) != null -> HEADING_NAME.matchEntire(name)!!.groupValues[2].toInt()
                outline != null && outline in 0..5 -> outline + 1
                else -> null
            }
            if (level != null) result[id] = level
        }
        return result
    }

    /** Testo di un paragrafo, senza scendere nei paragrafi annidati (caselle di testo). */
    private fun paragraphText(p: Element): String {
        val sb = StringBuilder()
        fun walk(e: Element) {
            for (child in e.childNodes()) {
                if (child !is Element) continue
                when (child.tagName()) {
                    "w:p", "w:delText", "w:instrText", "w:footnoteReference", "w:endnoteReference",
                    "w:commentReference", "w:del" -> Unit
                    "w:t" -> child.childNodes().forEach { if (it is TextNode) sb.append(it.wholeText) }
                    "w:tab", "w:br", "w:cr" -> sb.append(' ')
                    "w:noBreakHyphen" -> sb.append('-')
                    "w:sym" -> Unit
                    else -> walk(child)
                }
            }
        }
        walk(p)
        return sb.toString()
    }
}
