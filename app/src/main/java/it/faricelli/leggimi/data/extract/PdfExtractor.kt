package it.faricelli.leggimi.data.extract

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import it.faricelli.leggimi.data.text.BlockListBuilder
import it.faricelli.leggimi.data.text.ParsedDocument
import it.faricelli.leggimi.data.text.UserFacingException
import java.io.File
import java.io.IOException
import java.io.StringWriter

/** Richiede PDFBoxResourceLoader.init(context) all'avvio dell'app. */
object PdfExtractor : DocumentExtractor {
    /** 2: paragrafi ricostruiti dalla geometria delle righe ([PdfParagraphs]) invece che da PdfBox. */
    override val version: Int = 2

    override fun extract(file: File, displayName: String): ParsedDocument {
        val pdf = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw UserFacingException("Il PDF è protetto da password.", e)
        } catch (e: IOException) {
            throw UserFacingException("Il PDF è danneggiato o non leggibile.", e)
        }
        return pdf.use { doc ->
            val pageCount = doc.numberOfPages
            val collector = LineCollector().apply { writeText(doc, StringWriter()) }
            val lines = PdfParagraphs.clean(collector.lines, pageCount)

            val out = BlockListBuilder()
            fun addPages(from: Int, to: Int) =
                PdfParagraphs.build(lines.filter { it.page in from..to }).forEach(out::paragraph)

            val chapters = outlineChapters(doc)
            var nextPage = 1
            for ((i, chapter) in chapters.withIndex()) {
                val (chapterTitle, startPage) = chapter
                if (startPage > nextPage) addPages(nextPage, startPage - 1)
                val endPage = chapters.getOrNull(i + 1)?.second?.minus(1) ?: pageCount
                out.heading(1, chapterTitle)
                if (endPage >= startPage) addPages(startPage, endPage)
                nextPage = maxOf(nextPage, endPage + 1)
            }
            if (nextPage <= pageCount) addPages(nextPage, pageCount)

            val title = doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleFromFileName(displayName)
            ParsedDocument(title, out.build())
        }
    }

    /**
     * Invece di scrivere testo, raccoglie le righe che PdfBox ricostruisce (in ordine di lettura)
     * con la loro posizione. I separatori di paragrafo di PdfBox vengono ignorati.
     */
    private class LineCollector : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()
        private val text = StringBuilder()
        private val positions = mutableListOf<TextPosition>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            this.text.append(text)
            positions += textPositions
        }

        override fun writeWordSeparator() {
            if (text.isNotEmpty()) text.append(' ')
        }

        override fun writeLineSeparator() = flush()
        override fun writeParagraphStart() = flush()
        override fun writeParagraphEnd() = flush()
        override fun writePageStart() = flush()
        override fun writePageEnd() = flush()

        private fun flush() {
            if (positions.isNotEmpty() && text.isNotBlank()) {
                lines += PdfLine(
                    page = currentPageNo,
                    text = text.toString(),
                    xStart = positions.minOf { it.xDirAdj },
                    xEnd = positions.maxOf { it.xDirAdj + it.widthDirAdj },
                    y = positions.map { it.yDirAdj }.sorted()[positions.size / 2],
                    fontSize = positions.map { it.fontSizeInPt }.sorted()[positions.size / 2],
                )
            }
            text.clear()
            positions.clear()
        }
    }

    /** Segnalibri di primo livello come (titolo, pagina iniziale 1-based), ordinati e con pagine crescenti. */
    private fun outlineChapters(doc: PDDocument): List<Pair<String, Int>> = try {
        val result = mutableListOf<Pair<String, Int>>()
        var item: PDOutlineItem? = doc.documentCatalog?.documentOutline?.firstChild
        while (item != null) {
            val page = item.findDestinationPage(doc)
            val index = if (page != null) doc.pages.indexOf(page) else -1
            val title = item.title?.trim().orEmpty()
            if (index >= 0 && title.isNotEmpty()) result += title to (index + 1)
            item = item.nextSibling
        }
        result.sortedBy { it.second }.distinctBy { it.second }
    } catch (_: Exception) {
        emptyList()
    }
}
