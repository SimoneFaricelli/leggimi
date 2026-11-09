package it.faricelli.leggimi.data.extract

import it.faricelli.leggimi.data.text.isPageNumberLine
import it.faricelli.leggimi.data.text.joinLine
import it.faricelli.leggimi.data.text.normalizeSpaces
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Una riga di testo di un PDF con la sua posizione sulla pagina.
 * Le coordinate sono in punti, con [y] (linea di base) che cresce verso il basso.
 */
data class PdfLine(
    val page: Int,
    val text: String,
    val xStart: Float,
    val xEnd: Float,
    val y: Float,
    val fontSize: Float,
)

/**
 * Ricostruisce i paragrafi di un PDF dalla geometria delle righe.
 *
 * Il principio: due righe si uniscono, a meno di un segnale chiaro di fine paragrafo
 * (inizio di un elenco, spazio verticale ampio, cambio di carattere, frase che termina
 * su una riga corta o seguita da un rientro). Il rientro da solo non spezza mai, così
 * gli elenchi con rientro sospeso e i paragrafi a cavallo di pagina restano interi.
 */
object PdfParagraphs {
    /** Marcatori di elenco sempre affidabili: pallini e numerazioni. */
    private val LIST_MARKER = Regex("^([•●○◦▪■□►▸‣⁃✓✔➢➤→]|\\d{1,3}[.)]|[a-z]\\.|[a-zA-Z]\\)|[ivxIVX]{1,5}[.)])\\s")

    /** Trattini e asterischi: aprono un elenco solo se la riga prima chiude una frase. */
    private val DASH_MARKER = Regex("^[*–—-]\\s")

    /** Fine frase, eventualmente seguita da virgolette o parentesi chiuse. */
    private val SENTENCE_END = Regex("[.!?:;…][\"'»”’)\\]]*$")

    /** Pallini dei font Symbol/Wingdings, che arrivano come caratteri dell'area privata Unicode. */
    private val PRIVATE_BULLETS = Regex("[\\uF0B7\\uF0A7\\uF076\\uF0D8\\uF0FC\\uF0A8\\uF06C\\uF06E\\uF0E0]")

    private val DIGITS = Regex("\\d+")

    /** Una riga è "corta" se finisce prima del margine destro di oltre questa frazione della colonna. */
    private const val SHORT_LINE = 0.15f

    /** Spazio verticale, in multipli dell'interlinea tipica, oltre il quale inizia un nuovo paragrafo. */
    private const val PARAGRAPH_GAP = 1.5f

    /** Differenza relativa di dimensione del carattere che separa un titolo dal testo. */
    private const val FONT_CHANGE = 0.1f

    /** Rientro minimo, in caratteri, per considerare rientrata la prima riga di un paragrafo. */
    private const val INDENT_CHARS = 1.5f

    /**
     * Normalizza il testo delle righe e toglie numeri di pagina, intestazioni e piè di pagina:
     * una riga che si ripete (cifre a parte) alla stessa altezza in almeno metà delle pagine.
     */
    fun clean(lines: List<PdfLine>, pageCount: Int): List<PdfLine> {
        val normalized = lines
            .map { it.copy(text = normalizeSpaces(PRIVATE_BULLETS.replace(it.text, "•"))) }
            .filter { it.text.isNotEmpty() && !isPageNumberLine(it.text) }
        val threshold = max(3, ceil(pageCount * 0.5).toInt())
        if (pageCount < 3) return normalized
        fun key(l: PdfLine) = DIGITS.replace(l.text.lowercase(), "#") + "@" + (l.y / 4).roundToInt()
        val pagesByKey = normalized.groupBy(::key).mapValues { (_, ls) -> ls.mapTo(HashSet()) { it.page }.size }
        return normalized.filter { pagesByKey.getValue(key(it)) < threshold }
    }

    /** Unisce le righe (già ripulite con [clean], in ordine di lettura) in paragrafi. */
    fun build(lines: List<PdfLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val layout = Layout(lines)
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        var paragraphIsListItem = false
        for ((i, line) in lines.withIndex()) {
            val prev = lines.getOrNull(i - 1)
            if (prev != null && startsParagraph(prev, line, layout, paragraphIsListItem)) {
                paragraphs += normalizeSpaces(current.toString())
                current.clear()
            }
            if (current.isEmpty()) paragraphIsListItem = LIST_MARKER.containsMatchIn(line.text) || DASH_MARKER.containsMatchIn(line.text)
            joinLine(current, line.text)
        }
        paragraphs += normalizeSpaces(current.toString())
        return paragraphs.filter { it.isNotEmpty() }
    }

    private fun startsParagraph(prev: PdfLine, cur: PdfLine, layout: Layout, inListItem: Boolean): Boolean {
        val endsSentence = SENTENCE_END.containsMatchIn(prev.text)
        if (LIST_MARKER.containsMatchIn(cur.text)) return true
        if (DASH_MARKER.containsMatchIn(cur.text) && endsSentence) return true

        val samePage = prev.page == cur.page
        if (samePage) {
            val gap = cur.y - prev.y
            if (gap > PARAGRAPH_GAP * layout.lineSpacing(cur.page) || gap < -prev.fontSize) return true
        }
        if (abs(cur.fontSize - prev.fontSize) > FONT_CHANGE * max(cur.fontSize, prev.fontSize)) return true
        if (!endsSentence) return false

        val (left, right) = layout.margins(prev.page)
        if (prev.xEnd < right - SHORT_LINE * (right - left)) return true
        // Prima riga rientrata (stile romanzo). Non vale dentro un punto elenco, dove le righe a capo
        // sono rientrate rispetto al pallino.
        val reference = if (samePage) minOf(prev.xStart, layout.margins(cur.page).first) else layout.margins(cur.page).first
        return !inListItem && cur.xStart > reference + INDENT_CHARS * layout.charWidth
    }

    /** Misure tipiche della pagina, ricavate dalle righe stesse. */
    private class Layout(lines: List<PdfLine>) {
        private val byPage = lines.groupBy { it.page }
        private val globalMargins = marginsOf(lines)
        private val pageMargins = HashMap<Int, Pair<Float, Float>>()
        private val pageSpacing = HashMap<Int, Float>()
        private val globalSpacing = byPage.values.flatMap(::spacings).takeIf { it.isNotEmpty() }?.let(::typicalSpacing)
            ?: (median(lines.map { it.fontSize })!! * 1.2f)

        val charWidth: Float = median(lines.filter { it.text.length >= 10 }.map { (it.xEnd - it.xStart) / it.text.length })
            ?: (median(lines.map { it.fontSize })!! * 0.5f)

        /** (margine sinistro, margine destro) della pagina; su pagine con poche righe si usano quelli del documento. */
        fun margins(page: Int): Pair<Float, Float> = pageMargins.getOrPut(page) {
            val ls = byPage[page].orEmpty()
            if (ls.size >= 5) marginsOf(ls) else globalMargins
        }

        fun lineSpacing(page: Int): Float = pageSpacing.getOrPut(page) {
            spacings(byPage[page].orEmpty()).takeIf { it.size >= 3 }?.let(::typicalSpacing) ?: globalSpacing
        }

        /** Salti verticali tra righe consecutive, esclusi quelli minimi (apici, frammenti sulla stessa riga). */
        private fun spacings(ls: List<PdfLine>): List<Float> =
            ls.zipWithNext { a, b -> b.y - a.y }.filterIndexed { i, gap -> gap > 0.5f * ls[i + 1].fontSize }

        /** Interlinea normale: un percentile basso, perché i salti tra paragrafi sono più ampi. */
        private fun typicalSpacing(values: List<Float>): Float = percentile(values, 0.3f)

        private fun marginsOf(ls: List<PdfLine>): Pair<Float, Float> =
            percentile(ls.map { it.xStart }, 0.1f) to percentile(ls.map { it.xEnd }, 0.9f)

        private fun percentile(values: List<Float>, p: Float): Float {
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p).roundToInt()]
        }

        private fun median(values: List<Float>): Float? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }
}
