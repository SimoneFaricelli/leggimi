package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.UserFacingException
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.io.File

object OdtExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument = openZip(file, "documento ODT") { zip ->
        val content = zip.readXml("content.xml")
            ?: throw UserFacingException("Il file non contiene un documento ODT leggibile.")
        val metaTitle = zip.readXml("meta.xml")?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.trim()
        val out = BlockListBuilder()
        var firstTitle: String? = null
        val body = content.getElementsByTag("office:text").firstOrNull() ?: content
        for (e in body.allElements) {
            val tag = e.tagName()
            if (tag != "text:p" && tag != "text:h") continue
            if (e.parents().any { it.tagName() in setOf("text:note", "text:p", "text:h", "office:annotation") }) continue
            val text = elementText(e)
            if (tag == "text:h") {
                out.heading(e.attr("text:outline-level").toIntOrNull() ?: 1, text)
            } else if (e.attr("text:style-name").equals("Title", ignoreCase = true)) {
                if (firstTitle == null) firstTitle = text.trim()
                out.heading(1, text)
            } else {
                out.paragraph(text)
            }
        }
        val title = metaTitle?.takeIf { it.isNotEmpty() } ?: firstTitle?.takeIf { it.isNotEmpty() }
            ?: titleFromFileName(displayName)
        ParsedDocument(title, out.build())
    }

    private fun elementText(e: Element): String {
        val sb = StringBuilder()
        fun walk(el: Element) {
            for (child in el.childNodes()) {
                when (child) {
                    is TextNode -> sb.append(child.wholeText)
                    is Element -> when (child.tagName()) {
                        "text:s" -> repeat(child.attr("text:c").toIntOrNull() ?: 1) { sb.append(' ') }
                        "text:tab", "text:line-break" -> sb.append(' ')
                        "text:note", "office:annotation", "text:p", "text:h", "text:bookmark-ref" -> Unit
                        else -> walk(child)
                    }
                }
            }
        }
        walk(e)
        return sb.toString()
    }
}
