package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.Block
import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeFilter
import java.io.File

object HtmlExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument {
        val doc = Jsoup.parse(file, null)
        val title = doc.title().trim().ifEmpty { titleFromFileName(displayName) }
        return ParsedDocument(title, HtmlBlocks.extract(doc.body()))
    }
}

/** Trasforma un albero HTML/XHTML in blocchi (paragrafi e titoli) nell'ordine di lettura. */
object HtmlBlocks {
    private val SKIP = setOf(
        "script", "style", "noscript", "template", "svg", "math", "nav", "head", "button", "select",
        "input", "textarea", "iframe", "object", "video", "audio", "canvas", "rt", "rp",
    )
    private val BLOCK = setOf(
        "p", "div", "li", "blockquote", "section", "article", "aside", "header", "footer", "main",
        "pre", "table", "tr", "td", "th", "ul", "ol", "dl", "dt", "dd", "figure", "figcaption",
        "h1", "h2", "h3", "h4", "h5", "h6", "hr", "address", "center", "body", "caption",
    )
    private val NOTE_TYPES = listOf("noteref", "footnote", "endnote", "rearnote", "pagebreak")

    fun extract(root: Element): List<Block> {
        val out = BlockListBuilder()
        val buffer = StringBuilder()

        fun flush(headingLevel: Int = 0) {
            val text = buffer.toString()
            buffer.clear()
            if (headingLevel > 0) out.heading(headingLevel, text) else out.paragraph(text)
        }

        root.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): NodeFilter.FilterResult {
                when (node) {
                    is TextNode -> buffer.append(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        if (tag in SKIP || isNote(node)) return NodeFilter.FilterResult.SKIP_ENTIRELY
                        when {
                            tag == "br" -> buffer.append(' ')
                            tag == "img" -> Unit
                            tag in BLOCK -> flush()
                        }
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): NodeFilter.FilterResult {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in BLOCK) {
                        val level = if (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()) tag[1] - '0' else 0
                        flush(level)
                    } else if (tag in setOf("td", "th")) {
                        buffer.append(' ')
                    }
                }
                return NodeFilter.FilterResult.CONTINUE
            }
        })
        flush()
        return out.build()
    }

    private fun isNote(e: Element): Boolean {
        val type = (e.attr("epub:type") + " " + e.attr("role")).lowercase()
        return NOTE_TYPES.any { it in type } || "doc-noteref" in type || "doc-footnote" in type || "doc-pagebreak" in type
    }
}
