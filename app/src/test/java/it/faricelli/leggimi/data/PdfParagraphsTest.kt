package it.faricelli.leggimi.data

import it.faricelli.leggimi.data.extract.PdfLine
import it.faricelli.leggimi.data.extract.PdfParagraphs
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfParagraphsTest {
    /** Righe di una pagina con interlinea 14 a partire da y = 100; la larghezza dipende dal testo (6 pt/carattere). */
    private class PageBuilder(val page: Int, var y: Float = 100f) {
        val lines = mutableListOf<PdfLine>()

        fun line(text: String, x: Float = 72f, size: Float = 11f, gap: Float = 14f, width: Float? = null) {
            lines += PdfLine(page, text, x, x + (width ?: (text.length * 6f)), y, size)
            y += gap
        }

        /** Una riga piena, che arriva al margine destro (540). */
        fun full(text: String, x: Float = 72f) = line(text, x, width = 540f - x)
    }

    private fun page(page: Int = 1, y: Float = 100f, block: PageBuilder.() -> Unit) = PageBuilder(page, y).apply(block).lines

    private fun build(lines: List<PdfLine>, pages: Int = 1) = PdfParagraphs.build(PdfParagraphs.clean(lines, pages))

    @Test
    fun `elenco con rientro sospeso`() {
        val lines = page {
            full("Al termine di questo percorso, il lettore sarà in grado di:")
            full("• Sviluppare un approccio sistematico e oggettivo alla degustazione, imparando a")
            full("\"staccarsi\" dalle preferenze personali per un giudizio tecnico.", x = 84f)
            full("• Comprendere il meccanismo sensoriale che trasforma uno stimolo fisico (come")
            full("una molecola odorosa) in una sensazione e in una percezione consapevole e", x = 84f)
            line("riconosciuta.", x = 84f)
            line("• Apprendere un linguaggio tecnico condiviso.")
        }
        assertEquals(
            listOf(
                "Al termine di questo percorso, il lettore sarà in grado di:",
                "• Sviluppare un approccio sistematico e oggettivo alla degustazione, imparando a " +
                    "\"staccarsi\" dalle preferenze personali per un giudizio tecnico.",
                "• Comprendere il meccanismo sensoriale che trasforma uno stimolo fisico (come " +
                    "una molecola odorosa) in una sensazione e in una percezione consapevole e riconosciuta.",
                "• Apprendere un linguaggio tecnico condiviso.",
            ),
            build(lines),
        )
    }

    @Test
    fun `prima riga rientrata senza spazio tra i paragrafi`() {
        val lines = page {
            full("Era una notte buia e tempestosa, e il vento soffiava forte tra gli", x = 90f)
            full("alberi del parco. Nessuno sapeva cosa sarebbe successo, ma tutti lo")
            full("temevano.") // riga piena che termina con un punto: decide il rientro della successiva
            full("Il mattino dopo il cielo era sereno e la città sembrava un'altra.", x = 90f)
            line("Nessuno ne parlò più.")
        }
        assertEquals(
            listOf(
                "Era una notte buia e tempestosa, e il vento soffiava forte tra gli alberi del parco. " +
                    "Nessuno sapeva cosa sarebbe successo, ma tutti lo temevano.",
                "Il mattino dopo il cielo era sereno e la città sembrava un'altra. Nessuno ne parlò più.",
            ),
            build(lines),
        )
    }

    @Test
    fun `testo giustificato con spazio tra i paragrafi`() {
        val lines = page {
            full("Il primo paragrafo occupa due righe intere e finisce con un punto proprio al")
            full("margine destro della colonna.", x = 72f)
            y += 10f
            full("Il secondo paragrafo comincia dopo uno spazio verticale più ampio.")
        }
        assertEquals(
            listOf(
                "Il primo paragrafo occupa due righe intere e finisce con un punto proprio al margine destro della colonna.",
                "Il secondo paragrafo comincia dopo uno spazio verticale più ampio.",
            ),
            build(lines),
        )
    }

    @Test
    fun `paragrafo a cavallo di pagina, sillabazione e intestazioni ripetute`() {
        val lines = (1..3).flatMap { p ->
            page(p, y = 40f) {
                line("Lezione 5 - Tecnica della degustazione", size = 9f, gap = 60f)
                when (p) {
                    1 -> {
                        full("Il colore del vino dipende dal vitigno, dall'età e dalla tecnica di")
                        full("vinificazione, oltre che dalle condizioni dell'an-")
                    }
                    2 -> {
                        line("nata.")
                        full("Secondo paragrafo della seconda pagina che continua sulla terza")
                    }
                    3 -> line("pagina senza interruzioni.")
                }
                y = 760f
                line("Pagina $p di 3", x = 290f, size = 9f)
            }
        }
        assertEquals(
            listOf(
                "Il colore del vino dipende dal vitigno, dall'età e dalla tecnica di vinificazione, " +
                    "oltre che dalle condizioni dell'annata.",
                "Secondo paragrafo della seconda pagina che continua sulla terza pagina senza interruzioni.",
            ),
            build(lines, pages = 3),
        )
    }

    @Test
    fun `titolo con carattere più grande, pallini dei font simbolo e numeri di pagina`() {
        val lines = page {
            line("Obiettivi Formativi", size = 15f, gap = 20f)
            full("Il corso si articola in due parti")
            line(" analisi visiva;")
            line(" analisi olfattiva.")
            line("12", x = 300f)
        }
        assertEquals(
            listOf(
                "Obiettivi Formativi",
                "Il corso si articola in due parti",
                "• analisi visiva;",
                "• analisi olfattiva.",
            ),
            build(lines),
        )
    }

    @Test
    fun `un trattino a inizio riga apre un elenco solo dopo una fine frase`() {
        val lines = page {
            full("Il vino, servito alla giusta temperatura")
            full("– e nel bicchiere adatto – esprime al meglio i suoi profumi:")
            full("- primo punto dell'elenco;")
            line("- secondo punto.")
        }
        assertEquals(
            listOf(
                "Il vino, servito alla giusta temperatura – e nel bicchiere adatto – esprime al meglio i suoi profumi:",
                "- primo punto dell'elenco;",
                "- secondo punto.",
            ),
            build(lines),
        )
    }
}
