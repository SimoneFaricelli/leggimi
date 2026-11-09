package it.faricelli.leggimi.data

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import it.faricelli.leggimi.data.extract.PdfExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfExtractorTest {
    @Before
    fun init() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun makePdf(pages: List<List<String>>, bookmarks: List<Pair<String, Int>>): File {
        val file = File.createTempFile("leggimi", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            for (lines in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.setLeading(14f)
                    cs.newLineAtOffset(72f, 700f)
                    for (line in lines) {
                        cs.showText(line)
                        cs.newLine()
                    }
                    cs.endText()
                }
            }
            if (bookmarks.isNotEmpty()) {
                val outline = PDDocumentOutline()
                doc.documentCatalog.documentOutline = outline
                for ((title, pageIndex) in bookmarks) {
                    outline.addLast(PDOutlineItem().apply {
                        this.title = title
                        destination = PDPageFitDestination().apply { page = doc.getPage(pageIndex) }
                    })
                }
            }
            doc.documentInformation.title = "Manuale di prova"
            doc.save(file)
        }
        return file
    }

    @Test
    fun `testo, sillabazione e capitoli dai segnalibri`() {
        val pdf = makePdf(
            pages = listOf(
                listOf("Premessa del libro."),
                listOf("Il primo capitolo inizia qui e la paro-", "la continua a capo.", "", "17"),
                listOf("Secondo capitolo."),
            ),
            bookmarks = listOf("Capitolo 1" to 1, "Capitolo 2" to 2),
        )
        val doc = PdfExtractor.extract(pdf, "manuale.pdf")
        assertEquals("Manuale di prova", doc.title)
        val texts = doc.blocks.map { it.heading to it.text }
        assertEquals(0 to "Premessa del libro.", texts[0])
        assertEquals(1 to "Capitolo 1", texts[1])
        assertTrue(texts[2].second, texts[2].second.startsWith("Il primo capitolo inizia qui e la parola continua a capo."))
        assertTrue("numero di pagina non rimosso: $texts", texts.none { it.second == "17" })
        assertEquals(1 to "Capitolo 2", texts[texts.size - 2])
        assertEquals(0 to "Secondo capitolo.", texts.last())
    }

    /** Una riga posizionata: pagina (0-based), x, y, dimensione font, testo. */
    private data class L(val page: Int, val x: Float, val y: Float, val text: String, val size: Float = 11f)

    private fun makePositionedPdf(lines: List<L>): File {
        val file = File.createTempFile("leggimi", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            val pageCount = lines.maxOf { it.page } + 1
            repeat(pageCount) { doc.addPage(PDPage()) }
            for ((index, pageLines) in lines.groupBy { it.page }) {
                PDPageContentStream(doc, doc.getPage(index)).use { cs ->
                    for (l in pageLines) {
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, l.size)
                        cs.newLineAtOffset(l.x, l.y)
                        cs.showText(l.text)
                        cs.endText()
                    }
                }
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun `elenco puntato con rientro sospeso`() {
        val pdf = makePositionedPdf(
            listOf(
                L(0, 72f, 700f, "Obiettivi Formativi", 15f),
                L(0, 72f, 670f, "Al termine di questo percorso, il lettore sarà in grado di:"),
                L(0, 72f, 656f, "• Sviluppare un approccio sistematico e oggettivo alla degustazione, imparando a"),
                L(0, 84f, 642f, "\"staccarsi\" dalle proprie preferenze personali per formulare un giudizio tecnico."),
                L(0, 72f, 628f, "• Comprendere il meccanismo sensoriale che trasforma uno stimolo fisico (come"),
                L(0, 84f, 614f, "una molecola odorosa) in una sensazione e, infine, in una percezione consapevole e"),
                L(0, 84f, 600f, "riconosciuta."),
                L(0, 72f, 586f, "• Apprendere e utilizzare un linguaggio tecnico condiviso, una terminologia"),
                L(0, 84f, 572f, "standardizzata che permette di descrivere le caratteristiche di un vino."),
            )
        )
        val texts = PdfExtractor.extract(pdf, "lezione.pdf").blocks.map { it.text }
        assertEquals(
            listOf(
                "Obiettivi Formativi",
                "Al termine di questo percorso, il lettore sarà in grado di:",
                "• Sviluppare un approccio sistematico e oggettivo alla degustazione, imparando a " +
                    "\"staccarsi\" dalle proprie preferenze personali per formulare un giudizio tecnico.",
                "• Comprendere il meccanismo sensoriale che trasforma uno stimolo fisico (come " +
                    "una molecola odorosa) in una sensazione e, infine, in una percezione consapevole e riconosciuta.",
                "• Apprendere e utilizzare un linguaggio tecnico condiviso, una terminologia " +
                    "standardizzata che permette di descrivere le caratteristiche di un vino.",
            ),
            texts,
        )
    }

    @Test
    fun `paragrafo a cavallo di due pagine e intestazioni ripetute`() {
        val header = { p: Int -> L(p, 72f, 750f, "Lezione 5 - Tecnica della degustazione", 9f) }
        val footer = { p: Int -> L(p, 290f, 40f, "Pagina ${p + 1} di 3", 9f) }
        val pdf = makePositionedPdf(
            listOf(
                header(0), footer(0), header(1), footer(1), header(2), footer(2),
                L(0, 72f, 700f, "Il colore del vino dipende dal vitigno, dall'età e dalla tecnica di"),
                L(0, 72f, 686f, "vinificazione adottata in cantina, oltre che dalle condizioni dell'an-"),
                L(1, 72f, 700f, "nata e dal tipo di affinamento scelto dal produttore."),
                L(1, 72f, 670f, "Il secondo paragrafo comincia qui."),
                L(2, 72f, 700f, "Terza pagina."),
            )
        )
        val texts = PdfExtractor.extract(pdf, "lezione.pdf").blocks.map { it.text }
        assertEquals(
            listOf(
                "Il colore del vino dipende dal vitigno, dall'età e dalla tecnica di vinificazione adottata " +
                    "in cantina, oltre che dalle condizioni dell'annata e dal tipo di affinamento scelto dal produttore.",
                "Il secondo paragrafo comincia qui.",
                "Terza pagina.",
            ),
            texts,
        )
    }
}
