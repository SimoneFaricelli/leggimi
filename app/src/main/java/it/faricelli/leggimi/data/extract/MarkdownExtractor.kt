package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import java.io.File

object MarkdownExtractor : DocumentExtractor {
    override fun extract(file: File, displayName: String): ParsedDocument {
        val source = TxtExtractor.decodeText(file.readBytes())
        val root = Parser.builder().build().parse(source)
        val out = BlockListBuilder()
        var firstH1: String? = null

        root.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val t = inlineText(heading)
                if (heading.level == 1 && firstH1 == null) firstH1 = t
                out.heading(heading.level, t)
            }

            override fun visit(paragraph: Paragraph) = out.paragraph(inlineText(paragraph))
            override fun visit(fencedCodeBlock: FencedCodeBlock) = Unit
            override fun visit(indentedCodeBlock: IndentedCodeBlock) = Unit
            override fun visit(htmlBlock: HtmlBlock) = out.paragraph(Jsoup.parse(htmlBlock.literal).text())
        })
        val title = firstH1 ?: titleFromFileName(displayName)
        return ParsedDocument(title, out.build())
    }

    private fun inlineText(node: Node): String {
        val sb = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) { sb.append(text.literal) }
            override fun visit(code: Code) { sb.append(code.literal) }
            override fun visit(softLineBreak: SoftLineBreak) { sb.append(' ') }
            override fun visit(hardLineBreak: HardLineBreak) { sb.append(' ') }
            override fun visit(htmlInline: HtmlInline) = Unit
            override fun visit(image: Image) = Unit
        })
        return sb.toString()
    }
}
